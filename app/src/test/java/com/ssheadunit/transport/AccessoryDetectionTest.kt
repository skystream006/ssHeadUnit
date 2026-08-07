package com.ssheadunit.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Device and interface selection, including third party wireless adapters. */
class AccessoryDetectionTest {

    private fun bulkPair(offset: Int = 0) = listOf(
        EndpointDescriptor(address = 0x81 + offset, type = UsbClass.XFER_BULK, directionIn = true),
        EndpointDescriptor(address = 0x01 + offset, type = UsbClass.XFER_BULK, directionIn = false)
    )

    private fun accessoryInterface(index: Int = 0) = InterfaceDescriptor(
        index = index, id = index, interfaceClass = UsbClass.VENDOR_SPECIFIC,
        subclass = UsbClass.VENDOR_SPECIFIC, protocol = 0, endpoints = bulkPair()
    )

    private fun cdcDataInterface(index: Int = 0) = InterfaceDescriptor(
        index = index, id = index, interfaceClass = UsbClass.CDC_DATA,
        subclass = 0, protocol = 0, endpoints = bulkPair(offset = 1)
    )

    private fun adbInterface(index: Int = 0) = InterfaceDescriptor(
        index = index, id = index, interfaceClass = UsbClass.VENDOR_SPECIFIC,
        subclass = 0x42, protocol = 1, endpoints = bulkPair(offset = 2)
    )

    private fun hidInterface(index: Int = 0) = InterfaceDescriptor(
        index = index, id = index, interfaceClass = UsbClass.HID,
        subclass = 0, protocol = 0, endpoints = bulkPair(offset = 3)
    )

    @Test
    fun acceptsTheWholeAccessoryProductIdRange() {
        for (productId in 0x2D00..0x2D05) {
            assertTrue(
                "0x%04x must be accessory mode".format(productId),
                AccessoryDetection.isInAccessoryMode(AccessoryDetection.GOOGLE_VENDOR_ID, productId)
            )
        }
        assertFalse(AccessoryDetection.isInAccessoryMode(AccessoryDetection.GOOGLE_VENDOR_ID, 0x2D06))
        assertFalse(AccessoryDetection.isInAccessoryMode(0x05AC, 0x2D00))
    }

    @Test
    fun recognisesAnAccessoryInterfaceOnUnknownIds() {
        val adapter = DeviceDescriptor(0x05AC, 0x12A8, listOf(cdcDataInterface(0), accessoryInterface(1)))
        assertFalse(AccessoryDetection.isInAccessoryMode(adapter))
        assertTrue(AccessoryDetection.hasAccessoryInterface(adapter))
        assertTrue(AccessoryDetection.isSessionReady(adapter))
        assertEquals("AutoPro X style adapter (normal mode)", AccessoryDetection.knownAdapterName(adapter))
    }

    @Test
    fun prefersTheAccessoryInterfaceOverCdcData() {
        val adapter = DeviceDescriptor(0x0525, 0xA4A7, listOf(cdcDataInterface(0), accessoryInterface(1)))
        assertEquals(1, AccessoryDetection.sessionInterface(adapter)?.index)
    }

    @Test
    fun fallsBackToCdcDataWhenNoVendorInterfaceExists() {
        val adapter = DeviceDescriptor(0x0525, 0xA4A7, listOf(cdcDataInterface(0)))
        assertEquals(0, AccessoryDetection.sessionInterface(adapter)?.index)
        assertFalse(AccessoryDetection.isSessionReady(adapter))
    }

    @Test
    fun neverPicksHidOrIncompleteInterfaces() {
        val keyboard = DeviceDescriptor(0x1234, 0x5678, listOf(hidInterface(0)))
        assertNull(AccessoryDetection.sessionInterface(keyboard))
        assertEquals(0, AccessoryDetection.candidateScore(keyboard))

        val halfDuplex = DeviceDescriptor(
            0x1234, 0x5678,
            listOf(
                InterfaceDescriptor(
                    0, 0, UsbClass.VENDOR_SPECIFIC, UsbClass.VENDOR_SPECIFIC, 0,
                    listOf(EndpointDescriptor(0x81, UsbClass.XFER_BULK, true))
                )
            )
        )
        assertNull(AccessoryDetection.sessionInterface(halfDuplex))
        assertFalse(AccessoryDetection.hasAccessoryInterface(halfDuplex))
    }

    @Test
    fun adbInterfaceIsNotMistakenForAnAccessoryInterface() {
        val phone = DeviceDescriptor(0x04E8, 0x6860, listOf(adbInterface(0)))
        assertFalse(AccessoryDetection.hasAccessoryInterface(phone))
        assertFalse(AccessoryDetection.isSessionReady(phone))
    }

    @Test
    fun picksTheAccessoryDeviceBehindAHub() {
        val keyboard = DeviceDescriptor(0x1234, 0x5678, listOf(hidInterface(0)))
        val adapter = DeviceDescriptor(0x05AC, 0x12A8, listOf(cdcDataInterface(0)))
        val phone = DeviceDescriptor(AccessoryDetection.GOOGLE_VENDOR_ID, 0x2D01, listOf(accessoryInterface(0)))
        val picked = AccessoryDetection.pickCandidate(listOf(keyboard, adapter, phone)) { it }
        assertEquals(phone, picked)
    }

    @Test
    fun returnsNoCandidateWhenNothingQualifies() {
        val keyboard = DeviceDescriptor(0x1234, 0x5678, listOf(hidInterface(0)))
        assertNull(AccessoryDetection.pickCandidate(listOf(keyboard)) { it })
    }

    @Test
    fun ranksAKnownAdapterAboveAnUnrelatedBulkDevice() {
        val adapter = DeviceDescriptor(0x05AC, 0x12A8, listOf(cdcDataInterface(0)))
        val storage = DeviceDescriptor(0x1234, 0x5678, listOf(cdcDataInterface(0)))
        assertEquals(adapter, AccessoryDetection.pickCandidate(listOf(storage, adapter)) { it })
    }
}
