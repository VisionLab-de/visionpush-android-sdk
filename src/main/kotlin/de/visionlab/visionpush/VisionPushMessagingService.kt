package de.visionlab.visionpush

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

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

        // Unseal (Mode A/B) → parse → cache → dispatch, all via the shared pipeline
        // so the FCM and HMS receive paths behave identically. Pass-through +
        // fail-closed for non-VisionPush / undecryptable messages.
        VisionPush.handleRemoteData(message.data, sealedKeysProvider?.invoke())
    }

    /**
     * Called by the FCM SDK when the registration token is refreshed.
     *
     * Token rotation can happen at any time (e.g. after an app restore, or when
     * FCM invalidates stale tokens). The SDK re-registers the device immediately
     * and enqueues a WorkManager retry if the network is unavailable.
     *
     * If the SDK has not yet been initialized when this callback fires (i.e.
     * [VisionPush.initialize] has not been called yet), the token cannot be
     * forwarded immediately. In this case a WorkManager sync is scheduled so
     * that once the SDK is initialized the device registration picks up the
     * latest token automatically.
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Delegate to the shared token pipeline (re-register or schedule a sync).
        VisionPush.handleNewToken(applicationContext, token)
    }

    // ── Companion ───────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "VP.MessagingService"

        /**
         * Supplies this device's sealed-payload keys so a sealed push (Mode A/B) can
         * be decrypted in [onMessageReceived]. The host/SDK loads the keypair from
         * the Android Keystore (EncryptedSharedPreferences) and sets this once. If
         * unset or it returns null, sealed pushes keep their neutral placeholder
         * content (fail closed — never the opaque envelope).
         */
        @JvmStatic
        var sealedKeysProvider: (() -> VisionPushSeal.DeviceKeys?)? = null
    }
}
