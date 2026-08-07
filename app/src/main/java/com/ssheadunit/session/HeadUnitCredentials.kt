package com.ssheadunit.session

import android.content.Context
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

        val temporaryFile = File(context.filesDir, "$KEYSTORE_ASSET.tmp")
        try {
            val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_SIZE_BITS) }.generateKeyPair()
            val certificate = createCertificate(keyPair.public.encoded, keyPair.private)
            val keyStore = KeyStore.getInstance("PKCS12").apply { load(null, EMPTY_PASSWORD) }
            keyStore.setKeyEntry(KEY_ALIAS, keyPair.private, EMPTY_PASSWORD, arrayOf(certificate))
            FileOutputStream(temporaryFile).use { keyStore.store(it, EMPTY_PASSWORD) }
            if (!temporaryFile.renameTo(generatedFile)) {
                throw IOException("Unable to save $KEYSTORE_ASSET")
            }
            return generatedFile
        } catch (e: Exception) {
            temporaryFile.delete()
            throw MissingCredentialsException("Unable to create $KEYSTORE_ASSET", e)
        }
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

    private fun createCertificate(encodedPublicKey: ByteArray, privateKey: java.security.PrivateKey): X509Certificate {
        val algorithm = sequence(objectIdentifier(SHA256_WITH_RSA_OID), nullValue())
        val name = sequence(set(sequence(objectIdentifier(COMMON_NAME_OID), utf8String("ssHeadUnit"))))
        val now = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val expires = (now.clone() as Calendar).apply { add(Calendar.YEAR, CERTIFICATE_VALIDITY_YEARS) }
        val tbsCertificate = sequence(
            explicit(0, integer(BigInteger.valueOf(2))),
            integer(BigInteger(128, SecureRandom())),
            algorithm,
            name,
            sequence(utcTime(now), utcTime(expires)),
            name,
            encodedPublicKey,
        )
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(privateKey)
            update(tbsCertificate)
        }.sign()
        val certificateBytes = sequence(tbsCertificate, algorithm, bitString(signature))
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(certificateBytes.inputStream()) as X509Certificate
    }

    private fun sequence(vararg values: ByteArray) = der(0x30, values.fold(ByteArray(0)) { all, value -> all + value })

    private fun set(vararg values: ByteArray) = der(0x31, values.fold(ByteArray(0)) { all, value -> all + value })

    private fun explicit(tag: Int, value: ByteArray) = der(0xA0 + tag, value)

    private fun integer(value: BigInteger) = der(0x02, value.toByteArray())

    private fun objectIdentifier(value: String): ByteArray {
        val parts = value.split('.').map(String::toLong)
        val encoded = mutableListOf((parts[0] * 40 + parts[1]).toByte())
        parts.drop(2).forEach { part ->
            val bytes = mutableListOf((part and 0x7F).toByte())
            var remaining = part shr 7
            while (remaining > 0) {
                bytes.add(0, ((remaining and 0x7F) or 0x80).toByte())
                remaining = remaining shr 7
            }
            encoded += bytes
        }
        return der(0x06, encoded.toByteArray())
    }

    private fun utf8String(value: String) = der(0x0C, value.toByteArray(Charsets.UTF_8))

    private fun utcTime(value: Calendar): ByteArray {
        val formatter = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return der(0x17, formatter.format(value.time).toByteArray(Charsets.US_ASCII))
    }

    private fun nullValue() = der(0x05, ByteArray(0))

    private fun bitString(value: ByteArray) = der(0x03, byteArrayOf(0) + value)

    private fun der(tag: Int, value: ByteArray): ByteArray {
        val length = when {
            value.size < 128 -> byteArrayOf(value.size.toByte())
            value.size < 256 -> byteArrayOf(0x81.toByte(), value.size.toByte())
            else -> byteArrayOf(0x82.toByte(), (value.size shr 8).toByte(), value.size.toByte())
        }
        return byteArrayOf(tag.toByte()) + length + value
    }

    class MissingCredentialsException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private const val KEY_ALIAS = "headunit"
    private const val KEY_SIZE_BITS = 2048
    private const val CERTIFICATE_VALIDITY_YEARS = 10
    private const val SHA256_WITH_RSA_OID = "1.2.840.113549.1.1.11"
    private const val COMMON_NAME_OID = "2.5.4.3"
}
