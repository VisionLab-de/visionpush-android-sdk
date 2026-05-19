package de.visionlab.visionpush

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

/**
 * Tracks notification events (opens, action taps, dismissals) and delivers
 * them to the VisionPush API.
 *
 * Architecture overview:
 * 1. [NotificationBuilder] attaches [PendingIntent]s that point to [OpenReceiver].
 * 2. [OpenReceiver.onReceive] is called on the main thread when the user interacts
 *    with a notification.
 * 3. The receiver immediately enqueues a [TrackEventWorker] via WorkManager to
 *    perform the API call off the main thread with built-in retry logic.
 * 4. [TrackEventWorker] calls [ApiClient.trackEvent] and lets WorkManager retry
 *    with exponential back-off on network failures.
 */
class NotificationTracker private constructor() {

    // ── BroadcastReceiver ───────────────────────────────────────────────────

    /**
     * Receives broadcast intents fired by notification [PendingIntent]s.
     *
     * The receiver is declared in the SDK manifest and registered automatically
     * when the library is merged into the host application.
     *
     * On receipt it:
     * 1. Cancels the system notification (for opens and action taps).
     * 2. Enqueues a WorkManager task to report the event to the API.
     * 3. Forwards any deep-link URI to the host application.
     */
    class OpenReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val notificationId = intent.getStringExtra(EXTRA_NOTIFICATION_ID) ?: return
            val systemNotifId = intent.getIntExtra(EXTRA_SYSTEM_NOTIFICATION_ID, -1)
            val eventTypeKey = intent.getStringExtra(EXTRA_EVENT_TYPE) ?: return
            val actionId = intent.getStringExtra(EXTRA_ACTION_ID)
            val campaignId = intent.getStringExtra(EXTRA_CAMPAIGN_ID)
            val deepLink = intent.getStringExtra(EXTRA_DEEP_LINK)

            val eventType = NotificationEvent.EventType.values()
                .firstOrNull { it.apiKey == eventTypeKey }
                ?: NotificationEvent.EventType.OPENED

            VisionPushLogger.i(TAG, "Notification event received: $eventType id=$notificationId")

            // Dismiss the notification from the shade for opens and action taps
            if (eventType != NotificationEvent.EventType.DISMISSED && systemNotifId >= 0) {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                        as? NotificationManager
                manager?.cancel(systemNotifId)
            }

            // Enqueue the tracking API call via WorkManager (survives process death)
            enqueueTrackingWork(context, notificationId, eventType, actionId, campaignId)

            // Invoke the app-level custom handler (runs in the same process)
            VisionPush.internalNotificationOpenCallback?.let { callback ->
                // Re-inflate the original VisionPushNotification from the store
                VisionPush.getStoredNotification(notificationId)?.let { notification ->
                    callback(notification)
                }
            }

