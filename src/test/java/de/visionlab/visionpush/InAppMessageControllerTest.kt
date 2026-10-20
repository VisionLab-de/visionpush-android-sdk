package de.visionlab.visionpush

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * InAppMessageController behaviour tests running under Robolectric so that
 * Handler/Looper, Activity windows and AlertDialog are available on the JVM.
 *
 * Presentation is posted to the main looper, so tests call [idle] to let it run.
 * `onImpression` fires only once a message is ACTUALLY on screen — never for an
 * attempt that could not present (no activity, finishing activity) — so every
 * impression assertion here needs a resumed activity plus an idled looper.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class InAppMessageControllerTest {

    private lateinit var controller: InAppMessageController
    private val impressions = mutableListOf<String>()
    private val dismissed = mutableListOf<String>()
    private val nativeClicks = mutableListOf<Pair<String, String>>() // messageId to buttonId
    private val nativeDismisses = mutableListOf<String>()

    @Before
    fun setup() {
        val app = RuntimeEnvironment.getApplication()
        controller = InAppMessageController(app)
        controller.onImpression = { id -> impressions.add(id) }
        controller.onDismissed = { msg -> dismissed.add(msg.id) }
        controller.onNativeClick = { msg, buttonId -> nativeClicks.add(msg.id to buttonId) }
        controller.onNativeDismiss = { msg -> nativeDismisses.add(msg.id) }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeMessage(
        id: String,
        triggerType: String,
        eventName: String? = null,
        type: String = "modal",
        dismissAfterSeconds: Double? = null,
        htmlBody: String? = null,
        primaryButtonId: String? = null,
    ): InAppMessage {
        val trigger = JSONObject().apply {
            put("type", triggerType)
            if (eventName != null) put("value", eventName)
        }
        val json = JSONObject().apply {
            put("id", id)
            put("type", type)
            put("triggers", JSONArray().apply { put(trigger) })
            if (dismissAfterSeconds != null) put("dismiss_after", dismissAfterSeconds)
            if (htmlBody != null) put("html_body", htmlBody)
            if (primaryButtonId != null) {
                put("primary_button", JSONObject().apply {
                    put("id", primaryButtonId)
                    put("text", "Go")
                })
            }
        }
        return requireNotNull(InAppMessage.fromJson(json))
    }

    /** Depth-first search for the first [Button] whose text matches [text]. */
    private fun findButtonWithText(root: View, text: String): Button? {
        if (root is Button && root.text.toString() == text) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findButtonWithText(root.getChildAt(i), text)?.let { return it }
            }
        }
        return null
    }

    /** Creates a resumed activity and tells the controller it is in the foreground. */
    private fun resumeActivity(): Activity {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        controller.onActivityResumed(activity)
        return activity
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    // ── Queue management ──────────────────────────────────────────────────────

    @Test
    fun ingest_addsMessagesToQueue() {
        controller.ingest(listOf(makeMessage("m1", "manual")))
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
        assertEquals(0, controller.deferredMessageCount)
    }

    // ── triggerSession ────────────────────────────────────────────────────────

    @Test
    fun triggerSession_billsImpressionOnlyOnceTheMessageIsOnScreen() {
        resumeActivity()
        controller.ingest(listOf(makeMessage("m1", "on_session")))
        controller.triggerSession()

        assertTrue("not on screen yet — presentation is posted", impressions.isEmpty())
        idle()
        assertEquals(listOf("m1"), impressions)
    }

    @Test
    fun triggerSession_doesNotFireForNonSessionMessages() {
        resumeActivity()
        controller.ingest(listOf(
            makeMessage("m1", "on_notification_open"),
            makeMessage("m2", "manual")
        ))
        controller.triggerSession()
        idle()

        assertTrue(impressions.isEmpty())
        assertEquals(2, controller.queuedMessageCount)
    }

    @Test
    fun triggerSession_removesMessageFromQueue() {
        controller.ingest(listOf(makeMessage("m1", "on_session")))
        assertEquals(1, controller.queuedMessageCount)

        controller.triggerSession()

        assertEquals(0, controller.queuedMessageCount)
    }

    @Test
    fun onlyOneMessageAtATime_secondIsDeferredUntilTheFirstIsDismissed() {
        resumeActivity()
        controller.ingest(listOf(
            makeMessage("m1", "on_session"),
            makeMessage("m2", "on_session"),
            makeMessage("m3", "on_notification_open")
        ))
        controller.triggerSession()
        idle()

        assertEquals(listOf("m1"), impressions)
        assertEquals("m2 waits behind m1", 1, controller.deferredMessageCount)
        assertEquals("m2 deferred + m3 waiting for its trigger", 2, controller.queuedMessageCount)

        // Dismissing m1 drains m2 — the re-queued message is presented, not stranded.
        controller.dismissCurrent()
        idle()

        assertEquals(listOf("m1", "m2"), impressions)
        assertEquals(listOf("m1"), dismissed)
        assertEquals(0, controller.deferredMessageCount)
        assertEquals(1, controller.queuedMessageCount)
    }

    // ── triggerNotificationOpen ───────────────────────────────────────────────

    @Test
    fun triggerNotificationOpen_firesImpressionForMatchingMessages() {
        resumeActivity()
        controller.ingest(listOf(makeMessage("m1", "on_notification_open")))
        controller.triggerNotificationOpen()
        idle()

        assertEquals(listOf("m1"), impressions)
    }

    @Test
    fun triggerNotificationOpen_doesNotFireForSessionMessages() {
        resumeActivity()
        controller.ingest(listOf(makeMessage("m1", "on_session")))
        controller.triggerNotificationOpen()
        idle()

        assertTrue(impressions.isEmpty())
    }

    // ── triggerCustomEvent ────────────────────────────────────────────────────

    @Test
    fun triggerCustomEvent_firesImpressionForMatchingEvent() {
        resumeActivity()
        controller.ingest(listOf(makeMessage("m1", "custom_event", "purchase_completed")))
        controller.triggerCustomEvent("purchase_completed")
        idle()

        assertEquals(listOf("m1"), impressions)
    }

    @Test
    fun triggerCustomEvent_doesNotFireForDifferentEventName() {
        resumeActivity()
        controller.ingest(listOf(makeMessage("m1", "custom_event", "purchase_completed")))
        controller.triggerCustomEvent("tutorial_finished")
        idle()

        assertTrue(impressions.isEmpty())
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
        resumeActivity()
        controller.ingest(listOf(
            makeMessage("m1", "manual"),
            makeMessage("m2", "manual")
        ))
        controller.presentMessage("m2")
        idle()

        assertEquals(listOf("m2"), impressions)
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
        resumeActivity()
        controller.ingest(listOf(makeMessage("m1", "manual")))
        controller.presentMessage("nonexistent")
        idle()

        assertTrue(impressions.isEmpty())
        assertEquals(1, controller.queuedMessageCount)
    }

    // ── Impressions are billed only for a real presentation ──────────────────

    @Test
    fun impression_notBilledWithoutAForegroundActivity() {
        controller.ingest(listOf(makeMessage("m1", "on_session")))
        controller.triggerSession()
        idle()

        assertTrue("nothing was shown → nothing billed", impressions.isEmpty())
        assertEquals("the message is parked, not lost", 1, controller.deferredMessageCount)
    }

    @Test
    fun deferredMessage_isPresentedWhenAnActivityResumes() {
        controller.ingest(listOf(makeMessage("m1", "on_session")))
        controller.triggerSession()
        idle()
        assertTrue(impressions.isEmpty())

        resumeActivity() // drains the deferred queue
        idle()

        assertEquals(listOf("m1"), impressions)
        assertEquals(0, controller.deferredMessageCount)
    }

    @Test
    fun impression_notBilledWhenTheActivityIsFinishing() {
        val activity = resumeActivity()
        activity.finish()
        controller.ingest(listOf(makeMessage("m1", "on_session")))
        controller.triggerSession()
        idle()

        assertTrue(impressions.isEmpty())
        assertEquals(1, controller.deferredMessageCount)
    }

    @Test
    fun banner_billsImpressionOnceAttached() {
        resumeActivity()
        controller.ingest(listOf(makeMessage("b1", "on_session", type = "banner")))
        controller.triggerSession()
        assertTrue(impressions.isEmpty())
        idle()

        assertEquals(listOf("b1"), impressions)
    }

    @Test
    fun dismissAfter_autoDismissesTheModalAndDrainsTheNextMessage() {
        resumeActivity()
        controller.ingest(listOf(
            makeMessage("m1", "on_session", dismissAfterSeconds = 1.0),
            makeMessage("m2", "on_session")
        ))
        controller.triggerSession()
        idle()
        assertEquals(listOf("m1"), impressions)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_100))

        assertEquals(listOf("m1"), dismissed)
        assertEquals(listOf("m1", "m2"), impressions)
    }

    @Test
    fun dismissCurrent_withNothingOnScreenIsANoOp() {
        controller.dismissCurrent()
        idle()
        assertTrue(impressions.isEmpty())
        assertTrue(dismissed.isEmpty())
    }

    @Test
    fun dismissCurrent_firesOnDismissedForTheShownMessage() {
        resumeActivity()
        controller.ingest(listOf(makeMessage("m1", "on_session")))
        controller.triggerSession()
        idle()

        controller.dismissCurrent()
        idle()

        assertEquals(listOf("m1"), dismissed)
        assertEquals(0, controller.deferredMessageCount)
    }

    // ── Impression callback ───────────────────────────────────────────────────

    @Test
    fun impressionCallback_notFiredWhenQueueIsEmpty() {
        resumeActivity()
        controller.triggerSession()
        idle()

        assertTrue(impressions.isEmpty())
    }

    @Test
    fun impressionCallback_canBeCleared() {
        controller.onImpression = null
        resumeActivity()
        controller.ingest(listOf(makeMessage("m1", "on_session")))
        controller.triggerSession()
        idle()

        assertTrue(impressions.isEmpty())
    }

    // ── HTML in-app messages (US-16) ──────────────────────────────────────────

    @Test
    fun triggerSession_htmlMessage_doesNotCountImpressionImmediately() {
        resumeActivity()
        controller.ingest(listOf(makeMessage("m1", "on_session", type = "html", htmlBody = "<p>hi</p>")))
        controller.triggerSession()
        idle()

        // Unlike modal/banner, the HTML renderer counts its impression from the
        // WebView's onPageFinished (see HtmlInAppMessageRendererTest) — not
        // synchronously here just because the dialog is attached.
        assertTrue(impressions.isEmpty())
    }

    @Test
    fun triggerSession_htmlMessageWithoutHtmlBody_isDroppedRatherThanWedgingTheQueue() {
        // The CHECK constraint (migration 0056) means a real server response can't
        // omit html_body for type=html, but a malformed message must still free the
        // slot — same "not shown" path presentModal/presentBanner already have.
        resumeActivity()
        controller.ingest(listOf(makeMessage("m1", "on_session", type = "html")))
        controller.triggerSession()
        idle()

        assertTrue(impressions.isEmpty())
        assertTrue(dismissed.isEmpty())
        assertEquals(0, controller.queuedMessageCount)
    }

    // ── Native click/dismiss tracking (SQ-290) ────────────────────────────────

    @Test
    fun nativeBannerButtonTap_producesExactlyOneClickAndNoDismiss() {
        val activity = resumeActivity()
        controller.ingest(listOf(makeMessage("m1", "on_session", type = "banner", primaryButtonId = "cta")))
        controller.triggerSession()
        idle()

        val content = activity.window.decorView.findViewById<View>(android.R.id.content)
        val button = requireNotNull(findButtonWithText(content, "Go"))
        button.performClick()
        idle()

        assertEquals(listOf("m1" to "cta"), nativeClicks)
        assertTrue("a click is never also counted as a dismiss", nativeDismisses.isEmpty())
    }

    @Test
    fun nativeBannerButtonTap_tappedTwice_recordsExactlyOneClick() {
        // The banner's own dismiss-once guard removes it from the view tree after the
        // first tap, so a genuine double-tap can't reach the listener again in
        // practice — this exercises the controller-level guard directly instead.
        val activity = resumeActivity()
        controller.ingest(listOf(makeMessage("m1", "on_session", type = "banner", primaryButtonId = "cta")))
        controller.triggerSession()
        idle()

        val content = activity.window.decorView.findViewById<View>(android.R.id.content)
        val button = requireNotNull(findButtonWithText(content, "Go"))
        button.performClick()
        button.performClick()
        idle()

        assertEquals(1, nativeClicks.size)
    }

    @Test
    fun nativeModalDismiss_withoutAClick_producesExactlyOneDismiss() {
        resumeActivity()
        controller.ingest(listOf(makeMessage("m1", "on_session")))
        controller.triggerSession()
        idle()

        controller.dismissCurrent()
        idle()

        assertEquals(listOf("m1"), nativeDismisses)
        assertTrue(nativeClicks.isEmpty())
    }

    @Test
    fun nativeModalDismiss_calledRepeatedly_recordsExactlyOneDismiss() {
        resumeActivity()
        controller.ingest(listOf(makeMessage("m1", "on_session")))
        controller.triggerSession()
        idle()

        controller.dismissCurrent()
        controller.dismissCurrent() // nothing on screen any more — a no-op, not a second dismiss
        idle()

        assertEquals(listOf("m1"), nativeDismisses)
    }

    @Test
    fun htmlMessageDismiss_doesNotAlsoFireNativeDismissTracking() {
        // HTML tracks its own click/dismiss entirely through HtmlIamBridge (SQ-288);
        // onNativeDismiss must stay silent for type=html so the event isn't counted twice.
        resumeActivity()
        controller.ingest(listOf(makeMessage("m1", "on_session", type = "html", htmlBody = "<p>hi</p>")))
        controller.triggerSession()
        idle()

        controller.dismissCurrent()
        idle()

        assertTrue(nativeDismisses.isEmpty())
        assertTrue(nativeClicks.isEmpty())
    }
}
