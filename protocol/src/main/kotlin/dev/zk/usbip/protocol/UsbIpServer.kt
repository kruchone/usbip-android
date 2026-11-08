package dev.zk.usbip.protocol

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * USB/IP server (the "usbipd" side). One thread accepts connections; each client gets a reader
 * thread and, once it has imported a device, a writer thread that serializes replies.
 */
class UsbIpServer(
    private val backend: UsbIpBackend,
    private val port: Int = UsbIp.DEFAULT_PORT,
    private val bindAddress: InetAddress? = null,
    private val listener: Listener = object : Listener {},
    /** Answers the OP_REQ_INFO extension; by default just bus ids without friendly names. */
    private val info: () -> ServerInfo = {
        ServerInfo("usbip", backend.listDevices().map { DeviceDetail(it.busId, "%04x:%04x".format(it.vendorId, it.productId)) })
    },
) : Closeable {

    interface Listener {
        /** Return false to reject a connection (e.g. client IP allow-list). */
        fun accept(remote: InetSocketAddress): Boolean = true
        fun onAttached(busId: String, remote: InetSocketAddress) {}
        fun onDetached(busId: String, remote: InetSocketAddress) {}
        fun log(message: String, error: Throwable? = null) {}
    }

    private val clients: MutableSet<Socket> = ConcurrentHashMap.newKeySet()
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var closed = false

    val localPort: Int get() = serverSocket?.localPort ?: -1

    @Synchronized
    fun start() {
        check(serverSocket == null && !closed) { "already started" }
        val ss = ServerSocket()
        try {
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(bindAddress, port))
        } catch (e: IOException) {
            ss.closeQuietly()
            throw e
        }
        serverSocket = ss
        thread(name = "usbip-accept", isDaemon = true) { acceptLoop(ss) }
        listener.log("listening on port ${ss.localPort}")
    }

    override fun close() {
        closed = true
        serverSocket?.closeQuietly()
        clients.forEach { it.closeQuietly() }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!closed) {
            val s = try {
                ss.accept()
            } catch (e: IOException) {
                if (!closed) listener.log("accept failed", e)
                break
            }
            val remote = s.remoteSocketAddress as InetSocketAddress
            if (!listener.accept(remote)) {
                listener.log("rejected connection from ${remote.address.hostAddress}")
                s.closeQuietly()
                continue
            }
            clients += s
            thread(name = "usbip-client-${remote.address.hostAddress}", isDaemon = true) {
                try {
                    handleClient(s, remote)
                } catch (e: EOFException) {
                    // client hung up
                } catch (e: SocketException) {
                    // closed by us or the peer
                } catch (e: Exception) {
                    listener.log("client ${remote.address.hostAddress}: ${e.message}", e)
                } finally {
                    clients -= s
                    s.closeQuietly()
                }
            }
        }
    }

    private fun handleClient(s: Socket, remote: InetSocketAddress) {
        s.tcpNoDelay = true
        s.keepAlive = true
        s.soTimeout = HANDSHAKE_TIMEOUT_MS
        val input = DataInputStream(BufferedInputStream(s.getInputStream(), 64 * 1024))
        val output = s.getOutputStream()
        val who = remote.address.hostAddress

        val hdrBytes = ByteArray(UsbIpCodec.OP_HEADER_SIZE).also { input.readFully(it) }
        val hdr = UsbIpCodec.decodeOpHeader(hdrBytes)
        if (hdr.version != UsbIp.VERSION) {
            listener.log("$who: protocol version 0x${hdr.version.toString(16)} (expected 0x0111), continuing")
        }
        when (hdr.code) {
            UsbIp.OP_REQ_DEVLIST -> {
                val devices = backend.listDevices()
                output.write(UsbIpCodec.encodeDevList(devices))
                output.flush()
                listener.log("$who: listed ${devices.size} device(s)")
            }
            UsbIp.OP_REQ_INFO -> {
                output.write(UsbIpCodec.encodeInfoReply(info().toJson()))
                output.flush()
            }
            UsbIp.OP_REQ_IMPORT -> {
                val busId = UsbIpCodec.decodeFixedString(ByteArray(UsbIpCodec.BUSID_SIZE).also { input.readFully(it) })
                when (val r = backend.import(busId)) {
                    is ImportResult.Error -> {
                        output.write(UsbIpCodec.encodeImportReply(r.status, null))
                        output.flush()
                        listener.log("$who: import $busId refused: ${r.message}")
                    }
                    is ImportResult.Ok -> {
                        val session = r.session
                        try {
                            output.write(UsbIpCodec.encodeImportReply(UsbIp.ST_OK, session.info))
                            output.flush()
                            s.soTimeout = 0
                            listener.log("$who: attached $busId")
                            listener.onAttached(busId, remote)
                            UrbConnection(s, input, output, session).run()
                        } finally {
                            session.close()
                            listener.onDetached(busId, remote)
                            listener.log("$who: detached $busId")
                        }
                    }
                }
            }
            else -> listener.log("$who: unknown op code 0x${hdr.code.toString(16)}")
        }
    }

    /** URB phase for one imported device. */
    private inner class UrbConnection(
        private val socket: Socket,
        private val input: DataInputStream,
        rawOutput: OutputStream,
        private val session: UsbIpDeviceSession,
    ) {
        private val output = BufferedOutputStream(rawOutput, 64 * 1024)
        private val outQueue = LinkedBlockingQueue<ByteArray>()
        private val pending = ConcurrentHashMap<Int, Pending>()

        private inner class Pending {
            val state = AtomicInteger(PENDING)
            var handle: UrbHandle? = null
        }

        fun run() {
            session.setOnDisconnected { socket.closeQuietly() }
            val writer = thread(name = "usbip-writer", isDaemon = true) { writeLoop() }
            try {
                readLoop()
            } finally {
                socket.closeQuietly()
                outQueue.put(POISON)
                pending.values.forEach { p ->
                    if (p.state.compareAndSet(PENDING, UNLINKED)) p.handle?.cancel()
                }
                pending.clear()
                writer.join(1000)
            }
        }

        private fun readLoop() {
            val hdr = ByteArray(UsbIpCodec.URB_HEADER_SIZE)
            while (true) {
                input.readFully(hdr)
                when (val cmd = UsbIpCodec.decodeUrbCommand(hdr)) {
                    is UsbIpCodec.UrbCommand.Submit -> onSubmit(cmd)
                    is UsbIpCodec.UrbCommand.Unlink -> onUnlink(cmd)
                }
            }
        }

        private fun onSubmit(cmd: UsbIpCodec.UrbCommand.Submit) {
            val outData = if (cmd.direction == UsbIp.DIR_OUT && cmd.transferBufferLength > 0) {
                if (cmd.transferBufferLength > MAX_TRANSFER) {
                    throw UsbIpProtocolException("transfer of ${cmd.transferBufferLength} bytes is too large")
                }
                ByteArray(cmd.transferBufferLength).also { input.readFully(it) }
            } else null

            if (cmd.isIso) {
                // Android's public USB API has no isochronous support. Consume the descriptors and fail the URB.
                if (cmd.numberOfPackets < 0 || cmd.numberOfPackets > 1024) {
                    throw UsbIpProtocolException("bad number_of_packets ${cmd.numberOfPackets}")
                }
                input.skipNBytesCompat(cmd.numberOfPackets.toLong() * UsbIpCodec.ISO_DESCRIPTOR_SIZE)
                outQueue.put(UsbIpCodec.encodeRetSubmit(cmd.seqnum, -Errno.EXDEV, 0, cmd.numberOfPackets, cmd.direction == UsbIp.DIR_IN, null))
                return
            }

            val p = Pending()
            pending[cmd.seqnum] = p
            val isIn = cmd.direction == UsbIp.DIR_IN
            p.handle = session.submit(cmd.toRequest(outData)) { result ->
                if (p.state.compareAndSet(PENDING, DONE)) {
                    pending.remove(cmd.seqnum, p)
                    outQueue.put(
                        UsbIpCodec.encodeRetSubmit(
                            cmd.seqnum, result.status, result.actualLength, cmd.numberOfPackets, isIn, result.data,
                        ),
                    )
                }
            }
        }

        private fun onUnlink(cmd: UsbIpCodec.UrbCommand.Unlink) {
            val p = pending[cmd.unlinkSeqnum]
            val status = if (p != null && p.state.compareAndSet(PENDING, UNLINKED)) {
                pending.remove(cmd.unlinkSeqnum, p)
                p.handle?.cancel()
                // The URB was unlinked before completion: its RET_SUBMIT is suppressed.
                -Errno.ECONNRESET
            } else {
                0 // already completed (its RET_SUBMIT was or will be sent)
            }
            outQueue.put(UsbIpCodec.encodeRetUnlink(cmd.seqnum, status))
        }

        private fun writeLoop() {
            try {
                while (true) {
                    val msg = outQueue.take()
                    if (msg === POISON) break
                    output.write(msg)
                    if (outQueue.isEmpty()) output.flush()
                }
            } catch (e: IOException) {
                socket.closeQuietly()
            } catch (e: InterruptedException) {
                // shutting down
            }
        }
    }

    private companion object {
        const val HANDSHAKE_TIMEOUT_MS = 15_000
        const val MAX_TRANSFER = 16 * 1024 * 1024
        const val PENDING = 0
        const val DONE = 1
        const val UNLINKED = 2
        val POISON = ByteArray(0)
    }
}

internal fun Closeable.closeQuietly() {
    try {
        close()
    } catch (_: Exception) {
    }
}

private fun DataInputStream.skipNBytesCompat(n: Long) {
    var left = n
    val buf = ByteArray(4096)
    while (left > 0) {
        val r = read(buf, 0, minOf(buf.size.toLong(), left).toInt())
        if (r < 0) throw EOFException()
        left -= r
    }
}
