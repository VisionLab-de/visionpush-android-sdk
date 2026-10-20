package de.visionlab.visionpush

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections

/**
 * Primary entry point for the VisionPush Android SDK.
 *
 * This object is the sole public-facing singleton. All host-application code
 * should go through this class exclusively; direct access to internal components
 * is intentionally not exposed.
 *
 * ## Initialization
 *
 * Call [initialize] once in `Application.onCreate()`:
 *
 * ```kotlin
 * class MyApp : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         VisionPush.initialize(
 *             context = this,
 *             appId  = "your-app-uuid",
 *             apiKey = "vp_live_xxxxxxxxxxxxxxxx"
 *         )
 *     }
 * }
 * ```
 *
 * ## Requesting notification permission (Android 13+)
 *
 * ```kotlin
 * VisionPush.requestPermission(activity)
 * ```
 *
 * ## Identifying the logged-in user
 *
 * ```kotlin
 * VisionPush.login("user_123")
 * // With identity verification enabled for the app: pass the HMAC your backend computed
 * VisionPush.login("user_123", externalIdHash = hashFromYourBackend)
 * // After sign-out: hand the subscription to a new anonymous user
 * VisionPush.logout()
 * ```
 *
 * ## Custom notification handling
 *
 * ```kotlin
 * VisionPush.setNotificationHandler { notification ->
 *     // Invoked on the main thread when a notification arrives
 * }
 * ```
 *
 * ## Opt-out
 *
 * ```kotlin
 * VisionPush.optOut()  // suppress future notifications
 * VisionPush.optIn()   // re-enable
 * ```
 *
 * ## Java compatibility
 *
 * Every `@JvmStatic` method can be called from Java without the `INSTANCE`
 * qualifier:
 *
 * ```java
 * VisionPush.initialize(context, "uuid", "vp_live_xxx");
 * ```
 */
object VisionPush {

    // ── SDK version (exposed for host-app diagnostics) ──────────────────────

    /** Current VisionPush SDK version string. */
    const val SDK_VERSION: String = BuildConfig.VISIONPUSH_SDK_VERSION

    // ── Internal component references ───────────────────────────────────────
    // These are internal (visible within the same module) and set during initialize().

    @Volatile internal var internalApiClient: ApiClient? = null
    @Volatile internal var internalDeviceRegistrar: DeviceRegistrar? = null
    @Volatile internal var internalNotificationBuilder: NotificationBuilder? = null
    @Volatile internal var internalIamController: InAppMessageController? = null

    /**
     * On-device geofencing manager — created lazily by [enableGeofencing] and torn
     * down by [disableGeofencing] / [deleteDevice]. Null until geofencing is enabled.
     */
    @Volatile internal var internalGeofenceManager: GeofenceManager? = null

    /**
     * Owned-transport realtime channel — created lazily by [enableRealtime] and
     * torn down by [disableRealtime] / [deleteDevice].
     */
    @Volatile internal var internalRealtimeChannel: RealtimeChannel? = null

    /** Optional observer for realtime connection-state changes (set via [setRealtimeStatusHandler]). */
    @Volatile private var realtimeStatusHandler: ((RealtimeConnectionState) -> Unit)? = null

    /**
     * `true` when [enableRealtime] was called before device registration completed and
     * the connect is deferred until [notifyDeviceRegistered] runs.
     */
    @Volatile private var realtimeDeferred = false

    /** The explicit installation id passed to the deferred [enableRealtime] call ("" = SDK default). */
    @Volatile private var realtimeDeferredInstallationId: String = ""

    /** Test seam: transport used by [enableRealtime] instead of the OkHttp factory. */
    @Volatile internal var realtimeTransportFactory: RealtimeChannel.WebSocketFactory? = null

    /** Test seam: reconnect scheduler used by [enableRealtime] instead of the Handler one. */
    @Volatile internal var realtimeReconnectScheduler: RealtimeChannel.ReconnectScheduler? = null

    /** Retained application context — set during [initialize], never null after that. */
    @Volatile private var applicationContext: Context? = null

    /** Handler invoked when a notification arrives (set via [setNotificationHandler]). */
    @Volatile internal var internalNotificationHandler: ((VisionPushNotification) -> Unit)? = null

    /** Handler invoked when the user opens a notification (set via [setNotificationOpenHandler]). */
    @Volatile internal var internalNotificationOpenCallback: ((VisionPushNotification) -> Unit)? = null

    /** Handler invoked when an SDK-internal background error occurs (set via [onError]). */
    @Volatile private var errorHandler: ((VisionPushError) -> Unit)? = null

    /**
     * Callback invoked on the main thread when the device is successfully registered
     * with the VisionPush backend. Receives the server-assigned device ID.
     * Set via [setOnRegisteredListener].
     */
    @Volatile private var onRegisteredCallback: ((deviceId: String) -> Unit)? = null

    /**
     * Callback invoked on the main thread when the initial device registration fails
     * permanently (after WorkManager retries are exhausted). Set via [setOnRegistrationFailedListener].
     */
    @Volatile private var onRegistrationFailedCallback: ((error: Exception) -> Unit)? = null

    /**
     * Observer for the US-15 user this installation's subscription belongs to —
     * fires on [login] and [logout] (and the deprecated [setExternalId] that
     * delegates to them) whenever the server reports a different user id. Set via
     * [setOnUserChangedListener].
     */
    @Volatile private var userChangeListener: ((userId: String?) -> Unit)? = null

