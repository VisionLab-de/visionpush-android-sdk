package de.visionlab.visionpush

import android.app.Activity
import android.app.Dialog
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.VisibleForTesting

/** The interface name `addJavascriptInterface`/`removeJavascriptInterface` use, and the
 *  name the injected shim reads `window.VisionPush` off of. */
internal const val HTML_IAM_BRIDGE_INTERFACE_NAME = "VisionPushNativeBridge"

/**
 * Renders a `type = "html"` in-app message (US-16) in a hardened [WebView] — the
 * sibling of [InAppMessageController]'s native modal/banner/fullscreen rendering
 * (SQ-262), used only for the `html` message type. The WebView hardening, the bridge
 * it exposes and the navigation lockdown all follow the US-16 story contract's
 * "Isolation → Android" section exactly.
 */
internal class HtmlInAppMessageRenderer(
    private val activity: Activity,
    private val message: InAppMessage,
    private val onImpression: () -> Unit,
    private val onClick: (buttonId: String) -> Unit,
    private val onDismiss: () -> Unit,
    private val onClosed: () -> Unit,
) {
    @VisibleForTesting
    internal var webView: WebView? = null
    private var dialog: Dialog? = null
    private var closed = false

    /** Builds, hardens and shows the WebView. Returns `false` (nothing shown) only for
     *  a malformed message (no `html_body`) or if the window can't be attached. */
    fun show(): Boolean {
        val htmlBody = message.htmlBody ?: return false

        val webView = WebView(activity)
        hardenHtmlIamWebView(webView.settings)

        val bridge = HtmlIamBridge(
            activity = activity,
            recordClick = onClick,
            recordDismiss = onDismiss,
            dismiss = ::close,
            onAddTags = { tags -> VisionPush.updateTags(tags) },
            onRemoveTags = { keys -> VisionPush.removeTags(keys) },
        )
        // The ONLY thing the HTML content can reach — no cookies, no storage, no other
        // native API (US-16 bridge protocol).
        webView.addJavascriptInterface(bridge, HTML_IAM_BRIDGE_INTERFACE_NAME)
        webView.webViewClient = HtmlIamWebViewClient(onPageFinished = onImpression)

        // loadDataWithBaseURL(null, …): no baseURL, so the content can't resolve
        // relative file:// paths even if allowFileAccess were ever re-enabled.
        webView.loadDataWithBaseURL(null, buildHtmlIamShim() + htmlBody, "text/html", "utf-8", null)

        val dlg = Dialog(activity).apply {
            setContentView(webView)
            setOnDismissListener { close() }
        }

        return try {
            dlg.show()
            this.webView = webView
            this.dialog = dlg
            true
        } catch (e: Exception) {
            // e.g. WindowManager.BadTokenException when the activity is going away.
            VisionPushLogger.w(TAG, "Presenting HTML in-app message ${message.id} failed: ${e.message}")
            false
        }
    }

    /**
     * Idempotent teardown: removes the JS interface, destroys the WebView, dismisses
     * the dialog, then notifies [onClosed] exactly once — whether this was reached via
     * the bridge's `click`/`close`, a system back-press dismissing the dialog, or
     * [InAppMessageController.dismissCurrent].
     */
    fun close() {
        if (closed) return
        closed = true
        val dlg = dialog
        dialog = null
        dlg?.setOnDismissListener(null)
        if (dlg?.isShowing == true) dlg.dismiss()
        webView?.removeJavascriptInterface(HTML_IAM_BRIDGE_INTERFACE_NAME)
        webView?.destroy()
        webView = null
        onClosed()
    }

    private companion object {
        const val TAG = "VP.HtmlInAppMessage"
    }
}

/**
 * US-16 "Isolation → Android": the WebView hardening exactly as the contract
 * specifies — `allowFileAccess=false`, `allowContentAccess=false`,
 * `setGeolocationEnabled(false)`, `domStorageEnabled=false`.
 */
internal fun hardenHtmlIamWebView(settings: WebSettings) {
    // Not a contract bullet — just the functional prerequisite for `window.VisionPush`
    // (and therefore every bullet below) to exist at all.
    settings.javaScriptEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.domStorageEnabled = false
    @Suppress("DEPRECATION") // no-op since API 24, but still the pinned contract's call
    settings.setGeolocationEnabled(false)
}

/**
 * US-16: "`shouldOverrideUrlLoading` returns true for every navigation" — the rendered
 * HTML can never navigate the WebView itself; the only way out is the bridge.
 */
internal class HtmlIamWebViewClient(
    private val onPageFinished: () -> Unit
) : WebViewClient() {
    @Suppress("DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true

    /** US-16: "Count the impression after `onPageFinished`." */
    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        onPageFinished()
    }
}

/**
 * US-16: "The shim is injected before the HTML (prepended to the document) and
 * defines `window.VisionPush` on top of the interface object." Every call is
 * wrapped so that HTML content calling a bridge method with the wrong argument
 * shape never throws back into the page.
 */
internal fun buildHtmlIamShim(): String = """
    <script>
    (function () {
      var bridge = window.$HTML_IAM_BRIDGE_INTERFACE_NAME;
      window.VisionPush = {
        click: function (actionId, url) {
          if (!bridge) return;
          try { bridge.click(String(actionId), url ? String(url) : ""); } catch (e) {}
        },
        close: function () {
          if (!bridge) return;
          try { bridge.close(); } catch (e) {}
        },
        addTags: function (tags) {
          if (!bridge) return;
          try { bridge.addTags(JSON.stringify(tags || {})); } catch (e) {}
        },
        removeTags: function (keys) {
          if (!bridge) return;
          try { bridge.removeTags(JSON.stringify(keys || [])); } catch (e) {}
        }
      };
    })();
    </script>
""".trimIndent()
