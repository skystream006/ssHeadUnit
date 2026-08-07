package com.ssheadunit.protocol

/** Logical channels advertised by the head unit during service discovery. */
object ChannelId {
    const val CONTROL = 0
    const val INPUT = 1
    const val SENSOR = 2
    const val VIDEO = 3
    const val MEDIA_AUDIO = 4
    const val SPEECH_AUDIO = 5
    const val SYSTEM_AUDIO = 6
    const val AV_INPUT = 7
    const val BLUETOOTH = 8

    fun name(channelId: Int): String = when (channelId) {
        CONTROL -> "control"
        INPUT -> "input"
        SENSOR -> "sensor"
        VIDEO -> "video"
        MEDIA_AUDIO -> "media-audio"
        SPEECH_AUDIO -> "speech-audio"
        SYSTEM_AUDIO -> "system-audio"
        AV_INPUT -> "av-input"
        BLUETOOTH -> "bluetooth"
        else -> "channel-$channelId"
    }
}

/** Message identifiers exchanged on the control channel. */
object ControlMessage {
    const val VERSION_REQUEST = 0x0001
    const val VERSION_RESPONSE = 0x0002
    const val SSL_HANDSHAKE = 0x0003
    const val AUTH_COMPLETE = 0x0004
    const val SERVICE_DISCOVERY_REQUEST = 0x0005
    const val SERVICE_DISCOVERY_RESPONSE = 0x0006
    const val CHANNEL_OPEN_REQUEST = 0x0007
    const val CHANNEL_OPEN_RESPONSE = 0x0008
    const val PING_REQUEST = 0x000B
    const val PING_RESPONSE = 0x000C
    const val NAVIGATION_FOCUS_REQUEST = 0x000D
    const val NAVIGATION_FOCUS_RESPONSE = 0x000E
    const val SHUTDOWN_REQUEST = 0x000F
    const val SHUTDOWN_RESPONSE = 0x0010
    const val VOICE_SESSION_REQUEST = 0x0011
    const val AUDIO_FOCUS_REQUEST = 0x0012
    const val AUDIO_FOCUS_RESPONSE = 0x0013
}

/** Message identifiers exchanged on audio/video channels. */
object AvMessage {
    const val MEDIA_WITH_TIMESTAMP_INDICATION = 0x0000
    const val MEDIA_INDICATION = 0x0001
    const val SETUP_REQUEST = 0x8000
    const val START_INDICATION = 0x8001
    const val STOP_INDICATION = 0x8002
    const val SETUP_RESPONSE = 0x8003
    const val MEDIA_ACK_INDICATION = 0x8004
    const val AV_INPUT_OPEN_REQUEST = 0x8005
    const val AV_INPUT_OPEN_RESPONSE = 0x8006
    const val VIDEO_FOCUS_REQUEST = 0x8007
    const val VIDEO_FOCUS_INDICATION = 0x8008
}

/** Message identifiers exchanged on the input channel. */
object InputMessage {
    const val INPUT_EVENT_INDICATION = 0x8001
    const val BINDING_REQUEST = 0x8002
    const val BINDING_RESPONSE = 0x8003
}

/** Message identifiers exchanged on the sensor channel. */
object SensorMessage {
    const val SENSOR_START_REQUEST = 0x8001
    const val SENSOR_START_RESPONSE = 0x8002
    const val SENSOR_EVENT_INDICATION = 0x8003
}

/**
 * Header of a transport frame.
 *
 * Wire layout: `channel id (1) | flags (1) | payload size (2, big endian)`. Frames that start a
 * multi part message carry an additional big endian 4 byte total message size.
 */
