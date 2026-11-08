package dev.zk.usbip.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log
import dev.zk.usbip.Prefs
import dev.zk.usbip.protocol.ImportResult
import dev.zk.usbip.protocol.UsbDescriptors
import dev.zk.usbip.protocol.UsbIp
import dev.zk.usbip.protocol.UsbIpBackend
import dev.zk.usbip.protocol.UsbIpDevice
import dev.zk.usbip.protocol.UsbIpInterface
import dev.zk.usbip.protocol.UsbSpeed
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Exports the USB devices plugged into this phone that the user chose to share and granted access to. */
class AndroidUsbBackend(context: Context, private val prefs: Prefs) : UsbIpBackend {
    private val usb = context.getSystemService(UsbManager::class.java)

    /** Active sessions keyed by UsbDevice.deviceName. */
    private val sessions = ConcurrentHashMap<String, AndroidDeviceSession>()

    /** Speed/bcdDevice read from descriptors once per plugged-in device, keyed by deviceName. */
    private class DeviceFacts(val speed: UsbSpeed, val bcdDevice: Int)
    private val facts = ConcurrentHashMap<String, DeviceFacts>()

    /** Invoked whenever a session starts or ends. */
    @Volatile var onSessionsChanged: () -> Unit = {}

    fun allDevices(): List<UsbDevice> = usb.deviceList.values.sortedBy { it.deviceName }

    fun hasPermission(d: UsbDevice) = usb.hasPermission(d)

    fun isShared(d: UsbDevice) = prefs.isShared(shareKey(d))

    fun isInUse(d: UsbDevice) = sessions.containsKey(d.deviceName)

    fun exportedDevices(): List<UsbDevice> = allDevices().filter { isShared(it) && usb.hasPermission(it) }

    /** Stable bus id "1-N" derived from the device's identity (VID:PID plus serial number when readable). */
    fun busIdOf(d: UsbDevice): String {
        val serial = serialOf(d)
        val identity = shareKey(d) + (serial?.let { ":$it" } ?: "")
        val candidate = "1-${prefs.slotFor(identity)}"
        // Two identical devices without serial numbers would collide; give the later one a bus-number id.
        val clash = allDevices().any { it.deviceName < d.deviceName && shareKey(it) == shareKey(d) && serialOf(it) == serial }
        return if (clash) "2-${devNum(d)}" else candidate
    }

    private fun serialOf(d: UsbDevice) =
        if (usb.hasPermission(d)) runCatching { d.serialNumber }.getOrNull()?.takeIf { it.isNotBlank() } else null

    override fun listDevices(): List<UsbIpDevice> = exportedDevices().mapNotNull {
        runCatching { describe(it, null) }.onFailure { e -> Log.w(TAG, "describe ${it.deviceName}", e) }.getOrNull()
    }

    override fun import(busId: String): ImportResult {
        val d = exportedDevices().firstOrNull { busIdOf(it) == busId }
            ?: return ImportResult.Error(UsbIp.ST_NODEV, "no shared device with bus id $busId")
        synchronized(sessions) {
            if (sessions.containsKey(d.deviceName)) return ImportResult.Error(UsbIp.ST_DEV_BUSY, "$busId is already attached")
            val conn = usb.openDevice(d) ?: return ImportResult.Error(UsbIp.ST_DEV_ERR, "could not open $busId")
            val session = try {
                AndroidDeviceSession(d, conn, describe(d, conn)) {
                    sessions.remove(d.deviceName)
                    onSessionsChanged()
                }
            } catch (e: Exception) {
                Log.e(TAG, "import $busId failed", e)
                conn.close()
                return ImportResult.Error(UsbIp.ST_DEV_ERR, e.message ?: "import failed")
            }
            sessions[d.deviceName] = session
        }
        onSessionsChanged()
        return ImportResult.Ok(sessions[d.deviceName]!!)
    }

    fun onDeviceDetached(d: UsbDevice) {
        facts.remove(d.deviceName)
        sessions[d.deviceName]?.onDeviceDetached()
    }

    fun closeAllSessions() {
        sessions.values.toList().forEach { it.onDeviceDetached() }
    }

