package de.visionlab.visionpush

import android.net.Uri

/**
 * Scheme vetting for every server-supplied URL the SDK acts on — the ONE
 * implementation. Notification deep links, per-action deep links and in-app-message
 * button actions (all `ACTION_VIEW`'d) go through [safeUri] / [safeDeepLink]; remote
 * image URLs (downloaded over HTTP) go through [safeHttpUrl].
 *
 * The model layer ([VisionPushNotification.fromJson]) and the launch paths
 * ([NotificationTracker], [InAppMessageController]) previously kept two divergent
 * blocklists; a scheme added to one was silently missing from the other. Keep every
 * rule here so the parse-time and launch-time decisions can never disagree.
 */
internal object UrlSecurity {
    /**
     * Schemes that can execute code or access another app's private data — never
     * `ACTION_VIEW`'d from a server-controlled URL.
     */
    internal val blockedSchemes: Set<String> = setOf("javascript", "data", "vbscript", "content", "file")

    /**
     * Parse [url] and return its [Uri] only if its scheme is safe to `ACTION_VIEW`;
     * `null` for an unparseable, scheme-less, or blocked URL.
     */
    fun safeUri(url: String): Uri? {
        val uri = try {
            Uri.parse(url)
        } catch (e: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase()
        return if (scheme == null || blockedSchemes.contains(scheme)) null else uri
    }

    /** The original [url] string when [safeUri] accepts it, else `null`. */
    fun safeDeepLink(url: String): String? = safeUri(url)?.let { url }

    /**
     * Returns [url] only if its scheme is `http` or `https` — the only schemes the SDK
     * downloads images from. Everything else (`data:`, `file:`, `content:`, custom app
     * schemes, scheme-less strings) yields `null`.
     */
    fun safeHttpUrl(url: String): String? {
        val scheme = try {
            Uri.parse(url).scheme?.lowercase()
        } catch (e: Exception) {
            return null
        }
        return if (scheme == "http" || scheme == "https") url else null
    }
}
