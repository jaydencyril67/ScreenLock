package com.jaydencyril67.screenlock

import android.app.Activity
import android.content.Intent
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
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
        PhantomTypography.apply(findViewById(android.R.id.content), this)
        showEntrySplash()
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
                "LOCAL MUSIC\nChoose audio files stored on your phone. Phantom plays those selected tracks directly, with previous, play/pause and next controls. It does not need notification-listener access and does not read or display your notifications.",
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

    /**
     * A launch-only brand splash, matching the KingsHall app entry experience.
     * It covers the setup dashboard briefly, then gets out of the way before
     * the user needs to grant permissions or choose a wallpaper.
     */
    private fun showEntrySplash() {
        val content = findViewById<ViewGroup>(android.R.id.content)
        val splash = FrameLayout(this).apply {
            setBackgroundColor(0xFF05070F.toInt())
            contentDescription = "Loading Phantom Lock"
            isClickable = true
            isFocusable = true
        }

        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val logoCircle = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.BLACK)
                setStroke(dp(1), 0x1AFFFFFF)
            }
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher_foreground)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Phantom Lock"
        }
        logoCircle.addView(
            logo,
            FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER)
        )
        center.addView(logoCircle, LinearLayout.LayoutParams(dp(92), dp(92)))

        val appName = TextView(this).apply {
            text = "PHANTOM LOCK"
            textSize = 13f
            setTextColor(Color.WHITE)
            letterSpacing = 0.14f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        center.addView(
            appName,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(18) }
        )

        center.addView(
            PhantomSplashShimmerView(this),
            LinearLayout.LayoutParams(dp(180), dp(3)).apply {
                topMargin = dp(10)
            }
        )

        splash.addView(
            center,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )

        val poweredBy = TextView(this).apply {
            text = "Powered by KingsHall"
            textSize = 12f
            setTextColor(0xB8FFFFFF.toInt())
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        splash.addView(
            poweredBy,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                leftMargin = dp(16)
                rightMargin = dp(16)
                bottomMargin = dp(28)
            }
        )

        content.addView(
            splash,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        PhantomTypography.apply(splash, this)

        // Keep the branded entry splash visible for the same 3-second
        // minimum used across the KingsHall app experience.
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

    private fun refreshDashboard() {
        refreshWallpaperStatus()
        val overlayGranted = Settings.canDrawOverlays(this)
        val enabled = prefs.getBoolean(PhantomOverlayService.PREF_ENABLED, false)

        overlayButton.text = if (overlayGranted) {
            "DISPLAY OVER OTHER APPS  ·  ALLOWED"
        } else {
            "ALLOW DISPLAY OVER OTHER APPS"
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
                statusDetails.text = "Phantom keeps running in the background. The notification is status-only; use the control in this app to turn protection off."
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
        releaseWallpaperPermission(PhantomWallpaperStore.savedUri(this))
        prefs.edit().remove(PhantomWallpaperStore.PREF_KEY).apply()
        refreshWallpaperStatus()
        Toast.makeText(this, "Default Phantom wallpaper restored.", Toast.LENGTH_SHORT).show()
    }

    private fun releaseWallpaperPermission(uri: Uri?) {
        if (uri == null) return
        try {
            contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // The picker may already have revoked the persisted grant.
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_WALLPAPER || resultCode != RESULT_OK) return

        val selectedUri = data?.data ?: return
        if ((data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
            try {
                contentResolver.takePersistableUriPermission(
                    selectedUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                Toast.makeText(
                    this,
                    "Android could not keep access to that image. Please choose it again.",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
        }

        val previousUri = PhantomWallpaperStore.savedUri(this)
        if (previousUri != selectedUri) {
            releaseWallpaperPermission(previousUri)
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

    private fun toggleProtection() {
        val enabled = prefs.getBoolean(PhantomOverlayService.PREF_ENABLED, false)
        if (enabled) {
            // Persist the user's in-app shutdown choice before asking the service to stop.
            prefs.edit()
                .putBoolean(PhantomOverlayService.PREF_ENABLED, false)
                .commit()
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

        // Save activation synchronously so it survives process death or a fast reboot.
        prefs.edit()
            .putBoolean(PhantomOverlayService.PREF_ENABLED, true)
            .commit()

        val intent = Intent(this, PhantomOverlayService::class.java)
            .setAction(PhantomOverlayService.ACTION_START)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (error: Exception) {
            prefs.edit()
                .putBoolean(PhantomOverlayService.PREF_ENABLED, false)
                .commit()
            Toast.makeText(
                this,
                "Phantom Lock could not start. Please try activating it again.",
                Toast.LENGTH_LONG
            ).show()
            refreshDashboard()
            return
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

/**
 * The same slim, continuous shimmer used by the KingsHall web app splash.
 * The animation runs independently of device motion/accessibility settings.
 */
private class PhantomSplashShimmerView(context: Context) : View(context) {
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x2EFFFFFF
    }
    private val shimmerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var progress = 0f
    private var shimmerAnimator: ValueAnimator? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        shimmerAnimator?.cancel()
        shimmerAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1150L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        shimmerAnimator?.cancel()
        shimmerAnimator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val bounds = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val radius = height / 2f
        canvas.drawRoundRect(bounds, radius, radius, trackPaint)

        val sweepWidth = width * 0.42f
        val left = -sweepWidth + (width + sweepWidth) * progress
        shimmerPaint.shader = LinearGradient(
            left,
            0f,
            left + sweepWidth,
            0f,
            intArrayOf(
                Color.TRANSPARENT,
                Color.WHITE,
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )

        val saveCount = canvas.save()
        canvas.clipRect(bounds)
        canvas.drawRoundRect(
            left,
            0f,
            left + sweepWidth,
            height.toFloat(),
            radius,
            radius,
            shimmerPaint
        )
        canvas.restoreToCount(saveCount)
        shimmerPaint.shader = null
    }
}
