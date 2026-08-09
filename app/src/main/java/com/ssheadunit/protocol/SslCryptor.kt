package com.ssheadunit.protocol

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
class SslCryptor(
    sslContext: SSLContext,
    /**
     * Receives handshake diagnostics. Only called while the caller wants them, so the records are
     * not decoded at all during a normal session.
     */
    private val log: ((String) -> Unit)? = null,
    /**
     * Checked before any record is decoded, so a session running without debug logging does no
     * parsing work at all. Re-read each time because the setting can be toggled mid session.
     */
    private val logEnabled: () -> Boolean = { true }
) {

    private val engine: SSLEngine = sslContext.createSSLEngine().apply {
        useClientMode = false
        // The phone authenticates the head unit, not the other way around.
        needClientAuth = false
        wantClientAuth = false
    }

    private val incomingDescriber = TlsRecordDescriber("phone -> head unit")
    private val outgoingDescriber = TlsRecordDescriber("head unit -> phone")

    /** Guards against describing the completed handshake more than once. */
    private var summaryLogged = false

    private val packetSize = engine.session.packetBufferSize
    private val applicationSize = engine.session.applicationBufferSize
    private var inbound: ByteBuffer = ByteBuffer.allocate(packetSize * 4).apply { flip() }

    val isHandshakeComplete: Boolean
        get() = engine.handshakeStatus == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING ||
            engine.handshakeStatus == SSLEngineResult.HandshakeStatus.FINISHED

    /** Starts the handshake and returns the first records to send, if any. */
    fun startHandshake(): ByteArray {
        // The certificate is only selected once the peer's hello arrives, so it cannot be named yet.
        logSink()?.invoke("Starting TLS handshake as the TLS server; the phone must accept the head unit certificate")
        engine.beginHandshake()
        return handshake(null)
    }

    /**
     * Feeds handshake records received from the phone and returns the records to send back.
     */
    fun handshake(received: ByteArray?): ByteArray {
        if (received != null && received.isNotEmpty()) {
            describe(incomingDescriber, received)
            append(received)
        }
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
        val response = ByteArray(output.remaining()).also { output.get(it) }
        if (response.isNotEmpty()) describe(outgoingDescriber, response)
        logHandshakeSummary()
        return response
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

    private fun describe(describer: TlsRecordDescriber, records: ByteArray) {
        val sink = logSink() ?: return
        describer.describe(records).forEach(sink)
    }

    /** The sink to report to, or null when diagnostics are not wanted. */
    private fun logSink(): ((String) -> Unit)? = log?.takeIf { logEnabled() }

    /**
     * Reports the negotiated parameters and the certificates both sides ended up with, once. A
     * peer that accepted the head unit identity gets this far; one that refused it sends an alert
     * instead, which the record describer reports.
     */
    private fun logHandshakeSummary() {
        val sink = logSink() ?: return
        if (summaryLogged || !isHandshakeComplete) return
        summaryLogged = true
        val session = engine.session
        sink("TLS handshake complete: ${session.protocol} ${session.cipherSuite}")
        sink("Head unit presented ${describeLocalCertificates()}")
        sink("Phone presented ${describePeerCertificates()}")
    }

    /** The chain this head unit offers, i.e. the identity the phone has to accept. */
    private fun describeLocalCertificates(): String = runCatching {
        val chain = engine.session.localCertificates
        if (chain == null || chain.isEmpty()) return "no certificate (no key manager matched)"
        chain.mapIndexed { index, certificate ->
            "certificate #$index: ${describeEncodedCertificate(certificate.encoded)}"
        }.joinToString("; ")
    }.getOrElse { "an undescribable certificate chain (${it.message ?: it.javaClass.simpleName})" }

    private fun describePeerCertificates(): String = runCatching {
        val chain = engine.session.peerCertificates
        if (chain.isEmpty()) {
            "no certificate"
        } else {
            chain.mapIndexed { index, certificate ->
                "certificate #$index: ${describeEncodedCertificate(certificate.encoded)}"
            }.joinToString("; ")
        }
    }.getOrElse { "no certificate (client authentication is not requested)" }

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
