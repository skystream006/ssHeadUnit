package com.ssheadunit.session

import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The generated `headunit.p12` certificate must carry the v3 extensions a strict TLS validator
 * (including some third party wireless adapters) expects of a leaf server certificate, or it may
 * be rejected outright even though the Android Auto protocol itself does not require them.
 */
class HeadUnitCredentialsTest {

    private fun generateCertificate(
        profile: HeadUnitCredentials.CertificateProfile = HeadUnitCredentials.CertificateProfile.SS_HEAD_UNIT,
    ): X509Certificate {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val method = HeadUnitCredentials::class.java.getDeclaredMethod(
            "createCertificate",
            ByteArray::class.java,
            java.security.PrivateKey::class.java,
            HeadUnitCredentials.CertificateProfile::class.java,
        )
        method.isAccessible = true
        return method.invoke(HeadUnitCredentials, keyPair.public.encoded, keyPair.private, profile) as X509Certificate
    }

    @Test
    fun `certificate is v3`() {
        assertEquals(3, generateCertificate().version)
    }

    @Test
    fun `basic constraints marks the certificate as not a CA and is critical`() {
        val certificate = generateCertificate()
        assertEquals(-1, certificate.basicConstraints)
        assertTrue(certificate.criticalExtensionOIDs.contains(BASIC_CONSTRAINTS_OID))
    }

    @Test
    fun `key usage declares digital signature and key encipherment and is critical`() {
        val certificate = generateCertificate()
        val keyUsage = certificate.keyUsage
        assertTrue("digitalSignature bit should be set", keyUsage[0])
        assertTrue("keyEncipherment bit should be set", keyUsage[2])
        assertTrue(certificate.criticalExtensionOIDs.contains(KEY_USAGE_OID))
    }

    @Test
    fun `extended key usage declares server auth`() {
        val certificate = generateCertificate()
        assertTrue(certificate.extendedKeyUsage.contains(SERVER_AUTH_OID))
        assertTrue(certificate.nonCriticalExtensionOIDs.contains(EXTENDED_KEY_USAGE_OID))
    }

    @Test
    fun `subject alternative name declares a DNS entry`() {
        val certificate = generateCertificate()
        val dnsNames = certificate.subjectAlternativeNames.map { it[1] as String }
        assertTrue(dnsNames.contains("ssHeadUnit"))
        assertTrue(certificate.nonCriticalExtensionOIDs.contains(SUBJECT_ALT_NAME_OID))
    }

    @Test
    fun `chrysler pacifica certificate matches requested identity`() {
        val certificate = generateCertificate(HeadUnitCredentials.CertificateProfile.CHRYSLER_PACIFICA)

        assertEquals(BigInteger("7bcd3456ef1290ab", 16), certificate.serialNumber)
        assertEquals(
            "CN=Google Automotive Services Production CA,OU=Android,O=Google LLC,C=US",
            certificate.issuerX500Principal.name,
        )
        assertEquals(
            "CN=com.google.android.automotive.fca.pacifica.prod,OU=FCA US LLC Uconnect,O=Stellantis N.V.,C=US",
            certificate.subjectX500Principal.name,
        )
        assertEquals(utcDate("2024-03-15 00:00:00"), certificate.notBefore)
        assertEquals(utcDate("2034-03-14 23:59:59"), certificate.notAfter)
        assertEquals("SHA256withRSA", certificate.sigAlgName)
    }

    @Test
    fun `chrysler pacifica certificate uses pacifica subject alternative name`() {
        val certificate = generateCertificate(HeadUnitCredentials.CertificateProfile.CHRYSLER_PACIFICA)

        val dnsNames = certificate.subjectAlternativeNames.map { it[1] as String }
        assertTrue(dnsNames.contains("com.google.android.automotive.fca.pacifica.prod"))
    }

    private fun utcDate(value: String) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.parse(value)

    companion object {
        private const val BASIC_CONSTRAINTS_OID = "2.5.29.19"
        private const val KEY_USAGE_OID = "2.5.29.15"
        private const val EXTENDED_KEY_USAGE_OID = "2.5.29.37"
        private const val SUBJECT_ALT_NAME_OID = "2.5.29.17"
        private const val SERVER_AUTH_OID = "1.3.6.1.5.5.7.3.1"
    }
}
