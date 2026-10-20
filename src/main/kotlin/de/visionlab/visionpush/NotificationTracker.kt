package de.visionlab.visionpush

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
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
            // Trigger in-app messages queued for notification-open.
            VisionPush.internalIamController?.triggerNotificationOpen()

            // Route the deep-link intent
            if (!deepLink.isNullOrBlank()) {
                launchDeepLink(context, deepLink, intent)
            } else if (eventType == NotificationEvent.EventType.OPENED ||
                       eventType == NotificationEvent.EventType.ACTION_TAPPED) {
                launchHostApp(context, intent)
            }
        }

        /** Launches the host app launcher activity. */
        private fun launchHostApp(context: Context, source: Intent) {
            val launchIntent = context.packageManager
                .getLaunchIntentForPackage(context.packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                ?: return
            forwardOpenExtras(source, launchIntent)
            context.startActivity(launchIntent)
        }

        /** Fires an implicit intent for [deepLink] URI. */
        private fun launchDeepLink(context: Context, deepLink: String, source: Intent) {
            // Block schemes that could execute code or access private data — the
            // SAME allowlist the in-app-message action path uses (UrlSecurity).
            val uri = UrlSecurity.safeUri(deepLink)
            if (uri == null) {
                VisionPushLogger.w(TAG, "Deep-link blocked – unsafe or unparseable URL.")
                launchHostApp(context, source)
                return
            }
            try {
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                forwardOpenExtras(source, intent)
                context.startActivity(intent)
            } catch (e: Exception) {
                VisionPushLogger.w(TAG, "Failed to launch deep-link '$deepLink': ${e.message}")
                launchHostApp(context, source)
            }
        }

        /**
         * Copies the VisionPush identification extras from the tracking intent
         * onto the intent that (re)launches the UI.
         *
         * Without this the launch intent carries no VisionPush context, so an
         * app cold-started by a notification tap (e.g. the Flutter plugin's
         * `getInitialNotification`) cannot resolve which notification launched
         * it — the open event itself fires before any app-level handler is
         * registered in a fresh process.
         */
        private fun forwardOpenExtras(source: Intent, target: Intent) {
            source.getStringExtra(EXTRA_NOTIFICATION_ID)?.let {
                target.putExtra(EXTRA_NOTIFICATION_ID, it)
            }
            source.getStringExtra(EXTRA_CAMPAIGN_ID)?.let {
                target.putExtra(EXTRA_CAMPAIGN_ID, it)
            }
            source.getStringExtra(EXTRA_DEEP_LINK)?.let {
                target.putExtra(EXTRA_DEEP_LINK, it)
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

            // Consent may have been revoked between enqueue and execution — drop, don't send.
            if (!VisionPush.hasConsent) {
                VisionPushLogger.d(TAG, "Consent not given – dropping queued $eventType event.")
                return Result.failure()
            }

            val event = NotificationEvent(
                notificationId = notificationId,
                eventType = eventType,
                actionId = actionId,
                campaignId = campaignId,
                deviceId = VisionPush.deviceId
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
         * Test seam: when set, receives the built request instead of WorkManager, so
         * unit tests can assert the consent gate without initializing WorkManager.
         */
        @Volatile internal var workEnqueuer: ((Context, OneTimeWorkRequest) -> Unit)? = null

        /**
         * Enqueues a [TrackEventWorker] via WorkManager.
         *
         * Uses a network constraint so WorkManager waits for connectivity before
         * attempting the API call, and applies exponential back-off for retries.
         *
         * **Consent gate.** The automatic DELIVERED / OPENED / DISMISSED events from the
         * display and tap paths land here too — not only the public `trackOpened()`,
         * which throws [VisionPushError.ConsentRequired]. Without consent nothing may
         * leave the device: displaying the notification is fine, reporting it is not,
         * so the event is dropped here (debug-logged, never thrown).
         */
        internal fun enqueueTrackingWork(
            context: Context,
            notificationId: String,
            eventType: NotificationEvent.EventType,
            actionId: String?,
            campaignId: String? = null
        ) {
            if (!VisionPush.hasConsent) {
                VisionPushLogger.d(TAG, "Consent not given – dropping $eventType event for $notificationId.")
                return
            }

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

            val enqueuer = workEnqueuer
            if (enqueuer != null) enqueuer(context, request) else WorkManager.getInstance(context).enqueue(request)
        }

        /**
         * Cancels every tracking event still waiting in WorkManager. Called when consent
         * is revoked so events enqueued moments earlier do not leave the device later.
         */
        internal fun cancelPendingWork(context: Context) {
            try {
                WorkManager.getInstance(context).cancelAllWorkByTag(WORK_TAG)
            } catch (e: IllegalStateException) {
                // WorkManager not initialized in this process → nothing queued to cancel.
                VisionPushLogger.d(TAG, "WorkManager unavailable – no pending tracking work to cancel.")
            }
        }

        private const val INITIAL_BACKOFF_SECONDS = 15L
    }
}
