package com.jaydencyril67.screenlock

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

/**
 * Phantom uses the dark One UI theme throughout the app.
 * The lockscreen keeps its separate custom theme on its own Activity and overlay context.
 */
class PhantomApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
    }
}
