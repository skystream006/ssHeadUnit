package com.tabletaa.transport

/** Byte oriented link with the connected phone. */
interface Transport {
    /** Sends all of [data], blocking until it has been handed to the link. */
    fun send(data: ByteArray, timeoutMs: Int = DEFAULT_TIMEOUT_MS)

    /** Reads into [buffer], returning the number of bytes read (0 when the read timed out). */
    fun receive(buffer: ByteArray, timeoutMs: Int = DEFAULT_TIMEOUT_MS): Int

    fun close()

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5000
    }
}

class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause)
