package com.ssheadunit.session

import com.ssheadunit.protocol.AvMessage
import com.ssheadunit.protocol.ChannelId
import com.ssheadunit.protocol.ControlMessage
import com.ssheadunit.protocol.Frame
import com.ssheadunit.protocol.FrameDecoder
import com.ssheadunit.protocol.InputMessage
import com.ssheadunit.protocol.Message
import com.ssheadunit.protocol.MessageAssembler
import com.ssheadunit.protocol.Messages
import com.ssheadunit.protocol.SensorMessage
import com.ssheadunit.protocol.SslCryptor
import com.ssheadunit.transport.Transport
import javax.net.ssl.SSLContext

/** Static description of the projected head unit, sent during service discovery. */
data class HeadUnitConfig(
    val headUnitName: String = "ssHeadUnit",
    val carModel: String = "Universal",
    val carYear: String = "2024",
    val carSerial: String = "ssHeadUnit-0001",
    val manufacturer: String = "ssHeadUnit",
    val model: String = "ssHeadUnit Head Unit",
    val softwareBuild: String = "1",
    val softwareVersion: String = "1.0",
    val video: Messages.VideoConfig = Messages.VideoConfig(
        resolution = Messages.VideoResolution.RES_720p,
        fps = Messages.VideoFps.FPS_60,
        dpi = 160
    ),
    val touchWidth: Int = 1280,
    val touchHeight: Int = 720,
    val nightMode: Boolean = false
)

/**
 * Stages a session goes through before it is projecting. They are reported to the UI so a stalled
 * connection can be attributed to a specific step instead of a frozen "connecting" message.
 */
enum class SessionPhase(val description: String) {
    WAITING_FOR_VERSION("Waiting for the phone to start Android Auto…"),
    HANDSHAKING("Performing the TLS handshake…"),
    DISCOVERY("Negotiating head unit services…"),
    PROJECTING("Connected")
}

/** Events emitted by the session towards the UI and the media pipeline. */
interface HeadUnitListener {
    fun onConnected() {}
    fun onDisconnected(reason: String) {}
    fun onPhase(phase: SessionPhase) {}
    fun onVideoData(data: ByteArray, timestampNanos: Long) {}
    fun onAudioStart(channelId: Int, sampleRate: Int, channelCount: Int, bitDepth: Int) {}
    fun onAudioData(channelId: Int, data: ByteArray) {}
    fun onAudioStop(channelId: Int) {}
    fun onLog(message: String) {}

    /** Unexpected but non fatal condition; always reaches logcat. */
    fun onWarning(message: String) {}
}

/**
 * Drives a complete Android Auto session: version negotiation, TLS handshake, service discovery,
 * channel setup and the media/input streams.
 *
 * Every stage is bounded: if the peer never asks for the protocol version, stalls during the TLS
 * handshake, or goes quiet after the session was established, the session ends with a descriptive
 * reason instead of blocking for ever.
 */
