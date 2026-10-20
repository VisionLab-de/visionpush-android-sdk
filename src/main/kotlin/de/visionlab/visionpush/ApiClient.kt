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
import java.util.UUID
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
    private val config: VisionPushConfig,
    clientOverride: OkHttpClient? = null
) {

    // ── HTTP client ─────────────────────────────────────────────────────────
    //
    // [clientOverride] lets unit tests inject an OkHttpClient whose single
    // interceptor short-circuits the network with canned responses, exercising the
    // retry/backoff logic in [execute] without any real I/O. Production callers pass
    // no override and get the fully-configured client below.
    //
    // The auth interceptor is spliced in FIRST even on an override, so what the
    // tests observe is what really goes on the wire. Appending it would be
    // pointless: a canned-response interceptor short-circuits the chain before
    // anything added after it ever runs, which is exactly why the auth headers
    // used to be invisible to (and untestable by) the unit tests.

    internal val httpClient: OkHttpClient = clientOverride
        ?.newBuilder()
        ?.apply { interceptors().add(0, buildAuthInterceptor()) }
        ?.build()
        ?: OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .addInterceptor(buildAuthInterceptor())
            .addInterceptor(buildLoggingInterceptor())
            .build()

    // ── Device binding ──────────────────────────────────────────────────────

    /**
     * This installation's id, sent as `X-VisionPush-Installation-Id` on every
     * request once it exists.
     *
     * A `client` API key is extractable from the APK, so on its own it only
     * proves WHICH APP is calling. The installation id ([DeviceRegistrar]'s
     * `anonymousId`: kept in this app's encrypted prefs, never handed to another
     * user — unlike the device UUID, which the API returns) proves WHICH
     * INSTALLATION, so a lifted key cannot act on another user's device.
     *
     * Late-bound and `@Volatile` rather than a constructor argument: the
     * registrar that owns the id is built FROM this client, mints the id lazily,
     * and the interceptor reads it from OkHttp's own threads.
     */
    @Volatile
    internal var installationId: String? = null

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
     * The request is retried on network errors / 5xx (see [execute]). Because a lost
     * *response* to a request the server did apply would otherwise duplicate the
     * revenue row, every call carries an `idempotency_key` — generated ONCE per call
     * (default argument) and baked into the single [Request] that all retry attempts
     * reuse, so the server can dedup on `(device, key)`.
     *
     * @param deviceId       Server-assigned device ID.
     * @param name           Outcome identifier (e.g. "purchase_completed").
     * @param value          Optional numeric value (e.g. purchase amount). Pass `null` for binary outcomes.
     * @param idempotencyKey Per-call dedup key (≤ 64 chars; a UUID by default).
     */
    suspend fun sendOutcome(
        deviceId: String,
        name: String,
        value: Float? = null,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ): VisionPushResult<Unit> {
        require(idempotencyKey.isNotEmpty() && idempotencyKey.length <= MAX_IDEMPOTENCY_KEY_LENGTH) {
            "idempotencyKey must be 1–$MAX_IDEMPOTENCY_KEY_LENGTH characters"
        }
        val body = JSONObject().apply {
            put("name", name)
            put("device_id", deviceId)
            // toDouble(): Kotlin resolves a Float to org.json's put(String, float), which
            // exists in the org.json:json artifact but NOT in Android's platform org.json
            // (which wins at runtime) → NoSuchMethodError on device. Double is universal.
            if (value != null) put("value", value.toDouble()) else put("value", JSONObject.NULL)
            put("idempotency_key", idempotencyKey)
        }
        return post(path = "/devices/$deviceId/outcomes", body = body).map { }
    }

    /**
     * Clears the external-user association of the device identified by [deviceId].
     * Sends a PUT to `/devices/{id}` with `{"clear_external_id": true}`.
     *
     * A separate call is required because the device upsert treats an omitted / null
     * `external_id` as "leave unchanged" — omission alone can never clear it.
     */
    suspend fun clearExternalId(deviceId: String): VisionPushResult<Unit> {
        val body = JSONObject().apply { put("clear_external_id", true) }
        return put(path = "/devices/$deviceId", body = body).map { }
    }

    /**
     * Tracks an in-app message impression (fire-and-forget; errors are silently swallowed).
     */
    suspend fun trackIAMImpression(appId: String, deviceId: String, messageId: String): VisionPushResult<Unit> =
        post(
            path = "/in-app-messages/$messageId/impression",
            body = JSONObject().apply { put("app_id", appId); put("device_id", deviceId) }
        ).map { }

    /**
     * Pushes the device's current coordinates and returns the nearest active geofences
     * for on-device region monitoring. Calls `PUT /devices/{id}/location`; the backend
     * persists the location and replies with `{"geofences": [...]}`.
     * Mirrors the iOS SDK's `updateDeviceLocation`.
     */
    suspend fun updateDeviceLocation(
        deviceId: String,
        latitude: Double,
        longitude: Double
    ): VisionPushResult<List<GeofenceData>> {
        val body = JSONObject().apply {
            put("latitude", latitude)
            put("longitude", longitude)
        }
        return put(path = "/devices/$deviceId/location", body = body).map { response ->
            val result = mutableListOf<GeofenceData>()
            // Backend returns top-level {"geofences": [...]} (no `data` wrapper).
            response.rawBody?.optJSONArray("geofences")?.let { arr ->
                for (i in 0 until arr.length()) {
                    result.add(GeofenceData.fromJson(arr.getJSONObject(i)))
                }
            }
            result
        }
    }

    /**
     * Reports an on-device geofence transition (`entry` / `exit` / `dwell`) to
     * `POST /devices/{id}/geofence-events`. Mirrors the iOS SDK's `reportGeofenceEvent`.
     */
    suspend fun reportGeofenceEvent(
        deviceId: String,
        geofenceId: String,
        eventType: String,
        latitude: Double?,
        longitude: Double?
    ): VisionPushResult<Unit> {
        val body = JSONObject().apply {
            put("geofence_id", geofenceId)
            put("event_type", eventType)
            if (latitude != null) put("latitude", latitude) else put("latitude", JSONObject.NULL)
            if (longitude != null) put("longitude", longitude) else put("longitude", JSONObject.NULL)
        }
        return post(path = "/devices/$deviceId/geofence-events", body = body).map { }
    }

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
     * immediately. Maximum [MAX_RETRIES] attempts with a 500 ms → 1 s → 2 s base
     * (capped at [MAX_DELAY_MS]); the actual wait comes from [RetryBackoff]: the
     * server's `Retry-After` header when present (capped at 60 s), otherwise the base
     * with equal jitter, and never below a 2 s floor for 429.
     *
     * The same [Request] (and body) is re-sent on every attempt, so per-call values
     * embedded in the body — e.g. an outcome's `idempotency_key` — are stable across
     * retries by construction.
     */
    private suspend fun execute(request: Request): VisionPushResult<ApiResponse> =
        withContext(Dispatchers.IO) {
            var delayMs = INITIAL_DELAY_MS
            var attempt = 0
            var result: VisionPushResult<ApiResponse>
            do {
                var retryAfterMs: Long? = null
                result = try {
                    val response = httpClient.newCall(request).execute()
                    val bodyString = response.body?.string() ?: "{}"
                    val json = runCatching { JSONObject(bodyString) }.getOrElse { JSONObject() }
                    if (response.isSuccessful) {
                        VisionPushResult.Success(ApiResponse.fromJson(json))
                    } else {
                        retryAfterMs = RetryBackoff.parseRetryAfterMs(response.header("Retry-After"))
                        // Error bodies are either {"message": "..."} or a bare code
                        // {"error": "<code>"} (e.g. external_id_verification_failed);
                        // keep the code machine-readable instead of flattening it away.
                        val errorCode = json.optString("error").takeIf { it.isNotBlank() }
                        val message = json.optString("message").takeIf { it.isNotBlank() }
                            ?: errorCode
                            ?: "Unexpected server error."
                        VisionPushResult.Failure(VisionPushError.ApiError(response.code, message, errorCode))
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
                val waitMs = RetryBackoff.delayMs(
                    exponentialMs = delayMs,
                    retryAfterMs = retryAfterMs,
                    isRateLimit = isRateLimit,
                    rateLimitFloorMs = RATE_LIMIT_DELAY_MS,
                )

                VisionPushLogger.d(TAG, "Request failed (attempt ${attempt + 1}/$MAX_RETRIES), retrying in ${waitMs}ms")
                delay(waitMs)
                delayMs = minOf(delayMs * 2, MAX_DELAY_MS)
                attempt++
            } while (true)
            result
        }

    // ── Interceptors ────────────────────────────────────────────────────────

    /**
     * Injects authentication and content-type headers into every request.
     *
     * `internal` so the unit tests, whose `clientOverride` replaces the whole
     * OkHttpClient (and with it this interceptor), can install the real thing
     * and assert on what actually goes on the wire.
     */
    internal fun buildAuthInterceptor() = okhttp3.Interceptor { chain ->
        val builder = chain.request().newBuilder()
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            // Diagnostic only — the server authorises from the Bearer key and
            // reads the app id from the request body/query, never from a header.
            // Named `X-App-Id` here while iOS and the web SDK send
            // `X-VisionPush-App-Id`, so anything downstream (a WAF rule, an
            // access-log dashboard, a future server-side read) silently saw
            // Android as a different client. Aligned on the name the other two
            // already use.
            .addHeader("X-VisionPush-App-Id", config.appId)
            .addHeader("X-Sdk-Version", BuildConfig.VISIONPUSH_SDK_VERSION)
            .addHeader("Content-Type", JSON_MEDIA_TYPE.toString())
            .addHeader("Accept", JSON_MEDIA_TYPE.toString())
        // Device binding — omitted (never sent blank) until the registrar has
        // minted an id; the server tolerates that until the app opts in.
        installationId?.takeIf { it.isNotBlank() }?.let {
            builder.addHeader("X-VisionPush-Installation-Id", it)
        }
        chain.proceed(builder.build())
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

        /** Server-side limit for an outcome's `idempotency_key`. */
        private const val MAX_IDEMPOTENCY_KEY_LENGTH = 64

        /** Converts a [JSONObject] to an OkHttp [okhttp3.RequestBody]. */
        private fun JSONObject.toRequestBody() =
            toString().toRequestBody(JSON_MEDIA_TYPE)
    }
}
