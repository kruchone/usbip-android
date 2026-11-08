package dev.zk.usbip.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class UsbDescriptorsTest {
    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun device(bcdUsb: String, mps0: String) = "12 01 $bcdUsb 00 00 00 $mps0 8123 4567 0001 01 02 03 01"
    private val config = "09 02 2000 01 01 00 80 32"
    private val iface = "09 04 00 00 02 08 06 50 00"

    @Test
    fun highSpeedMassStorage() {
        val raw = hex(device("0002", "40") + config + iface + "07 05 81 02 0002 00" + "07 05 02 02 0002 00")
        val d = UsbDescriptors.parse(raw)
        assertEquals(0x0200, d.bcdUsb)
        assertEquals(0x0100, d.bcdDevice)
        assertEquals(2, d.endpoints.size)
        assertEquals(512, d.endpoints[0].maxPacketSize)
        assertEquals(UsbSpeed.HIGH, d.guessSpeed())
    }

    @Test
    fun fullSpeedSerial() {
        val raw = hex(device("1001", "40") + config + iface + "07 05 81 02 4000 00" + "07 05 02 02 4000 00")
        assertEquals(UsbSpeed.FULL, UsbDescriptors.parse(raw).guessSpeed())
    }

    @Test
    fun lowSpeedKeyboard() {
        val raw = hex(device("1001", "08") + config + "09 04 00 00 01 03 01 01 00" + "07 05 81 03 0800 0a")
        assertEquals(UsbSpeed.LOW, UsbDescriptors.parse(raw).guessSpeed())
    }

    @Test
    fun qualifierImpliesHighSpeed() {
        val raw = hex(device("0002", "40") + config + "09 04 00 00 01 03 01 01 00" + "07 05 81 03 4000 04")
        val d = UsbDescriptors.parse(raw)
        assertEquals(UsbSpeed.FULL, d.guessSpeed(hasDeviceQualifier = false))
        assertEquals(UsbSpeed.HIGH, d.guessSpeed(hasDeviceQualifier = true))
    }

    @Test
    fun superSpeed() {
        val raw = hex(device("2003", "09") + config + iface + "07 05 81 02 0004 00" + "06 30 0f 00 0000")
        assertEquals(UsbSpeed.SUPER, UsbDescriptors.parse(raw).guessSpeed())
    }
}
