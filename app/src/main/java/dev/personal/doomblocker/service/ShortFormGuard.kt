package dev.personal.doomblocker.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.personal.doomblocker.data.ShortFormPreferences
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class ShortFormKind(val packageName: String, val label: String) {
    REELS("com.instagram.android", "Instagram Reels"),
    SHORTS("com.google.android.youtube", "YouTube Shorts"),
}

/** The few facts read from each on-screen element: its internal id, its accessibility label and whether it is selected. */
data class ScreenNode(val id: String?, val label: String?, val selected: Boolean)

/**
 * Decides whether the visible screen is the Reels / Shorts player.
 *
 * Two independent signals are used so a single renamed id in an app update does not silently
 * break detection: (1) the full-screen player's internal view id, (2) the bottom tab named
 * "Reels" / "Shorts" being the selected one. Inline previews in the normal feed do not match.
 */
object ShortFormDetector {
    private val playerIds = mapOf(
        ShortFormKind.REELS to setOf("clips_viewer_view_pager", "clips_viewer_container", "clips_viewer_pager"),
        ShortFormKind.SHORTS to setOf("reel_recycler", "reel_player_page_container", "reel_watch_player"),
    )
    private val tabIds = mapOf(
        ShortFormKind.REELS to setOf("clips_tab"),
        ShortFormKind.SHORTS to emptySet(),
    )
    private val tabLabels = mapOf(
        ShortFormKind.REELS to setOf("reels"),
        ShortFormKind.SHORTS to setOf("shorts"),
    )

    fun matches(kind: ShortFormKind, nodes: Sequence<ScreenNode>): Boolean = nodes.any { node ->
        val shortId = node.id?.substringAfter(":id/")
        (shortId != null && shortId in playerIds.getValue(kind)) ||
            (node.selected && shortId != null && shortId in tabIds.getValue(kind)) ||
            (node.selected && node.label?.trim()?.lowercase() in tabLabels.getValue(kind))
    }
}

/**
 * Watches only Instagram and YouTube. When the user is on Reels / Shorts and the matching switch
 * is on, it presses Back (which returns to the previous tab) instead of closing the whole app.
 * Nothing it reads is stored or sent, except an explicit, user-triggered structure capture.
 */
