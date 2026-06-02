package de.visionlab.visionpush

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * VisionPush data models.
 *
 * All models are plain Kotlin data classes or sealed hierarchies. They implement
 * [JsonSerializable] so that the [ApiClient] can serialise them to JSON without
 * a heavyweight reflection-based library.
 */

/** Returns the current UTC time as an ISO 8601 string, compatible with API 21+. */
private fun nowIso8601(): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    return sdf.format(Date())
}

// ── Serialization contract ─────────────────────────────────────────────────────

/** Implemented by every model that needs to be sent to the VisionPush API. */
internal interface JsonSerializable {
    fun toJson(): JSONObject
}

// ── SDK configuration ──────────────────────────────────────────────────────────

/**
 * Immutable configuration supplied to [VisionPush.initialize].
 *
 * @property appId            UUID that identifies the VisionPush application.
 * @property apiKey           Secret API key with the prefix `vp_live_` or `vp_test_`.
 * @property apiBaseUrl       Base URL of the VisionPush REST API (overridable for testing).
 * @property logLevel         Controls SDK console output verbosity.
 * @property consentRequired  When `true`, no device data is sent to the VisionPush backend
 *                            until [VisionPush.setConsentGiven] is called with `true`.
 *                            Set this for apps that must comply with GDPR / CCPA.
 */
