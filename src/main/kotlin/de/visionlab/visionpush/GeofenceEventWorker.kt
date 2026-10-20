package de.visionlab.visionpush

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that posts a geofence entry / exit / dwell event to the VisionPush API.
 *
 * Enqueued by [GeofenceTransitionReceiver] immediately after a transition is received.
 * WorkManager handles Doze-mode resilience and network-availability retries.
 *
 * Each (event type, geofence) pair is UNIQUE work with [ExistingWorkPolicy.REPLACE]: a
 * transition that is re-delivered before the previous report left the device replaces
 * it instead of queueing a duplicate. Dwell is no longer a delayed work item — the OS
 * reports [com.google.android.gms.location.Geofence.GEOFENCE_TRANSITION_DWELL] itself
 * (see [GeofenceManager]), so a Doze-deferred timer can no longer fire "dwell" for a
 * user who left long ago.
 */
internal class GeofenceEventWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Consent may have been revoked while this work was queued — drop, don't send.
        if (!VisionPush.hasConsent) return@withContext Result.failure()
        val deviceId   = inputData.getString(KEY_DEVICE_ID)   ?: return@withContext Result.failure()
        val geofenceId = inputData.getString(KEY_GEOFENCE_ID) ?: return@withContext Result.failure()
        val eventType  = inputData.getString(KEY_EVENT_TYPE)  ?: return@withContext Result.failure()
        val latitude   = inputData.getString(KEY_LATITUDE)?.toDoubleOrNull()
        val longitude  = inputData.getString(KEY_LONGITUDE)?.toDoubleOrNull()

        val result = VisionPush.internalApiClient?.reportGeofenceEvent(
            deviceId   = deviceId,
            geofenceId = geofenceId,
            eventType  = eventType,
            latitude   = latitude,
            longitude  = longitude
        ) ?: return@withContext Result.failure()

        when {
            result.isSuccess -> Result.success()
            result.errorOrNull() is VisionPushError.NetworkError -> Result.retry()
            else -> Result.failure()
        }
    }

    companion object {
        private const val TAG           = "VP.GeofenceEventWorker"
        internal const val KEY_DEVICE_ID  = "vp_device_id"
        internal const val KEY_GEOFENCE_ID = "vp_geofence_id"
        internal const val KEY_EVENT_TYPE  = "vp_event_type"
        internal const val KEY_LATITUDE    = "vp_latitude"
        internal const val KEY_LONGITUDE   = "vp_longitude"

        /**
         * Test seam: when set, replaces `WorkManager.enqueueUniqueWork` so unit tests can
         * observe (unique name, request) pairs without initializing WorkManager.
         */
        @Volatile internal var workEnqueuer: ((Context, String, OneTimeWorkRequest) -> Unit)? = null

        /** The unique work name for one (event type, geofence) report. */
        internal fun uniqueWorkName(eventType: String, geofenceId: String): String =
            "vp_geo_${eventType}_$geofenceId"

        fun enqueue(
            context:    Context,
            deviceId:   String,
            geofenceId: String,
            eventType:  String,
            latitude:   Double?,
            longitude:  Double?
        ) {
            val data = Data.Builder()
                .putString(KEY_DEVICE_ID,   deviceId)
                .putString(KEY_GEOFENCE_ID, geofenceId)
                .putString(KEY_EVENT_TYPE,  eventType)
                .apply {
                    latitude?.let  { putString(KEY_LATITUDE,  it.toString()) }
                    longitude?.let { putString(KEY_LONGITUDE, it.toString()) }
                }
                .build()

            val request = OneTimeWorkRequestBuilder<GeofenceEventWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
                .setInputData(data)
                .build()

            val name = uniqueWorkName(eventType, geofenceId)
            val enqueuer = workEnqueuer
            if (enqueuer != null) {
                enqueuer(context, name, request)
            } else {
                WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request)
            }
            VisionPushLogger.d(TAG, "Enqueued $eventType event for geofence $geofenceId.")
        }
    }
}
