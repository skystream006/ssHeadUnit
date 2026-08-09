package com.ssheadunit.transport

import android.hardware.usb.UsbDeviceConnection
import com.ssheadunit.util.HeadUnitLog

/**
 * Thin Kotlin wrapper around the libusb based native transport (`libssusb.so`).
 *
 * Android's `UsbDeviceConnection.bulkTransfer` reports every problem as a single negative number,
 * so a plain timeout, a stalled endpoint and a lost device are indistinguishable. libusb returns
 * the real usbfs status, which lets the session keep waiting on an idle link, clear a halted
 * endpoint and give up immediately when the device is gone.
 *
 * Device discovery stays with the Android framework: the app still asks the user for USB
 * permission and opens the device through `UsbManager`; only the file descriptor of the open
 * connection is handed to libusb (`libusb_wrap_sys_device`).
 */
object LibUsb {

    private const val TAG = "LibUsb"

    /** True when `libssusb.so` could be loaded; false on a build or device without it. */
    val isAvailable: Boolean by lazy {
        runCatching { System.loadLibrary("ssusb") }
            .onFailure { HeadUnitLog.w(TAG, "libusb unavailable: ${it.message}") }
            .isSuccess
    }

    // libusb_error values used by the transport.
    const val SUCCESS = 0
    const val ERROR_IO = -1
    const val ERROR_ACCESS = -3
    const val ERROR_NO_DEVICE = -4
    const val ERROR_BUSY = -6
    const val ERROR_TIMEOUT = -7
    const val ERROR_OVERFLOW = -8
    const val ERROR_PIPE = -9
    const val ERROR_INTERRUPTED = -10

    /**
     * Wraps the descriptor of an already open [connection] and claims [interfaceNumber].
     * Returns a positive native handle, or a negative libusb error code.
     */
    fun open(connection: UsbDeviceConnection, interfaceNumber: Int): Long =
        nativeOpen(connection.fileDescriptor, interfaceNumber)

    fun errorName(code: Int): String = if (isAvailable) nativeErrorName(code) else "code $code"

    @JvmStatic
    private external fun nativeOpen(fd: Int, interfaceNumber: Int): Long

    @JvmStatic
    external fun nativeBulkRead(handle: Long, endpoint: Int, data: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int

    @JvmStatic
    external fun nativeBulkWrite(handle: Long, endpoint: Int, data: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int

    @JvmStatic
    external fun nativeClearHalt(handle: Long, endpoint: Int): Int

    @JvmStatic
    external fun nativeClose(handle: Long)

    @JvmStatic
    private external fun nativeErrorName(code: Int): String
}

/** How the transport reacts to the result of a libusb transfer. */
enum class LibUsbAction {
    /** The transfer succeeded. */
    OK,

    /** Nothing arrived within the timeout; the link is healthy. */
    IDLE,

    /** The endpoint stalled; clearing its halt condition may revive the link. */
    STALLED,

    /** A transient failure; worth retrying a bounded number of times. */
    RETRY,

    /** The device is gone; the session must end. */
    FATAL
}

/** Pure mapping from a libusb result to the action the transport takes, kept unit testable. */
object LibUsbResult {

    fun actionFor(result: Int): LibUsbAction = when {
        result >= 0 -> LibUsbAction.OK
        result == LibUsb.ERROR_TIMEOUT -> LibUsbAction.IDLE
        result == LibUsb.ERROR_PIPE || result == LibUsb.ERROR_OVERFLOW -> LibUsbAction.STALLED
        result == LibUsb.ERROR_IO || result == LibUsb.ERROR_INTERRUPTED || result == LibUsb.ERROR_BUSY -> LibUsbAction.RETRY
        else -> LibUsbAction.FATAL
    }
}
