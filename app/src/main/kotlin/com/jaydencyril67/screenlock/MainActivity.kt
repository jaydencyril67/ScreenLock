package com.jaydencyril67.screenlock

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        private const val REQUEST_WALLPAPER = 4170
    }

    private lateinit var statusTitle: TextView
    private lateinit var statusDetails: TextView
    private lateinit var overlayButton: Button
    private lateinit var notificationAccessButton: Button
    private lateinit var protectionButton: Button
    private lateinit var wallpaperStatus: TextView
    private lateinit var resetWallpaperButton: Button

    private val prefs by lazy {
        getSharedPreferences(PhantomOverlayService.PREFS, MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(5, 7, 15)
        window.navigationBarColor = Color.rgb(5, 7, 15)
        window.decorView.systemUiVisibility = 0
        buildDashboard()
    }

    override fun onResume() {
        super.onResume()
        if (::statusTitle.isInitialized) refreshDashboard()
    }

    private fun buildDashboard() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(5, 7, 15))
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(34), dp(24), dp(32))
        }
        scroll.addView(root)
        setContentView(scroll)

        val eyebrow = label("PHANTOM  /  SCREEN SECURITY", 10f, 0xFF8CA7D9.toInt(), true)
        eyebrow.letterSpacing = 0.18f
        root.addView(eyebrow)

        val title = label("Phantom Lock", 32f, Color.WHITE, false).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, dp(12), 0, dp(4))
        }
        root.addView(title)
        root.addView(label(
            "A full-screen S20-inspired lock screen that prepares before your display wakes.",
            13f,
            0xFFA4AEC3.toInt(),
            false
        ))

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = rounded(0xFF10182A.toInt(), 20f, 0xFF273653.toInt())
        }
        val statusEyebrow = label("PROTECTION STATUS", 9f, 0xFF91A7D0.toInt(), true)
        statusEyebrow.letterSpacing = 0.16f
        statusCard.addView(statusEyebrow)
        statusTitle = label("Checking setup…", 19f, Color.WHITE, true).apply {
            setPadding(0, dp(9), 0, dp(5))
        }
        statusCard.addView(statusTitle)
        statusDetails = label("", 12f, 0xFFAAB6CE.toInt(), false)
        statusCard.addView(statusDetails)
        val statusParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(26) }
        root.addView(statusCard, statusParams)

        val section = label("SETUP", 10f, 0xFF8296BC.toInt(), true).apply {
            letterSpacing = 0.18f
            setPadding(0, dp(28), 0, dp(10))
        }
        root.addView(section)

        overlayButton = actionButton("ALLOW DISPLAY OVER OTHER APPS", 0xFF243B63.toInt())
        overlayButton.setOnClickListener { requestOverlayPermission() }
        root.addView(overlayButton, buttonParams())

        notificationAccessButton = actionButton(
            "ALLOW NOTIFICATIONS & MUSIC",
            0xFF34313A.toInt()
        )
        notificationAccessButton.setOnClickListener { requestNotificationAccess() }
        root.addView(
            notificationAccessButton,
            buttonParams().apply { topMargin = dp(12) }
        )

        val wallpaperSection = label(
            "LOCKSCREEN WALLPAPER",
            10f,
            0xFF8296BC.toInt(),
            true
        ).apply {
            letterSpacing = 0.18f
            setPadding(0, dp(28), 0, dp(10))
        }
        root.addView(wallpaperSection)

        wallpaperStatus = label("", 12f, 0xFFAAB6CE.toInt(), false).apply {
            setPadding(0, 0, 0, dp(12))
        }
        root.addView(wallpaperStatus)

        val chooseWallpaperButton = actionButton(
            "CHOOSE WALLPAPER FROM DEVICE",
            0xFF27354A.toInt()
        )
        chooseWallpaperButton.setOnClickListener { openWallpaperPicker() }
        root.addView(chooseWallpaperButton, buttonParams())

        resetWallpaperButton = actionButton(
            "RESTORE DEFAULT WALLPAPER",
            0xFF34313A.toInt()
        )
        resetWallpaperButton.setOnClickListener { restoreDefaultWallpaper() }
        root.addView(
            resetWallpaperButton,
            buttonParams().apply { topMargin = dp(10) }
        )

        protectionButton = actionButton("ACTIVATE PHANTOM LOCK", 0xFF526ED6.toInt())
        protectionButton.setOnClickListener { toggleProtection() }
        val protectParams = buttonParams().apply { topMargin = dp(18) }
        root.addView(protectionButton, protectParams)

        val note = label(
            "HOW IT WORKS\nWhen the display goes to sleep, Phantom prepares its full-screen lock window without turning the display back on. When you wake the phone, the prepared window is already in place over the lockscreen. A screen-on launch remains as a recovery fallback.\n\nA small padlock silently arms one fingerprint tap. An ordinary fingerprint tap displays “Fingerprint doesn't match” with haptic feedback. On phones with enrolled fingerprint hardware, Android's genuine biometric prompt is used. The Galaxy A02 has no fingerprint reader, so its armed tap is a visual unlock gesture.\n\n" +
                if (BuildConfig.HAS_NOTIFICATION_ACCESS) {
                    "NOTIFICATIONS & MUSIC\nOptional notification access lets Phantom show notification previews and control the active media session. Notification content is processed locally for the lockscreen."
                } else {
                    "NOTIFICATIONS & MUSIC\nNot included in this direct-download edition. The full notification and media-session integration is kept in the Play-distribution build because Android blocks unverified internet-sideloaded apps that request notification-listener access."
                },
            12f,
            0xFF8793AD.toInt(),
            false
        ).apply {
            setPadding(0, dp(25), 0, 0)
            setLineSpacing(dp(5).toFloat(), 1f)
        }
        root.addView(note)

        val foot = label(
            "ONE TAP TO FAIL  ·  PADLOCK TO ARM  ·  ONE TAP TO OPEN",
            9f,
            0xFF596985.toInt(),
            true
        ).apply {
            letterSpacing = 0.05f
            setPadding(0, dp(24), 0, 0)
            gravity = Gravity.CENTER
        }
        root.addView(foot)

        refreshDashboard()
    }

    private fun refreshDashboard() {
        refreshWallpaperStatus()
        notificationAccessButton.visibility =
            if (BuildConfig.HAS_NOTIFICATION_ACCESS) View.VISIBLE else View.GONE

        val overlayGranted = Settings.canDrawOverlays(this)
        val enabled = prefs.getBoolean(PhantomOverlayService.PREF_ENABLED, false)

        overlayButton.text = if (overlayGranted) {
            "DISPLAY OVER OTHER APPS  ·  ALLOWED"
        } else {
            "ALLOW DISPLAY OVER OTHER APPS"
        }

        notificationAccessButton.text = if (notificationAccessGranted()) {
            "NOTIFICATIONS & MUSIC  ·  ALLOWED"
        } else {
            "ALLOW NOTIFICATIONS & MUSIC"
        }

        when {
            !overlayGranted -> {
                statusTitle.text = "Permission needed"
                statusTitle.setTextColor(0xFFFFCF88.toInt())
                statusDetails.text = "Allow Phantom Lock to display over other apps so it can appear when the screen wakes."
            }
            enabled -> {
                statusTitle.text = "Phantom protection enabled"
                statusTitle.setTextColor(0xFF9FE6C4.toInt())
                statusDetails.text = "Phantom prepares its lock window as the display sleeps, then keeps it ready over the keyguard. The persistent notification can turn protection off."
            }
            else -> {
                statusTitle.text = "Ready to activate"
                statusTitle.setTextColor(0xFFB9C9F4.toInt())
                statusDetails.text = "No Android PIN, pattern or password setup is required by Phantom Lock."
            }
        }

        protectionButton.text = if (enabled) {
            "TURN OFF PHANTOM LOCK"
        } else {
            "ACTIVATE PHANTOM LOCK"
        }
        protectionButton.background = rounded(
            if (enabled) 0xFF563044.toInt() else 0xFF526ED6.toInt(),
            18f
        )
    }

    private fun refreshWallpaperStatus() {
        if (!::wallpaperStatus.isInitialized) return
        val selected = prefs.getString(PhantomWallpaperStore.PREF_KEY, null)
        wallpaperStatus.text = if (selected.isNullOrBlank()) {
            "Using Phantom's built-in dark wallpaper. Choose any photo stored on your phone to make it your lockscreen background."
        } else {
            "Your custom lockscreen wallpaper is saved. It will remain selected after you close or restart Phantom."
        }
        if (::resetWallpaperButton.isInitialized) {
            resetWallpaperButton.visibility =
                if (selected.isNullOrBlank()) View.GONE else View.VISIBLE
        }
    }

    @Suppress("DEPRECATION")
    private fun openWallpaperPicker() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }

        try {
            startActivityForResult(picker, REQUEST_WALLPAPER)
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "No compatible image picker is available on this device.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun restoreDefaultWallpaper() {
        prefs.edit().remove(PhantomWallpaperStore.PREF_KEY).apply()
        refreshWallpaperStatus()
        Toast.makeText(this, "Default Phantom wallpaper restored.", Toast.LENGTH_SHORT).show()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_WALLPAPER || resultCode != RESULT_OK) return

        val selectedUri = data?.data ?: return
        val readPermission = (data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (readPermission != 0) {
            try {
                contentResolver.takePersistableUriPermission(selectedUri, readPermission)
            } catch (_: SecurityException) {
                Toast.makeText(
                    this,
                    "Android could not keep access to that image. Please choose it again.",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
        }

        prefs.edit()
            .putString(PhantomWallpaperStore.PREF_KEY, selectedUri.toString())
            .apply()
        refreshWallpaperStatus()
        Toast.makeText(
            this,
            "Lockscreen wallpaper saved.",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay access is already enabled.", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }
    }

    private fun notificationAccessGranted(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ).orEmpty()

        return enabled.split(':').any { entry ->
            val component = ComponentName.unflattenFromString(entry)
            component != null &&
                component.packageName == packageName &&
                component.className == "$packageName.PhantomNotificationListenerService"
        }
    }

    private fun requestNotificationAccess() {
        if (notificationAccessGranted()) {
            Toast.makeText(
                this,
                "Notification access is already enabled.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "Open Android Settings and enable notification access for ScreenLock.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun toggleProtection() {
        val enabled = prefs.getBoolean(PhantomOverlayService.PREF_ENABLED, false)
        if (enabled) {
            startService(
                Intent(this, PhantomOverlayService::class.java)
                    .setAction(PhantomOverlayService.ACTION_STOP)
            )
            Toast.makeText(this, "Phantom Lock turned off.", Toast.LENGTH_SHORT).show()
            refreshDashboard()
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }

        val intent = Intent(this, PhantomOverlayService::class.java)
            .setAction(PhantomOverlayService.ACTION_START)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        Toast.makeText(
            this,
            "Phantom Lock activated. Turn your screen off and on to test it.",
            Toast.LENGTH_LONG
        ).show()
        finish()
    }

    private fun label(
        text: String,
        size: Float,
        color: Int,
        bold: Boolean
    ) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        typeface = if (bold) {
            Typeface.create("sans-serif-medium", Typeface.NORMAL)
        } else {
            Typeface.create("sans-serif", Typeface.NORMAL)
        }
    }

    private fun actionButton(text: String, color: Int) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 11f
        letterSpacing = 0.04f
        setTextColor(Color.WHITE)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = rounded(color, 18f)
        elevation = 0f
        minHeight = dp(56)
        minimumHeight = dp(56)
        stateListAnimator = null
    }

    private fun rounded(color: Int, radius: Float, stroke: Int? = null) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radius)
            setColor(color)
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun buttonParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        dp(56)
    )

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun dp(value: Float): Float =
        value * resources.displayMetrics.density
}
