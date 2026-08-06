package com.tabletaa.protocol

import java.nio.ByteBuffer
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult

/**
 * TLS layer of the Android Auto protocol.
 *
 * The head unit is the TLS server: the phone opens the session, the handshake records are
 * tunnelled inside `SSL_HANDSHAKE` control messages and every later frame payload is encrypted
 * with the negotiated keys.
 */
class SslCryptor(sslContext: SSLContext) {

    private val engine: SSLEngine = sslContext.createSSLEngine().apply {
        useClientMode = false
        // The phone authenticates the head unit, not the other way around.
        needClientAuth = false
        wantClientAuth = false
    }

    private val packetSize = engine.session.packetBufferSize
    private val applicationSize = engine.session.applicationBufferSize
    private var inbound: ByteBuffer = ByteBuffer.allocate(packetSize * 4).apply { flip() }

    val isHandshakeComplete: Boolean
        get() = engine.handshakeStatus == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING ||
            engine.handshakeStatus == SSLEngineResult.HandshakeStatus.FINISHED

    /** Starts the handshake and returns the first records to send, if any. */
    fun startHandshake(): ByteArray {
        engine.beginHandshake()
        return handshake(null)
    }

    /**
     * Feeds handshake records received from the phone and returns the records to send back.
     */
    fun handshake(received: ByteArray?): ByteArray {
        if (received != null && received.isNotEmpty()) append(received)
        var output = ByteBuffer.allocate(packetSize * 4)
        val appBuffer = ByteBuffer.allocate(applicationSize)
        loop@ while (true) {
            when (engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    appBuffer.clear()
                    val result = engine.unwrap(inbound, appBuffer)
                    if (result.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) break@loop
                    if (result.status == SSLEngineResult.Status.CLOSED) break@loop
                }
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    output = ensureRemaining(output)
                    val result = engine.wrap(EMPTY, output)
                    if (result.status == SSLEngineResult.Status.CLOSED) break@loop
                }
                SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    while (true) {
                        val task = engine.delegatedTask ?: break
                        task.run()
                    }
                }
                else -> break@loop
            }
        }
        compactInbound()
        output.flip()
        return ByteArray(output.remaining()).also { output.get(it) }
    }

    /** Encrypts a frame payload. */
    fun encrypt(plain: ByteArray): ByteArray {
        val source = ByteBuffer.wrap(plain)
        var output = ByteBuffer.allocate(plain.size + packetSize)
        while (source.hasRemaining()) {
            output = ensureRemaining(output)
            val result = engine.wrap(source, output)
            if (result.status == SSLEngineResult.Status.CLOSED) throw IllegalStateException("TLS session closed")
        }
        output.flip()
        return ByteArray(output.remaining()).also { output.get(it) }
    }

    /** Decrypts a frame payload; returns an empty array when a record is still incomplete. */
    fun decrypt(cipher: ByteArray): ByteArray {
        append(cipher)
        var output = ByteBuffer.allocate(applicationSize + cipher.size)
        while (inbound.hasRemaining()) {
            output = ensureRemaining(output, applicationSize)
            val result = engine.unwrap(inbound, output)
            if (result.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) break
            if (result.status == SSLEngineResult.Status.CLOSED) break
            if (result.bytesConsumed() == 0 && result.bytesProduced() == 0) break
        }
        compactInbound()
        output.flip()
        return ByteArray(output.remaining()).also { output.get(it) }
    }

    fun close() {
        runCatching { engine.closeOutbound() }
    }

    private fun append(data: ByteArray) {
        // inbound is kept in "read" mode; grow it when the leftover plus new data does not fit.
        val required = inbound.remaining() + data.size
        if (required > inbound.capacity()) {
            val grown = ByteBuffer.allocate(required * 2)
            grown.put(inbound)
            grown.put(data)
            grown.flip()
            inbound = grown
            return
        }
        val leftover = ByteArray(inbound.remaining())
        inbound.get(leftover)
        inbound.clear()
        inbound.put(leftover)
        inbound.put(data)
        inbound.flip()
    }

    private fun compactInbound() {
        if (!inbound.hasRemaining()) {
            inbound.clear()
            inbound.flip()
        }
    }

    private fun ensureRemaining(buffer: ByteBuffer, needed: Int = packetSize): ByteBuffer {
        if (buffer.remaining() >= needed) return buffer
        val grown = ByteBuffer.allocate(buffer.capacity() + needed * 2)
        buffer.flip()
        grown.put(buffer)
        return grown
    }

    private companion object {
        val EMPTY: ByteBuffer = ByteBuffer.allocate(0)
    }
}
