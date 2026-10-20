package de.visionlab.visionpush

import android.app.Application
import androidx.work.OneTimeWorkRequest
import com.google.android.gms.location.Geofence
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The geofence state-change gate ([GeofenceTransitionReceiver.handleTransition]),
 * the persisted inside-set ([GeofenceState]) and the registration diff
 * ([GeofenceManager.computeDiff]) — driven under Robolectric with the WorkManager
 * seam so no Play Services / WorkManager initialization is needed.
 *
 * Regression background: the OS re-delivers ENTER for every fence the device is
 * inside each time the fence is (re-)registered, and the SDK re-registered ALL
 * fences with INITIAL_TRIGGER_ENTER on every 15-minute location cycle — so a user
 * standing still produced an "entry" report (and a campaign push) every 15 minutes.
 */
@RunWith(RobolectricTestRunner::class)
class GeofenceTransitionTest {

    private lateinit var context: Application
    private val enqueued = mutableListOf<Pair<String, OneTimeWorkRequest>>()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        GeofenceState.clear(context)
        GeofenceEventWorker.workEnqueuer = { _, name, request -> enqueued.add(name to request) }
    }

    @After
    fun tearDown() {
        GeofenceEventWorker.workEnqueuer = null
        GeofenceState.clear(context)
    }

    private fun fire(transition: Int, vararg ids: String) =
        GeofenceTransitionReceiver.handleTransition(context, transition, ids.toList(), 52.52, 13.405, "dev-1")

    private fun eventTypes() = enqueued.map { it.second.workSpec.input.getString(GeofenceEventWorker.KEY_EVENT_TYPE) }

    // ── state-change gate ────────────────────────────────────────────────────────

    @Test
    fun repeatedEnterWhileInside_isReportedOnce() {
        assertEquals(listOf("entry" to "f1"), fire(Geofence.GEOFENCE_TRANSITION_ENTER, "f1"))
        // Re-registration / next location cycle re-delivers ENTER for the same fence.
        assertEquals(emptyList<Pair<String, String>>(), fire(Geofence.GEOFENCE_TRANSITION_ENTER, "f1"))
        assertEquals(emptyList<Pair<String, String>>(), fire(Geofence.GEOFENCE_TRANSITION_ENTER, "f1"))
        assertEquals(listOf("entry"), eventTypes())
        assertTrue(GeofenceState.isInside(context, "f1"))
    }

    @Test
    fun enterExitEnter_reportsEachCrossing() {
        fire(Geofence.GEOFENCE_TRANSITION_ENTER, "f1")
        fire(Geofence.GEOFENCE_TRANSITION_EXIT, "f1")
        fire(Geofence.GEOFENCE_TRANSITION_ENTER, "f1")
        assertEquals(listOf("entry", "exit", "entry"), eventTypes())
    }

    @Test
    fun exitWhenNotInside_isNotReported() {
        // The initial-trigger sweep on registration delivers EXIT for every fence the
        // device is outside of — none of these may become a report.
        assertEquals(emptyList<Pair<String, String>>(), fire(Geofence.GEOFENCE_TRANSITION_EXIT, "f1", "f2", "f3"))
        assertTrue(enqueued.isEmpty())
    }

    @Test
    fun staleInsideState_isClearedByExitSweep() {
        // Device was inside f1, app died, user left, reboot dropped the fences: the
        // re-registration's EXIT initial trigger is the only signal we get.
        GeofenceState.markInside(context, "f1")
        assertEquals(listOf("exit" to "f1"), fire(Geofence.GEOFENCE_TRANSITION_EXIT, "f1"))
        assertFalse(GeofenceState.isInside(context, "f1"))
        // …and the next real entry is reported again.
        assertEquals(listOf("entry" to "f1"), fire(Geofence.GEOFENCE_TRANSITION_ENTER, "f1"))
    }

    @Test
    fun dwell_isReportedOncePerVisit_andImpliesEntry() {
        // DWELL without a preceding ENTER (fence added while inside, app killed):
        // the entry is recorded first, then the dwell.
        assertEquals(listOf("entry" to "f1", "dwell" to "f1"), fire(Geofence.GEOFENCE_TRANSITION_DWELL, "f1"))
        // A second DWELL for the same visit is not re-reported.
        assertEquals(emptyList<Pair<String, String>>(), fire(Geofence.GEOFENCE_TRANSITION_DWELL, "f1"))
        // After leaving and re-entering, dwell can fire again.
        fire(Geofence.GEOFENCE_TRANSITION_EXIT, "f1")
        fire(Geofence.GEOFENCE_TRANSITION_ENTER, "f1")
        assertEquals(listOf("dwell" to "f1"), fire(Geofence.GEOFENCE_TRANSITION_DWELL, "f1"))
        assertEquals(listOf("entry", "dwell", "exit", "entry", "dwell"), eventTypes())
    }

    @Test
    fun multipleFences_inOneTransition_areGatedIndependently() {
        GeofenceState.markInside(context, "f2")
        assertEquals(listOf("entry" to "f1", "entry" to "f3"), fire(Geofence.GEOFENCE_TRANSITION_ENTER, "f1", "f2", "f3"))
    }

    // ── unique work ──────────────────────────────────────────────────────────────

    @Test
    fun reports_areUniqueWorkPerEventTypeAndFence_withDeviceAndCoordinates() {
        fire(Geofence.GEOFENCE_TRANSITION_ENTER, "f1")
        val (name, request) = enqueued.single()
        assertEquals("vp_geo_entry_f1", name)
        assertEquals(GeofenceEventWorker.uniqueWorkName("entry", "f1"), name)
        val input = request.workSpec.input
        assertEquals("dev-1", input.getString(GeofenceEventWorker.KEY_DEVICE_ID))
        assertEquals("f1", input.getString(GeofenceEventWorker.KEY_GEOFENCE_ID))
        assertEquals("52.52", input.getString(GeofenceEventWorker.KEY_LATITUDE))
        assertEquals("13.405", input.getString(GeofenceEventWorker.KEY_LONGITUDE))
        assertEquals(
            "network constraint keeps the report until connectivity",
            androidx.work.NetworkType.CONNECTED,
            request.workSpec.constraints.requiredNetworkType
        )
    }

    // ── persisted state store ────────────────────────────────────────────────────

    @Test
    fun state_survivesAcrossInstances_andRetainOnlyForgetsDroppedFences() {
        assertTrue(GeofenceState.markInside(context, "f1"))
        assertTrue(GeofenceState.markInside(context, "f2"))
        assertTrue(GeofenceState.markDwelled(context, "f2"))
        // A fresh read (as the manifest receiver in another process would do) sees it.
        assertEquals(setOf("f1", "f2"), GeofenceState.inside(context))

        GeofenceState.retainOnly(context, setOf("f2"))
        assertEquals(setOf("f2"), GeofenceState.inside(context))
        // f2's dwell is still remembered (not re-reported), f1's state is gone.
        assertFalse(GeofenceState.markDwelled(context, "f2"))
        assertTrue(GeofenceState.markInside(context, "f1"))

        // Leaving forgets the dwell so the next visit can dwell again.
        assertTrue(GeofenceState.markOutside(context, "f2"))
        assertTrue(GeofenceState.markDwelled(context, "f2"))
    }

    // ── registration diff ────────────────────────────────────────────────────────

    private fun fence(id: String, radius: Int = 100, dwell: Int? = null) =
        GeofenceData(id, 52.52, 13.405, radius, dwell)

    @Test
    fun computeDiff_unchangedSet_registersNothing() {
        val set = listOf(fence("a"), fence("b", dwell = 60))
        val diff = GeofenceManager.computeDiff(set, set.map { it.copy() })
        assertTrue(diff.toAdd.isEmpty())
        assertTrue(diff.toRemoveIds.isEmpty())
    }

    @Test
    fun computeDiff_addsNew_removesDropped_reAddsChanged() {
        val previous = listOf(fence("a"), fence("b"), fence("c", radius = 100))
        val next = listOf(fence("b"), fence("c", radius = 250), fence("d"))
        val diff = GeofenceManager.computeDiff(previous, next)
        assertEquals(listOf("c", "d"), diff.toAdd.map { it.id })
        assertEquals(listOf("a", "c"), diff.toRemoveIds)
    }

    @Test
    fun computeDiff_dwellChange_countsAsChanged() {
        val diff = GeofenceManager.computeDiff(listOf(fence("a")), listOf(fence("a", dwell = 120)))
        assertEquals(listOf("a"), diff.toAdd.map { it.id })
        assertEquals(listOf("a"), diff.toRemoveIds)
    }
}
