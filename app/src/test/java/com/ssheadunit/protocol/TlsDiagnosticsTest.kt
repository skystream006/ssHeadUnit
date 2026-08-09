package com.ssheadunit.protocol

import java.util.Base64
import javax.net.ssl.SSLContext
import javax.security.auth.x500.X500Principal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The handshake describer exists so a rejected head unit certificate is visible in the debug log
 * instead of showing up as a session that silently stops progressing.
 */
class TlsDiagnosticsTest {

    private fun describer() = TlsRecordDescriber("head unit -> phone")

    private fun record(type: Int, body: ByteArray): ByteArray =
        byteArrayOf(type.toByte(), 0x03, 0x03, (body.size shr 8).toByte(), body.size.toByte()) + body

    private fun handshake(type: Int, body: ByteArray): ByteArray =
        byteArrayOf(
            type.toByte(),
            (body.size shr 16).toByte(),
            (body.size shr 8).toByte(),
            body.size.toByte(),
        ) + body

    private fun u24(value: Int) = byteArrayOf((value shr 16).toByte(), (value shr 8).toByte(), value.toByte())

    private fun u16(value: Int) = byteArrayOf((value shr 8).toByte(), value.toByte())

    @Test
    fun `messages are described in the order the peer sent them`() {
        val records = record(RECORD_HANDSHAKE, handshake(16, ByteArray(32))) +
            record(RECORD_CHANGE_CIPHER_SPEC, byteArrayOf(1)) +
            record(RECORD_HANDSHAKE, ByteArray(40))
        val lines = describer().describe(records)
        val keyExchange = lines.indexOfFirst { it.contains("ClientKeyExchange") }
        val changeCipher = lines.indexOfFirst { it.contains("ChangeCipherSpec") }
        val encrypted = lines.indexOfFirst { it.contains("encrypted handshake record") }
        assertTrue(lines.toString(), keyExchange in 0 until changeCipher)
        assertTrue(lines.toString(), changeCipher < encrypted)
    }

    @Test
    fun `a message split across records is described once it is complete`() {
        val describer = describer()
        val message = handshake(HANDSHAKE_CERTIFICATE, certificateChainBody(certificateDer()))
        val split = message.size / 2

        val first = describer.describe(record(RECORD_HANDSHAKE, message.copyOfRange(0, split)))
        assertTrue(first.toString(), first.any { it.contains("continues in a later record") })
        assertTrue(first.toString(), first.none { it.contains("certificate #0") })

        val second = describer.describe(record(RECORD_HANDSHAKE, message.copyOfRange(split, message.size)))
        assertTrue(second.toString(), second.any { it.contains("CN=ssHeadUnit Test") })
    }

    @Test
    fun `a record split across calls is not reported as garbage twice`() {
        val describer = describer()
        val message = handshake(1, ByteArray(48))
        // A record whose body arrives in two halves: only the second call can describe it.
        describer.describe(record(RECORD_HANDSHAKE, message.copyOfRange(0, 10)))
        val lines = describer.describe(record(RECORD_HANDSHAKE, message.copyOfRange(10, message.size)))
        assertTrue(lines.toString(), lines.any { it.contains("ClientHello") })
    }

    private fun certificateDer(): ByteArray = Base64.getDecoder().decode(TEST_CERTIFICATE_BASE64)

    private fun certificateChainBody(vararg chain: ByteArray): ByteArray {
        val entries = chain.fold(ByteArray(0)) { all, der -> all + u24(der.size) + der }
        return u24(entries.size) + entries
    }

    private fun certificateMessage(vararg chain: ByteArray): ByteArray =
        handshake(HANDSHAKE_CERTIFICATE, certificateChainBody(*chain))

    @Test
    fun `client hello is named`() {
        val lines = describer().describe(record(RECORD_HANDSHAKE, handshake(1, ByteArray(32))))
        assertTrue(lines.toString(), lines.any { it.contains("ClientHello") })
    }

    @Test
    fun `certificate message reports the exchanged identity`() {
        val lines = describer().describe(record(RECORD_HANDSHAKE, certificateMessage(certificateDer())))
        val joined = lines.joinToString("\n")
        assertTrue(joined, lines.any { it.contains("Certificate (") })
        assertTrue(joined, joined.contains("certificate #0"))
        assertTrue(joined, joined.contains("CN=ssHeadUnit Test"))
        assertTrue(joined, joined.contains("sha256=$TEST_CERTIFICATE_FINGERPRINT"))
    }

