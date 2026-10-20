package de.visionlab.visionpush

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [UrlSecurity], the shared scheme allowlist used by the notification
 * deep-link path, the in-app-message action path AND the model layer's parse-time
 * vetting. Robolectric provides a working `android.net.Uri.parse`.
 */
@RunWith(RobolectricTestRunner::class)
class UrlSecurityTest {

    @Test
    fun blocksCodeExecutingAndPrivateDataSchemes() {
        assertNull(UrlSecurity.safeUri("javascript:alert(1)"))
        assertNull(UrlSecurity.safeUri("data:text/html,<h1>x</h1>"))
        assertNull(UrlSecurity.safeUri("vbscript:msgbox(1)"))
        assertNull(UrlSecurity.safeUri("content://com.other.app/secret/1"))
        assertNull(UrlSecurity.safeUri("file:///data/data/com.host/private"))
    }

    @Test
    fun rejectsSchemelessUrl() {
        // No scheme → cannot be safely ACTION_VIEW'd.
        assertNull(UrlSecurity.safeUri("/just/a/path"))
        assertNull(UrlSecurity.safeUri(""))
    }

    @Test
    fun allowsHttpAndCustomAppSchemes() {
        assertNotNull(UrlSecurity.safeUri("https://example.com/path"))
        assertNotNull(UrlSecurity.safeUri("http://example.com"))
        assertNotNull(UrlSecurity.safeUri("myapp://open?id=1"))
    }

    @Test
    fun safeDeepLink_returnsTheOriginalStringOrNull() {
        assertEquals("myapp://open?id=1", UrlSecurity.safeDeepLink("myapp://open?id=1"))
        assertEquals("https://example.com/p", UrlSecurity.safeDeepLink("https://example.com/p"))
        assertNull(UrlSecurity.safeDeepLink("javascript:alert(1)"))
        assertNull(UrlSecurity.safeDeepLink("file:///etc/passwd"))
    }

    @Test
    fun safeHttpUrl_allowsOnlyHttpAndHttps() {
        assertEquals("https://example.com/a.png", UrlSecurity.safeHttpUrl("https://example.com/a.png"))
        assertEquals("http://example.com/a.png", UrlSecurity.safeHttpUrl("http://example.com/a.png"))
        assertEquals("HTTPS://example.com/a.png", UrlSecurity.safeHttpUrl("HTTPS://example.com/a.png"))
        assertNull(UrlSecurity.safeHttpUrl("data:image/png;base64,abc"))
        assertNull(UrlSecurity.safeHttpUrl("file:///sdcard/a.png"))
        assertNull(UrlSecurity.safeHttpUrl("content://media/1"))
        assertNull(UrlSecurity.safeHttpUrl("myapp://img")) // custom schemes are not downloadable
        assertNull(UrlSecurity.safeHttpUrl("/relative/path.png"))
        assertNull(UrlSecurity.safeHttpUrl(""))
    }
}
