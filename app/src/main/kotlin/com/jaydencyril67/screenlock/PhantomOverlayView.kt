package com.jaydencyril67.screenlock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/**
 * A full-screen post-keyguard lock layer. The fingerprint artwork is visual only;
 * the triple tap always hands authentication to Android's trusted system prompt.
 */
class PhantomOverlayView(
    context: Context,
    private val onTripleTap: () -> Unit
) : View(context) {

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val light = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val handler = Handler(Looper.getMainLooper())

    private var scannerX = 0f
    private var scannerY = 0f
    private var scannerRadius = 0f
    private var verifying = false
    private var tapCount = 0
    private var lastTapAt = 0L

    private val clockTick = object : Runnable {
        override fun run() {
            invalidate()
            handler.postDelayed(this, 1_000L)
        }
    }

    init {
        isClickable = true
        contentDescription = "Phantom Lock. Triple tap the fingerprint symbol to authenticate."
        handler.post(clockTick)
    }

    fun showVerifying() {
        verifying = true
        invalidate()
    }

    fun showReady() {
        verifying = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        drawAtmosphere(canvas, w, h)
        drawBrand(canvas, w, h)
        drawClock(canvas, w, h)
        drawScanner(canvas, w, h)
        drawFooter(canvas, w, h)
    }

    private fun drawAtmosphere(canvas: Canvas, w: Float, h: Float) {
        paint.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(
                Color.rgb(3, 5, 13),
                Color.rgb(8, 10, 25),
                Color.rgb(5, 5, 14)
            ),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        paint.shader = RadialGradient(
            w * 0.82f, h * 0.56f, w * 0.92f,
            intArrayOf(0x302C55B5, 0x17202162, Color.TRANSPARENT),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        paint.shader = RadialGradient(
            w * 0.12f, h * 0.12f, w * 0.72f,
            intArrayOf(0x251A4E86, 0x1020184F, Color.TRANSPARENT),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        paint.color = 0x1E8FAEFF
        canvas.drawLine(w * 0.12f, h * 0.365f, w * 0.88f, h * 0.365f, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawBrand(canvas: Canvas, w: Float, h: Float) {
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = medium
        paint.textSize = 9f * density
        paint.letterSpacing = 0.28f
        paint.color = 0xFFB9C7E8.toInt()
        canvas.drawText("PHANTOM  /  SECURE SESSION", w / 2f, h * 0.105f, paint)
        paint.letterSpacing = 0f

        paint.color = 0xFF77B9FF.toInt()
        canvas.drawCircle(w / 2f, h * 0.105f + 17f * density, 2.2f * density, paint)
    }

    private fun drawClock(canvas: Canvas, w: Float, h: Float) {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val date = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date())

        paint.textAlign = Paint.Align.CENTER
        paint.typeface = light
        paint.textSize = min(72f * density, w * 0.19f)
        paint.color = Color.WHITE
        paint.setShadowLayer(22f * density, 0f, 0f, 0x554E8DFF)
        canvas.drawText(time, w / 2f, h * 0.285f, paint)
        paint.clearShadowLayer()

        paint.typeface = regular
        paint.textSize = 11f * density
        paint.letterSpacing = 0.09f
        paint.color = 0xFF9CAAC8.toInt()
        canvas.drawText(date.uppercase(Locale.getDefault()), w / 2f, h * 0.285f + 29f * density, paint)
        paint.letterSpacing = 0f
    }

    private fun drawScanner(canvas: Canvas, w: Float, h: Float) {
        scannerX = w / 2f
        scannerY = h * 0.705f
        scannerRadius = min(53f * density, w * 0.145f)

        val cx = scannerX
        val cy = scannerY
        val r = scannerRadius

        paint.shader = RadialGradient(
            cx, cy, r * 2.25f,
            intArrayOf(0x514B83E8, 0x222B5AAB, Color.TRANSPARENT),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r * 2.25f, paint)
        paint.shader = null

        paint.color = 0x182D6AB0
        canvas.drawCircle(cx, cy, r * 1.48f, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.15f * density
        paint.shader = LinearGradient(
            cx - r, cy - r, cx + r, cy + r,
            intArrayOf(0xAA9CBFFF.toInt(), 0x775B9CFF, 0xAA78CFFF.toInt()),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r * 1.28f, paint)
        paint.shader = null
        paint.strokeWidth = 1f * density
        paint.color = 0x557DA6E8
        canvas.drawCircle(cx, cy, r * 1.08f, paint)
        paint.style = Paint.Style.FILL

        if (verifying) {
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = medium
            paint.textSize = 9f * density
            paint.letterSpacing = 0.17f
            paint.color = 0xFFD9E7FF.toInt()
            canvas.drawText("OPENING SYSTEM CHECK", cx, cy + r * 0.13f, paint)
            paint.letterSpacing = 0f
        } else {
            drawFingerprintRidges(canvas, cx, cy, r)
        }

        paint.textAlign = Paint.Align.CENTER
        paint.typeface = medium
        paint.textSize = 10f * density
        paint.letterSpacing = 0.17f
        paint.color = if (verifying) 0xFFB5C9ED.toInt() else 0xFFE8EEFF.toInt()
        canvas.drawText(
            if (verifying) "VERIFYING IDENTITY" else "TRIPLE TAP TO UNLOCK",
            w / 2f,
            cy + r * 1.78f,
            paint
        )
        paint.letterSpacing = 0f

        paint.typeface = regular
        paint.textSize = 10f * density
        paint.color = 0xFF8491AE.toInt()
        canvas.drawText(
            if (verifying) "Android secure authentication" else "Protected by your Android screen lock",
            w / 2f,
            cy + r * 1.78f + 22f * density,
            paint
        )
    }

    private fun drawFingerprintRidges(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val ridge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.05f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            shader = LinearGradient(
                cx - r, cy - r, cx + r, cy + r,
                intArrayOf(0xFFF2F6FF.toInt(), 0xFFB5CEFF.toInt(), 0xFF72BFFF.toInt()),
                null,
                Shader.TileMode.CLAMP
            )
            setShadowLayer(7f * density, 0f, 0f, 0xAA4B8FFF.toInt())
        }

        val paths = listOf(
            Path().apply {
                moveTo(cx - r * 0.54f, cy + r * 0.48f)
                cubicTo(cx - r * 0.91f, cy - r * 0.05f, cx - r * 0.54f, cy - r * 0.84f, cx + r * 0.02f, cy - r * 0.84f)
                cubicTo(cx + r * 0.61f, cy - r * 0.84f, cx + r * 0.91f, cy - r * 0.16f, cx + r * 0.58f, cy + r * 0.29f)
                cubicTo(cx + r * 0.43f, cy + r * 0.49f, cx + r * 0.51f, cy + r * 0.66f, cx + r * 0.67f, cy + r * 0.78f)
            },
            Path().apply {
                moveTo(cx - r * 0.34f, cy + r * 0.55f)
                cubicTo(cx - r * 0.65f, cy + r * 0.12f, cx - r * 0.43f, cy - r * 0.64f, cx + r * 0.02f, cy - r * 0.66f)
                cubicTo(cx + r * 0.48f, cy - r * 0.67f, cx + r * 0.65f, cy - r * 0.11f, cx + r * 0.37f, cy + r * 0.31f)
                cubicTo(cx + r * 0.25f, cy + r * 0.49f, cx + r * 0.33f, cy + r * 0.62f, cx + r * 0.46f, cy + r * 0.71f)
            },
            Path().apply {
                moveTo(cx - r * 0.12f, cy + r * 0.59f)
                cubicTo(cx - r * 0.40f, cy + r * 0.18f, cx - r * 0.27f, cy - r * 0.43f, cx + r * 0.02f, cy - r * 0.47f)
                cubicTo(cx + r * 0.34f, cy - r * 0.50f, cx + r * 0.47f, cy - r * 0.08f, cx + r * 0.23f, cy + r * 0.27f)
                cubicTo(cx + r * 0.13f, cy + r * 0.42f, cx + r * 0.16f, cy + r * 0.53f, cx + r * 0.29f, cy + r * 0.65f)
            },
            Path().apply {
                moveTo(cx + r * 0.08f, cy + r * 0.53f)
                cubicTo(cx - r * 0.11f, cy + r * 0.17f, cx - r * 0.06f, cy - r * 0.24f, cx + r * 0.12f, cy - r * 0.28f)
                cubicTo(cx + r * 0.31f, cy - r * 0.30f, cx + r * 0.36f, cy - r * 0.02f, cx + r * 0.20f, cy + r * 0.22f)
            },
            Path().apply {
                moveTo(cx - r * 0.61f, cy - r * 0.10f)
                cubicTo(cx - r * 0.48f, cy - r * 0.61f, cx - r * 0.17f, cy - r * 0.92f, cx + r * 0.18f, cy - r * 0.81f)
            }
        )

        paths.forEach { canvas.drawPath(it, ridge) }
        ridge.clearShadowLayer()
    }

    private fun drawFooter(canvas: Canvas, w: Float, h: Float) {
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = medium
        paint.textSize = 8f * density
        paint.letterSpacing = 0.22f
        paint.color = 0xFF687693.toInt()
        canvas.drawText("PHANTOM LOCK  •  ANDROID SYSTEM SECURITY", w / 2f, h - 27f * density, paint)
        paint.letterSpacing = 0f

        paint.color = 0x557CAEFF
        canvas.drawRoundRect(
            RectF(w * 0.38f, h - 12f * density, w * 0.62f, h - 10f * density),
            2f * density,
            2f * density,
            paint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_UP) return true

        val dx = event.x - scannerX
        val dy = event.y - scannerY
        val hitRadius = scannerRadius * 1.75f
        if (dx * dx + dy * dy > hitRadius * hitRadius || verifying) {
            tapCount = 0
            return true
        }

        val now = event.eventTime
        if (now - lastTapAt > 850L) tapCount = 0
        lastTapAt = now
        tapCount++
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

        if (tapCount >= 3) {
            tapCount = 0
            lastTapAt = 0L
            onTripleTap()
        }
        return true
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }
}
