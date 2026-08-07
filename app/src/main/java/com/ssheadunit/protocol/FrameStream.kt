package com.ssheadunit.protocol

import java.io.ByteArrayOutputStream

/**
 * Incremental decoder that turns the raw transport byte stream into [Frame]s.
 *
 * The USB transport is a stream: a bulk read may contain several frames or only a part of one,
 * so bytes are buffered until a complete frame is available.
 */
class FrameDecoder {

    private var buffer = ByteArray(0)

    fun feed(data: ByteArray, length: Int = data.size): List<Frame> {
        buffer = if (buffer.isEmpty()) {
            data.copyOfRange(0, length)
        } else {
            val merged = ByteArray(buffer.size + length)
            buffer.copyInto(merged)
            data.copyInto(merged, buffer.size, 0, length)
            merged
        }

        val frames = ArrayList<Frame>()
        var offset = 0
        while (true) {
            if (buffer.size - offset < FrameHeader.SIZE) break
            val header = FrameHeader.fromFlags(
                channelId = buffer[offset].toInt() and 0xFF,
                flags = buffer[offset + 1].toInt() and 0xFF
            )
            val payloadSize = ((buffer[offset + 2].toInt() and 0xFF) shl 8) or (buffer[offset + 3].toInt() and 0xFF)
            var payloadOffset = offset + FrameHeader.SIZE
            if (header.first && !header.last) {
                if (buffer.size - payloadOffset < 4) break
                payloadOffset += 4
            }
            if (buffer.size - payloadOffset < payloadSize) break
            frames += Frame(header, buffer.copyOfRange(payloadOffset, payloadOffset + payloadSize))
            offset = payloadOffset + payloadSize
        }
        if (offset > 0) {
            buffer = buffer.copyOfRange(offset, buffer.size)
        }
        return frames
    }

    fun reset() {
        buffer = ByteArray(0)
    }
}

/** Reassembles multi part messages, keeping one pending buffer per channel. */
class MessageAssembler {

    private val pending = HashMap<Int, ByteArrayOutputStream>()

    /** Returns the complete [Message] once the last fragment of a message arrived. */
    fun add(channelId: Int, encrypted: Boolean, first: Boolean, last: Boolean, payload: ByteArray): Message? {
        if (first && last) return Message(channelId, encrypted, payload)

        val buffer = if (first) {
            ByteArrayOutputStream().also { pending[channelId] = it }
        } else {
            pending[channelId] ?: return null
        }
        buffer.write(payload)
        if (!last) return null
        pending.remove(channelId)
        return Message(channelId, encrypted, buffer.toByteArray())
    }

    fun reset() {
        pending.clear()
    }
}

/** Prefixes [body] with the big endian 2 byte [messageId] expected by the phone. */
fun withMessageId(messageId: Int, body: ByteArray = ByteArray(0)): ByteArray {
    val out = ByteArray(body.size + 2)
    out[0] = ((messageId shr 8) and 0xFF).toByte()
    out[1] = (messageId and 0xFF).toByte()
    body.copyInto(out, 2)
    return out
}
