package dev.zk.usbip.protocol

import java.io.Closeable

data class UsbIpInterface(val interfaceClass: Int, val interfaceSubClass: Int, val interfaceProtocol: Int)

/** An exportable device, as described in OP_REP_DEVLIST / OP_REP_IMPORT. */
data class UsbIpDevice(
    val path: String,
    val busId: String,
    val busNum: Int,
    val devNum: Int,
    val speed: UsbSpeed,
    val vendorId: Int,
    val productId: Int,
    val bcdDevice: Int,
    val deviceClass: Int,
    val deviceSubClass: Int,
    val deviceProtocol: Int,
    val configurationValue: Int,
    val numConfigurations: Int,
    val interfaces: List<UsbIpInterface>,
)

/** A CMD_SUBMIT request together with its OUT payload. */
class SubmitRequest(
    val seqnum: Int,
    val direction: Int,
    val endpoint: Int,
    val transferFlags: Int,
    val transferBufferLength: Int,
    val startFrame: Int,
    val numberOfPackets: Int,
    val interval: Int,
    /** 8-byte setup packet (only meaningful for control transfers on endpoint 0). */
    val setup: ByteArray,
    /** OUT payload of [transferBufferLength] bytes, or null for IN transfers. */
    val outData: ByteArray?,
) {
    val isIn get() = direction == UsbIp.DIR_IN
    val isIso get() = numberOfPackets != 0 && numberOfPackets != UsbIp.NON_ISO_PACKETS
}

/**
 * Completion of a URB. [status] is 0 or a negative Linux errno. For IN transfers [data] holds
 * at least [actualLength] bytes (it may be larger; only the first [actualLength] are sent).
 */
class UrbResult(val status: Int, val actualLength: Int, val data: ByteArray? = null) {
    companion object {
        fun error(errno: Int) = UrbResult(-errno, 0)
    }
}

fun interface UrbHandle {
    /** Best-effort cancellation; the completion callback may still fire (and will be ignored). */
    fun cancel()
}

/** One imported device, owned by a single client connection. */
interface UsbIpDeviceSession : Closeable {
    val info: UsbIpDevice

    /** Starts a transfer; [onComplete] is called exactly once, from any thread. */
    fun submit(request: SubmitRequest, onComplete: (UrbResult) -> Unit): UrbHandle

    /** Registers a callback fired if the device goes away (e.g. unplugged) while imported. */
    fun setOnDisconnected(callback: () -> Unit)
}

sealed class ImportResult {
    class Ok(val session: UsbIpDeviceSession) : ImportResult()
    class Error(val status: Int, val message: String) : ImportResult()
}

/** What the server exports. Implemented on Android by AndroidUsbBackend, and by fakes in tests. */
interface UsbIpBackend {
    fun listDevices(): List<UsbIpDevice>
    fun import(busId: String): ImportResult
}

/** Extra, human-oriented details served by the OP_REQ_INFO extension. */
data class DeviceDetail(val busId: String, val name: String, val attachedBy: String? = null)

data class ServerInfo(val name: String, val devices: List<DeviceDetail>) {
    fun toJson(): String = buildString {
        append("{\"name\":").append(jsonString(name)).append(",\"devices\":[")
        devices.forEachIndexed { i, d ->
            if (i > 0) append(',')
            append("{\"busid\":").append(jsonString(d.busId))
            append(",\"name\":").append(jsonString(d.name))
            append(",\"attachedBy\":").append(d.attachedBy?.let(::jsonString) ?: "null")
            append('}')
        }
        append("]}")
    }

    private fun jsonString(s: String) = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }
}
