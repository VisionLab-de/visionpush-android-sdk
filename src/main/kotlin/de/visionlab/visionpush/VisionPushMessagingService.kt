package de.visionlab.visionpush

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * FCM service that handles incoming push messages and token rotation events.
 *
 * This service is registered in the SDK manifest and receives two categories
 * of FCM callbacks:
 *
 * 1. **[onMessageReceived]** – fires when a data or notification message is
 *    delivered while the app is in the foreground, or always for data-only
 *    messages. The SDK inspects the payload for a VisionPush envelope
 *    (`vp_payload`) and either delegates display to [NotificationBuilder] or
 *    forwards the parsed [VisionPushNotification] to the custom handler.
 *
 * 2. **[onNewToken]** – fires whenever FCM rotates the registration token.
 *    The SDK immediately re-registers the device with the new token.
 *
 * ## Subclassing
 *
 * Host applications that already extend [FirebaseMessagingService] can
 * subclass [VisionPushMessagingService] instead and call `super` to preserve
 * VisionPush behaviour:
 *
 * ```kotlin
 * class MyMessagingService : VisionPushMessagingService() {
 *     override fun onMessageReceived(message: RemoteMessage) {
 *         super.onMessageReceived(message) // VisionPush handling
 *         // ... additional app-level handling ...
 *     }
 * }
 * ```
 * Remember to update the manifest `<service>` entry to point at `MyMessagingService`.
 */
open class VisionPushMessagingService : FirebaseMessagingService() {

    /**
     * Coroutine scope tied to this service instance.
     *
     * [SupervisorJob] ensures that a failure in one child coroutine does not
     * cancel the others. The scope is cancelled in [onDestroy] when the service
     * lifecycle ends.
     */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ── FirebaseMessagingService overrides ──────────────────────────────────

    /**
     * Called by the FCM SDK when a message is received.
     *
     * VisionPush data messages carry a `vp_payload` key in the FCM data map.
     * When this key is present the payload is parsed and processed by the SDK.
     * All other messages are silently ignored by the SDK but remain available
     * to host-app subclasses via `super.onMessageReceived(message)`.
     *
     * **Note:** System-generated FCM notification messages (sent with a
     * `notification` block) are handled directly by the FCM SDK when the app
     * is in the background; this callback is only invoked for those when the
     * app is foregrounded. VisionPush recommends sending data-only messages
     * for full control over notification display.
     */
    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        VisionPushLogger.d(TAG, "FCM message received. from=${message.from}")

        val notification = VisionPushNotification.fromFcmData(message.data)
        if (notification == null) {
            VisionPushLogger.d(TAG, "Message does not contain a VisionPush payload – skipping.")
            return
        }

        VisionPushLogger.i(TAG, "VisionPush notification received: id=${notification.id}")

        // Store the notification for later retrieval by the open-tracking receiver
        VisionPush.storeNotification(notification)

        serviceScope.launch {
            processNotification(notification)
        }
    }

    /**
     * Called by the FCM SDK when the registration token is refreshed.
     *
     * Token rotation can happen at any time (e.g. after an app restore, or when
     * FCM invalidates stale tokens). The SDK re-registers the device immediately
     * and enqueues a WorkManager retry if the network is unavailable.
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        VisionPushLogger.i(TAG, "FCM token refreshed – re-registering device.")
        serviceScope.launch {
            val registrar = VisionPush.internalDeviceRegistrar
            if (registrar == null) {
                VisionPushLogger.w(TAG, "FCM token received before SDK init – will register on next initialize().")
                return@launch
            }
            registrar.handleTokenRefresh(token)
                .onFailure { error ->
                    VisionPushLogger.e(TAG, "Token refresh registration failed: ${error.message}")
                    // Enqueue a WorkManager retry so the token eventually reaches the backend
                    VisionPush.scheduleRegistrationSync(applicationContext)
                }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    // ── Internal ────────────────────────────────────────────────────────────

    /**
     * Processes a received [VisionPushNotification].
     *
     * If the host app has registered a custom handler via
     * [VisionPush.setNotificationHandler] and [notification.showSystemNotification]
     * is `false`, the notification is forwarded to the handler only.
     * Otherwise the SDK builds and posts a system notification.
     */
    private suspend fun processNotification(notification: VisionPushNotification) {
        val customHandler = VisionPush.internalNotificationHandler

        if (customHandler != null) {
            VisionPushLogger.d(TAG, "Delegating notification to custom handler.")
            customHandler(notification)
        }

        if (notification.showSystemNotification && VisionPush.foregroundNotificationEnabled) {
            val builder = VisionPush.internalNotificationBuilder
            if (builder != null) {
                builder.show(notification)
            } else {
                VisionPushLogger.w(
                    TAG,
                    "SDK not initialized – cannot display system notification id=${notification.id}"
                )
            }
        }
    }

    // ── Companion ───────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "VP.MessagingService"
    }
}
