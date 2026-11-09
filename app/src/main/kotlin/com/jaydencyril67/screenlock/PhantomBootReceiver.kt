package com.jaydencyril67.screenlock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

class PhantomBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val enabled = context.getSharedPreferences(
            PhantomOverlayService.PREFS,
            Context.MODE_PRIVATE
        ).getBoolean(PhantomOverlayService.PREF_ENABLED, false)

        if (!enabled || !Settings.canDrawOverlays(context)) return

        val serviceIntent = Intent(context, PhantomOverlayService::class.java)
            .setAction(PhantomOverlayService.ACTION_START)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
