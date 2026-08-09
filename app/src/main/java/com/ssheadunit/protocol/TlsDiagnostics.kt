package com.ssheadunit.protocol

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.security.auth.x500.X500Principal

/**
 * Turns the raw TLS records tunnelled inside `SSL_HANDSHAKE` control messages into readable
 * diagnostic lines.
 *
 * A phone only projects to a head unit whose certificate it accepts, and a rejection is otherwise
 * invisible: the link simply stops progressing. Describing the handshake shows exactly when each
 * certificate is exchanged, which certificate authorities the peer says it expects, and the alert
 * a peer sends when it refuses the head unit identity.
 *
 * Nothing here may throw: diagnostics must never break a session, so a record that cannot be
 * parsed is reported as such instead.
 */
class TlsRecordDescriber(private val direction: String) {

    /**
     * Records after a `ChangeCipherSpec` are encrypted, so their content can no longer be parsed.
     * Tracked per direction because each side switches independently.
     */
    private var cipherActive = false

    /**
     * A handshake message may be split across records, and records across `SSL_HANDSHAKE`
     * messages, so incomplete trailing bytes are carried over instead of being reported as
     * garbage.
     */
    private var pendingHandshake = ByteArray(0)

    /** Describes every record in [records]; returns one line per record or handshake message. */
    fun describe(records: ByteArray): List<String> = runCatching { parseRecords(records) }
        .getOrElse { listOf("$direction: ${records.size} bytes (undecodable: ${it.messageOrType()})") }

