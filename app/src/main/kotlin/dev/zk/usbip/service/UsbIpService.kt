package dev.zk.usbip.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.zk.usbip.R
import dev.zk.usbip.UsbIpApp
import dev.zk.usbip.ui.MainActivity
import dev.zk.usbip.protocol.UsbIpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service that owns the USB/IP server. While at least one client is attached it holds a
 * partial wake lock and a Wi-Fi lock so transfers keep flowing with the screen off.
 */
class UsbIpService : Service() {
    private val app get() = application as UsbIpApp
    private var server: UsbIpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val attachedCount = AtomicInteger(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        if (server == null) startServer()
        return START_STICKY
    }

    private fun startInForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    private fun startServer() {
        val port = app.prefs.port
        val allowed = app.prefs.allowedClientSet()
        val s = UsbIpServer(app.backend, port, listener = object : UsbIpServer.Listener {
            override fun accept(remote: InetSocketAddress) =
                allowed.isEmpty() || remote.address.hostAddress in allowed

            override fun onAttached(busId: String, remote: InetSocketAddress) {
                app.attachedClients[busId] = remote.address.hostAddress ?: "?"
                if (attachedCount.incrementAndGet() == 1) acquireLocks()
                app.refreshDevices()
                updateNotification()
            }

            override fun onDetached(busId: String, remote: InetSocketAddress) {
                app.attachedClients.remove(busId)
                if (attachedCount.decrementAndGet() == 0) releaseLocks()
                app.refreshDevices()
                updateNotification()
            }

            override fun log(message: String, error: Throwable?) {
                if (error != null) Log.w(TAG, message, error) else Log.i(TAG, message)
                app.log(message)
            }
        })
        try {
            s.start()
            server = s
            app.setServerState(running = true, port = port)
            updateNotification()
        } catch (e: Exception) {
            Log.e(TAG, "could not start server", e)
            app.setServerState(running = false, port = port, error = "Could not listen on port $port: ${e.message}")
            stopSelf()
        }
    }

    override fun onDestroy() {
        server?.close()
        server = null
        app.backend.closeAllSessions()
        app.attachedClients.clear()
        releaseLocks()
        // Keep a startup error visible; otherwise just mark the server stopped.
        if (app.server.value.error == null) app.setServerState(running = false, port = app.prefs.port)
        app.log("server stopped")
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "usbip:transfer").apply {
            setReferenceCounted(false)
            acquire()
        }
        val wm = applicationContext.getSystemService(WifiManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wm.createWifiLock(mode, "usbip:transfer").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock = null
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "USB/IP server", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, UsbIpService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val address = UsbIpApp.localAddresses().firstOrNull()?.let { "$it:${app.prefs.port}" } ?: "no network"
        val attached = attachedCount.get()
        val text = if (attached > 0) "$attached device(s) attached · $address" else "Waiting for clients · $address"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_usb)
            .setContentTitle("USB/IP server running")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        private const val TAG = "UsbIp"
        private const val CHANNEL_ID = "server"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "dev.zk.usbip.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, UsbIpService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, UsbIpService::class.java))
        }
    }
}