data class VisionPushConfig(
    val appId: String,
    val apiKey: String,
    val apiBaseUrl: String = BuildConfig.VISIONPUSH_DEFAULT_API_BASE_URL,
    val logLevel: LogLevel = LogLevel.WARN,
    val consentRequired: Boolean = false
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

// ── User Profile ───────────────────────────────────────────────────────────────

/**
 * Rich user profile fields sent to the VisionPush backend for personalisation.
 *
 * Values are merged server-side — only non-null fields update the stored profile.
 *
 * @property firstName   Given name (e.g. "Jana").
 * @property lastName    Family name (e.g. "Müller").
 * @property email       Email address for cross-channel targeting.
 * @property phone       Phone number in E.164 format (e.g. "+4915123456789").
 * @property gender      One of: "male", "female", "other", "prefer_not_to_say".
 * @property birthDate   Date of birth in ISO 8601 format ("YYYY-MM-DD").
 */
data class UserProfile(
    val firstName: String? = null,
    val lastName: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val gender: String? = null,
    val birthDate: String? = null
) : JsonSerializable {
    override fun toJson(): JSONObject = JSONObject().apply {
        firstName?.let { put("first_name", it) }
        lastName?.let  { put("last_name", it)  }
        email?.let     { put("email", it)       }
        phone?.let     { put("phone", it)       }
        gender?.let    { put("gender", it)      }
        birthDate?.let { put("birth_date", it)  }
    }
}

// ── Notification History ───────────────────────────────────────────────────────

/**
 * A lightweight record of a received VisionPush notification, stored locally
 * in [SharedPreferences] for use in inbox-style UIs.
 */
data class VisionPushNotificationRecord(
    /** The VisionPush notification identifier. */
    val id: String,
    /** The notification title. */
    val title: String?,
    /** The notification body. */
    val body: String?,
    /** The campaign ID for analytics attribution. */
    val campaignId: String?,
    /** Deep-link URI included in the notification payload. */
    val deepLink: String?,
    /** ISO 8601 UTC timestamp when the notification was received. */
    val receivedAt: String,
    /** Whether the user tapped the notification to open the app. */
    var wasOpened: Boolean = false
) {
    companion object {
        fun fromJson(json: JSONObject) = VisionPushNotificationRecord(
            id         = json.getString("id"),
            title      = json.optString("title").takeIf { it.isNotBlank() },
            body       = json.optString("body").takeIf { it.isNotBlank() },
            campaignId = json.optString("campaign_id").takeIf { it.isNotBlank() },
            deepLink   = json.optString("deep_link").takeIf { it.isNotBlank() },
            receivedAt = json.optString("received_at", nowIso8601()),
            wasOpened  = json.optBoolean("was_opened", false)
        )

        fun toJson(record: VisionPushNotificationRecord) = JSONObject().apply {
            put("id", record.id)
            record.title?.let { put("title", it) }
            record.body?.let { put("body", it) }
            record.campaignId?.let { put("campaign_id", it) }
            record.deepLink?.let { put("deep_link", it) }
            put("received_at", record.receivedAt)
            put("was_opened", record.wasOpened)
        }
    }
}

// ── Device / registration ──────────────────────────────────────────────────────

/**
 * Snapshot of the device state sent to the VisionPush API during registration
 * or whenever a meaningful attribute changes (token rotation, opt-out, etc.).
 */
internal data class DeviceRegistration(
    /** UUID identifying the VisionPush application. */
    val appId: String,
    /** Current FCM registration token. */
    val pushToken: String,
    /** Caller-supplied external user identifier (nullable; cleared on logout). */
    val externalId: String?,
    /** SDK-generated stable UUID (generated on first launch, persisted in EncryptedSharedPreferences).
     *  Primary device identity key — token rotation updates this row, never creates a duplicate. */
    val installationId: String,
    /** Stable anonymous device identifier. Kept for backwards compatibility. */
    val anonymousId: String?,
    /** Locale/language tag in BCP 47 format, e.g. "de-DE". */
    val locale: String,
    /** ISO 3166-1 alpha-2 country code from the device locale, e.g. "DE". */
    val country: String?,
    /** Human-readable OS version string, e.g. "Android 13". */
    val deviceOs: String,
    /** Device model string, e.g. "Google Pixel 7 Pro". */
    val deviceModel: String,
    /** Version name of the host application. */
    val appVersion: String,
    /** VisionPush SDK version string. */
    val sdkBuildVersion: String = BuildConfig.VISIONPUSH_SDK_VERSION,
    /** IANA timezone identifier, e.g. "Europe/Berlin". */
    val timezone: String? = null,
    /** Timezone offset from UTC in minutes (e.g. 60 for CET+1). */
    val timezoneOffset: Int = 0,
    /** "production" for vp_live_ keys, "sandbox" for vp_test_ keys. */
    val environment: String = "production",
    /** App bundle/package identifier, e.g. "com.example.app". */
    val bundleId: String? = null,
    /** App build number (versionCode), e.g. "42". */
    val appBuildNumber: String? = null,
    /** Device category: "phone" | "tablet" | "desktop" | "tv" | "unknown". */
    val deviceType: String = "phone",
    /** Hardware manufacturer, e.g. "Samsung", "Google". */
    val deviceManufacturer: String = android.os.Build.MANUFACTURER,
    /** Current network type: "wifi" | "cellular" | "other" | "none". */
    val networkType: String? = null,
    /** System notification permission: "granted" | "denied" | "unknown". */
    val notificationPermission: String? = null,
    /** Arbitrary key-value tags set by the integrating app (e.g. plan tier, feature flags). */
    val tags: Map<String, String>? = null
) : JsonSerializable {
    override fun toJson(): JSONObject = JSONObject().apply {
        put("app_id", appId)
        put("platform", "android")
        put("push_token", pushToken)
        put("environment", environment)
        externalId?.let { put("external_id", it) }
        put("installation_id", installationId)
        anonymousId?.let { put("anonymous_id", it) }
        put("device_model", deviceModel)
        put("device_os", deviceOs)
        put("app_version", appVersion)
        put("sdk_version", sdkBuildVersion)
        put("language", locale)
        country?.let { put("country", it) }
        timezone?.let { put("timezone", it) }
        put("timezone_offset", timezoneOffset)
        bundleId?.let { put("bundle_id", it) }
        appBuildNumber?.let { put("app_build_number", it) }
        put("device_type", deviceType)
        put("device_manufacturer", deviceManufacturer)
        networkType?.let { put("network_type", it) }
        notificationPermission?.let { put("notification_permission", it) }
        tags?.takeIf { it.isNotEmpty() }?.let {
            put("tags", JSONObject().apply { it.forEach { (k, v) -> put(k, v) } })
        }
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
    /** Campaign ID for open/click analytics tracking. */
    val campaignId: String? = null,
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
    val showSystemNotification: Boolean = true,
    /**
     * Optional group key for Android notification grouping.
     * Notifications with the same key are collapsed into a summary in the drawer.
     */
    val groupKey: String? = null
) {
    companion object {
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
                for (i in 0 until minOf(arr.length(), 3)) {
                    NotificationAction.fromJson(arr.getJSONObject(i))?.let { actions.add(it) }
                }
            }

            val customData = mutableMapOf<String, String>()
            json.optJSONObject("custom_data")?.let { obj ->
                obj.keys().forEach { key -> customData[key] = obj.optString(key) }
            }

            return VisionPushNotification(
                id = json.getString("id").take(512),
                campaignId = json.optString("campaign_id").takeIf { it.isNotBlank() }?.take(512),
                title = json.optString("title", "").take(256),
                body = json.optString("body", "").take(4096),
                imageUrl = json.optString("image_url").takeIf { it.isNotBlank() }?.let { safeHttpUrl(it) },
                smallIconName = json.optString("small_icon_name").takeIf { it.isNotBlank() }?.take(128),
                largeIconUrl = json.optString("large_icon_url").takeIf { it.isNotBlank() }?.let { safeHttpUrl(it) },
                deepLink = json.optString("deep_link").takeIf { it.isNotBlank() }?.let { safeDeepLink(it) },
                channelId = json.optString("channel_id", VisionPushChannel.DEFAULT_CHANNEL_ID).take(128),
                actions = actions.take(3),
                customData = customData,
                sentAt = json.optString("sent_at").takeIf { it.isNotBlank() }?.take(64),
                showSystemNotification = json.optBoolean("show_system_notification", true),
                groupKey = json.optString("group_key").takeIf { it.isNotBlank() }?.take(128)
            )
        }

        /** Returns the URL string only if its scheme is http or https. */
        private fun safeHttpUrl(url: String): String? {
            val scheme = try { Uri.parse(url).scheme?.lowercase() } catch (_: Exception) { null }
            return if (scheme == "http" || scheme == "https") url else null
        }

        /** Returns the deep-link string unless its scheme is explicitly dangerous. */
        private fun safeDeepLink(url: String): String? {
            val blocked = setOf("javascript", "data", "vbscript", "content", "file")
            val scheme = try { Uri.parse(url).scheme?.lowercase() } catch (_: Exception) { return null }
            return if (scheme != null && !blocked.contains(scheme)) url else null
        }
    }
}

