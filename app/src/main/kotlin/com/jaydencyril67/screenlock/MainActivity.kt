package com.jaydencyril67.screenlock

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import dev.oneuiproject.oneui.layout.ToolbarLayout

/**
 * Phantom's settings host.
 *
 * Navigation and preference controls are supplied by the One UI design library.
 * Settings behavior lives in PhantomSettingsFragment so the screen is not built
 * from a parallel set of hand-styled Android buttons and cards.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_phantom_settings)

        findViewById<ToolbarLayout>(R.id.toolbar_layout).apply {
            setTitle("Phantom")
            setSubtitle("Protection and personalization")
            isExpanded = false
            showNavigationButtonAsBack = true
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, PhantomSettingsFragment())
                .commit()
        }

        showEntrySplash()
    }

    /**
     * Keeps Phantom's existing branded entry moment while the actual settings
     * surface underneath is rendered with One UI components.
     */
    private fun showEntrySplash() {
        val content = findViewById<ViewGroup>(android.R.id.content)
        val splash = FrameLayout(this).apply {
            setBackgroundColor(0xFF05070F.toInt())
            contentDescription = "Loading Phantom"
            isClickable = true
            isFocusable = true
        }

        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val logoCircle = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.BLACK)
                setStroke(dp(1), 0x1AFFFFFF)
            }
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher_foreground)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Phantom"
        }
        logoCircle.addView(
            logo,
            FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER)
        )
        center.addView(logoCircle, LinearLayout.LayoutParams(dp(92), dp(92)))

        val appName = TextView(this).apply {
            text = "PHANTOM"
            textSize = 13f
            setTextColor(Color.WHITE)
            letterSpacing = 0.14f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        }
        center.addView(
            appName,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(18) }
        )

        center.addView(
            PhantomSplashShimmerView(this),
            LinearLayout.LayoutParams(dp(180), dp(3)).apply {
                topMargin = dp(10)
            }
        )

        splash.addView(
            center,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )

        val poweredBy = TextView(this).apply {
            text = "Powered by KingsHall"
            textSize = 12f
            setTextColor(0xB8FFFFFF.toInt())
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
        }
        splash.addView(
            poweredBy,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                leftMargin = dp(16)
                rightMargin = dp(16)
                bottomMargin = dp(28)
            }
        )

        content.addView(
            splash,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        Handler(Looper.getMainLooper()).postDelayed({
            if (splash.parent === content) {
                splash.animate()
                    .alpha(0f)
                    .setDuration(220L)
                    .withEndAction {
                        (splash.parent as? ViewGroup)?.removeView(splash)
                    }
                    .start()
            }
        }, 3_000L)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}

/**
 * Slim continuous shimmer for the branded entry splash.
 */
private class PhantomSplashShimmerView(context: Context) : View(context) {
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x2EFFFFFF
    }
    private val shimmerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var progress = 0f
    private var shimmerAnimator: ValueAnimator? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        shimmerAnimator?.cancel()
        shimmerAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1_150L
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
        shimmerAnimator?.cancel()
        shimmerAnimator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val bounds = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val radius = height / 2f
        canvas.drawRoundRect(bounds, radius, radius, trackPaint)

        val sweepWidth = width * 0.42f
        val left = -sweepWidth + (width + sweepWidth) * progress
        shimmerPaint.shader = LinearGradient(
            left,
            0f,
            left + sweepWidth,
            0f,
            intArrayOf(Color.TRANSPARENT, Color.WHITE, Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )

        val saveCount = canvas.save()
        canvas.clipRect(bounds)
        canvas.drawRoundRect(
            left,
            0f,
            left + sweepWidth,
            height.toFloat(),
            radius,
            radius,
            shimmerPaint
        )
        canvas.restoreToCount(saveCount)
        shimmerPaint.shader = null
    }
}