            // Route the deep-link intent
            if (!deepLink.isNullOrBlank()) {
                launchDeepLink(context, deepLink)
            } else if (eventType == NotificationEvent.EventType.OPENED ||
                       eventType == NotificationEvent.EventType.ACTION_TAPPED) {
                launchHostApp(context)
            }
        }

        /** Launches the host app launcher activity. */
        private fun launchHostApp(context: Context) {
            val launchIntent = context.packageManager
                .getLaunchIntentForPackage(context.packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                ?: return
            context.startActivity(launchIntent)
        }

        /** Fires an implicit intent for [deepLink] URI. */
        private fun launchDeepLink(context: Context, deepLink: String) {
            try {
                val uri = Uri.parse(deepLink)
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                VisionPushLogger.w(TAG, "Failed to launch deep-link '$deepLink': ${e.message}")
                // Fall back to opening the launcher activity
                launchHostApp(context)
            }
        }
    }

    // ── WorkManager worker ──────────────────────────────────────────────────

    /**
     * Background worker that calls [ApiClient.trackEvent] for a single
     * notification event. WorkManager retries with exponential back-off on
     * [Result.retry] responses, which are emitted for transient network errors.
     */
    class TrackEventWorker(
        appContext: Context,
        params: WorkerParameters
    ) : Worker(appContext, params) {

        override fun doWork(): Result {
            val notificationId = inputData.getString(WorkerKeys.KEY_NOTIFICATION_ID)
                ?: return Result.failure()
            val eventTypeKey = inputData.getString(WorkerKeys.KEY_EVENT_TYPE)
                ?: return Result.failure()
            val actionId = inputData.getString(WorkerKeys.KEY_ACTION_ID)
            val campaignId = inputData.getString(WorkerKeys.KEY_CAMPAIGN_ID)

            val eventType = NotificationEvent.EventType.values()
                .firstOrNull { it.apiKey == eventTypeKey }
                ?: return Result.failure()

            val event = NotificationEvent(
                notificationId = notificationId,
                eventType = eventType,
                actionId = actionId,
                campaignId = campaignId,
                deviceId = VisionPush.getDeviceId()
            )

            // runBlocking is acceptable here because doWork() already runs on a
            // background thread managed by WorkManager's executor pool.
            val result = runBlocking {
                VisionPush.internalApiClient?.trackEvent(event)
                    ?: VisionPushResult.Failure(VisionPushError.NotInitialized())
            }

            return when {
                result.isSuccess -> Result.success()
                result.errorOrNull() is VisionPushError.NetworkError -> Result.retry()
                else -> Result.failure()
            }
        }
    }

    // ── Companion (public constants + enqueue helper) ───────────────────────

    companion object {
        private const val TAG = "VP.NotificationTracker"

        // Intent action strings
        const val ACTION_NOTIFICATION_OPENED = "de.visionlab.visionpush.ACTION_OPENED"
        const val ACTION_NOTIFICATION_DISMISSED = "de.visionlab.visionpush.ACTION_DISMISSED"
        const val ACTION_ACTION_TAPPED = "de.visionlab.visionpush.ACTION_ACTION_TAPPED"

        // Intent extra keys
        const val EXTRA_NOTIFICATION_ID = "vp_notification_id"
        const val EXTRA_SYSTEM_NOTIFICATION_ID = "vp_system_notification_id"
        const val EXTRA_EVENT_TYPE = "vp_event_type"
        const val EXTRA_ACTION_ID = "vp_action_id"
        const val EXTRA_CAMPAIGN_ID = "vp_campaign_id"
        const val EXTRA_DEEP_LINK = "vp_deep_link"

        // WorkManager tag used for de-duplication queries
        private const val WORK_TAG = "vp_track_event"

        /**
         * Enqueues a [TrackEventWorker] via WorkManager.
         *
         * Uses a network constraint so WorkManager waits for connectivity before
         * attempting the API call, and applies exponential back-off for retries.
         */
        internal fun enqueueTrackingWork(
            context: Context,
            notificationId: String,
            eventType: NotificationEvent.EventType,
            actionId: String?,
            campaignId: String? = null
        ) {
            val inputData = Data.Builder()
                .putString(WorkerKeys.KEY_NOTIFICATION_ID, notificationId)
                .putString(WorkerKeys.KEY_EVENT_TYPE, eventType.apiKey)
                .apply { if (actionId != null) putString(WorkerKeys.KEY_ACTION_ID, actionId) }
                .apply { if (campaignId != null) putString(WorkerKeys.KEY_CAMPAIGN_ID, campaignId) }
                .build()

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<TrackEventWorker>()
                .setInputData(inputData)
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    INITIAL_BACKOFF_SECONDS,
                    TimeUnit.SECONDS
                )
                .addTag(WORK_TAG)
                .build()

            WorkManager.getInstance(context).enqueue(request)
        }

        private const val INITIAL_BACKOFF_SECONDS = 15L
    }
}
