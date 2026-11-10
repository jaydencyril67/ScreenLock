package com.jaydencyril67.screenlock

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import dev.oneuiproject.oneui.layout.ToolbarLayout

/**
 * Phantom's settings host.
 *
 * Navigation, preferences, dialogs, and splash rendering are supplied by the
 * One UI design library rather than a separate Phantom-only widget collection.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_phantom_settings)

        findViewById<ToolbarLayout>(R.id.toolbar_layout).apply {
            setTitle("Phantom")
            setSubtitle("Protection and personalization")
            isExpanded = false
            showNavigationButtonAsBack = true
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, PhantomSettingsFragment())
                .commit()
        }

        showEntrySplash()
    }

    /**
     * Keep the short branded entry moment while using the library's own
     * One UI splash component and typography.
     */
    private fun showEntrySplash() {
        val content = findViewById<ViewGroup>(android.R.id.content)
        val splash = layoutInflater.inflate(R.layout.view_phantom_splash, content, false)
        splash.isClickable = true
        splash.isFocusable = true

        content.addView(
            splash,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        Handler(Looper.getMainLooper()).postDelayed({
            if (splash.parent === content) {
                splash.animate()
                    .alpha(0f)
                    .setDuration(220L)
                    .withEndAction {
                        (splash.parent as? ViewGroup)?.removeView(splash)
                    }
                    .start()
            }
        }, 3_000L)
    }
}
