package de.visionlab.visionpush

import org.json.JSONArray
import org.json.JSONObject

/**
 * VisionPush data models.
 *
 * All models are plain Kotlin data classes or sealed hierarchies. They implement
 * [JsonSerializable] so that the [ApiClient] can serialise them to JSON without
 * a heavyweight reflection-based library.
 */

// ── Serialization contract ─────────────────────────────────────────────────────

/** Implemented by every model that needs to be sent to the VisionPush API. */
internal interface JsonSerializable {
    fun toJson(): JSONObject
}

// ── SDK configuration ──────────────────────────────────────────────────────────

/**
 * Immutable configuration supplied to [VisionPush.initialize].
 *
 * @property appId       UUID that identifies the VisionPush application.
 * @property apiKey      Secret API key with the prefix `vp_live_` or `vp_test_`.
 * @property apiBaseUrl  Base URL of the VisionPush REST API (overridable for testing).
 * @property logLevel    Controls SDK console output verbosity.
 */
data class VisionPushConfig(
    val appId: String,
    val apiKey: String,
    val apiBaseUrl: String = BuildConfig.VISIONPUSH_DEFAULT_API_BASE_URL,
    val logLevel: LogLevel = LogLevel.WARN
) {
    /** Supported log-verbosity levels, ordered from least to most verbose. */
    enum class LogLevel { NONE, ERROR, WARN, INFO, DEBUG, VERBOSE }

    internal companion object {
        /** Key used to persist the config across process restarts. */
        const val PREFS_KEY_APP_ID = "vp_app_id"
        const val PREFS_KEY_API_KEY = "vp_api_key"
        const val PREFS_KEY_API_BASE_URL = "vp_api_base_url"
    }
}

// ── Device / registration ──────────────────────────────────────────────────────

/**
 * Snapshot of the device state sent to the VisionPush API during registration
 * or whenever a meaningful attribute changes (token rotation, opt-out, etc.).
 */
internal data class DeviceRegistration(
    /** Current FCM registration token. */
    val fcmToken: String,
    /** Caller-supplied external user identifier (nullable; cleared on logout). */
    val externalId: String?,
    /** Locale string in BCP 47 format, e.g. "de-DE". */
    val locale: String,
    /** Android SDK level, e.g. 33. */
    val sdkVersion: Int,
    /** Device model string, e.g. "Pixel 7 Pro". */
    val deviceModel: String,
    /** Package name of the host application. */
    val appPackageName: String,
    /** Version name of the host application. */
    val appVersion: String,
    /** VisionPush SDK version string. */
    val sdkBuildVersion: String = BuildConfig.VISIONPUSH_SDK_VERSION,
    /** Whether the user has granted notification permission. */
    val notificationsEnabled: Boolean,
    /** Whether the user has explicitly opted out via [VisionPush.optOut]. */
    val optedOut: Boolean
) : JsonSerializable {
    override fun toJson(): JSONObject = JSONObject().apply {
        put("fcm_token", fcmToken)
        put("external_id", externalId ?: JSONObject.NULL)
        put("locale", locale)
        put("os_sdk_version", sdkVersion)
        put("device_model", deviceModel)
        put("app_package_name", appPackageName)
        put("app_version", appVersion)
        put("sdk_version", sdkBuildVersion)
        put("notifications_enabled", notificationsEnabled)
        put("opted_out", optedOut)
        put("platform", "android")
    }
}

// ── Notifications ──────────────────────────────────────────────────────────────

/**
 * Rich notification payload delivered via FCM data messages.
 *
 * All fields except [id] and [title] are optional so that the SDK degrades
 * gracefully when the server sends a minimal payload.
 */
data class VisionPushNotification(
    /** Unique server-assigned notification identifier (UUID). */
    val id: String,
    /** Primary title line displayed in bold. */
    val title: String,
    /** Body text shown below the title. */
    val body: String = "",
    /** URL of a large image loaded asynchronously and displayed in expanded state. */
    val imageUrl: String? = null,
    /** URL of a small icon resource name (must be a drawable resource in the host app). */
    val smallIconName: String? = null,
    /** URL of a large round icon displayed next to the notification. */
    val largeIconUrl: String? = null,
    /** Deep-link URI opened when the user taps the notification. */
    val deepLink: String? = null,
    /** Notification channel identifier (must be registered before delivery). */
    val channelId: String = VisionPushChannel.DEFAULT_CHANNEL_ID,
    /** Action buttons displayed beneath the notification body. */
    val actions: List<NotificationAction> = emptyList(),
    /** Arbitrary key/value pairs forwarded to the notification-open handler. */
    val customData: Map<String, String> = emptyMap(),
    /** ISO 8601 timestamp set by the server. */
    val sentAt: String? = null,
    /** Whether the SDK should bypass the custom handler and show a system notification. */
    val showSystemNotification: Boolean = true
) {
    internal companion object {
        /** FCM data-message key that carries the VisionPush JSON payload. */
        const val FCM_DATA_KEY = "vp_payload"

        /**
         * Parses a [VisionPushNotification] from the FCM data map.
         *
         * Returns `null` when [FCM_DATA_KEY] is absent or the JSON is malformed.
         */
        fun fromFcmData(data: Map<String, String>): VisionPushNotification? {
            val raw = data[FCM_DATA_KEY] ?: return null
            return try {
                fromJson(JSONObject(raw))
            } catch (_: Exception) {
                null
            }
        }

        /** Parses a [VisionPushNotification] from a [JSONObject]. */
        fun fromJson(json: JSONObject): VisionPushNotification {
            val actions = mutableListOf<NotificationAction>()
            json.optJSONArray("actions")?.let { arr ->
                for (i in 0 until arr.length()) {
                    NotificationAction.fromJson(arr.getJSONObject(i))?.let { actions.add(it) }
                }
            }

            val customData = mutableMapOf<String, String>()
            json.optJSONObject("custom_data")?.let { obj ->
                obj.keys().forEach { key -> customData[key] = obj.optString(key) }
            }

            return VisionPushNotification(
                id = json.getString("id"),
                title = json.optString("title", ""),
                body = json.optString("body", ""),
                imageUrl = json.optString("image_url").takeIf { it.isNotBlank() },
                smallIconName = json.optString("small_icon_name").takeIf { it.isNotBlank() },
                largeIconUrl = json.optString("large_icon_url").takeIf { it.isNotBlank() },
                deepLink = json.optString("deep_link").takeIf { it.isNotBlank() },
                channelId = json.optString("channel_id", VisionPushChannel.DEFAULT_CHANNEL_ID),
                actions = actions,
                customData = customData,
                sentAt = json.optString("sent_at").takeIf { it.isNotBlank() },
                showSystemNotification = json.optBoolean("show_system_notification", true)
            )
        }
    }
}

