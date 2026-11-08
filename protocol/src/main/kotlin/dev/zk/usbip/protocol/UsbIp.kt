package dev.zk.usbip.protocol

/** Constants of the USB/IP wire protocol (version 1.1.1, as used by Linux usbip and usbip-win2). */
object UsbIp {
    const val VERSION = 0x0111
    const val DEFAULT_PORT = 3240

    // Operation (handshake) codes.
    const val OP_REQ_DEVLIST = 0x8005
    const val OP_REP_DEVLIST = 0x0005
    const val OP_REQ_IMPORT = 0x8003
    const val OP_REP_IMPORT = 0x0003

    // Operation status codes (usbip_network.h).
    const val ST_OK = 0x00
    const val ST_NA = 0x01
    const val ST_DEV_BUSY = 0x02
    const val ST_DEV_ERR = 0x03
    const val ST_NODEV = 0x04
    const val ST_ERROR = 0x05

    // URB phase commands.
    const val CMD_SUBMIT = 0x0001
    const val CMD_UNLINK = 0x0002
    const val RET_SUBMIT = 0x0003
    const val RET_UNLINK = 0x0004

    const val DIR_OUT = 0
    const val DIR_IN = 1

    /** number_of_packets value used for non-isochronous transfers. */
    const val NON_ISO_PACKETS = -1 // 0xffffffff

    /** URB transfer_flags bit: terminate an OUT transfer that is a multiple of wMaxPacketSize with a ZLP. */
    const val URB_ZERO_PACKET = 0x0040
}

/** Linux errno values; USB/IP carries URB status as negative Linux errno. */
object Errno {
    const val ENOENT = 2
    const val EXDEV = 18
    const val ENODEV = 19
    const val EINVAL = 22
    const val EPIPE = 32
    const val EPROTO = 71
    const val ECONNRESET = 104
    const val ESHUTDOWN = 108
    const val ETIMEDOUT = 110
}

/** Linux enum usb_device_speed. */
enum class UsbSpeed(val code: Int) {
    UNKNOWN(0), LOW(1), FULL(2), HIGH(3), WIRELESS(4), SUPER(5), SUPER_PLUS(6);

    companion object {
        /** Parses the value of sysfs `speed` (in Mbit/s, e.g. "1.5", "12", "480", "5000"). */
        fun fromSysfs(value: String): UsbSpeed = when (value.trim()) {
            "1.5" -> LOW
            "12" -> FULL
            "480" -> HIGH
            "5000" -> SUPER
            "10000", "20000" -> SUPER_PLUS
            else -> UNKNOWN
        }
    }
}
