package com.ssheadunit.transport

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.ssheadunit.util.HeadUnitLog

/** USB standard request code for CLEAR_FEATURE. */
private const val USB_REQUEST_CLEAR_FEATURE = 1

/** USB feature selector for ENDPOINT_HALT. */
private const val USB_FEATURE_ENDPOINT_HALT = 0

/** USB recipient field for an endpoint target. */
private const val USB_RECIP_ENDPOINT = 0x02

/**
 * Android Open Accessory Protocol (AOAP) helper.
 *
 * A factory head unit is the USB host and the phone is the accessory. The tablet does the same:
 * it asks the phone to switch into accessory mode, after which the phone re-enumerates with a
 * Google vendor id and exposes a pair of bulk endpoints used for the Android Auto session.
 *
 * Third party wireless adapters (for example the Mayton AutoPro X) sit in the same place as a
 * phone: they impersonate one over AOAP and bridge to the real phone over Wi-Fi. They enumerate
 * with their own ids and expose extra interfaces, so device and interface selection is capability
 * based rather than a list of known ids.
 */
object Aoap {

    private const val TAG = "Aoap"

    const val GOOGLE_VENDOR_ID = AccessoryDetection.GOOGLE_VENDOR_ID

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
    private const val SERIAL = "HU-ssHeadUnit"

    /** Outcome of an accessory mode request. */
    enum class SwitchResult {
        /** The device accepted the accessory strings; it will re-enumerate shortly. */
        SWITCHED,

        /** The device did not answer the AOAP requests; it may still be usable as it is. */
        INCONCLUSIVE,

        /** The device cannot be used at all (it could not even be opened). */
        UNSUPPORTED
    }

    /** Describes [device] in the plain form used by [AccessoryDetection]. */
    fun describe(device: UsbDevice): DeviceDescriptor {
        val interfaces = (0 until device.interfaceCount).map { index ->
            val iface = device.getInterface(index)
            InterfaceDescriptor(
                index = index,
                id = iface.id,
                interfaceClass = iface.interfaceClass,
                subclass = iface.interfaceSubclass,
                protocol = iface.interfaceProtocol,
                endpoints = (0 until iface.endpointCount).map { e ->
                    val endpoint = iface.getEndpoint(e)
                    EndpointDescriptor(
                        address = endpoint.address,
                        type = endpoint.type,
                        directionIn = endpoint.direction == UsbConstants.USB_DIR_IN
                    )
                }
            )
        }
        return DeviceDescriptor(device.vendorId, device.productId, interfaces)
    }

    fun isInAccessoryMode(device: UsbDevice): Boolean =
        AccessoryDetection.isInAccessoryMode(device.vendorId, device.productId)

    /** True when a projection session can be attempted without switching the device first. */
    fun isSessionReady(device: UsbDevice): Boolean = AccessoryDetection.isSessionReady(describe(device))

    /** True when [device] is worth attempting as an Android Auto phone or adapter. */
    fun isCandidate(device: UsbDevice): Boolean = AccessoryDetection.candidateScore(describe(device)) > 0

    /** True when [device] exposes an interface that could carry a session. */
    fun hasUsableInterface(device: UsbDevice): Boolean =
        AccessoryDetection.sessionInterface(describe(device)) != null

    /** Picks the most likely phone or adapter out of [devices], or null when none qualifies. */
    fun pickCandidate(devices: Iterable<UsbDevice>): UsbDevice? =
        AccessoryDetection.pickCandidate(devices, ::describe)

    /** Logs the full descriptor of [device]; the entry point for diagnosing a specific adapter. */
    fun logDevice(device: UsbDevice, prefix: String) {
        val descriptor = describe(device)
        val adapter = AccessoryDetection.knownAdapterName(descriptor)?.let { " ($it)" } ?: ""
        HeadUnitLog.i(TAG, "$prefix ${descriptor.describe()}$adapter")
        descriptor.interfaces.forEach { HeadUnitLog.i(TAG, "  ${it.describe()}") }
    }

