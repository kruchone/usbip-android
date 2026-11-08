package dev.zk.usbip

import android.content.Context
import dev.zk.usbip.protocol.UsbIp

/** Small SharedPreferences wrapper for settings, shared devices and stable bus-id slots. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("usbip", Context.MODE_PRIVATE)

    var port: Int
        get() = sp.getInt("port", UsbIp.DEFAULT_PORT)
        set(v) = sp.edit().putInt("port", v).apply()

    /** Start the server when the app opens, a shared device is plugged in, or the phone boots. */
    var autoStart: Boolean
        get() = sp.getBoolean("auto_start", false)
        set(v) = sp.edit().putBoolean("auto_start", v).apply()

    /** Comma/space separated client IPs allowed to connect; blank means anyone on the network. */
    var allowedClients: String
        get() = sp.getString("allowed_clients", "") ?: ""
        set(v) = sp.edit().putString("allowed_clients", v.trim()).apply()

    fun allowedClientSet(): Set<String> =
        allowedClients.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** User-chosen label for a device identity (see AndroidUsbBackend.identityOf), or null. */
    fun customName(identity: String): String? = sp.getString("name:$identity", null)

    fun setCustomName(identity: String, name: String?) {
        val e = sp.edit()
        if (name.isNullOrBlank()) e.remove("name:$identity") else e.putString("name:$identity", name.trim())
        e.apply()
    }

    /** Devices are shared by "vvvv:pppp" so the choice survives re-plugging. */
    fun isShared(key: String) = key in sp.getStringSet("shared", emptySet())!!

    fun setShared(key: String, shared: Boolean) {
        val set = sp.getStringSet("shared", emptySet())!!.toMutableSet()
        if (shared) set += key else set -= key
        sp.edit().putStringSet("shared", set).apply()
    }

    /**
     * Returns a small, stable number for a device identity so its bus id ("1-N") stays the same across
     * re-plugs; that lets the Windows client re-attach by the same bus id.
     */
    @Synchronized
    fun slotFor(identity: String): Int {
        val entries = sp.getStringSet("slots", emptySet())!!
        val map = entries.mapNotNull { e ->
            val i = e.lastIndexOf('=')
            if (i <= 0) null else e.substring(0, i) to (e.substring(i + 1).toIntOrNull() ?: return@mapNotNull null)
        }.toMap()
        map[identity]?.let { return it }
        val slot = (map.values.maxOrNull() ?: 0) + 1
        sp.edit().putStringSet("slots", entries + "$identity=$slot").apply()
        return slot
    }
}
