package com.ssheadunit.transport

import android.hardware.usb.UsbDeviceConnection
import com.ssheadunit.util.HeadUnitLog

/**
 * [Transport] backed by libusb.
 *
 * The device is opened by the Android framework (which owns the USB permission) and the file
 * descriptor is then wrapped by libusb, which claims the accessory interface and performs the bulk
 * transfers itself. Compared with `UsbDeviceConnection.bulkTransfer` this gives a real status for
 * every transfer, so an idle link, a stalled endpoint and a disconnected device are handled
 * differently instead of all looking like "-1".
 */
class LibUsbTransport private constructor(
    private val connection: UsbDeviceConnection,
    private val handle: Long,
    private val inEndpoint: Int,
    private val outEndpoint: Int
) : Transport {

    @Volatile
    private var closed = false

    private var consecutiveErrors = 0

    override fun send(data: ByteArray, timeoutMs: Int) {
        var offset = 0
        while (offset < data.size) {
            if (closed) throw TransportException("Transport closed")
            val chunk = minOf(MAX_TRANSFER, data.size - offset)
            var result = LibUsb.nativeBulkWrite(handle, outEndpoint, data, offset, chunk, timeout(timeoutMs))
            when (LibUsbResult.actionFor(result)) {
                LibUsbAction.OK -> Unit
                LibUsbAction.STALLED, LibUsbAction.RETRY, LibUsbAction.IDLE -> {
                    // A write that stalled or timed out can often be repeated once the endpoint is
                    // unblocked; anything still failing afterwards ends the session.
                    clearHalt(outEndpoint)
                    result = LibUsb.nativeBulkWrite(handle, outEndpoint, data, offset, chunk, timeout(timeoutMs))
                }
                LibUsbAction.FATAL -> throw TransportException(failure("write at offset $offset", result))
            }
            if (result < 0) throw TransportException(failure("write at offset $offset", result))
            if (result == 0) throw TransportException("USB write made no progress at offset $offset")
            offset += result
        }
    }

    override fun receive(buffer: ByteArray, timeoutMs: Int): Int {
        if (closed) throw TransportException("Transport closed")
        val result = LibUsb.nativeBulkRead(handle, inEndpoint, buffer, 0, minOf(buffer.size, MAX_TRANSFER), timeout(timeoutMs))
        when (LibUsbResult.actionFor(result)) {
            LibUsbAction.OK -> {
                consecutiveErrors = 0
                return result
            }
            LibUsbAction.IDLE -> {
                // The peer simply had nothing to send within the timeout.
                consecutiveErrors = 0
                return 0
            }
            LibUsbAction.STALLED -> {
                clearHalt(inEndpoint)
                consecutiveErrors++
            }
            LibUsbAction.RETRY -> consecutiveErrors++
            LibUsbAction.FATAL -> throw TransportException(failure("read", result))
        }
        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
            throw TransportException("USB read failed $consecutiveErrors times in a row; link lost")
        }
        return 0
    }

    override fun close() {
        if (closed) return
        closed = true
        // libusb releases the interface and closes its handle; the descriptor itself belongs to
        // the framework connection, which is closed afterwards.
        runCatching { LibUsb.nativeClose(handle) }
        runCatching { connection.close() }
    }

    private fun clearHalt(endpoint: Int) {
        runCatching { LibUsb.nativeClearHalt(handle, endpoint) }
    }

    private fun failure(what: String, result: Int) = "libusb $what failed: ${LibUsb.errorName(result)}"

    /** libusb treats a zero timeout as "wait for ever", which the session must never do. */
    private fun timeout(timeoutMs: Int) = if (timeoutMs > 0) timeoutMs else Transport.DEFAULT_TIMEOUT_MS

    companion object {

        private const val TAG = "LibUsbTransport"

        private const val MAX_TRANSFER = 16384

        /** How many failed reads in a row are tolerated before the link is declared dead. */
        private const val MAX_CONSECUTIVE_ERRORS = 10

        /**
         * Wraps [connection] with libusb and claims [interfaceNumber]. Returns null when libusb is
         * not usable for this device, so the caller can fall back to the framework transport. The
         * connection stays owned by the caller until a transport is returned.
         */
        fun open(
            connection: UsbDeviceConnection,
            interfaceNumber: Int,
            inEndpoint: Int,
            outEndpoint: Int
        ): LibUsbTransport? {
            if (!LibUsb.isAvailable) return null
            val handle = LibUsb.open(connection, interfaceNumber)
            if (handle <= 0) {
                HeadUnitLog.w(TAG, "libusb could not claim interface $interfaceNumber: ${LibUsb.errorName(handle.toInt())}")
                return null
            }
            HeadUnitLog.i(TAG, "libusb claimed interface $interfaceNumber (in=0x%02x out=0x%02x)".format(inEndpoint, outEndpoint))
            return LibUsbTransport(connection, handle, inEndpoint, outEndpoint)
        }
    }
}
