package de.visionlab.visionpush

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * URL-scheme vetting of server-supplied notification payloads, run under Robolectric
 * so `android.net.Uri.parse` is REAL.
 *
 * These deliberately pair every negative assertion with a POSITIVE control: on the plain
 * JVM `Uri` is a stub, every URL parses to `null`, and an `assertNull(deepLink)` would
 * pass with the blocklist deleted. Here an `https` URL must SURVIVE parsing for the
 * blocked-scheme assertions to mean anything.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationUrlSecurityTest {

    private fun parse(payload: String): VisionPushNotification =
        requireNotNull(VisionPushNotification.fromFcmData(mapOf("vp_payload" to payload))) { "payload must parse" }

    private fun withDeepLink(url: String) = parse("""{"id":"n","title":"T","deep_link":"$url"}""")
    private fun withImage(url: String) = parse("""{"id":"n","title":"T","image_url":"$url"}""")
    private fun withLargeIcon(url: String) = parse("""{"id":"n","title":"T","large_icon_url":"$url"}""")
    private fun withActionDeepLink(url: String) = parse(
        """{"id":"n","title":"T","actions":[{"action_id":"a1","title":"Go","deep_link":"$url"}]}"""
    )

    // ── Positive controls ─────────────────────────────────────────────────────

    @Test
    fun keepsHttpsAndHttpImageUrls() {
        assertEquals("https://example.com/img.jpg", withImage("https://example.com/img.jpg").imageUrl)
        assertEquals("http://example.com/img.jpg", withImage("http://example.com/img.jpg").imageUrl)
        assertEquals("https://cdn.example.com/icon.png", withLargeIcon("https://cdn.example.com/icon.png").largeIconUrl)
    }

    @Test
    fun keepsHttpsAndCustomSchemeDeepLinks() {
        assertEquals("https://example.com/path?x=1", withDeepLink("https://example.com/path?x=1").deepLink)
        assertEquals("myapp://home", withDeepLink("myapp://home").deepLink)
        assertEquals("myapp://open?id=1", withActionDeepLink("myapp://open?id=1").actions.single().deepLink)
    }

    @Test
    fun schemeComparisonIsCaseInsensitive() {
        assertEquals("HTTPS://example.com/a.png", withImage("HTTPS://example.com/a.png").imageUrl)
        assertNull(withDeepLink("JavaScript:alert(1)").deepLink)
    }

    // ── Deep links: blocked schemes ───────────────────────────────────────────

    @Test
    fun blocksJavascriptDeepLink() {
        val n = withDeepLink("javascript:alert(1)")
        assertEquals("n", n.id) // the notification itself still parses
        assertNull(n.deepLink)
    }

    @Test
    fun blocksFileSchemeDeepLink() {
        assertNull(withDeepLink("file:///etc/passwd").deepLink)
    }

    @Test
    fun blocksDataContentAndVbscriptDeepLinks() {
        assertNull(withDeepLink("data:text/html,<h1>x</h1>").deepLink)
        assertNull(withDeepLink("content://com.other.app/secret/1").deepLink)
        assertNull(withDeepLink("vbscript:msgbox(1)").deepLink)
    }

    @Test
    fun blocksSchemelessDeepLink() {
        assertNull(withDeepLink("/just/a/path").deepLink)
    }

    @Test
    fun blocksJavascriptActionDeepLinkButKeepsTheAction() {
        val action = withActionDeepLink("javascript:alert(1)").actions.single()
        assertEquals("a1", action.id)
        assertEquals("Go", action.title)
        assertNull(action.deepLink)
    }

    // ── Image URLs: only http(s) ──────────────────────────────────────────────

    @Test
    fun blocksDataUrlInImageUrl() {
        assertNull(withImage("data:image/png;base64,abc").imageUrl)
    }

    @Test
    fun blocksFileContentAndCustomSchemeImageUrls() {
        assertNull(withImage("file:///sdcard/x.png").imageUrl)
        assertNull(withImage("content://media/external/images/1").imageUrl)
        // Custom app schemes are fine for deep links but not for a download.
        assertNull(withImage("myapp://img").imageUrl)
        assertNull(withLargeIcon("javascript:alert(1)").largeIconUrl)
    }

    // ── Single implementation ─────────────────────────────────────────────────

    @Test
    fun parseTimeAndLaunchTimeDecisionsAgree() {
        // The model's deep-link vetting and the ACTION_VIEW launch path both delegate to
        // UrlSecurity — the two blocklists that used to diverge can no longer disagree.
        val urls = listOf(
            "https://example.com", "http://example.com", "myapp://x", "mailto:a@b.c",
            "javascript:alert(1)", "data:text/html,x", "vbscript:x", "content://a/b", "file:///x", "/no/scheme", "",
        )
        for (url in urls) {
            val parsed = if (url.isEmpty()) null else withDeepLink(url).deepLink
            val launch = UrlSecurity.safeUri(url)
            assertEquals("url=$url", launch != null, parsed != null)
        }
        assertNotNull(UrlSecurity.safeUri("https://example.com"))
    }
}
