package dev.zk.usbip.protocol

import java.io.DataInputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** End-to-end over loopback TCP against a fake device. */
class UsbIpServerTest {
    private val deviceDescriptor = ByteArray(18) { (0x10 + it).toByte() }

    private val info = UsbIpDevice(
        "/dev/bus/usb/001/002", "1-1", 1, 2, UsbSpeed.FULL, 0x1234, 0x5678, 0x0100,
        0, 0, 0, 1, 1, listOf(UsbIpInterface(0xff, 0, 0)),
    )

    private val detached = CountDownLatch(1)
    private val closed = CountDownLatch(1)
    @Volatile private var pendingInCancelled = false
    @Volatile private var imported = false

    private inner class FakeSession : UsbIpDeviceSession {
        override val info = this@UsbIpServerTest.info
        override fun setOnDisconnected(callback: () -> Unit) {}
        override fun close() { closed.countDown() }

        override fun submit(request: SubmitRequest, onComplete: (UrbResult) -> Unit): UrbHandle {
            return when (request.endpoint) {
                0 -> { // control: GET_DESCRIPTOR returns the fake device descriptor
                    val n = minOf(request.transferBufferLength, deviceDescriptor.size)
                    Thread { onComplete(UrbResult(0, n, deviceDescriptor)) }.start()
                    UrbHandle {}
                }
                1 -> if (request.isIn) { // interrupt IN that never completes on its own
                    UrbHandle { pendingInCancelled = true; onComplete(UrbResult.error(Errno.ECONNRESET)) }
                } else { // bulk OUT: accept everything
                    onComplete(UrbResult(0, request.outData?.size ?: 0)); UrbHandle {}
                }
                2 -> { // bulk IN loopback-ish: return 0..n-1
                    onComplete(UrbResult(0, request.transferBufferLength, ByteArray(request.transferBufferLength) { it.toByte() }))
                    UrbHandle {}
                }
                else -> { onComplete(UrbResult.error(Errno.EPIPE)); UrbHandle {} }
            }
        }
    }

    private val backend = object : UsbIpBackend {
        override fun listDevices() = listOf(info)
        override fun import(busId: String): ImportResult = when {
            busId != "1-1" -> ImportResult.Error(UsbIp.ST_NODEV, "no such device")
            imported -> ImportResult.Error(UsbIp.ST_DEV_BUSY, "busy")
            else -> { imported = true; ImportResult.Ok(FakeSession()) }
        }
    }

    private lateinit var server: UsbIpServer

    @BeforeTest
    fun setUp() {
        server = UsbIpServer(backend, port = 0, bindAddress = InetAddress.getLoopbackAddress(), listener = object : UsbIpServer.Listener {
            override fun onDetached(busId: String, remote: java.net.InetSocketAddress) = detached.countDown()
        })
        server.start()
    }

    @AfterTest
    fun tearDown() = server.close()

    private fun connect() = Socket(InetAddress.getLoopbackAddress(), server.localPort).apply { soTimeout = 5000 }

    @Test
    fun devList() {
        connect().use { s ->
            s.getOutputStream().write(UsbIpCodec.encodeReqDevList())
            val bytes = s.getInputStream().readBytes() // server closes after replying
            assertEquals(listOf(info), UsbIpCodec.decodeDevList(bytes))
        }
    }

    @Test
    fun importUnknownBusId() {
        connect().use { s ->
            s.getOutputStream().write(UsbIpCodec.encodeReqImport("9-9"))
            val reply = s.getInputStream().readBytes()
            assertEquals(8, reply.size)
            assertEquals(UsbIp.ST_NODEV, UsbIpCodec.decodeOpHeader(reply).status)
        }
    }

    @Test
    fun importAndTransfer() {
        connect().use { s ->
            val out = s.getOutputStream()
            val input = DataInputStream(s.getInputStream())
            out.write(UsbIpCodec.encodeReqImport("1-1"))
            val reply = ByteArray(8 + 312).also { input.readFully(it) }
            assertEquals(UsbIp.ST_OK, UsbIpCodec.decodeOpHeader(reply).status)

            // Second import of the same device is refused as busy.
            connect().use { s2 ->
                s2.getOutputStream().write(UsbIpCodec.encodeReqImport("1-1"))
                assertEquals(UsbIp.ST_DEV_BUSY, UsbIpCodec.decodeOpHeader(s2.getInputStream().readBytes()).status)
            }

            fun readRet(): Pair<UsbIpCodec.Ret, ByteArray> {
                val h = ByteArray(48).also { input.readFully(it) }
                val ret = UsbIpCodec.decodeRet(h)
                val data = ByteArray(if (ret.command == UsbIp.RET_SUBMIT) ret.actualLength else 0)
                // Only IN replies carry data; this test knows which seqnums are IN.
                return ret to data
            }

            // 1) control IN GET_DESCRIPTOR
            val setup = byteArrayOf(0x80.toByte(), 0x06, 0x00, 0x01, 0x00, 0x00, 18, 0)
            out.write(UsbIpCodec.encodeCmdSubmit(1, 0x10002, UsbIp.DIR_IN, 0, 18, setup))
            var (ret, _) = readRet()
            assertEquals(UsbIp.RET_SUBMIT, ret.command)
            assertEquals(1, ret.seqnum)
            assertEquals(0, ret.status)
            assertEquals(18, ret.actualLength)
            val desc = ByteArray(18).also { input.readFully(it) }
            assertContentEquals(deviceDescriptor, desc)

            // 2) interrupt IN that stays pending, then 3) unlink it
            out.write(UsbIpCodec.encodeCmdSubmit(2, 0x10002, UsbIp.DIR_IN, 1, 8))
            out.write(UsbIpCodec.encodeCmdUnlink(3, 0x10002, 2))
            ret = readRet().first
            assertEquals(UsbIp.RET_UNLINK, ret.command)
            assertEquals(3, ret.seqnum)
            assertEquals(-Errno.ECONNRESET, ret.status)
            assertTrue(pendingInCancelled)

            // 4) bulk OUT 100 bytes (payload follows header), 5) bulk IN 64 bytes
            out.write(UsbIpCodec.encodeCmdSubmit(4, 0x10002, UsbIp.DIR_OUT, 1, 100, outData = ByteArray(100) { 7 }))
            out.write(UsbIpCodec.encodeCmdSubmit(5, 0x10002, UsbIp.DIR_IN, 2, 64))
            ret = readRet().first
            assertEquals(4, ret.seqnum) // the unlinked URB 2 never produced a RET_SUBMIT
            assertEquals(100, ret.actualLength)
            ret = readRet().first
            assertEquals(5, ret.seqnum)
            val bulk = ByteArray(64).also { input.readFully(it) }
            assertContentEquals(ByteArray(64) { it.toByte() }, bulk)

            // 6) unlink of an already-completed URB returns status 0
            out.write(UsbIpCodec.encodeCmdUnlink(6, 0x10002, 5))
            ret = readRet().first
            assertEquals(UsbIp.RET_UNLINK, ret.command)
            assertEquals(0, ret.status)

            // 7) iso URB is rejected with -EXDEV, its descriptors consumed
            out.write(UsbIpCodec.encodeCmdSubmit(7, 0x10002, UsbIp.DIR_OUT, 3, 0, numberOfPackets = 2))
            out.write(ByteArray(32))
            ret = readRet().first
            assertEquals(7, ret.seqnum)
            assertEquals(-Errno.EXDEV, ret.status)
        }
        assertTrue(closed.await(5, TimeUnit.SECONDS), "session closed after client hung up")
        assertTrue(detached.await(5, TimeUnit.SECONDS))
    }
}
