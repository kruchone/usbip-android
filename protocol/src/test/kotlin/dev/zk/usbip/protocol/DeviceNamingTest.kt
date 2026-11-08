package dev.zk.usbip.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceNamingTest {
    @Test
    fun usbIdsLookup() {
        assertEquals("Logitech, Inc.", UsbIds.vendor(0x046d))
        assertEquals("Logitech", UsbIds.shortVendor(0x046d))
        assertEquals("Unifying Receiver", UsbIds.product(0x046d, 0xc52b))
    }

    @Test
    fun genericProductStringFallsBackToDatabaseWithVendor() {
        // The receiver reports "USB Receiver"; the database knows it as the Unifying Receiver.
        assertEquals("Logitech Unifying Receiver", DeviceNaming.best(0x046d, 0xc52b, null, "Logitech", "USB Receiver"))
    }

    @Test
    fun specificProductStringWins() {
        assertEquals("AT2020 USB", DeviceNaming.best(0x0909, 0x0018, null, null, "AT2020 USB"))
    }

    @Test
    fun customLabelWins() {
        assertEquals("Desk keyboard", DeviceNaming.best(0x04d9, 0x2221, " Desk keyboard ", null, "USB Keyboard"))
    }

    @Test
    fun unknownDevice() {
        assertEquals("Widget", DeviceNaming.best(0xfff0, 0x0001, null, "Acme", "Widget"))
        assertEquals("Acme USB Receiver", DeviceNaming.best(0xfff0, 0x0001, null, "Acme", "USB Receiver"))
        assertEquals("USB device fff0:0001", DeviceNaming.best(0xfff0, 0x0001, null, null))
    }

    @Test
    fun kindsAndDetail() {
        val ifaces = listOf(UsbIpInterface(3, 1, 1), UsbIpInterface(3, 1, 2), UsbIpInterface(3, 0, 0))
        val kinds = DeviceNaming.kinds(ifaces)
        assertEquals(listOf("Keyboard", "Mouse"), kinds)
        assertEquals("Logitech · Keyboard, Mouse · 046d:c52b · bus id 1-2", DeviceNaming.detail(0x046d, 0xc52b, null, kinds, "bus id 1-2"))
        assertEquals(listOf("HID device"), DeviceNaming.kinds(listOf(UsbIpInterface(3, 0, 0))))
    }
}
