package dev.zk.usbip.usb

import dev.zk.usbip.AppLog
import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbRequest
import android.os.Build
import android.util.Log
import dev.zk.usbip.protocol.Errno
import dev.zk.usbip.protocol.SubmitRequest
import dev.zk.usbip.protocol.UrbHandle
import dev.zk.usbip.protocol.UrbResult
import dev.zk.usbip.protocol.UsbIp
import dev.zk.usbip.protocol.UsbIpDevice
import dev.zk.usbip.protocol.UsbIpDeviceSession
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeoutException

/**
 * Executes URBs from one USB/IP client against an Android UsbDeviceConnection.
 *
 * - Control transfers (endpoint 0) run synchronously on a dedicated thread, with SET_CONFIGURATION,
 *   SET_INTERFACE and CLEAR_FEATURE(ENDPOINT_HALT) mapped to the proper Android calls so the kernel's
 *   view of the device stays consistent.
 * - Bulk and interrupt transfers use asynchronous UsbRequests, reaped by one thread, so many URBs can be
 *   in flight at once (HID polling, storage read-ahead) and can be cancelled on CMD_UNLINK.
 *
 * Limitation of Android's Java API: UsbRequest doesn't report the URB status, so a stalled IN endpoint
 * looks like a zero-length completion. Isochronous endpoints are not supported.
 */
