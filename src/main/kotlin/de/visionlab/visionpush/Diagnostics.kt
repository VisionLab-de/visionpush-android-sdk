package de.visionlab.visionpush

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * A snapshot of the VisionPush SDK's current state, intended for support and
 * diagnostics workflows.
 *
 * Obtain an instance via [VisionPush.runDiagnostics].
 *
 * @property sdkVersion                The VisionPush SDK version string.
 * @property isInitialized             Whether [VisionPush.initialize] has been called.
 * @property deviceId                  The server-assigned device ID, or `null` before registration.
 * @property pushPermissionStatus      "granted", "denied", or "unknown".
 * @property hasConsent                Whether the SDK is allowed to send data to the backend.
 * @property consentRequired           Whether [VisionPushConfig.consentRequired] is enabled.
 * @property apiKeyPrefix              The first 12 characters of the configured API key.
 * @property apiBaseUrl                The API base URL from the active configuration.
 * @property networkReachable          Whether a HEAD request to the API base URL succeeded within 3 s.
 * @property subscribedTopics          Sorted list of notification topics this device is subscribed to.
 * @property cachedTagCount            Number of tag key-value pairs in the local cache.
 * @property notificationHistoryCount  Number of entries in the persisted notification history.
 * @property iamMessagesQueued         Number of in-app messages queued (always 0 for now).
 * @property timestamp                 Wall-clock time at which the report was created (epoch ms).
 */
data class DiagnosticReport(
    val sdkVersion: String,
    val isInitialized: Boolean,
    val deviceId: String?,
    val pushPermissionStatus: String,
    val hasConsent: Boolean,
    val consentRequired: Boolean,
    val apiKeyPrefix: String,
    val apiBaseUrl: String,
    val networkReachable: Boolean,
    val subscribedTopics: List<String>,
    val cachedTagCount: Int,
    val notificationHistoryCount: Int,
    val iamMessagesQueued: Int,
    val timestamp: Long
) {
    /**
     * Returns a human-readable multi-line summary of all diagnostic fields.
     * Suitable for attaching to a support ticket or writing to a log file.
     */
    fun summary(): String = buildString {
        appendLine("=== VisionPush Diagnostic Report ===")
        appendLine("timestamp              : $timestamp")
        appendLine("sdkVersion             : $sdkVersion")
        appendLine("isInitialized          : $isInitialized")
        appendLine("deviceId               : ${deviceId ?: "<not registered>"}")
        appendLine("pushPermissionStatus   : $pushPermissionStatus")
        appendLine("hasConsent             : $hasConsent")
        appendLine("consentRequired        : $consentRequired")
        appendLine("apiKeyPrefix           : $apiKeyPrefix")
        appendLine("apiBaseUrl             : $apiBaseUrl")
        appendLine("networkReachable       : $networkReachable")
        appendLine("subscribedTopics       : $subscribedTopics")
        appendLine("cachedTagCount         : $cachedTagCount")
        appendLine("notificationHistoryCount: $notificationHistoryCount")
        appendLine("iamMessagesQueued      : $iamMessagesQueued")
        append("=====================================")
    }
}

/**
 * Builds a [DiagnosticReport] for the current SDK state.
 *
 * Must be called from a coroutine; network I/O is dispatched to [Dispatchers.IO].
 * This is an internal top-level function so it can be called from the
 * [VisionPush] object without requiring a companion-object extension.
 */
internal suspend fun buildDiagnosticReport(context: Context): DiagnosticReport {
    val config = VisionPush.activeConfig

    // ── Push permission ──────────────────────────────────────────────────────
    val notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
    val pushPermissionStatus = when {
        notificationsEnabled -> "granted"
        else -> "denied"
    }

    // ── Network reachability: HEAD request to API base URL with 3 s timeout ──
    val networkReachable = withContext(Dispatchers.IO) {
        try {
            val client = VisionPush.internalApiClient?.httpClient
                ?: OkHttpClient.Builder()
                    .connectTimeout(3, TimeUnit.SECONDS)
                    .readTimeout(3, TimeUnit.SECONDS)
                    .build()

            val url = config?.apiBaseUrl ?: "https://api.visionpush.de"
            val request = Request.Builder()
                .url(url)
                .head()
                .build()

            client.newCall(request).execute().use { response ->
                response.code < 500
            }
        } catch (_: Exception) {
            false
        }
    }

    // ── API key prefix (first 12 chars only) ─────────────────────────────────
    val apiKeyPrefix = config?.apiKey?.take(12) ?: "<not set>"

    return DiagnosticReport(
        sdkVersion = VisionPush.SDK_VERSION,
        isInitialized = VisionPush.isInitialized,
        deviceId = VisionPush.deviceId,
        pushPermissionStatus = pushPermissionStatus,
        hasConsent = VisionPush.hasConsent,
        consentRequired = config?.consentRequired ?: false,
        apiKeyPrefix = apiKeyPrefix,
        apiBaseUrl = config?.apiBaseUrl ?: "<not set>",
        networkReachable = networkReachable,
        subscribedTopics = VisionPush.subscribedTopics,
        cachedTagCount = VisionPush.tags.size,
        notificationHistoryCount = VisionPush.getNotificationHistory().size,
        iamMessagesQueued = 0,
        timestamp = System.currentTimeMillis()
    )
}
