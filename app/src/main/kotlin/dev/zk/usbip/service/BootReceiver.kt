package dev.zk.usbip.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.zk.usbip.UsbIpApp

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        (context.applicationContext as UsbIpApp).maybeAutoStart()
    }
}
