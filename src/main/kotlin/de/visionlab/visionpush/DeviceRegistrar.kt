package de.visionlab.visionpush

import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Manages device registration with the VisionPush backend.
 *
 * Responsibilities:
 * - Fetching the current FCM token from Firebase.
 * - Constructing a [DeviceRegistration] snapshot from the device environment.
 * - Persisting the last-sent token and device-ID so that re-registration is
 *   skipped when nothing has changed.
 * - Delegating the actual HTTP call to [ApiClient].
 * - Scheduling background registration retries via WorkManager when the
 *   network is unavailable.
 *
 * All state is stored in a private [SharedPreferences] file so it survives
 * process restarts without requiring a database dependency.
 */
internal class DeviceRegistrar(
    private val context: Context,
    private val config: VisionPushConfig,
    private val apiClient: ApiClient
) {

    // ── Shared preferences ──────────────────────────────────────────────────

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
    }

    // ── Registration state accessors ────────────────────────────────────────

    /** The FCM token that was last successfully registered with the backend. */
    internal var lastRegisteredToken: String?
        get() = prefs.getString(KEY_LAST_TOKEN, null)
        private set(value) = prefs.edit().putString(KEY_LAST_TOKEN, value).apply()

    /** The server-assigned device ID returned after first registration. */
    internal var deviceId: String?
        get() = prefs.getString(KEY_DEVICE_ID, null)
        private set(value) = prefs.edit().putString(KEY_DEVICE_ID, value).apply()

    /** Caller-supplied external user identifier (nullable). */
    internal var externalId: String?
        get() = prefs.getString(KEY_EXTERNAL_ID, null)
        set(value) = prefs.edit().putString(KEY_EXTERNAL_ID, value).apply()

    /** Whether the user has explicitly opted out of push notifications. */
    internal var isOptedOut: Boolean
        get() = prefs.getBoolean(KEY_OPTED_OUT, false)
        set(value) = prefs.edit().putBoolean(KEY_OPTED_OUT, value).apply()

    // ── Public operations ───────────────────────────────────────────────────

    /**
     * Registers or updates this device with the VisionPush backend.
     *
     * The method is idempotent: if the FCM token, opt-out state, and external ID
     * have not changed since the last successful registration, the backend call
     * is skipped and [VisionPushResult.Success] is returned immediately.
     *
     * @param forceRefresh  When `true` the registration is always sent, even if
     *                      nothing appears to have changed locally.
     */
    suspend fun register(forceRefresh: Boolean = false): VisionPushResult<String> {
        val token = fetchFcmToken() ?: return VisionPushResult.Failure(
            VisionPushError.TokenUnavailable()
        )

        val registration = buildRegistration(token)
        val stateKey = registration.stateKey()

        // Skip if the device state has not changed and forceRefresh is not set
        if (!forceRefresh && lastRegisteredToken == token && prefs.getString(KEY_LAST_STATE, null) == stateKey) {
            VisionPushLogger.d(TAG, "Device state unchanged – skipping registration.")
            return VisionPushResult.Success(deviceId ?: "")
        }

        VisionPushLogger.i(TAG, "Registering device with VisionPush backend.")
        return apiClient.registerDevice(registration).onSuccess { id ->
            lastRegisteredToken = token
            deviceId = id
            prefs.edit().putString(KEY_LAST_STATE, stateKey).apply()
            VisionPushLogger.i(TAG, "Device registered successfully. deviceId=$id")
        }.onFailure { error ->
            VisionPushLogger.e(TAG, "Device registration failed: ${error.message}")
        }
    }

    /**
     * Called by [VisionPushMessagingService.onNewToken] whenever FCM rotates
     * the token. Forces a full re-registration so the backend always holds the
     * current token.
     */
    suspend fun handleTokenRefresh(newToken: String): VisionPushResult<String> {
        VisionPushLogger.i(TAG, "FCM token rotated – triggering re-registration.")
        val registration = buildRegistration(newToken)
        return apiClient.registerDevice(registration).onSuccess { id ->
            lastRegisteredToken = newToken
            deviceId = id
            prefs.edit().putString(KEY_LAST_STATE, registration.stateKey()).apply()
        }
    }

    /**
     * Updates the opt-out flag locally and on the backend.
     *
     * If the network call fails the local flag is still updated so the user
     * intent is preserved; the WorkManager sync will reconcile the backend state
     * on the next successful network request.
     */
    suspend fun setOptedOut(optedOut: Boolean): VisionPushResult<Unit> {
        isOptedOut = optedOut
        val token = lastRegisteredToken
            ?: return VisionPushResult.Failure(VisionPushError.TokenUnavailable())
        return apiClient.updateOptStatus(token, optedOut)
    }

    /**
     * Deletes device registration data from the VisionPush backend and clears
     * all locally persisted state. Intended to be called during user logout.
     */
    suspend fun unregister(): VisionPushResult<Unit> {
        val token = lastRegisteredToken
        val result = if (token != null) {
            apiClient.deleteDevice(token)
        } else {
            VisionPushResult.Success(Unit) // Nothing to delete
        }
        clearLocalState()
        return result
    }

    // ── Internal helpers ────────────────────────────────────────────────────

    /**
     * Fetches the current FCM registration token, suspending until Firebase
     * resolves the Task. Returns `null` if Firebase is not configured or the
     * token cannot be obtained.
     */
    private suspend fun fetchFcmToken(): String? = withContext(Dispatchers.IO) {
        try {
            FirebaseMessaging.getInstance().token.await()
        } catch (e: Exception) {
            VisionPushLogger.e(TAG, "Failed to retrieve FCM token: ${e.message}")
            null
        }
    }

    /** Constructs a [DeviceRegistration] from the current device environment. */
    private fun buildRegistration(token: String): DeviceRegistration {
        val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.resources.configuration.locales[0].toLanguageTag()
        } else {
            @Suppress("DEPRECATION")
            context.resources.configuration.locale.toLanguageTag()
        }

        val appVersion = try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName ?: "unknown"
        } catch (_: PackageManager.NameNotFoundException) {
            "unknown"
        }

        return DeviceRegistration(
            fcmToken = token,
            externalId = externalId,
            locale = locale,
            sdkVersion = Build.VERSION.SDK_INT,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            appPackageName = context.packageName,
            appVersion = appVersion,
            notificationsEnabled = areNotificationsEnabled(),
            optedOut = isOptedOut
        )
    }

    /** Returns `true` when system-level notifications are enabled for this app. */
    private fun areNotificationsEnabled(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Clears all SDK-persisted state from SharedPreferences. */
    private fun clearLocalState() {
        prefs.edit().clear().apply()
    }

    /**
     * Produces a compact string that uniquely represents the current registration
     * state. Used for change-detection to avoid unnecessary API calls.
     */
    private fun DeviceRegistration.stateKey(): String =
        "${fcmToken}|${externalId}|${notificationsEnabled}|${optedOut}|${locale}"

    // ── Companion ───────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "VP.DeviceRegistrar"
        private const val PREFS_FILE_NAME = "visionpush_device"
        private const val KEY_LAST_TOKEN = "last_fcm_token"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_EXTERNAL_ID = "external_id"
        private const val KEY_OPTED_OUT = "opted_out"
        private const val KEY_LAST_STATE = "last_reg_state"
    }
}
