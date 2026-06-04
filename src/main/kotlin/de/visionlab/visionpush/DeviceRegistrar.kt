package de.visionlab.visionpush

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.app.NotificationManagerCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import android.os.Build
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.UUID

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
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Fallback: some vendor ROMs or very old devices may fail to provision the
            // Android Keystore. Plain SharedPreferences is still MODE_PRIVATE (no other
            // app can read it without root), so this is an acceptable degradation.
            VisionPushLogger.w(TAG, "EncryptedSharedPreferences unavailable – using plain storage: ${e.message}")
            context.getSharedPreferences("${PREFS_FILE_NAME}_plain", Context.MODE_PRIVATE)
        }
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

    /**
     * Stable anonymous identifier for this device installation. Generated once on first
     * launch and persisted. Resets only when app data is cleared or on reinstall.
     * Does not require any permissions.
     */
    internal val anonymousId: String
        get() = prefs.getString(KEY_ANONYMOUS_ID, null) ?: UUID.randomUUID().toString().also { uuid ->
            prefs.edit().putString(KEY_ANONYMOUS_ID, uuid).apply()
        }

    /**
     * The device's sealed-payload keys, generated + persisted in
     * EncryptedSharedPreferences on first use. The base64url public parts are sent
     * at registration; the private key never leaves the device. Exposed so the SDK
     * can set [VisionPushMessagingService.sealedKeysProvider] for decryption.
     */
    internal val sealedKeys: VisionPushSeal.DeviceKeys
        // Synchronized: accessed from both the main thread (registration) and the FCM
        // background thread (sealedKeysProvider in onMessageReceived).
        @Synchronized get() {
            prefs.getString(KEY_SEALED_KEYS, null)?.let { raw ->
                try {
                    val o = org.json.JSONObject(raw)
                    return VisionPushSeal.DeviceKeys(
                        VisionPushSeal.b64urlDecode(o.getString("priv")),
                        VisionPushSeal.b64urlDecode(o.getString("pub")),
                        VisionPushSeal.b64urlDecode(o.getString("auth")),
                    )
                } catch (_: Exception) {
                    // fall through and regenerate on corruption
                }
            }
            val generated = VisionPushSeal.generateKeys()
            val stored = org.json.JSONObject()
                .put("priv", VisionPushSeal.b64urlEncode(generated.keys.privateKey))
                .put("pub", generated.pubkeyB64)
                .put("auth", generated.authB64)
            prefs.edit().putString(KEY_SEALED_KEYS, stored.toString()).apply()
            return generated.keys
        }

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
    suspend fun register(
        forceRefresh: Boolean = false,
        initialTags: Map<String, String> = emptyMap()
    ): VisionPushResult<String> {
        val token = fetchFcmToken() ?: return VisionPushResult.Failure(
            VisionPushError.TokenUnavailable()
        )

        val registration = buildRegistration(token, initialTags)
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
        val id = deviceId
            ?: return VisionPushResult.Failure(VisionPushError.TokenUnavailable())
        return if (optedOut) {
            apiClient.optOut(id)
        } else {
            apiClient.optIn(id)
        }
    }

    /**
     * Deletes device registration data from the VisionPush backend and clears
     * all locally persisted state. Intended to be called during user logout.
     */
    suspend fun unregister(): VisionPushResult<Unit> {
        val id = deviceId
        val result = if (id != null) {
            apiClient.deleteDevice(id)
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
    private fun buildRegistration(token: String, tags: Map<String, String> = emptyMap()): DeviceRegistration {
        val currentLocale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.resources.configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            context.resources.configuration.locale
        }
        val locale = currentLocale.toLanguageTag()
        val country = currentLocale.country.takeIf { it.isNotBlank() }

        val pkgInfo = try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) { null }

        val appVersion = pkgInfo?.versionName ?: "unknown"

        @Suppress("DEPRECATION")
        val appBuildNumber = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkgInfo?.longVersionCode?.toString()
        } else {
            pkgInfo?.versionCode?.toString()
        }

        val tz = java.util.TimeZone.getDefault()
        val timezone = try { tz.id } catch (_: Exception) { null }
        val timezoneOffset = tz.rawOffset / 60_000  // raw offset in minutes (no DST)

        val environment = if (config.apiKey.startsWith("vp_test_")) "sandbox" else "production"

        val screenLayout = context.resources.configuration.screenLayout and
                android.content.res.Configuration.SCREENLAYOUT_SIZE_MASK
        val deviceType = when {
            screenLayout >= android.content.res.Configuration.SCREENLAYOUT_SIZE_LARGE -> "tablet"
            else -> "phone"
        }

        // Sealed-payload public key (generated + persisted on first use). Sent so the
        // server can seal content to this device; the private key stays on-device.
        val sealed = sealedKeys

        return DeviceRegistration(
            appId = config.appId,
            pushToken = token,
            externalId = externalId,
            installationId = anonymousId,
            anonymousId = anonymousId,
            locale = locale,
            country = country,
            deviceOs = "Android ${Build.VERSION.RELEASE}",
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            appVersion = appVersion,
            timezone = timezone,
            timezoneOffset = timezoneOffset,
            environment = environment,
            bundleId = context.packageName,
            appBuildNumber = appBuildNumber,
            deviceType = deviceType,
            deviceManufacturer = Build.MANUFACTURER,
            networkType = currentNetworkType(),
            notificationPermission = currentNotificationPermission(),
            tags = tags.takeIf { it.isNotEmpty() },
            sealedPubkey = VisionPushSeal.b64urlEncode(sealed.publicKey),
            sealedKeyAlg = VisionPushSeal.ALG,
            sealedAuth = VisionPushSeal.b64urlEncode(sealed.auth),
        )
    }

    private fun currentNetworkType(): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return "unknown"
            val network = cm.activeNetwork ?: return "none"
            val caps = cm.getNetworkCapabilities(network) ?: return "none"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                else -> "other"
            }
        } catch (_: SecurityException) {
            "unknown"
        }
    }

    private fun currentNotificationPermission(): String =
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) "granted" else "denied"

    /** Clears all SDK-persisted state from SharedPreferences. */
    private fun clearLocalState() {
        prefs.edit().clear().apply()
    }

    /**
     * Produces a compact string that uniquely represents the current registration
     * state. Used for change-detection to avoid unnecessary API calls.
     */
    private fun DeviceRegistration.stateKey(): String =
        "${pushToken}|${externalId}|${anonymousId}|${locale}|${country}|${deviceOs}|${environment}|${bundleId}|${appBuildNumber}|${deviceType}|${deviceManufacturer}|${networkType}|${notificationPermission}"

    // ── Companion ───────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "VP.DeviceRegistrar"
        private const val PREFS_FILE_NAME = "visionpush_device"
        private const val KEY_LAST_TOKEN = "last_fcm_token"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_EXTERNAL_ID = "external_id"
        private const val KEY_OPTED_OUT = "opted_out"
        private const val KEY_LAST_STATE = "last_reg_state"
        private const val KEY_ANONYMOUS_ID = "anonymous_id"
        private const val KEY_SEALED_KEYS = "sealed_keys"
    }
}
