package com.cyberhayat.ijma

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After a reboot, resume background message alerts if the member was signed in. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (SyncService.prefs(c).getString("cfg", "").orEmpty().isNotEmpty()) {
            try { SyncService.start(c) } catch (_: Exception) {}
        }
    }
}
