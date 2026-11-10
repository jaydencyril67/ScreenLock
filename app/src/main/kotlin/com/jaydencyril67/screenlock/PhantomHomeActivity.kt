package com.jaydencyril67.screenlock

import android.app.Activity
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.GestureDetector
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
import java.text.Collator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Phantom's default Home surface.
 *
 * The launcher borrows the visual language of Samsung One UI: generous spacing,
 * a four-column home grid, a restrained dock, and a horizontally paged app
 * drawer. It remains an independent launcher and does not own Phantom's lock.
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
    private lateinit var homeGestureDetector: GestureDetector
    private lateinit var widgetContainer: LinearLayout
    private lateinit var widgetHost: PhantomWidgetHost

    private var launchableApps: List<LauncherApp> = emptyList()
    private var drawerOverlay: FrameLayout? = null
    private var drawerPageFlipper: ViewFlipper? = null
    private var drawerPageDots: LinearLayout? = null
    private var drawerSearch: EditText? = null
    private var drawerGestureDetector: GestureDetector? = null
    private var drawerPageCount = 0
    private var drawerCurrentPage = 0

    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.rgb(7, 9, 15)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN

        launchableApps = queryLaunchableApps()
        homeGestureDetector = GestureDetector(
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
                    val dx = second.x - start.x
                    val dy = second.y - start.y
                    if (abs(dy) <= abs(dx)) return false

                    if (dy < -dp(65) && drawerOverlay == null) {
                        openAppDrawer()
                        return true
                    }
                    if (dy > dp(65) && drawerOverlay != null) {
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

        // PhantomOverlayService independently owns the service-managed lock surface.
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
        drawerGestureDetector?.onTouchEvent(event)
        homeGestureDetector.onTouchEvent(event)
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

        root.addView(
            View(this).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0x3005070D, 0x0805070D, 0x5705070D)
                )
            },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(45), dp(20), dp(14))
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
        clockLabel = text("9:41", 47f, Color.WHITE, false).apply {
            includeFontPadding = false
            letterSpacing = -0.045f
            setShadowLayer(dp(8).toFloat(), 0f, dp(2).toFloat(), 0x44000000)
        }
        dateLabel = text("", 13f, 0xEFFFFFFF.toInt(), false).apply {
            setPadding(dp(2), dp(3), 0, 0)
            letterSpacing = 0.005f
            setShadowLayer(dp(4).toFloat(), 0f, dp(1).toFloat(), 0x55000000)
        }
        clockBlock.addView(clockLabel)
        clockBlock.addView(dateLabel)
        header.addView(
            clockBlock,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        val settingsButton = text("⚙", 23f, Color.WHITE, false).apply {
            gravity = Gravity.CENTER
            background = circleSurface(0x38FFFFFF, 0x55FFFFFF)
            contentDescription = "Open Phantom settings"
            isClickable = true
            isFocusable = true
            setOnClickListener {
                startActivity(Intent(this@PhantomHomeActivity, MainActivity::class.java))
            }
        }
        header.addView(settingsButton, LinearLayout.LayoutParams(dp(45), dp(45)))
        content.addView(header)

        widgetContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(
            widgetContainer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(13) }
        )
        widgetHost = PhantomWidgetHost(this, widgetContainer)

        val pinnedApps = selectPinnedApps()
        val homeApps = selectHomeApps(pinnedApps).take(12)
        addAppRows(content, homeApps, 4)

        val searchBar = createHomeSearchBar()
        content.addView(
            searchBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(49)
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(12)
            }
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

        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(5), dp(4), dp(5), dp(4))
            background = glassSurface(0x32FFFFFF, 0x36FFFFFF, 27f)
        }
        pinnedApps.take(4).forEach { app ->
            dock.addView(
                createAppTile(app, showLabel = false, iconSize = 51),
                LinearLayout.LayoutParams(0, dp(62), 1f)
            )
        }
        content.addView(
            dock,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(70)
            )
        )

        setContentView(root)
        // The launcher uses Android's system sans family, matching the host
        // Samsung font. The rest of Phantom continues using its bundled font.
    }

    private fun createHomeSearchBar(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(15), 0, dp(15), 0)
            background = glassSurface(0xF3F4F6F8.toInt(), 0xCFFFFFFF.toInt(), 25f)
            isClickable = true
            isFocusable = true
            contentDescription = "Search apps"
            setOnClickListener { openAppDrawer(focusSearch = true) }

            addView(
                text("⌕", 25f, 0xFF454954.toInt(), false).apply {
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(dp(30), dp(48))
            )
            addView(
                text("Search", 14f, 0xFF555A65.toInt(), false).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(7), 0, 0, 0)
                },
                LinearLayout.LayoutParams(0, dp(48), 1f)
            )
            addView(
                text("●", 12f, 0xFF717784.toInt(), false).apply {
                    gravity = Gravity.CENTER
                    contentDescription = "Voice search"
                },
                LinearLayout.LayoutParams(dp(26), dp(48))
            )
        }
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
                    createAppTile(app, showLabel = true, iconSize = 53),
                    LinearLayout.LayoutParams(0, dp(91), 1f)
                )
            }
            repeat((columns - rowApps.size).coerceAtLeast(0)) {
                row.addView(View(this), LinearLayout.LayoutParams(0, dp(91), 1f))
            }
            parent.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(91)
                )
            )
        }
    }

    private fun createAppTile(
        app: LauncherApp,
        showLabel: Boolean,
        iconSize: Int
    ): View {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = app.label
            setOnClickListener { launchApp(app) }
        }

        val icon = ImageView(this).apply {
            // Use the real installed app icon. On Samsung devices this is the
            // OEM-provided icon resource, not a hand-drawn substitute.
            val original = app.icon.constantState?.newDrawable(resources)?.mutate() ?: app.icon
            setImageDrawable(original)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = app.label
        }
        tile.addView(
            icon,
            LinearLayout.LayoutParams(dp(iconSize), dp(iconSize)).apply {
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
                    setShadowLayer(dp(4).toFloat(), 0f, dp(1).toFloat(), 0x66000000)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(22)
                )
            )
        }
        return tile
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

    private fun selectHomeApps(pinnedApps: List<LauncherApp>): List<LauncherApp> {
        val preferredPackages = listOf(
            "com.samsung.android.calendar",
            "com.google.android.calendar",
            "com.sec.android.gallery3d",
            "com.google.android.apps.photos",
            "com.sec.android.app.myfiles",
            "com.google.android.documentsui",
            "com.android.documentsui",
            "com.samsung.android.app.notes",
            "com.google.android.keep",
            "com.android.settings",
            "com.google.android.apps.maps",
            "com.android.vending",
            "com.google.android.youtube",
            "com.whatsapp",
            "com.instagram.android"
        )
        val selected = preferredPackages.mapNotNull { packageName ->
            launchableApps.firstOrNull { it.packageName == packageName }
        }.filterNot { app -> pinnedApps.any { it.packageName == app.packageName } }
            .distinctBy { it.packageName }
            .toMutableList()

        launchableApps.forEach { app ->
            if (selected.size < 12 &&
                selected.none { it.packageName == app.packageName } &&
                pinnedApps.none { it.packageName == app.packageName }
            ) {
                selected.add(app)
            }
        }
        return selected.take(12)
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
            setBackgroundColor(0xD9090C14.toInt())
            isClickable = true
            isFocusable = true
        }

        val drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(56), dp(16), dp(17))
        }

        val pageFlipper = ViewFlipper(this).apply {
            setAnimateFirstView(false)
            inAnimation = android.view.animation.AlphaAnimation(0.78f, 1f).apply {
                duration = 140L
            }
            outAnimation = android.view.animation.AlphaAnimation(1f, 0.72f).apply {
                duration = 110L
            }
        }
        drawer.addView(
            pageFlipper,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        val dots = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        drawer.addView(
            dots,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(19)
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(11)
            }
        )

        val searchBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(15), 0, dp(14), 0)
            background = glassSurface(0xFFF1F2F5.toInt(), 0xFFFFFFFF.toInt(), 25f)
        }
        searchBar.addView(
            text("⌕", 25f, 0xFF555B67.toInt(), false).apply {
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(dp(30), dp(49))
        )

        val search = EditText(this).apply {
            hint = "Search"
            setHintTextColor(0xFF747A86.toInt())
            setTextColor(0xFF20232B.toInt())
            textSize = 14f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setSingleLine(true)
            background = null
            setPadding(dp(7), 0, dp(7), 0)
            includeFontPadding = false
        }
        searchBar.addView(
            search,
            LinearLayout.LayoutParams(0, dp(49), 1f)
        )
        searchBar.addView(
            text("×", 23f, 0xFF6C727E.toInt(), false).apply {
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { search.text.clear() }
            },
            LinearLayout.LayoutParams(dp(28), dp(49))
        )
        drawer.addView(
            searchBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(49)
            )
        )

        overlay.addView(
            drawer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
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
        drawerPageFlipper = pageFlipper
        drawerPageDots = dots
        drawerSearch = search

        drawerGestureDetector = GestureDetector(
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
                    val dx = second.x - start.x
                    val dy = second.y - start.y
                    if (abs(dx) < dp(48) || abs(dx) < abs(dy)) return false

                    if (dx < 0f) {
                        showDrawerPage(drawerCurrentPage + 1)
                    } else {
                        showDrawerPage(drawerCurrentPage - 1)
                    }
                    return true
                }
            }
        )

        renderDrawerApps(launchableApps)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val queryText = s?.toString().orEmpty().trim()
                val filtered = if (queryText.isEmpty()) {
                    launchableApps
                } else {
                    launchableApps.filter { it.label.contains(queryText, ignoreCase = true) }
                }
                renderDrawerApps(filtered)
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })

        overlay.alpha = 0f
        overlay.animate().alpha(1f).setDuration(150L).start()

        if (focusSearch) {
            search.requestFocus()
            search.post {
                val inputMethod = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                inputMethod.showSoftInput(search, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun renderDrawerApps(apps: List<LauncherApp>) {
        val flipper = drawerPageFlipper ?: return
        flipper.removeAllViews()
        drawerCurrentPage = 0
        drawerPageCount = if (apps.isEmpty()) 1 else (apps.size + 19) / 20

        if (apps.isEmpty()) {
            val empty = LinearLayout(this).apply {
                gravity = Gravity.CENTER
                addView(text("No matching apps", 14f, 0xEFFFFFFF.toInt(), false))
            }
            flipper.addView(
                empty,
                ViewFlipper.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        } else {
            for (pageIndex in 0 until drawerPageCount) {
                val pageApps = apps.drop(pageIndex * 20).take(20)
                flipper.addView(
                    createDrawerPage(pageApps),
                    ViewFlipper.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }
        }

        flipper.setDisplayedChild(0)
        updateDrawerPageDots()
    }

    private fun createDrawerPage(apps: List<LauncherApp>): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(2))
        }

        for (rowIndex in 0 until 5) {
            val rowApps = apps.drop(rowIndex * 4).take(4)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }

            for (columnIndex in 0 until 4) {
                val app = rowApps.getOrNull(columnIndex)
                val tile = if (app != null) {
                    createAppTile(app, showLabel = true, iconSize = 51)
                } else {
                    View(this)
                }
                row.addView(
                    tile,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                )
            }

            page.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
        }
        return page
    }

    private fun showDrawerPage(requestedPage: Int) {
        val flipper = drawerPageFlipper ?: return
        if (drawerPageCount <= 1) return
        val nextPage = requestedPage.coerceIn(0, drawerPageCount - 1)
        if (nextPage == drawerCurrentPage) return

        flipper.setInAnimation(android.view.animation.AlphaAnimation(0.76f, 1f).apply {
            duration = 150L
        })
        flipper.setOutAnimation(android.view.animation.AlphaAnimation(1f, 0.74f).apply {
            duration = 115L
        })
        flipper.setDisplayedChild(nextPage)
        drawerCurrentPage = nextPage
        updateDrawerPageDots()
    }

    private fun updateDrawerPageDots() {
        val dots = drawerPageDots ?: return
        dots.removeAllViews()
        repeat(drawerPageCount.coerceAtLeast(1)) { index ->
            val active = index == drawerCurrentPage
            val dot = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (active) Color.WHITE else 0x77FFFFFF)
                }
            }
            dots.addView(
                dot,
                LinearLayout.LayoutParams(
                    dp(if (active) 15 else 5),
                    dp(5)
                ).apply {
                    leftMargin = dp(4)
                    rightMargin = dp(4)
                }
            )
        }
    }

    private fun closeAppDrawer() {
        val overlay = drawerOverlay ?: return
        overlay.animate()
            .alpha(0f)
            .setDuration(135L)
            .withEndAction { closeAppDrawerImmediately() }
            .start()
    }

    private fun closeAppDrawerImmediately() {
        drawerOverlay?.let { overlay ->
            (overlay.parent as? ViewGroup)?.removeView(overlay)
        }
        drawerOverlay = null
        drawerPageFlipper = null
        drawerPageDots = null
        drawerSearch = null
        drawerGestureDetector = null
        drawerPageCount = 0
        drawerCurrentPage = 0
    }

    private fun updateClock() {
        if (!::clockLabel.isInitialized || !::dateLabel.isInitialized) return
        val now = Date()
        val clockPattern = if (android.text.format.DateFormat.is24HourFormat(this)) "HH:mm" else "h:mm"
        clockLabel.text = SimpleDateFormat(clockPattern, Locale.getDefault()).format(now)
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
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
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

    private fun circleSurface(fill: Int, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
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