/**
 * A single tappable action button displayed beneath the notification body.
 *
 * @property id        Opaque identifier sent to the API when the user taps this action.
 * @property title     Button text shown in the notification shade.
 * @property deepLink  Optional deep-link override specific to this action.
 * @property iconName  Optional drawable resource name for an action icon.
 */
data class NotificationAction(
    val id: String,
    val title: String,
    val deepLink: String? = null,
    val iconName: String? = null
) {
    internal companion object {
        fun fromJson(json: JSONObject): NotificationAction? {
            val id = json.optString("action_id").takeIf { it.isNotBlank() } ?: return null
            val title = json.optString("title").takeIf { it.isNotBlank() } ?: return null
            return NotificationAction(
                id = id,
                title = title,
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
    /** Campaign ID extracted from the `vp_campaign_id` FCM data field. */
    val campaignId: String? = null,
    /** Device ID from local SDK storage — enables per-device analytics. */
    val deviceId: String? = null,
    /** ISO 8601 UTC timestamp of when the event occurred. */
    val occurredAt: String = nowIso8601()
) : JsonSerializable {

    enum class EventType(val apiKey: String) {
        DELIVERED("delivered"),
        OPENED("opened"),
        ACTION_TAPPED("action_tapped"),
        DISMISSED("dismissed")
    }

    override fun toJson(): JSONObject = JSONObject().apply {
        put("notification_id", notificationId)
        put("event_type", eventType.apiKey)
        put("action_id", actionId ?: JSONObject.NULL)
        put("occurred_at", occurredAt)
        put("platform", "android")
        campaignId?.let { put("campaign_id", it) }
        deviceId?.let { put("device_id", it) }
    }
}

// ── API responses ───────────────────────────────────────────────────────────────

/** Minimal envelope returned by the VisionPush API for write operations. */
data class ApiResponse(
    val success: Boolean,
    val message: String?,
    val deviceId: String?
) {
    /** The raw `data` object from the JSON response (when data is a JSON object). */
    var rawData: JSONObject? = null
    /** The raw `data` array from the JSON response (when data is a JSON array). */
    var rawDataArray: org.json.JSONArray? = null
    /** The full parsed response object — for endpoints that return top-level keys
     *  (e.g. {"geofences": [...]}) rather than a `data` wrapper. */
    var rawBody: JSONObject? = null

    companion object {
        fun fromJson(json: JSONObject): ApiResponse = ApiResponse(
            success = json.optBoolean("success", false),
            message = json.optString("message").takeIf { it.isNotBlank() },
            deviceId = json.optString("device_id").takeIf { it.isNotBlank() }
        ).also {
            it.rawData = json.optJSONObject("data")
            it.rawDataArray = json.optJSONArray("data")
            it.rawBody = json
        }
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
    const val KEY_CAMPAIGN_ID = "campaign_id"
    const val KEY_FCM_TOKEN = "fcm_token"
    const val KEY_RETRY_COUNT = "retry_count"
}
