package com.ssheadunit.session

import android.content.Context
import java.io.IOException
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Loads the head unit TLS credentials.
 *
 * A phone only starts a projection session with a head unit whose certificate it accepts, so the
 * matching certificate/key pair has to be provided as a PKCS#12 keystore in
 * `app/src/main/assets/headunit.p12`, with its password in `app/src/main/assets/headunit.pwd`.
 * No credentials are bundled with this repository.
 */
object HeadUnitCredentials {

    const val KEYSTORE_ASSET = "headunit.p12"
    const val PASSWORD_ASSET = "headunit.pwd"

    fun createSslContext(context: Context): SSLContext {
        val password = readPassword(context)
        val keyStore = KeyStore.getInstance("PKCS12")
        try {
            context.assets.open(KEYSTORE_ASSET).use { keyStore.load(it, password) }
        } catch (e: IOException) {
            throw MissingCredentialsException(
                "Missing assets/$KEYSTORE_ASSET; see README.md for how to provide head unit credentials", e
            )
        }
        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(keyStore, password)
        return SSLContext.getInstance("TLSv1.2").apply {
            init(keyManagerFactory.keyManagers, null, null)
        }
    }

    private fun readPassword(context: Context): CharArray = try {
        context.assets.open(PASSWORD_ASSET).use { it.readBytes() }
            .toString(Charsets.UTF_8)
            .trim()
            .toCharArray()
    } catch (e: IOException) {
        CharArray(0)
    }

    class MissingCredentialsException(message: String, cause: Throwable? = null) : Exception(message, cause)
}
