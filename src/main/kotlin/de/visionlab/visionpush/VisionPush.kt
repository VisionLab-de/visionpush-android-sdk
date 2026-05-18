package de.visionlab.visionpush

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
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

    /** Handler invoked when a notification arrives (set via [setNotificationHandler]). */
    @Volatile internal var internalNotificationHandler: ((VisionPushNotification) -> Unit)? = null

    /** Handler invoked when the user opens a notification (set via [setNotificationOpenHandler]). */
    @Volatile internal var internalNotificationOpenCallback: ((VisionPushNotification) -> Unit)? = null

    /** SDK-wide coroutine scope; survives the entire application lifetime. */
    private val sdkScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * In-memory LRU-like cache of recently received notifications keyed by ID.
     * Allows [NotificationTracker.OpenReceiver] to resolve the full model on tap.
     */
    private val notificationCache: MutableMap<String, VisionPushNotification> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, VisionPushNotification>(CACHE_MAX_SIZE, 0.75f, true) {
                override fun removeEldestEntry(eldest: Map.Entry<String, VisionPushNotification>) =
                    size > CACHE_MAX_SIZE
            }
        )

    @Volatile private var isInitialized = false

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
        apiBaseUrl: String = BuildConfig.VISIONPUSH_DEFAULT_API_BASE_URL
    ) {
        require(appId.isNotBlank()) { "appId must not be blank." }
        require(apiKey.isNotBlank()) { "apiKey must not be blank." }

        val config = VisionPushConfig(
            appId = appId,
            apiKey = apiKey,
            apiBaseUrl = apiBaseUrl,
            logLevel = logLevel
        )

        initializeWithConfig(context, config)
    }

    /**
     * Initializes the VisionPush SDK with a pre-built [VisionPushConfig].
     *
     * Use this overload when you need full control over all configuration
     * options. Under the hood [initialize] delegates to this method.
     */
    @JvmStatic
    fun initializeWithConfig(context: Context, config: VisionPushConfig) {
        val appContext = context.applicationContext

        VisionPushLogger.minLevel = config.logLevel
        VisionPushLogger.i(TAG, "Initializing VisionPush SDK v$SDK_VERSION appId=${config.appId}")

        if (!config.apiKey.startsWith("vp_live_") && !config.apiKey.startsWith("vp_test_")) {
            VisionPushLogger.w(
                TAG,
                "API key does not start with 'vp_live_' or 'vp_test_'. " +
                        "Ensure you are using a valid VisionPush API key."
            )
        }

        val apiClient = ApiClient(config)
        val registrar = DeviceRegistrar(appContext, config, apiClient)
        val notificationBuilder = NotificationBuilder(appContext)

        internalApiClient = apiClient
        internalDeviceRegistrar = registrar
        internalNotificationBuilder = notificationBuilder

        // Ensure the default notification channel is always present (API 26+)
        notificationBuilder.ensureDefaultChannelExists()

        isInitialized = true

        // Initial device registration – failures schedule a WorkManager retry
        sdkScope.launch {
            registrar.register()
                .onSuccess { deviceId ->
                    VisionPushLogger.i(TAG, "SDK initialized. deviceId=$deviceId")
                }
                .onFailure { error ->
                    VisionPushLogger.w(
                        TAG,
                        "Initial registration failed: ${error.message}. " +
                                "WorkManager will retry when network is available."
                    )
                    scheduleRegistrationSync(appContext)
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
        checkInitialized()
        val registrar = internalDeviceRegistrar ?: return
        if (registrar.externalId == externalId) return

        registrar.externalId = externalId
        VisionPushLogger.i(TAG, "External ID updated: $externalId")

        sdkScope.launch { registrar.register(forceRefresh = true) }
    }

    /** Returns the currently configured external user identifier, or `null`. */
    @JvmStatic
    fun getExternalId(): String? = internalDeviceRegistrar?.externalId

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
    fun setNotificationOpenHandler(handler: ((VisionPushNotification) -> Unit)?) {
        internalNotificationOpenCallback = handler
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
        sdkScope.launch {
            val result = internalDeviceRegistrar?.setOptedOut(false)
                ?: VisionPushResult.Failure(VisionPushError.NotInitialized())
            VisionPushLogger.i(TAG, "User opted in.")
            withContext(Dispatchers.Main) { callback?.invoke(result) }
        }
    }

    /** Returns `true` when the user has explicitly opted out of notifications. */
    @JvmStatic
    fun isOptedOut(): Boolean = internalDeviceRegistrar?.isOptedOut ?: false

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
            VisionPushLogger.i(TAG, "User logged out; device unregistered.")
            withContext(Dispatchers.Main) { callback?.invoke(result) }
        }
    }

    // ── Diagnostics ─────────────────────────────────────────────────────────

    /** Returns the server-assigned device ID, or `null` before first registration. */
    @JvmStatic
    fun getDeviceId(): String? = internalDeviceRegistrar?.deviceId

    /** Returns `true` when [initialize] has been called successfully. */
    @JvmStatic
    fun isInitialized(): Boolean = isInitialized

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

    // ── Precondition guard ───────────────────────────────────────────────────

    private fun checkInitialized() {
        if (!isInitialized) throw VisionPushError.NotInitialized()
    }

    // ── Constants ────────────────────────────────────────────────────────────

    private const val TAG = "VisionPush"

    /**
     * Arbitrary SDK-owned request code for [Activity.requestPermissions].
     * Chosen to be unlikely to collide with host-app request codes.
     */
    private const val PERMISSION_REQUEST_CODE = 0x5050

    /** Maximum number of notifications kept in the in-memory open-tracking cache. */
    private const val CACHE_MAX_SIZE = 50
}
