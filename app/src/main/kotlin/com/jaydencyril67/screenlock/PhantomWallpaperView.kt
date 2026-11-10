package com.jaydencyril67.screenlock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.view.View
import kotlin.math.max
import kotlin.math.min

object PhantomWallpaperStore {
    const val PREF_KEY = "phantom_lock_wallpaper_uri"

    fun savedUri(context: Context): Uri? {
        val value = context.getSharedPreferences(
            PhantomOverlayService.PREFS,
            Context.MODE_PRIVATE
        ).getString(PREF_KEY, null) ?: return null

        return runCatching { Uri.parse(value) }.getOrNull()
    }
}

/**
 * Full-screen wallpaper surface. A chosen image is read through the persisted
 * document URI permission; no broad storage permission is required.
 */
class PhantomWallpaperView(
    context: Context,
    private val homeStyle: Boolean = false
) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val preferences = context.getSharedPreferences(
        PhantomOverlayService.PREFS,
        Context.MODE_PRIVATE
    )

    private var wallpaper: Bitmap? = null
    private var loadGeneration = 0

    override fun onSizeChanged(
        width: Int,
        height: Int,
        oldWidth: Int,
        oldHeight: Int
    ) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width > 0 && height > 0) loadSelectedWallpaper(width, height)
    }

    private fun loadSelectedWallpaper(targetWidth: Int, targetHeight: Int) {
        val generation = ++loadGeneration
        val saved = preferences.getString(PhantomWallpaperStore.PREF_KEY, null)

        if (saved.isNullOrBlank()) {
            wallpaper?.recycle()
            wallpaper = null
            invalidate()
            return
        }

        val uri = runCatching { Uri.parse(saved) }.getOrNull()
        if (uri == null) {
            wallpaper?.recycle()
            wallpaper = null
            invalidate()
            return
        }

        Thread({
            val decoded = decodeWallpaper(uri, targetWidth, targetHeight)
            post {
                if (generation != loadGeneration) {
                    decoded?.recycle()
                    return@post
                }
                wallpaper?.recycle()
                wallpaper = decoded
                invalidate()
            }
        }, "PhantomWallpaperLoader").start()
    }

    private fun decodeWallpaper(
        uri: Uri,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }

            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val widthRatio = bounds.outWidth.toFloat() / targetWidth.coerceAtLeast(1)
            val heightRatio = bounds.outHeight.toFloat() / targetHeight.coerceAtLeast(1)
            val safeRatio = min(widthRatio, heightRatio)

            var sampleSize = 1
            while (sampleSize <= safeRatio / 2f) {
                sampleSize *= 2
            }

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val image = wallpaper
        if (image != null && !image.isRecycled) {
            val scale = max(w / image.width, h / image.height)
            val drawWidth = image.width * scale
            val drawHeight = image.height * scale
            val destination = RectF(
                (w - drawWidth) / 2f,
                (h - drawHeight) / 2f,
                (w + drawWidth) / 2f,
                (h + drawHeight) / 2f
            )
            canvas.drawBitmap(image, null, destination, bitmapPaint)

            // A restrained dark veil keeps the clock and lock controls legible.
            paint.shader = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(
                    0x36000000,
                    0x18000000,
                    0x40000000,
                    0xA8000000.toInt()
                ),
                floatArrayOf(0f, 0.34f, 0.72f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null
            return
        }

        if (homeStyle) {
            drawDefaultHomeWallpaper(canvas, w, h)
        } else {
            drawDefaultWallpaper(canvas, w, h)
        }
    }

    private fun drawDefaultHomeWallpaper(canvas: Canvas, w: Float, h: Float) {
        canvas.drawColor(Color.rgb(82, 69, 151))

        paint.shader = LinearGradient(
            0f, 0f, w * 0.22f, h,
            intArrayOf(
                Color.rgb(160, 139, 224),
                Color.rgb(112, 111, 205),
                Color.rgb(78, 131, 204),
                Color.rgb(39, 135, 168)
            ),
            floatArrayOf(0f, 0.39f, 0.72f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        paint.shader = RadialGradient(
            w * 0.82f,
            h * 0.18f,
            max(w, h) * 0.72f,
            intArrayOf(
                0x66F4C7FF,
                0x24B7C8FF,
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        paint.shader = RadialGradient(
            w * 0.12f,
            h * 0.78f,
            max(w, h) * 0.58f,
            intArrayOf(
                0x3359E2D5,
                0x123F93C4,
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        paint.shader = LinearGradient(
            0f, h * 0.56f, 0f, h,
            intArrayOf(
                Color.TRANSPARENT,
                0x14090E27,
                0x46090E27
            ),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, h * 0.56f, w, h, paint)
        paint.shader = null
    }

    private fun drawDefaultWallpaper(canvas: Canvas, w: Float, h: Float) {
        canvas.drawColor(Color.rgb(5, 7, 12))

        paint.shader = LinearGradient(
            0f, 0f, w * 0.72f, h,
            intArrayOf(
                Color.rgb(21, 25, 34),
                Color.rgb(18, 17, 23),
                Color.rgb(7, 9, 14)
            ),
            floatArrayOf(0f, 0.56f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        paint.shader = RadialGradient(
            w * 0.78f,
            h * 0.25f,
            max(w, h) * 0.76f,
            intArrayOf(
                0x445B6172,
                0x1D343847,
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        paint.shader = LinearGradient(
            0f, h * 0.58f, 0f, h,
            intArrayOf(
                Color.TRANSPARENT,
                0x44030408,
                0xD9030408.toInt()
            ),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, h * 0.58f, w, h, paint)
        paint.shader = null
    }
}
