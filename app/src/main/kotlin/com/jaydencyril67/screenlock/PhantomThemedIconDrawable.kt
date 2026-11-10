package com.jaydencyril67.screenlock

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import kotlin.math.min

/**
 * Phantom's One UI-inspired home icon skin.
 *
 * It redraws common system app symbols in a unified adaptive style. Apps without
 * a known mapping keep their original launcher icon inside the same tile system.
 */
class PhantomThemedIconDrawable(
    packageName: String,
    originalIcon: Drawable
) : Drawable() {

    private enum class Kind {
        PHONE, MESSAGES, CAMERA, BROWSER, GALLERY, SETTINGS,
        FILES, CLOCK, CALENDAR, PLAY, WHATSAPP, INSTAGRAM, MUSIC, ORIGINAL
    }

    private val kind = identify(packageName.lowercase())
    private val original = originalIcon.mutate()
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = 1.4f
    }
    private var drawableAlpha = 255
    private var drawableColorFilter: ColorFilter? = null

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        val side = min(bounds.width(), bounds.height()).toFloat()
        if (side <= 0f) return

        val left = bounds.left + (bounds.width() - side) / 2f
        val top = bounds.top + (bounds.height() - side) / 2f
        val tile = RectF(left, top, left + side, top + side)
        val radius = side * 0.235f
        val palette = paletteFor(kind)

        backgroundPaint.alpha = drawableAlpha
        backgroundPaint.colorFilter = drawableColorFilter
        backgroundPaint.shader = LinearGradient(
            tile.left,
            tile.top,
            tile.right,
            tile.bottom,
            palette.first,
            palette.second,
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(tile, radius, radius, backgroundPaint)
        backgroundPaint.shader = null

        borderPaint.alpha = drawableAlpha
        canvas.drawRoundRect(tile, radius, radius, borderPaint)

        if (kind == Kind.ORIGINAL) {
            val inset = side * 0.12f
            original.alpha = drawableAlpha
            original.colorFilter = drawableColorFilter
            original.setBounds(
                (tile.left + inset).toInt(),
                (tile.top + inset).toInt(),
                (tile.right - inset).toInt(),
                (tile.bottom - inset).toInt()
            )
            original.draw(canvas)
        } else {
            val save = canvas.save()
            canvas.translate(tile.left, tile.top)
            canvas.scale(side / 100f, side / 100f)
            glyphPaint.alpha = drawableAlpha
            glyphPaint.colorFilter = drawableColorFilter
            drawGlyph(canvas)
            canvas.restoreToCount(save)
        }
    }

    private fun drawGlyph(canvas: Canvas) {
        when (kind) {
            Kind.PHONE -> drawPhone(canvas)
            Kind.MESSAGES -> drawMessages(canvas)
            Kind.CAMERA -> drawCamera(canvas)
            Kind.BROWSER -> drawBrowser(canvas)
            Kind.GALLERY -> drawGallery(canvas)
            Kind.SETTINGS -> drawSettings(canvas)
            Kind.FILES -> drawFiles(canvas)
            Kind.CLOCK -> drawClock(canvas)
            Kind.CALENDAR -> drawCalendar(canvas)
            Kind.PLAY -> drawPlay(canvas)
            Kind.WHATSAPP -> drawWhatsApp(canvas)
            Kind.INSTAGRAM -> drawInstagram(canvas)
            Kind.MUSIC -> drawMusic(canvas)
            Kind.ORIGINAL -> Unit
        }
    }

    private fun drawPhone(canvas: Canvas) {
        val path = Path().apply {
            moveTo(28f, 17f)
            cubicTo(23f, 17f, 19f, 21f, 20f, 27f)
            cubicTo(24f, 53f, 47f, 76f, 73f, 81f)
            cubicTo(79f, 82f, 83f, 78f, 83f, 72f)
            lineTo(82f, 66f)
            cubicTo(81f, 62f, 77f, 60f, 73f, 61f)
            lineTo(63f, 66f)
            cubicTo(57f, 63f, 38f, 45f, 35f, 38f)
            lineTo(40f, 28f)
            cubicTo(41f, 24f, 39f, 21f, 35f, 19f)
            close()
        }
        canvas.drawPath(path, glyphPaint)
    }

    private fun drawMessages(canvas: Canvas) {
        canvas.drawRoundRect(RectF(17f, 19f, 83f, 72f), 18f, 18f, glyphPaint)
        val tail = Path().apply {
            moveTo(31f, 67f)
            lineTo(24f, 83f)
            lineTo(47f, 69f)
            close()
        }
        canvas.drawPath(tail, glyphPaint)
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4F91E8.toInt() }
        canvas.drawCircle(36f, 46f, 4f, dotPaint)
        canvas.drawCircle(50f, 46f, 4f, dotPaint)
        canvas.drawCircle(64f, 46f, 4f, dotPaint)
    }

    private fun drawCamera(canvas: Canvas) {
        canvas.drawRoundRect(RectF(14f, 28f, 86f, 78f), 13f, 13f, glyphPaint)
        canvas.drawRoundRect(RectF(28f, 21f, 49f, 33f), 5f, 5f, glyphPaint)
        val cutout = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF586276.toInt() }
        canvas.drawCircle(51f, 53f, 17f, cutout)
        canvas.drawCircle(51f, 53f, 10f, glyphPaint)
        canvas.drawCircle(73f, 39f, 3f, cutout)
    }

    private fun drawBrowser(canvas: Canvas) {
        glyphPaint.style = Paint.Style.STROKE
        glyphPaint.strokeWidth = 6f
        canvas.drawCircle(50f, 50f, 32f, glyphPaint)
        canvas.drawLine(19f, 50f, 81f, 50f, glyphPaint)
        canvas.drawOval(RectF(36f, 18f, 64f, 82f), glyphPaint)
        canvas.drawArc(RectF(23f, 18f, 77f, 82f), 205f, 130f, false, glyphPaint)
        glyphPaint.style = Paint.Style.FILL
    }

    private fun drawGallery(canvas: Canvas) {
        val colors = intArrayOf(
            0xFFFFD45E.toInt(), 0xFFFF8D70.toInt(), 0xFFEE719D.toInt(),
            0xFF9A82E8.toInt(), 0xFF62B9E8.toInt(), 0xFF66C99D.toInt()
        )
        val petalPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val centers = arrayOf(
            50f to 29f, 68f to 40f, 68f to 61f,
            50f to 72f, 32f to 61f, 32f to 40f
        )
        centers.forEachIndexed { index, point ->
            petalPaint.color = colors[index]
            canvas.drawCircle(point.first, point.second, 13f, petalPaint)
        }
        petalPaint.color = Color.WHITE
        canvas.drawCircle(50f, 50f, 9f, petalPaint)
    }

    private fun drawSettings(canvas: Canvas) {
        glyphPaint.style = Paint.Style.STROKE
        glyphPaint.strokeWidth = 7f
        canvas.drawCircle(50f, 50f, 22f, glyphPaint)
        for (angle in 0 until 8) {
            val radians = Math.toRadians((angle * 45).toDouble())
            val x1 = 50f + kotlin.math.cos(radians).toFloat() * 27f
            val y1 = 50f + kotlin.math.sin(radians).toFloat() * 27f
            val x2 = 50f + kotlin.math.cos(radians).toFloat() * 36f
            val y2 = 50f + kotlin.math.sin(radians).toFloat() * 36f
            canvas.drawLine(x1, y1, x2, y2, glyphPaint)
        }
        glyphPaint.style = Paint.Style.FILL
        canvas.drawCircle(50f, 50f, 8f, glyphPaint)
    }

    private fun drawFiles(canvas: Canvas) {
        val path = Path().apply {
            moveTo(14f, 31f)
            cubicTo(14f, 25f, 18f, 22f, 24f, 22f)
            lineTo(43f, 22f)
            lineTo(51f, 31f)
            lineTo(76f, 31f)
            cubicTo(82f, 31f, 86f, 35f, 86f, 41f)
            lineTo(82f, 73f)
            cubicTo(81f, 78f, 77f, 81f, 72f, 81f)
            lineTo(25f, 81f)
            cubicTo(19f, 81f, 15f, 77f, 15f, 71f)
            close()
        }
        canvas.drawPath(path, glyphPaint)
    }

    private fun drawClock(canvas: Canvas) {
        glyphPaint.style = Paint.Style.STROKE
        glyphPaint.strokeWidth = 6f
        canvas.drawCircle(50f, 50f, 32f, glyphPaint)
        canvas.drawLine(50f, 50f, 50f, 30f, glyphPaint)
        canvas.drawLine(50f, 50f, 68f, 59f, glyphPaint)
        glyphPaint.style = Paint.Style.FILL
        canvas.drawCircle(50f, 50f, 4f, glyphPaint)
    }

    private fun drawCalendar(canvas: Canvas) {
        canvas.drawRoundRect(RectF(17f, 21f, 83f, 81f), 11f, 11f, glyphPaint)
        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFDE6F87.toInt() }
        canvas.drawRoundRect(RectF(17f, 21f, 83f, 41f), 10f, 10f, headerPaint)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF4C5362.toInt()
            textSize = 27f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("31", 50f, 69f, textPaint)
    }

    private fun drawPlay(canvas: Canvas) {
        val path = Path().apply {
            moveTo(31f, 17f)
            lineTo(78f, 44f)
            cubicTo(84f, 48f, 84f, 53f, 78f, 57f)
            lineTo(31f, 83f)
            cubicTo(26f, 86f, 21f, 83f, 21f, 77f)
            lineTo(21f, 23f)
            cubicTo(21f, 17f, 26f, 14f, 31f, 17f)
            close()
        }
        canvas.drawPath(path, glyphPaint)
    }

    private fun drawWhatsApp(canvas: Canvas) {
        glyphPaint.style = Paint.Style.STROKE
        glyphPaint.strokeWidth = 7f
        canvas.drawCircle(49f, 47f, 30f, glyphPaint)
        glyphPaint.style = Paint.Style.FILL
        val tail = Path().apply {
            moveTo(29f, 67f)
            lineTo(24f, 82f)
            lineTo(42f, 74f)
            close()
        }
        canvas.drawPath(tail, glyphPaint)
        val phone = Path().apply {
            moveTo(39f, 32f)
            cubicTo(35f, 35f, 39f, 49f, 52f, 59f)
            cubicTo(60f, 64f, 67f, 61f, 67f, 56f)
            lineTo(57f, 50f)
            lineTo(51f, 55f)
            cubicTo(46f, 52f, 43f, 47f, 42f, 43f)
            lineTo(47f, 38f)
            close()
        }
        canvas.drawPath(phone, glyphPaint)
    }

    private fun drawInstagram(canvas: Canvas) {
        glyphPaint.style = Paint.Style.STROKE
        glyphPaint.strokeWidth = 6f
        canvas.drawRoundRect(RectF(19f, 19f, 81f, 81f), 18f, 18f, glyphPaint)
        canvas.drawCircle(50f, 50f, 15f, glyphPaint)
        glyphPaint.style = Paint.Style.FILL
        canvas.drawCircle(69f, 32f, 4f, glyphPaint)
    }

    private fun drawMusic(canvas: Canvas) {
        val stem = Path().apply {
            moveTo(57f, 23f)
            lineTo(57f, 65f)
            cubicTo(57f, 76f, 47f, 82f, 37f, 78f)
            cubicTo(27f, 74f, 30f, 62f, 40f, 59f)
            cubicTo(46f, 57f, 51f, 59f, 54f, 62f)
            lineTo(54f, 32f)
            lineTo(76f, 26f)
            lineTo(76f, 39f)
            lineTo(57f, 44f)
            close()
        }
        canvas.drawPath(stem, glyphPaint)
    }

    private fun paletteFor(kind: Kind): Pair<Int, Int> = when (kind) {
        Kind.PHONE -> 0xFF3CCB9A.toInt() to 0xFF087F69.toInt()
        Kind.MESSAGES -> 0xFF7BB8FF.toInt() to 0xFF3977D8.toInt()
        Kind.CAMERA -> 0xFF929BAE.toInt() to 0xFF4C566A.toInt()
        Kind.BROWSER -> 0xFF82C4E6.toInt() to 0xFF3B78B7.toInt()
        Kind.GALLERY -> 0xFFFFB9C9.toInt() to 0xFFE36E9B.toInt()
        Kind.SETTINGS -> 0xFFB6BDCE.toInt() to 0xFF687286.toInt()
        Kind.FILES -> 0xFFFFD779.toInt() to 0xFFCD8B2C.toInt()
        Kind.CLOCK -> 0xFFC2B9F4.toInt() to 0xFF8376C8.toInt()
        Kind.CALENDAR -> 0xFFFFB9C6.toInt() to 0xFFE47791.toInt()
        Kind.PLAY -> 0xFF66CFAE.toInt() to 0xFF4086D9.toInt()
        Kind.WHATSAPP -> 0xFF39D68A.toInt() to 0xFF0C9A62.toInt()
        Kind.INSTAGRAM -> 0xFFFFA56F.toInt() to 0xFFB852A9.toInt()
        Kind.MUSIC -> 0xFF536B69.toInt() to 0xFF182F36.toInt()
        Kind.ORIGINAL -> 0xFFE4E7F1.toInt() to 0xFFC6CDDF.toInt()
    }

    private fun identify(packageName: String): Kind = when {
        packageName.contains("dialer") || packageName.contains("phone") -> Kind.PHONE
        packageName.contains("messag") || packageName.contains("sms") -> Kind.MESSAGES
        packageName.contains("camera") -> Kind.CAMERA
        packageName.contains("gallery") || packageName.contains("photo") -> Kind.GALLERY
        packageName.contains("setting") -> Kind.SETTINGS
        packageName.contains("myfiles") || packageName.contains("filemanager") ||
            packageName.contains("documentsui") -> Kind.FILES
        packageName.contains("clock") -> Kind.CLOCK
        packageName.contains("calendar") -> Kind.CALENDAR
        packageName.contains("play") && (packageName.contains("store") || packageName.contains("vending")) -> Kind.PLAY
        packageName.contains("whatsapp") -> Kind.WHATSAPP
        packageName.contains("instagram") -> Kind.INSTAGRAM
        packageName.contains("spotify") || packageName.contains("music") -> Kind.MUSIC
        packageName.contains("browser") || packageName.contains("sbrowser") ||
            packageName.contains("chrome") -> Kind.BROWSER
        else -> Kind.ORIGINAL
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        drawableColorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = 96
    override fun getIntrinsicHeight(): Int = 96
}
