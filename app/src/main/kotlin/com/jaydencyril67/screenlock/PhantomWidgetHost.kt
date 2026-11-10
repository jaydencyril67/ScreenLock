package com.jaydencyril67.screenlock

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Owns the Android AppWidgetHost lifecycle for Phantom Home.
 *
 * Widget IDs are persisted independently from the Home Activity so widgets
 * survive launcher restarts and device reboots.
 */
class PhantomWidgetHost(
    private val activity: Activity,
    private val container: LinearLayout
) {
    companion object {
        private const val HOST_ID = 0x50484E54
        private const val REQUEST_PICK_WIDGET = 7401
        private const val REQUEST_CONFIGURE_WIDGET = 7402
        private const val INVALID_WIDGET_ID = AppWidgetManager.INVALID_APPWIDGET_ID
        private const val PREFS = "phantom_launcher_widgets"
        private const val KEY_WIDGET_IDS = "widget_ids"
        const val MAX_WIDGETS = 4
    }

    private val manager = AppWidgetManager.getInstance(activity)
    private val host = AppWidgetHost(activity, HOST_ID)
    private val preferences = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
    private var pendingWidgetId = INVALID_WIDGET_ID
    private var listening = false

    fun startListening() {
        if (listening) return
        try {
            host.startListening()
            listening = true
            render()
        } catch (_: Exception) {
            // A widget provider can fail independently; Home remains usable.
        }
    }

    fun stopListening() {
        if (!listening) return
        try {
            host.stopListening()
        } catch (_: Exception) {
            // The system may already have detached this host.
        }
        listening = false
    }

    fun addWidget() {
        val ids = savedWidgetIds()
        if (ids.size >= MAX_WIDGETS) {
            android.widget.Toast.makeText(
                activity,
                "Phantom supports up to $MAX_WIDGETS home widgets for now.",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }

        val widgetId = host.allocateAppWidgetId()
        pendingWidgetId = widgetId
        val picker = Intent(AppWidgetManager.ACTION_APPWIDGET_PICK).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        }

        try {
            activity.startActivityForResult(picker, REQUEST_PICK_WIDGET)
        } catch (_: Exception) {
            discardPendingWidget()
            android.widget.Toast.makeText(
                activity,
                "Android's widget picker is unavailable on this device.",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Returns true when the result belongs to the widget picker/configuration flow.
     */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        when (requestCode) {
            REQUEST_PICK_WIDGET -> {
                val widgetId = data?.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    pendingWidgetId
                ) ?: pendingWidgetId

                if (resultCode != Activity.RESULT_OK || widgetId == INVALID_WIDGET_ID) {
                    discardPendingWidget()
                    return true
                }

                pendingWidgetId = widgetId
                val info = manager.getAppWidgetInfo(widgetId)
                if (info == null) {
                    discardPendingWidget()
                    return true
                }

                if (info.configure != null) {
                    launchConfiguration(widgetId, info)
                } else {
                    commitWidget(widgetId)
                }
                return true
            }

            REQUEST_CONFIGURE_WIDGET -> {
                val widgetId = pendingWidgetId
                if (resultCode == Activity.RESULT_OK && widgetId != INVALID_WIDGET_ID) {
                    commitWidget(widgetId)
                } else {
                    discardPendingWidget()
                }
                return true
            }
        }
        return false
    }

    fun render() {
        container.removeAllViews()
        val ids = savedWidgetIds()
        if (ids.isEmpty()) {
            container.addView(createEmptyState())
            return
        }

        val validIds = mutableListOf<Int>()
        ids.forEach { widgetId ->
            val info = manager.getAppWidgetInfo(widgetId)
            if (info == null) {
                runCatching { host.deleteAppWidgetId(widgetId) }
                return@forEach
            }

            try {
                val widgetView = host.createView(activity, widgetId, info)
                widgetView.setAppWidget(widgetId, info)
                widgetView.minimumHeight = dp(info.minHeight.coerceAtLeast(112))
                widgetView.setBackground(
                    roundedBackground(0x20FFFFFF, 0x28FFFFFF, 24)
                )
                widgetView.setOnLongClickListener {
                    confirmRemove(widgetId, info)
                    true
                }
                container.addView(
                    widgetView,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = dp(12)
                    }
                )
                validIds.add(widgetId)
            } catch (_: Exception) {
                // A broken or removed provider must not break the launcher.
            }
        }

        if (validIds.size != ids.size) saveWidgetIds(validIds)
        if (validIds.isEmpty()) container.addView(createEmptyState())
    }

    private fun launchConfiguration(widgetId: Int, info: AppWidgetProviderInfo) {
        val configureComponent: ComponentName = info.configure
        val configureIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
            component = configureComponent
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        }
        try {
            activity.startActivityForResult(
                configureIntent,
                REQUEST_CONFIGURE_WIDGET
            )
        } catch (_: Exception) {
            discardPendingWidget()
            android.widget.Toast.makeText(
                activity,
                "This widget could not open its setup screen.",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun commitWidget(widgetId: Int) {
        val ids = savedWidgetIds().toMutableList()
        if (widgetId !in ids && ids.size < MAX_WIDGETS) ids.add(widgetId)
        saveWidgetIds(ids)
        pendingWidgetId = INVALID_WIDGET_ID
        render()
    }

    private fun discardPendingWidget() {
        if (pendingWidgetId != INVALID_WIDGET_ID) {
            runCatching { host.deleteAppWidgetId(pendingWidgetId) }
        }
        pendingWidgetId = INVALID_WIDGET_ID
        render()
    }

    private fun confirmRemove(widgetId: Int, info: AppWidgetProviderInfo) {
        AlertDialog.Builder(activity)
            .setTitle("Remove widget?")
            .setMessage("Remove the ${info.loadLabel(activity.packageManager)} widget from Phantom Home?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ ->
                val ids = savedWidgetIds().filterNot { it == widgetId }
                saveWidgetIds(ids)
                runCatching { host.deleteAppWidgetId(widgetId) }
                render()
            }
            .show()
    }

    private fun createEmptyState(): View {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(15), dp(8))
            background = roundedBackground(0x28FFFFFF, 0x38FFFFFF, 22)
            minimumHeight = dp(54)

            addView(TextView(activity).apply {
                text = "+"
                textSize = 24f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                includeFontPadding = false
            }, LinearLayout.LayoutParams(dp(31), dp(36)))

            addView(TextView(activity).apply {
                text = "Add widgets"
                textSize = 13f
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(Color.WHITE)
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setPadding(dp(8), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, dp(36), 1f))

            addView(TextView(activity).apply {
                text = "›"
                textSize = 25f
                gravity = Gravity.CENTER
                setTextColor(0xCCFFFFFF.toInt())
                includeFontPadding = false
            }, LinearLayout.LayoutParams(dp(22), dp(36)))
        }.apply {
            isClickable = true
            isFocusable = true
            contentDescription = "Add a home screen widget"
            setOnClickListener { addWidget() }
        }
    }
    private fun savedWidgetIds(): List<Int> =
        preferences.getStringSet(KEY_WIDGET_IDS, emptySet())
            .orEmpty()
            .mapNotNull { it.toIntOrNull() }
            .distinct()
            .take(MAX_WIDGETS)

    private fun saveWidgetIds(ids: List<Int>) {
        preferences.edit()
            .putStringSet(KEY_WIDGET_IDS, ids.map { it.toString() }.toSet())
            .apply()
    }

    private fun roundedBackground(
        fill: Int,
        stroke: Int,
        radiusDp: Int
    ) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
