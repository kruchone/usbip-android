package dev.zk.usbip.protocol

import java.io.IOException
import java.nio.ByteBuffer

class UsbIpProtocolException(message: String) : IOException(message)

/** Big-endian encoding/decoding of USB/IP messages. */
object UsbIpCodec {
    const val OP_HEADER_SIZE = 8
    const val PATH_SIZE = 256
    const val BUSID_SIZE = 32
    const val DEVICE_SIZE = 312
    const val INTERFACE_SIZE = 4
    const val URB_HEADER_SIZE = 48
    const val ISO_DESCRIPTOR_SIZE = 16

    // ---------------------------------------------------------------- handshake

    data class OpHeader(val version: Int, val code: Int, val status: Int)

    fun decodeOpHeader(bytes: ByteArray): OpHeader {
        val bb = ByteBuffer.wrap(bytes, 0, OP_HEADER_SIZE)
        return OpHeader(bb.short.toInt() and 0xffff, bb.short.toInt() and 0xffff, bb.int)
    }

    private fun putOpHeader(bb: ByteBuffer, code: Int, status: Int) {
        bb.putShort(UsbIp.VERSION.toShort())
        bb.putShort(code.toShort())
        bb.putInt(status)
    }

    fun encodeReqDevList(): ByteArray =
        ByteBuffer.allocate(OP_HEADER_SIZE).also { putOpHeader(it, UsbIp.OP_REQ_DEVLIST, 0) }.array()

    fun encodeReqImport(busId: String): ByteArray =
        ByteBuffer.allocate(OP_HEADER_SIZE + BUSID_SIZE).also {
            putOpHeader(it, UsbIp.OP_REQ_IMPORT, 0)
            putFixedString(it, busId, BUSID_SIZE)
        }.array()

    fun encodeReqInfo(): ByteArray =
        ByteBuffer.allocate(OP_HEADER_SIZE).also { putOpHeader(it, UsbIp.OP_REQ_INFO, 0) }.array()

    /** OP_REP_INFO: header, u32 length, UTF-8 JSON. */
    fun encodeInfoReply(json: String): ByteArray {
        val body = json.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(OP_HEADER_SIZE + 4 + body.size).also {
            putOpHeader(it, UsbIp.OP_REP_INFO, UsbIp.ST_OK)
            it.putInt(body.size)
            it.put(body)
        }.array()
    }

    fun encodeDevList(devices: List<UsbIpDevice>): ByteArray {
        val size = OP_HEADER_SIZE + 4 + devices.sumOf { DEVICE_SIZE + INTERFACE_SIZE * it.interfaces.size }
        val bb = ByteBuffer.allocate(size)
        putOpHeader(bb, UsbIp.OP_REP_DEVLIST, UsbIp.ST_OK)
        bb.putInt(devices.size)
        for (d in devices) {
            putDevice(bb, d)
            for (i in d.interfaces) {
                bb.put(i.interfaceClass.toByte())
                bb.put(i.interfaceSubClass.toByte())
                bb.put(i.interfaceProtocol.toByte())
                bb.put(0) // padding
            }
        }
        return bb.array()
    }

    /** OP_REP_IMPORT. On error only the 8-byte header is sent, as Linux usbipd does. */
    fun encodeImportReply(status: Int, device: UsbIpDevice?): ByteArray {
        if (status != UsbIp.ST_OK || device == null) {
            return ByteBuffer.allocate(OP_HEADER_SIZE)
                .also { putOpHeader(it, UsbIp.OP_REP_IMPORT, if (status == UsbIp.ST_OK) UsbIp.ST_ERROR else status) }
                .array()
        }
        val bb = ByteBuffer.allocate(OP_HEADER_SIZE + DEVICE_SIZE)
        putOpHeader(bb, UsbIp.OP_REP_IMPORT, UsbIp.ST_OK)
        putDevice(bb, device)
        return bb.array()
    }

    private fun putDevice(bb: ByteBuffer, d: UsbIpDevice) {
        putFixedString(bb, d.path, PATH_SIZE)
        putFixedString(bb, d.busId, BUSID_SIZE)
        bb.putInt(d.busNum)
        bb.putInt(d.devNum)
        bb.putInt(d.speed.code)
        bb.putShort(d.vendorId.toShort())
        bb.putShort(d.productId.toShort())
        bb.putShort(d.bcdDevice.toShort())
        bb.put(d.deviceClass.toByte())
        bb.put(d.deviceSubClass.toByte())
        bb.put(d.deviceProtocol.toByte())
        bb.put(d.configurationValue.toByte())
        bb.put(d.numConfigurations.toByte())
        bb.put(d.interfaces.size.toByte())
    }

