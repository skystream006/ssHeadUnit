package com.tabletaa.session

import com.tabletaa.protocol.AvMessage
import com.tabletaa.protocol.ChannelId
import com.tabletaa.protocol.ControlMessage
import com.tabletaa.protocol.Frame
import com.tabletaa.protocol.FrameDecoder
import com.tabletaa.protocol.InputMessage
import com.tabletaa.protocol.Message
import com.tabletaa.protocol.MessageAssembler
import com.tabletaa.protocol.Messages
import com.tabletaa.protocol.SensorMessage
import com.tabletaa.protocol.SslCryptor
import com.tabletaa.transport.Transport
import javax.net.ssl.SSLContext

/** Static description of the projected head unit, sent during service discovery. */
data class HeadUnitConfig(
    val headUnitName: String = "TabletAA",
    val carModel: String = "Universal",
    val carYear: String = "2024",
    val carSerial: String = "TabletAA-0001",
    val manufacturer: String = "TabletAA",
    val model: String = "TabletAA Head Unit",
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

/** Events emitted by the session towards the UI and the media pipeline. */
interface HeadUnitListener {
    fun onConnected() {}
    fun onDisconnected(reason: String) {}
    fun onVideoData(data: ByteArray, timestampNanos: Long) {}
    fun onAudioStart(channelId: Int, sampleRate: Int, channelCount: Int, bitDepth: Int) {}
    fun onAudioData(channelId: Int, data: ByteArray) {}
    fun onAudioStop(channelId: Int) {}
    fun onLog(message: String) {}
}

/**
 * Drives a complete Android Auto session: version negotiation, TLS handshake, service discovery,
 * channel setup and the media/input streams.
 */
class HeadUnitSession(
    private val transport: Transport,
    private val sslContext: SSLContext,
    private val config: HeadUnitConfig = HeadUnitConfig(),
    private val listener: HeadUnitListener
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

    /** Runs the session until the link is closed or [stop] is called. */
    fun run() {
        running = true
        val buffer = ByteArray(READ_BUFFER_SIZE)
        try {
            while (running) {
                val read = transport.receive(buffer, READ_TIMEOUT_MS)
                if (read <= 0) continue
                decoder.feed(buffer, read).forEach { handleFrame(it) }
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
            else -> listener.onLog("Ignoring message ${message.messageId} on ${ChannelId.name(message.channelId)}")
        }
    }

    private fun handleControl(message: Message) {
        when (message.messageId) {
            ControlMessage.VERSION_REQUEST -> {
                listener.onLog("Version request received")
                sendControl(Messages.versionResponse(PROTOCOL_MAJOR, PROTOCOL_MINOR, Messages.Status.OK), encrypted = false)
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
                    listener.onConnected()
                }
            }
            ControlMessage.SERVICE_DISCOVERY_REQUEST -> {
                listener.onLog("Service discovery request received")
                sendControl(serviceDiscoveryResponse())
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
            else -> listener.onLog("Unhandled control message 0x%04x".format(message.messageId))
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
            else -> listener.onLog("Unhandled video message 0x%04x".format(message.messageId))
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
            else -> listener.onLog("Unhandled audio message 0x%04x".format(message.messageId))
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

    private companion object {
        const val PROTOCOL_MAJOR = 1
        const val PROTOCOL_MINOR = 1
        const val MAX_UNACKED = 1
        const val READ_BUFFER_SIZE = 16384
        const val READ_TIMEOUT_MS = 1000
    }
}
