package de.visionlab.visionpush

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

/**
 * Receives geofence transition intents from the OS and enqueues a [GeofenceEventWorker]
 * for each triggered geofence — but only on a real STATE CHANGE.
 *
 * The OS re-delivers ENTER whenever a fence is (re-)registered while the device is
 * inside it, and [GeofenceManager] must re-register on cold start (the OS drops fences
 * on reboot) and when the fence set changes. Without an inside-set every such
 * registration produced a fresh "entry" report — and a fresh campaign push — while the
 * user had not moved. [GeofenceState] persists which fences the device is currently
 * inside; this receiver reports entry only outside→inside, exit only inside→outside,
 * and dwell once per visit.
 *
 * On ENTER: reports an "entry" event.
 * On DWELL: the OS-evaluated loitering threshold elapsed (see
 *   [GeofenceManager] / `setLoiteringDelay`) — reports a "dwell" event.
 * On EXIT:  reports an "exit" event.
 *
 * Registered in the SDK manifest; delivered via the PendingIntent set up by
 * [GeofenceManager]. WorkManager ensures events reach the API even in Doze mode.
 */
class GeofenceTransitionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        // GDPR/CCPA: a transition is location data — never reported without consent.
        if (!VisionPush.hasConsent) return

        val deviceId = VisionPush.deviceId ?: return
        val lat = event.triggeringLocation?.latitude
        val lng = event.triggeringLocation?.longitude
        val ids = event.triggeringGeofences?.map { it.requestId.removePrefix("vp_") } ?: return

        handleTransition(context, event.geofenceTransition, ids, lat, lng, deviceId)
    }

    companion object {
        private const val TAG = "VP.GeofenceReceiver"

        /**
         * The state-change gate, separated from the Play Services intent parsing so it
         * is unit-testable. Returns the (event type, geofence id) pairs actually reported.
         */
        internal fun handleTransition(
            context: Context,
            transition: Int,
            geofenceIds: List<String>,
            latitude: Double?,
            longitude: Double?,
            deviceId: String
        ): List<Pair<String, String>> {
            val reported = mutableListOf<Pair<String, String>>()
            fun report(eventType: String, geofenceId: String) {
                GeofenceEventWorker.enqueue(context, deviceId, geofenceId, eventType, latitude, longitude)
                reported += eventType to geofenceId
            }

            for (geofenceId in geofenceIds) {
                when (transition) {
                    Geofence.GEOFENCE_TRANSITION_ENTER -> {
                        if (GeofenceState.markInside(context, geofenceId)) {
                            report("entry", geofenceId)
                        } else {
                            VisionPushLogger.d(TAG, "ENTER for $geofenceId while already inside — not re-reported.")
                        }
                    }
                    Geofence.GEOFENCE_TRANSITION_DWELL -> {
                        // A dwell implies presence: if the ENTER was missed (fence added
                        // while inside, app killed), record the entry first.
                        if (GeofenceState.markInside(context, geofenceId)) {
                            report("entry", geofenceId)
                        }
                        if (GeofenceState.markDwelled(context, geofenceId)) {
                            report("dwell", geofenceId)
                        }
                    }
                    Geofence.GEOFENCE_TRANSITION_EXIT -> {
                        if (GeofenceState.markOutside(context, geofenceId)) {
                            report("exit", geofenceId)
                        }
                        // else: EXIT for a fence we never saw the inside of (e.g. the
                        // initial-trigger sweep on registration) — nothing to report.
                    }
                }
            }
            return reported
        }
    }
}
