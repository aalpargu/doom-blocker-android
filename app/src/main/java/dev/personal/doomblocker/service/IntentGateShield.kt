package dev.personal.doomblocker.service

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/** Main-thread-only, short-lived opaque cover while the gate submits its first frame. */
internal class IntentGateShield(
    private val service: AccessibilityService,
    private val onTimeout: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val manager = service.getSystemService(WindowManager::class.java)
    private var view: TextView? = null
    private val handoff = GateHandoff()
    private val timeout = Runnable {
        try { onTimeout() } finally { dismiss() }
    }

    fun isShowingFor(packageName: String) = handoff.isWaitingFor(packageName)

    fun show(packageName: String): Long {
        dismiss()
        val token = SystemClock.elapsedRealtimeNanos()
        val dark = service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val cover = TextView(service).apply {
            setBackgroundColor(Color.parseColor(if (dark) "#101713" else "#F6F5F0"))
            setTextColor(Color.parseColor(if (dark) "#A1DFBF" else "#246B53"))
            text = "Bir nefes arası"
            textSize = 24f
            gravity = Gravity.CENTER
            // Intercept touches to the protected app without stealing window focus.
            isClickable = true
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        manager.addView(cover, WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
        })
        view = cover
        handoff.begin(packageName, token)
        handler.postDelayed(timeout, 3_000L)
        return token
    }

    fun onGateFrameCommitted(token: Long) {
        // An old activity/frame must never dismiss a newer target's cover.
        if (handoff.acceptFrame(token)) dismiss()
    }

    fun onForeground(packageName: String?) {
        if (handoff.shouldDismissFor(packageName, service.packageName)) dismiss()
    }

    fun dismiss() {
        handler.removeCallbacks(timeout)
        handoff.clear()
        view?.let { manager.removeViewImmediate(it) }
        view = null
    }
}
