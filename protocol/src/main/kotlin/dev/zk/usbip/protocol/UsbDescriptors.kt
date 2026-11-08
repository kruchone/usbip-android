package dev.zk.usbip.protocol

/**
 * Minimal parser for the raw descriptor blob Android returns from
 * UsbDeviceConnection.getRawDescriptors() (device descriptor followed by configuration descriptors).
 */
class UsbDescriptors private constructor(
    val bcdUsb: Int,
    val maxPacketSize0: Int,
    val bcdDevice: Int,
    val deviceClass: Int,
    val deviceSubClass: Int,
    val deviceProtocol: Int,
    val endpoints: List<Endpoint>,
) {
    data class Endpoint(val address: Int, val attributes: Int, val maxPacketSize: Int) {
        val type get() = attributes and 0x03
        val isBulk get() = type == TYPE_BULK
        val isInterrupt get() = type == TYPE_INTERRUPT
    }

    /**
     * Best guess at the bus speed when the OS doesn't tell us.
     * @param hasDeviceQualifier whether the device answered GET_DESCRIPTOR(DEVICE_QUALIFIER),
     *   which only high-speed-capable devices do.
     */
    fun guessSpeed(hasDeviceQualifier: Boolean = false): UsbSpeed = when {
        bcdUsb >= 0x0300 && maxPacketSize0 == 9 -> UsbSpeed.SUPER
        endpoints.any { (it.isBulk && it.maxPacketSize > 64) || (it.isInterrupt && it.maxPacketSize > 64) } -> UsbSpeed.HIGH
        hasDeviceQualifier && bcdUsb >= 0x0200 -> UsbSpeed.HIGH
        maxPacketSize0 == 8 && endpoints.none { it.isBulk } && endpoints.all { it.maxPacketSize <= 8 } -> UsbSpeed.LOW
        else -> UsbSpeed.FULL
    }

    companion object {
        const val TYPE_CONTROL = 0
        const val TYPE_ISO = 1
        const val TYPE_BULK = 2
        const val TYPE_INTERRUPT = 3

        fun parse(raw: ByteArray): UsbDescriptors {
            require(raw.size >= 18 && raw[1].toInt() == 0x01) { "raw descriptors don't start with a device descriptor" }
            fun u8(i: Int) = raw[i].toInt() and 0xff
            fun u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
            val eps = mutableListOf<Endpoint>()
            var i = u8(0)
            while (i + 1 < raw.size) {
                val len = u8(i)
                if (len < 2 || i + len > raw.size) break
                if (u8(i + 1) == 0x05 && len >= 7) {
                    eps += Endpoint(u8(i + 2), u8(i + 3), u16(i + 4) and 0x7ff)
                }
                i += len
            }
            return UsbDescriptors(
                bcdUsb = u16(2), maxPacketSize0 = u8(7), bcdDevice = u16(12),
                deviceClass = u8(4), deviceSubClass = u8(5), deviceProtocol = u8(6), endpoints = eps,
            )
        }
    }
}