    /**
     * Last line of defence for SDK background work: an uncaught throwable in a
     * coroutine launched on [sdkScope] (an `OutOfMemoryError` decoding a hostile
     * image, an `IllegalStateException` from an un-initialized WorkManager, …) is
     * logged and reported to [onError] — it must NEVER propagate to the host app's
     * uncaught-exception handler and crash the process. `SupervisorJob` already keeps
     * sibling coroutines alive; this handler keeps the app alive.
     */
    private val sdkExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        VisionPushLogger.e(TAG, "Unhandled error in SDK background task: ${throwable.message}", throwable)
        val handler = errorHandler ?: return@CoroutineExceptionHandler
        try {
            handler(throwable as? VisionPushError ?: VisionPushError.Unknown(throwable))
        } catch (_: Exception) {
            // A throwing host callback must not recurse into a crash either.
        }
    }

    /** SDK-wide coroutine scope; survives the entire application lifetime. */
    private val sdkScope = CoroutineScope(SupervisorJob() + Dispatchers.Main + sdkExceptionHandler)

    /**
     * In-memory LRU-like cache of recently received notifications keyed by ID.
     * Allows [NotificationTracker.OpenReceiver] to resolve the full model on tap.
     */
    private val notificationCache: MutableMap<String, VisionPushNotification> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, VisionPushNotification>(50, 0.75f, true) {
                override fun removeEldestEntry(eldest: Map.Entry<String, VisionPushNotification>) =
                    size > 50
            }
        )

    @Volatile private var initialized = false

    /** The active configuration; set during [initializeWithConfig], used for consent checks. */
    @Volatile internal var activeConfig: VisionPushConfig? = null

    /** Local snapshot of tags most recently written via [updateTags] or [removeTags]. */
    @Volatile private var cachedTags: Map<String, String> = emptyMap()

    /** Local snapshot of subscribed notification topics. */
    @Volatile private var cachedTopics: Set<String> = emptySet()

    /** Current badge count applied to future notifications via [NotificationCompat.Builder.setNumber]. */
    @Volatile var badgeCount: Int = 0
        internal set

    /** Default action buttons merged into every notification that has no per-notification actions. */
    @Volatile var defaultActions: List<NotificationAction> = emptyList()
        internal set

    /**
     * In-memory notification history (newest first, max [HISTORY_MAX_SIZE] entries).
     * Persisted to a dedicated [SharedPreferences] file for cross-session availability.
     */
    private val notificationHistory = mutableListOf<VisionPushNotificationRecord>()

    /**
     * Controls whether foreground FCM data messages trigger a system notification.
     * Set to `false` to suppress system notifications when the app is in the foreground
     * (the [setNotificationHandler] callback is always invoked regardless of this flag).
     */
    @Volatile internal var foregroundNotificationEnabled = true

    /**
     * Overrides how the SDK obtains its push token during device registration.
     *
     * Left `null` (the default) the SDK fetches the **FCM** token from Firebase.
     * The Huawei SDK (`VisionPushHms`) installs a provider here that returns the
     * **HMS** Push token instead, before calling [initialize] — that is the only
     * change needed to route a device through HMS instead of FCM. The provider is
     * suspending (token fetch is a network/IPC call) and returns `null` when no
     * token is available yet, in which case registration is retried via WorkManager.
     */
    @JvmStatic
    @Volatile
    var pushTokenProvider: (suspend () -> String?)? = null

    /**
     * This device's sealed-payload keys, or `null` before [initialize] runs.
     *
     * Exposed so the Huawei SDK's HMS messaging service — which lives in a separate
     * Gradle module and therefore cannot reach the `internal` registrar the FCM
     * service uses — can decrypt sealed pushes (Mode A/B). Wire it on the HMS
     * service with `sealedKeysProvider = { VisionPush.sealedDeviceKeys }`.
     */
    @get:JvmStatic
    val sealedDeviceKeys: VisionPushSeal.DeviceKeys?
        get() = internalDeviceRegistrar?.sealedKeys

    // ── Initialization ──────────────────────────────────────────────────────

    /**
     * Initializes the VisionPush SDK with explicit parameters.
     *
     * Must be called once before any other SDK method, typically in
     * [android.app.Application.onCreate]. Subsequent calls are safe and
     * update the configuration then re-register the device.
     *
     * **Registration is asynchronous.** This method returns before the device
     * is registered with the VisionPush backend. If the FCM token is not yet
     * available (common on first launch) or the network is unreachable, the
     * registration attempt fails silently and WorkManager schedules a retry.
     * Use [setOnRegisteredListener] to be notified when registration completes,
     * and [setOnRegistrationFailedListener] to be notified when the immediate
     * attempt fails.
     *
     * @param context  Any context; only the application context is retained.
     * @param appId    UUID identifying the VisionPush application.
     * @param apiKey   Secret key with prefix `vp_live_` or `vp_test_`.
     * @param logLevel Controls SDK console verbosity (default: [VisionPushConfig.LogLevel.WARN]).
     * @param apiBaseUrl Override the default API endpoint (useful for testing/staging).
     */
    @JvmStatic
    @JvmOverloads
    fun initialize(
        context: Context,
        appId: String,
        apiKey: String,
        logLevel: VisionPushConfig.LogLevel = VisionPushConfig.LogLevel.WARN,
        apiBaseUrl: String = BuildConfig.VISIONPUSH_DEFAULT_API_BASE_URL,
        tags: Map<String, String> = emptyMap()
    ) {
        if (appId.isBlank()) throw VisionPushError.InvalidConfig("appId must not be blank.")
        if (apiKey.isBlank()) throw VisionPushError.InvalidConfig("apiKey must not be blank.")

        val config = VisionPushConfig(
            appId = appId,
            apiKey = apiKey,
            apiBaseUrl = apiBaseUrl,
            logLevel = logLevel
        )

        initializeWithConfig(context, config, tags)
    }

    /**
     * Initializes the VisionPush SDK with a pre-built [VisionPushConfig].
     *
     * Use this overload when you need full control over all configuration
     * options. Under the hood [initialize] delegates to this method.
     *
     * ## Async registration
     *
     * Device registration with the VisionPush backend happens asynchronously
     * inside a coroutine launched by this method. `initializeWithConfig`
     * (and [initialize]) return to the caller **before** registration is
     * complete. The following scenarios are possible:
     *
     * - **FCM token not yet available** — Firebase may not have delivered an
     *   FCM token by the time `initialize()` is called (e.g. on first launch or
     *   after a fresh install). In this case [DeviceRegistrar.register] fails
     *   and WorkManager schedules a retry once the token becomes available.
     * - **Network unavailable** — If there is no network connection at init
     *   time, registration fails and WorkManager retries when connectivity
     *   is restored.
     *
     * In both cases `initialize()` returns without error — use
     * [setOnRegisteredListener] to be notified when registration eventually
     * succeeds, and [setOnRegistrationFailedListener] for permanent failures.
     */
    @JvmStatic
    @JvmOverloads
    fun initializeWithConfig(context: Context, config: VisionPushConfig, initialTags: Map<String, String> = emptyMap()) {
        val appContext = context.applicationContext

        val apiClient: ApiClient
        val registrar: DeviceRegistrar

        synchronized(VisionPush) {
            // Guard against concurrent / repeated initialize() calls FIRST, before any
            // side effects run. Otherwise loadHistory(), the IAM controller registration,
            // and channel creation would all execute twice on a duplicate call.
            if (initialized) {
                VisionPushLogger.d(TAG, "Already initialized – skipping duplicate call.")
                return
            }

            applicationContext = appContext
            loadHistory()

            VisionPushLogger.minLevel = config.logLevel
            VisionPushLogger.i(TAG, "Initializing VisionPush SDK v$SDK_VERSION appId=${config.appId}")

            if (!config.apiKey.startsWith("vp_live_") && !config.apiKey.startsWith("vp_test_")) {
                VisionPushLogger.w(
                    TAG,
                    "API key does not start with 'vp_live_' or 'vp_test_'. " +
                            "Ensure you are using a valid VisionPush API key."
                )
            }

            apiClient = ApiClient(config)
            registrar = DeviceRegistrar(appContext, config, apiClient)
            // Device binding: from here on every request carries this
            // installation's id, so the (extractable) client key alone can no
            // longer act on another user's device. Seeded WITHOUT minting one —
            // initialize() is not a registration; the registrar publishes the
            // id the moment it mints one.
            apiClient.installationId = registrar.existingAnonymousId
            // Forward every US-15 user-id change (login/logout, or the
            // deprecated setExternalId that delegates to them) to the public
            // listener. Posted through sdkScope → the main thread, like every
            // other public callback.
            registrar.onUserIdChanged = { newUserId -> sdkScope.launch { userChangeListener?.invoke(newUserId) } }
            val notificationBuilder = NotificationBuilder(appContext)

            internalApiClient = apiClient
            internalDeviceRegistrar = registrar
            // Let the FCM service (same process) decrypt sealed pushes with this
            // device's keys (generated + persisted by the registrar).
            VisionPushMessagingService.sealedKeysProvider = { registrar.sealedKeys }
            internalNotificationBuilder = notificationBuilder
            internalNotificationHandler = null
            internalNotificationOpenCallback = null
            internalIamController = InAppMessageController(appContext as android.app.Application)
            activeConfig = config

            // Create the default notification channel inside the lock so the channel
            // always exists before any notification can be posted, even when
            // initializeWithConfig() is called from multiple threads simultaneously.
            notificationBuilder.ensureDefaultChannelExists()

            initialized = true
        }

        // GDPR/CCPA: skip backend registration until consent is granted.
        if (config.consentRequired && !isConsentGiven(appContext)) {
            VisionPushLogger.i(
                TAG,
                "consentRequired=true — awaiting user consent. Call setConsentGiven(true) to activate data collection."
            )
            return
        }

        // Wire IAM impression tracking before any messages can be shown.
        internalIamController?.onImpression = { messageId ->
            val client = internalApiClient
            if (client != null) {
                sdkScope.launch { client.trackIAMImpression(config.appId, "", messageId) }
            }
        }

        // Wire IAM OUTCOME button taps so an in-app message's "outcome" action records
        // a conversion via the same backend path as the public sendOutcome() API.
        internalIamController?.onOutcome = { name -> dispatchOutcome(name) }

        // Wire click/dismiss recording for both the HTML bridge (US-16) and the native
        // modal/banner/fullscreen types (SQ-290/SQ-285) — same backend path the
        // impression tracker above already uses, just the click/dismiss event kinds the
        // 0045 migration added. Read dynamically (dispatchIam*) rather than reassigned
        // post-registration like onImpression above: a message can't be ingested/shown
        // before registration completes anyway, so there's nothing to rewire.
        internalIamController?.onHtmlClick = { message, buttonId -> dispatchIamClick(message.id, buttonId) }
        internalIamController?.onHtmlDismiss = { message -> dispatchIamDismiss(message.id) }
        internalIamController?.onNativeClick = { message, buttonId -> dispatchIamClick(message.id, buttonId) }
        internalIamController?.onNativeDismiss = { message -> dispatchIamDismiss(message.id) }

        // Initial device registration – failures schedule a WorkManager retry
        sdkScope.launch {
            when (val regResult = registrar.register(initialTags = initialTags)) {
                is VisionPushResult.Success -> {
                    val deviceId = regResult.data
                    VisionPushLogger.i(TAG, "SDK initialized. deviceId=$deviceId")
                    // Update impression tracker to include the real deviceId.
                    internalIamController?.onImpression = { messageId ->
                        sdkScope.launch {
                            internalApiClient?.trackIAMImpression(config.appId, deviceId, messageId)
                        }
                    }
                    // Wire up whatever was enabled before registration completed: geofence
                    // attribution and a deferred realtime connect.
                    notifyDeviceRegistered(deviceId)
                    // Fetch and trigger in-app messages for this session.
                    val iamResult = internalApiClient?.fetchInAppMessages(config.appId, deviceId)
                    if (iamResult is VisionPushResult.Success) {
                        withContext(Dispatchers.Main) {
                            internalIamController?.ingest(iamResult.data)
                            internalIamController?.triggerSession()
                        }
                    }
                    withContext(Dispatchers.Main) { onRegisteredCallback?.invoke(deviceId) }
                }
                is VisionPushResult.Failure -> {
                    VisionPushLogger.w(
                        TAG,
                        "Initial registration failed: ${regResult.error.message}. " +
                                "WorkManager will retry when network is available."
                    )
                    withContext(Dispatchers.Main) {
                        errorHandler?.invoke(regResult.error)
                        onRegistrationFailedCallback?.invoke(regResult.error)
                    }
                    scheduleRegistrationSync(appContext)
                }
            }
        }
    }

    // ── Permission handling ─────────────────────────────────────────────────

    /**
     * Requests the `POST_NOTIFICATIONS` runtime permission on Android 13 (API 33)
     * and above. On older API levels this is a no-op because the permission is
     * granted implicitly.
     *
     * Call this from a foreground [Activity] at a contextually appropriate
     * moment (e.g. after explaining to the user why notifications are useful).
     *
     * After calling this method, relay the result by implementing
     * [Activity.onRequestPermissionsResult] and calling [handlePermissionResult].
     *
     * @param activity The foreground [Activity] used to show the permission dialog.
     */
    @JvmStatic
    @MainThread
    fun requestPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (ContextCompat.checkSelfPermission(activity, permission) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            VisionPushLogger.d(TAG, "POST_NOTIFICATIONS permission already granted.")
            return
        }

        ActivityCompat.requestPermissions(activity, arrayOf(permission), PERMISSION_REQUEST_CODE)
    }

    /**
     * Requests the `POST_NOTIFICATIONS` permission using the Jetpack Activity
     * Result API. Preferred over [requestPermission] for new code.
     *
     * ```kotlin
     * val launcher = registerForActivityResult(
     *     ActivityResultContracts.RequestPermission()
     * ) { granted -> VisionPush.handlePermissionResult(this, granted) }
     *
     * VisionPush.requestPermissionWithLauncher(launcher)
     * ```
     */
    @JvmStatic
    @MainThread
    fun requestPermissionWithLauncher(launcher: ActivityResultLauncher<String>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * Must be called from [Activity.onRequestPermissionsResult] when using the
     * legacy [requestPermission] overload.
     *
     * ```kotlin
     * override fun onRequestPermissionsResult(
     *     requestCode: Int, permissions: Array<String>, grantResults: IntArray
     * ) {
     *     super.onRequestPermissionsResult(requestCode, permissions, grantResults)
     *     VisionPush.handlePermissionResult(this, requestCode, permissions, grantResults)
     * }
     * ```
     */
    @JvmStatic
    fun handlePermissionResult(
        context: Context,
        requestCode: Int,
        @Suppress("UNUSED_PARAMETER") permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode != PERMISSION_REQUEST_CODE) return

        val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED

        VisionPushLogger.i(TAG, "Notification permission result: granted=$granted")
        if (granted) {
            sdkScope.launch { internalDeviceRegistrar?.register(forceRefresh = true) }
        }
    }

    /**
     * Convenience overload for use with the Activity Result API callback.
     * Call this inside the `ActivityResultCallback` lambda.
     */
    @JvmStatic
    fun handlePermissionResult(context: Context, granted: Boolean) {
        VisionPushLogger.i(TAG, "Notification permission (launcher API): granted=$granted")
        if (granted) {
            sdkScope.launch { internalDeviceRegistrar?.register(forceRefresh = true) }
        }
    }

    // ── User identity (US-15) ───────────────────────────────────────────────
    //
    // A real user object: aliases (external_id is a reserved one), email/SMS
    // subscriptions and user-level properties (tags/language/country). An
    // anonymous user is created at first registration; login() attaches an
    // external id to it (or hands the subscription to the existing user that
    // already owns that id); logout() hands it to a brand-new anonymous
    // user. See the US-15 story contract for the full server-side semantics —
    // this SDK is a thin, validated wrapper over
    // `PUT /v1/devices/{id}` (login/logout via external_id/clear_external_id),
    // `PATCH /v1/devices/{id}/user` (tags/language/country — see updateTags/removeTags),
    // `POST|DELETE /v1/devices/{id}/user/aliases` (addAlias/removeAlias) and
    // `POST /v1/devices/{id}/user/subscriptions` (addEmail/addSms).

    /**
     * Associates this installation with [externalId] from your own system (e.g.
     * your database user ID or CRM identifier) — OneSignal v5 `login()`.
     *
     * If another user already owns [externalId], this installation's subscription
     * moves to THAT user (its own anonymous user, with its tags, is discarded).
     * Otherwise the id is assigned to this installation's current anonymous user.
     * Setting the same ([externalId], [externalIdHash]) pair again is idempotent
     * (no network call). The call is asynchronous; [userId] reflects the server's
     * answer once it completes, and [setOnUserChangedListener] fires if the user
     * actually changed.
     *
     * ## Identity verification
     *
     * When identity verification is enabled for the app (VisionPush dashboard), the
     * server only accepts an external id that comes with an [externalIdHash] proving
     * your backend vouches for it — otherwise any user of the app could claim another
     * user's id with the client API key and receive that user's pushes. The hash is the
     * lowercase hex HMAC-SHA256 of the external id, keyed with the app's identity secret.
     * **Your backend computes it; the secret must never ship inside the app** — the SDK
     * only forwards the hash. It is stored next to the id and re-sent with it on every
     * registration (e.g. after an FCM token rotation), and wiped by [logout].
     *
     * A missing or wrong hash is rejected with HTTP 403 `external_id_verification_failed`
     * ([VisionPushError.ApiError.isExternalIdVerificationFailed]): the SDK logs it at
     * ERROR level, does not retry, restores the previously accepted identity locally, and
     * reports the failure through [callback] and [onError]. With verification disabled
     * the hash is ignored, so it is always safe to pass one.
     *
     * @param externalId     Opaque string identifying the user in your own system.
     * @param externalIdHash The backend-computed identity-verification hash for
     *                       [externalId] (64 hex characters; upper-case input is
     *                       normalised).
     * @param callback       Optional result callback invoked on the main thread with
     *                       the [userId] this installation belongs to once the login
     *                       has been pushed to the backend.
     * @throws VisionPushError.InvalidArgument for a blank id, an id over 1 024
     *         characters, or a hash that is not 64 hex characters.
     */
    @JvmStatic
    @JvmOverloads
    fun login(
        externalId: String,
        externalIdHash: String? = null,
        callback: ((VisionPushResult<String?>) -> Unit)? = null
    ) {
        if (externalId.isBlank()) {
            throw VisionPushError.InvalidArgument("externalId must not be blank.")
        }
        if (externalId.length > 1_024) {
            throw VisionPushError.InvalidArgument("externalId must be 1 024 characters or fewer.")
        }
        val hash = normalizeExternalIdHash(externalIdHash)
        checkInitialized()
        checkConsent()
        val registrar = internalDeviceRegistrar ?: return
        if (registrar.externalId == externalId && registrar.externalIdHash == hash) {
            callback?.invoke(VisionPushResult.Success(registrar.userId))
            return
        }

        VisionPushLogger.i(
            TAG,
            if (hash != null) "Logging in: $externalId (with identity hash)" else "Logging in: $externalId"
        )

        // updateExternalIdentity() persists id + hash, then register() flushes a pending
        // clear (explicit PUT) before the upsert that carries them and reports userId.
        sdkScope.launch {
            val result = registrar.updateExternalIdentity(externalId, hash).map { registrar.userId }
            withContext(Dispatchers.Main) {
                result.onFailure { error -> errorHandler?.invoke(error) }
                callback?.invoke(result)
            }
        }
    }

    /**
     * Detaches the current external identity from this installation — OneSignal v5
     * `logout()`: the subscription moves to a BRAND-NEW anonymous user; the previous
     * (identified) user keeps its aliases and other subscriptions untouched. Already
     * anonymous is idempotent (no network call). Asynchronous; [userId] reflects the
     * new anonymous user once it completes, and [setOnUserChangedListener] fires.
     *
     * Distinct from [deleteDevice]: that call fully UNREGISTERS the device from push
     * and resets the SDK to the un-initialized state. This call keeps the device
     * registered and receiving push — it only detaches the signed-in user.
     *
     * @param callback Optional result callback invoked on the main thread with the
     *                 new anonymous [userId].
     */
    @JvmStatic
    @JvmOverloads
    fun logout(callback: ((VisionPushResult<String?>) -> Unit)? = null) {
        checkInitialized()
        checkConsent()
        val registrar = internalDeviceRegistrar ?: return
        if (registrar.externalId == null && !registrar.isExternalIdClearPending) {
            callback?.invoke(VisionPushResult.Success(registrar.userId))
            return
        }

        VisionPushLogger.i(TAG, "Logging out the current user.")

        sdkScope.launch {
            val result = registrar.updateExternalIdentity(null, null).map { registrar.userId }
            withContext(Dispatchers.Main) {
                result.onFailure { error -> errorHandler?.invoke(error) }
                callback?.invoke(result)
            }
        }
    }

    /**
     * @deprecated Use [login] to associate an id, or [logout] to clear it — the
     * OneSignal v5 names for the same operation. Kept for source compatibility;
     * delegates to them (a non-null [externalId] calls [login], `null` calls
     * [logout]) and keeps the exact same validation and idempotency behaviour.
     */
    @Deprecated(
        "Use login(externalId, externalIdHash) to associate an id, or logout() to clear it.",
        ReplaceWith("login(externalId, externalIdHash, callback)")
    )
    @JvmStatic
    @JvmOverloads
    fun setExternalId(
        externalId: String?,
        externalIdHash: String? = null,
        callback: ((VisionPushResult<Unit>) -> Unit)? = null
    ) {
        if (externalId == null) {
            logout { result -> callback?.invoke(result.map { }) }
        } else {
            login(externalId, externalIdHash) { result -> callback?.invoke(result.map { }) }
        }
    }

    /**
     * Normalises a caller-supplied identity-verification hash: trims, lowercases (the
     * server compares lowercase hex) and rejects anything that is not exactly the 64 hex
     * characters of an HMAC-SHA256 — catching a raw secret, base64 or a truncated value
     * locally instead of with a 403 round-trip. Blank → `null`.
     */
    @VisibleForTesting
    internal fun normalizeExternalIdHash(raw: String?): String? {
        val hash = raw?.trim()?.lowercase() ?: return null
        if (hash.isEmpty()) return null
        if (!EXTERNAL_ID_HASH_PATTERN.matches(hash)) {
            throw VisionPushError.InvalidArgument(
                "externalIdHash must be the 64-character lowercase hex HMAC-SHA256 of the external id, computed by your backend."
            )
        }
        return hash
    }

    private val EXTERNAL_ID_HASH_PATTERN = Regex("[0-9a-f]{64}")

    /** The currently configured external user identifier, or `null` before it is set. */
    @get:JvmStatic
    val externalId: String? get() = internalDeviceRegistrar?.externalId

    /**
     * The US-15 user id this installation's subscription currently belongs to —
     * anonymous until [login], and freshly re-assigned after [logout]. `null`
     * before the first successful registration.
     */
    @get:JvmStatic
    val userId: String? get() = internalDeviceRegistrar?.userId

    /**
     * Registers a callback invoked on the main thread whenever the [userId] this
     * installation belongs to changes — after [login], [logout], or the
     * deprecated [setExternalId] that delegates to them. Pass `null` to clear.
     */
    @JvmStatic
    fun setOnUserChangedListener(listener: ((userId: String?) -> Unit)?) {
        userChangeListener = listener
    }

    private val ALIAS_LABEL_PATTERN = Regex("[a-z0-9_]{1,64}")

    /** Shared by [addAlias] and [removeAlias] — mirrors the server's `validate_custom_alias`. */
    private fun validateAliasLabel(label: String) {
        if (!ALIAS_LABEL_PATTERN.matches(label)) {
            throw VisionPushError.InvalidArgument("label must match ^[a-z0-9_]{1,64}\$.")
        }
        if (label == "external_id" || label == "onesignal_id") {
            throw VisionPushError.InvalidArgument(
                "label '$label' is reserved — use login()/logout() for external_id."
            )
        }
    }

    /**
     * Adds a custom alias to the user this installation belongs to — a second
     * namespace alongside [login]'s external id (e.g. a loyalty-card number).
     *
     * [aliasHash] is required: the lowercase hex HMAC-SHA256 of `"$label:$id"`,
     * keyed with the app's identity secret — computed by your backend, exactly
     * like [login]'s `externalIdHash`. An app without an identity secret gets a
     * 403 [VisionPushError.ApiError]; an alias already owned by a different user
     * gets a 409 ([VisionPushError.ApiError.isSubscriptionOwnedByAnotherUser]).
     *
     * @param label     Custom alias namespace, `^[a-z0-9_]{1,64}$`. `external_id`
     *                  and `onesignal_id` are reserved — use [login] instead.
     * @param id        The alias value in that namespace (1–512 characters).
     * @param aliasHash Backend-computed hex HMAC-SHA256 of `"label:id"`.
     * @param callback  Optional result callback invoked on the main thread.
     */
    @JvmStatic
    @JvmOverloads
    fun addAlias(
        label: String,
        id: String,
        aliasHash: String,
        callback: ((VisionPushResult<Unit>) -> Unit)? = null
    ) {
        validateAliasLabel(label)
        if (id.isBlank() || id.length > 512) {
            throw VisionPushError.InvalidArgument("id must be 1–512 characters.")
        }
        val hash = normalizeExternalIdHash(aliasHash)
            ?: throw VisionPushError.InvalidArgument(
                "aliasHash must be the 64-character lowercase hex HMAC-SHA256 of \"$label:$id\", computed by your backend."
            )
        checkInitialized()
        checkConsent()
        sdkScope.launch {
            val deviceId = internalDeviceRegistrar?.deviceId
            val result = if (deviceId != null) {
                internalApiClient?.addUserAlias(deviceId, label, id, hash)
                    ?: VisionPushResult.Failure(VisionPushError.NotInitialized())
            } else {
                VisionPushResult.Failure(VisionPushError.TokenUnavailable("Device not yet registered."))
            }
            withContext(Dispatchers.Main) {
                result.onFailure { error -> errorHandler?.invoke(error) }
                callback?.invoke(result)
            }
        }
    }

    /**
     * Removes a custom alias from the user this installation belongs to. Needs the
     * same signed proof as [addAlias] — the server verifies [aliasHash] against
     * `"label:id"` before removing it.
     *
     * @param label     The alias namespace to remove, as passed to [addAlias].
     * @param id        The alias value, as passed to [addAlias].
     * @param aliasHash Backend-computed hex HMAC-SHA256 of `"label:id"`.
     * @param callback  Optional result callback invoked on the main thread.
     */
    @JvmStatic
    @JvmOverloads
    fun removeAlias(
        label: String,
        id: String,
        aliasHash: String,
        callback: ((VisionPushResult<Unit>) -> Unit)? = null
    ) {
        validateAliasLabel(label)
        if (id.isBlank() || id.length > 512) {
            throw VisionPushError.InvalidArgument("id must be 1–512 characters.")
        }
        val hash = normalizeExternalIdHash(aliasHash)
            ?: throw VisionPushError.InvalidArgument(
                "aliasHash must be the 64-character lowercase hex HMAC-SHA256 of \"$label:$id\", computed by your backend."
            )
        checkInitialized()
        checkConsent()
        sdkScope.launch {
            val deviceId = internalDeviceRegistrar?.deviceId
            val result = if (deviceId != null) {
                internalApiClient?.removeUserAlias(deviceId, label, id, hash)
                    ?: VisionPushResult.Failure(VisionPushError.NotInitialized())
            } else {
                VisionPushResult.Failure(VisionPushError.TokenUnavailable("Device not yet registered."))
            }
            withContext(Dispatchers.Main) {
                result.onFailure { error -> errorHandler?.invoke(error) }
                callback?.invoke(result)
            }
        }
    }

    private val EMAIL_PATTERN = Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
    private val E164_PATTERN = Regex("\\+[1-9]\\d{1,14}")

    /**
     * Adds an email subscription to the user this installation belongs to — so the
     * same person can also be reached on the email channel. Validated locally (a
     * syntactically valid address) before the request; the server lower-cases and
     * trims it too. An address already owned by a DIFFERENT user is rejected with
     * a 409 [VisionPushError.ApiError] ([VisionPushError.ApiError.isSubscriptionOwnedByAnotherUser]).
     *
     * @param email    The address to subscribe.
     * @param callback Optional result callback invoked on the main thread.
     * @throws VisionPushError.InvalidArgument if [email] is not a syntactically valid address.
     */
    @JvmStatic
    @JvmOverloads
    fun addEmail(email: String, callback: ((VisionPushResult<Unit>) -> Unit)? = null) {
        val trimmed = email.trim()
        if (!EMAIL_PATTERN.matches(trimmed)) {
            throw VisionPushError.InvalidArgument("email must be a syntactically valid email address.")
        }
        checkInitialized()
        checkConsent()
        sdkScope.launch {
            val deviceId = internalDeviceRegistrar?.deviceId
            val result = if (deviceId != null) {
                internalApiClient?.addUserSubscription(deviceId, "email", trimmed.lowercase())
                    ?: VisionPushResult.Failure(VisionPushError.NotInitialized())
            } else {
                VisionPushResult.Failure(VisionPushError.TokenUnavailable("Device not yet registered."))
            }
            withContext(Dispatchers.Main) {
                result.onFailure { error -> errorHandler?.invoke(error) }
                callback?.invoke(result)
            }
        }
    }

    /**
     * Adds an SMS subscription to the user this installation belongs to.
     * [phoneE164] must already be in E.164 format (e.g. `"+4915123456789"`) — the
     * SDK does not reformat local numbers. An address already owned by a DIFFERENT
     * user is rejected with a 409 [VisionPushError.ApiError]
     * ([VisionPushError.ApiError.isSubscriptionOwnedByAnotherUser]).
     *
     * @param phoneE164 The phone number to subscribe, in E.164 format.
     * @param callback  Optional result callback invoked on the main thread.
     * @throws VisionPushError.InvalidArgument if [phoneE164] is not valid E.164.
     */
    @JvmStatic
    @JvmOverloads
    fun addSms(phoneE164: String, callback: ((VisionPushResult<Unit>) -> Unit)? = null) {
        if (!E164_PATTERN.matches(phoneE164)) {
            throw VisionPushError.InvalidArgument("phoneE164 must be in E.164 format, e.g. \"+4915123456789\".")
        }
        checkInitialized()
        checkConsent()
        sdkScope.launch {
            val deviceId = internalDeviceRegistrar?.deviceId
            val result = if (deviceId != null) {
                internalApiClient?.addUserSubscription(deviceId, "sms", phoneE164)
                    ?: VisionPushResult.Failure(VisionPushError.NotInitialized())
            } else {
                VisionPushResult.Failure(VisionPushError.TokenUnavailable("Device not yet registered."))
            }
            withContext(Dispatchers.Main) {
                result.onFailure { error -> errorHandler?.invoke(error) }
                callback?.invoke(result)
            }
        }
    }

    // ── Notification handlers ───────────────────────────────────────────────

    /**
     * Registers a callback invoked on the **main thread** whenever a VisionPush
     * notification is received (foreground delivery or FCM data message).
     *
     * ```kotlin
     * VisionPush.setNotificationHandler { notification ->
     *     showInAppBanner(notification.title, notification.body)
     * }
     * ```
     *
     * Pass `null` to clear the handler. Setting a handler does not suppress
     * the system notification unless [VisionPushNotification.showSystemNotification]
     * is `false`.
     */
    @JvmStatic
    @MainThread
    fun setNotificationHandler(handler: ((VisionPushNotification) -> Unit)?) {
        internalNotificationHandler = handler
    }

    /**
     * Registers a callback invoked on the **main thread** when the user opens
     * a notification (taps the shade entry or an action button).
     *
     * ```kotlin
     * VisionPush.setNotificationOpenHandler { notification ->
     *     navigateTo(notification.deepLink)
     * }
     * ```
     *
     * Pass `null` to clear the handler.
     */
    @JvmStatic
    @MainThread
    fun setNotificationOpenHandler(handler: ((VisionPushNotification) -> Unit)?) {
        internalNotificationOpenCallback = handler
    }

    // ── Tags ────────────────────────────────────────────────────────────────

    /**
     * Merges [tags] into the user (US-15) this installation belongs to —
     * mirrored onto every subscription of that user, so they survive a [login]
     * to a different device just like in the OneSignal model.
     *
     * Keys and values must be ≤ 256 characters; maximum 100 pairs.
     * Existing tags are preserved unless overwritten by a matching key.
     *
     * @param tags Key-value metadata (e.g. `mapOf("plan" to "pro")`).
     * @param callback Optional result callback invoked on the main thread.
     */
    @JvmStatic
    @JvmOverloads
    fun updateTags(
        tags: Map<String, String>,
        callback: ((VisionPushResult<Unit>) -> Unit)? = null
    ) {
        if (tags.size > 100) {
            throw VisionPushError.InvalidArgument("tags must contain 100 or fewer key-value pairs.")
        }
        tags.forEach { (k, v) ->
            if (k.length > 256) throw VisionPushError.InvalidArgument("Tag key '$k' exceeds 256 characters.")
            if (v.length > 256) throw VisionPushError.InvalidArgument("Tag value for key '$k' exceeds 256 characters.")
        }
        checkInitialized()
        checkConsent()

        sdkScope.launch {
            val deviceId = internalDeviceRegistrar?.deviceId
            val result = if (deviceId != null) {
                internalApiClient?.updateDeviceTags(deviceId, tags)
                    ?: VisionPushResult.Failure(VisionPushError.NotInitialized())
            } else {
                VisionPushResult.Failure(VisionPushError.TokenUnavailable("Device not yet registered."))
            }
            if (result.isSuccess) cachedTags = cachedTags + tags
            withContext(Dispatchers.Main) { callback?.invoke(result) }
        }
    }

    /**
     * Removes specific tag keys from the user (US-15) this installation belongs to.
     *
     * Keys not present on the user are silently ignored. Maximum 100 keys,
     * each key ≤ 256 characters.
     *
     * @param keys The tag keys to remove.
     * @param callback Optional result callback invoked on the main thread.
     */
    @JvmStatic
    @JvmOverloads
    fun removeTags(
        keys: List<String>,
        callback: ((VisionPushResult<Unit>) -> Unit)? = null
    ) {
        if (keys.size > 100) {
            throw VisionPushError.InvalidArgument("keys must contain 100 or fewer entries.")
        }
        keys.forEach { key ->
            if (key.length > 256) throw VisionPushError.InvalidArgument("Tag key '$key' exceeds 256 characters.")
        }
        checkInitialized()
        checkConsent()

        sdkScope.launch {
            val deviceId = internalDeviceRegistrar?.deviceId
            val result = if (deviceId != null) {
                internalApiClient?.removeDeviceTags(deviceId, keys)
                    ?: VisionPushResult.Failure(VisionPushError.NotInitialized())
            } else {
                VisionPushResult.Failure(VisionPushError.TokenUnavailable("Device not yet registered."))
            }
            if (result.isSuccess) cachedTags = cachedTags - keys.toSet()
            withContext(Dispatchers.Main) { callback?.invoke(result) }
        }
    }

    /** A local snapshot of the tags most recently written via [updateTags] or [removeTags]. */
    @get:JvmStatic
    val tags: Map<String, String> get() = cachedTags

    /**
     * Controls whether foreground FCM messages trigger a system notification.
     *
     * When set to `false`, notifications received while the app is in the foreground
     * are forwarded to the [setNotificationHandler] callback only — no system
     * notification is shown. Defaults to `true`.
     */
    @JvmStatic
    @MainThread
    fun setForegroundNotificationEnabled(enabled: Boolean) {
        foregroundNotificationEnabled = enabled
    }

    /**
     * Removes all VisionPush notifications from the system notification drawer.
     */
    @JvmStatic
    @MainThread
    fun clearAllNotifications() {
        val ctx = applicationContext ?: return
        androidx.core.app.NotificationManagerCompat.from(ctx).cancelAll()
    }

    // ── Error handler ───────────────────────────────────────────────────────

    /**
     * Registers a callback invoked on the main thread whenever an SDK-internal
     * background error occurs (e.g. registration retry exhausted, event tracking failure).
     *
     * Pass `null` to clear the handler.
     */
    @JvmStatic
    fun onError(handler: ((VisionPushError) -> Unit)?) {
        errorHandler = handler
    }

    /**
     * Registers a callback invoked on the **main thread** when the device is
     * successfully registered with the VisionPush backend.
     *
     * Because [initialize] returns before registration completes (registration
     * is asynchronous and may be delayed by an unavailable FCM token or network),
     * use this listener to react to the registration completing — for example to
     * retrieve [deviceId] or to call [login].
     *
     * ```kotlin
     * VisionPush.setOnRegisteredListener { deviceId ->
     *     Log.d("App", "VisionPush device registered: $deviceId")
     * }
     * ```
     *
     * Pass `null` to clear the listener.
     *
     * @param onRegistered Lambda receiving the server-assigned device ID string.
     */
    @JvmStatic
    fun setOnRegisteredListener(onRegistered: ((deviceId: String) -> Unit)?) {
        onRegisteredCallback = onRegistered
    }

    /**
     * Registers a callback invoked on the **main thread** when the initial device
     * registration fails (WorkManager has scheduled a retry but the immediate
     * attempt failed).
     *
     * Note: this callback fires for every failed attempt during the initial
     * [initialize] call. WorkManager may still succeed on a subsequent retry,
     * in which case [setOnRegisteredListener] will be invoked.
     *
     * ```kotlin
     * VisionPush.setOnRegistrationFailedListener { error ->
     *     Log.e("App", "VisionPush registration failed: ${error.message}")
     * }
     * ```
     *
     * Pass `null` to clear the listener.
     *
     * @param onFailed Lambda receiving the [Exception] that caused the failure.
     */
    @JvmStatic
    fun setOnRegistrationFailedListener(onFailed: ((error: Exception) -> Unit)?) {
        onRegistrationFailedCallback = onFailed
    }

    // MARK: - Manual Tracking

    /**
     * Manually records a notification-opened event for the given VisionPush notification ID.
     *
     * Use this only if you manage notification presentation yourself and do not
     * use [NotificationBuilder] or the SDK's auto-tracking. Enqueues a WorkManager
     * task for reliable delivery.
     *
     * @param notificationId The `id` from the [VisionPushNotification] model.
     * @param campaignId Optional campaign identifier for analytics attribution.
     */
    @JvmStatic
    @JvmOverloads
    fun trackOpened(notificationId: String, campaignId: String? = null) {
        checkInitialized()
        checkConsent()
        NotificationTracker.enqueueTrackingWork(
            applicationContext ?: return,
            notificationId,
            NotificationEvent.EventType.OPENED,
            actionId = null,
            campaignId = campaignId
        )
    }

    /**
     * Manually records an action-button tap event.
     *
     * @param notificationId The `id` from the [VisionPushNotification] model.
     * @param actionId The identifier of the tapped action button.
     * @param campaignId Optional campaign identifier.
     */
    @JvmStatic
    @JvmOverloads
    fun trackActionTapped(notificationId: String, actionId: String, campaignId: String? = null) {
        checkInitialized()
        checkConsent()
        NotificationTracker.enqueueTrackingWork(
            applicationContext ?: return,
            notificationId,
            NotificationEvent.EventType.ACTION_TAPPED,
            actionId = actionId,
            campaignId = campaignId
        )
    }

    // ── Notification channels ───────────────────────────────────────────────

    /**
     * Creates or updates one or more notification channels on the device.
     *
     * On API < 26 this method is a no-op. Channels must exist before any
     * notification that references them can be displayed.
     *
     * ```kotlin
     * VisionPush.registerNotificationChannels(
     *     VisionPushChannel(
     *         channelId  = "promotions",
     *         name       = "Promotions",
     *         importance = NotificationManager.IMPORTANCE_LOW
     *     )
     * )
     * ```
     */
    @JvmStatic
    fun registerNotificationChannels(vararg channels: VisionPushChannel) {
        checkInitialized()
        internalNotificationBuilder?.ensureChannelsExist(channels.toList())
    }

    // ── Opt-in / opt-out ────────────────────────────────────────────────────

    /**
     * Opts the user out of push notifications.
     *
     * The device record is updated on the backend so the server no longer
     * sends FCM messages to this device. The preference is persisted locally
     * and survives process restarts.
     *
     * @param callback Optional result callback invoked on the main thread.
     */
    @JvmStatic
    @JvmOverloads
    fun optOut(callback: ((VisionPushResult<Unit>) -> Unit)? = null) {
        checkInitialized()
        checkConsent()
        sdkScope.launch {
            val result = internalDeviceRegistrar?.setOptedOut(true)
                ?: VisionPushResult.Failure(VisionPushError.NotInitialized())
            VisionPushLogger.i(TAG, "User opted out.")
            withContext(Dispatchers.Main) { callback?.invoke(result) }
        }
    }

    /**
     * Opts the user back in to push notifications after a prior [optOut] call.
     *
     * @param callback Optional result callback invoked on the main thread.
     */
    @JvmStatic
    @JvmOverloads
    fun optIn(callback: ((VisionPushResult<Unit>) -> Unit)? = null) {
        checkInitialized()
        checkConsent()
        sdkScope.launch {
            val result = internalDeviceRegistrar?.setOptedOut(false)
                ?: VisionPushResult.Failure(VisionPushError.NotInitialized())
            VisionPushLogger.i(TAG, "User opted in.")
            withContext(Dispatchers.Main) { callback?.invoke(result) }
        }
    }

    /** `true` when the user is opted in to push notifications. */
    @get:JvmStatic
    val isOptedIn: Boolean get() = !(internalDeviceRegistrar?.isOptedOut ?: false)

    // ── Device registration ─────────────────────────────────────────────────

    /**
     * Seeds this installation's identity so a device migrating from another VisionPush
     * bridge — e.g. a Unity player switching from the REST bridge to this native core —
     * keeps its existing device record instead of registering as a brand-new device.
     *
     * Call this **before** [initialize] whenever possible, so the very first registration
     * already carries [id] (Unity passes its `vp_installation_id` PlayerPrefs value). It is
     * also safe to call after [initialize].
     *
     * No-op once an installation id is already persisted: [id] is discarded and the
     * pre-existing id is returned unchanged. An established identity is never overwritten.
     *
     * ```java
     * // Java — the Unity bridge is Java-callable through this @JvmStatic method.
     * String installationId = VisionPush.adoptInstallationId(context, unityStoredId);
     * ```
     *
     * @param context Any context; only used to open the local identity store.
     * @param id The UUID installation id to adopt.
     * @return The installation id now in effect: [id] if it was adopted, or the
     *         pre-existing id if one was already stored.
     * @throws VisionPushError.InvalidArgument if [id] is not a valid UUID string.
     */
    @JvmStatic
    fun adoptInstallationId(context: Context, id: String): String {
        val registrar = internalDeviceRegistrar
        return if (registrar != null) {
            registrar.adoptAnonymousId(id)
        } else {
            DeviceRegistrar.seedAnonymousId(context.applicationContext, id)
        }
    }

    /**
     * Forces an immediate device registration sync with the backend.
     *
     * Useful after restoring a backup or when the host app detects a state
     * inconsistency. Under normal operation the SDK handles registration
     * automatically.
     *
     * @param callback Optional result callback invoked on the main thread.
     */
    @JvmStatic
    @JvmOverloads
    fun syncDevice(callback: ((VisionPushResult<String>) -> Unit)? = null) {
        checkInitialized()
        checkConsent()
        sdkScope.launch {
            val result = internalDeviceRegistrar?.register(forceRefresh = true)
                ?: VisionPushResult.Failure(VisionPushError.NotInitialized())
            result.onSuccess { id -> notifyDeviceRegistered(id) }
            withContext(Dispatchers.Main) { callback?.invoke(result) }
        }
    }

    /**
     * Removes all device registration data from the VisionPush backend and
     * clears all locally stored state. Call during user logout.
     *
     * After calling [deleteDevice] the SDK returns to the un-initialized state, so a
     * subsequent [initialize] re-registers cleanly (e.g. when a different user
     * logs in). Calling [initialize] again is required to resume push delivery;
     * unlike before this fix, it is no longer a silent no-op.
     *
     * @param callback Optional result callback invoked on the main thread.
     */
    @JvmStatic
    @JvmOverloads
    fun deleteDevice(callback: ((VisionPushResult<Unit>) -> Unit)? = null) {
        checkInitialized()
        // Tear down the realtime socket + geofencing so a logged-out device stops
        // receiving pushes and reporting location (disableRealtime also drops a
        // deferred connect).
        disableRealtime()
        disableGeofencing()
        // Capture the registrar before we reset component refs below; unregister()
        // needs it, but a logout→login-as-different-user flow must re-initialize.
        val registrar = internalDeviceRegistrar
        sdkScope.launch {
            val result = registrar?.unregister()
                ?: VisionPushResult.Failure(VisionPushError.NotInitialized())

            // Reset SDK to the un-initialized state so initialize() can run again for
            // a new user. Guarded with the same lock initialize() uses.
            synchronized(VisionPush) {
                internalNotificationHandler = null
                internalNotificationOpenCallback = null
                internalApiClient = null
                internalDeviceRegistrar = null
                internalNotificationBuilder = null
                internalIamController = null
                internalGeofenceManager = null
                activeConfig = null
                cachedTags = emptyMap()
                cachedTopics = emptySet()
                userChangeListener = null
                initialized = false
            }
            VisionPushLogger.i(TAG, "User logged out; device unregistered. SDK reset – call initialize() to re-register.")
            withContext(Dispatchers.Main) { callback?.invoke(result) }
        }
    }

    // ── Privacy Consent (GDPR / CCPA) ───────────────────────────────────────

    /**
     * `true` when the SDK is allowed to send data to the VisionPush backend.
     *
     * Returns `true` unconditionally when [VisionPushConfig.consentRequired] is `false`
     * (the default). When `consentRequired` is `true`, returns `true` only after
     * [setConsentGiven] has been called with `true`.
     */
    @get:JvmStatic
    val hasConsent: Boolean
        get() {
            val config = activeConfig ?: return true
            if (!config.consentRequired) return true
            val ctx = applicationContext ?: return false
            return isConsentGiven(ctx)
        }

    /**
     * Grants or revokes user consent for VisionPush data collection.
     *
     * Only has an effect when [VisionPushConfig.consentRequired] is `true`.
     * When [given] is `true` and the SDK is in the consent-pending state, this
     * method triggers full device registration with the VisionPush backend.
     *
     * When [given] is `false` the consent flag is cleared so future backend calls
     * fail with [VisionPushError.ConsentRequired], AND every automatic data flow is
     * torn down: the realtime channel is disconnected ([disableRealtime]), geofencing
     * stops reporting location ([disableGeofencing]), tracking events still queued in
     * WorkManager are cancelled, and the automatic DELIVERED / OPENED / DISMISSED
     * events of subsequently received notifications are dropped. Notifications are
     * still *displayed* — display is not data collection.
     *
     * @param given `true` to grant consent, `false` to revoke it.
     */
    @JvmStatic
    fun setConsentGiven(given: Boolean) {
        val ctx = applicationContext ?: return
        persistConsent(ctx, given)
        VisionPushLogger.i(TAG, "Privacy consent ${if (given) "granted" else "revoked"}.")

        if (!given) {
            // Revocation must stop every automatic data flow, not just flip a flag.
            disableRealtime()
            disableGeofencing()
            NotificationTracker.cancelPendingWork(ctx)
            return
        }

        // Trigger backend registration now that consent has been granted.
        sdkScope.launch {
            internalDeviceRegistrar?.register(forceRefresh = true)
                ?.onSuccess { id ->
                    VisionPushLogger.i(TAG, "Device registered after consent. deviceId=$id")
                    notifyDeviceRegistered(id)
                }
                ?.onFailure { error ->
                    VisionPushLogger.w(TAG, "Post-consent registration failed: ${error.message}")
                    applicationContext?.let { scheduleRegistrationSync(it) }
                }
        }
    }

    // ── Custom Outcomes ─────────────────────────────────────────────────────

    /**
     * Records a named conversion event tied to the most recently received notification.
     *
     * Custom outcomes let you measure any conversion that matters (e.g.
     * "purchase_completed", "tutorial_finished"). Results appear in the
     * VisionPush analytics dashboard.
     *
     * @param name     Outcome identifier, max 256 characters.
     * @param callback Optional result callback invoked on the main thread.
     */
    @JvmStatic
    @JvmOverloads
    fun sendOutcome(name: String, callback: ((VisionPushResult<Unit>) -> Unit)? = null) {
        if (name.length > 256) {
            throw VisionPushError.InvalidArgument("Outcome name must be 256 characters or fewer.")
        }
        checkInitialized()
        checkConsent()

        sdkScope.launch {
            val deviceId = internalDeviceRegistrar?.deviceId
            val result = if (deviceId != null) {
                internalApiClient?.sendOutcome(deviceId, name)
                    ?: VisionPushResult.Failure(VisionPushError.NotInitialized())
            } else {
                VisionPushResult.Failure(VisionPushError.TokenUnavailable("Device not yet registered."))
            }
            withContext(Dispatchers.Main) { callback?.invoke(result) }
        }
    }

    /**
     * Records a named conversion event with an associated numeric value.
     *
     * @param name     Outcome identifier, max 256 characters.
     * @param value    Numeric value to attach (e.g. revenue in the local currency).
     * @param callback Optional result callback invoked on the main thread.
     */
    @JvmStatic
    @JvmOverloads
    fun sendOutcomeWithValue(
        name: String,
        value: Float,
        callback: ((VisionPushResult<Unit>) -> Unit)? = null
    ) {
        if (name.length > 256) {
            throw VisionPushError.InvalidArgument("Outcome name must be 256 characters or fewer.")
        }
        checkInitialized()
        checkConsent()

        sdkScope.launch {
            val deviceId = internalDeviceRegistrar?.deviceId
            val result = if (deviceId != null) {
                internalApiClient?.sendOutcome(deviceId, name, value)
                    ?: VisionPushResult.Failure(VisionPushError.NotInitialized())
            } else {
                VisionPushResult.Failure(VisionPushError.TokenUnavailable("Device not yet registered."))
            }
            withContext(Dispatchers.Main) { callback?.invoke(result) }
        }
    }

    /**
     * Records a named conversion event with an associated double-precision numeric value.
     *
     * This overload accepts a [Double] value for interoperability with callers that
     * work with double-precision numbers (e.g. the React Native bridge).
     *
     * @param name     Outcome identifier, max 256 characters.
     * @param value    Double-precision numeric value to attach (e.g. revenue).
     * @param callback Optional result callback invoked on the main thread.
     */
    @JvmStatic
    @JvmOverloads
    fun sendOutcomeWithValue(
        name: String,
        value: Double,
        callback: ((VisionPushResult<Unit>) -> Unit)? = null
    ) {
        sendOutcomeWithValue(name, value.toFloat(), callback)
    }

    // ── Server-Side Tags ────────────────────────────────────────────────────

    /**
     * Fetches the current device tags from the VisionPush backend and refreshes
     * the local [tags] cache.
     *
     * Use this to retrieve tags set server-side (e.g. from a CRM integration).
     * The result also becomes the new value of the [tags] property.
     *
     * @param callback Optional result callback invoked on the main thread.
     */
    @JvmStatic
    @JvmOverloads
    fun refreshTags(callback: ((VisionPushResult<Map<String, String>>) -> Unit)? = null) {
        checkInitialized()
        checkConsent()

        sdkScope.launch {
            val deviceId = internalDeviceRegistrar?.deviceId
            val result = if (deviceId != null) {
                internalApiClient?.getDeviceTags(deviceId)
                    ?: VisionPushResult.Failure(VisionPushError.NotInitialized())
            } else {
                VisionPushResult.Failure(VisionPushError.TokenUnavailable("Device not yet registered."))
            }
            if (result.isSuccess) {
                val serverTags = (result as VisionPushResult.Success).data
                cachedTags = serverTags
                VisionPushLogger.d(TAG, "Tags refreshed from server: ${serverTags.size} entries.")
            }
            withContext(Dispatchers.Main) { callback?.invoke(result) }
        }
    }

    // ── User Profile ────────────────────────────────────────────────────────

    /**
     * Updates the rich user profile associated with this device.
     *
     * Profile fields carry semantic meaning (name, email, birth date) and are
     * used for personalisation and cross-channel targeting, unlike arbitrary
     * key-value [tags].
     *
     * @param profile  The profile fields to update. Null fields are ignored.
     * @param callback Optional result callback invoked on the main thread.
     */
    @JvmStatic
    @JvmOverloads
    fun setUserProfile(
        profile: UserProfile,
        callback: ((VisionPushResult<Unit>) -> Unit)? = null
    ) {
        checkInitialized()
        checkConsent()
        sdkScope.launch {
            val deviceId = internalDeviceRegistrar?.deviceId
            val result = if (deviceId != null) {
                internalApiClient?.setUserProfile(deviceId, profile)
                    ?: VisionPushResult.Failure(VisionPushError.NotInitialized())
            } else {
                VisionPushResult.Failure(VisionPushError.TokenUnavailable("Device not yet registered."))
            }
            withContext(Dispatchers.Main) { callback?.invoke(result) }
        }
    }

    // ── Subscription Topics ──────────────────────────────────────────────────

    /**
     * Subscribes this device to one or more notification topics.
     *
     * Topics let users opt in to specific categories of notifications (e.g.
     * "breaking_news", "promotions") independently of the global opt-in state.
     *
     * @param topics   Topic keys to subscribe to (max 50 per call).
     * @param callback Optional result callback invoked on the main thread.
     */
    @JvmStatic
    @JvmOverloads
    fun subscribeToTopics(
        topics: List<String>,
        callback: ((VisionPushResult<Unit>) -> Unit)? = null
    ) {
        if (topics.size > 50) throw VisionPushError.InvalidArgument("Maximum 50 topics per call.")
        if (topics.isEmpty()) { callback?.invoke(VisionPushResult.Success(Unit)); return }
        checkInitialized()
        checkConsent()
        sdkScope.launch {
            val deviceId = internalDeviceRegistrar?.deviceId
            val result = if (deviceId != null) {
                internalApiClient?.subscribeToTopics(deviceId, topics)
                    ?: VisionPushResult.Failure(VisionPushError.NotInitialized())
            } else {
                VisionPushResult.Failure(VisionPushError.TokenUnavailable("Device not yet registered."))
            }
            if (result.isSuccess) cachedTopics = cachedTopics + topics.toSet()
            withContext(Dispatchers.Main) { callback?.invoke(result) }
        }
    }

    /**
     * Unsubscribes this device from one or more notification topics.
     *
     * @param topics   Topic keys to unsubscribe from.
     * @param callback Optional result callback invoked on the main thread.
     */
    @JvmStatic
    @JvmOverloads
    fun unsubscribeFromTopics(
        topics: List<String>,
        callback: ((VisionPushResult<Unit>) -> Unit)? = null
    ) {
        if (topics.isEmpty()) { callback?.invoke(VisionPushResult.Success(Unit)); return }
        checkInitialized()
        checkConsent()
        sdkScope.launch {
            val deviceId = internalDeviceRegistrar?.deviceId
            val result = if (deviceId != null) {
                internalApiClient?.unsubscribeFromTopics(deviceId, topics)
                    ?: VisionPushResult.Failure(VisionPushError.NotInitialized())
            } else {
                VisionPushResult.Failure(VisionPushError.TokenUnavailable("Device not yet registered."))
            }
            if (result.isSuccess) cachedTopics = cachedTopics - topics.toSet()
            withContext(Dispatchers.Main) { callback?.invoke(result) }
        }
    }

    /** The notification topics this device is currently subscribed to (local cache). */
    @get:JvmStatic
    val subscribedTopics: List<String> get() = cachedTopics.sorted()

    // ── Notification History ─────────────────────────────────────────────────

    /**
     * Returns the notification history stored on this device, newest first.
     *
     * Stores up to [HISTORY_MAX_SIZE] entries across app restarts. Each entry
     * records the notification title/body, campaign ID, and whether the user
     * opened it. Useful for building an in-app inbox.
     */
    @JvmStatic
    fun getNotificationHistory(): List<VisionPushNotificationRecord> =
        synchronized(notificationHistory) { notificationHistory.toList() }

    // ── Soft Permission Prompt ───────────────────────────────────────────────

    /**
     * Displays a custom in-app [android.app.AlertDialog] before the system
     * POST_NOTIFICATIONS permission dialog.
     *
     * Use this to explain to users why notifications are valuable before
     * triggering the non-dismissible system dialog. The system dialog is only
     * shown when the user taps the accept button.
     *
     * @param activity      The foreground [Activity] used to show the dialog.
     * @param title         Dialog title (default: "Stay Updated").
     * @param message       Dialog message (default: generic benefit copy).
     * @param acceptTitle   Accept button label (default: "Allow").
     * @param declineTitle  Decline button label (default: "Not Now").
     * @param onAccepted    Called when the user taps accept (after launching the system dialog).
     * @param onDeclined    Called when the user taps decline.
     */
    @JvmStatic
    @JvmOverloads
    @MainThread
    fun showPermissionRationale(
        activity: Activity,
        title: String = "Stay Updated",
        message: String = "Enable notifications to get important updates and personalised messages.",
        acceptTitle: String = "Allow",
        declineTitle: String = "Not Now",
        onAccepted: (() -> Unit)? = null,
        onDeclined: (() -> Unit)? = null
    ) {
        android.app.AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(acceptTitle) { _, _ ->
                onAccepted?.invoke()
                requestPermission(activity)
            }
            .setNegativeButton(declineTitle) { _, _ -> onDeclined?.invoke() }
            .setCancelable(false)
            .show()
    }

    // ── Permission resync ───────────────────────────────────────────────────

    /**
     * Call this from your main [Activity.onResume] (or via [ProcessLifecycleOwner])
     * to detect when the user changes notification settings in the system settings
     * and returns to the app.
     *
     * When notifications are re-enabled after a prior opt-out, the SDK automatically
     * opts the device back in. When notifications are disabled after a prior opt-in,
     * the SDK opts the device out.
     *
     * No-op if the SDK is not initialized or consent has not been given.
     */
    @JvmStatic
    @MainThread
    fun onAppForeground() {
        if (!initialized || !hasConsent) return
        val ctx = applicationContext ?: return
        sdkScope.launch {
            val registrar = internalDeviceRegistrar ?: return@launch
            val notificationsEnabled = androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled()
            when {
                notificationsEnabled && registrar.isOptedOut -> {
                    registrar.setOptedOut(false)
                    VisionPushLogger.i(TAG, "Notifications re-enabled in Settings – opting back in.")
                }
                !notificationsEnabled && !registrar.isOptedOut -> {
                    registrar.setOptedOut(true)
                    VisionPushLogger.i(TAG, "Notifications disabled in Settings – opting out.")
                }
            }
        }
    }

    // ── In-App Messages ──────────────────────────────────────────────────────

    /**
     * Registers a callback invoked when the user taps a button in an in-app message.
     *
     * @param handler Lambda receiving the [InAppMessage] and the tapped [IAMButton].
     */
    @JvmStatic
    @MainThread
    fun setInAppMessageClickHandler(handler: ((InAppMessage, IAMButton) -> Unit)?) {
        internalIamController?.onButtonTapped = handler
    }

    /**
     * Registers a callback invoked when an in-app message is dismissed.
     *
     * @param handler Lambda receiving the dismissed [InAppMessage].
     */
    @JvmStatic
    @MainThread
    fun setInAppMessageDismissHandler(handler: ((InAppMessage) -> Unit)?) {
        internalIamController?.onDismissed = handler
    }

    /**
     * Manually presents the in-app message with the given [id].
     * Useful for messages with a `manual` trigger type.
     */
    @JvmStatic
    @MainThread
    fun presentInAppMessage(id: String) {
        internalIamController?.presentMessage(id)
    }

    /**
     * Fires all queued in-app messages whose trigger type is `custom_event` and
     * whose event name matches [eventName].
     *
     * Call this whenever a meaningful event occurs in your app — for example after
     * a purchase, a level completion, or an onboarding milestone.
     *
     * @param eventName The event name configured in the VisionPush dashboard.
     */
    @JvmStatic
    @MainThread
    fun triggerCustomEvent(eventName: String) {
        internalIamController?.triggerCustomEvent(eventName)
    }

    // ── Realtime (owned-transport WebSocket) ─────────────────────────────────

    /**
     * Opens the owned-transport realtime WebSocket to the VisionPush gateway.
     *
     * While the channel is connected this device is **ONLINE** and pushes are
     * delivered over the persistent socket instead of FCM (no FCM quota used).
     * Received pushes are forwarded to the same [setNotificationHandler] callback
     * apps already use for FCM notifications, so no extra wiring is required.
     *
     * The channel auto-reconnects with exponential back-off + jitter on transient
     * failures. Online delivery is best-effort / at-most-once (Phase 1).
     *
     * Calling this again while already enabled re-resolves the device identity and
     * reconnects. Called before device registration has completed (registration is
     * asynchronous — [initialize] returns first), the connect is **deferred** and
     * performed automatically once the device is registered; connecting earlier would
     * only make the gateway reject an unknown device.
     *
     * If the gateway rejects the device (close code `4401` auth rejected / `4404`
     * device unknown or inactive) the channel stops reconnecting and logs a warning
     * once — fix the cause (API key, re-registration) and call this again.
     *
     * @param installationId Optional explicit device identifier. When omitted the
     *   SDK's own installation ID (the same one used for device registration) is used.
     */
    @JvmStatic
    @JvmOverloads
    fun enableRealtime(installationId: String? = null) {
        checkInitialized()
        checkConsent()
        val config = activeConfig ?: throw VisionPushError.NotInitialized()
        val registrar = internalDeviceRegistrar ?: throw VisionPushError.NotInitialized()

        if (registrar.deviceId == null) {
            // `initialized` flips before the async registration completes. Connecting now
            // would be rejected with 4404 (device unknown) — defer until the registration
            // success path calls notifyDeviceRegistered().
            realtimeDeferredInstallationId = installationId?.takeIf { it.isNotBlank() } ?: ""
            realtimeDeferred = true
            VisionPushLogger.i(
                TAG,
                "enableRealtime() called before device registration completed – deferring the connect until the device is registered."
            )
            return
        }
        realtimeDeferred = false

        val resolvedId = installationId?.takeIf { it.isNotBlank() } ?: registrar.anonymousId
        if (resolvedId.isBlank()) {
            VisionPushLogger.w(TAG, "enableRealtime() called before an installation ID is available – ignoring.")
            return
        }

        // Tear down any prior channel so identity changes take effect.
        internalRealtimeChannel?.disconnect()

        val channel = RealtimeChannel(
            baseUrl = config.apiBaseUrl,
            apiKey = config.apiKey,
            installationId = resolvedId,
            appId = config.appId,
            onPush = { payload, _ -> handleRealtimePush(payload) },
            onStatusChange = { state ->
                sdkScope.launch { realtimeStatusHandler?.invoke(state) }
            },
            factory = realtimeTransportFactory ?: OkHttpWebSocketFactory(),
            scheduler = realtimeReconnectScheduler ?: HandlerReconnectScheduler(),
        )
        internalRealtimeChannel = channel
        channel.connect()
        VisionPushLogger.i(TAG, "Realtime channel enabled.")
    }

    /**
     * Closes the realtime channel and stops reconnect attempts (and cancels a connect
     * that [enableRealtime] deferred). Pushes resume being delivered over FCM. Safe
     * to call when realtime is disabled (no-op).
     */
    @JvmStatic
    fun disableRealtime() {
        realtimeDeferred = false
        internalRealtimeChannel?.disconnect()
        internalRealtimeChannel = null
        VisionPushLogger.i(TAG, "Realtime channel disabled.")
    }

    /** The current realtime connection state, or [RealtimeConnectionState.CLOSED] when disabled. */
    @get:JvmStatic
    val realtimeState: RealtimeConnectionState
        get() = internalRealtimeChannel?.connectionState ?: RealtimeConnectionState.CLOSED

    /** `true` when the realtime channel is connected and online. */
    @get:JvmStatic
    val isRealtimeConnected: Boolean
        get() = internalRealtimeChannel?.isConnected ?: false

    /**
     * Registers a callback invoked when the realtime connection state changes.
     * Invoked on the main thread. Pass `null` to clear the handler.
     */
    @JvmStatic
    fun setRealtimeStatusHandler(handler: ((RealtimeConnectionState) -> Unit)?) {
        realtimeStatusHandler = handler
    }

    // ── Geofencing (location-triggered campaigns) ────────────────────────────

    /**
     * Enables on-device geofencing.
     *
     * Once enabled the SDK periodically reports the device's coarse location to the
     * VisionPush backend (`PUT /devices/{id}/location`), receives the nearest active
     * geofences, and registers them with the OS. Geofence entry/exit/dwell transitions
     * are reported back to the backend ([GeofenceManager] → [GeofenceTransitionReceiver]
     * → [GeofenceEventWorker]) so location-triggered campaigns can fire.
     *
     * **The host app must request the location runtime permissions itself** before (or
     * after) calling this — `ACCESS_FINE_LOCATION` and, for background geofencing on
     * Android 10+ (API 29), `ACCESS_BACKGROUND_LOCATION`. The SDK never prompts for
     * location; without the permission the manager simply stays idle until it is granted
     * and [enableGeofencing] is called again. The permissions are declared in the SDK
     * manifest but left for the host to request at the right moment in its UX.
     *
     * No-op when called repeatedly while already enabled (the existing manager is reused).
     * Mirrors the iOS SDK's geofencing entry point.
     */
    @JvmStatic
    @MainThread
    fun enableGeofencing() {
        checkInitialized()
        checkConsent()
        if (internalGeofenceManager != null) {
            VisionPushLogger.d(TAG, "Geofencing already enabled – ignoring duplicate call.")
            return
        }
        val ctx = applicationContext ?: throw VisionPushError.NotInitialized()
        val client = internalApiClient ?: throw VisionPushError.NotInitialized()

        val manager = GeofenceManager(ctx, client)
        // If the device is already registered, attribute location syncs immediately;
        // otherwise the registration-success path wires the deviceId once it arrives.
        internalDeviceRegistrar?.deviceId?.let { manager.setDeviceId(it) }
        internalGeofenceManager = manager
        manager.start()
        VisionPushLogger.i(TAG, "Geofencing enabled.")
    }

    /**
     * Disables on-device geofencing: stops location updates and removes all SDK-registered
     * geofences from the OS. Safe to call when geofencing is disabled (no-op).
     */
    @JvmStatic
    @MainThread
    fun disableGeofencing() {
        internalGeofenceManager?.let { manager ->
            manager.stop()
            internalGeofenceManager = null
            VisionPushLogger.i(TAG, "Geofencing disabled.")
        }
    }

    /** `true` when on-device geofencing is currently enabled. */
    @get:JvmStatic
    val isGeofencingEnabled: Boolean
        get() = internalGeofenceManager != null

    /**
     * Forwards a realtime push payload into the same notification pipeline used
     * for FCM messages, so consumers see a single delivery API.
     */
    private fun handleRealtimePush(payload: org.json.JSONObject) {
        val notification = try {
            VisionPushNotification.fromJson(payload)
        } catch (e: Exception) {
            VisionPushLogger.w(TAG, "Dropping malformed realtime push payload: ${e.message}")
            return
        }
        sdkScope.launch { deliverNotification(notification) }
    }

    // ── Inbound push pipeline (shared by the FCM + HMS messaging services) ────

    /**
     * Processes an inbound push data map from a platform messaging service —
     * [VisionPushMessagingService] (FCM) or `VisionPushHmsMessagingService` (HMS).
     *
     * Unseals a sealed payload (Mode A/B) with [sealedKeys], parses the VisionPush
     * envelope (`vp_payload`), and routes it through [deliverNotification] — the single
     * delivery path shared with the realtime channel — so caching, history, the custom
     * handler, the system UI, and the DELIVERED event behave identically across all
     * delivery sources (FCM, HMS, realtime).
     *
     * @param data       Raw provider data map (FCM `RemoteMessage.data` /
     *                   HMS `RemoteMessage.dataOfMap`).
     * @param sealedKeys This device's sealed-payload keys, or `null` to skip unsealing
     *                   (a sealed push then keeps its neutral placeholder — fail closed).
     */
    @JvmStatic
    fun handleRemoteData(data: Map<String, String>, sealedKeys: VisionPushSeal.DeviceKeys? = null) {
        val unsealed = VisionPushSeal.unsealFcmData(data, sealedKeys)
        val notification = VisionPushNotification.fromFcmData(unsealed)
        if (notification == null) {
            VisionPushLogger.d(TAG, "Inbound message has no VisionPush payload – skipping.")
            return
        }
        VisionPushLogger.i(TAG, "VisionPush notification received: id=${notification.id}")
        sdkScope.launch { deliverNotification(notification) }
    }

    /**
     * Unified inbound-delivery path for every source (FCM, HMS, realtime).
     *
     * Performs, in order and exactly once per notification:
     * 1. caches the model for open-tracking ([storeNotification]),
     * 2. appends it to the local notification history ([recordNotificationReceived]),
     * 3. invokes the custom [setNotificationHandler] callback,
     * 4. either posts the system notification (which records the DELIVERED event), or —
     *    when the system UI is suppressed — records a standalone DELIVERED event.
     *
     * Both the history append and the DELIVERED event are de-duplicated by VP notification
     * ID (history in [recordNotificationReceived], DELIVERED in
     * [NotificationBuilder.trackDeliveredOnce]) so duplicate provider deliveries of the same
     * message do not double-count.
     */
    private suspend fun deliverNotification(notification: VisionPushNotification) {
        storeNotification(notification)
        recordNotificationReceived(notification)

        internalNotificationHandler?.let { handler ->
            VisionPushLogger.d(TAG, "Delegating notification to custom handler.")
            handler(notification)
        }

        val builder = internalNotificationBuilder
        when {
            builder == null ->
                // SDK not initialized – no builder means we can neither show UI nor track.
                VisionPushLogger.w(
                    TAG,
                    "SDK not initialized – cannot display or track notification id=${notification.id}"
                )
            notification.showSystemNotification && foregroundNotificationEnabled ->
                builder.show(notification) // records DELIVERED (deduped) after posting
            else ->
                // No system UI shown – still record the DELIVERED event once for this ID.
                builder.trackDeliveredOnce(notification)
        }
    }

    /**
     * Handles a refreshed push token from a platform messaging service (FCM or HMS).
     *
     * Re-registers the device immediately when the SDK is initialized; otherwise (or
     * on network failure) schedules a WorkManager sync so the new token reaches the
     * backend once [initialize] has run / connectivity returns.
     *
     * @param context A context (the messaging service's `applicationContext`).
     * @param token   The new platform push token (FCM or HMS).
     */
    @JvmStatic
    fun handleNewToken(context: Context, token: String) {
        VisionPushLogger.i(TAG, "Push token refreshed – re-registering device.")
        val appContext = context.applicationContext
        sdkScope.launch {
            val registrar = internalDeviceRegistrar
            if (registrar == null) {
                VisionPushLogger.w(
                    TAG,
                    "Token received before SDK init – scheduling sync for after initialize()."
                )
                RegistrationSyncWorker.enqueue(appContext)
                return@launch
            }
            registrar.handleTokenRefresh(token)
                .onFailure { error ->
                    VisionPushLogger.e(TAG, "Token refresh registration failed: ${error.message}")
                    scheduleRegistrationSync(appContext)
                }
        }
    }

    // ── Logging (cross-module shim) ──────────────────────────────────────────

    /**
     * Routes a log line through the SDK's level-gated [VisionPushLogger].
     *
     * The logger itself is `internal`, so a companion Gradle module — notably the
     * Huawei transport (`VisionPushHms`) — cannot reach it directly and would
     * otherwise have to call `android.util.Log` unconditionally, bypassing the
     * verbosity the host app configured via [VisionPushConfig.logLevel]. This
     * `public` shim lets such modules honour that level with a single call.
     *
     * The line is emitted only when [level] passes the configured minimum, exactly
     * as for the core SDK's own logging.
     *
     * @param level     Severity of this line.
     * @param tag       Log tag (e.g. `"VP.Hms"`).
     * @param message   Human-readable message.
     * @param throwable Optional cause, attached for [VisionPushConfig.LogLevel.WARN]
     *                  and [VisionPushConfig.LogLevel.ERROR] so stack traces survive.
     */
    @JvmStatic
    @JvmOverloads
    fun logInternal(
        level: VisionPushConfig.LogLevel,
        tag: String,
        message: String,
        throwable: Throwable? = null
    ) {
        when (level) {
            VisionPushConfig.LogLevel.NONE -> Unit
            VisionPushConfig.LogLevel.VERBOSE -> VisionPushLogger.v(tag, message)
            VisionPushConfig.LogLevel.DEBUG -> VisionPushLogger.d(tag, message)
            VisionPushConfig.LogLevel.INFO -> VisionPushLogger.i(tag, message)
            VisionPushConfig.LogLevel.WARN -> VisionPushLogger.w(tag, message, throwable)
            VisionPushConfig.LogLevel.ERROR -> VisionPushLogger.e(tag, message, throwable)
        }
    }

    // ── Badge Management ─────────────────────────────────────────────────────

    /**
     * Sets the badge count applied to the next notification posted by VisionPush.
     *
     * Uses [NotificationCompat.Builder.setNumber], which is respected by launchers
     * that support badge counts (Samsung, Pixel, Huawei, etc.).
     *
     * @param count Non-negative badge value. Pass 0 to clear the badge.
     */
    @JvmStatic
    @MainThread
    fun setBadge(count: Int) {
        badgeCount = count.coerceAtLeast(0)
    }

    /** Increments the badge count by one. */
    @JvmStatic
    @MainThread
    fun incrementBadge() {
        badgeCount += 1
    }

    /** Resets the badge count to zero. */
    @JvmStatic
    @MainThread
    fun clearBadge() {
        badgeCount = 0
    }

    // ── Notification Action Buttons ──────────────────────────────────────────

    /**
     * Adds a default action button that appears on every push notification
     * that does not define its own actions.
     *
     * Default actions are evaluated in insertion order. The system enforces a
     * maximum of three action buttons per notification.
     *
     * @param title      Visible button label shown in the notification drawer.
     * @param identifier Unique identifier used for analytics tracking.
     */
    @JvmStatic
    fun addDefaultAction(title: String, identifier: String) {
        defaultActions = defaultActions + NotificationAction(
            id = identifier, title = title, deepLink = null, iconName = null
        )
    }

    /** Removes all previously registered default action buttons. */
    @JvmStatic
    fun clearDefaultActions() {
        defaultActions = emptyList()
    }

    // ── Diagnostics ─────────────────────────────────────────────────────────

    /**
     * Collects a [DiagnosticReport] that captures the current SDK state and
     * network reachability, then delivers it on the main thread via [callback].
     *
     * Typical usage:
     * ```kotlin
     * VisionPush.runDiagnostics(context) { report ->
     *     Log.d("VisionPush", report.summary())
     * }
     * ```
     *
     * The method is non-blocking: the network probe runs on [kotlinx.coroutines.Dispatchers.IO]
     * with a 3-second timeout, and [callback] is always invoked on the main thread.
     *
     * @param context  Any [android.content.Context]; only used for system service access.
     * @param callback Invoked on the main thread with the completed [DiagnosticReport].
     */
    @JvmStatic
    fun runDiagnostics(context: Context, callback: (DiagnosticReport) -> Unit) {
        sdkScope.launch {
            val report = buildDiagnosticReport(context)
            withContext(Dispatchers.Main) { callback(report) }
        }
    }

    /** The server-assigned device ID, or `null` before first registration. */
    @get:JvmStatic
    val deviceId: String? get() = internalDeviceRegistrar?.deviceId

    /** `true` when [initialize] has been called successfully. */
    @get:JvmStatic
    val isInitialized: Boolean get() = initialized

    // ── Package-internal helpers ─────────────────────────────────────────────

    /**
     * Stores [notification] in the in-memory cache so that [NotificationTracker.OpenReceiver]
     * can resolve the full model when the user taps the notification.
     */
    internal fun storeNotification(notification: VisionPushNotification) {
        notificationCache[notification.id] = notification
    }

    /** Returns the cached [VisionPushNotification] for [notificationId], or `null`. */
    internal fun getStoredNotification(notificationId: String): VisionPushNotification? =
        notificationCache[notificationId]

    /**
     * Fire-and-forget outcome dispatch shared by the public [sendOutcome] API and the
     * in-app-message OUTCOME button wiring ([InAppMessageController.onOutcome]). Records
     * a named conversion via the same backend endpoint; failures are logged, not surfaced.
     */
    private fun dispatchOutcome(name: String) {
        sdkScope.launch {
            val deviceId = internalDeviceRegistrar?.deviceId
            if (deviceId == null) {
                VisionPushLogger.w(TAG, "Outcome '$name' dropped – device not yet registered.")
                return@launch
            }
            internalApiClient?.sendOutcome(deviceId, name)
                ?.onFailure { error -> VisionPushLogger.w(TAG, "Outcome '$name' failed: ${error.message}") }
        }
    }

    /**
     * Fire-and-forget in-app-message click tracking — shared by the US-16 HTML
     * bridge's `click(actionId, …)` ([InAppMessageController.onHtmlClick]) and a
     * native modal/banner/fullscreen button tap ([InAppMessageController.onNativeClick],
     * SQ-290). Both callers already guarantee at most one terminal event per display.
     */
    private fun dispatchIamClick(messageId: String, buttonId: String) {
        sdkScope.launch {
            val appId = activeConfig?.appId
            val deviceId = internalDeviceRegistrar?.deviceId
            if (appId == null || deviceId == null) {
                VisionPushLogger.w(TAG, "IAM click on '$messageId' dropped – not yet initialized/registered.")
                return@launch
            }
            internalApiClient?.trackIAMClick(appId, deviceId, messageId, buttonId)
                ?.onFailure { error -> VisionPushLogger.w(TAG, "IAM click tracking failed: ${error.message}") }
        }
    }

    /**
     * Fire-and-forget in-app-message dismiss tracking — shared by the US-16 HTML
     * bridge's `close()` ([InAppMessageController.onHtmlDismiss]) and a native
     * modal/banner/fullscreen dismiss ([InAppMessageController.onNativeDismiss],
     * SQ-290). Both callers already guarantee at most one terminal event per display.
     */
    private fun dispatchIamDismiss(messageId: String) {
        sdkScope.launch {
            val appId = activeConfig?.appId
            val deviceId = internalDeviceRegistrar?.deviceId
            if (appId == null || deviceId == null) {
                VisionPushLogger.w(TAG, "IAM dismiss on '$messageId' dropped – not yet initialized/registered.")
                return@launch
            }
            internalApiClient?.trackIAMDismiss(appId, deviceId, messageId)
                ?.onFailure { error -> VisionPushLogger.w(TAG, "IAM dismiss tracking failed: ${error.message}") }
        }
    }

    /**
     * Schedules a background device registration sync via WorkManager.
     * Called when an immediate registration attempt fails due to no network.
     */
    internal fun scheduleRegistrationSync(context: Context) {
        RegistrationSyncWorker.enqueue(context)
    }

    /**
     * Post-registration hook: attributes geofence syncs to the new device id and
     * performs a realtime connect that [enableRealtime] deferred because no device
     * existed yet. Idempotent; safe to call on every successful registration.
     */
    internal fun notifyDeviceRegistered(deviceId: String) {
        internalGeofenceManager?.setDeviceId(deviceId)
        if (!realtimeDeferred) return
        realtimeDeferred = false
        val explicitId = realtimeDeferredInstallationId.takeIf { it.isNotBlank() }
        try {
            enableRealtime(explicitId)
        } catch (e: VisionPushError) {
            VisionPushLogger.w(TAG, "Deferred realtime enable failed: ${e.message}")
        }
    }

    /**
     * Test-only: returns the singleton to its pristine, un-initialized state without
     * any network I/O (unlike [deleteDevice]). Robolectric test classes share one sandbox
     * class loader, so a test that initializes the SDK must reset it in `@After`.
     */
    @VisibleForTesting
    internal fun resetForTesting() {
        synchronized(VisionPush) {
            internalRealtimeChannel?.disconnect()
            internalRealtimeChannel = null
            internalGeofenceManager = null
            internalApiClient = null
            internalDeviceRegistrar = null
            internalNotificationBuilder = null
            internalIamController = null
            internalNotificationHandler = null
            internalNotificationOpenCallback = null
            realtimeStatusHandler = null
            errorHandler = null
            onRegisteredCallback = null
            onRegistrationFailedCallback = null
            userChangeListener = null
            activeConfig = null
            cachedTags = emptyMap()
            cachedTopics = emptySet()
            realtimeDeferred = false
            realtimeDeferredInstallationId = ""
            realtimeTransportFactory = null
            realtimeReconnectScheduler = null
            pushTokenProvider = null
            applicationContext = null
            initialized = false
        }
    }

    // ── Precondition guards ──────────────────────────────────────────────────

    private fun checkInitialized() {
        if (!initialized) throw VisionPushError.NotInitialized()
    }

    private fun checkConsent() {
        if (!hasConsent) throw VisionPushError.ConsentRequired()
    }

    // ── Consent persistence ──────────────────────────────────────────────────

    private fun isConsentGiven(context: Context): Boolean =
        context.getSharedPreferences(CONSENT_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_CONSENT_GIVEN, false)

    private fun persistConsent(context: Context, given: Boolean) {
        context.getSharedPreferences(CONSENT_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_CONSENT_GIVEN, given).apply()
    }

    // ── Notification history helpers ─────────────────────────────────────────

    internal fun recordNotificationReceived(notification: VisionPushNotification) {
        val record = VisionPushNotificationRecord(
            id         = notification.id,
            title      = notification.title,
            body       = notification.body,
            campaignId = notification.campaignId,
            deepLink   = notification.deepLink,
            receivedAt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                .format(java.util.Date())
        )
        synchronized(notificationHistory) {
            notificationHistory.removeAll { it.id == record.id }
            notificationHistory.add(0, record)
            if (notificationHistory.size > HISTORY_MAX_SIZE) {
                notificationHistory.subList(HISTORY_MAX_SIZE, notificationHistory.size).clear()
            }
        }
        persistHistory()
    }

    internal fun markNotificationOpened(notificationId: String) {
        synchronized(notificationHistory) {
            notificationHistory.indexOfFirst { it.id == notificationId }
                .takeIf { it >= 0 }
                ?.let { notificationHistory[it] = notificationHistory[it].copy(wasOpened = true) }
        }
        persistHistory()
    }

    private fun loadHistory() {
        val ctx = applicationContext ?: return
        val json = ctx.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_HISTORY, null) ?: return
        try {
            val arr = org.json.JSONArray(json)
            val loaded = (0 until arr.length()).map { VisionPushNotificationRecord.fromJson(arr.getJSONObject(it)) }
            synchronized(notificationHistory) {
                notificationHistory.clear()
                notificationHistory.addAll(loaded)
            }
        } catch (_: Exception) {}
    }

    private fun persistHistory() {
        val ctx = applicationContext ?: return
        val arr = org.json.JSONArray()
        synchronized(notificationHistory) {
            notificationHistory.forEach { arr.put(VisionPushNotificationRecord.toJson(it)) }
        }
        ctx.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    // ── Constants ────────────────────────────────────────────────────────────

    private const val TAG = "VisionPush"

    /**
     * Arbitrary SDK-owned request code for [Activity.requestPermissions].
     * Chosen to be unlikely to collide with host-app request codes.
     */
    private const val PERMISSION_REQUEST_CODE = 0x5050

    private const val CONSENT_PREFS = "visionpush_consent"
    private const val KEY_CONSENT_GIVEN = "consent_given"

    private const val HISTORY_PREFS = "visionpush_history"
    private const val KEY_HISTORY = "notification_history"
    private const val HISTORY_MAX_SIZE = 50
}
