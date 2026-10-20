package de.visionlab.visionpush

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Pins every REST route [ApiClient] calls to the server's REAL response shape —
 * read from `docs/api/openapi.json` where it documents a schema
 * (`RegisterDeviceResponse`, `ApiErrorBody`, `ApiErrorDetail`), and from the
 * handler's own `file:line` where it does not (most SDK routes document only a
 * bare `type: object` or nothing at all).
 *
 * SQ-292/SQ-295 found two real decode bugs this way:
 * - `getDeviceTags()` expected a `{"data":{...}}` envelope; the server answers
 *   `GET /devices/{id}/user` flat, with `tags` a top-level key
 *   (`services/user.rs:633-640` `UserService::get_device_user`).
 * - [ApiClient.execute]'s error parsing expected a top-level `{"message":...}`
 *   or a bare `{"error":"<code>"}`; the server ALWAYS nests
 *   `{"error":{"code","message"}}` (`crates/visionpush-api/src/error.rs:177-182`).
 *
 * With the OLD decoding (pre-SQ-295) this suite fails:
 * [getDeviceTags_decodesTheFlatUserResponse] on an empty tag map, and every
 * error-shaped row ([optIn_rejectsA403WithTheRealNestedErrorBody] etc.) on a
 * JSON-dump `errorCode` instead of the server's real `code`/`message` split.
 *
 * Mirrors `sdks/ios/Tests/VisionPushSDKTests/ApiContractTests.swift` (SQ-291):
 * same `docs/api/openapi.json` authority, same "real handler fixture + its
 * file:line where no schema exists" fallback, one row per [ApiClient] route.
 */
@RunWith(RobolectricTestRunner::class)
class ApiContractTest {

    private val config = VisionPushConfig(
        appId = "11111111-1111-1111-1111-111111111111",
        apiKey = "vp_test_key",
        apiBaseUrl = "https://api.visionpush.test/v1",
    )

    private data class Sent(val method: String, val path: String)

