package de.visionlab.visionpush

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `window.VisionPush`'s native half (US-16 "Bridge protocol") — exactly 4
 * [JavascriptInterface] methods, every parameter a bare or JSON-encoded [String]; no
 * object/array parameter types are ever exposed to the JS side, and there is no other
 * API surface (no cookies, no storage, no native SDK access beyond these 4 calls).
 *
 * The rendered HTML is untrusted, campaign-authored content with no server-side
 * sanitising — isolation is the WebView sandbox, not input validation — so every
 * method below rejects anything that isn't exactly the expected shape rather than
 * throwing: [android.webkit.WebView] invokes `@JavascriptInterface` methods on its own
 * background thread, and an uncaught exception there would crash the host app.
 *
 * All side effects (click/dismiss recording, [VisionPush] tag updates, tearing down
 * the WebView) are posted to the main thread, per the contract's "Bridge calls are
 * marshalled onto the main thread". [activity] is read only on that posted main-thread
 * work, to resolve a clicked URL's `ACTION_VIEW` intent.
 *
 * Each display tracks at most ONE terminal event — a click or a dismiss, never both
 * and never more than once (SQ-288/SQ-290): campaign HTML calling `close()` several
 * times, or `click()` then `close()`, must not inflate the stats. [terminalEventFired]
 * is set atomically BEFORE the tracking call, on whatever thread the call arrives on
 * (WebView's JavaBridge thread, per the class doc above — not the main thread the
 * tracking itself is posted to), so two racing calls still record exactly one event.
 * Once it fires, every further bridge call — including `addTags`/`removeTags` — is
 * ignored: there is nothing left to tag once the display is terminal.
 */
internal class HtmlIamBridge(
    private val activity: Activity,
    private val recordClick: (buttonId: String) -> Unit,
    private val recordDismiss: () -> Unit,
    private val dismiss: () -> Unit,
    private val onAddTags: (Map<String, String>) -> Unit,
    private val onRemoveTags: (List<String>) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val terminalEventFired = AtomicBoolean(false)

    /**
     * Records a click with `button_id = actionId`. For an http(s) [url] (same rule as
     * the server's `validate_url_scheme`), fires an `ACTION_VIEW` intent. Then closes
     * the message — a click is never also counted as a dismiss.
     *
     * A missing/blank `actionId` is an invalid payload and the whole call is ignored
     * (without consuming the once-per-display terminal event). Ignored entirely once
     * a terminal event already fired for this display.
     */
    @JavascriptInterface
    fun click(actionId: String?, url: String?) {
        val buttonId = actionId?.takeIf { it.isNotBlank() } ?: return
        if (!terminalEventFired.compareAndSet(false, true)) return
        val targetUrl = url?.takeIf { it.isNotBlank() }
        mainHandler.post {
            recordClick(buttonId)
            targetUrl?.let { openUrl(it) }
            dismiss()
        }
    }

    /** Records a dismiss, then closes the message — at most once per display. */
    @JavascriptInterface
    fun close() {
        if (!terminalEventFired.compareAndSet(false, true)) return
        mainHandler.post {
            recordDismiss()
            dismiss()
        }
    }

    /**
     * Delegates to the existing [VisionPush.updateTags]. [tagsJson] must decode to a
     * flat JSON object of string values; anything else (invalid JSON, nested
     * objects/arrays, non-string values) is an unknown payload and is ignored. Also
     * ignored once this display's terminal event (click/dismiss) has already fired.
     */
    @JavascriptInterface
    fun addTags(tagsJson: String?) {
        if (terminalEventFired.get()) return
        val obj = tagsJson?.let(::parseJsonObjectOrNull) ?: return
        val tags = mutableMapOf<String, String>()
        obj.keys().forEach { key -> (obj.opt(key) as? String)?.let { tags[key] = it } }
        if (tags.isEmpty()) return
        mainHandler.post { onAddTags(tags) }
    }

    /**
     * Delegates to the existing [VisionPush.removeTags]. [keysJson] must decode to a
     * flat JSON array of strings; anything else is an unknown payload and is ignored.
     * Also ignored once this display's terminal event (click/dismiss) has already fired.
     */
    @JavascriptInterface
    fun removeTags(keysJson: String?) {
        if (terminalEventFired.get()) return
        val arr = keysJson?.let(::parseJsonArrayOrNull) ?: return
        val keys = (0 until arr.length())
            .mapNotNull { arr.opt(it) as? String }
            .filter { it.isNotBlank() }
        if (keys.isEmpty()) return
        mainHandler.post { onRemoveTags(keys) }
    }

    /** http(s)-only, same rule [UrlSecurity.safeHttpUrl] already applies to every other
     *  server-supplied URL the SDK opens — a bad scheme is silently ignored. */
    private fun openUrl(url: String) {
        val safeUrl = UrlSecurity.safeHttpUrl(url) ?: return
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl)))
        } catch (e: Exception) {
            VisionPushLogger.w(TAG, "Opening in-app-message click URL failed: ${e.message}")
        }
    }

    private fun parseJsonObjectOrNull(json: String): JSONObject? =
        try {
            JSONObject(json)
        } catch (e: Exception) {
            null
        }

    private fun parseJsonArrayOrNull(json: String): JSONArray? =
        try {
            JSONArray(json)
        } catch (e: Exception) {
            null
        }

    private companion object {
        const val TAG = "VP.HtmlIamBridge"
    }
}
