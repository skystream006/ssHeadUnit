package com.tabletaa.protocol

/**
 * Builders and parsers for the Android Auto protocol messages the head unit needs to speak.
 *
 * The field numbers below follow the publicly documented Android Auto protocol as implemented by
 * the open source `aasdk` project; they are kept in one place so they are easy to audit.
 */
object Messages {

    // --- enumerations -------------------------------------------------------------------------

    object Status {
        const val OK = 0
        const val FAIL = 1
    }

    object StreamType {
        const val AUDIO = 1
        const val VIDEO = 3
    }

    object AudioType {
        const val NONE = 0
        const val SPEECH = 1
        const val SYSTEM = 2
        const val MEDIA = 3
        const val ALARM = 4
    }

    object VideoResolution {
        const val RES_480p = 1
        const val RES_720p = 2
        const val RES_1080p = 3
    }

    object VideoFps {
        const val FPS_30 = 1
        const val FPS_60 = 2
    }

    object SensorType {
        const val DRIVING_STATUS = 13
        const val NIGHT_DATA = 10
    }

    object DrivingStatus {
        const val UNRESTRICTED = 0
    }

    object AudioFocusState {
        const val GAIN = 1
        const val GAIN_TRANSIENT = 2
        const val LOSS = 3
        const val LOSS_TRANSIENT_CAN_DUCK = 4
        const val LOSS_TRANSIENT = 5
    }

    object AudioFocusType {
        const val RELEASE = 3
    }

    object VideoFocusMode {
        const val FOCUSED = 1
        const val UNFOCUSED = 2
    }

    object TouchAction {
        const val PRESS = 0
        const val RELEASE = 1
        const val DRAG = 2
    }

    /** Audio stream parameters of a single audio channel. */
    data class AudioConfig(val sampleRate: Int, val bitDepth: Int, val channelCount: Int)

    /** Video stream parameters of the projected display. */
    data class VideoConfig(
        val resolution: Int,
        val fps: Int,
        val marginWidth: Int = 0,
        val marginHeight: Int = 0,
        val dpi: Int = 160
    )

    // --- head unit -> phone -------------------------------------------------------------------

    /**
     * Version response: three big endian 16 bit values (major, minor, status). This message is
     * not protobuf encoded.
     */
    fun versionResponse(major: Int, minor: Int, status: Int): ByteArray {
        val body = ByteArray(6)
        body[0] = ((major shr 8) and 0xFF).toByte()
        body[1] = (major and 0xFF).toByte()
        body[2] = ((minor shr 8) and 0xFF).toByte()
        body[3] = (minor and 0xFF).toByte()
        body[4] = ((status shr 8) and 0xFF).toByte()
        body[5] = (status and 0xFF).toByte()
        return withMessageId(ControlMessage.VERSION_RESPONSE, body)
    }

    fun sslHandshake(handshakeData: ByteArray): ByteArray =
        withMessageId(ControlMessage.SSL_HANDSHAKE, ProtoWriter().bytes(1, handshakeData).toByteArray())

    fun parseSslHandshake(message: Message): ByteArray =
        message.reader().findBytes(1) ?: ByteArray(0)

    fun authComplete(status: Int = Status.OK): ByteArray =
        withMessageId(ControlMessage.AUTH_COMPLETE, ProtoWriter().enum(1, status).toByteArray())

    /** Full service discovery response advertising video, audio, input and sensor channels. */
    fun serviceDiscoveryResponse(
        headUnitName: String,
        carModel: String,
        carYear: String,
        carSerial: String,
        manufacturer: String,
        model: String,
        softwareBuild: String,
        softwareVersion: String,
        videoConfig: VideoConfig,
        touchWidth: Int,
        touchHeight: Int
    ): ByteArray {
        val writer = ProtoWriter()

        // Video channel.
        writer.message(1) {
            int32(1, ChannelId.VIDEO)
            message(3) {
                enum(1, StreamType.VIDEO)
                message(4) {
                    enum(1, videoConfig.resolution)
                    enum(2, videoConfig.fps)
                    int32(3, videoConfig.marginWidth)
                    int32(4, videoConfig.marginHeight)
                    int32(5, videoConfig.dpi)
                }
                bool(5, true)
            }
        }

        // Audio channels: media, speech and system.
        writer.audioChannel(ChannelId.MEDIA_AUDIO, AudioType.MEDIA, AudioConfig(48000, 16, 2))
        writer.audioChannel(ChannelId.SPEECH_AUDIO, AudioType.SPEECH, AudioConfig(16000, 16, 1))
        writer.audioChannel(ChannelId.SYSTEM_AUDIO, AudioType.SYSTEM, AudioConfig(16000, 16, 1))

        // Touch screen input.
        writer.message(1) {
            int32(1, ChannelId.INPUT)
            message(4) {
                message(2) {
                    int32(1, touchWidth)
                    int32(2, touchHeight)
                }
            }
        }

        // Sensors: the phone requires at least driving status and night mode.
        writer.message(1) {
            int32(1, ChannelId.SENSOR)
            message(2) {
                message(1) { enum(1, SensorType.DRIVING_STATUS) }
                message(1) { enum(1, SensorType.NIGHT_DATA) }
            }
        }

        writer.string(2, headUnitName)
        writer.string(3, carModel)
        writer.string(4, carYear)
        writer.string(5, carSerial)
        writer.bool(6, true)
        writer.string(7, manufacturer)
        writer.string(8, model)
        writer.string(9, softwareBuild)
        writer.string(10, softwareVersion)
        writer.bool(11, true)

        return withMessageId(ControlMessage.SERVICE_DISCOVERY_RESPONSE, writer.toByteArray())
    }

