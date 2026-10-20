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

    /**
     * `true` when [prefs] is the plain fallback file because EncryptedSharedPreferences
     * could not be opened. The typical cause is a backup restore: the Keystore master
     * key does not travel with the backup, so the restored encrypted file is
     * unreadable and the SDK starts from an EMPTY store — new installation id, new
     * sealed keys. Non-secret state (opt-out) is kept in [statePrefs] so it survives.
     */
    @Volatile internal var usingFallbackStorage: Boolean = false
        private set

    private val prefs: SharedPreferences by lazy {
        openEncryptedPrefs(context) { e ->
            // Fallback: some vendor ROMs or very old devices may fail to provision the
            // Android Keystore, and a backup restore leaves the encrypted file without
            // its master key. Plain SharedPreferences is still MODE_PRIVATE (no other
            // app can read it without root). This is NOT silent: the device identity is
            // lost, so log at ERROR — hosts that ship the SDK's backup exclusion rules
            // (see README "Backup & Restore") avoid the restore case entirely.
            usingFallbackStorage = true
            VisionPushLogger.e(
                TAG,
                "EncryptedSharedPreferences unavailable – falling back to plain storage. " +
                        "Installation id and sealed keys will be regenerated (a new device row); " +
                        "the opt-out state is restored from the plain state file. Cause: ${e.message}",
                e
            )
        }
    }

    /**
     * Non-secret registration state kept OUTSIDE the encrypted file (plain,
     * MODE_PRIVATE) so it survives a lost master key. Currently: the opt-out flag —
     * an opted-out user must never be silently re-subscribed by a restore.
     */
    private val statePrefs: SharedPreferences by lazy {
        context.getSharedPreferences(STATE_PREFS_FILE_NAME, Context.MODE_PRIVATE)
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

    /**
     * The US-15 user this device's subscription currently belongs to (anonymous
     * until a [VisionPush.login]). Persisted exactly the way [deviceId] is —
     * same encrypted store, survives process restarts, wiped by [clearLocalState].
     *
     * [onUserIdChanged] fires whenever a write actually changes the value, so
     * [VisionPush]'s user-change listener observes every login/logout, not just
     * ones a caller happened to be awaiting.
     */
    internal var userId: String?
        get() = prefs.getString(KEY_USER_ID, null)
        private set(value) {
            val previous = prefs.getString(KEY_USER_ID, null)
            prefs.edit().putString(KEY_USER_ID, value).apply()
            if (value != null && value != previous) onUserIdChanged?.invoke(value)
        }

    /** Set by [VisionPush] to forward every [userId] change to its public listener. */
    @Volatile internal var onUserIdChanged: ((String?) -> Unit)? = null

    /**
     * Caller-supplied external user identifier (nullable).
     *
     * Setting `null` after a non-null value marks a server-side clear as pending
     * ([isExternalIdClearPending]): the register upsert treats an omitted
     * `external_id` as "unchanged", so the next [register] first sends an explicit
     * `PUT /devices/{id} {"clear_external_id": true}`.
     */
    internal var externalId: String?
        get() = prefs.getString(KEY_EXTERNAL_ID, null)
        set(value) = setExternalIdentity(value, hash = null)

    /**
     * The identity-verification hash stored next to [externalId] (same encrypted store,
     * same lifetime: wiped by [clearLocalState]). Re-sent with the id on every
     * registration — e.g. after an FCM token rotation — so a verified association
     * survives re-registration. `null` when the host never supplied one.
     */
    internal val externalIdHash: String?
        get() = prefs.getString(KEY_EXTERNAL_ID_HASH, null)

    /**
     * Persists [externalId] together with its identity-verification [hash] in ONE edit,
     * so a crash between two writes can never leave a hash that belongs to a different
     * id. A hash is bound to its id: it is stored only alongside a non-null id, and
     * setting an id WITHOUT a hash drops any previously stored one (it would be wrong
     * for the new id anyway). Clearing (`null`) marks the explicit server-side clear as
     * pending exactly like the [externalId] setter always did.
     */
    internal fun setExternalIdentity(externalId: String?, hash: String?) {
        val previous = prefs.getString(KEY_EXTERNAL_ID, null)
        val editor = prefs.edit().putString(KEY_EXTERNAL_ID, externalId)
        if (externalId != null && hash != null) {
            editor.putString(KEY_EXTERNAL_ID_HASH, hash)
        } else {
            editor.remove(KEY_EXTERNAL_ID_HASH)
        }
        if (externalId == null) {
            if (previous != null) editor.putBoolean(KEY_EXTERNAL_ID_CLEAR_PENDING, true)
        } else {
            editor.putBoolean(KEY_EXTERNAL_ID_CLEAR_PENDING, false)
        }
        editor.apply()
    }

    /**
     * Sets (or clears) the external identity and pushes it to the backend right away.
     *
     * When the server rejects the id with 403 `external_id_verification_failed` (identity
     * verification enabled, hash missing/wrong) the PREVIOUS identity is restored locally:
     * the device keeps its last accepted association and stays syncable, instead of
     * re-sending an unverifiable id on every later sync. The failure is returned as-is —
     * [ApiClient] never retries a 4xx other than 429.
     */
    suspend fun updateExternalIdentity(externalId: String?, hash: String?): VisionPushResult<String> {
        val previous = ExternalIdentitySnapshot(this.externalId, externalIdHash, isExternalIdClearPending)
        setExternalIdentity(externalId, hash)
        val result = register(forceRefresh = true)
        val error = result.errorOrNull() as? VisionPushError.ApiError
        if (error?.isExternalIdVerificationFailed == true) {
            prefs.edit()
                .putString(KEY_EXTERNAL_ID, previous.externalId)
                .putString(KEY_EXTERNAL_ID_HASH, previous.hash)
                .putBoolean(KEY_EXTERNAL_ID_CLEAR_PENDING, previous.clearPending)
                .apply()
            VisionPushLogger.w(
                TAG,
                "Restored the previous external identity (${previous.externalId ?: "none"}) after the rejection."
            )
        }
        return result
    }

    /** Immutable capture of the external identity, for rollback after a rejection. */
    private data class ExternalIdentitySnapshot(val externalId: String?, val hash: String?, val clearPending: Boolean)

    /** `true` while a `setExternalId(null)` still has to be pushed to the backend. */
    internal var isExternalIdClearPending: Boolean
        get() = prefs.getBoolean(KEY_EXTERNAL_ID_CLEAR_PENDING, false)
        private set(value) = prefs.edit().putBoolean(KEY_EXTERNAL_ID_CLEAR_PENDING, value).apply()

    /**
     * Whether the user has explicitly opted out of push notifications.
     *
     * Written to BOTH the (encrypted) [prefs] and the plain [statePrefs]; read from the
     * encrypted store when it holds a value, otherwise from the plain one — so after
     * the encrypted store is lost (see [usingFallbackStorage]) the opt-out is
     * preserved instead of silently reverting to opted-in.
     */
    internal var isOptedOut: Boolean
        get() = if (prefs.contains(KEY_OPTED_OUT)) {
            prefs.getBoolean(KEY_OPTED_OUT, false)
        } else {
            statePrefs.getBoolean(KEY_OPTED_OUT, false)
        }
        set(value) {
            prefs.edit().putBoolean(KEY_OPTED_OUT, value).apply()
            statePrefs.edit().putBoolean(KEY_OPTED_OUT, value).apply()
        }

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
     * The anonymous id **without minting one** — `null` until a registration
     * (or any other read of [anonymousId]) has created it.
     *
     * The device-binding header reads this at SDK init, and merely starting the
     * SDK is not a registration: reading [anonymousId] there would write an
     * identity for a host app that never registers.
     */
    internal val existingAnonymousId: String?
        get() = prefs.getString(KEY_ANONYMOUS_ID, null)

    /**
     * [anonymousId], published to [ApiClient] so every later request carries the
     * device-binding header. This is the read that is allowed to create an
     * identity: it happens while building a registration payload, i.e. after
     * the host app asked to register.
     */
    private val publishedAnonymousId: String
        get() = anonymousId.also { apiClient.installationId = it }

    /**
     * Seeds [anonymousId] with [id] when no id is persisted yet — see
     * [VisionPush.adoptInstallationId]. No-op, returning the existing id unchanged,
     * once an id is already stored: an established identity is never overwritten.
     *
     * @throws VisionPushError.InvalidArgument if [id] is not a valid UUID string.
     */
    internal fun adoptAnonymousId(id: String): String {
        val normalized = validateInstallationId(id)
        val existing = prefs.getString(KEY_ANONYMOUS_ID, null)
        if (existing != null) return existing
        prefs.edit().putString(KEY_ANONYMOUS_ID, normalized).apply()
        return normalized
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
    ): VisionPushResult<String> = registerForOutcome(forceRefresh, initialTags).map { it.deviceId }

    /**
     * Same as [register] but keeps the server's `user_id` (US-15) in the result
     * instead of discarding it — [VisionPush.login] / [VisionPush.logout] need
     * the fresh user id a login/logout's forced re-registration just produced.
     */
    internal suspend fun registerForOutcome(
        forceRefresh: Boolean = false,
        initialTags: Map<String, String> = emptyMap()
    ): VisionPushResult<DeviceRegistrationOutcome> {
        val token = fetchToken() ?: return VisionPushResult.Failure(
            VisionPushError.TokenUnavailable()
        )

        // A pending setExternalId(null) needs its own explicit PUT before the upsert —
        // omitting external_id from the registration leaves the stored value untouched.
        flushPendingExternalIdClear()

        val registration = buildRegistration(token, initialTags)
        val stateKey = registration.stateKey()

        // Skip if the device state has not changed and forceRefresh is not set
        if (!forceRefresh && lastRegisteredToken == token && prefs.getString(KEY_LAST_STATE, null) == stateKey) {
            VisionPushLogger.d(TAG, "Device state unchanged – skipping registration.")
            return VisionPushResult.Success(DeviceRegistrationOutcome(deviceId ?: "", userId))
        }

        VisionPushLogger.i(TAG, "Registering device with VisionPush backend.")
        val previousDeviceId = deviceId
        val result = apiClient.registerDevice(registration)
        when (result) {
            is VisionPushResult.Success -> {
                val outcome = result.data
                val id = outcome.deviceId
                lastRegisteredToken = token
                deviceId = id
                outcome.userId?.let { userId = it }
                prefs.edit().putString(KEY_LAST_STATE, stateKey).apply()
                VisionPushLogger.i(TAG, "Device registered successfully. deviceId=$id")
                // A NEW device row (first registration, or a re-created identity after
                // the encrypted store was lost) starts opted-in on the server. Replay a
                // locally persisted opt-out so the user is not silently re-subscribed.
                if (isOptedOut && id != previousDeviceId) {
                    apiClient.optOut(id).onFailure { error ->
                        VisionPushLogger.w(TAG, "Could not replay opt-out for the new device row: ${error.message}")
                    }
                }
            }
            is VisionPushResult.Failure -> {
                VisionPushLogger.e(TAG, "Device registration failed: ${result.error.message}")
                handleVerificationFailure(result.error)
            }
        }
        return result
    }

    /**
     * HTTP 403 `external_id_verification_failed`: identity verification is enabled for
     * the app and the registration set `external_id` without a valid `external_id_hash`.
     * The rejection is deterministic — [ApiClient] does not retry it — so it is logged at
     * ERROR with the fix, and the unverified id + hash are dropped locally: otherwise
     * every later sync (token rotation, WorkManager retry) would carry the same id and be
     * rejected again, leaving the device permanently unregistrable. Nothing changed
     * server-side (an omitted `external_id` means "keep the stored value"), so the
     * server keeps whatever association it already had; the pending-clear flag is left
     * untouched for the same reason. No-op for any other error.
     */
    private fun handleVerificationFailure(error: VisionPushError) {
        val apiError = error as? VisionPushError.ApiError ?: return
        if (!apiError.isExternalIdVerificationFailed) return
        VisionPushLogger.e(TAG, VERIFICATION_FAILED_MESSAGE)
        prefs.edit().remove(KEY_EXTERNAL_ID).remove(KEY_EXTERNAL_ID_HASH).apply()
    }

    /**
     * Pushes a pending `setExternalId(null)` to the backend as an explicit
     * `clear_external_id` PUT. Kept pending (and retried on the next sync) when the
     * call fails; dropped when the device was never registered — nothing to clear.
     */
    private suspend fun flushPendingExternalIdClear() {
        if (!isExternalIdClearPending) return
        val id = deviceId
        if (id == null) {
            isExternalIdClearPending = false
            return
        }
        apiClient.clearExternalId(id)
            .onSuccess {
                isExternalIdClearPending = false
                VisionPushLogger.i(TAG, "External ID cleared on backend.")
            }
            .onFailure { error ->
                VisionPushLogger.w(TAG, "Clearing external ID failed – will retry on the next sync: ${error.message}")
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
        return apiClient.registerDevice(registration)
            .onSuccess { outcome ->
                lastRegisteredToken = newToken
                deviceId = outcome.deviceId
                outcome.userId?.let { userId = it }
                prefs.edit().putString(KEY_LAST_STATE, registration.stateKey()).apply()
            }
            .onFailure { error -> handleVerificationFailure(error) }
            .map { it.deviceId }
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
     * Resolves the current push token. When a [VisionPush.pushTokenProvider] is
     * installed (e.g. by the Huawei SDK to return an HMS token) it is the sole
     * source — no Firebase fallback, so a pure-HMS build never touches FCM. With
     * no provider the SDK fetches the FCM token from Firebase ([fetchFcmToken]).
     */
    private suspend fun fetchToken(): String? {
        val provider = VisionPush.pushTokenProvider
        return if (provider != null) provider.invoke() else fetchFcmToken()
    }

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
            platform = config.platform,
            externalId = externalId,
            installationId = publishedAnonymousId,
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
            externalIdHash = externalIdHash,
        )
    }

    private fun currentNetworkType(): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return "unknown"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = cm.activeNetwork ?: return "none"
                val caps = cm.getNetworkCapabilities(network) ?: return "none"
                when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                    else -> "other"
                }
            } else {
                legacyNetworkType(cm)
            }
        } catch (_: SecurityException) {
            "unknown"
        }
    }

    /**
     * API 21–22 fallback: [ConnectivityManager.getActiveNetwork] only exists from
     * API 23, and the pre-M signal is the (since API 29 deprecated) NetworkInfo API.
     */
    @Suppress("DEPRECATION")
    private fun legacyNetworkType(cm: ConnectivityManager): String {
        val info = cm.activeNetworkInfo ?: return "none"
        if (!info.isConnected) return "none"
        return when (info.type) {
            ConnectivityManager.TYPE_WIFI -> "wifi"
            ConnectivityManager.TYPE_MOBILE -> "cellular"
            else -> "other"
        }
    }

    private fun currentNotificationPermission(): String =
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) "granted" else "denied"

    /** Clears all SDK-persisted state from SharedPreferences (encrypted + plain state). */
    private fun clearLocalState() {
        // The next request must stop presenting the wiped identity.
        apiClient.installationId = null
        prefs.edit().clear().apply()
        statePrefs.edit().clear().apply()
    }

    /**
     * Produces a compact string that uniquely represents the current registration
     * state. Used for change-detection to avoid unnecessary API calls.
     */
    private fun DeviceRegistration.stateKey(): String =
        "${pushToken}|${externalId}|${externalIdHash}|${anonymousId}|${locale}|${country}|${deviceOs}|${environment}|${bundleId}|${appBuildNumber}|${deviceType}|${deviceManufacturer}|${networkType}|${notificationPermission}"

    // ── Companion ───────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "VP.DeviceRegistrar"
        /** Encrypted store (EncryptedSharedPreferences). Exclude from backups — see README. */
        internal const val PREFS_FILE_NAME = "visionpush_device"

        /** Plain fallback used when the encrypted store cannot be opened. Exclude from backups. */
        internal const val FALLBACK_PREFS_FILE_NAME = "${PREFS_FILE_NAME}_plain"

        /** Plain, non-secret state (opt-out) that must SURVIVE a lost master key. */
        internal const val STATE_PREFS_FILE_NAME = "visionpush_device_state"

        private const val KEY_LAST_TOKEN = "last_fcm_token"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_EXTERNAL_ID = "external_id"
        private const val KEY_EXTERNAL_ID_HASH = "external_id_hash"
        private const val KEY_EXTERNAL_ID_CLEAR_PENDING = "external_id_clear_pending"

        /** ERROR-level log line for the server's `external_id_verification_failed` rejection. */
        internal const val VERIFICATION_FAILED_MESSAGE =
            "External ID rejected (HTTP 403 external_id_verification_failed): identity verification " +
                    "is enabled for this app — pass the hash your backend computed (lowercase hex HMAC-SHA256 " +
                    "of the external id, keyed with the app's identity secret) via " +
                    "VisionPush.login(externalId, externalIdHash). The request is not retried."
        private const val KEY_OPTED_OUT = "opted_out"
        private const val KEY_LAST_STATE = "last_reg_state"
        private const val KEY_ANONYMOUS_ID = "anonymous_id"
        private const val KEY_SEALED_KEYS = "sealed_keys"

        /**
         * Opens the same encrypted device-state store the instance [prefs] property uses,
         * falling back to plain SharedPreferences when the Android Keystore master key
         * cannot be provisioned/opened. [onFallback] lets each caller react (set
         * [usingFallbackStorage], log) without duplicating the open/catch logic — shared by
         * the instance [prefs] lazy and the pre-initialize seed path [seedAnonymousId].
         */
        private fun openEncryptedPrefs(
            context: Context,
            onFallback: (Exception) -> Unit
        ): SharedPreferences = try {
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
            onFallback(e)
            context.getSharedPreferences(FALLBACK_PREFS_FILE_NAME, Context.MODE_PRIVATE)
        }

        /**
         * Validates [id] is a UUID string, throwing [VisionPushError.InvalidArgument] (named
         * after the offending value) otherwise. Shared by the instance [adoptAnonymousId]
         * and the static pre-initialize [seedAnonymousId] so both reject the same way.
         */
        internal fun validateInstallationId(id: String): String =
            try {
                UUID.fromString(id).toString()
            } catch (e: IllegalArgumentException) {
                throw VisionPushError.InvalidArgument(
                    "adoptInstallationId: '$id' is not a valid UUID."
                )
            }

        /**
         * Seeds the installation id BEFORE a [DeviceRegistrar] instance exists — the path
         * [VisionPush.adoptInstallationId] takes when called ahead of [VisionPush.initialize],
         * e.g. a Unity player migrating from the REST bridge to this native core. Opens the
         * same store [prefs] would, so a [DeviceRegistrar] created afterwards picks up the
         * seeded id as its [anonymousId] / [existingAnonymousId] instead of minting a new one.
         *
         * No-op, returning the existing id unchanged, once an id is already stored.
         *
         * @throws VisionPushError.InvalidArgument if [id] is not a valid UUID string.
         */
        internal fun seedAnonymousId(context: Context, id: String): String {
            val normalized = validateInstallationId(id)
            val store = openEncryptedPrefs(context) { e ->
                VisionPushLogger.e(
                    TAG,
                    "EncryptedSharedPreferences unavailable – seeding installation id into " +
                            "plain fallback storage. Cause: ${e.message}",
                    e
                )
            }
            val existing = store.getString(KEY_ANONYMOUS_ID, null)
            if (existing != null) return existing
            store.edit().putString(KEY_ANONYMOUS_ID, normalized).apply()
            return normalized
        }
    }
}