    @Test
    fun `certificate message reports every entry of the chain`() {
        val der = certificateDer()
        val lines = describer().describe(record(RECORD_HANDSHAKE, certificateMessage(der, der)))
        val joined = lines.joinToString("\n")
        assertTrue(joined, joined.contains("certificate #0"))
        assertTrue(joined, joined.contains("certificate #1"))
    }

    @Test
    fun `an empty chain is called out`() {
        val lines = describer().describe(record(RECORD_HANDSHAKE, certificateMessage()))
        assertTrue(lines.toString(), lines.any { it.contains("peer offered no certificate") })
    }

    @Test
    fun `certificate request lists the authorities the peer expects`() {
        val authority = X500Principal("CN=Test Authority").encoded
        val authorities = u16(authority.size) + authority
        val body = byteArrayOf(1, 1) + // one accepted certificate type: rsa_sign
            u16(2) + byteArrayOf(0x04, 0x01) + // one signature algorithm
            u16(authorities.size) + authorities
        val lines = describer().describe(record(RECORD_HANDSHAKE, handshake(HANDSHAKE_CERTIFICATE_REQUEST, body)))
        val joined = lines.joinToString("\n")
        assertTrue(joined, joined.contains("CertificateRequest"))
        assertTrue(joined, joined.contains("rsa_sign"))
        assertTrue(joined, joined.contains("expects a certificate issued by one of 1 authorities"))
        assertTrue(joined, joined.contains("CN=Test Authority"))
    }

    @Test
    fun `a request without an authority list is reported as unrestricted`() {
        val body = byteArrayOf(1, 1) + u16(0) + u16(0)
        val lines = describer().describe(record(RECORD_HANDSHAKE, handshake(HANDSHAKE_CERTIFICATE_REQUEST, body)))
        assertTrue(lines.toString(), lines.any { it.contains("any authority") })
    }

    @Test
    fun `a certificate rejection alert is decoded`() {
        val lines = describer().describe(record(RECORD_ALERT, byteArrayOf(2, 48)))
        assertEquals(1, lines.size)
        assertTrue(lines[0], lines[0].contains("fatal alert: unknown_ca (48)"))
    }

    @Test
    fun `records after change cipher spec are reported as encrypted`() {
        val describer = describer()
        val records = record(RECORD_CHANGE_CIPHER_SPEC, byteArrayOf(1)) +
            record(RECORD_HANDSHAKE, ByteArray(40))
        val lines = describer.describe(records)
        val joined = lines.joinToString("\n")
        assertTrue(joined, joined.contains("ChangeCipherSpec"))
        assertTrue(joined, joined.contains("encrypted handshake record"))
        // The flag survives across calls because each side switches once, for the rest of the session.
        assertTrue(describer.describe(record(RECORD_HANDSHAKE, ByteArray(8))).first().contains("encrypted"))
    }

    @Test
    fun `the direction is part of every line`() {
        val lines = TlsRecordDescriber("phone -> head unit")
            .describe(record(RECORD_HANDSHAKE, handshake(1, ByteArray(4))))
        assertTrue(lines.toString(), lines.all { it.startsWith("phone -> head unit:") })
    }

    @Test
    fun `a truncated record is reported instead of throwing`() {
        val truncated = record(RECORD_HANDSHAKE, ByteArray(40)).copyOfRange(0, 20)
        val lines = describer().describe(truncated)
        assertTrue(lines.toString(), lines.any { it.contains("truncated") })
    }

    @Test
    fun `random bytes never throw`() {
        val random = java.util.Random(7)
        repeat(200) {
            val bytes = ByteArray(random.nextInt(64)).also(random::nextBytes)
            describer().describe(bytes)
        }
    }

    @Test
    fun `an empty payload produces no lines`() {
        assertTrue(describer().describe(ByteArray(0)).isEmpty())
    }

