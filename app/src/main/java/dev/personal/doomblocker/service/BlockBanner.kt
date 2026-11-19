package dev.personal.doomblocker.service

import android.accessibilityservice.AccessibilityService
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
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.personal.doomblocker.R

/**
 * A small, quiet pill at the top of the screen ("🔒 Reels kapalı"), styled like a system
 * notice rather than a promotional card. It fades in, stays briefly and fades out. It never
 * takes touches or focus, and needs no extra permission (accessibility overlay).
 */
internal class BlockBanner(private val service: AccessibilityService) {
    private val handler = Handler(Looper.getMainLooper())
    private val manager = service.getSystemService(WindowManager::class.java)
    private var current: View? = null
    private val hide = Runnable { dismiss(animated = true) }

    fun show(message: String, showLock: Boolean = true) {
        dismiss(animated = false)
        fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, service.resources.displayMetrics).toInt()

        val pill = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f), dp(9f), dp(16f), dp(9f))
            background = GradientDrawable().apply {
                cornerRadius = dp(100f).toFloat()
                setColor(Color.parseColor("#EB202124"))
            }
            elevation = dp(6f).toFloat()
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            if (showLock) addView(ImageView(service).apply {
                setImageResource(R.drawable.ic_lock_small)
            }, LinearLayout.LayoutParams(dp(15f), dp(15f)).apply { marginEnd = dp(8f) })
            addView(TextView(service).apply {
                text = message
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 1
            })
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            val statusBar = service.resources.getIdentifier("status_bar_height", "dimen", "android")
                .takeIf { it > 0 }?.let(service.resources::getDimensionPixelSize) ?: dp(24f)
            y = statusBar + dp(10f)
        }
        runCatching { manager.addView(pill, params) }.onFailure { return }
        current = pill

        pill.alpha = 0f
        pill.translationY = -dp(10f).toFloat()
        pill.animate().alpha(1f).translationY(0f).setDuration(200).setInterpolator(DecelerateInterpolator()).start()
        handler.postDelayed(hide, VISIBLE_MILLIS)
    }

    fun dismiss(animated: Boolean) {
        handler.removeCallbacks(hide)
        val view = current ?: return
        current = null
        if (animated) {
            view.animate().alpha(0f).translationY(-view.height / 3f).setDuration(180).withEndAction {
                runCatching { manager.removeViewImmediate(view) }
            }.start()
        } else runCatching { manager.removeViewImmediate(view) }
    }

    private companion object {
        const val VISIBLE_MILLIS = 1_800L
    }
}
