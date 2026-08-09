package com.ssheadunit.session

import android.content.Context
import com.ssheadunit.protocol.describeCertificate
import com.ssheadunit.util.HeadUnitLog
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Loads the head unit TLS credentials.
 *
 * A phone only starts a projection session with a head unit whose certificate it accepts, so the
 * matching certificate/key pair is stored in a passwordless PKCS#12 keystore. A bundled
 * `app/src/main/assets/headunit.p12` is still supported for custom credentials.
 */
object HeadUnitCredentials {

    const val KEYSTORE_ASSET = "headunit.p12"
    const val PASSWORD_ASSET = "headunit.pwd"
    private val EMPTY_PASSWORD = CharArray(0)

    enum class CertificateProfile {
        SS_HEAD_UNIT,
        CHRYSLER_PACIFICA,
    }

    fun createSslContext(context: Context): SSLContext {
        val keyStore = KeyStore.getInstance("PKCS12")
        val generatedKeyStore = ensureCredentials(context)
        val password = if (generatedKeyStore != null) EMPTY_PASSWORD else readPassword(context)
        try {
            (generatedKeyStore?.let(::FileInputStream) ?: context.assets.open(KEYSTORE_ASSET))
                .use { keyStore.load(it, password) }
        } catch (e: IOException) {
            throw MissingCredentialsException("Unable to load $KEYSTORE_ASSET", e)
        }
        logIdentity(keyStore, generated = generatedKeyStore != null)
        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(keyStore, password)
        return SSLContext.getInstance("TLSv1.2").apply {
            init(keyManagerFactory.keyManagers, null, null)
        }
    }

    /**
     * Creates the app-private, passwordless keystore unless a custom keystore asset is supplied.
     *
     * The synchronized block also prevents concurrent first-load attempts from replacing a
     * keystore while it is being read.
     */
    @Synchronized
    fun ensureCredentials(context: Context): File? {
        val generatedFile = File(context.filesDir, KEYSTORE_ASSET)
        if (generatedFile.exists()) return generatedFile
        if (hasBundledKeyStore(context)) return null

        return writeGeneratedCredentials(context, CertificateProfile.SS_HEAD_UNIT, overwrite = false)
    }

    @Synchronized
    fun replaceCredentials(context: Context, profile: CertificateProfile): File =
        writeGeneratedCredentials(context, profile, overwrite = true)
            ?: throw MissingCredentialsException("Unable to create $KEYSTORE_ASSET")