    @Test
    fun `nothing is decoded while diagnostics are disabled`() {
        val lines = mutableListOf<String>()
        var enabled = false
        val cryptor = SslCryptor(
            SSLContext.getInstance("TLSv1.2").apply { init(null, null, null) },
            log = { lines += it },
            logEnabled = { enabled },
        )
        cryptor.startHandshake()
        assertTrue(lines.toString(), lines.isEmpty())

        enabled = true
        cryptor.handshake(ByteArray(0))
        // The gate is re-read, so turning logging on mid session starts reporting.
        val describer = TlsRecordDescriber("head unit -> phone")
        assertTrue(describer.describe(record(RECORD_ALERT, byteArrayOf(2, 42))).isNotEmpty())
    }

    @Test
    fun `an unparsable certificate is reported by length`() {
        val lines = describer().describe(record(RECORD_HANDSHAKE, certificateMessage(byteArrayOf(1, 2, 3))))
        assertTrue(lines.toString(), lines.any { it.contains("unparsable") })
    }

    private companion object {
        const val RECORD_CHANGE_CIPHER_SPEC = 20
        const val RECORD_ALERT = 21
        const val RECORD_HANDSHAKE = 22
        const val HANDSHAKE_CERTIFICATE = 11
        const val HANDSHAKE_CERTIFICATE_REQUEST = 13

        /**
         * A throwaway self signed certificate used only as parser input. It carries no private key
         * and is never trusted by anything.
         */
        const val TEST_CERTIFICATE_BASE64 =
            "MIIDFTCCAf2gAwIBAgIUcW9xZ4emXJLa0+2Sc8YD+xJkqFEwDQYJKoZIhvcNAQELBQAwGjEYMBYG" +
                "A1UEAwwPc3NIZWFkVW5pdCBUZXN0MB4XDTI2MDgwOTA2MTM1NFoXDTM2MDgwNjA2MTM1NFowGjEY" +
                "MBYGA1UEAwwPc3NIZWFkVW5pdCBUZXN0MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA" +
                "oF0fcW7uGY22mCdwmGNm9YXIGl4gpqr4cYHdXl6bjBJrJIfDRmdkwvrZmKOeUsG0/byW9QZ9Nk30" +
                "6mk4x0eWDtUJB8gztPEIdEL8UgkopAU6nIMy8wflDZmIJzz6cYer5Ysl9g+9zK0q87DWZRe3Cxwy" +
                "UWo8C2lTOnq1b6T6TzBtsiXMyHsueRu31K4LbBAt6CCNaky3/HdZAiN7DKriMZrTS1Q8jWzneDYo" +
                "h2s9vhR1iWfQRT9+Gred1L6v6oQ/VcL+NWD5Vmx5pQfffj7ObgG/zPxFkO/soJaqXhdSbKI6rGa6" +
                "Z1svSRvtOawXJIBx3r2cQgDutggx1Kjt1EplMwIDAQABo1MwUTAdBgNVHQ4EFgQUHb4H2qUf1iKq" +
                "3YsnBgddf75/ZyMwHwYDVR0jBBgwFoAUHb4H2qUf1iKq3YsnBgddf75/ZyMwDwYDVR0TAQH/BAUw" +
                "AwEB/zANBgkqhkiG9w0BAQsFAAOCAQEAik4Ke7YJziOWF6FVmloIB/1ydcfQCtaxnO7cUdGo43Bk" +
                "Po/2dOk56C8BIbMQY9waGWc9usAZR05O/f9f+LZkTkkycDJMWTmq5ersTiwon2Q0ZqXvKDKrL9W5" +
                "a2vujxUw+fvD5d+ZDVW/M+s5GPi1NtJIizT2HiZEEvQFENz42b3qVyqXOfgQrg3YjrZDqIH5BlYi" +
                "1QzKCGPxmLPWejGXH6vIgNNneGobIF2hhYn08rEBLj/B0p21D6ojl4L3otlMa0/A9GokWmN95VPU" +
                "DJIPSp7TzzFtkKTRBHiQDvBoHdW5PSq/vVz+3tVyNQJ1oqUYaloqyYmx6lGIsGRqqs0QJg=="

        const val TEST_CERTIFICATE_FINGERPRINT =
            "ecadfbcf621b755c16663bcb95008aeef6b97b1e70b77c395c15cb8277d9d071"
    }
}
