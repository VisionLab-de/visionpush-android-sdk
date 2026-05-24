package de.visionlab.visionpush

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Low-level HTTP client that wraps OkHttp and handles all communication with
 * the VisionPush REST API.
 *
 * Callers should prefer [VisionPush] or [DeviceRegistrar] rather than using
 * [ApiClient] directly. All suspending functions are safe to call from any
 * coroutine context; they dispatch to [Dispatchers.IO] internally.
 *
 * Authentication is supplied via the `Authorization: Bearer <apiKey>` header using
 * the key provided during [VisionPush.initialize].
 */
internal class ApiClient(
    private val config: VisionPushConfig
) {

    // ── HTTP client ─────────────────────────────────────────────────────────

    internal val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .addInterceptor(buildAuthInterceptor())
        .addInterceptor(buildLoggingInterceptor())
        .build()

    // ── Public API endpoints ────────────────────────────────────────────────

    /**
     * Registers or updates the device on the VisionPush backend.
     *
     * On first call this creates a new device record; on subsequent calls it
     * upserts based on the FCM token. Returns the server-assigned device ID on
     * success.
     *
     * @throws VisionPushError.NetworkError if the network request fails.
     * @throws VisionPushError.ApiError     if the server returns a non-2xx response.
     */
    suspend fun registerDevice(registration: DeviceRegistration): VisionPushResult<String> =
        post(
            path = "/devices/register",
            body = registration.toJson()
        ).map { response ->
            response.deviceId ?: "unknown"
        }

    /**
     * Sends a single notification event (open, action tap, dismiss) to the API.
     *
     * Network failures are returned as [VisionPushResult.Failure]; they do not
     * throw so that WorkManager can decide whether to retry.
     */
    suspend fun trackEvent(event: NotificationEvent): VisionPushResult<Unit> =
        post(
            path = "/events",
            body = event.toJson()
        ).map { /* discard response body */ }

    /**
     * Opts in the device identified by [deviceId] to push notifications.
     *
     * @param deviceId   Server-assigned device ID.
     */
    suspend fun optIn(deviceId: String): VisionPushResult<Unit> =
        post(path = "/devices/$deviceId/opt-in", body = JSONObject()).map { }

    /**
     * Opts out the device identified by [deviceId] from push notifications.
     *
     * @param deviceId   Server-assigned device ID.
     */
    suspend fun optOut(deviceId: String): VisionPushResult<Unit> =
        post(path = "/devices/$deviceId/opt-out", body = JSONObject()).map { }

    /**
     * Deactivates the device record identified by [deviceId].
     * Called when the user logs out or explicitly requests data deletion.
     */
    suspend fun deleteDevice(deviceId: String): VisionPushResult<Unit> =
        delete(path = "/devices/$deviceId", body = JSONObject()).map { }

    /**
     * Merges [tags] into the device record identified by [deviceId].
     * Sends a PUT to `/devices/{id}` with `{"tags": {...}}`.
     */
    suspend fun updateDeviceTags(deviceId: String, tags: Map<String, String>): VisionPushResult<Unit> {
        val body = JSONObject().apply {
            val tagsJson = JSONObject()
            tags.forEach { (k, v) -> tagsJson.put(k, v) }
            put("tags", tagsJson)
        }
        return put(path = "/devices/$deviceId", body = body).map { }
    }

    /**
     * Removes specific tag keys from the device record identified by [deviceId].
     * Sends a PUT to `/devices/{id}` with `{"tags_to_remove": [...]}`.
     */
    suspend fun removeDeviceTags(deviceId: String, keys: List<String>): VisionPushResult<Unit> {
        val body = JSONObject().apply {
            val arr = org.json.JSONArray()
            keys.forEach { arr.put(it) }
            put("tags_to_remove", arr)
        }
        return put(path = "/devices/$deviceId", body = body).map { }
    }

    /**
     * Updates the user profile fields for the device identified by [deviceId].
     * Sends a PUT to `/devices/{id}` with `{"user_profile": {...}}`.
     */
    suspend fun setUserProfile(deviceId: String, profile: UserProfile): VisionPushResult<Unit> {
        val body = JSONObject().apply { put("user_profile", profile.toJson()) }
        return put(path = "/devices/$deviceId", body = body).map { }
    }

    /**
     * Subscribes this device to [topics] by sending a PUT with `{"topics_subscribe": [...]}`.
     */
    suspend fun subscribeToTopics(deviceId: String, topics: List<String>): VisionPushResult<Unit> {
        val body = JSONObject().apply {
            val arr = org.json.JSONArray(); topics.forEach { arr.put(it) }
            put("topics_subscribe", arr)
        }
        return put(path = "/devices/$deviceId", body = body).map { }
    }

    /**
     * Unsubscribes this device from [topics] by sending a PUT with `{"topics_unsubscribe": [...]}`.
     */
    suspend fun unsubscribeFromTopics(deviceId: String, topics: List<String>): VisionPushResult<Unit> {
        val body = JSONObject().apply {
            val arr = org.json.JSONArray(); topics.forEach { arr.put(it) }
            put("topics_unsubscribe", arr)
        }
        return put(path = "/devices/$deviceId", body = body).map { }
    }

    /**
     * Fetches in-app messages eligible for this device.
     * Sends a GET to `/in-app-messages?app_id={appId}&device_id={deviceId}`.
     */
    suspend fun fetchInAppMessages(appId: String, deviceId: String): VisionPushResult<List<InAppMessage>> =
        execute(
            Request.Builder()
                .url(config.apiBaseUrl + "/in-app-messages?app_id=$appId&device_id=$deviceId")
                .get()
                .build()
        ).map { response ->
            val result = mutableListOf<InAppMessage>()
            // Backend returns {"data": [...]} — data is a JSON array.
            response.rawDataArray?.let { arr ->
                for (i in 0 until arr.length()) {
                    InAppMessage.fromJson(arr.getJSONObject(i))?.let { result.add(it) }
                }
            }
            result
        }

    /**
     * Fetches the current device record and returns the server-side tags.
     * Sends a GET to `/devices/{id}`.
     */
    suspend fun getDeviceTags(deviceId: String): VisionPushResult<Map<String, String>> =
        execute(
            Request.Builder()
                .url(config.apiBaseUrl + "/devices/$deviceId")
                .get()
                .build()
        ).map { response ->
            val tags = mutableMapOf<String, String>()
            response.rawData?.optJSONObject("tags")?.let { obj ->
                obj.keys().forEach { key -> tags[key] = obj.optString(key) }
            }
            tags
        }

    /**
     * Sends a custom outcome event to POST `/devices/{id}/outcomes`.
     *
     * @param deviceId Server-assigned device ID.
     * @param name     Outcome identifier (e.g. "purchase_completed").
     * @param value    Optional numeric value (e.g. purchase amount). Pass `null` for binary outcomes.
     */
    suspend fun sendOutcome(deviceId: String, name: String, value: Float? = null): VisionPushResult<Unit> {
        val body = JSONObject().apply {
            put("name", name)
            put("device_id", deviceId)
            if (value != null) put("value", value) else put("value", JSONObject.NULL)
        }
        return post(path = "/devices/$deviceId/outcomes", body = body).map { }
    }

    /**
     * Tracks an in-app message impression (fire-and-forget; errors are silently swallowed).
     */
    suspend fun trackIAMImpression(appId: String, deviceId: String, messageId: String): VisionPushResult<Unit> =
        post(
            path = "/in-app-messages/$messageId/impression",
            body = JSONObject().apply { put("app_id", appId); put("device_id", deviceId) }
        ).map { }

    // ── Internal HTTP helpers ───────────────────────────────────────────────

    private suspend fun post(path: String, body: JSONObject): VisionPushResult<ApiResponse> =
        execute(
            Request.Builder()
                .url(config.apiBaseUrl + path)
                .post(body.toRequestBody())
                .build()
        )

    private suspend fun put(path: String, body: JSONObject): VisionPushResult<ApiResponse> =
        execute(
            Request.Builder()
                .url(config.apiBaseUrl + path)
                .put(body.toRequestBody())
                .build()
        )

    private suspend fun delete(path: String, body: JSONObject): VisionPushResult<ApiResponse> =
        execute(
            Request.Builder()
                .url(config.apiBaseUrl + path)
                .delete(body.toRequestBody())
                .build()
        )

    /**
     * Executes a [Request] with exponential-backoff retries.
     *
     * Retries on transient failures: [IOException] (network), 5xx server errors,
     * and 429 Too Many Requests. Non-retryable errors (4xx except 429) are returned
     * immediately. Maximum [MAX_RETRIES] attempts with delays 500 ms → 1 s → 2 s
     * (capped at [MAX_DELAY_MS]). 429 responses use a 2 s floor delay.
     */
    private suspend fun execute(request: Request): VisionPushResult<ApiResponse> =
        withContext(Dispatchers.IO) {
            var delayMs = INITIAL_DELAY_MS
            var attempt = 0
            var result: VisionPushResult<ApiResponse>
            do {
                result = try {
                    val response = httpClient.newCall(request).execute()
                    val bodyString = response.body?.string() ?: "{}"
                    val json = runCatching { JSONObject(bodyString) }.getOrElse { JSONObject() }
                    if (response.isSuccessful) {
                        VisionPushResult.Success(ApiResponse.fromJson(json))
                    } else {
                        val message = json.optString("message", "Unexpected server error.")
                        VisionPushResult.Failure(VisionPushError.ApiError(response.code, message))
                    }
                } catch (e: IOException) {
                    VisionPushResult.Failure(VisionPushError.NetworkError(e))
                } catch (e: Exception) {
                    return@withContext VisionPushResult.Failure(VisionPushError.Unknown(e))
                }

                val shouldRetry = when {
                    attempt >= MAX_RETRIES -> false
                    result is VisionPushResult.Failure && result.error is VisionPushError.NetworkError -> true
                    result is VisionPushResult.Failure && result.error is VisionPushError.ApiError -> {
                        val status = (result.error as VisionPushError.ApiError).httpStatus
                        status == 429 || status >= 500
                    }
                    else -> false
                }

                if (!shouldRetry) break

                val isRateLimit = result is VisionPushResult.Failure &&
                        result.error is VisionPushError.ApiError &&
                        (result.error as VisionPushError.ApiError).httpStatus == 429
                val waitMs = if (isRateLimit) maxOf(delayMs, RATE_LIMIT_DELAY_MS) else delayMs

                VisionPushLogger.d(TAG, "Request failed (attempt ${attempt + 1}/$MAX_RETRIES), retrying in ${waitMs}ms")
                delay(waitMs)
                delayMs = minOf(delayMs * 2, MAX_DELAY_MS)
                attempt++
            } while (true)
            result
        }

    // ── Interceptors ────────────────────────────────────────────────────────

    /** Injects authentication and content-type headers into every request. */
    private fun buildAuthInterceptor() = okhttp3.Interceptor { chain ->
        val request = chain.request().newBuilder()
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("X-App-Id", config.appId)
            .addHeader("X-Sdk-Version", BuildConfig.VISIONPUSH_SDK_VERSION)
            .addHeader("Content-Type", JSON_MEDIA_TYPE.toString())
            .addHeader("Accept", JSON_MEDIA_TYPE.toString())
            .build()
        chain.proceed(request)
    }

    /** Logs request/response details according to the configured [VisionPushConfig.LogLevel]. */
    private fun buildLoggingInterceptor(): HttpLoggingInterceptor {
        // HEADERS and BODY levels would expose the Authorization: Bearer token in logs.
        // Cap at BASIC (method + URL + status code) to prevent credential leakage.
        val level = when (config.logLevel) {
            VisionPushConfig.LogLevel.VERBOSE,
            VisionPushConfig.LogLevel.DEBUG,
            VisionPushConfig.LogLevel.INFO -> HttpLoggingInterceptor.Level.BASIC
            else                           -> HttpLoggingInterceptor.Level.NONE
        }
        return HttpLoggingInterceptor { message ->
            VisionPushLogger.d(TAG, message)
        }.apply { setLevel(level) }
    }

    // ── Companion ───────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "VP.ApiClient"
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val READ_TIMEOUT_SECONDS = 30L
        private const val WRITE_TIMEOUT_SECONDS = 15L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MAX_RETRIES = 3
        private const val INITIAL_DELAY_MS = 500L
        private const val MAX_DELAY_MS = 8_000L
        private const val RATE_LIMIT_DELAY_MS = 2_000L

        /** Converts a [JSONObject] to an OkHttp [okhttp3.RequestBody]. */
        private fun JSONObject.toRequestBody() =
            toString().toRequestBody(JSON_MEDIA_TYPE)
    }
}
