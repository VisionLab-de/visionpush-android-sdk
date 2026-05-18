package de.visionlab.visionpush

import kotlinx.coroutines.Dispatchers
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
 * Authentication is supplied via the `X-Api-Key` header using the key provided
 * during [VisionPush.initialize].
 */
internal class ApiClient(
    private val config: VisionPushConfig
) {

    // ── HTTP client ─────────────────────────────────────────────────────────

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
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
            path = "/devices",
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
     * Updates only the opt-in/opt-out state for the given device.
     *
     * @param fcmToken   Current FCM token identifying the device.
     * @param optedOut   `true` to suppress all future notifications.
     */
    suspend fun updateOptStatus(fcmToken: String, optedOut: Boolean): VisionPushResult<Unit> {
        val body = JSONObject().apply {
            put("fcm_token", fcmToken)
            put("opted_out", optedOut)
        }
        return patch(path = "/devices/opt", body = body).map { }
    }

    /**
     * Deletes device registration data for the given FCM token.
     * Called when the user logs out or explicitly requests data deletion.
     */
    suspend fun deleteDevice(fcmToken: String): VisionPushResult<Unit> {
        val body = JSONObject().apply {
            put("fcm_token", fcmToken)
        }
        return delete(path = "/devices", body = body).map { }
    }

    // ── Internal HTTP helpers ───────────────────────────────────────────────

    private suspend fun post(path: String, body: JSONObject): VisionPushResult<ApiResponse> =
        execute(
            Request.Builder()
                .url(config.apiBaseUrl + path)
                .post(body.toRequestBody())
                .build()
        )

    private suspend fun patch(path: String, body: JSONObject): VisionPushResult<ApiResponse> =
        execute(
            Request.Builder()
                .url(config.apiBaseUrl + path)
                .patch(body.toRequestBody())
                .build()
        )

    private suspend fun delete(path: String, body: JSONObject): VisionPushResult<ApiResponse> =
        execute(
            Request.Builder()
                .url(config.apiBaseUrl + path)
                .delete(body.toRequestBody())
                .build()
        )

    /** Executes a [Request] on [Dispatchers.IO] and maps the response. */
    private suspend fun execute(request: Request): VisionPushResult<ApiResponse> =
        withContext(Dispatchers.IO) {
            try {
                val response = okHttpClient.newCall(request).execute()
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
                VisionPushResult.Failure(VisionPushError.Unknown(e))
            }
        }

    // ── Interceptors ────────────────────────────────────────────────────────

    /** Injects authentication and content-type headers into every request. */
    private fun buildAuthInterceptor() = okhttp3.Interceptor { chain ->
        val request = chain.request().newBuilder()
            .addHeader("X-Api-Key", config.apiKey)
            .addHeader("X-App-Id", config.appId)
            .addHeader("X-Sdk-Version", BuildConfig.VISIONPUSH_SDK_VERSION)
            .addHeader("Content-Type", JSON_MEDIA_TYPE.toString())
            .addHeader("Accept", JSON_MEDIA_TYPE.toString())
            .build()
        chain.proceed(request)
    }

    /** Logs request/response details according to the configured [VisionPushConfig.LogLevel]. */
    private fun buildLoggingInterceptor(): HttpLoggingInterceptor {
        val level = when (config.logLevel) {
            VisionPushConfig.LogLevel.VERBOSE -> HttpLoggingInterceptor.Level.BODY
            VisionPushConfig.LogLevel.DEBUG   -> HttpLoggingInterceptor.Level.HEADERS
            VisionPushConfig.LogLevel.INFO    -> HttpLoggingInterceptor.Level.BASIC
            else                              -> HttpLoggingInterceptor.Level.NONE
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

        /** Converts a [JSONObject] to an OkHttp [okhttp3.RequestBody]. */
        private fun JSONObject.toRequestBody() =
            toString().toRequestBody(JSON_MEDIA_TYPE)
    }
}
