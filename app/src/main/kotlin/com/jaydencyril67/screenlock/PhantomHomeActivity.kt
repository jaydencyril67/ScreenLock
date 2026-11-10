package com.jaydencyril67.screenlock

import android.app.Activity
import android.app.SearchManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.GestureDetector
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.content.pm.ResolveInfo
import android.text.Editable
import android.text.TextWatcher
import java.text.Collator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phantom's default Home surface.
 *
 * This Activity is deliberately separate from MainActivity (setup/settings)
 * and PhantomLockActivity (the existing lock surface). That keeps launcher
 * navigation independent from the fingerprint and lockscreen lifecycle.
 */
class PhantomHomeActivity : Activity() {

    private data class LauncherApp(
        val packageName: String,
        val activityName: String,
        val label: String,
        val icon: Drawable
    )

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var clockLabel: TextView
    private lateinit var dateLabel: TextView
    private lateinit var gestureDetector: GestureDetector
    private lateinit var widgetContainer: LinearLayout
    private lateinit var widgetHost: PhantomWidgetHost

    private var launchableApps: List<LauncherApp> = emptyList()
    private var drawerOverlay: FrameLayout? = null
    private var drawerAppsContainer: LinearLayout? = null
    private var drawerSearch: EditText? = null

    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.rgb(10, 11, 17)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN

        launchableApps = queryLaunchableApps()
        gestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(event: MotionEvent): Boolean = true

