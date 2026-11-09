package com.jaydencyril67.screenlock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log

class PhantomBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }

        val enabled = context.getSharedPreferences(
            PhantomOverlayService.PREFS,
            Context.MODE_PRIVATE
        ).getBoolean(PhantomOverlayService.PREF_ENABLED, false)

        if (!enabled) {
            Log.i(TAG, "Protection is disabled; no service restart requested")
            return
        }

        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Saved protection state is enabled, but overlay access is not granted")
            return
        }

        val serviceIntent = Intent(context, PhantomOverlayService::class.java)
            .setAction(PhantomOverlayService.ACTION_START)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.i(TAG, "Requested Phantom service recovery after ${intent.action}")
        } catch (error: Exception) {
            Log.e(TAG, "Unable to restore Phantom service after ${intent.action}", error)
        }
    }

    companion object {
        private const val TAG = "PhantomBoot"
    }
}
