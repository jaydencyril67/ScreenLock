package com.jaydencyril67.screenlock

import androidx.appcompat.app.AppCompatDelegate
import com.oneuihomeclone.LauncherApp

/**
 * Phantom keeps its own identity and security services while inheriting the
 * One UI Home Clone launcher host, widget host, and crash recovery bootstrap.
 */
class PhantomApplication : LauncherApp() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
    }
}
