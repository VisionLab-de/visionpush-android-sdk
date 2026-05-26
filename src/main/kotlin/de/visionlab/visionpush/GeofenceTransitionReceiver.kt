package de.visionlab.visionpush

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

/**
 * Receives geofence transition intents from the OS and enqueues a [GeofenceEventWorker]
 * for each triggered geofence.
 *
 * On ENTER: reports an "entry" event and schedules a "dwell" event (with initial delay)
 * when the geofence has a configured dwell time.
 * On EXIT:  cancels any pending dwell work, then reports an "exit" event.
 *
 * Registered in the SDK manifest; delivered via the PendingIntent set up by
 * [GeofenceManager]. WorkManager ensures events reach the API even in Doze mode.
 */
class GeofenceTransitionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return

        val deviceId = VisionPush.deviceId ?: return
        val lat = event.triggeringLocation?.latitude
        val lng = event.triggeringLocation?.longitude

        when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> {
                event.triggeringGeofences?.forEach { geofence ->
                    val geofenceId = geofence.requestId.removePrefix("vp_")
                    // Report entry immediately
                    GeofenceEventWorker.enqueue(context, deviceId, geofenceId, "entry", lat, lng)
                    // Schedule dwell event if zone has a dwell threshold
                    val dwellSecs = GeofenceManager.getDwellTimeSecs(context, geofenceId)
                    if (dwellSecs != null && dwellSecs > 0) {
                        GeofenceEventWorker.enqueueDwell(context, deviceId, geofenceId, lat, lng, dwellSecs.toLong())
                    }
                }
            }
            Geofence.GEOFENCE_TRANSITION_EXIT -> {
                event.triggeringGeofences?.forEach { geofence ->
                    val geofenceId = geofence.requestId.removePrefix("vp_")
                    // Cancel pending dwell — user left before threshold
                    WorkManager.getInstance(context).cancelAllWorkByTag("vp_dwell_$geofenceId")
                    // Report exit
                    GeofenceEventWorker.enqueue(context, deviceId, geofenceId, "exit", lat, lng)
                }
            }
        }
    }
}
