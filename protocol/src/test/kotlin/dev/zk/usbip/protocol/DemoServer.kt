package dev.zk.usbip.protocol

/**
 * Runs the server on the JVM with a fake device that answers GET_DESCRIPTOR, for testing clients
 * and tools without a phone: ./gradlew :protocol:demoServer, then tools/usbip_probe.py 127.0.0.1 --busid 1-1
 */
fun main() {
    val device = byteArrayOf(0x12, 0x01, 0x00, 0x02, 0, 0, 0, 0x40, 0x34, 0x12, 0x78, 0x56, 0x00, 0x01, 1, 2, 0, 1)
    val config = byteArrayOf(
        0x09, 0x02, 0x19, 0x00, 0x01, 0x01, 0x00, 0x80.toByte(), 0x32,
        0x09, 0x04, 0x00, 0x00, 0x01, 0xff.toByte(), 0x00, 0x00, 0x00,
        0x07, 0x05, 0x81.toByte(), 0x02, 0x00, 0x02, 0x00,
    )
    val info = UsbIpDevice("/dev/bus/usb/001/002", "1-1", 1, 2, UsbSpeed.HIGH, 0x1234, 0x5678, 0x0100, 0, 0, 0, 1, 1, listOf(UsbIpInterface(0xff, 0, 0)))
    var busy = false
    val lock = Any()
    val backend = object : UsbIpBackend {
        override fun listDevices() = listOf(info)
        override fun import(busId: String): ImportResult = synchronized(lock) {
            if (busId != info.busId) return ImportResult.Error(UsbIp.ST_NODEV, "no such device")
            if (busy) return ImportResult.Error(UsbIp.ST_DEV_BUSY, "busy")
            busy = true
            ImportResult.Ok(object : UsbIpDeviceSession {
                override val info = info
                override fun setOnDisconnected(callback: () -> Unit) {}
                override fun close() = synchronized(lock) { busy = false }
                override fun submit(request: SubmitRequest, onComplete: (UrbResult) -> Unit): UrbHandle {
                    val type = request.setup[3].toInt()
                    val src = when {
                        request.endpoint != 0 -> null
                        type == 1 -> device
                        type == 2 -> config
                        else -> null
                    }
                    onComplete(if (src == null) UrbResult.error(Errno.EPIPE) else UrbResult(0, minOf(src.size, request.transferBufferLength), src))
                    return UrbHandle {}
                }
            })
        }
    }
    val port = System.getenv("USBIP_PORT")?.toIntOrNull() ?: UsbIp.DEFAULT_PORT
    UsbIpServer(backend, port, listener = object : UsbIpServer.Listener {
        override fun log(message: String, error: Throwable?) = println("[usbip] $message" + (error?.let { " ($it)" } ?: ""))
    }).start()
    Thread.currentThread().join()
}
