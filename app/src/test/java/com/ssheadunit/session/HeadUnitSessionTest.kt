package com.ssheadunit.session

import com.ssheadunit.protocol.ChannelId
import com.ssheadunit.protocol.ControlMessage
import com.ssheadunit.protocol.Frame
import com.ssheadunit.protocol.FrameDecoder
import com.ssheadunit.protocol.ProtoWriter
import com.ssheadunit.protocol.withMessageId
import com.ssheadunit.transport.Transport
import com.ssheadunit.transport.TransportException
import java.io.ByteArrayOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the unencrypted part of the handshake through an in-memory transport. */
class HeadUnitSessionTest {

    private open class FakeTransport : Transport {
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

    /** Transport whose link breaks after the first read, like an unplugged or rebooting adapter. */
    private class DyingTransport : FakeTransport() {
        private var reads = 0

        override fun receive(buffer: ByteArray, timeoutMs: Int): Int {
            if (reads++ > 0) throw TransportException("USB read failed 10 times in a row; link lost")
            return super.receive(buffer, timeoutMs)
        }
    }

    private fun sslContext() = SSLContext.getInstance("TLSv1.2").apply { init(null, null, null) }

    private fun runSession(
        transport: Transport,
        handshakeTimeoutMs: Long = HeadUnitSession.HANDSHAKE_TIMEOUT_MS,
        idleTimeoutMs: Long = HeadUnitSession.IDLE_TIMEOUT_MS
    ): String? {
        val captured = AtomicReference<String?>()
        val session = HeadUnitSession(
            transport = transport,
            sslContext = sslContext(),
            listener = object : HeadUnitListener {
                override fun onDisconnected(reason: String) {
                    captured.compareAndSet(null, reason)
                }
            },
            handshakeTimeoutMs = handshakeTimeoutMs,
            idleTimeoutMs = idleTimeoutMs
        )
        val worker = Thread { session.run() }
        worker.start()
        worker.join(10_000)
        session.stop()
        worker.join(2_000)
        return captured.get()
    }

    @Test
    fun answersVersionRequestInTheClear() {
        val transport = FakeTransport()
        val session = HeadUnitSession(
            transport = transport,
            sslContext = sslContext(),
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

    @Test
    fun aSilentPeerEndsTheSessionInsteadOfHanging() {
        val reason = runSession(FakeTransport(), handshakeTimeoutMs = 1_500L)
        assertNotNull("a silent peer must end the session", reason)
        assertTrue("unexpected reason: $reason", reason!!.contains("Timed out"))
    }

    @Test
    fun aBrokenLinkEndsTheSession() {
        val reason = runSession(DyingTransport(), handshakeTimeoutMs = 60_000L)
        assertNotNull("a broken link must end the session", reason)
        assertTrue("unexpected reason: $reason", reason!!.contains("link lost"))
    }

    @Test
    fun theHandshakeWatchdogOnlyFiresBeforeAuthentication() {
        assertNull(
            watchdogFailure(
                authenticated = false, phase = SessionPhase.HANDSHAKING,
                sinceStartMs = 999L, sinceActivityMs = 999L,
                handshakeTimeoutMs = 1_000L, idleTimeoutMs = 1_000L
            )
        )
        val failure = watchdogFailure(
            authenticated = false, phase = SessionPhase.HANDSHAKING,
            sinceStartMs = 1_000L, sinceActivityMs = 0L,
            handshakeTimeoutMs = 1_000L, idleTimeoutMs = 60_000L
        )
        assertNotNull(failure)
        assertTrue("unexpected reason: $failure", failure!!.contains("TLS handshake"))
    }

    @Test
    fun theIdleWatchdogFiresOnAnEstablishedSession() {
        assertNull(
            watchdogFailure(
                authenticated = true, phase = SessionPhase.PROJECTING,
                sinceStartMs = 600_000L, sinceActivityMs = 100L,
                handshakeTimeoutMs = 1_000L, idleTimeoutMs = 5_000L
            )
        )
        val failure = watchdogFailure(
            authenticated = true, phase = SessionPhase.PROJECTING,
            sinceStartMs = 600_000L, sinceActivityMs = 5_000L,
            handshakeTimeoutMs = 1_000L, idleTimeoutMs = 5_000L
        )
        assertNotNull(failure)
        assertTrue("unexpected reason: $failure", failure!!.contains("No data"))
    }
}