class AndroidDeviceSession(
    private val device: UsbDevice,
    private val conn: UsbDeviceConnection,
    override val info: UsbIpDevice,
    private val onClosed: () -> Unit,
) : UsbIpDeviceSession {

    private data class EpRef(val interfaceId: Int, val endpoint: UsbEndpoint)

    private val tag = "UsbIp/${info.busId}"

    /** Per-URB tracing: adb shell setprop log.tag.UsbIpUrb VERBOSE (then restart the server). */
    private val trace = Log.isLoggable(TRACE_TAG, Log.VERBOSE)
    private val lock = Any()
    @Volatile private var closed = false
    @Volatile private var disconnectCallback: (() -> Unit)? = null

    private var config: UsbConfiguration
    private val claimed = mutableListOf<UsbInterface>()
    private val endpoints = ConcurrentHashMap<Int, EpRef>() // endpoint address -> active endpoint
    private val altSettings = HashMap<Int, Int>() // interface number -> selected alternate setting
    private val inFlight: MutableSet<Transfer> = ConcurrentHashMap.newKeySet()

    private val control: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "usbip-ctrl-${info.busId}").apply { isDaemon = true }
    }
    private val reaper = Thread(::reapLoop, "usbip-reap-${info.busId}").apply { isDaemon = true }

    init {
        config = activeConfiguration()
        claimConfiguration(config)
        DeviceQuirks.afterClaim(device, conn, config, tag)
        reaper.start()
    }

    // ------------------------------------------------------------------ session API

    override fun setOnDisconnected(callback: () -> Unit) {
        disconnectCallback = callback
        if (closed) callback()
    }

    override fun submit(request: SubmitRequest, onComplete0: (UrbResult) -> Unit): UrbHandle {
        val onComplete: (UrbResult) -> Unit = if (!trace) onComplete0 else { r ->
            val data = r.data?.takeIf { request.isIn }?.copyOf(minOf(r.actualLength, 16))?.joinToString(" ") { "%02x".format(it) } ?: ""
            Log.v(TRACE_TAG, "done  #${request.seqnum} ep${request.endpoint}${if (request.isIn) "in" else "out"} status=${r.status} actual=${r.actualLength} $data")
            onComplete0(r)
        }
        if (trace) {
            val setup = if (request.endpoint == 0) " setup=" + request.setup.joinToString("") { "%02x".format(it) } else ""
            Log.v(TRACE_TAG, "submit #${request.seqnum} ep${request.endpoint}${if (request.isIn) "in" else "out"} len=${request.transferBufferLength} flags=0x${request.transferFlags.toString(16)}$setup")
        }
        if (closed) {
            onComplete(UrbResult.error(Errno.ENODEV))
            return NO_CANCEL
        }
        if (request.endpoint == 0) {
            try {
                control.execute { onComplete(runControl(request)) }
            } catch (e: RejectedExecutionException) {
                onComplete(UrbResult.error(Errno.ENODEV))
            }
            return NO_CANCEL // synchronous control transfers can't be aborted; a late result is discarded
        }
        val address = request.endpoint or (if (request.isIn) UsbConstants.USB_DIR_IN else UsbConstants.USB_DIR_OUT)
        val ep = endpoints[address]?.endpoint
        if (ep == null) {
            AppLog.w(tag, "URB for unknown endpoint 0x${address.toString(16)}")
            onComplete(UrbResult.error(Errno.ENOENT))
            return NO_CANCEL
        }
        if (ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC) {
            onComplete(UrbResult.error(Errno.EXDEV))
            return NO_CANCEL
        }
        return Transfer(request, ep, onComplete).also { it.start() }
    }

    /** Called by the backend when Android reports the device was unplugged. */
    fun onDeviceDetached() {
        AppLog.i(tag, "device detached")
        disconnectCallback?.invoke() ?: close()
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        inFlight.toList().forEach { it.cancel() }
        if (Thread.currentThread() !== reaper) reaper.join(1000)
        control.shutdownNow()
        synchronized(lock) {
            claimed.forEach { runCatching { conn.releaseInterface(it) } }
            claimed.clear()
        }
        conn.close()
        AppLog.i(tag, "session closed")
        onClosed()
    }

    // ------------------------------------------------------------------ configuration

    private fun activeConfiguration(): UsbConfiguration {
        val b = ByteArray(1)
        val n = conn.controlTransfer(0x80, REQ_GET_CONFIGURATION, 0, 0, b, 1, 1000)
        val value = if (n == 1) b[0].toInt() and 0xff else -1
        val configs = (0 until device.configurationCount).map { device.getConfiguration(it) }
        return configs.firstOrNull { it.id == value } ?: configs.first()
    }

    private fun claimConfiguration(cfg: UsbConfiguration) = synchronized(lock) {
        endpoints.clear()
        for (i in 0 until cfg.interfaceCount) {
            val itf = cfg.getInterface(i)
            if (itf.alternateSetting != 0) continue
            if (conn.claimInterface(itf, true)) claimed += itf else AppLog.w(tag, "could not claim interface ${itf.id}")
            addEndpoints(itf)
        }
    }

    private fun addEndpoints(itf: UsbInterface) {
        for (e in 0 until itf.endpointCount) {
            val ep = itf.getEndpoint(e)
            endpoints[ep.address] = EpRef(itf.id, ep)
        }
    }

    private fun setConfiguration(value: Int): UrbResult = synchronized(lock) {
        if (value == 0 || value == config.id) return UrbResult(0, 0) // unconfigure, or already active
        val target = (0 until device.configurationCount).map { device.getConfiguration(it) }.firstOrNull { it.id == value }
            ?: return UrbResult.error(Errno.EPIPE)
        claimed.forEach { conn.releaseInterface(it) }
        claimed.clear()
        val ok = conn.setConfiguration(target)
        if (ok) config = target else AppLog.w(tag, "SET_CONFIGURATION $value failed")
        claimConfiguration(config)
        if (ok) UrbResult(0, 0) else UrbResult.error(Errno.EPIPE)
    }

    private fun setInterface(interfaceId: Int, alt: Int): UrbResult = synchronized(lock) {
        val itf = (0 until config.interfaceCount).map { config.getInterface(it) }
            .firstOrNull { it.id == interfaceId && it.alternateSetting == alt }
            ?: return UrbResult.error(Errno.EPIPE)
        if (!conn.setInterface(itf)) return UrbResult.error(Errno.EPIPE)
        endpoints.entries.removeIf { it.value.interfaceId == interfaceId }
        addEndpoints(itf)
        altSettings[interfaceId] = alt
        UrbResult(0, 0)
    }

    /**
     * CLEAR_FEATURE(ENDPOINT_HALT). Android has no clear-halt call, so re-select the interface's current
     * alternate setting: SET_INTERFACE clears halt and resets data toggles on both the device and the
     * host side (the kernel falls back to clearing halts itself if the device rejects SET_INTERFACE).
     */
    private fun clearHalt(endpointAddress: Int, raw: SubmitRequest): UrbResult {
        val ref = endpoints[endpointAddress] ?: return passthrough(raw)
        val alt = synchronized(lock) { altSettings[ref.interfaceId] ?: 0 }
        val r = setInterface(ref.interfaceId, alt)
        return if (r.status == 0) r else passthrough(raw)
    }

    // ------------------------------------------------------------------ control transfers

    private fun runControl(req: SubmitRequest): UrbResult {
        if (closed) return UrbResult.error(Errno.ENODEV)
        val s = req.setup
        val bmRequestType = s[0].toInt() and 0xff
        val bRequest = s[1].toInt() and 0xff
        val wValue = (s[2].toInt() and 0xff) or ((s[3].toInt() and 0xff) shl 8)
        val wIndex = (s[4].toInt() and 0xff) or ((s[5].toInt() and 0xff) shl 8)

        return when {
            bmRequestType == 0x00 && bRequest == REQ_SET_CONFIGURATION -> setConfiguration(wValue and 0xff)
            bmRequestType == 0x01 && bRequest == REQ_SET_INTERFACE -> setInterface(wIndex and 0xff, wValue and 0xff)
            bmRequestType == 0x02 && bRequest == REQ_CLEAR_FEATURE && wValue == 0 -> clearHalt(wIndex and 0xff, req)
            // SetPortFeature(PORT_RESET) addressed to the virtual hub port: nothing to do here.
            bmRequestType == 0x23 && bRequest == REQ_SET_FEATURE && wValue == 4 -> UrbResult(0, 0)
            else -> passthrough(req)
        }
    }

    private fun passthrough(req: SubmitRequest): UrbResult {
        val s = req.setup
        val bmRequestType = s[0].toInt() and 0xff
        val isIn = bmRequestType and 0x80 != 0
        val wValue = (s[2].toInt() and 0xff) or ((s[3].toInt() and 0xff) shl 8)
        val wIndex = (s[4].toInt() and 0xff) or ((s[5].toInt() and 0xff) shl 8)
        val wLength = (s[6].toInt() and 0xff) or ((s[7].toInt() and 0xff) shl 8)
        val buf = if (isIn) ByteArray(minOf(wLength, req.transferBufferLength)) else (req.outData ?: ByteArray(0))
        val n = conn.controlTransfer(bmRequestType, s[1].toInt() and 0xff, wValue, wIndex, buf, buf.size, CONTROL_TIMEOUT_MS)
        if (n < 0) return UrbResult.error(Errno.EPIPE) // stall (or timeout/disconnect; Android doesn't say which)
        return if (isIn) UrbResult(0, n, buf) else UrbResult(0, n)
    }

    // ------------------------------------------------------------------ bulk / interrupt

    private inner class Transfer(
        private val req: SubmitRequest,
        private val ep: UsbEndpoint,
        private val onComplete: (UrbResult) -> Unit,
    ) : UrbHandle {
        private val isIn = req.isIn
        private val total = req.transferBufferLength
        private val outData = req.outData ?: ByteArray(0)
        private val inData = if (isIn) ByteArray(total) else null
        private var zlpPending = !isIn && (req.transferFlags and UsbIp.URB_ZERO_PACKET) != 0 &&
            total > 0 && ep.maxPacketSize > 0 && total % ep.maxPacketSize == 0

        private var done = 0
        private var chunkLen = 0
        private var buffer: ByteBuffer? = null
        private var current: UsbRequest? = null
        private var cancelled = false
        private var finished = false

        fun start() = synchronized(this) {
            inFlight += this
            queue(minOf(MAX_CHUNK, total))
        }

        override fun cancel() = synchronized(this) {
            if (finished) return
            if (trace) Log.v(TRACE_TAG, "cancel #${req.seqnum}")
            cancelled = true
            val r = current
            if (r == null || !r.cancel()) finish(-Errno.ECONNRESET)
        }

        /** Must hold the lock. */
        private fun queue(len: Int) {
            val r = UsbRequest()
            if (!r.initialize(conn, ep)) {
                r.close()
                finish(-Errno.ENODEV)
                return
            }
            r.clientData = this
            val buf = if (len == 0) null else ByteBuffer.allocateDirect(len).also {
                if (!isIn) {
                    it.put(outData, done, len)
                    it.flip()
                }
            }
            chunkLen = len
            buffer = buf
            current = r
            if (!r.queue(buf)) {
                current = null
                r.close()
                finish(-Errno.ENODEV)
            }
        }

        fun onReaped(r: UsbRequest) = synchronized(this) {
            if (r !== current) return
            current = null
            val buf = buffer
            val n = buf?.position() ?: 0 // position advances by the bytes transferred
            r.close()
            if (finished) return
            if (isIn && n > 0) {
                buf!!.flip()
                buf.get(inData!!, done, n)
            }
            done += n
            when {
                cancelled -> finish(-Errno.ECONNRESET)
                !isIn && n < chunkLen -> finish(-Errno.EPIPE) // OUT completes short only on error
                isIn && n < chunkLen -> finish(0) // short packet ends an IN transfer
                done < total -> queue(minOf(MAX_CHUNK, total - done))
                zlpPending -> { zlpPending = false; queue(0) }
                else -> finish(0)
            }
        }

        /** Must hold the lock. */
        private fun finish(status: Int) {
            if (finished) return
            finished = true
            inFlight -= this
            onComplete(UrbResult(status, done, inData))
        }
    }

    private fun reapLoop() {
        var failures = 0
        while (!(closed && inFlight.isEmpty())) {
            val r = try {
                conn.requestWait(REAP_TIMEOUT_MS)
            } catch (e: TimeoutException) {
                if (closed) break // anything still in flight was cancelled; don't wait forever
                continue
            } catch (e: Exception) {
                null
            }
            if (r == null) {
                // requestWait returns null on errors, e.g. when the device disappears.
                if (closed || ++failures >= 5) break
                Thread.sleep(20)
                continue
            }
            failures = 0
            (r.clientData as? Transfer)?.onReaped(r) ?: r.close()
        }
        if (!closed) {
            AppLog.w(tag, "reaper stopped; treating device as disconnected")
            onDeviceDetached()
        }
    }

    private companion object {
        const val TRACE_TAG = "UsbIpUrb"
        val NO_CANCEL = UrbHandle {}
        const val REQ_CLEAR_FEATURE = 0x01
        const val REQ_SET_FEATURE = 0x03
        const val REQ_GET_CONFIGURATION = 0x08
        const val REQ_SET_CONFIGURATION = 0x09
        const val REQ_SET_INTERFACE = 0x0B
        const val CONTROL_TIMEOUT_MS = 5000
        const val REAP_TIMEOUT_MS = 250L

        /** Before Android 9, UsbRequest buffers were limited to 16 KiB. */
        val MAX_CHUNK = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) 1 shl 20 else 16 * 1024
    }
}
