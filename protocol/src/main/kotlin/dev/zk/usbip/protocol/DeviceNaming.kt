package dev.zk.usbip.protocol

import java.util.zip.GZIPInputStream

/**
 * Vendor and product names from the public USB ID database (usb.ids from linux-usb.org,
 * dual-licensed GPL-2.0-or-later / BSD-3-Clause), bundled gzipped as a classpath resource.
 */
object UsbIds {
    private class Db(val vendors: Map<Int, String>, val products: Map<Int, String>)

    private val db: Db by lazy { load() }

    fun vendor(vid: Int): String? = db.vendors[vid]

    fun product(vid: Int, pid: Int): String? = db.products[(vid shl 16) or pid]

    /** "Logitech, Inc." -> "Logitech". */
    fun shortVendor(vid: Int): String? {
        var v = vendor(vid) ?: return null
        for (suffix in listOf(", Inc.", " Inc.", ", Ltd", " Co., Ltd", " Corp.", ", Corp.", " Corporation", " GmbH", " AG", " Ltd.")) {
            val i = v.indexOf(suffix, ignoreCase = true)
            if (i > 0) v = v.substring(0, i)
        }
        return v.trim().trimEnd(',')
    }

    private fun load(): Db {
        val vendors = HashMap<Int, String>(4096)
        val products = HashMap<Int, String>(32768)
        val stream = UsbIds::class.java.getResourceAsStream("/usb.ids.gz") ?: return Db(vendors, products)
        GZIPInputStream(stream).bufferedReader().useLines { lines ->
            var vendor = -1
            for (line in lines) {
                if (line.isEmpty() || line[0] == '#') continue
                if (line[0] != '\t') {
                    // "046d  Logitech, Inc."; any other top-level line ("C 00 ...") ends the vendor section.
                    vendor = if (line.length > 6) line.substring(0, 4).toIntOrNull(16) ?: -1 else -1
                    if (vendor < 0) {
                        if (vendors.isNotEmpty()) break else continue
                    }
                    vendors[vendor] = line.substring(6).trim()
                } else if (vendor >= 0 && line.length > 7 && line[1] != '\t') {
                    val pid = line.substring(1, 5).toIntOrNull(16) ?: continue
                    products[(vendor shl 16) or pid] = line.substring(7).trim()
                }
            }
        }
        return Db(vendors, products)
    }
}

/** Picks the most informative name and description for a USB device. Mirrors usbip-windows' DeviceNames. */
object DeviceNaming {
    private val generic = setOf(
        "usb input device", "usb composite device", "usb device", "hid-compliant device", "usb mass storage device",
        "unknown usb device", "usb serial device", "usb receiver", "usb-serial controller", "usb2.0 hub",
    )

    fun isGeneric(s: String?) = s.isNullOrBlank() || s.trim().lowercase() in generic

    /**
     * Custom label, else the first specific candidate (e.g. the device's product string), else the USB ID database,
     * else a generic candidate prefixed with the vendor. [manufacturer] is used when the database has no vendor.
     */
    fun best(vid: Int, pid: Int, custom: String?, manufacturer: String?, vararg candidates: String?): String {
        if (!custom.isNullOrBlank()) return custom.trim()
        val vendor = UsbIds.shortVendor(vid) ?: manufacturer?.trim()?.takeIf { it.isNotEmpty() }
        candidates.firstOrNull { !isGeneric(it) }?.let { return it!!.trim() }
        UsbIds.product(vid, pid)?.let { p ->
            return if (vendor != null && !p.contains(vendor, ignoreCase = true)) "$vendor $p" else p
        }
        candidates.firstOrNull { !it.isNullOrBlank() }?.let { g ->
            return if (vendor != null) "$vendor ${g.trim()}" else g.trim()
        }
        return if (vendor != null) "$vendor device" else "USB device %04x:%04x".format(vid, pid)
    }

    /** "Logitech · Keyboard, Mouse · 046d:c52b · bus id 1-2" */
    fun detail(vid: Int, pid: Int, manufacturer: String?, kinds: List<String>, extra: String? = null): String =
        listOfNotNull(
            UsbIds.shortVendor(vid) ?: manufacturer?.trim()?.takeIf { it.isNotEmpty() },
            kinds.takeIf { it.isNotEmpty() }?.joinToString(", "),
            "%04x:%04x".format(vid, pid),
            extra,
        ).joinToString(" · ")

    /** What a device does, from its interface (or device) class codes. */
    fun kinds(interfaces: List<UsbIpInterface>): List<String> {
        val kinds = LinkedHashSet<String>()
        for (i in interfaces) {
            when {
                i.interfaceClass == 0x03 && i.interfaceSubClass == 1 && i.interfaceProtocol == 1 -> kinds += "Keyboard"
                i.interfaceClass == 0x03 && i.interfaceSubClass == 1 && i.interfaceProtocol == 2 -> kinds += "Mouse"
                i.interfaceClass == 0x01 -> kinds += "Audio"
                i.interfaceClass == 0x02 || i.interfaceClass == 0x0A -> kinds += "Serial port"
                i.interfaceClass == 0x07 -> kinds += "Printer"
                i.interfaceClass == 0x08 -> kinds += "Storage"
                i.interfaceClass == 0x0B -> kinds += "Smart card reader"
                i.interfaceClass == 0x0E -> kinds += "Camera"
                i.interfaceClass == 0xE0 -> kinds += "Wireless"
                i.interfaceClass == 0x09 -> kinds += "Hub"
            }
        }
        if (kinds.isEmpty() && interfaces.any { it.interfaceClass == 0x03 }) kinds += "HID device"
        return kinds.toList()
    }
}
