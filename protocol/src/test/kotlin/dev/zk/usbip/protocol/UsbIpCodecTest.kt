package dev.zk.usbip.protocol

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class UsbIpCodecTest {
    private val device = UsbIpDevice(
        path = "/dev/bus/usb/001/002", busId = "1-1", busNum = 1, devNum = 2, speed = UsbSpeed.HIGH,
        vendorId = 0x2341, productId = 0x0043, bcdDevice = 0x0001,
        deviceClass = 0x02, deviceSubClass = 0, deviceProtocol = 0,
        configurationValue = 1, numConfigurations = 1,
        interfaces = listOf(UsbIpInterface(0x02, 0x02, 0x01), UsbIpInterface(0x0a, 0, 0)),
    )

    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun reqDevListIsHeaderOnly() {
        assertContentEquals(hex("0111 8005 00000000"), UsbIpCodec.encodeReqDevList())
    }

    @Test
    fun devListLayout() {
        val b = UsbIpCodec.encodeDevList(listOf(device))
        assertEquals(8 + 4 + 312 + 2 * 4, b.size)
        assertContentEquals(hex("0111 0005 00000000 00000001"), b.copyOfRange(0, 12))
        val bb = ByteBuffer.wrap(b)
        // busid follows the 256-byte path
        assertEquals("1-1", UsbIpCodec.decodeFixedString(b.copyOfRange(12 + 256, 12 + 256 + 32)))
        bb.position(12 + 256 + 32)
        assertEquals(1, bb.int) // busnum
        assertEquals(2, bb.int) // devnum
        assertEquals(3, bb.int) // speed HIGH
        assertContentEquals(hex("2341 0043 0001 02 00 00 01 01 02"), b.copyOfRange(bb.position(), bb.position() + 12))
        // interface records
        assertContentEquals(hex("02020100 0a000000"), b.copyOfRange(b.size - 8, b.size))
        assertEquals(listOf(device), UsbIpCodec.decodeDevList(b))
    }

    @Test
    fun importReply() {
        val ok = UsbIpCodec.encodeImportReply(UsbIp.ST_OK, device)
        assertEquals(8 + 312, ok.size)
        assertContentEquals(hex("0111 0003 00000000"), ok.copyOfRange(0, 8))
        val err = UsbIpCodec.encodeImportReply(UsbIp.ST_DEV_BUSY, null)
        assertContentEquals(hex("0111 0003 00000002"), err)
    }

    @Test
    fun reqImportRoundTrip() {
        val b = UsbIpCodec.encodeReqImport("1-1")
        assertEquals(40, b.size)
        assertEquals(UsbIp.OP_REQ_IMPORT, UsbIpCodec.decodeOpHeader(b).code)
        assertEquals("1-1", UsbIpCodec.decodeFixedString(b.copyOfRange(8, 40)))
    }

    @Test
    fun submitDecode() {
        // CMD_SUBMIT seq=5 devid=0x10002 dir=IN ep=0 flags=0x200 len=18, GET_DESCRIPTOR(device)
        val b = hex(
            "00000001 00000005 00010002 00000001 00000000" +
                "00000200 00000012 00000000 ffffffff 00000000" +
                "8006000100001200",
        )
        val cmd = assertIs<UsbIpCodec.UrbCommand.Submit>(UsbIpCodec.decodeUrbCommand(b))
        assertEquals(5, cmd.seqnum)
        assertEquals(UsbIp.DIR_IN, cmd.direction)
        assertEquals(0, cmd.endpoint)
        assertEquals(18, cmd.transferBufferLength)
        assertEquals(false, cmd.isIso)
        assertContentEquals(hex("8006000100001200"), cmd.setup)
    }

    @Test
    fun retSubmitLayout() {
        val data = ByteArray(32) { it.toByte() }
        val b = UsbIpCodec.encodeRetSubmit(7, 0, 18, UsbIp.NON_ISO_PACKETS, isIn = true, data = data)
        assertEquals(48 + 18, b.size)
        assertContentEquals(
            hex("00000003 00000007 00000000 00000000 00000000 00000000 00000012 00000000 ffffffff 00000000 0000000000000000"),
            b.copyOfRange(0, 48),
        )
        assertContentEquals(data.copyOf(18), b.copyOfRange(48, 66))
        // OUT completions carry no payload
        assertEquals(48, UsbIpCodec.encodeRetSubmit(8, 0, 64, 0, isIn = false, data = null).size)
    }

    @Test
    fun unlinkRoundTrip() {
        val cmd = assertIs<UsbIpCodec.UrbCommand.Unlink>(UsbIpCodec.decodeUrbCommand(UsbIpCodec.encodeCmdUnlink(9, 0, 4)))
        assertEquals(4, cmd.unlinkSeqnum)
        val ret = UsbIpCodec.decodeRet(UsbIpCodec.encodeRetUnlink(9, -Errno.ECONNRESET))
        assertEquals(UsbIp.RET_UNLINK, ret.command)
        assertEquals(-104, ret.status)
    }
}
