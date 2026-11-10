package com.jaydencyril67.screenlock

import androidx.appcompat.app.AppCompatDelegate
import com.oneuihomeclone.LauncherApp

/**
 * Phantom's process-wide Application.
 *
 * It extends the imported launcher's Application foundation so the same process owns
 * the AppWidgetHost, widget request callbacks and crash-recovery diagnostics while
 * retaining Phantom's existing dark One UI configuration.
 */
class PhantomApplication : LauncherApp() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
    }
}