    /** Single canned response; records the one request the SDK call sent. */
    private class OneShotInterceptor(private val status: Int, private val bodyJson: String) : Interceptor {
        var sent: Sent? = null

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            sent = Sent(request.method, request.url.encodedPath)
            val media = "application/json; charset=utf-8".toMediaType()
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message(if (status in 200..299) "OK" else "Error")
                .body(bodyJson.toResponseBody(media))
                .build()
        }
    }

    /** Builds an [ApiClient] whose single request gets [bodyJson] back at [status],
     *  and also checks the route against openapi.json before returning it. */
    private fun apiClient(method: String, path: String, status: Int, bodyJson: String): Pair<ApiClient, OneShotInterceptor> {
        assertRouteContract(method, path, status, bodyJson)
        val interceptor = OneShotInterceptor(status, bodyJson)
        val client = ApiClient(config, clientOverride = OkHttpClient.Builder().addInterceptor(interceptor).build())
        return client to interceptor
    }

    private fun registration(externalId: String? = null) = DeviceRegistration(
        appId = config.appId, pushToken = "tok-1", platform = "android", externalId = externalId,
        installationId = "inst-1", anonymousId = "inst-1", locale = "en-US", country = "US",
        deviceOs = "Android 14", deviceModel = "Pixel", appVersion = "1.0", timezone = "UTC",
        timezoneOffset = 0, environment = "sandbox", bundleId = "com.example", appBuildNumber = "1",
        deviceType = "phone", deviceManufacturer = "Google", networkType = "wifi",
        notificationPermission = "granted", tags = null, sealedPubkey = "pk", sealedKeyAlg = "alg",
        sealedAuth = "auth",
    )

    // ── docs/api/openapi.json ────────────────────────────────────────────────

    /** Walk up from the test working dir to the repo root (robust to the Gradle
     *  module's working directory) — same pattern as [RealtimeConformanceTest]. */
    private fun loadOpenApi(): JSONObject {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val f = File(dir, "docs/api/openapi.json")
            if (f.isFile) return JSONObject(f.readText())
            dir = dir.parentFile
        }
        throw IllegalStateException("docs/api/openapi.json not found from ${System.getProperty("user.dir")}")
    }

    private val openApi by lazy { loadOpenApi() }

    private fun operation(method: String, path: String): JSONObject? {
        val paths = openApi.optJSONObject("paths") ?: return null
        return paths.optJSONObject(path)?.optJSONObject(method.lowercase())
    }

    private fun responseSchema(op: JSONObject, status: Int): JSONObject? {
        val response = op.optJSONObject("responses")?.optJSONObject(status.toString()) ?: return null
        return response.optJSONObject("content")?.optJSONObject("application/json")?.optJSONObject("schema")
    }

    /** Resolves a (possibly `$ref`'d) schema to its concrete definition. */
    private fun resolve(schema: JSONObject): JSONObject {
        val ref = schema.optString("\$ref").takeIf { it.isNotEmpty() } ?: return schema
        val name = ref.substringAfterLast("/")
        val resolved = openApi.optJSONObject("components")?.optJSONObject("schemas")?.optJSONObject(name)
            ?: return schema
        return resolve(resolved)
    }

    /** Fails when [value] lacks a property the (possibly `$ref`'d) [schema] requires. */
    private fun assertConforms(value: JSONObject, schema: JSONObject, label: String) {
        val resolved = resolve(schema)
        if (resolved.optString("type") != "object") return
        val required = resolved.optJSONArray("required") ?: JSONArray()
        for (i in 0 until required.length()) {
            val key = required.getString(i)
            assertTrue("$label: fixture lacks openapi-required \"$key\"", value.has(key))
        }
        val properties = resolved.optJSONObject("properties") ?: JSONObject()
        for (key in properties.keys()) {
            val child = value.opt(key)
            if (child is JSONObject) {
                assertConforms(child, properties.getJSONObject(key), "$label.$key")
            }
        }
    }

    /** Confirms `method path` exists in openapi.json at `status`, and — where
     *  openapi documents a response schema — that [bodyJson] conforms to it. */
    private fun assertRouteContract(method: String, path: String, status: Int, bodyJson: String) {
        val op = requireNotNull(operation(method, path)) { "openapi has no $method $path" }
        val responses = op.optJSONObject("responses") ?: JSONObject()
        assertTrue(
            "openapi documents ${responses.keys().asSequence().toList()}, not $status for $method $path",
            responses.has(status.toString()),
        )
        if (bodyJson.isBlank()) return
        responseSchema(op, status)?.let { schema ->
            assertConforms(JSONObject(bodyJson), schema, "$method $path")
        }
    }

    // ── register ──────────────────────────────────────────────────────────────

    @Test
    fun registerDevice_matchesTheRegisterDeviceResponseSchema() {
        // RegisterDeviceResponse (docs/api/openapi.json components.schemas) requires
        // device_id/is_new/user_id — a real server answer (devices.rs:662-669).
        val fixture = """{"device_id":"dev-1","is_new":true,"user_id":"user-1"}"""
        val (client, interceptor) = apiClient("POST", "/v1/devices/register", 201, fixture)
        val result = runBlocking { client.registerDevice(registration()) }
        assertTrue(result.isSuccess)
        val outcome = (result as VisionPushResult.Success).data
        assertEquals("dev-1", outcome.deviceId)
        assertEquals("user-1", outcome.userId)
        assertEquals(Sent("POST", "/v1/devices/register"), interceptor.sent)
    }

    // ── events ────────────────────────────────────────────────────────────────

    @Test
    fun trackEvent_acceptsTheRealAcceptedResponse() {
        // crates/visionpush-api/src/routes/events.rs:362 Handler::process.
        val fixture = """{"accepted":true}"""
        val (client, interceptor) = apiClient("POST", "/v1/events", 200, fixture)
        val result = runBlocking {
            client.trackEvent(
                NotificationEvent(
                    notificationId = "n1", eventType = NotificationEvent.EventType.DELIVERED,
                    actionId = null, campaignId = null, deviceId = "dev-1",
                )
            )
        }
        assertTrue(result.isSuccess)
        assertEquals(Sent("POST", "/v1/events"), interceptor.sent)
    }

    // ── opt-in / opt-out (no body) ───────────────────────────────────────────

    @Test
    fun optIn_acceptsTheReal204() {
        val (client, interceptor) = apiClient("POST", "/v1/devices/{id}/opt-in", 204, "")
        assertTrue(runBlocking { client.optIn("dev-1") }.isSuccess)
        assertEquals(Sent("POST", "/v1/devices/dev-1/opt-in"), interceptor.sent)
    }

    @Test
    fun optOut_acceptsTheReal204() {
        val (client, interceptor) = apiClient("POST", "/v1/devices/{id}/opt-out", 204, "")
        assertTrue(runBlocking { client.optOut("dev-1") }.isSuccess)
        assertEquals(Sent("POST", "/v1/devices/dev-1/opt-out"), interceptor.sent)
    }

    @Test
    fun deleteDevice_acceptsTheReal204() {
        val (client, interceptor) = apiClient("DELETE", "/v1/devices/{id}", 204, "")
        assertTrue(runBlocking { client.deleteDevice("dev-1") }.isSuccess)
        assertEquals(Sent("DELETE", "/v1/devices/dev-1"), interceptor.sent)
    }

    // ── PATCH /devices/{id}/user (tags) ──────────────────────────────────────

    @Test
    fun updateDeviceTags_acceptsTheReal204() {
        // crates/visionpush-api/src/routes/devices.rs:1077-1104 DeviceHandler::update_user.
        val (client, interceptor) = apiClient("PATCH", "/v1/devices/{id}/user", 204, "")
        assertTrue(runBlocking { client.updateDeviceTags("dev-1", mapOf("plan" to "pro")) }.isSuccess)
        assertEquals(Sent("PATCH", "/v1/devices/dev-1/user"), interceptor.sent)
    }

    @Test
    fun removeDeviceTags_acceptsTheReal204() {
        val (client, interceptor) = apiClient("PATCH", "/v1/devices/{id}/user", 204, "")
        assertTrue(runBlocking { client.removeDeviceTags("dev-1", listOf("plan")) }.isSuccess)
        assertEquals(Sent("PATCH", "/v1/devices/dev-1/user"), interceptor.sent)
    }

    // ── PUT /devices/{id} (SQ-272: 200 {"user_id"}, no schema documented) ────

    private val putDeviceBody = """{"user_id":"user-1"}"""

    @Test
    fun setUserProfile_acceptsTheReal200WithUserId() {
        // crates/visionpush-api/src/routes/devices.rs:756-759 DeviceHandler::update.
        val (client, interceptor) = apiClient("PUT", "/v1/devices/{id}", 200, putDeviceBody)
        assertTrue(runBlocking { client.setUserProfile("dev-1", UserProfile(firstName = "Jana")) }.isSuccess)
        assertEquals(Sent("PUT", "/v1/devices/dev-1"), interceptor.sent)
    }

    @Test
    fun subscribeToTopics_acceptsTheReal200WithUserId() {
        val (client, interceptor) = apiClient("PUT", "/v1/devices/{id}", 200, putDeviceBody)
        assertTrue(runBlocking { client.subscribeToTopics("dev-1", listOf("news")) }.isSuccess)
        assertEquals(Sent("PUT", "/v1/devices/dev-1"), interceptor.sent)
    }

    @Test
    fun unsubscribeFromTopics_acceptsTheReal200WithUserId() {
        val (client, interceptor) = apiClient("PUT", "/v1/devices/{id}", 200, putDeviceBody)
        assertTrue(runBlocking { client.unsubscribeFromTopics("dev-1", listOf("news")) }.isSuccess)
        assertEquals(Sent("PUT", "/v1/devices/dev-1"), interceptor.sent)
    }

    @Test
    fun clearExternalId_acceptsTheReal200WithUserId() {
        val (client, interceptor) = apiClient("PUT", "/v1/devices/{id}", 200, putDeviceBody)
        assertTrue(runBlocking { client.clearExternalId("dev-1") }.isSuccess)
        assertEquals(Sent("PUT", "/v1/devices/dev-1"), interceptor.sent)
    }

    // ── GET /in-app-messages (the one route that really uses the {success,data} envelope) ──

    @Test
    fun fetchInAppMessages_decodesTheRealEnvelope() {
        // crates/visionpush-api/src/routes/in_app_messages.rs:1034-1037 Handler::sdk_list.
        val fixture = """{"success":true,"data":[{"id":"m1","type":"modal","title":"Hi","body":"there"}]}"""
        val (client, interceptor) = apiClient("GET", "/v1/in-app-messages", 200, fixture)
        val result = runBlocking { client.fetchInAppMessages("app-1", "dev-1") }
        assertTrue(result.isSuccess)
        assertEquals(1, (result as VisionPushResult.Success).data.size)
        assertEquals("m1", result.data.first().id)
        assertEquals("GET", interceptor.sent?.method)
        assertTrue(interceptor.sent?.path?.startsWith("/v1/in-app-messages") == true)
    }

    // ── GET /devices/{id}/user (SQ-295: the fix — flat tags, not {"data":{...}}) ──

    @Test
    fun getDeviceTags_decodesTheFlatUserResponse() {
        // Real shape of services/user.rs:633-640 UserService::get_device_user: `tags`
        // is a TOP-LEVEL key. The OLD decoding read response.data.tags (an envelope
        // this route never sends), so it always returned an empty map; this row fails
        // under that decoding.
        val fixture = """{"user_id":"user-1","tags":{"plan":"pro","level":5},""" +
            """"language":"de","country":"DE","aliases":{},"subscriptions":[]}"""
        val (client, interceptor) = apiClient("GET", "/v1/devices/{id}/user", 200, fixture)
        val result = runBlocking { client.getDeviceTags("dev-1") }
        assertTrue(result.isSuccess)
        assertEquals(mapOf("plan" to "pro", "level" to "5"), (result as VisionPushResult.Success).data)
        // Also pins the SQ-297 route switch: a client key cannot read GET /devices/{id}
        // (needs devices:read); this device-bound route needs only devices:write.
        assertEquals(Sent("GET", "/v1/devices/dev-1/user"), interceptor.sent)
    }

    // ── outcomes (no body) ───────────────────────────────────────────────────

    @Test
    fun sendOutcome_acceptsTheReal204() {
        val (client, interceptor) = apiClient("POST", "/v1/devices/{id}/outcomes", 204, "")
        assertTrue(runBlocking { client.sendOutcome("dev-1", "purchase", 9.99f) }.isSuccess)
        assertEquals(Sent("POST", "/v1/devices/dev-1/outcomes"), interceptor.sent)
    }

    // ── user aliases (no body) ───────────────────────────────────────────────

    @Test
    fun addUserAlias_acceptsTheReal204() {
        val (client, interceptor) = apiClient("POST", "/v1/devices/{id}/user/aliases", 204, "")
        assertTrue(runBlocking { client.addUserAlias("dev-1", "loyalty_card", "LC-1", "ab".repeat(32)) }.isSuccess)
        assertEquals(Sent("POST", "/v1/devices/dev-1/user/aliases"), interceptor.sent)
    }

    @Test
    fun removeUserAlias_acceptsTheReal204() {
        val (client, interceptor) = apiClient("DELETE", "/v1/devices/{id}/user/aliases", 204, "")
        assertTrue(runBlocking { client.removeUserAlias("dev-1", "loyalty_card", "LC-1", "ab".repeat(32)) }.isSuccess)
        assertEquals(Sent("DELETE", "/v1/devices/dev-1/user/aliases"), interceptor.sent)
    }

    // ── user subscriptions ───────────────────────────────────────────────────

    @Test
    fun addUserSubscription_decodesTheRealSubscriptionId() {
        // crates/visionpush-api/src/routes/devices.rs:1182-1193 DeviceHandler::add_user_subscription.
        val fixture = """{"subscription_id":"sub-1"}"""
        val (client, interceptor) = apiClient("POST", "/v1/devices/{id}/user/subscriptions", 201, fixture)
        val result = runBlocking { client.addUserSubscription("dev-1", "email", "a@b.de") }
        assertTrue(result.isSuccess)
        assertEquals(Sent("POST", "/v1/devices/dev-1/user/subscriptions"), interceptor.sent)
    }

    // ── in-app message tracking (fire-and-forget, no body) ──────────────────

    @Test
    fun trackIAMImpression_acceptsTheReal204() {
        val (client, interceptor) = apiClient("POST", "/v1/in-app-messages/{id}/impression", 204, "")
        assertTrue(runBlocking { client.trackIAMImpression("app-1", "dev-1", "m1") }.isSuccess)
        assertEquals(Sent("POST", "/v1/in-app-messages/m1/impression"), interceptor.sent)
    }

    @Test
    fun trackIAMClick_acceptsTheReal204() {
        val (client, interceptor) = apiClient("POST", "/v1/in-app-messages/{id}/click", 204, "")
        assertTrue(runBlocking { client.trackIAMClick("app-1", "dev-1", "m1", "primary") }.isSuccess)
        assertEquals(Sent("POST", "/v1/in-app-messages/m1/click"), interceptor.sent)
    }

    @Test
    fun trackIAMDismiss_acceptsTheReal204() {
        val (client, interceptor) = apiClient("POST", "/v1/in-app-messages/{id}/dismiss", 204, "")
        assertTrue(runBlocking { client.trackIAMDismiss("app-1", "dev-1", "m1") }.isSuccess)
        assertEquals(Sent("POST", "/v1/in-app-messages/m1/dismiss"), interceptor.sent)
    }

    // ── geofencing ────────────────────────────────────────────────────────────

    @Test
    fun updateDeviceLocation_decodesTheRealGeofenceList() {
        // crates/visionpush-api/src/routes/geofencing.rs:366-467 update_device_location.
        val fixture = """{"geofences":[{"id":"geo-1","name":"Store","latitude":52.52,""" +
            """"longitude":13.405,"radius_meters":150,"dwell_time_secs":null,"distance_meters":42.5}]}"""
        val (client, interceptor) = apiClient("PUT", "/v1/devices/{id}/location", 200, fixture)
        val result = runBlocking { client.updateDeviceLocation("dev-1", 52.5, 13.4) }
        assertTrue(result.isSuccess)
        val fences = (result as VisionPushResult.Success).data
        assertEquals(1, fences.size)
        assertEquals(150, fences.first().radiusMeters)
        assertEquals(Sent("PUT", "/v1/devices/dev-1/location"), interceptor.sent)
    }

    @Test
    fun reportGeofenceEvent_acceptsTheReal201() {
        // crates/visionpush-api/src/routes/geofencing.rs:477-631 report_event.
        val fixture = """{"id":"evt-1","event_type":"entry","created_at":"2026-10-20T12:00:00Z"}"""
        val (client, interceptor) = apiClient("POST", "/v1/devices/{id}/geofence-events", 201, fixture)
        val result = runBlocking {
            client.reportGeofenceEvent("dev-1", "geo-1", "entry", 52.5, 13.4)
        }
        assertTrue(result.isSuccess)
        assertEquals(Sent("POST", "/v1/devices/dev-1/geofence-events"), interceptor.sent)
    }

    // ── Error contract: every non-2xx body is ApiErrorBody {"error":{"code","message"}} ──

    /**
     * crates/visionpush-api/src/error.rs:177-182 — the ONE shape every error response
     * takes. The OLD decoding handled `{"message":...}` or a bare `{"error":"<code>"}`;
     * neither is real, so `errorCode` held the raw JSON dump of the nested object and
     * [VisionPushError.ApiError.isExternalIdVerificationFailed] only passed by an
     * accidental substring hit on that dump.
     */
    @Test
    fun optIn_rejectsA403WithTheRealNestedErrorBody() {
        // services/identity.rs:62-64 -> ApiError::Forbidden(FAILED) -> error.rs code "forbidden".
        val fixture = """{"error":{"code":"forbidden","message":"external_id_verification_failed"}}"""
        val errorSchema = JSONObject().put("\$ref", "#/components/schemas/ApiErrorBody")
        assertConforms(JSONObject(fixture), errorSchema, "ApiErrorBody")
        val interceptor = OneShotInterceptor(403, fixture)
        val client = ApiClient(config, clientOverride = OkHttpClient.Builder().addInterceptor(interceptor).build())
        val result = runBlocking { client.optIn("dev-1") }
        assertTrue(result.isFailure)
        val error = result.errorOrNull() as VisionPushError.ApiError
        assertEquals(403, error.httpStatus)
        assertEquals("forbidden", error.errorCode)
        assertEquals("external_id_verification_failed", error.message)
        assertTrue(error.isExternalIdVerificationFailed)
    }

    @Test
    fun addUserSubscription_rejectsA409WithTheRealNestedErrorBody() {
        // services/user.rs:936 ApiError::Conflict(...) -> error.rs code "conflict".
        val fixture = """{"error":{"code":"conflict","message":"subscription belongs to another user"}}"""
        val errorSchema = JSONObject().put("\$ref", "#/components/schemas/ApiErrorBody")
        assertConforms(JSONObject(fixture), errorSchema, "ApiErrorBody")
        val interceptor = OneShotInterceptor(409, fixture)
        val client = ApiClient(config, clientOverride = OkHttpClient.Builder().addInterceptor(interceptor).build())
        val result = runBlocking { client.addUserSubscription("dev-1", "sms", "+4915123456789") }
        assertTrue(result.isFailure)
        val error = result.errorOrNull() as VisionPushError.ApiError
        assertEquals(409, error.httpStatus)
        assertEquals("conflict", error.errorCode)
        assertTrue(error.isSubscriptionOwnedByAnotherUser)
    }

    @Test
    fun registerDevice_rejectsA410WithTheRealNestedIdentityErasedBody() {
        // services/user.rs:1298 ApiError::IdentityErased -> error.rs code "identity_erased"
        // (its own dedicated code, unlike the generic "forbidden"/"conflict" above).
        val fixture = """{"error":{"code":"identity_erased","message":"This identity was erased."}}"""
        val errorSchema = JSONObject().put("\$ref", "#/components/schemas/ApiErrorBody")
        assertConforms(JSONObject(fixture), errorSchema, "ApiErrorBody")
        val interceptor = OneShotInterceptor(410, fixture)
        val client = ApiClient(config, clientOverride = OkHttpClient.Builder().addInterceptor(interceptor).build())
        val result = runBlocking { client.registerDevice(registration(externalId = "erased-user")) }
        assertTrue(result.isFailure)
        val error = result.errorOrNull() as VisionPushError.ApiError
        assertEquals(410, error.httpStatus)
        assertEquals("identity_erased", error.errorCode)
        assertTrue(error.isIdentityErased)
    }
}
