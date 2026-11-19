package dev.personal.doomblocker.service

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * A small card that drops in from the top of the screen, shows what was blocked and slides
 * away by itself. It never takes touches or focus, so the app underneath stays usable.
 * Drawn as an accessibility overlay, which needs no extra permission.
 */
internal class BlockBanner(private val service: AccessibilityService) {
    private val handler = Handler(Looper.getMainLooper())
    private val manager = service.getSystemService(WindowManager::class.java)
    private var current: View? = null
    private val hide = Runnable { dismiss(animated = true) }

    fun show(emoji: String, title: String, subtitle: String, accent: Int = ACCENT) {
        dismiss(animated = false)
        val dark = service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, service.resources.displayMetrics)

        val badge = TextView(service).apply {
            text = emoji
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(accent, blend(accent, Color.parseColor("#00B4D8"), .45f))
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.TL_BR
            }
            layoutParams = LinearLayout.LayoutParams(dp(46f).toInt(), dp(46f).toInt())
        }
        val titleView = TextView(service).apply {
            text = title
            setTextColor(if (dark) Color.WHITE else Color.parseColor("#14201A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val subtitleView = TextView(service).apply {
            text = subtitle
            setTextColor(if (dark) Color.parseColor("#BBC6BF") else Color.parseColor("#56615B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        val texts = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f).toInt(), 0, 0, 0)
            addView(titleView)
            addView(subtitleView)
        }
        val row = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(badge)
            addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        // A thin bar that empties while the card is visible, so it is clear it will go away.
        val timer = ProgressBar(service, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
        }
        val column = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f).toInt(), dp(14f).toInt(), dp(16f).toInt(), dp(8f).toInt())
            addView(row)
            addView(timer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6f).toInt()).apply {
                topMargin = dp(10f).toInt()
            })
            background = GradientDrawable().apply {
                cornerRadius = dp(26f)
                setColor(if (dark) Color.parseColor("#F21B231E") else Color.parseColor("#FAFFFFFF"))
                setStroke(dp(1f).toInt(), blend(accent, if (dark) Color.BLACK else Color.WHITE, .55f))
            }
            elevation = dp(12f)
        }
        val root = FrameLayout(service).apply {
            setPadding(dp(14f).toInt(), dp(12f).toInt(), dp(14f).toInt(), dp(24f).toInt())
            clipToPadding = false
            addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            y = service.resources.getIdentifier("status_bar_height", "dimen", "android")
                .takeIf { it > 0 }?.let(service.resources::getDimensionPixelSize) ?: dp(24f).toInt()
        }
        runCatching { manager.addView(root, params) }.onFailure { return }
        current = root

        // Entrance: drop from above with a little overshoot, badge pops a moment later.
        column.translationY = -dp(140f)
        column.alpha = 0f
        column.scaleX = .92f; column.scaleY = .92f
        column.animate().translationY(0f).alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(520).setInterpolator(OvershootInterpolator(1.1f)).start()
        badge.scaleX = 0f; badge.scaleY = 0f
        badge.animate().scaleX(1f).scaleY(1f).setStartDelay(180).setDuration(420)
            .setInterpolator(OvershootInterpolator(3f)).start()
        ValueAnimator.ofInt(1000, 0).apply {
            duration = VISIBLE_MILLIS
            addUpdateListener { timer.progress = it.animatedValue as Int }
            start()
        }
        handler.postDelayed(hide, VISIBLE_MILLIS)
    }

    fun dismiss(animated: Boolean) {
        handler.removeCallbacks(hide)
        val view = current ?: return
        current = null
        val card = (view as? FrameLayout)?.getChildAt(0)
        if (animated && card != null) {
            card.animate().translationY(-card.height.toFloat() - 80f).alpha(0f).setDuration(260)
                .setInterpolator(AccelerateInterpolator()).withEndAction {
                    runCatching { manager.removeViewImmediate(view) }
                }.start()
        } else runCatching { manager.removeViewImmediate(view) }
    }

    private fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
    )

    companion object {
        val ACCENT = Color.parseColor("#2A8A66")
        const val VISIBLE_MILLIS = 2_600L
    }
}
