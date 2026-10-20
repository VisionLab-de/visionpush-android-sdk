package de.visionlab.visionpush

import android.app.Activity
import android.content.Intent
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * HtmlIamBridge behaviour tests (US-16 "Bridge protocol"). Every bridge call posts to
 * the main looper, so every assertion that depends on its effect needs [idle] first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class HtmlIamBridgeTest {

    private lateinit var activity: Activity
    private val clicks = mutableListOf<String>()
    private var dismissesRecorded = 0
    private var dismissCount = 0
    private val addedTags = mutableListOf<Map<String, String>>()
    private val removedTags = mutableListOf<List<String>>()

    private lateinit var bridge: HtmlIamBridge

    @Before
    fun setup() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        bridge = HtmlIamBridge(
            activity = activity,
            recordClick = { id -> clicks.add(id) },
            recordDismiss = { dismissesRecorded++ },
            dismiss = { dismissCount++ },
            onAddTags = { tags -> addedTags.add(tags) },
            onRemoveTags = { keys -> removedTags.add(keys) },
        )
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    // ── click ────────────────────────────────────────────────────────────────

    @Test
    fun click_recordsClickAndClosesTheMessage() {
        bridge.click("primary", null)
        idle()

        assertEquals(listOf("primary"), clicks)
        assertEquals(1, dismissCount)
        assertEquals(0, dismissesRecorded) // a click is never also a dismiss
    }

    @Test
    fun click_withHttpsUrl_firesAnActionViewIntent() {
        bridge.click("cta", "https://example.com/offer")
        idle()

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("https://example.com/offer", started.data.toString())
        assertEquals(listOf("cta"), clicks)
        assertEquals(1, dismissCount)
    }

    @Test
    fun click_withHttpUrl_firesAnActionViewIntent() {
        bridge.click("cta", "http://example.com/offer")
        idle()

        assertEquals("http://example.com/offer", shadowOf(activity).nextStartedActivity.data.toString())
    }

    @Test
    fun click_withNonHttpScheme_doesNotOpenAnythingButStillRecordsAndCloses() {
        bridge.click("cta", "javascript:alert(1)")
        idle()

        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(listOf("cta"), clicks)
        assertEquals(1, dismissCount)
    }

    @Test
    fun click_withoutUrl_justRecordsAndCloses() {
        bridge.click("secondary", null)
        idle()

        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(listOf("secondary"), clicks)
        assertEquals(1, dismissCount)
    }

    @Test
    fun click_blankActionId_isIgnored() {
        bridge.click("", "https://example.com")
        idle()

        assertTrue(clicks.isEmpty())
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(0, dismissCount)
    }

    @Test
    fun click_nullActionId_isIgnored() {
        bridge.click(null, "https://example.com")
        idle()

        assertTrue(clicks.isEmpty())
        assertEquals(0, dismissCount)
    }

    // ── close ────────────────────────────────────────────────────────────────

    @Test
    fun close_recordsDismissThenClosesTheMessage() {
        bridge.close()
        idle()

        assertEquals(1, dismissesRecorded)
        assertEquals(1, dismissCount)
    }

    // ── once-per-display idempotency (SQ-288/SQ-290) ───────────────────────────

    @Test
    fun close_calledRepeatedly_recordsExactlyOneDismiss() {
        bridge.close()
        bridge.close()
        bridge.close()
        idle()

        assertEquals(1, dismissesRecorded)
        assertEquals("the guard gates the whole call, so teardown is only requested once", 1, dismissCount)
    }

    @Test
    fun clickThenClose_recordsExactlyOneClickAndNoDismiss() {
        bridge.click("primary", null)
        bridge.close()
        idle()

        assertEquals(listOf("primary"), clicks)
        assertEquals(0, dismissesRecorded)
    }

    @Test
    fun closeThenClick_recordsExactlyOneDismissAndNoClick() {
        bridge.close()
        bridge.click("primary", null)
        idle()

        assertEquals(1, dismissesRecorded)
        assertTrue(clicks.isEmpty())
    }

    @Test
    fun concurrentClickAndClose_recordExactlyOneTerminalEvent() {
        val ready = java.util.concurrent.CyclicBarrier(2)
        val clickThread = Thread {
            ready.await()
            bridge.click("primary", null)
        }
        val closeThread = Thread {
            ready.await()
            bridge.close()
        }
        clickThread.start()
        closeThread.start()
        clickThread.join()
        closeThread.join()
        idle()

        assertEquals("exactly one terminal event won the race", 1, clicks.size + dismissesRecorded)
    }

    @Test
    fun afterTerminalEvent_addTagsAndRemoveTagsAreIgnored() {
        bridge.close()
        idle()

        bridge.addTags("""{"plan":"pro"}""")
        bridge.removeTags("""["plan"]""")
        idle()

        assertTrue("addTags after close() must be ignored", addedTags.isEmpty())
        assertTrue("removeTags after close() must be ignored", removedTags.isEmpty())
    }

    // ── addTags ──────────────────────────────────────────────────────────────

    @Test
    fun addTags_validJson_delegatesToTheSdkTagApi() {
        bridge.addTags("""{"plan":"pro","source":"iam"}""")
        idle()

        assertEquals(listOf(mapOf("plan" to "pro", "source" to "iam")), addedTags)
    }

    @Test
    fun addTags_malformedJson_isIgnored() {
        bridge.addTags("{not valid json")
        idle()

        assertTrue(addedTags.isEmpty())
    }

    @Test
    fun addTags_nonStringValues_areDroppedKeepingTheRest() {
        bridge.addTags("""{"plan":"pro","count":5}""")
        idle()

        assertEquals(listOf(mapOf("plan" to "pro")), addedTags)
    }

    @Test
    fun addTags_onlyNonStringValues_isIgnored() {
        bridge.addTags("""{"count":5}""")
        idle()

        assertTrue(addedTags.isEmpty())
    }

    @Test
    fun addTags_null_isIgnored() {
        bridge.addTags(null)
        idle()

        assertTrue(addedTags.isEmpty())
    }

    @Test
    fun addTags_jsonArrayInsteadOfObject_isIgnored() {
        bridge.addTags("""["plan","pro"]""")
        idle()

        assertTrue(addedTags.isEmpty())
    }

    // ── removeTags ───────────────────────────────────────────────────────────

    @Test
    fun removeTags_validJson_delegatesToTheSdkTagApi() {
        bridge.removeTags("""["plan","source"]""")
        idle()

        assertEquals(listOf(listOf("plan", "source")), removedTags)
    }

    @Test
    fun removeTags_malformedJson_isIgnored() {
        bridge.removeTags("not json at all")
        idle()

        assertTrue(removedTags.isEmpty())
    }

    @Test
    fun removeTags_nonStringEntries_areDroppedKeepingTheRest() {
        bridge.removeTags("""["plan", 5]""")
        idle()

        assertEquals(listOf(listOf("plan")), removedTags)
    }

    @Test
    fun removeTags_null_isIgnored() {
        bridge.removeTags(null)
        idle()

        assertTrue(removedTags.isEmpty())
    }
}
