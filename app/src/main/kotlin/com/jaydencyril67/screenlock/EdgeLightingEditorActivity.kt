package com.jaydencyril67.screenlock

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class EdgeLightingEditorActivity : Activity() {
    private lateinit var outlineView: EdgeLightingEditorView
    private lateinit var selectedPointLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(5, 7, 15)
        window.navigationBarColor = Color.rgb(5, 7, 15)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(14))
            setBackgroundColor(Color.rgb(5, 7, 15))
        }

        val topLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = TextView(this).apply {
            text = "‹"
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(0xFFE8EDF8.toInt())
            setPadding(0, 0, dp(18), 0)
            contentDescription = "Back"
            setOnClickListener { finish() }
        }
        topLine.addView(back, LinearLayout.LayoutParams(dp(42), dp(48)))

        val titleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        titleBlock.addView(textLabel("EDGE LIGHTING", 10f, 0xFF91A9D5.toInt(), true))
        titleBlock.addView(textLabel("Custom screen outline", 22f, Color.WHITE, true))
        topLine.addView(titleBlock)
        root.addView(topLine)

        root.addView(
            textLabel(
                "Drag the handles around the phone to shape every edge. The three handles at the top let you match the Galaxy A02 waterdrop notch.",
                12f,
                0xFFAAB6CE.toInt(),
                false
            ).apply { setPadding(0, dp(8), 0, dp(10)) }
        )

        selectedPointLabel = textLabel(
            "SELECTED: TOP LEFT",
            10f,
            0xFFE6C58F.toInt(),
            true
        ).apply {
            letterSpacing = 0.10f
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(selectedPointLabel)

        outlineView = EdgeLightingEditorView(this) { index ->
            selectedPointLabel.text = "SELECTED: ${PhantomEdgeShape.pointNames[index]}"
        }
        root.addView(
            outlineView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                topMargin = dp(2)
                bottomMargin = dp(8)
            }
        )

        root.addView(
            textLabel(
                "TIP  ·  Move the notch tip down to deepen it. Move the notch sides inward or outward to change its width. Drag the side and bottom handles to reshape the remaining border.",
                11f,
                0xFF8492AC.toInt(),
                false
            ).apply {
                setPadding(dp(2), dp(2), dp(2), dp(12))
                setLineSpacing(dp(3).toFloat(), 1f)
            }
        )

        val initialThickness = PhantomEdgeShape.loadThickness(this)
        val thicknessLabel = textLabel(
            "LIGHT THICKNESS  ·  ${"%.1f".format(initialThickness)} DP",
            10f,
            0xFFE6C58F.toInt(),
            true
        ).apply {
            letterSpacing = 0.08f
            setPadding(0, dp(2), 0, dp(2))
        }
        root.addView(thicknessLabel)
        val thicknessSlider = SeekBar(this).apply {
            max = 8
            progress = ((initialThickness - 1f) / 0.5f).toInt().coerceIn(0, 8)
            progressTintList = android.content.res.ColorStateList.valueOf(0xFF8EA9F5.toInt())
            thumbTintList = android.content.res.ColorStateList.valueOf(0xFFE6C58F.toInt())
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val thickness = 1f + progress * 0.5f
                    outlineView.setThickness(thickness)
                    thicknessLabel.text = "LIGHT THICKNESS  ·  ${"%.1f".format(thickness)} DP"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        root.addView(
            thicknessSlider,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(38)
            ).apply { bottomMargin = dp(4) }
        )

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val reset = actionButton("RESET A02 SHAPE", 0xFF263147.toInt())
        reset.setOnClickListener {
            outlineView.resetShape()
            selectedPointLabel.text = "SELECTED: TOP LEFT"
        }
        val save = actionButton("SAVE OUTLINE", 0xFF526ED6.toInt())
        save.setOnClickListener {
            PhantomEdgeShape.save(this, outlineView.currentPoints())
            PhantomEdgeShape.saveThickness(this, outlineView.currentThickness())
            Toast.makeText(this, "Custom edge outline saved.", Toast.LENGTH_SHORT).show()
            finish()
        }
        buttons.addView(
            reset,
            LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginEnd = dp(6) }
        )
        buttons.addView(
            save,
            LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginStart = dp(6) }
        )
        root.addView(buttons)

        setContentView(root)
        PhantomTypography.apply(root, this)
    }

    private fun textLabel(text: String, size: Float, color: Int, bold: Boolean) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            typeface = if (bold) {
                android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            } else {
                android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
            }
        }

    private fun actionButton(text: String, color: Int) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 11f
        letterSpacing = 0.035f
        setTextColor(Color.WHITE)
        typeface = android.graphics.Typeface.create(
            "sans-serif-medium",
            android.graphics.Typeface.NORMAL
        )
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(color)
        }
        minHeight = dp(54)
        minimumHeight = dp(54)
        stateListAnimator = null
        elevation = 0f
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}

