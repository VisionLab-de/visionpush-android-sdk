package de.visionlab.visionpush

import android.app.Activity
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * HTML in-app-message renderer tests (US-16 "Isolation → Android"). The WebView
 * shadow doesn't execute JS, so "navigation blocked" and "impression after
 * onPageFinished" are exercised directly against the registered [HtmlIamWebViewClient]
 * (via `webView.webViewClient`), not a real page load.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class HtmlInAppMessageRendererTest {

    private lateinit var activity: Activity

    @Before
    fun setup() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    }

    private fun htmlMessage(id: String = "m1", htmlBody: String? = "<p>offer</p>"): InAppMessage {
        val json = JSONObject().apply {
            put("id", id)
            put("type", "html")
            if (htmlBody != null) put("html_body", htmlBody)
        }
        return requireNotNull(InAppMessage.fromJson(json))
    }

    // ── WebView hardening ───────────────────────────────────────────────────

    @Test
    fun hardenHtmlIamWebView_setsExactlyTheContractSettings() {
        val settings = mock<WebSettings>()

        hardenHtmlIamWebView(settings)

        verify(settings).setJavaScriptEnabled(true)
        verify(settings).setAllowFileAccess(false)
        verify(settings).setAllowContentAccess(false)
        verify(settings).setDomStorageEnabled(false)
        verify(settings).setGeolocationEnabled(false)
    }

    // ── Navigation ──────────────────────────────────────────────────────────

    @Test
    fun webViewClient_blocksEveryNavigation_bothOverloads() {
        val client = HtmlIamWebViewClient(onPageFinished = {})

        assertTrue(client.shouldOverrideUrlLoading(null, "https://example.com"))
        assertTrue(client.shouldOverrideUrlLoading(null, null as WebResourceRequest?))
    }

    @Test
    fun webViewClient_firesCallback_onPageFinished() {
        var impressions = 0
        val client = HtmlIamWebViewClient(onPageFinished = { impressions++ })

        client.onPageFinished(null, "about:blank")

        assertEquals(1, impressions)
    }

    // ── show() ──────────────────────────────────────────────────────────────

    @Test
    fun show_loadsTheShimPrependedToTheHtmlBody_withNoBaseUrl() {
        val renderer = HtmlInAppMessageRenderer(
            activity = activity,
            message = htmlMessage(htmlBody = "<p>offer</p>"),
            onImpression = {},
            onClick = {},
            onDismiss = {},
            onClosed = {}
        )

        assertTrue(renderer.show())
        val webView = requireNotNull(renderer.webView)
        val loaded = shadowOf(webView).lastLoadDataWithBaseURL

        assertNull(loaded.baseUrl)
        assertEquals("text/html", loaded.mimeType)
        assertEquals("utf-8", loaded.encoding)
        assertTrue(loaded.data.contains("window.VisionPush"))
        assertTrue(loaded.data.indexOf("window.VisionPush") < loaded.data.indexOf("<p>offer</p>"))
    }

    @Test
    fun show_countsTheImpressionOnlyAfterOnPageFinished() {
        var impressions = 0
        val renderer = HtmlInAppMessageRenderer(
            activity = activity,
            message = htmlMessage(),
            onImpression = { impressions++ },
            onClick = {},
            onDismiss = {},
            onClosed = {}
        )

        renderer.show()
        assertEquals("not shown yet — the page hasn't finished loading", 0, impressions)

        val webView = requireNotNull(renderer.webView)
        requireNotNull(webView.webViewClient).onPageFinished(webView, null)
        assertEquals(1, impressions)
    }

    @Test
    fun show_malformedMessage_withoutHtmlBody_returnsFalseAndShowsNothing() {
        val malformed = htmlMessage(htmlBody = null)
        val renderer = HtmlInAppMessageRenderer(
            activity = activity, message = malformed, onImpression = {}, onClick = {}, onDismiss = {}, onClosed = {}
        )

        assertFalse(renderer.show())
        assertNull(renderer.webView)
    }

    // ── close() ─────────────────────────────────────────────────────────────

    @Test
    fun close_removesTheJsInterfaceAndDestroysTheWebView() {
        var closedCount = 0
        val renderer = HtmlInAppMessageRenderer(
            activity = activity, message = htmlMessage(), onImpression = {}, onClick = {}, onDismiss = {},
            onClosed = { closedCount++ }
        )
        renderer.show()
        val webView = requireNotNull(renderer.webView)
        assertTrue(shadowOf(webView).getJavascriptInterface(HTML_IAM_BRIDGE_INTERFACE_NAME) != null)

        renderer.close()

        assertNull(shadowOf(webView).getJavascriptInterface(HTML_IAM_BRIDGE_INTERFACE_NAME))
        assertTrue(shadowOf(webView).wasDestroyCalled())
        assertEquals(1, closedCount)
        assertNull(renderer.webView)
    }

    @Test
    fun close_isIdempotent() {
        var closedCount = 0
        val renderer = HtmlInAppMessageRenderer(
            activity = activity, message = htmlMessage(), onImpression = {}, onClick = {}, onDismiss = {},
            onClosed = { closedCount++ }
        )
        renderer.show()

        renderer.close()
        renderer.close()

        assertEquals(1, closedCount)
    }

    // ── Bridge wired end-to-end through the renderer ───────────────────────

    @Test
    fun bridgeClick_recordsAndTearsDownThroughTheSameClosePath() {
        var clickedButtonId: String? = null
        var closedCount = 0
        val renderer = HtmlInAppMessageRenderer(
            activity = activity,
            message = htmlMessage(),
            onImpression = {},
            onClick = { id -> clickedButtonId = id },
            onDismiss = {},
            onClosed = { closedCount++ }
        )
        renderer.show()
        val webView = requireNotNull(renderer.webView)
        val bridge = shadowOf(webView).getJavascriptInterface(HTML_IAM_BRIDGE_INTERFACE_NAME) as HtmlIamBridge

        bridge.click("primary", null)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals("primary", clickedButtonId)
        assertEquals(1, closedCount)
        assertNull(shadowOf(webView).getJavascriptInterface(HTML_IAM_BRIDGE_INTERFACE_NAME))
    }

    @Test
    fun bridgeClose_recordsDismissAndTearsDown() {
        var dismissed = 0
        var closedCount = 0
        val renderer = HtmlInAppMessageRenderer(
            activity = activity,
            message = htmlMessage(),
            onImpression = {},
            onClick = {},
            onDismiss = { dismissed++ },
            onClosed = { closedCount++ }
        )
        renderer.show()
        val webView = requireNotNull(renderer.webView)
        val bridge = shadowOf(webView).getJavascriptInterface(HTML_IAM_BRIDGE_INTERFACE_NAME) as HtmlIamBridge

        bridge.close()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        assertEquals(1, dismissed)
        assertEquals(1, closedCount)
    }
}