    private fun writeGeneratedCredentials(
        context: Context,
        profile: CertificateProfile,
        overwrite: Boolean,
    ): File? {
        val generatedFile = File(context.filesDir, KEYSTORE_ASSET)
        if (!overwrite && generatedFile.exists()) return generatedFile
        if (!overwrite && hasBundledKeyStore(context)) return null

        val temporaryFile = File(context.filesDir, "$KEYSTORE_ASSET.tmp")
        try {
            val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_SIZE_BITS) }.generateKeyPair()
            val certificate = createCertificate(keyPair.public.encoded, keyPair.private, profile)
            val keyStore = KeyStore.getInstance("PKCS12").apply { load(null, EMPTY_PASSWORD) }
            keyStore.setKeyEntry(KEY_ALIAS, keyPair.private, EMPTY_PASSWORD, arrayOf(certificate))
            FileOutputStream(temporaryFile).use { keyStore.store(it, EMPTY_PASSWORD) }
            if (overwrite && generatedFile.exists() && !generatedFile.delete()) {
                throw IOException("Unable to overwrite $KEYSTORE_ASSET")
            }
            if (!temporaryFile.renameTo(generatedFile)) {
                throw IOException("Unable to save $KEYSTORE_ASSET")
            }
            return generatedFile
        } catch (e: Exception) {
            temporaryFile.delete()
            throw MissingCredentialsException("Unable to create $KEYSTORE_ASSET", e)
        }
    }

    /**
     * Records which head unit identity is about to be offered. A phone that refuses to project
     * rejects this certificate, so the log has to name it before the handshake starts.
     */
    private fun logIdentity(keyStore: KeyStore, generated: Boolean) {
        if (!HeadUnitLog.enabled) return
        runCatching {
            val source = if (generated) "generated" else "bundled $KEYSTORE_ASSET"
            val aliases = keyStore.aliases().toList()
            HeadUnitLog.i(TAG, "Head unit identity ($source): ${aliases.size} keystore entries")
            aliases.forEach { alias ->
                val chain = keyStore.getCertificateChain(alias).orEmpty()
                if (chain.isEmpty()) {
                    HeadUnitLog.i(TAG, "  \"$alias\" has no certificate chain")
                }
                chain.forEachIndexed { index, certificate ->
                    val described = (certificate as? X509Certificate)?.let(::describeCertificate)
                        ?: certificate.type
                    HeadUnitLog.i(TAG, "  \"$alias\" certificate #$index: $described")
                }
            }
        }.onFailure { HeadUnitLog.w(TAG, "Unable to describe the head unit identity: ${it.message}") }
    }

    private fun readPassword(context: Context): CharArray = try {
        context.assets.open(PASSWORD_ASSET).use { it.readBytes() }
            .toString(Charsets.UTF_8)
            .trim()
            .toCharArray()
    } catch (e: IOException) {
        EMPTY_PASSWORD
    }

    private fun hasBundledKeyStore(context: Context): Boolean = try {
        context.assets.open(KEYSTORE_ASSET).close()
        true
    } catch (e: IOException) {
        false
    }

    private fun createCertificate(encodedPublicKey: ByteArray, privateKey: java.security.PrivateKey): X509Certificate =
        createCertificate(encodedPublicKey, privateKey, CertificateProfile.SS_HEAD_UNIT)

    private fun createCertificate(
        encodedPublicKey: ByteArray,
        privateKey: java.security.PrivateKey,
        profile: CertificateProfile,
    ): X509Certificate {
        val algorithm = sequence(objectIdentifier(SHA256_WITH_RSA_OID), nullValue())
        val issuer = issuerName(profile)
        val subject = subjectName(profile)
        val notBefore = notBefore(profile)
        val notAfter = notAfter(profile, notBefore)
        val tbsCertificate = sequence(
            explicit(0, integer(BigInteger.valueOf(2))),
            integer(serialNumber(profile)),
            algorithm,
            issuer,
            sequence(utcTime(notBefore), utcTime(notAfter)),
            subject,
            encodedPublicKey,
            explicit(3, sequence(*extensions(subjectAlternativeName(profile)))),
        )
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(privateKey)
            update(tbsCertificate)
        }.sign()
        val certificateBytes = sequence(tbsCertificate, algorithm, bitString(signature))
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(certificateBytes.inputStream()) as X509Certificate
    }

    private fun serialNumber(profile: CertificateProfile): BigInteger =
        when (profile) {
            CertificateProfile.SS_HEAD_UNIT -> BigInteger(128, SecureRandom())
            CertificateProfile.CHRYSLER_PACIFICA -> BigInteger("7bcd3456ef1290ab", 16)
        }

    private fun issuerName(profile: CertificateProfile): ByteArray =
        when (profile) {
            CertificateProfile.SS_HEAD_UNIT -> distinguishedName(
                NameAttribute(COMMON_NAME_OID, "ssHeadUnit"),
            )
            CertificateProfile.CHRYSLER_PACIFICA -> distinguishedName(
                NameAttribute(COUNTRY_NAME_OID, "US", printable = true),
                NameAttribute(ORGANIZATION_NAME_OID, "Google LLC"),
                NameAttribute(ORGANIZATIONAL_UNIT_NAME_OID, "Android"),
                NameAttribute(COMMON_NAME_OID, "Google Automotive Services Production CA"),
            )
        }

    private fun subjectName(profile: CertificateProfile): ByteArray =
        when (profile) {
            CertificateProfile.SS_HEAD_UNIT -> distinguishedName(
                NameAttribute(COMMON_NAME_OID, "ssHeadUnit"),
            )
            CertificateProfile.CHRYSLER_PACIFICA -> distinguishedName(
                NameAttribute(COUNTRY_NAME_OID, "US", printable = true),
                NameAttribute(ORGANIZATION_NAME_OID, "Stellantis N.V."),
                NameAttribute(ORGANIZATIONAL_UNIT_NAME_OID, "FCA US LLC Uconnect"),
                NameAttribute(COMMON_NAME_OID, "com.google.android.automotive.fca.pacifica.prod"),
            )
        }

    private fun notBefore(profile: CertificateProfile): Calendar =
        when (profile) {
            CertificateProfile.SS_HEAD_UNIT -> Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            CertificateProfile.CHRYSLER_PACIFICA -> utcCalendar(2024, Calendar.MARCH, 15, 0, 0, 0)
        }

    private fun notAfter(profile: CertificateProfile, notBefore: Calendar): Calendar =
        when (profile) {
            CertificateProfile.SS_HEAD_UNIT ->
                (notBefore.clone() as Calendar).apply { add(Calendar.YEAR, CERTIFICATE_VALIDITY_YEARS) }
            CertificateProfile.CHRYSLER_PACIFICA -> utcCalendar(2034, Calendar.MARCH, 14, 23, 59, 59)
        }

    private fun subjectAlternativeName(profile: CertificateProfile): String =
        when (profile) {
            CertificateProfile.SS_HEAD_UNIT -> "ssHeadUnit"
            CertificateProfile.CHRYSLER_PACIFICA -> "com.google.android.automotive.fca.pacifica.prod"
        }

    private fun utcCalendar(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month, day, hour, minute, second)
        }

    /**
     * Standard v3 extensions expected of a leaf TLS server certificate. Some head unit
     * validators (including third party wireless adapters) are stricter than the Android Auto
     * protocol requires and reject a certificate lacking them.
     */
    private fun extensions(subjectAlternativeName: String): Array<ByteArray> = arrayOf(
        extension(BASIC_CONSTRAINTS_OID, critical = true, value = sequence()),
        extension(
            KEY_USAGE_OID,
            critical = true,
            value = keyUsageBitString(DIGITAL_SIGNATURE_BIT or KEY_ENCIPHERMENT_BIT),
        ),
        extension(
            EXTENDED_KEY_USAGE_OID,
            critical = false,
            value = sequence(objectIdentifier(SERVER_AUTH_OID)),
        ),
        extension(
            SUBJECT_ALT_NAME_OID,
            critical = false,
            value = sequence(generalNameDnsName(subjectAlternativeName)),
        ),
    )

    private fun extension(oid: String, critical: Boolean, value: ByteArray): ByteArray =
        if (critical) {
            sequence(objectIdentifier(oid), booleanValue(true), octetString(value))
        } else {
            sequence(objectIdentifier(oid), octetString(value))
        }

    /**
     * KeyUsage ::= BIT STRING; bits are numbered from the most significant bit of the first byte,
     * so bit 0 (digitalSignature) is 0x80 and bit 2 (keyEncipherment) is 0x20. DER requires
     * trailing zero bits to be reported as unused rather than included in the content.
     */
    private fun keyUsageBitString(bits: Int): ByteArray {
        var remaining = bits and 0xFF
        if (remaining == 0) return bitStringWithUnused(0, byteArrayOf(0))
        var unusedBits = 0
        while (remaining and 1 == 0) {
            unusedBits++
            remaining = remaining shr 1
        }
        return bitStringWithUnused(unusedBits, byteArrayOf(bits.toByte()))
    }

    /** GeneralName ::= CHOICE { ..., dNSName [2] IA5String, ... } */
    private fun generalNameDnsName(value: String) = der(0x82, value.toByteArray(Charsets.US_ASCII))

    private fun sequence(vararg values: ByteArray) = der(0x30, values.fold(ByteArray(0)) { all, value -> all + value })

    private fun set(vararg values: ByteArray) = der(0x31, values.fold(ByteArray(0)) { all, value -> all + value })

    private fun explicit(tag: Int, value: ByteArray) = der(0xA0 + tag, value)

    private fun integer(value: BigInteger) = der(0x02, value.toByteArray())

    private data class NameAttribute(val oid: String, val value: String, val printable: Boolean = false)

    private fun distinguishedName(vararg attributes: NameAttribute): ByteArray =
        sequence(
            *attributes.map { attribute ->
                val value = if (attribute.printable) {
                    printableString(attribute.value)
                } else {
                    utf8String(attribute.value)
                }
                set(sequence(objectIdentifier(attribute.oid), value))
            }.toTypedArray()
        )

    private fun objectIdentifier(value: String): ByteArray {
        val parts = value.split('.').map(String::toLong)
        val encoded = mutableListOf<Byte>()
        appendBase128(encoded, parts[0] * 40 + parts[1])
        parts.drop(2).forEach { appendBase128(encoded, it) }
        return der(0x06, encoded.toByteArray())
    }

    private fun appendBase128(destination: MutableList<Byte>, value: Long) {
        val bytes = mutableListOf((value and 0x7F).toByte())
        var remaining = value shr 7
        while (remaining > 0) {
            bytes.add(0, ((remaining and 0x7F) or 0x80).toByte())
            remaining = remaining shr 7
        }
        destination += bytes
    }

    private fun utf8String(value: String) = der(0x0C, value.toByteArray(Charsets.UTF_8))

    private fun printableString(value: String) = der(0x13, value.toByteArray(Charsets.US_ASCII))

    private fun utcTime(value: Calendar): ByteArray {
        val formatter = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return der(0x17, formatter.format(value.time).toByteArray(Charsets.US_ASCII))
    }

    private fun nullValue() = der(0x05, ByteArray(0))

    private fun booleanValue(value: Boolean) = der(0x01, byteArrayOf(if (value) 0xFF.toByte() else 0x00))

    private fun octetString(value: ByteArray) = der(0x04, value)

    private fun bitString(value: ByteArray) = bitStringWithUnused(0, value)

    private fun bitStringWithUnused(unusedBits: Int, value: ByteArray) =
        der(0x03, byteArrayOf(unusedBits.toByte()) + value)

    private fun der(tag: Int, value: ByteArray): ByteArray {
        val length = when {
            value.size < 128 -> byteArrayOf(value.size.toByte())
            value.size < 256 -> byteArrayOf(0x81.toByte(), value.size.toByte())
            else -> byteArrayOf(0x82.toByte(), (value.size shr 8).toByte(), value.size.toByte())
        }
        return byteArrayOf(tag.toByte()) + length + value
    }

    class MissingCredentialsException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private const val TAG = "HeadUnitCredentials"
    private const val KEY_ALIAS = "headunit"
    private const val KEY_SIZE_BITS = 2048
    private const val CERTIFICATE_VALIDITY_YEARS = 10
    private const val SHA256_WITH_RSA_OID = "1.2.840.113549.1.1.11"
    private const val COUNTRY_NAME_OID = "2.5.4.6"
    private const val ORGANIZATION_NAME_OID = "2.5.4.10"
    private const val ORGANIZATIONAL_UNIT_NAME_OID = "2.5.4.11"
    private const val COMMON_NAME_OID = "2.5.4.3"
    private const val BASIC_CONSTRAINTS_OID = "2.5.29.19"
    private const val KEY_USAGE_OID = "2.5.29.15"
    private const val EXTENDED_KEY_USAGE_OID = "2.5.29.37"
    private const val SUBJECT_ALT_NAME_OID = "2.5.29.17"
    private const val SERVER_AUTH_OID = "1.3.6.1.5.5.7.3.1"
    private const val DIGITAL_SIGNATURE_BIT = 0x80
    private const val KEY_ENCIPHERMENT_BIT = 0x20
}
