package com.jaydencyril67.screenlock

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import androidx.appcompat.util.SeslRoundedCorner
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.GestureDetector
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import dev.oneuiproject.oneui.dialog.GridMenuDialog
import dev.oneuiproject.oneui.widget.RoundedFrameLayout
import dev.oneuiproject.oneui.widget.RoundedLinearLayout
import org.json.JSONArray
import org.json.JSONObject
import java.text.Collator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

/**
 * Phantom's default Home surface.
 *
 * The launcher borrows the visual language of Samsung One UI: generous spacing,
 * a four-column home grid, a restrained dock, and a horizontally paged app
 * drawer. It remains an independent launcher and does not own Phantom's lock.
 */
class PhantomHomeActivity : AppCompatActivity() {

    private companion object {
        const val PREF_HOME_FOLDERS = "phantom_home_folders"
        const val KEY_HOME_FOLDERS = "folders"
        const val ACTION_APP_INFO = 8101
        const val ACTION_CREATE_FOLDER = 8102
        const val ACTION_UNINSTALL_APP = 8103
        const val ACTION_RENAME_FOLDER = 8104
        const val ACTION_REMOVE_FOLDER = 8105
    }

    private data class LauncherApp(
        val packageName: String,
        val activityName: String,
        val label: String,
        val icon: Drawable
    )

    private data class LauncherFolder(
        val id: String,
        val title: String,
        val packageNames: List<String>
    )

    private sealed class HomeItem {
        data class App(val app: LauncherApp) : HomeItem()
        data class Folder(val folder: LauncherFolder, val apps: List<LauncherApp>) : HomeItem()
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var clockLabel: TextView
    private lateinit var dateLabel: TextView
    private lateinit var homeGestureDetector: GestureDetector
    private lateinit var widgetContainer: LinearLayout
    private lateinit var homeAppsContainer: LinearLayout
    private lateinit var widgetHost: PhantomWidgetHost

    private var launchableApps: List<LauncherApp> = emptyList()
    private var drawerOverlay: FrameLayout? = null
    private var drawerPageFlipper: ViewFlipper? = null
    private var drawerPageDots: LinearLayout? = null
    private var drawerGestureDetector: GestureDetector? = null
    private var drawerPageCount = 0
    private var drawerCurrentPage = 0
    private var drawerTouchStartX = 0f
    private var drawerTouchStartY = 0f
    private var drawerHorizontalSwipeIntercepted = false

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
        if (::homeAppsContainer.isInitialized) renderHomeGrid()
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

        if (drawerOverlay != null) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    drawerTouchStartX = event.x
                    drawerTouchStartY = event.y
                    drawerHorizontalSwipeIntercepted = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - drawerTouchStartX
                    val dy = event.y - drawerTouchStartY
                    if (!drawerHorizontalSwipeIntercepted &&
                        abs(dx) > dp(18) && abs(dx) > abs(dy)
                    ) {
                        // Stop the app tile underneath from treating a page swipe
                        // as a tap and accidentally launching an application.
                        drawerHorizontalSwipeIntercepted = true
                    }
                    if (drawerHorizontalSwipeIntercepted) return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (drawerHorizontalSwipeIntercepted) {
                        drawerHorizontalSwipeIntercepted = false
                        return true
                    }
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun buildHome() {
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(7, 9, 15))
            isFocusableInTouchMode = true
        }

        root.addView(
            PhantomWallpaperView(this, homeStyle = true),
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

        val settingsButton = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_preferences)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER
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

        homeAppsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(
            homeAppsContainer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        renderHomeGrid()

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

        val dock = RoundedLinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(5), dp(4), dp(5), dp(4))
            roundedCorners = SeslRoundedCorner.ROUNDED_CORNER_ALL
            roundedCornersColor = 0xFF07090F.toInt()
            setBackgroundColor(0x32FFFFFF)
        }
        selectPinnedApps().take(4).forEach { app ->
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
        val surface = RoundedFrameLayout(this).apply {
            roundedCorners = SeslRoundedCorner.ROUNDED_CORNER_ALL
            roundedCornersColor = 0xFF07090F.toInt()
            setBackgroundColor(0xFFF1F2F5.toInt())
            isClickable = true
            isFocusable = true
            contentDescription = "Search apps"
            setOnClickListener { openAppDrawer(focusSearch = true) }
        }

        val search = SearchView(this).apply {
            setIconifiedByDefault(false)
            isIconified = false
            queryHint = "Search apps"
            maxWidth = Int.MAX_VALUE
            setSubmitButtonEnabled(false)
            setBackgroundColor(Color.TRANSPARENT)
            isFocusable = false
            isFocusableInTouchMode = false
            isClickable = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        surface.addView(
            search,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(49)
            )
        )
        return surface
    }

    private fun renderHomeGrid() {
        if (!::homeAppsContainer.isInitialized) return
        homeAppsContainer.removeAllViews()
        val pinnedApps = selectPinnedApps()
        val homeApps = selectHomeApps(pinnedApps).take(12)
        addHomeRows(homeAppsContainer, buildHomeItems(homeApps), 4)
    }

    private fun buildHomeItems(apps: List<LauncherApp>): List<HomeItem> {
        val appsByPackage = apps.associateBy { it.packageName }
        val appPackages = appsByPackage.keys
        val folderEntries = loadHomeFolders().mapNotNull { folder ->
            val members = folder.packageNames.distinct()
                .filter { it in appPackages }
                .mapNotNull { appsByPackage[it] }
            if (members.size < 2) null
            else folder.copy(packageNames = members.map { it.packageName }) to members
        }
        val folderByPackage = folderEntries
            .flatMap { entry -> entry.second.map { it.packageName to entry } }
            .toMap()
        val emittedFolderIds = mutableSetOf<String>()

        return apps.mapNotNull { app ->
            val entry = folderByPackage[app.packageName]
            when {
                entry == null -> HomeItem.App(app)
                emittedFolderIds.add(entry.first.id) -> HomeItem.Folder(entry.first, entry.second)
                else -> null
            }
        }
    }

    private fun addHomeRows(
        parent: LinearLayout,
        items: List<HomeItem>,
        columns: Int
    ) {
        items.chunked(columns).forEach { rowItems ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            rowItems.forEach { item ->
                val tile = when (item) {
                    is HomeItem.App -> createAppTile(
                        item.app,
                        showLabel = true,
                        iconSize = 53,
                        allowFolderCreation = true
                    )
                    is HomeItem.Folder -> createFolderTile(item.folder, item.apps)
                }
                row.addView(tile, LinearLayout.LayoutParams(0, dp(91), 1f))
            }
            repeat((columns - rowItems.size).coerceAtLeast(0)) {
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

    private fun loadHomeFolders(): List<LauncherFolder> {
        val raw = getSharedPreferences(PREF_HOME_FOLDERS, MODE_PRIVATE)
            .getString(KEY_HOME_FOLDERS, "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            val folders = mutableListOf<LauncherFolder>()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val packageArray = item.optJSONArray("packages") ?: JSONArray()
                val packages = mutableListOf<String>()
                for (packageIndex in 0 until packageArray.length()) {
                    val packageName = packageArray.optString(packageIndex)
                    if (packageName.isNotBlank()) packages.add(packageName)
                }
                if (packages.distinct().size >= 2) {
                    folders.add(
                        LauncherFolder(
                            id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                            title = item.optString("title").ifBlank { "Folder" },
                            packageNames = packages.distinct()
                        )
                    )
                }
            }
            folders
        }.getOrDefault(emptyList())
    }

    private fun saveHomeFolders(folders: List<LauncherFolder>) {
        val array = JSONArray()
        folders.forEach { folder ->
            val packageArray = JSONArray()
            folder.packageNames.distinct().forEach { packageArray.put(it) }
            array.put(
                JSONObject()
                    .put("id", folder.id)
                    .put("title", folder.title)
                    .put("packages", packageArray)
            )
        }
        getSharedPreferences(PREF_HOME_FOLDERS, MODE_PRIVATE)
            .edit()
            .putString(KEY_HOME_FOLDERS, array.toString())
            .apply()
    }

    private fun createAppTile(
        app: LauncherApp,
        showLabel: Boolean,
        iconSize: Int,
        allowFolderCreation: Boolean = false
    ): View {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = app.label
            setOnClickListener { launchApp(app) }
            setOnLongClickListener {
                showAppActions(this, app, allowFolderCreation)
                true
            }
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

    private fun createFolderTile(
        folder: LauncherFolder,
        apps: List<LauncherApp>
    ): View {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = folder.title + ", folder"
            setOnClickListener { showFolderContents(this, folder, apps) }
            setOnLongClickListener {
                showFolderActions(this, folder)
                true
            }
        }

        val iconSurface = RoundedLinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(7), dp(7), dp(7), dp(7))
            roundedCorners = SeslRoundedCorner.ROUNDED_CORNER_ALL
            roundedCornersColor = 0xFF07090F.toInt()
            setBackgroundColor(0xA82A3044.toInt())
        }
        apps.take(4).chunked(2).forEach { appRow ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            appRow.forEach { app ->
                val previewIcon = ImageView(this).apply {
                    val drawable = app.icon.constantState?.newDrawable(resources)?.mutate() ?: app.icon
                    setImageDrawable(drawable)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = app.label
                }
                row.addView(
                    previewIcon,
                    LinearLayout.LayoutParams(dp(19), dp(19)).apply {
                        leftMargin = dp(2)
                        rightMargin = dp(2)
                        topMargin = dp(1)
                        bottomMargin = dp(1)
                    }
                )
            }
            if (appRow.size == 1) {
                row.addView(View(this), LinearLayout.LayoutParams(dp(19), dp(19)))
            }
            iconSurface.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        tile.addView(iconSurface, LinearLayout.LayoutParams(dp(53), dp(53)))
        tile.addView(
            text(folder.title, 10.5f, Color.WHITE, false).apply {
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
        return tile
    }

    private fun showFolderContents(anchor: View, folder: LauncherFolder, apps: List<LauncherApp>) {
        val dialog = GridMenuDialog(this)
        val appById = mutableMapOf<Int, LauncherApp>()
        apps.forEach { app ->
            val itemId = app.packageName.hashCode()
            appById[itemId] = app
            dialog.addItem(
                GridMenuDialog.GridItem(
                    itemId = itemId,
                    title = app.label,
                    icon = app.icon
                )
            )
        }
        dialog.setOnItemClickListener { item ->
            appById[item.itemId]?.let { launchApp(it) }
            true
        }
        dialog.show()
        dialog.setAnchor(anchor)
    }

    private fun showFolderActions(anchor: View, folder: LauncherFolder) {
        val dialog = GridMenuDialog(this)
        dialog.addItem(
            GridMenuDialog.GridItem(
                ACTION_RENAME_FOLDER,
                "Rename",
                getDrawable(android.R.drawable.ic_menu_edit)
            )
        )
        dialog.addItem(
            GridMenuDialog.GridItem(
                ACTION_REMOVE_FOLDER,
                "Remove folder",
                getDrawable(android.R.drawable.ic_menu_delete)
            )
        )
        dialog.setOnItemClickListener { item ->
            when (item.itemId) {
                ACTION_RENAME_FOLDER -> renameFolder(folder)
                ACTION_REMOVE_FOLDER -> {
                    saveHomeFolders(loadHomeFolders().filterNot { it.id == folder.id })
                    renderHomeGrid()
                    Toast.makeText(this, "Folder removed. Its apps are back on Home.", Toast.LENGTH_SHORT).show()
                }
            }
            true
        }
        dialog.show()
        dialog.setAnchor(anchor)
    }

    private fun renameFolder(folder: LauncherFolder) {
        val input = EditText(this).apply {
            setText(folder.title)
            setSelection(text.length)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            hint = "Folder name"
        }
        AlertDialog.Builder(this)
            .setTitle("Rename folder")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val title = input.text.toString().trim().ifBlank { "Folder" }
                val updated = loadHomeFolders().map {
                    if (it.id == folder.id) it.copy(title = title) else it
                }
                saveHomeFolders(updated)
                renderHomeGrid()
            }
            .show()
    }

    private fun showCreateFolderDialog(anchorApp: LauncherApp) {
        val existingFolders = loadHomeFolders()
        val candidates = selectHomeApps(selectPinnedApps()).filter { app ->
            existingFolders.none { folder -> app.packageName in folder.packageNames }
        }
        if (candidates.size < 2) {
            Toast.makeText(this, "There are not enough Home apps to create a folder.", Toast.LENGTH_SHORT).show()
            return
        }

        val selectedPackages = mutableSetOf(anchorApp.packageName)
        val labels = candidates.map { it.label }.toTypedArray()
        val checked = BooleanArray(candidates.size) {
            candidates[it].packageName == anchorApp.packageName
        }
        lateinit var dialog: AlertDialog
        dialog = AlertDialog.Builder(this)
            .setTitle("Create folder")
            .setMessage("Choose at least two apps to group together.")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                val packageName = candidates[which].packageName
                if (isChecked) selectedPackages.add(packageName)
                else selectedPackages.remove(packageName)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = selectedPackages.size >= 2
            }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create") { _, _ ->
                if (selectedPackages.size < 2) {
                    Toast.makeText(this, "Select at least two apps.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val nextNumber = existingFolders.size + 1
                val folder = LauncherFolder(
                    id = UUID.randomUUID().toString(),
                    title = "Folder " + nextNumber,
                    packageNames = selectedPackages.toList()
                )
                saveHomeFolders(existingFolders + folder)
                renderHomeGrid()
                Toast.makeText(this, folder.title + " created.", Toast.LENGTH_SHORT).show()
            }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = selectedPackages.size >= 2
        }
        dialog.show()
    }

    private fun showAppActions(anchor: View, app: LauncherApp, allowFolderCreation: Boolean) {
        val dialog = GridMenuDialog(this)
        dialog.addItem(
            GridMenuDialog.GridItem(
                ACTION_APP_INFO,
                "App info",
                getDrawable(android.R.drawable.ic_menu_info_details)
            )
        )
        if (allowFolderCreation) {
            dialog.addItem(
                GridMenuDialog.GridItem(
                    ACTION_CREATE_FOLDER,
                    "Create folder",
                    getDrawable(android.R.drawable.ic_menu_add)
                )
            )
        }

        val isSystemApp = runCatching {
            packageManager.getApplicationInfo(app.packageName, 0).flags and
                android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
        }.getOrDefault(true)
        if (!isSystemApp && app.packageName != packageName) {
            dialog.addItem(
                GridMenuDialog.GridItem(
                    ACTION_UNINSTALL_APP,
                    "Uninstall",
                    getDrawable(android.R.drawable.ic_menu_delete)
                )
            )
        }

        dialog.setOnItemClickListener { item ->
            when (item.itemId) {
                ACTION_APP_INFO -> {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:" + app.packageName)
                        }
                    )
                }
                ACTION_CREATE_FOLDER -> showCreateFolderDialog(app)
                ACTION_UNINSTALL_APP -> {
                    startActivity(
                        Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.packageName))
                    )
                }
            }
            true
        }
        dialog.show()
        dialog.setAnchor(anchor)
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
            setBackgroundColor(0x55090C14)
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

        // SESL AppCompat SearchView receives its One UI styling from PhantomOneUITheme.
        val searchSurface = RoundedFrameLayout(this).apply {
            roundedCorners = SeslRoundedCorner.ROUNDED_CORNER_ALL
            roundedCornersColor = 0xFF07090F.toInt()
            setBackgroundColor(0xFFF1F2F5.toInt())
            clipToOutline = true
        }
        val search = SearchView(this).apply {
            setIconifiedByDefault(false)
            isIconified = false
            queryHint = "Search apps"
            maxWidth = Int.MAX_VALUE
            setSubmitButtonEnabled(false)
            setBackgroundColor(Color.TRANSPARENT)
        }
        search.findViewById<AutoCompleteTextView>(androidx.appcompat.R.id.search_src_text)?.apply {
            setHintTextColor(0xFF747A86.toInt())
            setTextColor(0xFF20232B.toInt())
            textSize = 14f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            includeFontPadding = false
        }
        searchSurface.addView(
            search,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(49)
            )
        )
        drawer.addView(
            searchSurface,
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
        search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false

            override fun onQueryTextChange(newText: String?): Boolean {
                val queryText = newText.orEmpty().trim()
                val filtered = if (queryText.isEmpty()) {
                    launchableApps
                } else {
                    launchableApps.filter { it.label.contains(queryText, ignoreCase = true) }
                }
                renderDrawerApps(filtered)
                return true
            }
        })

        overlay.alpha = 0f
        overlay.animate().alpha(1f).setDuration(150L).start()

        if (focusSearch) {
            val queryField = search.findViewById<AutoCompleteTextView>(androidx.appcompat.R.id.search_src_text)
            search.requestFocus()
            queryField?.requestFocus()
            search.post {
                val inputMethod = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                inputMethod.showSoftInput(
                    queryField ?: search,
                    android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT
                )
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
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        } else {
            for (pageIndex in 0 until drawerPageCount) {
                val pageApps = apps.drop(pageIndex * 20).take(20)
                flipper.addView(
                    createDrawerPage(pageApps),
                    FrameLayout.LayoutParams(
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
    @SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        if (drawerOverlay != null) {
            closeAppDrawer()
            return
        }
        // Home is the launch surface. Back must not close the default launcher.
    }
}