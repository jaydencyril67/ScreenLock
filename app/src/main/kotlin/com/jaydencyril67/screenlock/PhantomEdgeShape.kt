package com.jaydencyril67.screenlock

import android.content.Context
import android.graphics.Path
import android.graphics.RectF

/**
 * User-editable outline for Phantom's edge lighting.
 *
 * Every point is stored as a normalized (0..1) position inside the display
 * bounds, so the same profile scales to the editor preview and the real AOD.
 */
object PhantomEdgeShape {
    private const val PREF_PROFILE = "edge_lighting_custom_outline"
    private const val PREF_THICKNESS = "edge_lighting_line_thickness_dp"
    const val POINT_COUNT = 11

    val pointNames = arrayOf(
        "TOP LEFT",
        "NOTCH LEFT",
        "NOTCH TIP",
        "NOTCH RIGHT",
        "TOP RIGHT",
        "RIGHT TOP",
        "RIGHT BOTTOM",
        "BOTTOM RIGHT",
        "BOTTOM LEFT",
        "LEFT BOTTOM",
        "LEFT TOP"
    )

    private val defaults = floatArrayOf(
        0.09f, 0.018f,
        0.405f, 0.018f,
        0.50f, 0.082f,
        0.595f, 0.018f,
        0.91f, 0.018f,
        0.985f, 0.075f,
        0.985f, 0.925f,
        0.91f, 0.982f,
        0.09f, 0.982f,
        0.015f, 0.925f,
        0.015f, 0.075f
    )

    fun defaultPoints(): FloatArray = defaults.copyOf()

    fun load(context: Context): FloatArray {
        val encoded = context.getSharedPreferences(
            PhantomOverlayService.PREFS,
            Context.MODE_PRIVATE
        ).getString(PREF_PROFILE, null) ?: return defaultPoints()

        val values = encoded.split(',').mapNotNull { it.toFloatOrNull() }
        if (values.size != defaults.size || values.any { !it.isFinite() || it < 0f || it > 1f }) {
            return defaultPoints()
        }
        return values.toFloatArray()
    }

    fun save(context: Context, points: FloatArray) {
        if (points.size != defaults.size || points.any { !it.isFinite() || it < 0f || it > 1f }) {
            return
        }
        val encoded = points.joinToString(",") { it.toString() }
        context.getSharedPreferences(
            PhantomOverlayService.PREFS,
            Context.MODE_PRIVATE
        ).edit().putString(PREF_PROFILE, encoded).apply()
    }

    /**
     * Keep each handle in its own part of the display so dragging cannot make
     * the perimeter cross over itself or turn into an unusable scribble.
     */
    fun loadThickness(context: Context): Float =
        context.getSharedPreferences(
            PhantomOverlayService.PREFS,
            Context.MODE_PRIVATE
        ).getFloat(PREF_THICKNESS, 2f).coerceIn(1f, 5f)

    fun saveThickness(context: Context, value: Float) {
        context.getSharedPreferences(
            PhantomOverlayService.PREFS,
            Context.MODE_PRIVATE
        ).edit().putFloat(PREF_THICKNESS, value.coerceIn(1f, 5f)).apply()
    }

    fun constrainPoint(index: Int, x: Float, y: Float): Pair<Float, Float> {
        val limits = when (index) {
            0 -> floatArrayOf(0.025f, 0.46f, 0.003f, 0.14f)
            1 -> floatArrayOf(0.20f, 0.49f, 0.003f, 0.10f)
            2 -> floatArrayOf(0.40f, 0.60f, 0.018f, 0.22f)
            3 -> floatArrayOf(0.51f, 0.80f, 0.003f, 0.10f)
            4 -> floatArrayOf(0.54f, 0.975f, 0.003f, 0.14f)
            5 -> floatArrayOf(0.87f, 0.997f, 0.05f, 0.48f)
            6 -> floatArrayOf(0.87f, 0.997f, 0.52f, 0.95f)
            7 -> floatArrayOf(0.54f, 0.975f, 0.86f, 0.997f)
            8 -> floatArrayOf(0.025f, 0.46f, 0.86f, 0.997f)
            9 -> floatArrayOf(0.003f, 0.13f, 0.52f, 0.95f)
            else -> floatArrayOf(0.003f, 0.13f, 0.05f, 0.48f)
        }
        return Pair(
            x.coerceIn(limits[0], limits[1]),
            y.coerceIn(limits[2], limits[3])
        )
    }

    fun writePath(points: FloatArray, bounds: RectF, path: Path) {
        if (points.size != defaults.size || bounds.width() <= 0f || bounds.height() <= 0f) {
            path.reset()
            return
        }

        fun px(index: Int) = bounds.left + points[index * 2] * bounds.width()
        fun py(index: Int) = bounds.top + points[index * 2 + 1] * bounds.height()

        path.reset()
        path.moveTo(px(0), py(0))
        path.lineTo(px(1), py(1))

        // Two cubic curves form the waterdrop notch. The centre handle controls
        // its depth; the left and right handles control its width and shoulders.
        val notchWidth = (px(3) - px(1)).coerceAtLeast(1f)
        val notchDepth = (py(2) - minOf(py(1), py(3))).coerceAtLeast(1f)
        path.cubicTo(
            px(1), py(1) + notchDepth * 0.76f,
            px(2) - notchWidth * 0.20f, py(2),
            px(2), py(2)
        )
        path.cubicTo(
            px(2) + notchWidth * 0.20f, py(2),
            px(3), py(3) + notchDepth * 0.76f,
            px(3), py(3)
        )

        path.lineTo(px(4), py(4))
        path.quadTo(px(5), py(4), px(5), py(5))
        path.lineTo(px(6), py(6))
        path.quadTo(px(7), py(6), px(7), py(7))
        path.lineTo(px(8), py(8))
        path.quadTo(px(9), py(8), px(9), py(9))
        path.lineTo(px(10), py(10))
        path.quadTo(px(0), py(10), px(0), py(0))
        path.close()
    }
}
