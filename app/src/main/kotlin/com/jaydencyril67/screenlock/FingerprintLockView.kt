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
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

class FingerprintLockView(
    context: Context,
    private val onKeypadTap: () -> Unit,
    private val onFingerprintTap: () -> Unit,
    private val onCloseTap: () -> Unit
) : View(context) {

    private val density = resources.displayMetrics.density
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val light = Typeface.create("sans-serif-light", Typeface.NORMAL)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var primaryStatus = "TOUCH TO SCAN"
    private var secondaryStatus = "Place your finger on the glowing scanner"
    private var errorStatus = false
    var isAuthenticated: Boolean = false
        private set

    private var keypadHit = RectF()
    private var fingerprintX = 0f
    private var fingerprintY = 0f
    private var fingerprintRadius = 0f
    private var closeHit = RectF()

    fun showStatus(primary: String, secondary: String, isError: Boolean = false) {
        primaryStatus = primary
        secondaryStatus = secondary
        errorStatus = isError
        invalidate()
    }

    fun showAuthenticated() {
        isAuthenticated = true
        primaryStatus = "ACCESS GRANTED"
        secondaryStatus = "Android confirmed your device credentials"
        errorStatus = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        drawBackground(canvas, w, h)
        drawTopKeypad(canvas, w)
        drawClock(canvas, w, h)
        drawFingerprintScanner(canvas, w, h)
        drawStatus(canvas, w, h)
        if (isAuthenticated) drawCloseAction(canvas, w, h)
        drawBottomDetails(canvas, w, h)
    }

    private fun drawBackground(canvas: Canvas, w: Float, h: Float) {
        paint.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(Color.rgb(5, 5, 13), Color.rgb(12, 7, 28), Color.rgb(4, 7, 17)),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        paint.shader = RadialGradient(
            w * 0.82f, h * 0.42f, w * 0.68f,
            intArrayOf(0x332D77FF, 0x1E6B35D5, Color.TRANSPARENT),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        paint.shader = RadialGradient(
            w * 0.12f, h * 0.83f, w * 0.66f,
            intArrayOf(0x332E0C72, 0x142A1D91, Color.TRANSPARENT),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        paint.color = 0x147C62D9
        paint.strokeWidth = 1f * density
        canvas.drawLine(0f, h * 0.84f, w, h * 0.84f, paint)
    }

    private fun drawTopKeypad(canvas: Canvas, w: Float) {
        val cx = w - 37f * density
        val cy = 43f * density
        val radius = 20f * density
        keypadHit = RectF(
            cx - 29f * density,
            cy - 29f * density,
            cx + 29f * density,
            cy + 29f * density
        )

        paint.shader = RadialGradient(
            cx - 6f * density, cy - 8f * density, 34f * density,
            intArrayOf(0x332C83FF, 0x222A1550, 0x10201030),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, paint)
        paint.shader = null

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * density
        paint.color = 0x667D69C9
        canvas.drawCircle(cx, cy, radius, paint)

        // Small locked keypad glyph. Its appearance never changes when tapped.
        paint.color = 0xFFE1D8FF.toInt()
        paint.strokeWidth = 1.8f * density
        paint.strokeCap = Paint.Cap.ROUND
        val lock = Path().apply {
            moveTo(cx - 6f * density, cy - 1f * density)
            lineTo(cx - 6f * density, cy - 6f * density)
            cubicTo(cx - 6f * density, cy - 14f * density, cx + 6f * density, cy - 14f * density, cx + 6f * density, cy - 6f * density)
            lineTo(cx + 6f * density, cy - 1f * density)
        }
        canvas.drawPath(lock, paint)

        paint.style = Paint.Style.FILL
        val keySize = 2.1f * density
        for (row in 0..1) {
            for (column in 0..2) {
                canvas.drawCircle(
                    cx + (column - 1) * 5.6f * density,
                    cy + (row * 5.5f + 3f) * density,
                    keySize,
                    paint
                )
            }
        }
        paint.style = Paint.Style.FILL
    }

    private fun drawClock(canvas: Canvas, w: Float, h: Float) {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val date = SimpleDateFormat("EEEE  ·  d MMMM", Locale.getDefault()).format(Date())
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = light
        paint.textSize = 58f * density
        paint.color = Color.WHITE
        paint.setShadowLayer(18f * density, 0f, 0f, 0x665A7BFF)
        canvas.drawText(time, w / 2f, h * 0.235f, paint)
        paint.clearShadowLayer()

        paint.typeface = medium
        paint.textSize = 10f * density
        paint.letterSpacing = 0.12f
        paint.color = 0xFFAAA3C6.toInt()
        canvas.drawText(date.uppercase(Locale.getDefault()), w / 2f, h * 0.235f + 25f * density, paint)
        paint.letterSpacing = 0f
    }

    private fun drawFingerprintScanner(canvas: Canvas, w: Float, h: Float) {
        fingerprintX = w / 2f
        fingerprintY = h * 0.59f
        fingerprintRadius = min(w * 0.235f, 88f * density)

        val cx = fingerprintX
        val cy = fingerprintY
        val r = fingerprintRadius

        paint.shader = RadialGradient(
            cx, cy, r * 1.65f,
            intArrayOf(0x443E6FFF, 0x222B19A4, Color.TRANSPARENT),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r * 1.65f, paint)
        paint.shader = null

        paint.style = Paint.Style.FILL
        paint.color = 0x1C8E69FF
        canvas.drawCircle(cx, cy, r * 1.18f, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.4f * density
        paint.shader = LinearGradient(
            cx - r, cy - r, cx + r, cy + r,
            intArrayOf(0xAA9D73FF.toInt(), 0xAA38BDF8.toInt()),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r * 1.12f, paint)
        paint.shader = null

        paint.strokeWidth = 1f * density
        paint.color = 0x447C79E8
        canvas.drawCircle(cx, cy, r * 0.98f, paint)
        paint.style = Paint.Style.FILL

        if (isAuthenticated) {
            drawCheckmark(canvas, cx, cy, r)
            return
        }

        drawFingerprintRidges(canvas, cx, cy, r)
    }

    private fun drawFingerprintRidges(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val ridgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.4f * density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            shader = LinearGradient(
                cx - r, cy - r, cx + r, cy + r,
                intArrayOf(0xFFF0E9FF.toInt(), 0xFF8BA7FF.toInt(), 0xFF63D4FF.toInt()),
                null,
                Shader.TileMode.CLAMP
            )
            setShadowLayer(8f * density, 0f, 0f, 0xAA6A62FF.toInt())
        }

        val paths = listOf(
            Path().apply {
                moveTo(cx - r * 0.48f, cy + r * 0.46f)
                cubicTo(cx - r * 0.88f, cy + r * 0.06f, cx - r * 0.60f, cy - r * 0.76f, cx - r * 0.05f, cy - r * 0.77f)
                cubicTo(cx + r * 0.52f, cy - r * 0.79f, cx + r * 0.80f, cy - r * 0.18f, cx + r * 0.53f, cy + r * 0.24f)
                cubicTo(cx + r * 0.38f, cy + r * 0.46f, cx + r * 0.50f, cy + r * 0.63f, cx + r * 0.67f, cy + r * 0.78f)
            },
            Path().apply {
                moveTo(cx - r * 0.28f, cy + r * 0.56f)
                cubicTo(cx - r * 0.61f, cy + r * 0.16f, cx - r * 0.44f, cy - r * 0.59f, cx - r * 0.03f, cy - r * 0.60f)
                cubicTo(cx + r * 0.40f, cy - r * 0.61f, cx + r * 0.57f, cy - r * 0.11f, cx + r * 0.33f, cy + r * 0.27f)
                cubicTo(cx + r * 0.21f, cy + r * 0.45f, cx + r * 0.30f, cy + r * 0.56f, cx + r * 0.45f, cy + r * 0.69f)
            },
            Path().apply {
                moveTo(cx - r * 0.08f, cy + r * 0.58f)
                cubicTo(cx - r * 0.34f, cy + r * 0.20f, cx - r * 0.28f, cy - r * 0.39f, cx + r * 0.01f, cy - r * 0.43f)
                cubicTo(cx + r * 0.30f, cy - r * 0.46f, cx + r * 0.42f, cy - r * 0.09f, cx + r * 0.20f, cy + r * 0.24f)
                cubicTo(cx + r * 0.10f, cy + r * 0.41f, cx + r * 0.12f, cy + r * 0.51f, cx + r * 0.25f, cy + r * 0.64f)
            },
            Path().apply {
                moveTo(cx + r * 0.06f, cy + r * 0.51f)
                cubicTo(cx - r * 0.12f, cy + r * 0.16f, cx - r * 0.08f, cy - r * 0.22f, cx + r * 0.09f, cy - r * 0.25f)
                cubicTo(cx + r * 0.27f, cy - r * 0.27f, cx + r * 0.32f, cy - r * 0.02f, cx + r * 0.17f, cy + r * 0.22f)
            },
            Path().apply {
                moveTo(cx - r * 0.55f, cy - r * 0.02f)
                cubicTo(cx - r * 0.48f, cy - r * 0.52f, cx - r * 0.18f, cy - r * 0.90f, cx + r * 0.16f, cy - r * 0.83f)
            }
        )

        paths.forEach { canvas.drawPath(it, ridgePaint) }
        ridgePaint.clearShadowLayer()
    }

    private fun drawCheckmark(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5f * density
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = 0xFF8AF0D2.toInt()
        val check = Path().apply {
            moveTo(cx - r * 0.34f, cy)
            lineTo(cx - r * 0.08f, cy + r * 0.27f)
            lineTo(cx + r * 0.40f, cy - r * 0.30f)
        }
        canvas.drawPath(check, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawStatus(canvas: Canvas, w: Float, h: Float) {
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = medium
        paint.textSize = 11f * density
        paint.letterSpacing = 0.12f
        paint.color = if (errorStatus) 0xFFFF9BAF.toInt() else 0xFFE7E0FF.toInt()
        canvas.drawText(primaryStatus, w / 2f, h * 0.59f + fingerprintRadius * 1.48f, paint)

        paint.letterSpacing = 0f
        paint.typeface = regular
        paint.textSize = 11f * density
        paint.color = 0xFF9C96B7.toInt()
        canvas.drawText(secondaryStatus, w / 2f, h * 0.59f + fingerprintRadius * 1.48f + 23f * density, paint)
    }

    private fun drawCloseAction(canvas: Canvas, w: Float, h: Float) {
        val rect = RectF(w * 0.32f, h * 0.80f, w * 0.68f, h * 0.87f)
        closeHit = rect
        paint.shader = LinearGradient(
            rect.left, rect.top, rect.right, rect.bottom,
            intArrayOf(0x443E2B7D, 0x44305E9A),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, 24f * density, 24f * density, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * density
        paint.color = 0x887F89E8.toInt()
        canvas.drawRoundRect(rect, 24f * density, 24f * density, paint)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = medium
        paint.textSize = 11f * density
        paint.color = Color.WHITE
        canvas.drawText("CLOSE", w / 2f, rect.centerY() + 4f * density, paint)
    }

    private fun drawBottomDetails(canvas: Canvas, w: Float, h: Float) {
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = medium
        paint.textSize = 8f * density
        paint.letterSpacing = 0.22f
        paint.color = 0xFF6C6684.toInt()
        canvas.drawText("PHANTOM  ·  SECURE ACCESS", w / 2f, h - 27f * density, paint)
        paint.letterSpacing = 0f

        paint.color = 0x447F75BA
        canvas.drawCircle(w / 2f, h - 14f * density, 2f * density, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true

        val x = event.x
        val y = event.y
        if (isAuthenticated && closeHit.contains(x, y)) {
            onCloseTap()
            return true
        }
        if (keypadHit.contains(x, y)) {
            onKeypadTap()
            return true
        }

        val dx = x - fingerprintX
        val dy = y - fingerprintY
        if (dx * dx + dy * dy <= fingerprintRadius * fingerprintRadius * 1.45f) {
            onFingerprintTap()
        }
        return true
    }
}