    /** Decodes one usb_device record (312 bytes), optionally followed by its interface records. */
    fun decodeDevice(bb: ByteBuffer, withInterfaces: Boolean): UsbIpDevice {
        val path = getFixedString(bb, PATH_SIZE)
        val busId = getFixedString(bb, BUSID_SIZE)
        val busNum = bb.int
        val devNum = bb.int
        val speedCode = bb.int
        val vid = bb.short.toInt() and 0xffff
        val pid = bb.short.toInt() and 0xffff
        val bcd = bb.short.toInt() and 0xffff
        val cls = bb.get().toInt() and 0xff
        val sub = bb.get().toInt() and 0xff
        val proto = bb.get().toInt() and 0xff
        val cfg = bb.get().toInt() and 0xff
        val numCfg = bb.get().toInt() and 0xff
        val numIf = bb.get().toInt() and 0xff
        val ifaces = if (withInterfaces) List(numIf) {
            val i = UsbIpInterface(bb.get().toInt() and 0xff, bb.get().toInt() and 0xff, bb.get().toInt() and 0xff)
            bb.get()
            i
        } else List(numIf) { UsbIpInterface(0, 0, 0) }
        return UsbIpDevice(
            path, busId, busNum, devNum, UsbSpeed.entries.firstOrNull { it.code == speedCode } ?: UsbSpeed.UNKNOWN,
            vid, pid, bcd, cls, sub, proto, cfg, numCfg, ifaces,
        )
    }

    fun decodeDevList(bytes: ByteArray): List<UsbIpDevice> {
        val bb = ByteBuffer.wrap(bytes)
        val hdr = decodeOpHeader(bytes)
        if (hdr.code != UsbIp.OP_REP_DEVLIST) throw UsbIpProtocolException("unexpected op ${hdr.code}")
        bb.position(OP_HEADER_SIZE)
        val n = bb.int
        return List(n) { decodeDevice(bb, withInterfaces = true) }
    }

    // ---------------------------------------------------------------- URB phase

    sealed class UrbCommand {
        abstract val seqnum: Int

        class Submit(
            override val seqnum: Int,
            val devId: Int,
            val direction: Int,
            val endpoint: Int,
            val transferFlags: Int,
            val transferBufferLength: Int,
            val startFrame: Int,
            val numberOfPackets: Int,
            val interval: Int,
            val setup: ByteArray,
        ) : UrbCommand() {
            val isIso get() = numberOfPackets != 0 && numberOfPackets != UsbIp.NON_ISO_PACKETS

            fun toRequest(outData: ByteArray?) = SubmitRequest(
                seqnum, direction, endpoint, transferFlags, transferBufferLength,
                startFrame, numberOfPackets, interval, setup, outData,
            )
        }

        class Unlink(
            override val seqnum: Int,
            val devId: Int,
            val direction: Int,
            val endpoint: Int,
            val unlinkSeqnum: Int,
        ) : UrbCommand()
    }

    fun decodeUrbCommand(bytes: ByteArray): UrbCommand {
        val bb = ByteBuffer.wrap(bytes, 0, URB_HEADER_SIZE)
        val command = bb.int
        val seqnum = bb.int
        val devId = bb.int
        val direction = bb.int
        val ep = bb.int
        return when (command) {
            UsbIp.CMD_SUBMIT -> {
                val flags = bb.int
                val len = bb.int
                val startFrame = bb.int
                val packets = bb.int
                val interval = bb.int
                val setup = ByteArray(8).also { bb.get(it) }
                if (len < 0) throw UsbIpProtocolException("negative transfer_buffer_length $len")
                UrbCommand.Submit(seqnum, devId, direction, ep, flags, len, startFrame, packets, interval, setup)
            }
            UsbIp.CMD_UNLINK -> UrbCommand.Unlink(seqnum, devId, direction, ep, bb.int)
            else -> throw UsbIpProtocolException("unknown URB command 0x${command.toString(16)}")
        }
    }