    /**
     * Requests that [device] switches into accessory mode. On success the device detaches and
     * re-attaches shortly after, which is signalled through the USB attach broadcast.
     *
     * A device that does not answer the AOAP control requests is reported as
     * [SwitchResult.INCONCLUSIVE] rather than as a failure: some adapters answer only on a later
     * enumeration, and the caller can still try to open the interfaces the device already has.
     */
    fun requestAccessoryMode(manager: UsbManager, device: UsbDevice): SwitchResult {
        logDevice(device, "Requesting accessory mode on")
        val connection = manager.openDevice(device) ?: run {
            HeadUnitLog.w(TAG, "Unable to open ${device.deviceName}")
            return SwitchResult.UNSUPPORTED
        }
        try {
            val buffer = ByteArray(2)
            val protocolResult = connection.controlTransfer(
                UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_VENDOR,
                REQUEST_GET_PROTOCOL, 0, 0, buffer, buffer.size, CONTROL_TIMEOUT_MS
            )
            if (protocolResult < 2) {
                HeadUnitLog.w(TAG, "No AOAP answer (result=$protocolResult); treating as inconclusive")
                return SwitchResult.INCONCLUSIVE
            }
            val protocolVersion = ((buffer[1].toInt() and 0xFF) shl 8) or (buffer[0].toInt() and 0xFF)
            HeadUnitLog.i(TAG, "AOAP protocol version $protocolVersion")
            if (protocolVersion < 1) return SwitchResult.INCONCLUSIVE

            // A partial identification (e.g. manufacturer sent but model missing) can cause the
            // phone to ignore the accessory start request or fail to switch into accessory mode,
            // so the whole switch is aborted as soon as one of the strings fails to send.
            val identified =
                sendString(connection, INDEX_MANUFACTURER, MANUFACTURER) &&
                    sendString(connection, INDEX_MODEL, MODEL) &&
                    sendString(connection, INDEX_DESCRIPTION, DESCRIPTION) &&
                    sendString(connection, INDEX_VERSION, VERSION) &&
                    sendString(connection, INDEX_URI, URI) &&
                    sendString(connection, INDEX_SERIAL, SERIAL)
            if (!identified) {
                HeadUnitLog.w(TAG, "Aborting accessory switch; not all identification strings were accepted")
                return SwitchResult.INCONCLUSIVE
            }

            val started = connection.controlTransfer(
                UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_VENDOR,
                REQUEST_START, 0, 0, null, 0, CONTROL_TIMEOUT_MS
            )
            HeadUnitLog.i(TAG, "Accessory start result $started")
            if (started < 0) return SwitchResult.INCONCLUSIVE
            // Give the device a moment to begin re-enumerating before the caller's polling loop
            // starts looking for it.
            try {
                Thread.sleep(ACCESSORY_START_GRACE_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            return SwitchResult.SWITCHED
        } finally {
            connection.close()
        }
    }

    /**
     * Opens the bulk endpoints of a phone or adapter that is already in accessory mode.
     *
     * libusb drives the endpoints when the native library is available: it claims the interface
     * on the descriptor of the framework connection and reports the real usbfs status of every
     * transfer, which is what makes a stalled or idle link recoverable. The framework transport
     * remains as a fallback for builds or devices where libusb cannot claim the interface.
     */
    fun openTransport(manager: UsbManager, device: UsbDevice): Transport {
        logDevice(device, "Opening")
        val descriptor = describe(device)
        val selected = AccessoryDetection.sessionInterface(descriptor)
            ?: throw TransportException("No usable bulk interface on ${device.deviceName}")
        HeadUnitLog.i(TAG, "Selected ${selected.describe()}")

        val connection = manager.openDevice(device)
            ?: throw TransportException("Unable to open USB device ${device.deviceName}")
        HeadUnitLog.i(TAG, "Opened ${device.deviceName}")
        val iface: UsbInterface = device.getInterface(selected.index)
        val inAddress = selected.bulkIn.first().address
        val outAddress = selected.bulkOut.first().address

        LibUsbTransport.open(connection, iface.id, inAddress, outAddress)?.let { return it }

        HeadUnitLog.w(TAG, "Falling back to the framework USB transport")
        val input = endpointAt(iface, inAddress)
        val output = endpointAt(iface, outAddress)
        if (input == null || output == null || !connection.claimInterface(iface, true)) {
            connection.close()
            throw TransportException("Unable to claim interface ${selected.index} on ${device.deviceName}")
        }
        HeadUnitLog.i(TAG, "Claimed interface ${selected.index} on ${device.deviceName}")
        // A freshly claimed AOAP endpoint can be left halted by the kernel driver that owned it a
        // moment ago; clearing both up front avoids a burst of read/write failures before the
        // session even starts.
        clearHaltOnOpen(connection, input)
        clearHaltOnOpen(connection, output)
        return UsbTransport(connection, iface, input, output)
    }

    /** Best effort halt clear performed right after claiming an interface; both outcomes are logged. */
    private fun clearHaltOnOpen(connection: UsbDeviceConnection, endpoint: UsbEndpoint) {
        val result = runCatching {
            connection.controlTransfer(
                UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_STANDARD or USB_RECIP_ENDPOINT,
                USB_REQUEST_CLEAR_FEATURE,
                USB_FEATURE_ENDPOINT_HALT,
                endpoint.address,
                null,
                0,
                CONTROL_TIMEOUT_MS
            )
        }.onFailure {
            HeadUnitLog.w(TAG, "Unable to clear halt on endpoint 0x%02x: ${it.message}".format(endpoint.address))
        }.getOrDefault(-1)
        if (result >= 0) {
            HeadUnitLog.i(TAG, "Cleared halt on endpoint 0x%02x".format(endpoint.address))
        } else {
            HeadUnitLog.w(TAG, "Failed to clear halt on endpoint 0x%02x (result=$result)".format(endpoint.address))
        }
    }

    private fun endpointAt(iface: UsbInterface, address: Int): UsbEndpoint? =
        (0 until iface.endpointCount).map { iface.getEndpoint(it) }.firstOrNull { it.address == address }

    /** Sends one AOAP identification string; returns false if the transfer itself failed. */
    private fun sendString(connection: UsbDeviceConnection, index: Int, value: String): Boolean {
        val bytes = (value + "\u0000").toByteArray(Charsets.UTF_8)
        val len = connection.controlTransfer(
            UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_VENDOR,
            REQUEST_SEND_STRING, 0, index, bytes, bytes.size, CONTROL_TIMEOUT_MS
        )
        if (len < 0) {
            // Negative means the USB transfer itself failed (e.g. device disconnected or timed
            // out); sending the accessory start request afterwards would be pointless.
            HeadUnitLog.w(TAG, "Error sending accessory string index=$index \"$value\" (result=$len)")
            return false
        }
        // len == bytes.size is the ideal ACK. Some phones return 0 for a successful OUT control
        // transfer (they accept the data but report 0 bytes in the data stage), so any
        // non-negative result is treated as success.
        if (len != bytes.size) {
            HeadUnitLog.w(TAG, "Unexpected accessory string result len=$len (expected ${bytes.size}) index=$index \"$value\"")
        } else {
            HeadUnitLog.i(TAG, "Sent accessory string index=$index \"$value\"")
        }
        return true
    }

    private const val CONTROL_TIMEOUT_MS = 1000

    /** Grace period after ACC_REQ_START to let the device begin re-enumerating. */
    private const val ACCESSORY_START_GRACE_MS = 500L
}

/**
 * [Transport] backed by the USB bulk endpoints of a phone in accessory mode.
 *
 * A bulk read that returns a negative result is either a benign timeout or a broken link. The two
 * are told apart by how long the read took: a read that fails long before its timeout expired did
 * not wait for data, so the endpoint is stalled or gone. A stalled endpoint can often be woken up
 * again by clearing its halt condition, so that is attempted before the failure is allowed to pile
 * up. Once enough failures pile up regardless the link is declared dead instead of letting the
 * session block for ever.
 */
class UsbTransport(
    private val connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val input: UsbEndpoint,
    private val output: UsbEndpoint
) : Transport {

    @Volatile
    private var closed = false

    private var consecutiveErrors = 0

    override fun send(data: ByteArray, timeoutMs: Int) {
        var offset = 0
        while (offset < data.size) {
            if (closed) throw TransportException("Transport closed")
            val chunk = minOf(MAX_TRANSFER, data.size - offset)
            val payload = if (offset == 0 && chunk == data.size) data else data.copyOfRange(offset, offset + chunk)
            var written = connection.bulkTransfer(output, payload, chunk, timeoutMs)
            if (written < 0) {
                // The endpoint may simply be stalled; clear it and retry once before giving up.
                clearHalt(output)
                written = connection.bulkTransfer(output, payload, chunk, timeoutMs)
            }
            if (written < 0) throw TransportException("USB write failed at offset $offset")
            offset += written
        }
    }

    override fun receive(buffer: ByteArray, timeoutMs: Int): Int {
        if (closed) throw TransportException("Transport closed")
        val startedAt = System.nanoTime()
        val read = connection.bulkTransfer(input, buffer, minOf(buffer.size, MAX_TRANSFER), timeoutMs)
        if (read >= 0) {
            consecutiveErrors = 0
            return read
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        if (elapsedMs >= timeoutMs.toLong() * TIMEOUT_TOLERANCE_NUMERATOR / TIMEOUT_TOLERANCE_DENOMINATOR) {
            // The read waited for (almost) its full timeout: the peer simply had nothing to send.
            consecutiveErrors = 0
            return 0
        }
        consecutiveErrors++
        // The endpoint may simply be stalled (a common state right after accessory mode is
        // entered); clearing it gives the next read a chance to succeed instead of letting
        // failures pile up towards a link that may still be usable.
        clearHalt(input)
        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
            throw TransportException("USB read failed $consecutiveErrors times in a row; link lost")
        }
        return 0
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { connection.releaseInterface(iface) }
            .onSuccess { HeadUnitLog.i(TAG, "Released interface ${iface.id}") }
            .onFailure { HeadUnitLog.w(TAG, "Unable to release interface ${iface.id}: ${it.message}") }
        runCatching { connection.close() }
            .onFailure { HeadUnitLog.w(TAG, "Unable to close USB connection: ${it.message}") }
    }

    /** Best effort recovery from a halted bulk endpoint; both outcomes are logged. */
    private fun clearHalt(endpoint: UsbEndpoint) {
        val result = runCatching {
            connection.controlTransfer(
                UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_STANDARD or USB_RECIP_ENDPOINT,
                USB_REQUEST_CLEAR_FEATURE,
                USB_FEATURE_ENDPOINT_HALT,
                endpoint.address,
                null,
                0,
                0
            )
        }.onFailure {
            HeadUnitLog.w(TAG, "Unable to clear halt on endpoint 0x%02x: ${it.message}".format(endpoint.address))
        }.getOrDefault(-1)
        if (result >= 0) {
            HeadUnitLog.i(TAG, "Cleared halt on endpoint 0x%02x".format(endpoint.address))
        } else {
            HeadUnitLog.w(TAG, "Failed to clear halt on endpoint 0x%02x (result=$result)".format(endpoint.address))
        }
    }

    private companion object {
        const val MAX_TRANSFER = 16384

        /** A read that returned before 3/4 of its timeout failed rather than timed out. */
        const val TIMEOUT_TOLERANCE_NUMERATOR = 3L
        const val TIMEOUT_TOLERANCE_DENOMINATOR = 4L

        /** How many immediate read failures are tolerated before the link is declared dead. */
        const val MAX_CONSECUTIVE_ERRORS = 10
    }
}