private class EdgeLightingEditorView(
    context: android.content.Context,
    private val onSelectedPointChanged: (Int) -> Unit
) : View(context) {
    private val density = resources.displayMetrics.density
    private val points = PhantomEdgeShape.load(context)
    private var edgeThickness = PhantomEdgeShape.loadThickness(context)
    private val screenBounds = RectF()
    private val deviceBounds = RectF()
    private val outline = Path()
    private val runner = Path()
    private val pathMeasure = PathMeasure()
    private val matrix = Matrix()
    private var selectedPoint = 0
    private var progress = 0f
    private var animator: ValueAnimator? = null

    private val rainbowColors = intArrayOf(
        0xFFFF315A.toInt(),
        0xFFFF8A24.toInt(),
        0xFFFFD84A.toInt(),
        0xFF55E58A.toInt(),
        0xFF35D9F2.toInt(),
        0xFF477BFF.toInt(),
        0xFFB65CFF.toInt(),
        0xFFFF315A.toInt()
    )
    private var rainbow: SweepGradient? = null

    private val devicePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0C1220.toInt()
        style = Paint.Style.FILL
    }
    private val deviceStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF34425C.toInt()
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
    }
    private val screenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val ambientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(edgeThickness * 0.675f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        alpha = 205
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(edgeThickness * 2.75f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        alpha = 85
    }
    private val runnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(edgeThickness * 0.95f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF4F6FF.toInt()
        style = Paint.Style.FILL
    }
    private val selectedHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFD28C.toInt()
        style = Paint.Style.FILL
    }
    private val handleHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAA6F8DFF.toInt()
        style = Paint.Style.FILL
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = true
        isFocusable = true
        contentDescription = "Drag the 11 handles to customize the edge lighting outline"
    }

    fun currentPoints(): FloatArray = points.copyOf()

    fun currentThickness(): Float = edgeThickness

    fun setThickness(value: Float) {
        edgeThickness = value.coerceIn(1f, 5f)
        ambientPaint.strokeWidth = dp(edgeThickness * 0.675f)
        glowPaint.strokeWidth = dp(edgeThickness * 2.75f)
        runnerPaint.strokeWidth = dp(edgeThickness * 0.95f)
        invalidate()
    }

    fun resetShape() {
        val defaults = PhantomEdgeShape.defaultPoints()
        for (i in defaults.indices) points[i] = defaults[i]
        selectedPoint = 0
        onSelectedPointChanged(selectedPoint)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3_600L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val displayRatio = resources.displayMetrics.widthPixels.toFloat() /
            resources.displayMetrics.heightPixels.toFloat().coerceAtLeast(1f)
        val horizontalSpace = width - dp(56f)
        val verticalSpace = height - dp(26f)
        val frameWidth = minOf(horizontalSpace, verticalSpace * displayRatio)
        val frameHeight = frameWidth / displayRatio.coerceAtLeast(0.1f)
        val left = (width - frameWidth) / 2f
        val top = (height - frameHeight) / 2f
        deviceBounds.set(left, top, left + frameWidth, top + frameHeight)

        canvas.drawRoundRect(deviceBounds, dp(30f), dp(30f), devicePaint)
        canvas.drawRoundRect(deviceBounds, dp(30f), dp(30f), deviceStroke)
        screenBounds.set(
            deviceBounds.left + dp(7f),
            deviceBounds.top + dp(7f),
            deviceBounds.right - dp(7f),
            deviceBounds.bottom - dp(7f)
        )
        canvas.drawRoundRect(screenBounds, dp(24f), dp(24f), screenPaint)

        PhantomEdgeShape.writePath(points, screenBounds, outline)
        val shader = SweepGradient(
            screenBounds.centerX(),
            screenBounds.centerY(),
            rainbowColors,
            null
        )
        matrix.setRotate(progress * 360f, screenBounds.centerX(), screenBounds.centerY())
        shader.setLocalMatrix(matrix)
        ambientPaint.shader = shader
        glowPaint.shader = shader
        runnerPaint.shader = shader

        canvas.drawPath(outline, glowPaint)
        canvas.drawPath(outline, ambientPaint)

        pathMeasure.setPath(outline, false)
        val length = pathMeasure.length
        if (length > 0f) {
            val runnerLength = length * 0.12f
            val start = progress * length
            val end = start + runnerLength
            runner.reset()
            if (end <= length) {
                pathMeasure.getSegment(start, end, runner, true)
            } else {
                pathMeasure.getSegment(start, length, runner, true)
                pathMeasure.getSegment(0f, end - length, runner, true)
            }
            canvas.drawPath(runner, runnerPaint)
        }

        for (index in 0 until PhantomEdgeShape.POINT_COUNT) {
            val x = screenBounds.left + points[index * 2] * screenBounds.width()
            val y = screenBounds.top + points[index * 2 + 1] * screenBounds.height()
            canvas.drawCircle(x, y, dp(if (index == selectedPoint) 12f else 10f), handleHaloPaint)
            canvas.drawCircle(
                x,
                y,
                dp(if (index == selectedPoint) 6.5f else 5f),
                if (index == selectedPoint) selectedHandlePaint else handlePaint
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (screenBounds.width() <= 0f || screenBounds.height() <= 0f) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val touchRadius = dp(30f)
                var closest = -1
                var closestDistance = touchRadius * touchRadius
                for (index in 0 until PhantomEdgeShape.POINT_COUNT) {
                    val x = screenBounds.left + points[index * 2] * screenBounds.width()
                    val y = screenBounds.top + points[index * 2 + 1] * screenBounds.height()
                    val dx = event.x - x
                    val dy = event.y - y
                    val distance = dx * dx + dy * dy
                    if (distance <= closestDistance) {
                        closest = index
                        closestDistance = distance
                    }
                }
                if (closest < 0) return false
                selectedPoint = closest
                onSelectedPointChanged(selectedPoint)
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val rawX = ((event.x - screenBounds.left) / screenBounds.width()).coerceIn(0f, 1f)
                val rawY = ((event.y - screenBounds.top) / screenBounds.height()).coerceIn(0f, 1f)
                val constrained = PhantomEdgeShape.constrainPoint(selectedPoint, rawX, rawY)
                points[selectedPoint * 2] = constrained.first
                points[selectedPoint * 2 + 1] = constrained.second
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                performClick()
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun dp(value: Float): Float = value * density
}
