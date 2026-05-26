package de.visionlab.visionpush

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.UUID

/**
 * WorkManager worker that posts a geofence entry or exit event to the VisionPush API.
 *
 * Enqueued by [GeofenceTransitionReceiver] immediately after a transition is received.
 * WorkManager handles Doze-mode resilience and network-availability retries.
 */
internal class GeofenceEventWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
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
        private const val KEY_DEVICE_ID  = "vp_device_id"
        private const val KEY_GEOFENCE_ID = "vp_geofence_id"
        private const val KEY_EVENT_TYPE  = "vp_event_type"
        private const val KEY_LATITUDE    = "vp_latitude"
        private const val KEY_LONGITUDE   = "vp_longitude"

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

            WorkManager.getInstance(context).enqueue(request)
            VisionPushLogger.d(TAG, "Enqueued $eventType event for geofence $geofenceId.")
        }

        /**
         * Schedules a "dwell" event with an initial delay.
         * Tagged with `vp_dwell_<geofenceId>` so it can be cancelled on exit.
         */
        fun enqueueDwell(
            context:       Context,
            deviceId:      String,
            geofenceId:    String,
            latitude:      Double?,
            longitude:     Double?,
            delaySeconds:  Long
        ) {
            val data = Data.Builder()
                .putString(KEY_DEVICE_ID,   deviceId)
                .putString(KEY_GEOFENCE_ID, geofenceId)
                .putString(KEY_EVENT_TYPE,  "dwell")
                .apply {
                    latitude?.let  { putString(KEY_LATITUDE,  it.toString()) }
                    longitude?.let { putString(KEY_LONGITUDE, it.toString()) }
                }
                .build()

            val request = OneTimeWorkRequestBuilder<GeofenceEventWorker>()
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .addTag("vp_dwell_$geofenceId")
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
                .setInputData(data)
                .build()

            WorkManager.getInstance(context).enqueue(request)
            VisionPushLogger.d(TAG, "Scheduled dwell event for geofence $geofenceId in ${delaySeconds}s.")
        }
    }
}