/**
 * A single tappable action button displayed beneath the notification body.
 *
 * @property actionId  Opaque identifier sent to the API when the user taps this action.
 * @property label     Button text shown in the notification shade.
 * @property deepLink  Optional deep-link override specific to this action.
 * @property iconName  Optional drawable resource name for an action icon.
 */
data class NotificationAction(
    val actionId: String,
    val label: String,
    val deepLink: String? = null,
    val iconName: String? = null
) {
    internal companion object {
        fun fromJson(json: JSONObject): NotificationAction? {
            val actionId = json.optString("action_id").takeIf { it.isNotBlank() } ?: return null
            val label = json.optString("label").takeIf { it.isNotBlank() } ?: return null
            return NotificationAction(
                actionId = actionId,
                label = label,
                deepLink = json.optString("deep_link").takeIf { it.isNotBlank() },
                iconName = json.optString("icon_name").takeIf { it.isNotBlank() }
            )
        }
    }
}

// ── Notification channels ──────────────────────────────────────────────────────

/**
 * Describes a notification channel that the SDK should ensure exists on the device.
 *
 * Channels must be registered on API 26+ before a notification can be posted to them.
 *
 * @property channelId   Unique identifier used in notification payloads.
 * @property name        Human-readable channel name shown in system settings.
 * @property description Optional description shown in system settings.
 * @property importance  NotificationManagerCompat importance constant.
 * @property enableVibration Whether vibration is enabled for this channel.
 * @property enableLights    Whether the notification LED is enabled.
 * @property lightColor      ARGB color for the notification LED.
 */
data class VisionPushChannel(
    val channelId: String,
    val name: String,
    val description: String = "",
    val importance: Int = android.app.NotificationManager.IMPORTANCE_DEFAULT,
    val enableVibration: Boolean = true,
    val enableLights: Boolean = true,
    val lightColor: Int = android.graphics.Color.BLUE
) {
    companion object {
        /** Channel ID used when no channel is specified in the notification payload. */
        const val DEFAULT_CHANNEL_ID = "visionpush_default"
        const val DEFAULT_CHANNEL_NAME = "Notifications"
    }
}

// ── Event tracking ──────────────────────────────────────────────────────────────

/**
 * Represents a tracked notification event (open, action tap, dismiss).
 */
internal data class NotificationEvent(
    val notificationId: String,
    val eventType: EventType,
    /** Present when [eventType] is [EventType.ACTION_TAPPED]. */
    val actionId: String? = null,
    val occurredAtMs: Long = System.currentTimeMillis()
) : JsonSerializable {

    enum class EventType(val apiKey: String) {
        OPENED("opened"),
        ACTION_TAPPED("action_tapped"),
        DISMISSED("dismissed")
    }

    override fun toJson(): JSONObject = JSONObject().apply {
        put("notification_id", notificationId)
        put("event_type", eventType.apiKey)
        put("action_id", actionId ?: JSONObject.NULL)
        put("occurred_at_ms", occurredAtMs)
        put("platform", "android")
    }
}

// ── API responses ───────────────────────────────────────────────────────────────

/** Minimal envelope returned by the VisionPush API for write operations. */
internal data class ApiResponse(
    val success: Boolean,
    val message: String?,
    val deviceId: String?
) {
    internal companion object {
        fun fromJson(json: JSONObject): ApiResponse = ApiResponse(
            success = json.optBoolean("success", false),
            message = json.optString("message").takeIf { it.isNotBlank() },
            deviceId = json.optString("device_id").takeIf { it.isNotBlank() }
        )
    }
}

// ── WorkManager input data keys ─────────────────────────────────────────────────

/**
 * Typed constants for WorkManager [Data] keys used by SDK background workers.
 *
 * Centralising these strings here eliminates accidental typo-based mismatches
 * between worker enqueue sites and worker implementations.
 */
internal object WorkerKeys {
    const val KEY_NOTIFICATION_ID = "notification_id"
    const val KEY_EVENT_TYPE = "event_type"
    const val KEY_ACTION_ID = "action_id"
    const val KEY_FCM_TOKEN = "fcm_token"
    const val KEY_RETRY_COUNT = "retry_count"
}
