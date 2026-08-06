package com.tabletaa.session

import com.tabletaa.protocol.ChannelId
import com.tabletaa.protocol.ControlMessage
import com.tabletaa.protocol.Frame
import com.tabletaa.protocol.FrameDecoder
import com.tabletaa.protocol.ProtoWriter
import com.tabletaa.protocol.withMessageId
import com.tabletaa.transport.Transport
import java.io.ByteArrayOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the unencrypted part of the handshake through an in-memory transport. */
class HeadUnitSessionTest {

    private class FakeTransport : Transport {
        private val incoming = LinkedBlockingQueue<ByteArray>()
        private val sent = ByteArrayOutputStream()

        fun deliver(data: ByteArray) = incoming.put(data)

        override fun send(data: ByteArray, timeoutMs: Int) {
            synchronized(sent) { sent.write(data) }
        }

        override fun receive(buffer: ByteArray, timeoutMs: Int): Int {
            val data = incoming.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return 0
            data.copyInto(buffer)
            return data.size
        }

        override fun close() = Unit

        fun sentBytes(): ByteArray = synchronized(sent) { sent.toByteArray() }
    }

    @Test
    fun answersVersionRequestInTheClear() {
        val transport = FakeTransport()
        val session = HeadUnitSession(
            transport = transport,
            sslContext = SSLContext.getInstance("TLSv1.2").apply { init(null, null, null) },
            listener = object : HeadUnitListener {}
        )
        val worker = Thread { session.run() }
        worker.start()

        val versionRequest = withMessageId(
            ControlMessage.VERSION_REQUEST,
            ProtoWriter().int32(1, 1).int32(2, 1).toByteArray()
        )
        transport.deliver(
            Frame.split(ChannelId.CONTROL, versionRequest, control = true, encrypted = false)
                .flatMap { it.encode().asList() }
                .toByteArray()
        )

        val deadline = System.currentTimeMillis() + 5000
        var response: List<Frame> = emptyList()
        while (System.currentTimeMillis() < deadline && response.isEmpty()) {
            Thread.sleep(20)
            response = FrameDecoder().feed(transport.sentBytes())
        }
        session.stop()
        worker.join(2000)

        assertTrue("expected a version response", response.isNotEmpty())
        val frame = response.first()
        assertEquals(ChannelId.CONTROL, frame.header.channelId)
        assertTrue(frame.header.control)
        assertTrue("version response must be sent in the clear", !frame.header.encrypted)
        val messageId = ((frame.payload[0].toInt() and 0xFF) shl 8) or (frame.payload[1].toInt() and 0xFF)
        assertEquals(ControlMessage.VERSION_RESPONSE, messageId)
    }
}
