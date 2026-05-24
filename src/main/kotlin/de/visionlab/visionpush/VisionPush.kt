package de.visionlab.visionpush

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.MainThread
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
 * VisionPush.setExternalId("user_123")
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

    /** Retained application context — set during [initialize], never null after that. */
    @Volatile private var applicationContext: Context? = null

    /** Handler invoked when a notification arrives (set via [setNotificationHandler]). */
    @Volatile internal var internalNotificationHandler: ((VisionPushNotification) -> Unit)? = null

    /** Handler invoked when the user opens a notification (set via [setNotificationOpenHandler]). */
    @Volatile internal var internalNotificationOpenCallback: ((VisionPushNotification) -> Unit)? = null

    /** Handler invoked when an SDK-internal background error occurs (set via [onError]). */
    @Volatile private var errorHandler: ((VisionPushError) -> Unit)? = null

    /** SDK-wide coroutine scope; survives the entire application lifetime. */
    private val sdkScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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

    // ── Initialization ──────────────────────────────────────────────────────

    /**
     * Initializes the VisionPush SDK with explicit parameters.
     *
     * Must be called once before any other SDK method, typically in
     * [android.app.Application.onCreate]. Subsequent calls are safe and
     * update the configuration then re-register the device.
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
     */
    @JvmStatic
    @JvmOverloads
    fun initializeWithConfig(context: Context, config: VisionPushConfig, initialTags: Map<String, String> = emptyMap()) {
        val appContext = context.applicationContext
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

        val apiClient: ApiClient
        val registrar: DeviceRegistrar
        val notificationBuilder: NotificationBuilder

        synchronized(VisionPush) {
            // Guard against concurrent initialize() calls from multiple threads.
            if (initialized) {
                VisionPushLogger.d(TAG, "Already initialized – skipping duplicate call.")
                return
            }
            apiClient = ApiClient(config)
            registrar = DeviceRegistrar(appContext, config, apiClient)
            notificationBuilder = NotificationBuilder(appContext)

            internalApiClient = apiClient
            internalDeviceRegistrar = registrar
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
                    // Fetch and trigger in-app messages for this session.
                    val iamResult = internalApiClient?.fetchInAppMessages(config.appId, deviceId)
                    if (iamResult is VisionPushResult.Success) {
                        withContext(Dispatchers.Main) {
                            internalIamController?.ingest(iamResult.data)
                            internalIamController?.triggerSession()
                        }
                    }
                }
                is VisionPushResult.Failure -> {
                    VisionPushLogger.w(
                        TAG,
                        "Initial registration failed: ${regResult.error.message}. " +
                                "WorkManager will retry when network is available."
                    )
                    withContext(Dispatchers.Main) { errorHandler?.invoke(regResult.error) }
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

    // ── User identification ─────────────────────────────────────────────────

    /**
     * Associates the VisionPush device record with an external user identifier
     * from your own system (e.g. your database user ID or CRM identifier).
     *
     * Setting the same value again is idempotent. Pass `null` to clear the
     * association (e.g. after logout; also consider calling [logout]).
     *
     * @param externalId Opaque string, or `null` to clear.
     */
    @JvmStatic
    fun setExternalId(externalId: String?) {
        if (externalId != null && externalId.length > 1_024) {
            throw VisionPushError.InvalidArgument("externalId must be 1 024 characters or fewer.")
        }
        checkInitialized()
        checkConsent()
        val registrar = internalDeviceRegistrar ?: return
        if (registrar.externalId == externalId) return

        registrar.externalId = externalId
        VisionPushLogger.i(TAG, "External ID updated: $externalId")

        sdkScope.launch { registrar.register(forceRefresh = true) }
    }

    /** The currently configured external user identifier, or `null` before it is set. */
    @get:JvmStatic
    val externalId: String? get() = internalDeviceRegistrar?.externalId

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
     * Merges [tags] into the device record on the VisionPush backend.
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
     * Removes specific tag keys from this device on the VisionPush backend.
     *
     * Keys not present on the device are silently ignored. Maximum 100 keys,
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
            withContext(Dispatchers.Main) { callback?.invoke(result) }
        }
    }

    /**
     * Removes all device registration data from the VisionPush backend and
     * clears all locally stored state. Call during user logout.
     *
     * After calling [logout], call [initialize] or [syncDevice] to resume
     * push delivery for a new user.
     *
     * @param callback Optional result callback invoked on the main thread.
     */
    @JvmStatic
    @JvmOverloads
    fun logout(callback: ((VisionPushResult<Unit>) -> Unit)? = null) {
        checkInitialized()
        sdkScope.launch {
            internalNotificationHandler = null
            internalNotificationOpenCallback = null
            val result = internalDeviceRegistrar?.unregister()
                ?: VisionPushResult.Failure(VisionPushError.NotInitialized())
            cachedTags = emptyMap()
            cachedTopics = emptySet()
            VisionPushLogger.i(TAG, "User logged out; device unregistered.")
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
     * When [given] is `false`, the consent flag is cleared so future backend
     * calls will fail with [VisionPushError.ConsentRequired].
     *
     * @param given `true` to grant consent, `false` to revoke it.
     */
    @JvmStatic
    fun setConsentGiven(given: Boolean) {
        val ctx = applicationContext ?: return
        persistConsent(ctx, given)
        VisionPushLogger.i(TAG, "Privacy consent ${if (given) "granted" else "revoked"}.")

        if (!given) return

        // Trigger backend registration now that consent has been granted.
        sdkScope.launch {
            internalDeviceRegistrar?.register(forceRefresh = true)
                ?.onSuccess { id -> VisionPushLogger.i(TAG, "Device registered after consent. deviceId=$id") }
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
     * Schedules a background device registration sync via WorkManager.
     * Called when an immediate registration attempt fails due to no network.
     */
    internal fun scheduleRegistrationSync(context: Context) {
        RegistrationSyncWorker.enqueue(context)
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