                override fun onFling(
                    first: MotionEvent?,
                    second: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean {
                    val start = first ?: return false
                    val distance = second.y - start.y
                    if (distance < -dp(70) && drawerOverlay == null) {
                        openAppDrawer()
                        return true
                    }
                    if (distance > dp(70) && drawerOverlay != null) {
                        closeAppDrawer()
                        return true
                    }
                    return false
                }
            }
        )

        buildHome()
    }

    override fun onStart() {
        super.onStart()
        if (::widgetHost.isInitialized) widgetHost.startListening()
    }

    override fun onResume() {
        super.onResume()
        launchableApps = queryLaunchableApps()
        updateClock()
        if (::widgetHost.isInitialized) widgetHost.render()
        handler.removeCallbacks(clockTick)
        handler.post(clockTick)
    }

    override fun onPause() {
        handler.removeCallbacks(clockTick)
        super.onPause()
    }

    override fun onStop() {
        if (::widgetHost.isInitialized) widgetHost.stopListening()
        super.onStop()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (::widgetHost.isInitialized) {
            widgetHost.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }

    private fun buildHome() {
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(7, 9, 15))
            isFocusableInTouchMode = true
        }

        root.addView(
            PhantomWallpaperView(this),
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // A restrained scrim preserves wallpaper detail while keeping One UI-like
        // glass surfaces and labels readable on both bright and dark wallpapers.
        root.addView(
            View(this).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0x4A05070D, 0x1205070D, 0x7205070D)
                )
            },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(48), dp(22), dp(18))
        }
        root.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val clockBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        clockLabel = text("09:41", 49f, Color.WHITE, false).apply {
            includeFontPadding = false
            letterSpacing = -0.035f
            setShadowLayer(dp(10).toFloat(), 0f, dp(2).toFloat(), 0x55000000)
        }
        dateLabel = text("", 13f, 0xEFFFFFFF.toInt(), false).apply {
            setPadding(dp(2), dp(3), 0, 0)
            letterSpacing = 0.015f
        }
        clockBlock.addView(clockLabel)
        clockBlock.addView(dateLabel)
        header.addView(
            clockBlock,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        val settingsButton = text("⚙", 24f, Color.WHITE, false).apply {
            gravity = Gravity.CENTER
            background = glassSurface(0x36FFFFFF, 0x50FFFFFF, 23f)
            contentDescription = "Open Phantom settings"
            isClickable = true
            isFocusable = true
            setOnClickListener {
                startActivity(Intent(this@PhantomHomeActivity, MainActivity::class.java))
            }
        }
        header.addView(
            settingsButton,
            LinearLayout.LayoutParams(dp(49), dp(49))
        )
        content.addView(header)

        val widgetHeading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        widgetHeading.addView(
            text("WIDGETS", 10f, 0xDFFFFFFF.toInt(), true).apply {
                letterSpacing = 0.16f
            },
            LinearLayout.LayoutParams(0, dp(34), 1f)
        )
        val addWidgetButton = text("+  ADD", 10f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
            setPadding(dp(13), 0, dp(13), 0)
            background = glassSurface(0x36FFFFFF, 0x48FFFFFF, 18f)
            isClickable = true
            isFocusable = true
            setOnClickListener { widgetHost.addWidget() }
        }
        widgetHeading.addView(
            addWidgetButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34))
        )
        content.addView(
            widgetHeading,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(34)
            ).apply { topMargin = dp(15) }
        )

        widgetContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(
            widgetContainer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(5) }
        )
        widgetHost = PhantomWidgetHost(this, widgetContainer)

        val search = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(17), 0, dp(14), 0)
            background = glassSurface(0xDDF7F7FA.toInt(), 0xAFFFFFFF.toInt(), 26f)
            isClickable = true
            isFocusable = true
            setOnClickListener { openAppDrawer(focusSearch = true) }
        }
        search.addView(
            text("⌕", 27f, 0xFF343744.toInt(), false).apply {
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(dp(32), dp(50))
        )
        search.addView(
            text("Search apps", 14f, 0xFF515563.toInt(), false).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(7), 0, 0, 0)
            },
            LinearLayout.LayoutParams(0, dp(50), 1f)
        )
        search.addView(
            text("⋮", 22f, 0xFF4B5060.toInt(), false).apply {
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(dp(25), dp(50))
        )
        content.addView(
            search,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52)
            ).apply { topMargin = dp(15) }
        )

        val spacer = View(this)
        content.addView(
            spacer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        val appHeading = text("FAVOURITES", 10f, 0xDFFFFFFF.toInt(), true).apply {
            letterSpacing = 0.16f
            setPadding(dp(2), 0, 0, dp(9))
        }
        content.addView(appHeading)

        val pinnedApps = selectPinnedApps()
        val homeApps = (launchableApps.filterNot { app ->
            pinnedApps.any { it.packageName == app.packageName }
        }).take(8)

        addAppRows(content, homeApps, 4)

        val drawerHint = text("SWIPE UP FOR ALL APPS", 9f, 0xBFFFFFFF.toInt(), true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.13f
            setPadding(0, dp(2), 0, dp(9))
            isClickable = true
            setOnClickListener { openAppDrawer() }
        }
        content.addView(drawerHint)

        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(7), dp(6), dp(7), dp(6))
            background = glassSurface(0x3DFFFFFF, 0x50FFFFFF, 27f)
        }
        pinnedApps.take(4).forEach { app ->
            dock.addView(
                createAppTile(app, showLabel = false),
                LinearLayout.LayoutParams(0, dp(70), 1f)
            )
        }
        content.addView(
            dock,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(80)
            ).apply { topMargin = dp(1) }
        )

        setContentView(root)
        PhantomTypography.apply(root, this)
    }

    private fun addAppRows(
        parent: LinearLayout,
        apps: List<LauncherApp>,
        columns: Int
    ) {
        apps.chunked(columns).forEach { rowApps ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            rowApps.forEach { app ->
                row.addView(
                    createAppTile(app, showLabel = true),
                    LinearLayout.LayoutParams(0, dp(100), 1f)
                )
            }
            repeat((columns - rowApps.size).coerceAtLeast(0)) {
                row.addView(
                    View(this),
                    LinearLayout.LayoutParams(0, dp(100), 1f)
                )
            }
            parent.addView(row)
        }
    }

    private fun createAppTile(
        app: LauncherApp,
        showLabel: Boolean
    ): View {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setOnClickListener { launchApp(app) }
        }

        // One UI-inspired squircle icon plate: a consistent surface and larger
        // icon area, without replacing the identity of the app's own logo.
        val iconFrame = FrameLayout(this).apply {
            background = appIconSurface(app)
            clipToOutline = true
            elevation = dp(2).toFloat()
        }
        val icon = ImageView(this).apply {
            setImageDrawable(app.icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = app.label
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        iconFrame.addView(
            icon,
            FrameLayout.LayoutParams(dp(54), dp(54), Gravity.CENTER)
        )
        tile.addView(
            iconFrame,
            LinearLayout.LayoutParams(dp(60), dp(60)).apply {
                gravity = Gravity.CENTER
            }
        )

        if (showLabel) {
            tile.addView(
                text(app.label, 10.5f, Color.WHITE, false).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(dp(2), dp(5), dp(2), 0)
                    shadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(24)
                )
            )
        }
        return tile
    }

    private fun appIconSurface(app: LauncherApp): GradientDrawable {
        val packageName = app.packageName.lowercase(Locale.ROOT)
        val colors = when {
            packageName.contains("dialer") || packageName.contains("phone") ->
                intArrayOf(0xFFE5F5ED.toInt(), 0xFFC8EBDD.toInt())
            packageName.contains("messag") || packageName.contains("sms") ->
                intArrayOf(0xFFE9F2FF.toInt(), 0xFFD4E2FA.toInt())
            packageName.contains("camera") ->
                intArrayOf(0xFFF2F0EC.toInt(), 0xFFDAD7D1.toInt())
            packageName.contains("gallery") || packageName.contains("photo") ->
                intArrayOf(0xFFFCEAF0.toInt(), 0xFFF2D5E2.toInt())
            packageName.contains("browser") || packageName.contains("chrome") ->
                intArrayOf(0xFFE8F2FB.toInt(), 0xFFD3E3F5.toInt())
            packageName.contains("setting") ->
                intArrayOf(0xFFEDEFF4.toInt(), 0xFFDADDE6.toInt())
            packageName.contains("music") || packageName.contains("spotify") ->
                intArrayOf(0xFFE9F3E9.toInt(), 0xFFD5E8D7.toInt())
            else -> intArrayOf(0xFFF1F1F6.toInt(), 0xFFDADDE8.toInt())
        }
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            colors
        ).apply {
            cornerRadius = dp(19).toFloat()
            setStroke(dp(1), 0x66FFFFFF)
        }
    }

    private fun selectPinnedApps(): List<LauncherApp> {
        val preferredPackages = listOf(
            "com.samsung.android.dialer",
            "com.google.android.dialer",
            "com.android.dialer",
            "com.samsung.android.messaging",
            "com.google.android.apps.messaging",
            "com.sec.android.app.camera",
            "com.android.camera2",
            "com.android.chrome",
            "com.sec.android.app.sbrowser"
        )
        val selected = preferredPackages.mapNotNull { packageName ->
            launchableApps.firstOrNull { it.packageName == packageName }
        }.distinctBy { it.packageName }.toMutableList()

        launchableApps.forEach { app ->
            if (selected.size < 4 && selected.none { it.packageName == app.packageName }) {
                selected.add(app)
            }
        }
        return selected.take(4)
    }

    private fun queryLaunchableApps(): List<LauncherApp> {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val collator = Collator.getInstance(Locale.getDefault())

        return packageManager.queryIntentActivities(query, 0)
            .mapNotNull { resolved: ResolveInfo ->
                val info = resolved.activityInfo ?: return@mapNotNull null
                runCatching {
                    LauncherApp(
                        packageName = info.packageName,
                        activityName = info.name,
                        label = resolved.loadLabel(packageManager).toString(),
                        icon = resolved.loadIcon(packageManager)
                    )
                }.getOrNull()
            }
            .distinctBy { it.packageName + "/" + it.activityName }
            .sortedWith { left, right -> collator.compare(left.label, right.label) }
    }

    private fun launchApp(app: LauncherApp) {
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(app.packageName)
                ?: Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setClassName(app.packageName, app.activityName)
                }
            closeAppDrawerImmediately()
            startActivity(launchIntent)
        } catch (_: Exception) {
            Toast.makeText(this, "This app could not be opened.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAppDrawer(focusSearch: Boolean = false) {
        if (drawerOverlay != null) return

        val overlay = FrameLayout(this).apply {
            setBackgroundColor(0xC9080A10.toInt())
            isClickable = true
            isFocusable = true
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(17), dp(22), dp(18))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xF51C202B.toInt(), 0xF20C1019.toInt())
            ).apply {
                cornerRadii = floatArrayOf(
                    dp(30).toFloat(), dp(30).toFloat(),
                    dp(30).toFloat(), dp(30).toFloat(),
                    0f, 0f, 0f, 0f
                )
                setStroke(dp(1), 0x35FFFFFF)
            }
        }

        val handle = View(this).apply {
            background = glassSurface(0x70FFFFFF, 0x00FFFFFF, 4f)
        }
        panel.addView(
            handle,
            LinearLayout.LayoutParams(dp(42), dp(4)).apply {
                gravity = Gravity.CENTER
                bottomMargin = dp(17)
            }
        )

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(
            text("All apps", 25f, Color.WHITE, false),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val close = text("×", 29f, 0xEFFFFFFF.toInt(), false).apply {
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { closeAppDrawer() }
        }
        titleRow.addView(close, LinearLayout.LayoutParams(dp(42), dp(42)))
        panel.addView(titleRow)

        val search = EditText(this).apply {
            hint = "Search apps"
            setHintTextColor(0xAFFFFFFF.toInt())
            setTextColor(Color.WHITE)
            textSize = 14f
            setSingleLine(true)
            background = glassSurface(0x30FFFFFF, 0x40FFFFFF, 18f)
            setPadding(dp(16), 0, dp(16), 0)
            compoundDrawablePadding = dp(8)
        }
        panel.addView(
            search,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(50)
            ).apply { topMargin = dp(14) }
        )

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val appsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(15), 0, dp(12))
        }
        scroll.addView(
            appsContainer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        panel.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply { topMargin = dp(5) }
        )

        renderDrawerApps(appsContainer, launchableApps)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val queryText = s?.toString().orEmpty().trim()
                val filtered = if (queryText.isEmpty()) {
                    launchableApps
                } else {
                    launchableApps.filter { it.label.contains(queryText, ignoreCase = true) }
                }
                renderDrawerApps(appsContainer, filtered)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        overlay.addView(
            panel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.BOTTOM
            )
        )
        root.addView(
            overlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        drawerOverlay = overlay
        drawerAppsContainer = appsContainer
        drawerSearch = search

        panel.translationY = dp(75).toFloat()
        panel.alpha = 0.94f
        panel.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(190L)
            .start()
        PhantomTypography.apply(panel, this)
        if (focusSearch) {
            search.requestFocus()
            search.post {
                val inputMethod = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                inputMethod.showSoftInput(search, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun renderDrawerApps(
        container: LinearLayout,
        apps: List<LauncherApp>
    ) {
        container.removeAllViews()
        if (apps.isEmpty()) {
            container.addView(
                text("No matching apps", 14f, 0xBFFFFFFF.toInt(), false).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(30), 0, dp(30))
                }
            )
            return
        }

        apps.chunked(4).forEach { rowApps ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
            }
            rowApps.forEach { app ->
                row.addView(
                    createAppTile(app, showLabel = true),
                    LinearLayout.LayoutParams(0, dp(102), 1f)
                )
            }
            repeat((4 - rowApps.size).coerceAtLeast(0)) {
                row.addView(
                    View(this),
                    LinearLayout.LayoutParams(0, dp(102), 1f)
                )
            }
            container.addView(row)
        }
    }

    private fun closeAppDrawer() {
        val overlay = drawerOverlay ?: return
        overlay.animate()
            .alpha(0f)
            .translationY(dp(35).toFloat())
            .setDuration(150L)
            .withEndAction { closeAppDrawerImmediately() }
            .start()
    }

    private fun closeAppDrawerImmediately() {
        drawerOverlay?.let { overlay ->
            (overlay.parent as? ViewGroup)?.removeView(overlay)
        }
        drawerOverlay = null
        drawerAppsContainer = null
        drawerSearch = null
    }

    private fun openWebSearch() {
        try {
            val searchIntent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(SearchManager.QUERY, "")
            }
            if (searchIntent.resolveActivity(packageManager) != null) {
                startActivity(searchIntent)
            } else {
                Toast.makeText(this, "No search app is available.", Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
            Toast.makeText(this, "Search is unavailable on this device.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateClock() {
        if (!::clockLabel.isInitialized || !::dateLabel.isInitialized) return
        val now = Date()
        clockLabel.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        dateLabel.text = SimpleDateFormat(
            "EEEE, d MMMM",
            Locale.getDefault()
        ).format(now)
    }

    private fun text(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean
    ): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        includeFontPadding = false
        typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun glassSurface(
        fill: Int,
        stroke: Int,
        radius: Float
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(radius.toInt()).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (drawerOverlay != null) {
            closeAppDrawer()
            return
        }
        // Home is the launch surface. Back must not close the default launcher.
    }
}
