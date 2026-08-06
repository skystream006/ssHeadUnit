package com.tabletaa.transport

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log

/**
 * Android Open Accessory Protocol (AOAP) helper.
 *
 * A factory head unit is the USB host and the phone is the accessory. The tablet does the same:
 * it asks the phone to switch into accessory mode, after which the phone re-enumerates with a
 * Google vendor id and exposes a pair of bulk endpoints used for the Android Auto session.
 */
object Aoap {

    private const val TAG = "Aoap"

    const val GOOGLE_VENDOR_ID = 0x18D1
    private val ACCESSORY_PRODUCT_IDS = intArrayOf(0x2D00, 0x2D01, 0x2D04, 0x2D05)

    private const val REQUEST_GET_PROTOCOL = 51
    private const val REQUEST_SEND_STRING = 52
    private const val REQUEST_START = 53

    private const val INDEX_MANUFACTURER = 0
    private const val INDEX_MODEL = 1
    private const val INDEX_DESCRIPTION = 2
    private const val INDEX_VERSION = 3
    private const val INDEX_URI = 4
    private const val INDEX_SERIAL = 5

    // Values a phone recognises as an Android Auto capable head unit.
    private const val MANUFACTURER = "Android"
    private const val MODEL = "Android Auto"
    private const val DESCRIPTION = "Android Auto"
    private const val VERSION = "2.0.1"
    private const val URI = "https://www.android.com/auto"
    private const val SERIAL = "HU-TabletAA"

    fun isInAccessoryMode(device: UsbDevice): Boolean =
        device.vendorId == GOOGLE_VENDOR_ID && ACCESSORY_PRODUCT_IDS.contains(device.productId)

    /**
     * Requests that [device] switches into accessory mode. On success the device detaches and
     * re-attaches shortly after, which is signalled through the USB attach broadcast.
     */
    fun requestAccessoryMode(manager: UsbManager, device: UsbDevice): Boolean {
        val connection = manager.openDevice(device) ?: run {
            Log.w(TAG, "Unable to open ${device.deviceName}")
            return false
        }
        try {
            val buffer = ByteArray(2)
            val protocolResult = connection.controlTransfer(
                UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_VENDOR,
                REQUEST_GET_PROTOCOL, 0, 0, buffer, buffer.size, CONTROL_TIMEOUT_MS
            )
            if (protocolResult < 2) {
                Log.i(TAG, "Device does not support AOAP (result=$protocolResult)")
                return false
            }
            val protocolVersion = ((buffer[1].toInt() and 0xFF) shl 8) or (buffer[0].toInt() and 0xFF)
            Log.i(TAG, "AOAP protocol version $protocolVersion")
            if (protocolVersion < 1) return false

            sendString(connection, INDEX_MANUFACTURER, MANUFACTURER)
            sendString(connection, INDEX_MODEL, MODEL)
            sendString(connection, INDEX_DESCRIPTION, DESCRIPTION)
            sendString(connection, INDEX_VERSION, VERSION)
            sendString(connection, INDEX_URI, URI)
            sendString(connection, INDEX_SERIAL, SERIAL)

            val started = connection.controlTransfer(
                UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_VENDOR,
                REQUEST_START, 0, 0, null, 0, CONTROL_TIMEOUT_MS
            )
            Log.i(TAG, "Accessory start result $started")
            return started >= 0
        } finally {
            connection.close()
        }
    }

    /** Opens the bulk endpoints of a phone that is already in accessory mode. */
    fun openTransport(manager: UsbManager, device: UsbDevice): UsbTransport {
        val connection = manager.openDevice(device)
            ?: throw TransportException("Unable to open USB device ${device.deviceName}")
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            var input: UsbEndpoint? = null
            var output: UsbEndpoint? = null
            for (e in 0 until iface.endpointCount) {
                val endpoint = iface.getEndpoint(e)
                if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (endpoint.direction == UsbConstants.USB_DIR_IN) input = endpoint else output = endpoint
            }
            if (input != null && output != null && connection.claimInterface(iface, true)) {
                return UsbTransport(connection, iface, input, output)
            }
        }
        connection.close()
        throw TransportException("No bulk endpoints found on ${device.deviceName}")
    }

    private fun sendString(connection: UsbDeviceConnection, index: Int, value: String) {
        val bytes = (value + "\u0000").toByteArray(Charsets.UTF_8)
        connection.controlTransfer(
            UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_VENDOR,
            REQUEST_SEND_STRING, 0, index, bytes, bytes.size, CONTROL_TIMEOUT_MS
        )
    }

    private const val CONTROL_TIMEOUT_MS = 1000
}

/** [Transport] backed by the USB bulk endpoints of a phone in accessory mode. */
class UsbTransport(
    private val connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val input: UsbEndpoint,
    private val output: UsbEndpoint
) : Transport {

    @Volatile
    private var closed = false

    override fun send(data: ByteArray, timeoutMs: Int) {
        var offset = 0
        while (offset < data.size) {
            if (closed) throw TransportException("Transport closed")
            val chunk = minOf(MAX_TRANSFER, data.size - offset)
            val payload = if (offset == 0 && chunk == data.size) data else data.copyOfRange(offset, offset + chunk)
            val written = connection.bulkTransfer(output, payload, chunk, timeoutMs)
            if (written < 0) throw TransportException("USB write failed at offset $offset")
            offset += written
        }
    }

    override fun receive(buffer: ByteArray, timeoutMs: Int): Int {
        if (closed) throw TransportException("Transport closed")
        val read = connection.bulkTransfer(input, buffer, minOf(buffer.size, MAX_TRANSFER), timeoutMs)
        if (read < 0) {
            // A negative result is either a timeout or a broken link; the caller retries.
            return 0
        }
        return read
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { connection.releaseInterface(iface) }
        runCatching { connection.close() }
    }

    private companion object {
        const val MAX_TRANSFER = 16384
    }
}
