package com.ssheadunit.transport

import org.junit.Assert.assertEquals
import org.junit.Test

/** Mapping of libusb transfer results onto the transport's recovery behaviour. */
class LibUsbResultTest {

    @Test
    fun `a transferred byte count succeeds`() {
        assertEquals(LibUsbAction.OK, LibUsbResult.actionFor(0))
        assertEquals(LibUsbAction.OK, LibUsbResult.actionFor(512))
    }

    @Test
    fun `a timeout means the link is idle`() {
        assertEquals(LibUsbAction.IDLE, LibUsbResult.actionFor(LibUsb.ERROR_TIMEOUT))
    }

    @Test
    fun `a stalled endpoint is recoverable`() {
        assertEquals(LibUsbAction.STALLED, LibUsbResult.actionFor(LibUsb.ERROR_PIPE))
        assertEquals(LibUsbAction.STALLED, LibUsbResult.actionFor(LibUsb.ERROR_OVERFLOW))
    }

    @Test
    fun `transient failures are retried`() {
        assertEquals(LibUsbAction.RETRY, LibUsbResult.actionFor(LibUsb.ERROR_IO))
        assertEquals(LibUsbAction.RETRY, LibUsbResult.actionFor(LibUsb.ERROR_INTERRUPTED))
        assertEquals(LibUsbAction.RETRY, LibUsbResult.actionFor(LibUsb.ERROR_BUSY))
    }

    @Test
    fun `a missing device ends the session`() {
        assertEquals(LibUsbAction.FATAL, LibUsbResult.actionFor(LibUsb.ERROR_NO_DEVICE))
        assertEquals(LibUsbAction.FATAL, LibUsbResult.actionFor(LibUsb.ERROR_ACCESS))
    }
}