    private fun parseRecords(records: ByteArray): List<String> {
        if (records.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        var offset = 0
        while (offset + RECORD_HEADER_SIZE <= records.size) {
            val type = records[offset].toInt() and 0xFF
            val version = tlsVersionName(records.u16(offset + 1))
            val length = records.u16(offset + 3)
            val bodyStart = offset + RECORD_HEADER_SIZE
            val bodyEnd = bodyStart + length
            if (length < 0 || bodyEnd > records.size) {
                lines += "$direction: truncated ${recordTypeName(type)} record ($version," +
                    " declared $length bytes, ${records.size - bodyStart} available)"
                return lines
            }
            val body = records.copyOfRange(bodyStart, bodyEnd)
            when {
                type == RECORD_HANDSHAKE && cipherActive ->
                    lines += "$direction: encrypted handshake record ($version, $length bytes)"
                type == RECORD_HANDSHAKE -> lines += drainHandshake(body)
                type == RECORD_CHANGE_CIPHER_SPEC -> {
                    lines += "$direction: ChangeCipherSpec ($version); later records are encrypted"
                    cipherActive = true
                }
                type == RECORD_ALERT && cipherActive ->
                    lines += "$direction: encrypted alert record ($version, $length bytes)"
                type == RECORD_ALERT -> lines += "$direction: ${describeAlert(body)}"
                type == RECORD_APPLICATION_DATA ->
                    lines += "$direction: application data ($version, $length bytes)"
                else -> lines += "$direction: ${recordTypeName(type)} record ($version, $length bytes)"
            }
            offset = bodyEnd
        }
        if (offset < records.size) {
            lines += "$direction: ${records.size - offset} trailing bytes are not a complete record header"
        }
        return lines
    }

    /**
     * Describes every handshake message that is complete once [body] is appended, keeping any
     * partial trailing message for the next record. Messages are described where they occur so
     * the log keeps the order the peer actually sent them in.
     */
    private fun drainHandshake(body: ByteArray): List<String> {
        val messages = if (pendingHandshake.isEmpty()) body else pendingHandshake + body
        val lines = mutableListOf<String>()
        var offset = 0
        while (offset + HANDSHAKE_HEADER_SIZE <= messages.size) {
            val type = messages[offset].toInt() and 0xFF
            val length = messages.u24(offset + 1)
            val bodyStart = offset + HANDSHAKE_HEADER_SIZE
            val bodyEnd = bodyStart + length
            val name = handshakeTypeName(type)
            if (bodyEnd > messages.size) {
                pendingHandshake = messages.copyOfRange(offset, messages.size)
                lines += "$direction: $name continues in a later record ($length bytes declared," +
                    " ${messages.size - bodyStart} available)"
                return lines
            }
            val body = messages.copyOfRange(bodyStart, bodyEnd)
            lines += "$direction: $name ($length bytes)"
            when (type) {
                HANDSHAKE_CERTIFICATE -> lines += describeCertificateMessage(body)
                HANDSHAKE_CERTIFICATE_REQUEST -> lines += describeCertificateRequest(body)
            }
            offset = bodyEnd
        }
        pendingHandshake = if (offset < messages.size) messages.copyOfRange(offset, messages.size) else ByteArray(0)
        return lines
    }

    /**
     * `Certificate` carries the chain the peer offers, so this is the moment a certificate is
     * actually exchanged. Each entry is described by identity and fingerprint.
     */
    private fun describeCertificateMessage(body: ByteArray): List<String> {
        if (body.size < LENGTH_24_SIZE) return listOf("  chain is empty or truncated")
        val declared = body.u24(0)
        val end = minOf(LENGTH_24_SIZE + declared, body.size)
        val lines = mutableListOf<String>()
        var offset = LENGTH_24_SIZE
        var index = 0
        while (offset + LENGTH_24_SIZE <= end) {
            val length = body.u24(offset)
            val start = offset + LENGTH_24_SIZE
            if (start + length > end) {
                lines += "  certificate #$index is truncated ($length bytes declared)"
                break
            }
            lines += "  certificate #$index: ${describeEncodedCertificate(body.copyOfRange(start, start + length))}"
            offset = start + length
            index++
        }
        if (index == 0) lines += "  chain is empty; the peer offered no certificate"
        return lines
    }

    /**
     * `CertificateRequest` is the peer stating which certificates it expects: the key types it
     * accepts and, most usefully, the certificate authorities it is willing to chain to.
     */
    private fun describeCertificateRequest(body: ByteArray): List<String> {
        val lines = mutableListOf<String>()
        if (body.isEmpty()) return listOf("  empty request")
        var offset = 0
        val typeCount = body[offset].toInt() and 0xFF
        offset++
        if (offset + typeCount > body.size) return listOf("  truncated certificate type list")
        val types = (0 until typeCount).map { clientCertificateTypeName(body[offset + it].toInt() and 0xFF) }
        offset += typeCount
        lines += "  accepted key types: ${types.joinToString().ifEmpty { "none" }}"

        // TLS 1.2 inserts the supported signature algorithms before the authority list.
        if (offset + LENGTH_16_SIZE <= body.size) {
            val signatureLength = body.u16(offset)
            offset += LENGTH_16_SIZE
            if (offset + signatureLength <= body.size) {
                lines += "  accepted signature algorithms: ${signatureLength / 2}"
                offset += signatureLength
            } else {
                return lines + "  truncated signature algorithm list"
            }
        }

        if (offset + LENGTH_16_SIZE > body.size) return lines + "  no certificate authority list"
        val authoritiesLength = body.u16(offset)
        offset += LENGTH_16_SIZE
        val end = minOf(offset + authoritiesLength, body.size)
        val authorities = mutableListOf<String>()
        while (offset + LENGTH_16_SIZE <= end) {
            val length = body.u16(offset)
            val start = offset + LENGTH_16_SIZE
            if (start + length > end) break
            authorities += describeDistinguishedName(body.copyOfRange(start, start + length))
            offset = start + length
        }
        lines += if (authorities.isEmpty()) {
            "  expects a certificate from any authority (no authority list sent)"
        } else {
            "  expects a certificate issued by one of ${authorities.size} authorities"
        }
        authorities.forEach { lines += "    authority: $it" }
        return lines
    }

    private fun describeAlert(body: ByteArray): String {
        if (body.size < 2) return "alert record (${body.size} bytes, too short to decode)"
        val level = when (val value = body[0].toInt() and 0xFF) {
            1 -> "warning"
            2 -> "fatal"
            else -> "level $value"
        }
        val code = body[1].toInt() and 0xFF
        return "$level alert: ${alertDescriptionName(code)} ($code)"
    }

    private fun Throwable.messageOrType() = message ?: javaClass.simpleName

    private companion object {
        const val RECORD_HEADER_SIZE = 5
        const val HANDSHAKE_HEADER_SIZE = 4
        const val LENGTH_16_SIZE = 2
        const val LENGTH_24_SIZE = 3
        const val RECORD_CHANGE_CIPHER_SPEC = 20
        const val RECORD_ALERT = 21
        const val RECORD_HANDSHAKE = 22
        const val RECORD_APPLICATION_DATA = 23
        const val HANDSHAKE_CERTIFICATE = 11
        const val HANDSHAKE_CERTIFICATE_REQUEST = 13
    }
}

/** Describes a DER encoded certificate by identity, validity and fingerprint. */
internal fun describeEncodedCertificate(der: ByteArray): String = runCatching {
    val certificate = CertificateFactory.getInstance("X.509")
        .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    describeCertificate(certificate)
}.getOrElse { "${der.size} bytes, unparsable (${it.message ?: it.javaClass.simpleName})" }

/**
 * One line summary of a certificate: who it identifies, who issued it, how long it is valid and
 * the fingerprint, so the identity a peer rejected can be recognised in the log.
 */
internal fun describeCertificate(certificate: X509Certificate): String {
    val key = runCatching { certificate.publicKey }.getOrNull()
    val keyBits = (key as? java.security.interfaces.RSAPublicKey)?.modulus?.bitLength()
    val keyDescription = buildString {
        append(key?.algorithm ?: "unknown key")
        if (keyBits != null) append(" $keyBits bit")
    }
    return "subject=${describePrincipal(certificate.subjectX500Principal)}" +
        " issuer=${describePrincipal(certificate.issuerX500Principal)}" +
        " serial=${certificate.serialNumber.toString(16)}" +
        " valid=${formatInstant(certificate.notBefore.time)}..${formatInstant(certificate.notAfter.time)}" +
        " key=$keyDescription" +
        " sigalg=${certificate.sigAlgName}" +
        " sha256=${fingerprint(runCatching { certificate.encoded }.getOrDefault(ByteArray(0)))}"
}

/** Lower case hex SHA-256 digest, the form certificate fingerprints are usually compared in. */
internal fun fingerprint(encoded: ByteArray): String = runCatching {
    MessageDigest.getInstance("SHA-256").digest(encoded)
        .joinToString("") { "%02x".format(it) }
}.getOrDefault("unavailable")

private fun describeDistinguishedName(der: ByteArray): String = runCatching {
    describePrincipal(X500Principal(der))
}.getOrElse { "${der.size} undecodable bytes" }

private fun describePrincipal(principal: X500Principal?): String =
    principal?.name?.takeIf { it.isNotEmpty() } ?: "(empty)"

private fun formatInstant(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(java.util.Date(epochMillis))

private fun ByteArray.u16(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)

private fun ByteArray.u24(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 16) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        (this[offset + 2].toInt() and 0xFF)

private fun recordTypeName(type: Int) = when (type) {
    20 -> "ChangeCipherSpec"
    21 -> "Alert"
    22 -> "Handshake"
    23 -> "ApplicationData"
    else -> "unknown record type $type"
}

private fun tlsVersionName(version: Int) = when (version) {
    0x0300 -> "SSLv3"
    0x0301 -> "TLSv1.0"
    0x0302 -> "TLSv1.1"
    0x0303 -> "TLSv1.2"
    0x0304 -> "TLSv1.3"
    else -> "version 0x%04x".format(version)
}

private fun handshakeTypeName(type: Int) = when (type) {
    0 -> "HelloRequest"
    1 -> "ClientHello"
    2 -> "ServerHello"
    4 -> "NewSessionTicket"
    8 -> "EncryptedExtensions"
    11 -> "Certificate"
    12 -> "ServerKeyExchange"
    13 -> "CertificateRequest"
    14 -> "ServerHelloDone"
    15 -> "CertificateVerify"
    16 -> "ClientKeyExchange"
    20 -> "Finished"
    else -> "handshake type $type"
}

private fun clientCertificateTypeName(type: Int) = when (type) {
    1 -> "rsa_sign"
    2 -> "dss_sign"
    3 -> "rsa_fixed_dh"
    4 -> "dss_fixed_dh"
    64 -> "ecdsa_sign"
    else -> "type $type"
}

/**
 * Alert descriptions from RFC 5246. The certificate related ones are the interesting outcome: a
 * phone that refuses the head unit identity answers with one of them instead of continuing.
 */
private fun alertDescriptionName(code: Int) = when (code) {
    0 -> "close_notify"
    10 -> "unexpected_message"
    20 -> "bad_record_mac"
    21 -> "decryption_failed"
    22 -> "record_overflow"
    30 -> "decompression_failure"
    40 -> "handshake_failure"
    41 -> "no_certificate"
    42 -> "bad_certificate"
    43 -> "unsupported_certificate"
    44 -> "certificate_revoked"
    45 -> "certificate_expired"
    46 -> "certificate_unknown"
    47 -> "illegal_parameter"
    48 -> "unknown_ca"
    49 -> "access_denied"
    50 -> "decode_error"
    51 -> "decrypt_error"
    60 -> "export_restriction"
    70 -> "protocol_version"
    71 -> "insufficient_security"
    80 -> "internal_error"
    86 -> "inappropriate_fallback"
    90 -> "user_canceled"
    100 -> "no_renegotiation"
    109 -> "missing_extension"
    110 -> "unsupported_extension"
    112 -> "unrecognized_name"
    113 -> "bad_certificate_status_response"
    115 -> "unknown_psk_identity"
    116 -> "certificate_required"
    120 -> "no_application_protocol"
    else -> "alert $code"
}
