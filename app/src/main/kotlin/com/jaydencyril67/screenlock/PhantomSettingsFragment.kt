package com.jaydencyril67.screenlock

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import dev.oneuiproject.oneui.preference.InsetPreferenceCategory
import androidx.preference.SeslSwitchPreferenceScreen

/**
 * Main Phantom settings surface.
 *
 * This screen is built with SESL/One UI preferences so permissions, protection,
 * wallpaper and Home configuration share the same native One UI interaction
 * patterns instead of maintaining a separate collection of custom buttons.
 */
class PhantomSettingsFragment : PreferenceFragmentCompat() {

    private val lockPrefs by lazy {
        requireContext().getSharedPreferences(PhantomOverlayService.PREFS, android.content.Context.MODE_PRIVATE)
    }

    private lateinit var statusPreference: Preference
    private lateinit var protectionPreference: SeslSwitchPreferenceScreen
    private lateinit var homePreference: Preference
    private lateinit var overlayPreference: Preference
    private lateinit var cameraPreference: Preference
    private lateinit var wallpaperPreference: Preference
    private lateinit var restoreWallpaperPreference: Preference

    private val wallpaperPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) saveWallpaper(uri)
        }

    private val cameraPermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            refreshPreferences()
            Toast.makeText(
                requireContext(),
                if (granted) {
                    "Camera access enabled. Phantom camera and flashlight shortcuts are ready."
                } else {
                    "Camera access was not enabled. You can allow it later in Android app permissions."
                },
                Toast.LENGTH_LONG
            ).show()
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()
        val screen = preferenceManager.createPreferenceScreen(context)

        val protectionCategory = PreferenceCategory(context).apply {
            title = "Protection"
        }
        screen.addPreference(protectionCategory)

        statusPreference = Preference(context).apply {
            title = "Protection status"
            summary = "Checking Phantom protection…"
            isSelectable = false
        }
        protectionCategory.addPreference(statusPreference)

        protectionPreference = SeslSwitchPreferenceScreen(context).apply {
            key = "phantom_protection_enabled"
            title = "Phantom Lock"
            summaryOn = "Protection is enabled. Phantom will show its lockscreen when the display wakes."
            summaryOff = "Turn on Phantom protection for this device."
            isPersistent = false
            setOnPreferenceChangeListener { _, newValue ->
                setProtectionEnabled(newValue as? Boolean == true)
            }
        }
        protectionCategory.addPreference(protectionPreference)

        screen.addPreference(InsetPreferenceCategory(context))

        val permissionsCategory = PreferenceCategory(context).apply {
            title = "Permissions"
        }
        screen.addPreference(permissionsCategory)

        overlayPreference = Preference(context).apply {
            title = "Display over other apps"
            summary = "Required for Phantom's lockscreen to appear above other apps."
            setOnPreferenceClickListener {
                requestOverlayPermission()
                true
            }
        }
        permissionsCategory.addPreference(overlayPreference)

        cameraPreference = Preference(context).apply {
            title = "Camera access"
            summary = "Optional. Used by the lockscreen camera and flashlight shortcuts."
            setOnPreferenceClickListener {
                if (requireContext().checkSelfPermission(android.Manifest.permission.CAMERA) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    Toast.makeText(requireContext(), "Camera access is already enabled.", Toast.LENGTH_SHORT).show()
                } else {
                    cameraPermissionRequest.launch(android.Manifest.permission.CAMERA)
                }
                true
            }
        }
        permissionsCategory.addPreference(cameraPreference)

        screen.addPreference(InsetPreferenceCategory(context))

        val homeCategory = PreferenceCategory(context).apply {
            title = "Home screen"
        }
        screen.addPreference(homeCategory)

        homePreference = Preference(context).apply {
            title = "Default Home app"
            summary = "Choose Phantom as your Android Home app to use the imported One UI launcher foundation."
            setOnPreferenceClickListener {
                openHomeSettings()
                true
            }
        }
        homeCategory.addPreference(homePreference)

        screen.addPreference(InsetPreferenceCategory(context))

        val personalizationCategory = PreferenceCategory(context).apply {
            title = "Lockscreen personalization"
        }
        screen.addPreference(personalizationCategory)

        wallpaperPreference = Preference(context).apply {
            title = "Lockscreen wallpaper"
            summary = "Choose a photo stored on this device."
            setOnPreferenceClickListener {
                wallpaperPicker.launch(arrayOf("image/*"))
                true
            }
        }
        personalizationCategory.addPreference(wallpaperPreference)

        restoreWallpaperPreference = Preference(context).apply {
            title = "Restore default wallpaper"
            summary = "Use Phantom's built-in lockscreen background again."
            setOnPreferenceClickListener {
                restoreDefaultWallpaper()
                true
            }
        }
        personalizationCategory.addPreference(restoreWallpaperPreference)

        screen.addPreference(InsetPreferenceCategory(context))

        val aboutCategory = PreferenceCategory(context).apply {
            title = "About Phantom"
        }
        screen.addPreference(aboutCategory)

        aboutCategory.addPreference(Preference(context).apply {
            title = "How Phantom works"
            summary = "Phantom protects the display with its own service-managed lockscreen. The Home screen, app drawer, folders, settings and other app surfaces use the One UI design system. Local music controls are available from the lockscreen."
            isSelectable = false
        })

        preferenceScreen = screen
        refreshPreferences()
    }

    override fun onResume() {
        super.onResume()
        refreshPreferences()
    }

    private fun refreshPreferences() {
        if (!isAdded || !::statusPreference.isInitialized) return

        val context = requireContext()
        val overlayGranted = Settings.canDrawOverlays(context)
        val cameraGranted = context.checkSelfPermission(android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val enabled = lockPrefs.getBoolean(PhantomOverlayService.PREF_ENABLED, false)

        when {
            !overlayGranted -> {
                statusPreference.title = "Permission required"
                statusPreference.summary = "Allow display over other apps before enabling Phantom protection."
            }
            enabled -> {
                statusPreference.title = "Protection is on"
                statusPreference.summary = "Phantom is running and will protect the next screen wake."
            }
            else -> {
                statusPreference.title = "Ready to activate"
                statusPreference.summary = "Grant the required permission, then switch on Phantom Lock."
            }
        }

        protectionPreference.isChecked = enabled
        overlayPreference.summary = if (overlayGranted) {
            "Allowed. Phantom can display its lockscreen above other apps."
        } else {
            "Not allowed. Required for Phantom's lockscreen to appear above other apps."
        }
        cameraPreference.summary = if (cameraGranted) {
            "Allowed. Camera and flashlight shortcuts are ready."
        } else {
            "Optional. Allow access to use the lockscreen camera and flashlight shortcuts."
        }

        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = context.packageManager.resolveActivity(
            homeIntent,
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo
        val isPhantomDefault = resolved?.packageName == context.packageName &&
            resolved.name.endsWith("OneUiLauncherActivity")
        homePreference.summary = if (isPhantomDefault) {
            "Phantom One UI Home is currently your default Home app."
        } else {
            "Samsung One UI Home remains installed. Select Phantom in Android's Home app settings to use this launcher."
        }

        val selectedWallpaper = lockPrefs.getString(PhantomWallpaperStore.PREF_KEY, null)
        wallpaperPreference.summary = if (selectedWallpaper.isNullOrBlank()) {
            "Using Phantom's built-in dark wallpaper. Choose a photo stored on this device to personalize the lockscreen."
        } else {
            "A custom lockscreen wallpaper is saved on this device."
        }
        restoreWallpaperPreference.isVisible = !selectedWallpaper.isNullOrBlank()
    }

    private fun setProtectionEnabled(enabled: Boolean): Boolean {
        val context = requireContext()

        if (!enabled) {
            lockPrefs.edit()
                .putBoolean(PhantomOverlayService.PREF_ENABLED, false)
                .commit()
            runCatching {
                context.startService(
                    Intent(context, PhantomOverlayService::class.java)
                        .setAction(PhantomOverlayService.ACTION_STOP)
                )
            }
            Toast.makeText(context, "Phantom Lock turned off.", Toast.LENGTH_SHORT).show()
            refreshPreferences()
            return true
        }

        if (!Settings.canDrawOverlays(context)) {
            requestOverlayPermission()
            return false
        }

        lockPrefs.edit()
            .putBoolean(PhantomOverlayService.PREF_ENABLED, true)
            .commit()

        val serviceIntent = Intent(context, PhantomOverlayService::class.java)
            .setAction(PhantomOverlayService.ACTION_START)

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Toast.makeText(
                context,
                "Phantom Lock activated. Turn the screen off and on to test it.",
                Toast.LENGTH_LONG
            ).show()
            refreshPreferences()
            true
        } catch (_: Exception) {
            lockPrefs.edit()
                .putBoolean(PhantomOverlayService.PREF_ENABLED, false)
                .commit()
            Toast.makeText(
                context,
                "Phantom Lock could not start. Please try again.",
                Toast.LENGTH_LONG
            ).show()
            refreshPreferences()
            false
        }
    }

    private fun requestOverlayPermission() {
        val context = requireContext()
        val packageIntent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        try {
            startActivity(packageIntent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            } catch (_: Exception) {
                Toast.makeText(
                    context,
                    "Open Android Settings > Apps > Special app access > Display over other apps.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun openHomeSettings() {
        try {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (_: Exception) {
            Toast.makeText(
                requireContext(),
                "Open Settings > Apps > Default apps > Home app, then choose Phantom.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun saveWallpaper(uri: Uri) {
        val context = requireContext()
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            Toast.makeText(
                context,
                "Android could not keep access to that image. Please choose it again.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val previousUri = PhantomWallpaperStore.savedUri(context)
        if (previousUri != uri) releaseWallpaperPermission(previousUri)

        lockPrefs.edit()
            .putString(PhantomWallpaperStore.PREF_KEY, uri.toString())
            .apply()
        refreshPreferences()
        Toast.makeText(context, "Lockscreen wallpaper saved.", Toast.LENGTH_SHORT).show()
    }

    private fun restoreDefaultWallpaper() {
        val context = requireContext()
        releaseWallpaperPermission(PhantomWallpaperStore.savedUri(context))
        lockPrefs.edit()
            .remove(PhantomWallpaperStore.PREF_KEY)
            .apply()
        refreshPreferences()
        Toast.makeText(context, "Default Phantom wallpaper restored.", Toast.LENGTH_SHORT).show()
    }

    private fun releaseWallpaperPermission(uri: Uri?) {
        if (uri == null) return
        try {
            requireContext().contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // The document provider may already have revoked the stored permission.
        }
    }
}
