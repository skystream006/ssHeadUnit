package com.tabletaa.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FramingTest {

    @Test
    fun encodesSingleFrameWithoutTotalSize() {
        val payload = byteArrayOf(1, 2, 3)
        val frame = Frame(
            FrameHeader(ChannelId.CONTROL, first = true, last = true, control = true, encrypted = false),
            payload
        )
        val encoded = frame.encode()
        assertEquals(FrameHeader.SIZE + payload.size, encoded.size)
        assertEquals(ChannelId.CONTROL, encoded[0].toInt())
        assertEquals(FrameHeader.FLAG_FIRST or FrameHeader.FLAG_LAST or FrameHeader.FLAG_CONTROL, encoded[1].toInt())
        assertEquals(3, encoded[3].toInt())
    }

    @Test
    fun splitsLargePayloadsAndCarriesTotalSize() {
        val payload = ByteArray(Frame.MAX_PAYLOAD_SIZE + 100) { (it % 251).toByte() }
        val frames = Frame.split(ChannelId.VIDEO, payload, control = false, encrypted = true)

        assertEquals(2, frames.size)
        assertTrue(frames.first().header.first && !frames.first().header.last)
        assertTrue(!frames.last().header.first && frames.last().header.last)
        assertEquals(payload.size, frames.first().totalSize)
        assertEquals(FrameHeader.SIZE + 4 + Frame.MAX_PAYLOAD_SIZE, frames.first().encode().size)
    }

    @Test
    fun decoderReassemblesStreamAcrossReadBoundaries() {
        val payload = ByteArray(Frame.MAX_PAYLOAD_SIZE + 512) { (it % 97).toByte() }
        val stream = Frame.split(ChannelId.VIDEO, payload, control = false, encrypted = false)
            .flatMap { it.encode().asList() }
            .toByteArray()

        val decoder = FrameDecoder()
        val assembler = MessageAssembler()
        var message: Message? = null
        var offset = 0
        while (offset < stream.size) {
            val chunk = minOf(1000, stream.size - offset)
            decoder.feed(stream.copyOfRange(offset, offset + chunk)).forEach { frame ->
                assembler.add(
                    frame.header.channelId, frame.header.encrypted,
                    frame.header.first, frame.header.last, frame.payload
                )?.let { message = it }
            }
            offset += chunk
        }

        assertNotNull(message)
        assertEquals(ChannelId.VIDEO, message!!.channelId)
        assertArrayEquals(payload, message!!.payload)
    }

    @Test
    fun decoderWaitsForIncompleteFrames() {
        val encoded = Frame(
            FrameHeader(ChannelId.CONTROL, first = true, last = true, control = true, encrypted = false),
            byteArrayOf(9, 9, 9, 9)
        ).encode()

        val decoder = FrameDecoder()
        assertTrue(decoder.feed(encoded.copyOfRange(0, 5)).isEmpty())
        assertEquals(1, decoder.feed(encoded.copyOfRange(5, encoded.size)).size)
    }

    @Test
    fun assemblerIgnoresContinuationWithoutStart() {
        val assembler = MessageAssembler()
        assertNull(assembler.add(ChannelId.VIDEO, false, first = false, last = true, payload = byteArrayOf(1)))
    }

    @Test
    fun messageExposesIdAndBody() {
        val message = Message(ChannelId.CONTROL, false, withMessageId(ControlMessage.PING_REQUEST, byteArrayOf(7)))
        assertEquals(ControlMessage.PING_REQUEST, message.messageId)
        assertArrayEquals(byteArrayOf(7), message.body())
    }
}