class HeadUnitSession(
    private val transport: Transport,
    private val sslContext: SSLContext,
    private val config: HeadUnitConfig = HeadUnitConfig(),
    private val listener: HeadUnitListener,
    /** Maximum time from opening the link to a completed TLS handshake. */
    private val handshakeTimeoutMs: Long = HANDSHAKE_TIMEOUT_MS,
    /** Maximum time an established session may stay silent. */
    private val idleTimeoutMs: Long = IDLE_TIMEOUT_MS,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private val decoder = FrameDecoder()
    private val assembler = MessageAssembler()
    private val cryptor = SslCryptor(sslContext)
    private val sendLock = Any()
    private val sessionIds = HashMap<Int, Int>()

    @Volatile
    private var running = false

    @Volatile
    private var authenticated = false

    @Volatile
    var phase: SessionPhase = SessionPhase.WAITING_FOR_VERSION
        private set

    /** Guards against re-announcing a phase, while still announcing the very first one. */
    private var phaseAnnounced = false

    /** Runs the session until the link is closed, a watchdog fires or [stop] is called. */
    fun run() {
        running = true
        val buffer = ByteArray(READ_BUFFER_SIZE)
        val startedAt = clock()
        var lastActivityAt = startedAt
        publishPhase(SessionPhase.WAITING_FOR_VERSION)
        try {
            while (running) {
                val read = transport.receive(buffer, READ_TIMEOUT_MS)
                if (read > 0) {
                    lastActivityAt = clock()
                    decoder.feed(buffer, read).forEach { handleFrame(it) }
                }
                checkWatchdogs(startedAt, lastActivityAt)
            }
        } catch (e: Exception) {
            listener.onDisconnected(e.message ?: e.javaClass.simpleName)
            return
        } finally {
            running = false
            cryptor.close()
        }
        listener.onDisconnected("session stopped")
    }

    /**
     * Ends the session when the handshake takes too long or an established session goes silent.
     * The thrown exception is turned into [HeadUnitListener.onDisconnected] by [run].
     */
    private fun checkWatchdogs(startedAt: Long, lastActivityAt: Long) {
        val now = clock()
        val failure = watchdogFailure(
            authenticated = authenticated,
            phase = phase,
            sinceStartMs = now - startedAt,
            sinceActivityMs = now - lastActivityAt,
            handshakeTimeoutMs = handshakeTimeoutMs,
            idleTimeoutMs = idleTimeoutMs
        )
        if (failure != null) throw IllegalStateException(failure)
    }

    private fun publishPhase(next: SessionPhase) {
        if (phaseAnnounced && phase == next) return
        phase = next
        phaseAnnounced = true
        listener.onPhase(next)
    }

    fun stop() {
        running = false
    }

    /** Sends a touch event to the phone; ignored until the input channel is authenticated. */
    fun sendTouchEvent(timestampNanos: Long, action: Int, actionIndex: Int, pointers: List<Triple<Int, Int, Int>>) {
        if (!authenticated) return
        runCatching {
            send(ChannelId.INPUT, Messages.touchEvent(timestampNanos, action, actionIndex, pointers))
        }
    }

    /** Tells the phone whether the display is currently in night mode. */
    fun sendNightMode(night: Boolean) {
        if (!authenticated) return
        runCatching { send(ChannelId.SENSOR, Messages.nightModeEvent(night)) }
    }

    // --- frame handling -----------------------------------------------------------------------

    private fun handleFrame(frame: Frame) {
        val payload = if (frame.header.encrypted) cryptor.decrypt(frame.payload) else frame.payload
        if (payload.isEmpty()) return
        val message = assembler.add(
            channelId = frame.header.channelId,
            encrypted = frame.header.encrypted,
            first = frame.header.first,
            last = frame.header.last,
            payload = payload
        ) ?: return
        dispatch(message)
    }

    private fun dispatch(message: Message) {
        when (message.channelId) {
            ChannelId.CONTROL -> handleControl(message)
            ChannelId.VIDEO -> handleVideo(message)
            ChannelId.MEDIA_AUDIO, ChannelId.SPEECH_AUDIO, ChannelId.SYSTEM_AUDIO -> handleAudio(message)
            ChannelId.INPUT -> handleInput(message)
            ChannelId.SENSOR -> handleSensor(message)
            else -> listener.onWarning(
                "Ignoring message 0x%04x on %s".format(message.messageId, ChannelId.name(message.channelId))
            )
        }
    }

    private fun handleControl(message: Message) {
        when (message.messageId) {
            ControlMessage.VERSION_REQUEST -> {
                val offered = Messages.parseVersionRequest(message)
                val (major, minor) = negotiateVersion(offered)
                listener.onLog(
                    "Version request received (offered=${offered?.let { "${it.first}.${it.second}" } ?: "unparsable"}," +
                        " answering $major.$minor)"
                )
                sendControl(Messages.versionResponse(major, minor, Messages.Status.OK), encrypted = false)
                publishPhase(SessionPhase.HANDSHAKING)
                val hello = cryptor.startHandshake()
                if (hello.isNotEmpty()) sendControl(Messages.sslHandshake(hello), encrypted = false)
            }
            ControlMessage.SSL_HANDSHAKE -> {
                val response = cryptor.handshake(Messages.parseSslHandshake(message))
                if (response.isNotEmpty()) sendControl(Messages.sslHandshake(response), encrypted = false)
                if (cryptor.isHandshakeComplete && !authenticated) {
                    authenticated = true
                    listener.onLog("TLS handshake complete")
                    sendControl(Messages.authComplete(), encrypted = false)
                    publishPhase(SessionPhase.DISCOVERY)
                    listener.onConnected()
                }
            }
            ControlMessage.SERVICE_DISCOVERY_REQUEST -> {
                listener.onLog("Service discovery request received")
                sendControl(serviceDiscoveryResponse())
                publishPhase(SessionPhase.PROJECTING)
            }
            ControlMessage.CHANNEL_OPEN_REQUEST -> {
                val channelId = Messages.parseChannelOpenRequest(message)
                listener.onLog("Channel open request for ${ChannelId.name(channelId)}")
                send(channelId, Messages.channelOpenResponse())
            }
            ControlMessage.PING_REQUEST -> sendControl(Messages.pingResponse(Messages.parsePingRequest(message)))
            ControlMessage.AUDIO_FOCUS_REQUEST -> {
                val request = Messages.parseAudioFocusRequest(message)
                val state = if (request == Messages.AudioFocusType.RELEASE) {
                    Messages.AudioFocusState.LOSS
                } else {
                    Messages.AudioFocusState.GAIN
                }
                sendControl(Messages.audioFocusResponse(state))
            }
            ControlMessage.NAVIGATION_FOCUS_REQUEST ->
                sendControl(Messages.navigationFocusResponse(Messages.parseNavigationFocusRequest(message)))
            ControlMessage.SHUTDOWN_REQUEST -> {
                listener.onLog("Shutdown requested by phone")
                sendControl(Messages.shutdownResponse())
                stop()
            }
            ControlMessage.SHUTDOWN_RESPONSE -> stop()
            else -> listener.onWarning("Unhandled control message 0x%04x".format(message.messageId))
        }
    }

    private fun handleVideo(message: Message) {
        when (message.messageId) {
            AvMessage.SETUP_REQUEST -> {
                send(ChannelId.VIDEO, Messages.avChannelSetupResponse(MAX_UNACKED, Messages.parseAvSetupRequest(message)))
                send(ChannelId.VIDEO, Messages.videoFocusIndication(Messages.VideoFocusMode.FOCUSED, unrequested = true))
            }
            AvMessage.START_INDICATION -> sessionIds[ChannelId.VIDEO] = Messages.parseAvStartIndication(message)
            AvMessage.STOP_INDICATION -> sessionIds.remove(ChannelId.VIDEO)
            AvMessage.MEDIA_WITH_TIMESTAMP_INDICATION -> {
                val (timestamp, data) = Messages.parseMediaWithTimestamp(message)
                listener.onVideoData(data, timestamp)
                acknowledge(ChannelId.VIDEO)
            }
            AvMessage.MEDIA_INDICATION -> {
                listener.onVideoData(message.body(), 0L)
                acknowledge(ChannelId.VIDEO)
            }
            AvMessage.VIDEO_FOCUS_REQUEST ->
                send(ChannelId.VIDEO, Messages.videoFocusIndication(Messages.parseVideoFocusRequest(message), unrequested = false))
            else -> listener.onWarning("Unhandled video message 0x%04x".format(message.messageId))
        }
    }

    private fun handleAudio(message: Message) {
        val channelId = message.channelId
        when (message.messageId) {
            AvMessage.SETUP_REQUEST ->
                send(channelId, Messages.avChannelSetupResponse(MAX_UNACKED, Messages.parseAvSetupRequest(message)))
            AvMessage.START_INDICATION -> {
                sessionIds[channelId] = Messages.parseAvStartIndication(message)
                val audio = audioConfigOf(channelId)
                listener.onAudioStart(channelId, audio.sampleRate, audio.channelCount, audio.bitDepth)
            }
            AvMessage.STOP_INDICATION -> {
                sessionIds.remove(channelId)
                listener.onAudioStop(channelId)
            }
            AvMessage.MEDIA_WITH_TIMESTAMP_INDICATION -> {
                val (_, data) = Messages.parseMediaWithTimestamp(message)
                listener.onAudioData(channelId, data)
                acknowledge(channelId)
            }
            AvMessage.MEDIA_INDICATION -> {
                listener.onAudioData(channelId, message.body())
                acknowledge(channelId)
            }
            else -> listener.onWarning("Unhandled audio message 0x%04x".format(message.messageId))
        }
    }

    private fun handleInput(message: Message) {
        if (message.messageId == InputMessage.BINDING_REQUEST) {
            send(ChannelId.INPUT, Messages.bindingResponse())
        }
    }

    private fun handleSensor(message: Message) {
        if (message.messageId == SensorMessage.SENSOR_START_REQUEST) {
            send(ChannelId.SENSOR, Messages.sensorStartResponse())
            send(ChannelId.SENSOR, Messages.drivingStatusEvent())
            send(ChannelId.SENSOR, Messages.nightModeEvent(config.nightMode))
        }
    }

    private fun acknowledge(channelId: Int) {
        val sessionId = sessionIds[channelId] ?: return
        send(channelId, Messages.mediaAck(sessionId))
    }

    private fun audioConfigOf(channelId: Int): Messages.AudioConfig = when (channelId) {
        ChannelId.MEDIA_AUDIO -> Messages.AudioConfig(48000, 16, 2)
        else -> Messages.AudioConfig(16000, 16, 1)
    }

    private fun serviceDiscoveryResponse(): ByteArray = Messages.serviceDiscoveryResponse(
        headUnitName = config.headUnitName,
        carModel = config.carModel,
        carYear = config.carYear,
        carSerial = config.carSerial,
        manufacturer = config.manufacturer,
        model = config.model,
        softwareBuild = config.softwareBuild,
        softwareVersion = config.softwareVersion,
        videoConfig = config.video,
        touchWidth = config.touchWidth,
        touchHeight = config.touchHeight
    )

    // --- sending ------------------------------------------------------------------------------

    private fun sendControl(payload: ByteArray, encrypted: Boolean = true) =
        send(ChannelId.CONTROL, payload, control = true, encrypted = encrypted)

    private fun send(channelId: Int, payload: ByteArray, control: Boolean = false, encrypted: Boolean = true) {
        synchronized(sendLock) {
            val frames = Frame.split(channelId, payload, control = control, encrypted = encrypted)
            val outgoing = if (encrypted) {
                frames.map { Frame(it.header, cryptor.encrypt(it.payload)) }
            } else {
                frames
            }
            val totalSize = outgoing.sumOf { it.payload.size }
            outgoing.forEach { transport.send(Frame(it.header, it.payload, totalSize).encode()) }
        }
    }

    /**
     * Answers with a version the peer can understand: the peer's own version when it fits within
     * what this head unit implements, and the head unit version otherwise.
     */
    private fun negotiateVersion(offered: Pair<Int, Int>?): Pair<Int, Int> {
        if (offered == null) return PROTOCOL_MAJOR to PROTOCOL_MINOR
        val (major, minor) = offered
        if (major != PROTOCOL_MAJOR) {
            listener.onWarning("Peer offered unsupported protocol major $major; answering $PROTOCOL_MAJOR.$PROTOCOL_MINOR")
            return PROTOCOL_MAJOR to PROTOCOL_MINOR
        }
        return PROTOCOL_MAJOR to minOf(minor, PROTOCOL_MINOR)
    }

    companion object {
        const val PROTOCOL_MAJOR = 1
        const val PROTOCOL_MINOR = 1

        /** Time allowed from opening the link to a completed TLS handshake. */
        const val HANDSHAKE_TIMEOUT_MS = 30_000L

        /** Time an established session may stay completely silent. */
        const val IDLE_TIMEOUT_MS = 30_000L

        private const val MAX_UNACKED = 1
        private const val READ_BUFFER_SIZE = 16384
        private const val READ_TIMEOUT_MS = 1000
    }
}

/**
 * Decides whether a session has stalled.
 *
 * Before the TLS handshake completes the whole connection is bounded by `handshakeTimeoutMs`, so a
 * peer that never sends a version request, or that stops answering during the handshake, cannot
 * keep the head unit waiting. Afterwards the session only has to stay alive: any frame, including
 * a ping, resets the idle timer.
 *
 * Returns the reason the session should end, or null when it is still healthy.
 */
internal fun watchdogFailure(
    authenticated: Boolean,
    phase: SessionPhase,
    sinceStartMs: Long,
    sinceActivityMs: Long,
    handshakeTimeoutMs: Long,
    idleTimeoutMs: Long
): String? = when {
    !authenticated && sinceStartMs >= handshakeTimeoutMs ->
        "Timed out after ${handshakeTimeoutMs / 1000}s: ${phase.description.trimEnd('…')}"
    authenticated && sinceActivityMs >= idleTimeoutMs ->
        "No data from the phone for ${idleTimeoutMs / 1000}s"
    else -> null
}
