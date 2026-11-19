package dev.personal.doomblocker.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Closes the "open it in the browser instead" loophole. When a supported browser shows a site
 * that belongs to a protected app (tiktok.com → TikTok, x.com → X …), the service treats the
 * browser as that app, so the very same quota, breaks, schedules and focus rules apply.
 * Only the address bar is read and only the host name is compared; nothing is stored.
 */
internal object BrowserSites {
    /** Browser package → address-bar view ids (several, because browsers rename them between versions). */
    private val urlBarIds = mapOf(
        "com.android.chrome" to listOf("url_bar"),
        "com.chrome.beta" to listOf("url_bar"),
        "com.sec.android.app.sbrowser" to listOf("location_bar_edit_text", "url_bar_text"),
        "com.sec.android.app.sbrowser.beta" to listOf("location_bar_edit_text", "url_bar_text"),
        "org.mozilla.firefox" to listOf("mozac_browser_toolbar_url_view", "url_bar_title"),
        "com.microsoft.emmx" to listOf("url_bar"),
        "com.brave.browser" to listOf("url_bar"),
        "com.opera.browser" to listOf("url_field"),
        "com.opera.mini.native" to listOf("url_field"),
        "com.duckduckgo.mobile.android" to listOf("omnibarTextInput"),
    )

    /** Site → app packages it stands for (the first one that is protected wins). */
    private val sites = listOf(
        listOf("tiktok.com") to listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
        listOf("instagram.com") to listOf("com.instagram.android"),
        listOf("x.com", "twitter.com") to listOf("com.twitter.android"),
        listOf("youtube.com", "youtu.be") to listOf("com.google.android.youtube"),
        listOf("facebook.com", "fb.com") to listOf("com.facebook.katana"),
        listOf("reddit.com") to listOf("com.reddit.frontpage"),
        listOf("snapchat.com") to listOf("com.snapchat.android"),
        listOf("pinterest.com") to listOf("com.pinterest"),
        listOf("twitch.tv") to listOf("tv.twitch.android.app"),
        listOf("threads.net", "threads.com") to listOf("com.instagram.barcelona"),
    )

    fun isBrowser(packageName: String?) = packageName != null && packageName in urlBarIds

    /** Reads the browser's address bar and returns the protected app package it maps to, if any. */
    fun protectedPackageFor(
        service: AccessibilityService,
        browserPackage: String,
        root: AccessibilityNodeInfo?,
        protectedPackages: Set<String>,
    ): String? {
        val ids = urlBarIds[browserPackage] ?: return null
        val window = root ?: return null
        val url = ids.firstNotNullOfOrNull { id ->
            runCatching { window.findAccessibilityNodeInfosByViewId("$browserPackage:id/$id") }.getOrNull()
                // refresh(): the accessibility cache can still hold the previous address.
                ?.firstNotNullOfOrNull { node -> runCatching { node.refresh() }; node.text?.toString()?.takeIf(String::isNotBlank) }
        } ?: return null
        return packageForUrl(url, protectedPackages)
    }

    /** Pure mapping, unit-testable: "https://m.tiktok.com/@x" → TikTok when TikTok is protected. */
    fun packageForUrl(url: String, protectedPackages: Set<String>): String? {
        val host = url.trim().lowercase()
            .substringAfter("://")
            .substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
            .substringAfter('@')
        if (host.isBlank() || ' ' in host || '.' !in host) return null
        return sites.firstNotNullOfOrNull { (domains, packages) ->
            if (domains.any { host == it || host.endsWith(".$it") }) packages.firstOrNull { it in protectedPackages } else null
        }
    }
}
