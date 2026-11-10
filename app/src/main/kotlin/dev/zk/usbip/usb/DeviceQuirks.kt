package dev.zk.usbip.usb

import dev.zk.usbip.AppLog
import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection

/**
 * Fix-ups for devices that Android's own kernel drivers leave in a non-default state. A device was
 * usually bound to an Android driver before we claimed it, and the remote host expects it to be
 * fresh from power-on.
 */
object DeviceQuirks {
    private const val LOGITECH = 0x046d

    /**
     * Logitech Unifying / Lightspeed / Nano receivers. Android's hid-logitech-dj driver switches them
     * into "DJ mode", where mouse and keyboard input arrives as vendor reports on the HID++ interface
     * instead of on the standard keyboard and mouse endpoints. Generic HID hosts (Windows) then
     * see no input.
     */
    private val logitechDjReceivers = setOf(
        0xc52b, 0xc52f, 0xc532, 0xc534, 0xc539, 0xc53a, 0xc53d, 0xc53f, 0xc541, 0xc545, 0xc547, 0xc548,
    )

    fun afterClaim(device: UsbDevice, conn: UsbDeviceConnection, config: UsbConfiguration, tag: String) {
        if (device.vendorId == LOGITECH && device.productId in logitechDjReceivers) {
            leaveLogitechDjMode(conn, config, tag)
        }
    }

    private fun leaveLogitechDjMode(conn: UsbDeviceConnection, config: UsbConfiguration, tag: String) {
        // The HID++/DJ interface is the last HID interface (interface 2 on Unifying receivers).
        val hidpp = (0 until config.interfaceCount).map { config.getInterface(it) }
            .filter { it.alternateSetting == 0 && it.interfaceClass == UsbConstants.USB_CLASS_HID }
            .maxByOrNull { it.id } ?: return
        // DJ short report (id 0x20) to the receiver (index 0xFF): CMD_SWITCH (0x80) with an empty
        // device bit-field (no device in DJ mode = plain HID mode) and no keep-alive timeout.
        val report = ByteArray(15).also {
            it[0] = 0x20
            it[1] = 0xff.toByte()
            it[2] = 0x80.toByte()
            it[3] = 0x00
            it[4] = 0x00
        }
        // SET_REPORT(Output, id 0x20) on the HID++ interface.
        val n = conn.controlTransfer(0x21, 0x09, (0x02 shl 8) or 0x20, hidpp.id, report, report.size, 1000)
        AppLog.i(tag, "Logitech receiver: leave DJ mode on interface ${hidpp.id} -> ${if (n >= 0) "ok" else "failed ($n)"}")
    }
}