    // ------------------------------------------------------------------ description

    private fun describe(d: UsbDevice, existing: UsbDeviceConnection?): UsbIpDevice {
        val (busNum, devNum) = busDev(d)
        val f = facts[d.deviceName] ?: readFacts(d, existing, busNum, devNum).also { facts[d.deviceName] = it }
        val cfg = if (d.configurationCount > 0) d.getConfiguration(0) else null
        val ifaces = if (cfg != null) {
            (0 until cfg.interfaceCount).map { cfg.getInterface(it) }.filter { it.alternateSetting == 0 }
                .map { UsbIpInterface(it.interfaceClass, it.interfaceSubclass, it.interfaceProtocol) }
        } else {
            (0 until d.interfaceCount).map { d.getInterface(it) }.filter { it.alternateSetting == 0 }
                .map { UsbIpInterface(it.interfaceClass, it.interfaceSubclass, it.interfaceProtocol) }
        }
        return UsbIpDevice(
            path = d.deviceName,
            busId = busIdOf(d),
            busNum = busNum,
            devNum = devNum,
            speed = f.speed,
            vendorId = d.vendorId,
            productId = d.productId,
            bcdDevice = f.bcdDevice,
            deviceClass = d.deviceClass,
            deviceSubClass = d.deviceSubclass,
            deviceProtocol = d.deviceProtocol,
            configurationValue = cfg?.id ?: 1,
            numConfigurations = d.configurationCount,
            interfaces = ifaces,
        )
    }

    private fun readFacts(d: UsbDevice, existing: UsbDeviceConnection?, busNum: Int, devNum: Int): DeviceFacts {
        val conn = existing ?: usb.openDevice(d)
        try {
            val raw = conn?.rawDescriptors
            val desc = raw?.let { runCatching { UsbDescriptors.parse(it) }.getOrNull() }
            val speed = sysfsSpeed(busNum, devNum) ?: run {
                val qualifier = conn != null && desc != null && desc.bcdUsb >= 0x0200 &&
                    conn.controlTransfer(0x80, 0x06, 0x0600, 0, ByteArray(10), 10, 500) >= 10
                desc?.guessSpeed(qualifier) ?: UsbSpeed.FULL
            }
            Log.i(TAG, "${d.deviceName}: speed=$speed bcdUSB=${desc?.bcdUsb?.toString(16)}")
            return DeviceFacts(speed, desc?.bcdDevice ?: parseBcd(d.version))
        } finally {
            if (existing == null) conn?.close()
        }
    }

    /** sysfs is usually readable on older Android and blocked by SELinux on newer releases. */
    private fun sysfsSpeed(busNum: Int, devNum: Int): UsbSpeed? = runCatching {
        File("/sys/bus/usb/devices").listFiles()?.firstNotNullOfOrNull { dir ->
            runCatching {
                val b = File(dir, "busnum").readText().trim().toInt()
                val n = File(dir, "devnum").readText().trim().toInt()
                if (b == busNum && n == devNum) UsbSpeed.fromSysfs(File(dir, "speed").readText()) else null
            }.getOrNull()
        }?.takeIf { it != UsbSpeed.UNKNOWN }
    }.getOrNull()

    companion object {
        private const val TAG = "UsbIp"

        fun shareKey(d: UsbDevice) = "%04x:%04x".format(d.vendorId, d.productId)

        /** "/dev/bus/usb/001/005" -> (1, 5). */
        fun busDev(d: UsbDevice): Pair<Int, Int> {
            val parts = d.deviceName.split('/').filter { it.isNotEmpty() }
            val bus = parts.getOrNull(parts.size - 2)?.toIntOrNull() ?: 1
            val dev = parts.lastOrNull()?.toIntOrNull() ?: d.deviceId
            return bus to dev
        }

        fun devNum(d: UsbDevice) = busDev(d).second

        /** UsbDevice.getVersion() formats bcdDevice as "M.mm" (hex digits). */
        private fun parseBcd(v: String?): Int = runCatching {
            val (maj, min) = v!!.split('.')
            (maj.toInt(16) shl 8) or min.toInt(16)
        }.getOrDefault(0)
    }
}
