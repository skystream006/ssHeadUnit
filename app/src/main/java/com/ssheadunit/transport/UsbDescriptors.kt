package com.ssheadunit.transport

/**
 * Plain description of an attached USB device.
 *
 * The Android USB classes cannot be created outside a device, so the parts of the enumeration
 * logic that decide *which* device and *which* interface carry an Android Auto session work on
 * these descriptors instead. [Aoap] converts the framework objects into them.
 */
data class EndpointDescriptor(val address: Int, val type: Int, val directionIn: Boolean)

data class InterfaceDescriptor(
    val index: Int,
    val id: Int,
    val interfaceClass: Int,
    val subclass: Int,
    val protocol: Int,
    val endpoints: List<EndpointDescriptor>
) {
    val bulkIn: List<EndpointDescriptor> get() = endpoints.filter { it.type == UsbClass.XFER_BULK && it.directionIn }
    val bulkOut: List<EndpointDescriptor> get() = endpoints.filter { it.type == UsbClass.XFER_BULK && !it.directionIn }

    /** True when the interface exposes exactly one bulk IN and one bulk OUT endpoint. */
    val hasBulkPair: Boolean get() = bulkIn.size == 1 && bulkOut.size == 1

    fun describe(): String = "interface #$index id=$id class=0x%02x/0x%02x/0x%02x endpoints=%s"
        .format(interfaceClass, subclass, protocol, endpoints.joinToString {
            "0x%02x/%s%s".format(it.address, if (it.directionIn) "in" else "out", if (it.type == UsbClass.XFER_BULK) "/bulk" else "")
        })
}

data class DeviceDescriptor(
    val vendorId: Int,
    val productId: Int,
    val interfaces: List<InterfaceDescriptor>
) {
    fun describe(): String = "usb %04x:%04x with %d interface(s)".format(vendorId, productId, interfaces.size)
}

/** USB class codes and endpoint constants used by the enumeration logic. */
object UsbClass {
    /** Mirrors `UsbConstants.USB_ENDPOINT_XFER_BULK`. */
    const val XFER_BULK = 2

    const val AUDIO = 0x01
    const val CDC_CONTROL = 0x02
    const val HID = 0x03
    const val PRINTER = 0x07
    const val MASS_STORAGE = 0x08
    const val HUB = 0x09
    const val CDC_DATA = 0x0A
    const val VIDEO = 0x0E
    const val VENDOR_SPECIFIC = 0xFF

    /** Classes that can never carry an Android Auto session. */
    val NEVER_SESSION = setOf(AUDIO, CDC_CONTROL, HID, PRINTER, MASS_STORAGE, HUB, VIDEO)
}

/**
 * Pure decision logic for finding the phone (or a third party wireless adapter that emulates one)
 * among the attached USB devices and for picking the interface that carries the session.
 *
 * Third party wireless adapters such as the Mayton AutoPro X impersonate a phone over AOAP but
 * enumerate with their own vendor and product ids, and expose extra interfaces (CDC serial in the
 * adapter's reset mode). Detection is therefore capability based, with the well known ids only
 * used as a fast path and for ranking.
 */
object AccessoryDetection {

    const val GOOGLE_VENDOR_ID = 0x18D1

    /** The full documented AOAP accessory product id range. */
    val ACCESSORY_PRODUCT_IDS: IntRange = 0x2D00..0x2D05

    /**
     * Vendor/product ids of third party wireless Android Auto adapters that are known to
     * impersonate a phone. Used to keep such a device ahead of unrelated peripherals when
     * several are attached; the session itself is still opened capability first.
     */
    val KNOWN_ADAPTER_IDS: Map<Pair<Int, Int>, String> = mapOf(
        (0x05AC to 0x12A8) to "AutoPro X style adapter (normal mode)",
        (0x0525 to 0xA4A7) to "AutoPro X style adapter (CDC/A2A reset mode)"
    )

    /** The AOAP accessory interface is vendor specific with subclass 0xFF and protocol 0. */
    private fun isAccessoryInterface(iface: InterfaceDescriptor): Boolean =
        iface.interfaceClass == UsbClass.VENDOR_SPECIFIC &&
            iface.subclass == UsbClass.VENDOR_SPECIFIC &&
            iface.protocol == 0 &&
            iface.hasBulkPair

    fun isInAccessoryMode(vendorId: Int, productId: Int): Boolean =
        vendorId == GOOGLE_VENDOR_ID && productId in ACCESSORY_PRODUCT_IDS

    fun isInAccessoryMode(device: DeviceDescriptor): Boolean = isInAccessoryMode(device.vendorId, device.productId)

    fun knownAdapterName(device: DeviceDescriptor): String? = KNOWN_ADAPTER_IDS[device.vendorId to device.productId]

    /** True when the device exposes an AOAP style accessory interface, whatever its ids are. */
    fun hasAccessoryInterface(device: DeviceDescriptor): Boolean = device.interfaces.any(::isAccessoryInterface)

    /**
     * True when a session can be attempted straight away: either the ids say the device already
     * switched into accessory mode, or it exposes an accessory interface.
     */
    fun isSessionReady(device: DeviceDescriptor): Boolean =
        isInAccessoryMode(device) || hasAccessoryInterface(device)

    /**
     * Picks the interface that most likely carries the session. AOAP accessory interfaces win,
     * then other vendor specific bulk interfaces, then any remaining bulk interface (a CDC data
     * interface, for instance). Interfaces of classes that can never carry a session, and
     * interfaces without a bulk pair, are never returned.
     */
    fun sessionInterface(device: DeviceDescriptor): InterfaceDescriptor? =
        device.interfaces.filter { interfaceScore(it) > 0 }.maxByOrNull { interfaceScore(it) }

    private fun interfaceScore(iface: InterfaceDescriptor): Int = when {
        !iface.hasBulkPair -> 0
        iface.interfaceClass in UsbClass.NEVER_SESSION -> 0
        isAccessoryInterface(iface) -> 3
        iface.interfaceClass == UsbClass.VENDOR_SPECIFIC -> 2
        else -> 1
    }

    /**
     * Ranks a device as a projection candidate. Higher is better and zero means "not a
     * candidate", so an unrelated peripheral behind a hub is never picked by accident.
     */
    fun candidateScore(device: DeviceDescriptor): Int = when {
        isInAccessoryMode(device) -> 4
        hasAccessoryInterface(device) -> 3
        knownAdapterName(device) != null -> 2
        sessionInterface(device) != null -> 1
        else -> 0
    }

    /** Picks the most likely phone or adapter out of [devices], or null when none qualifies. */
    fun <T> pickCandidate(devices: Iterable<T>, describe: (T) -> DeviceDescriptor): T? =
        devices.map { it to candidateScore(describe(it)) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first
}