class ShortFormGuard(
    private val service: AccessibilityService,
    private val preferences: ShortFormPreferences,
    private val onBlocked: (ShortFormKind) -> Unit,
    private val onCaptured: (String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var lastCheckElapsed = 0L
    private var lastBackElapsed = 0L
    private var consecutiveBacks = 0
    private var pendingPackage: String? = null
    private var retries = 0
    private val pendingCheck = Runnable { pendingPackage?.let { check(it) } }

    fun onEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (ShortFormKind.entries.none { it.packageName == pkg }) return
        val state = preferences.state.value
        if (!state.blockReels && !state.blockShorts && state.captureAtWallMillis == null) return
        // Throttle: video playback fires many content events; check at most ~3 times per second,
        // and always once more shortly after the last event so a settled screen is never missed.
        val now = SystemClock.elapsedRealtime()
        pendingPackage = pkg
        retries = 0
        handler.removeCallbacks(pendingCheck)
        if (now - lastCheckElapsed >= CHECK_INTERVAL) check(pkg)
        else handler.postDelayed(pendingCheck, CHECK_INTERVAL - (now - lastCheckElapsed))
    }

    private fun check(pkg: String) {
        lastCheckElapsed = SystemClock.elapsedRealtime()
        // Right after a screen change the "active window" can still be the previous one, so look
        // the app's window up explicitly and retry a few times if it is not there yet.
        val root = findRoot(pkg)
        if (root == null) {
            if (retries++ < MAX_RETRIES) handler.postDelayed(pendingCheck, CHECK_INTERVAL)
            return
        }
        // Static screens send no further events; re-check once more so a Reels/Shorts that
        // finished loading after this check is still caught.
        if (retries++ < MAX_RETRIES) handler.postDelayed(pendingCheck, CHECK_INTERVAL * 2)
        val state = preferences.state.value
        val kind = ShortFormKind.entries.first { it.packageName == pkg }

        state.captureAtWallMillis?.let { at ->
            val wall = System.currentTimeMillis()
            when {
                wall - at > CAPTURE_EXPIRY -> preferences.clearCapture()
                wall >= at -> capture(root, kind)
                // A still screen sends no events, so wake up by ourselves when the capture is due.
                else -> handler.postDelayed(pendingCheck, at - wall + 100)
            }
        }

        val enabled = when (kind) {
            ShortFormKind.REELS -> state.blockReels
            ShortFormKind.SHORTS -> state.blockShorts
        }
        // While a capture is armed the user needs to reach Reels/Shorts, so blocking pauses until it is taken.
        if (!enabled || preferences.state.value.captureAtWallMillis != null) return
        if (!ShortFormDetector.matches(kind, collect(root))) {
            consecutiveBacks = 0
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastBackElapsed < BACK_SPACING) return
        consecutiveBacks = if (now - lastBackElapsed < 3_000) consecutiveBacks + 1 else 1
        lastBackElapsed = now
        // If Back keeps landing on Reels/Shorts (e.g. opened from a link), leave to the home screen.
        service.performGlobalAction(
            if (consecutiveBacks >= 3) AccessibilityService.GLOBAL_ACTION_HOME else AccessibilityService.GLOBAL_ACTION_BACK,
        )
        preferences.recordBlock()
        onBlocked(kind)
    }

    private fun findRoot(pkg: String): AccessibilityNodeInfo? = runCatching {
        service.rootInActiveWindow?.takeIf { it.packageName?.toString() == pkg }
            ?: service.windows.orEmpty()
                .filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
                .firstNotNullOfOrNull { window -> window.root?.takeIf { it.packageName?.toString() == pkg } }
    }.getOrNull()

    /** Bounded breadth-first walk; only ids, accessibility labels and the selected flag are read. */
    private fun collect(root: AccessibilityNodeInfo): Sequence<ScreenNode> = sequence {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            yield(ScreenNode(node.viewIdResourceName, node.contentDescription?.toString() ?: node.text?.toString()
                ?.takeIf { it.length <= 20 }, node.isSelected))
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
    }

    /**
     * Writes the element tree (ids, class names, short labels, selected flag, position) so the
     * detector can be tuned to the user's exact app versions. Long texts such as messages are left out.
     */
    private fun capture(root: AccessibilityNodeInfo, kind: ShortFormKind) {
        val builder = StringBuilder()
        val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(Instant.now().atZone(ZoneId.systemDefault()))
        val version = runCatching {
            service.packageManager.getPackageInfo(kind.packageName, 0).versionName
        }.getOrNull()
        builder.append("Doom Blocker ekran yapısı kaydı\n")
        builder.append("Uygulama: ${kind.label} (${kind.packageName}) sürüm $version\n")
        builder.append("Zaman: $time\n")
        builder.append("Tespit sonucu: ${if (ShortFormDetector.matches(kind, collect(root))) "Reels/Shorts algılandı" else "algılanmadı"}\n\n")
        val rect = Rect()
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 40 || builder.length > 400_000) return
            node.getBoundsInScreen(rect)
            val label = (node.contentDescription ?: node.text)?.toString()?.replace('\n', ' ')
            builder.append("  ".repeat(depth))
                .append(node.className?.toString()?.substringAfterLast('.') ?: "?")
            node.viewIdResourceName?.let { builder.append(" id=").append(it.substringAfter(":id/")) }
            if (label != null) builder.append(" label=\"").append(if (label.length <= 30) label else "…(${label.length} karakter)").append('"')
            if (node.isSelected) builder.append(" SELECTED")
            if (node.isClickable) builder.append(" clickable")
            builder.append(" [").append(rect.left).append(',').append(rect.top).append(',')
                .append(rect.right).append(',').append(rect.bottom).append("]\n")
            for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        val label = "${kind.label} · ${DateTimeFormatter.ofPattern("HH:mm").format(Instant.now().atZone(ZoneId.systemDefault()))}"
        preferences.saveCapture(label, builder.toString())
        onCaptured(label)
    }

    fun stop() = handler.removeCallbacksAndMessages(null)

    private companion object {
        const val CHECK_INTERVAL = 300L
        const val BACK_SPACING = 700L
        const val MAX_NODES = 1_500
        const val CAPTURE_EXPIRY = 120_000L
        const val MAX_RETRIES = 4
    }
}