data class FrameHeader(
    val channelId: Int,
    val first: Boolean,
    val last: Boolean,
    val control: Boolean,
    val encrypted: Boolean
) {
    val flags: Int
        get() {
            var value = 0
            if (first) value = value or FLAG_FIRST
            if (last) value = value or FLAG_LAST
            if (control) value = value or FLAG_CONTROL
            if (encrypted) value = value or FLAG_ENCRYPTED
            return value
        }

    companion object {
        const val SIZE = 4
        const val FLAG_FIRST = 1 shl 0
        const val FLAG_LAST = 1 shl 1
        const val FLAG_CONTROL = 1 shl 2
        const val FLAG_ENCRYPTED = 1 shl 3

        fun fromFlags(channelId: Int, flags: Int) = FrameHeader(
            channelId = channelId,
            first = flags and FLAG_FIRST != 0,
            last = flags and FLAG_LAST != 0,
            control = flags and FLAG_CONTROL != 0,
            encrypted = flags and FLAG_ENCRYPTED != 0
        )
    }
}

/**
 * A transport frame: a header plus (a fragment of) a message payload.
 *
 * [totalSize] is only used by the first frame of a multi part message and tells the phone how
 * many payload bytes the complete message has.
 */
data class Frame(val header: FrameHeader, val payload: ByteArray, val totalSize: Int = payload.size) {

    fun encode(): ByteArray {
        val hasTotalSize = header.first && !header.last
        val out = ByteArray(FrameHeader.SIZE + (if (hasTotalSize) 4 else 0) + payload.size)
        out[0] = header.channelId.toByte()
        out[1] = header.flags.toByte()
        out[2] = ((payload.size shr 8) and 0xFF).toByte()
        out[3] = (payload.size and 0xFF).toByte()
        var offset = FrameHeader.SIZE
        if (hasTotalSize) {
            writeInt(out, offset, totalSize)
            offset += 4
        }
        payload.copyInto(out, offset)
        return out
    }

    override fun equals(other: Any?): Boolean =
        other is Frame && other.header == header && other.totalSize == totalSize && other.payload.contentEquals(payload)

    override fun hashCode(): Int = 31 * (31 * header.hashCode() + payload.contentHashCode()) + totalSize

    companion object {
        /** Maximum payload size of a single frame, as negotiated with the phone. */
        const val MAX_PAYLOAD_SIZE = 0x4000

        private fun writeInt(out: ByteArray, offset: Int, value: Int) {
            out[offset] = ((value shr 24) and 0xFF).toByte()
            out[offset + 1] = ((value shr 16) and 0xFF).toByte()
            out[offset + 2] = ((value shr 8) and 0xFF).toByte()
            out[offset + 3] = (value and 0xFF).toByte()
        }

        /** Splits [payload] into frames that each fit into [MAX_PAYLOAD_SIZE]. */
        fun split(
            channelId: Int,
            payload: ByteArray,
            control: Boolean,
            encrypted: Boolean
        ): List<Frame> {
            if (payload.size <= MAX_PAYLOAD_SIZE) {
                return listOf(
                    Frame(FrameHeader(channelId, first = true, last = true, control = control, encrypted = encrypted), payload)
                )
            }
            val frames = ArrayList<Frame>()
            var offset = 0
            while (offset < payload.size) {
                val size = minOf(MAX_PAYLOAD_SIZE, payload.size - offset)
                val isFirst = offset == 0
                val isLast = offset + size == payload.size
                frames += Frame(
                    FrameHeader(channelId, first = isFirst, last = isLast, control = control, encrypted = encrypted),
                    payload.copyOfRange(offset, offset + size),
                    payload.size
                )
                offset += size
            }
            return frames
        }
    }
}

/** A fully reassembled message: the payload starts with a big endian 2 byte message id. */
class Message(val channelId: Int, val encrypted: Boolean, val payload: ByteArray) {
    val messageId: Int
        get() = if (payload.size >= 2) {
            ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        } else {
            -1
        }

    /** Payload without the leading message id. */
    fun body(): ByteArray = if (payload.size > 2) payload.copyOfRange(2, payload.size) else ByteArray(0)

    fun reader(): ProtoReader = ProtoReader(body())
}