    private fun ProtoWriter.audioChannel(channelId: Int, audioType: Int, config: AudioConfig) {
        message(1) {
            int32(1, channelId)
            message(3) {
                enum(1, StreamType.AUDIO)
                enum(2, audioType)
                message(3) {
                    int32(1, config.sampleRate)
                    int32(2, config.bitDepth)
                    int32(3, config.channelCount)
                }
                bool(5, true)
            }
        }
    }

    fun channelOpenResponse(status: Int = Status.OK): ByteArray =
        withMessageId(ControlMessage.CHANNEL_OPEN_RESPONSE, ProtoWriter().enum(1, status).toByteArray())

    fun pingResponse(timestampNanos: Long): ByteArray =
        withMessageId(ControlMessage.PING_RESPONSE, ProtoWriter().varint(1, timestampNanos).toByteArray())

    fun audioFocusResponse(state: Int): ByteArray =
        withMessageId(ControlMessage.AUDIO_FOCUS_RESPONSE, ProtoWriter().enum(1, state).toByteArray())

    fun navigationFocusResponse(type: Int): ByteArray =
        withMessageId(ControlMessage.NAVIGATION_FOCUS_RESPONSE, ProtoWriter().enum(1, type).toByteArray())

    fun shutdownRequest(reason: Int = 1): ByteArray =
        withMessageId(ControlMessage.SHUTDOWN_REQUEST, ProtoWriter().enum(1, reason).toByteArray())

    fun shutdownResponse(): ByteArray = withMessageId(ControlMessage.SHUTDOWN_RESPONSE)

    fun avChannelSetupResponse(maxUnacked: Int, configIndex: Int): ByteArray =
        withMessageId(
            AvMessage.SETUP_RESPONSE,
            ProtoWriter()
                .enum(1, Status.OK)
                .int32(2, maxUnacked)
                .int32(3, configIndex)
                .toByteArray()
        )

    fun videoFocusIndication(mode: Int, unrequested: Boolean): ByteArray =
        withMessageId(
            AvMessage.VIDEO_FOCUS_INDICATION,
            ProtoWriter().enum(1, mode).bool(2, unrequested).toByteArray()
        )

    fun mediaAck(sessionId: Int): ByteArray =
        withMessageId(
            AvMessage.MEDIA_ACK_INDICATION,
            ProtoWriter().int32(1, sessionId).int32(2, 1).toByteArray()
        )

    fun bindingResponse(status: Int = Status.OK): ByteArray =
        withMessageId(InputMessage.BINDING_RESPONSE, ProtoWriter().enum(1, status).toByteArray())

    fun sensorStartResponse(status: Int = Status.OK): ByteArray =
        withMessageId(SensorMessage.SENSOR_START_RESPONSE, ProtoWriter().enum(1, status).toByteArray())

    fun drivingStatusEvent(status: Int = DrivingStatus.UNRESTRICTED): ByteArray =
        withMessageId(
            SensorMessage.SENSOR_EVENT_INDICATION,
            ProtoWriter().message(11) { enum(1, status) }.toByteArray()
        )

    fun nightModeEvent(night: Boolean): ByteArray =
        withMessageId(
            SensorMessage.SENSOR_EVENT_INDICATION,
            ProtoWriter().message(10) { bool(1, night) }.toByteArray()
        )

    /** Encodes a touch event (single or multi pointer) for the input channel. */
    fun touchEvent(timestampNanos: Long, action: Int, actionIndex: Int, pointers: List<Triple<Int, Int, Int>>): ByteArray {
        val writer = ProtoWriter()
        writer.varint(1, timestampNanos)
        writer.message(2) {
            pointers.forEach { (x, y, pointerId) ->
                message(1) {
                    int32(1, x)
                    int32(2, y)
                    int32(3, pointerId)
                }
            }
            int32(2, actionIndex)
            enum(3, action)
        }
        return withMessageId(InputMessage.INPUT_EVENT_INDICATION, writer.toByteArray())
    }

    // --- phone -> head unit -------------------------------------------------------------------

    /** Channel id requested in a channel open request. */
    fun parseChannelOpenRequest(message: Message): Int = message.reader().findVarint(2)?.toInt() ?: -1

    /** Configuration index requested in an A/V channel setup request. */
    fun parseAvSetupRequest(message: Message): Int = message.reader().findVarint(1)?.toInt() ?: 0

    /** Session id carried by an A/V channel start indication. */
    fun parseAvStartIndication(message: Message): Int = message.reader().findVarint(1)?.toInt() ?: 0

    fun parsePingRequest(message: Message): Long = message.reader().findVarint(1) ?: 0L

    fun parseAudioFocusRequest(message: Message): Int = message.reader().findVarint(1)?.toInt() ?: 0

    fun parseNavigationFocusRequest(message: Message): Int = message.reader().findVarint(1)?.toInt() ?: 1

    fun parseVideoFocusRequest(message: Message): Int =
        message.reader().findVarint(2)?.toInt() ?: VideoFocusMode.FOCUSED

    /** Splits a media-with-timestamp payload into its timestamp and media bytes. */
    fun parseMediaWithTimestamp(message: Message): Pair<Long, ByteArray> {
        val body = message.body()
        if (body.size < 8) return 0L to ByteArray(0)
        var timestamp = 0L
        for (i in 0 until 8) {
            timestamp = (timestamp shl 8) or (body[i].toLong() and 0xFF)
        }
        return timestamp to body.copyOfRange(8, body.size)
    }
}
