package de.visionlab.visionpush

import android.app.Application
import org.robolectric.RuntimeEnvironment
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * InAppMessageController behavior tests running under Robolectric so that
 * Handler/Looper and Application lifecycle APIs are available on the JVM.
 *
 * present() fires onImpression synchronously before posting UI work to the
 * main looper, so all callback assertions complete without advancing the looper.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class InAppMessageControllerTest {

    private lateinit var controller: InAppMessageController

    @Before
    fun setup() {
        val app = RuntimeEnvironment.getApplication()
        controller = InAppMessageController(app)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeMessage(id: String, triggerType: String, eventName: String? = null): InAppMessage {
        val trigger = JSONObject().apply {
            put("type", triggerType)
            if (eventName != null) put("value", eventName)
        }
        val json = JSONObject().apply {
            put("id", id)
            put("type", "modal")
            put("triggers", JSONArray().apply { put(trigger) })
        }
        return requireNotNull(InAppMessage.fromJson(json))
    }

    // ── Queue management ──────────────────────────────────────────────────────

    @Test
    fun ingest_addsMessagesToQueue() {
        val msg = makeMessage("m1", "manual")
        controller.ingest(listOf(msg))
        assertEquals(1, controller.queuedMessageCount)
    }

    @Test
    fun ingest_multipleMessages_allAdded() {
        controller.ingest(listOf(
            makeMessage("m1", "manual"),
            makeMessage("m2", "manual"),
            makeMessage("m3", "manual")
        ))
        assertEquals(3, controller.queuedMessageCount)
    }

    @Test
    fun queueCount_isZeroInitially() {
        assertEquals(0, controller.queuedMessageCount)
    }

    // ── triggerSession ────────────────────────────────────────────────────────

    @Test
    fun triggerSession_firesImpressionForSessionMessages() {
        val presentedIds = mutableListOf<String>()
        controller.onImpression = { id -> presentedIds.add(id) }

        controller.ingest(listOf(makeMessage("m1", "on_session")))
        controller.triggerSession()

        assertEquals(listOf("m1"), presentedIds)
    }

    @Test
    fun triggerSession_doesNotFireForNonSessionMessages() {
        val presentedIds = mutableListOf<String>()
        controller.onImpression = { id -> presentedIds.add(id) }

        controller.ingest(listOf(
            makeMessage("m1", "on_notification_open"),
            makeMessage("m2", "manual")
        ))
        controller.triggerSession()

        assertTrue(presentedIds.isEmpty())
    }

    @Test
    fun triggerSession_removesMessageFromQueue() {
        controller.ingest(listOf(makeMessage("m1", "on_session")))
        assertEquals(1, controller.queuedMessageCount)

        controller.triggerSession()

        assertEquals(0, controller.queuedMessageCount)
    }

    @Test
    fun triggerSession_firesFirstEligibleMessage_showsOneAtATime() {
        // The controller only presents one message at a time.
        // The second session-message is re-queued because isPresenting is true.
        val presentedIds = mutableListOf<String>()
        controller.onImpression = { id -> presentedIds.add(id) }

        controller.ingest(listOf(
            makeMessage("m1", "on_session"),
            makeMessage("m2", "on_session"),
            makeMessage("m3", "on_notification_open")
        ))
        controller.triggerSession()

        assertEquals(listOf("m1"), presentedIds)
    }

    // ── triggerNotificationOpen ───────────────────────────────────────────────

    @Test
    fun triggerNotificationOpen_firesImpressionForMatchingMessages() {
        val presentedIds = mutableListOf<String>()
        controller.onImpression = { id -> presentedIds.add(id) }

        controller.ingest(listOf(makeMessage("m1", "on_notification_open")))
        controller.triggerNotificationOpen()

        assertEquals(listOf("m1"), presentedIds)
    }

    @Test
    fun triggerNotificationOpen_doesNotFireForSessionMessages() {
        val presentedIds = mutableListOf<String>()
        controller.onImpression = { id -> presentedIds.add(id) }

        controller.ingest(listOf(makeMessage("m1", "on_session")))
        controller.triggerNotificationOpen()

        assertTrue(presentedIds.isEmpty())
    }

    // ── triggerCustomEvent ────────────────────────────────────────────────────

    @Test
    fun triggerCustomEvent_firesImpressionForMatchingEvent() {
        val presentedIds = mutableListOf<String>()
        controller.onImpression = { id -> presentedIds.add(id) }

        controller.ingest(listOf(makeMessage("m1", "custom_event", "purchase_completed")))
        controller.triggerCustomEvent("purchase_completed")

        assertEquals(listOf("m1"), presentedIds)
    }

    @Test
    fun triggerCustomEvent_doesNotFireForDifferentEventName() {
        val presentedIds = mutableListOf<String>()
        controller.onImpression = { id -> presentedIds.add(id) }

        controller.ingest(listOf(makeMessage("m1", "custom_event", "purchase_completed")))
        controller.triggerCustomEvent("tutorial_finished")

        assertTrue(presentedIds.isEmpty())
    }

    @Test
    fun triggerCustomEvent_removesMatchingMessageFromQueue() {
        controller.ingest(listOf(
            makeMessage("m1", "custom_event", "event_a"),
            makeMessage("m2", "custom_event", "event_b")
        ))
        controller.triggerCustomEvent("event_a")

        assertEquals(1, controller.queuedMessageCount)
    }

    // ── presentMessage ────────────────────────────────────────────────────────

    @Test
    fun presentMessage_firesImpressionForRequestedId() {
        var impressionId: String? = null
        controller.onImpression = { id -> impressionId = id }

        controller.ingest(listOf(
            makeMessage("m1", "manual"),
            makeMessage("m2", "manual")
        ))
        controller.presentMessage("m2")

        assertEquals("m2", impressionId)
    }

    @Test
    fun presentMessage_onlyRemovesRequestedMessage() {
        controller.ingest(listOf(
            makeMessage("m1", "manual"),
            makeMessage("m2", "manual")
        ))
        controller.presentMessage("m1")

        assertEquals(1, controller.queuedMessageCount)
    }

    @Test
    fun presentMessage_doesNothingForUnknownId() {
        val presentedIds = mutableListOf<String>()
        controller.onImpression = { id -> presentedIds.add(id) }

        controller.ingest(listOf(makeMessage("m1", "manual")))
        controller.presentMessage("nonexistent")

        assertTrue(presentedIds.isEmpty())
        assertEquals(1, controller.queuedMessageCount)
    }

    // ── Impression callback ───────────────────────────────────────────────────

    @Test
    fun impressionCallback_notFiredWhenQueueIsEmpty() {
        val presentedIds = mutableListOf<String>()
        controller.onImpression = { id -> presentedIds.add(id) }

        controller.triggerSession()

        assertTrue(presentedIds.isEmpty())
    }

    @Test
    fun impressionCallback_canBeCleared() {
        val presentedIds = mutableListOf<String>()
        controller.onImpression = { id -> presentedIds.add(id) }
        controller.onImpression = null

        controller.ingest(listOf(makeMessage("m1", "on_session")))
        controller.triggerSession()

        assertTrue(presentedIds.isEmpty())
    }

    // ── VisionPushNotification URL validation (requires Android runtime) ──────

    @Test
    fun visionPushNotification_allowsHttpsImageUrl() {
        val payload = """{"id":"n1","title":"T","image_url":"https://example.com/img.jpg"}"""
        val n = VisionPushNotification.fromFcmData(mapOf("vp_payload" to payload))
        assertEquals("https://example.com/img.jpg", n?.imageUrl)
    }

    @Test
    fun visionPushNotification_blocksDataSchemeImageUrl() {
        val payload = """{"id":"n2","title":"T","image_url":"data:image/png;base64,abc"}"""
        val n = VisionPushNotification.fromFcmData(mapOf("vp_payload" to payload))
        assertNull(n?.imageUrl)
    }

    @Test
    fun visionPushNotification_allowsCustomSchemeDeepLink() {
        val payload = """{"id":"n3","title":"T","deep_link":"myapp://home"}"""
        val n = VisionPushNotification.fromFcmData(mapOf("vp_payload" to payload))
        assertEquals("myapp://home", n?.deepLink)
    }

    @Test
    fun visionPushNotification_blocksJavascriptDeepLink() {
        val payload = """{"id":"n4","title":"T","deep_link":"javascript:alert(1)"}"""
        val n = VisionPushNotification.fromFcmData(mapOf("vp_payload" to payload))
        assertNull(n?.deepLink)
    }

    @Test
    fun visionPushNotification_blocksFileSchemeDeepLink() {
        val payload = """{"id":"n5","title":"T","deep_link":"file:///etc/passwd"}"""
        val n = VisionPushNotification.fromFcmData(mapOf("vp_payload" to payload))
        assertNull(n?.deepLink)
    }
}