    fun encodeCmdSubmit(
        seqnum: Int, devId: Int, direction: Int, endpoint: Int,
        transferBufferLength: Int, setup: ByteArray = ByteArray(8), outData: ByteArray? = null,
        transferFlags: Int = 0, numberOfPackets: Int = UsbIp.NON_ISO_PACKETS, interval: Int = 0,
    ): ByteArray {
        val payload = if (direction == UsbIp.DIR_OUT) outData ?: ByteArray(0) else ByteArray(0)
        val bb = ByteBuffer.allocate(URB_HEADER_SIZE + payload.size)
        bb.putInt(UsbIp.CMD_SUBMIT); bb.putInt(seqnum); bb.putInt(devId); bb.putInt(direction); bb.putInt(endpoint)
        bb.putInt(transferFlags); bb.putInt(transferBufferLength); bb.putInt(0); bb.putInt(numberOfPackets)
        bb.putInt(interval); bb.put(setup, 0, 8)
        bb.put(payload)
        return bb.array()
    }

    fun encodeCmdUnlink(seqnum: Int, devId: Int, unlinkSeqnum: Int): ByteArray {
        val bb = ByteBuffer.allocate(URB_HEADER_SIZE)
        bb.putInt(UsbIp.CMD_UNLINK); bb.putInt(seqnum); bb.putInt(devId); bb.putInt(0); bb.putInt(0)
        bb.putInt(unlinkSeqnum)
        return bb.array()
    }

    /**
     * RET_SUBMIT. Per the Linux stub, devid/direction/ep in the basic header are 0.
     * IN data ([data], first [actualLength] bytes) follows the header only when [isIn].
     */
    fun encodeRetSubmit(
        seqnum: Int, status: Int, actualLength: Int, numberOfPackets: Int,
        isIn: Boolean, data: ByteArray?,
    ): ByteArray {
        val payloadLen = if (isIn && data != null) actualLength else 0
        val bb = ByteBuffer.allocate(URB_HEADER_SIZE + payloadLen)
        bb.putInt(UsbIp.RET_SUBMIT); bb.putInt(seqnum); bb.putInt(0); bb.putInt(0); bb.putInt(0)
        bb.putInt(status); bb.putInt(actualLength); bb.putInt(0) /* start_frame */
        bb.putInt(numberOfPackets); bb.putInt(0) /* error_count */
        bb.position(URB_HEADER_SIZE)
        if (payloadLen > 0) bb.put(data!!, 0, payloadLen)
        return bb.array()
    }

    fun encodeRetUnlink(seqnum: Int, status: Int): ByteArray {
        val bb = ByteBuffer.allocate(URB_HEADER_SIZE)
        bb.putInt(UsbIp.RET_UNLINK); bb.putInt(seqnum); bb.putInt(0); bb.putInt(0); bb.putInt(0)
        bb.putInt(status)
        return bb.array()
    }

    /** Decoded RET_SUBMIT / RET_UNLINK header (client side; used by tests and tools). */
    class Ret(val command: Int, val seqnum: Int, val status: Int, val actualLength: Int, val numberOfPackets: Int)

    fun decodeRet(bytes: ByteArray): Ret {
        val bb = ByteBuffer.wrap(bytes, 0, URB_HEADER_SIZE)
        val cmd = bb.int
        val seq = bb.int
        bb.position(20)
        val status = bb.int
        val actual = bb.int
        bb.int
        val packets = bb.int
        return Ret(cmd, seq, status, if (cmd == UsbIp.RET_SUBMIT) actual else 0, packets)
    }

    // ---------------------------------------------------------------- helpers

    fun putFixedString(bb: ByteBuffer, s: String, size: Int) {
        val raw = s.toByteArray(Charsets.US_ASCII)
        val n = minOf(raw.size, size - 1)
        bb.put(raw, 0, n)
        repeat(size - n) { bb.put(0) }
    }

    fun getFixedString(bb: ByteBuffer, size: Int): String {
        val raw = ByteArray(size).also { bb.get(it) }
        return decodeFixedString(raw)
    }

    fun decodeFixedString(raw: ByteArray): String {
        val end = raw.indexOf(0).let { if (it < 0) raw.size else it }
        return String(raw, 0, end, Charsets.US_ASCII)
    }
}
