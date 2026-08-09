package com.ssheadunit.session

import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import javax.security.auth.x500.X500Principal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The generated `headunit.p12` certificate must carry the v3 extensions a strict TLS validator
 * (including some third party wireless adapters) expects of a leaf server certificate, or it may
 * be rejected outright even though the Android Auto protocol itself does not require them.
 */
class HeadUnitCredentialsTest {

    private fun generateCertificate(): X509Certificate {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val method = HeadUnitCredentials::class.java.getDeclaredMethod(
            "createCertificate",
            ByteArray::class.java,
            java.security.PrivateKey::class.java,
        )
        method.isAccessible = true
        return method.invoke(HeadUnitCredentials, keyPair.public.encoded, keyPair.private) as X509Certificate
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
        assertTrue(dnsNames.contains(SUBJECT_COMMON_NAME))
        assertTrue(certificate.nonCriticalExtensionOIDs.contains(SUBJECT_ALT_NAME_OID))
    }

    @Test
    fun `subject distinguished name matches pacifica production identity`() {
        val subject = generateCertificate().subjectX500Principal.getName(X500Principal.RFC2253)
        assertEquals(
            "CN=$SUBJECT_COMMON_NAME,OU=FCA US LLC Uconnect,O=Stellantis N.V.,C=US",
            subject,
        )
    }

    companion object {
        private const val BASIC_CONSTRAINTS_OID = "2.5.29.19"
        private const val KEY_USAGE_OID = "2.5.29.15"
        private const val EXTENDED_KEY_USAGE_OID = "2.5.29.37"
        private const val SUBJECT_ALT_NAME_OID = "2.5.29.17"
        private const val SERVER_AUTH_OID = "1.3.6.1.5.5.7.3.1"
        private const val SUBJECT_COMMON_NAME = "com.google.android.automotive.fca.pacifica.prod"
    }
}
