package dev.zk.usbip

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import dev.zk.usbip.protocol.DeviceDetail
import dev.zk.usbip.protocol.ServerInfo
import dev.zk.usbip.service.UsbIpService
import dev.zk.usbip.usb.AndroidUsbBackend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class DeviceUi(
    val deviceName: String,
    val shareKey: String,
    val busId: String,
    val title: String,
    val subtitle: String,
    val shared: Boolean,
    val hasPermission: Boolean,
    val attachedBy: String?,
    /** Null if the device can be shared, otherwise why not. */
    val notShareableReason: String? = null,
)

data class ServerUi(
    val running: Boolean = false,
    val port: Int = 0,
    val addresses: List<String> = emptyList(),
    val error: String? = null,
)

class UsbIpApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var backend: AndroidUsbBackend
        private set

    private val _devices = MutableStateFlow<List<DeviceUi>>(emptyList())
    val devices: StateFlow<List<DeviceUi>> = _devices.asStateFlow()

    private val _server = MutableStateFlow(ServerUi())
    val server: StateFlow<ServerUi> = _server.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    /** busId -> client IP currently attached. */
    val attachedClients = ConcurrentHashMap<String, String>()

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    device?.let { backend.onDeviceDetached(it) }
                    log("unplugged ${device?.productName ?: device?.deviceName}")
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    log("plugged in ${device?.productName ?: device?.deviceName}")
                    maybeAutoStart(device)
                }
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    log("permission ${if (granted) "granted" else "denied"} for ${device?.productName ?: device?.deviceName}")
                }
            }
            refreshDevices()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        backend = AndroidUsbBackend(this, prefs)
        backend.onSessionsChanged = { refreshDevices() }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        refreshDevices()
    }

    fun refreshDevices() {
        _devices.value = backend.allDevices().map { d ->
            val perm = backend.hasPermission(d)
            val busId = backend.busIdOf(d)
            val name = listOfNotNull(
                d.manufacturerName?.takeIf { perm && it.isNotBlank() },
                d.productName?.takeIf { it.isNotBlank() },
            ).joinToString(" ").ifBlank { "USB device" }
            DeviceUi(
                deviceName = d.deviceName,
                shareKey = AndroidUsbBackend.shareKey(d),
                busId = busId,
                title = name,
                subtitle = "${AndroidUsbBackend.shareKey(d)} · bus id $busId",
                shared = backend.isShared(d),
                hasPermission = perm,
                attachedBy = if (backend.isInUse(d)) attachedClients[busId] ?: "a client" else null,
                notShareableReason = when (d.deviceClass) {
                    AndroidUsbBackend.USB_CLASS_HUB -> "USB hubs can't be shared. Share the devices plugged into it instead."
                    AndroidUsbBackend.USB_CLASS_BILLBOARD -> "This is an information-only function of a USB-C adapter; there's nothing to share."
                    else -> null
                },
            )
        }
        _server.update { it.copy(addresses = if (it.running) localAddresses() else emptyList()) }
    }

    /** Data for the OP_REQ_INFO extension: friendly names and current holders of exported devices. */
    fun serverInfo(): ServerInfo {
        val name = runCatching { android.provider.Settings.Global.getString(contentResolver, android.provider.Settings.Global.DEVICE_NAME) }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL
        val details = backend.exportedDevices().map { d ->
            val busId = backend.busIdOf(d)
            val title = listOfNotNull(
                d.manufacturerName?.takeIf { it.isNotBlank() },
                d.productName?.takeIf { it.isNotBlank() },
            ).joinToString(" ").ifBlank { AndroidUsbBackend.shareKey(d) }
            DeviceDetail(busId, title, if (backend.isInUse(d)) attachedClients[busId] ?: "?" else null)
        }
        return ServerInfo(name, details)
    }

    fun setShared(deviceName: String, shared: Boolean) {
        val d = backend.allDevices().firstOrNull { it.deviceName == deviceName } ?: return
        prefs.setShared(AndroidUsbBackend.shareKey(d), shared)
        if (shared && !backend.hasPermission(d)) requestPermission(d)
        if (!shared && backend.isInUse(d)) backend.onDeviceDetached(d) // kick the client off
        refreshDevices()
    }

    fun requestPermission(deviceName: String) {
        backend.allDevices().firstOrNull { it.deviceName == deviceName }?.let(::requestPermission)
    }

    private fun requestPermission(d: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags)
        getSystemService(UsbManager::class.java).requestPermission(d, pi)
    }

    fun maybeAutoStart(device: UsbDevice? = null) {
        if (!prefs.autoStart || _server.value.running) return
        if (device != null && !backend.isShared(device)) return
        UsbIpService.start(this)
    }

    fun setServerState(running: Boolean, port: Int, error: String? = null) {
        _server.value = ServerUi(running, port, if (running) localAddresses() else emptyList(), error)
        refreshDevices()
    }

    fun log(message: String) {
        val line = "${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}  $message"
        _log.update { (listOf(line) + it).take(200) }
    }

    companion object {
        const val ACTION_USB_PERMISSION = "dev.zk.usbip.USB_PERMISSION"
        lateinit var instance: UsbIpApp
            private set

        /** Non-loopback IPv4 addresses of this phone (Wi-Fi, hotspot, Ethernet, VPN). */
        fun localAddresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { nif.name to it.hostAddress!! } }
                .sortedBy { (name, _) -> if (name.startsWith("wlan")) 0 else if (name.startsWith("eth")) 1 else 2 }
                .map { it.second }
        }.getOrDefault(emptyList())
    }
}
