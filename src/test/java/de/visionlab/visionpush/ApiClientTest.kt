package de.visionlab.visionpush

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [ApiClient]'s retry / exponential-backoff logic.
 *
 * They inject an [OkHttpClient] whose single interceptor short-circuits the network
 * with canned responses (or throws [IOException]), so the full retry decision tree in
 * [ApiClient.execute] is exercised without any real I/O. Robolectric provides a working
 * `android.util.Log` for [VisionPushLogger] — the same runner convention used by
 * [RealtimeChannelTest].
 *
 * Note: [ApiClient.execute] uses a real `delay()` between retries (500ms → 1s → 2s,
 * 2s floor for 429), so the retry-exhaustion tests intentionally take a few seconds.
 */
@RunWith(RobolectricTestRunner::class)
class ApiClientTest {

    private val config = VisionPushConfig(
        appId = "11111111-1111-1111-1111-111111111111",
        apiKey = "vp_test_key",
        apiBaseUrl = "https://api.visionpush.test/v1",
    )

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // ── Canned-response interceptor ──────────────────────────────────────────

    /**
     * One captured outbound request: method, URL path, the JSON body as sent,
     * and the headers actually on the wire (the auth interceptor runs before
     * this fake, so what it added is visible here).
     */
    private data class Sent(
        val method: String,
        val path: String,
        val body: String?,
        val headers: Map<String, String> = emptyMap(),
    )

    /**
     * Returns [statusSequence] one entry per attempt; when the sequence is exhausted the
     * last entry repeats. A status of -1 means "throw IOException" (network failure).
     * [retryAfterSequence] optionally adds a `Retry-After` header per attempt.
     * Each call increments [calls] so tests can assert the exact attempt count, and
     * every request is recorded in [sent] so payloads can be asserted per attempt.
     */
    private class FakeInterceptor(
        private val statusSequence: List<Int>,
        private val bodyJson: String = "{\"device_id\":\"dev-xyz\"}",
        private val retryAfterSequence: List<String?> = emptyList(),
    ) : Interceptor {
        val calls = AtomicInteger(0)
        val sent = CopyOnWriteArrayList<Sent>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val index = calls.getAndIncrement()
            val request = chain.request()
            val body = request.body?.let { b -> okio.Buffer().also { b.writeTo(it) }.readUtf8() }
            sent.add(
                Sent(
                    request.method,
                    request.url.encodedPath,
                    body,
                    request.headers.toMap(),
                )
            )
            val status = statusSequence.getOrElse(index) { statusSequence.last() }
            if (status < 0) throw IOException("simulated network failure")
            val media = "application/json; charset=utf-8".toMediaType()
            val builder = Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message(if (status in 200..299) "OK" else "Error")
                .body(bodyJson.toResponseBody(media))
            retryAfterSequence.getOrNull(index)?.let { builder.header("Retry-After", it) }
            return builder.build()
        }
    }

    private fun clientWith(interceptor: FakeInterceptor): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(interceptor).build()

    private fun apiClient(interceptor: FakeInterceptor): ApiClient =
        ApiClient(config, clientOverride = clientWith(interceptor))

    private fun registration(): DeviceRegistration = DeviceRegistration(
        appId = config.appId,
        pushToken = "tok-1",
        platform = "android",
        externalId = null,
        installationId = "inst-1",
        anonymousId = "inst-1",
        locale = "en-US",
        country = "US",
        deviceOs = "Android 14",
        deviceModel = "Pixel",
        appVersion = "1.0",
        timezone = "UTC",
        timezoneOffset = 0,
        environment = "sandbox",
        bundleId = "com.example",
        appBuildNumber = "1",
        deviceType = "phone",
        deviceManufacturer = "Google",
        networkType = "wifi",
        notificationPermission = "granted",
        tags = null,
        sealedPubkey = "pk",
        sealedKeyAlg = "alg",
        sealedAuth = "auth",
    )

    // ── Success on first attempt ─────────────────────────────────────────────

    @Test
    fun succeedsOnFirstAttemptWithoutRetry() {
        val interceptor = FakeInterceptor(listOf(200))
        val result = runBlocking { apiClient(interceptor).registerDevice(registration()) }
        assertTrue(result.isSuccess)
        assertEquals("dev-xyz", (result as VisionPushResult.Success).data.deviceId)
        assertEquals(1, interceptor.calls.get())
    }

    // ── 5xx retries then succeeds ────────────────────────────────────────────

    @Test
    fun retriesOn500ThenSucceeds() {
        val interceptor = FakeInterceptor(listOf(500, 503, 200))
        val result = runBlocking { apiClient(interceptor).registerDevice(registration()) }
        assertTrue(result.isSuccess)
        assertEquals(3, interceptor.calls.get())
    }

    // ── 429 retries then succeeds ────────────────────────────────────────────

    @Test
    fun retriesOn429ThenSucceeds() {
        val interceptor = FakeInterceptor(listOf(429, 200))
        val result = runBlocking { apiClient(interceptor).registerDevice(registration()) }
        assertTrue(result.isSuccess)
        assertEquals(2, interceptor.calls.get())
    }

    // ── IOException retries up to MAX_RETRIES then fails ──────────────────────

    @Test
    fun retriesOnIOExceptionAndExhaustsThenFailsWithNetworkError() {
        // -1 => throw IOException on every attempt: 1 initial + 3 retries = 4 calls.
        val interceptor = FakeInterceptor(listOf(-1))
        val result = runBlocking { apiClient(interceptor).registerDevice(registration()) }
        assertTrue(result.isFailure)
        assertTrue(result.errorOrNull() is VisionPushError.NetworkError)
        assertEquals(4, interceptor.calls.get())
    }

    // ── 5xx exhausts retries then fails with ApiError ────────────────────────

    @Test
    fun exhaustsRetriesOnPersistent500AndReturnsApiError() {
        val interceptor = FakeInterceptor(listOf(500))
        val result = runBlocking { apiClient(interceptor).registerDevice(registration()) }
        assertTrue(result.isFailure)
        val error = result.errorOrNull()
        assertTrue(error is VisionPushError.ApiError)
        assertEquals(500, (error as VisionPushError.ApiError).httpStatus)
        // 1 initial + 3 retries = 4 attempts.
        assertEquals(4, interceptor.calls.get())
    }

    // ── 4xx (non-429) is NOT retried ─────────────────────────────────────────

    @Test
    fun doesNotRetryOn400() {
        val interceptor = FakeInterceptor(listOf(400, 200))
        val result = runBlocking { apiClient(interceptor).registerDevice(registration()) }
        assertTrue(result.isFailure)
        assertEquals(400, (result.errorOrNull() as VisionPushError.ApiError).httpStatus)
        // No retry: exactly one attempt.
        assertEquals(1, interceptor.calls.get())
    }

    @Test
    fun doesNotRetryOn404() {
        val interceptor = FakeInterceptor(listOf(404))
        val result = runBlocking { apiClient(interceptor).trackEvent(
            NotificationEvent(
                notificationId = "n1",
                eventType = NotificationEvent.EventType.DELIVERED,
                actionId = null,
                campaignId = null,
                deviceId = "dev-1",
            )
        ) }
        assertTrue(result.isFailure)
        assertFalse(result.isSuccess)
        assertEquals(1, interceptor.calls.get())
    }

    // ── Retry-After is honoured (ports the iOS behaviour) ─────────────────────

    @Test
    fun honoursRetryAfterOn429InsteadOfTheTwoSecondFloor() {
        // Without the header a 429 waits the 2 s floor; "Retry-After: 1" must win.
        val interceptor = FakeInterceptor(listOf(429, 200), retryAfterSequence = listOf("1"))
        val started = System.nanoTime()
        val result = runBlocking { apiClient(interceptor).registerDevice(registration()) }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(result.isSuccess)
        assertEquals(2, interceptor.calls.get())
        assertTrue("elapsed=${elapsedMs}ms — expected ≈1 s (header), not ≥2 s (floor)", elapsedMs in 900..1900)
    }

    @Test
    fun honoursRetryAfterOn503() {
        // A 503 would normally wait the jittered 500 ms base; "Retry-After: 1" must win.
        val interceptor = FakeInterceptor(listOf(503, 200), retryAfterSequence = listOf("1"))
        val started = System.nanoTime()
        val result = runBlocking { apiClient(interceptor).registerDevice(registration()) }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(result.isSuccess)
        assertTrue("elapsed=${elapsedMs}ms", elapsedMs in 900..1900)
    }

    // ── Outcomes carry ONE idempotency key across all retries ─────────────────

    @Test
    fun sendOutcome_reusesTheSameIdempotencyKeyOnEveryRetry() {
        val interceptor = FakeInterceptor(listOf(500, -1, 200))
        val result = runBlocking { apiClient(interceptor).sendOutcome("dev-1", "purchase", 9.99f) }
        assertTrue(result.isSuccess)
        assertEquals(3, interceptor.sent.size)

        val bodies = interceptor.sent.map { JSONObject(requireNotNull(it.body)) }
        val keys = bodies.map { it.getString("idempotency_key") }.toSet()
        assertEquals("all attempts must carry the same key: $keys", 1, keys.size)
        val key = keys.single()
        assertTrue("key must be 1–64 chars, was ${key.length}", key.length in 1..64)
        for (s in interceptor.sent) {
            assertEquals("POST", s.method)
            assertEquals("/v1/devices/dev-1/outcomes", s.path)
        }
        assertEquals("purchase", bodies[0].getString("name"))
        assertEquals(9.99, bodies[0].getDouble("value"), 0.001)
    }

    @Test
    fun sendOutcome_generatesADistinctKeyPerCall() {
        val interceptor = FakeInterceptor(listOf(200))
        runBlocking {
            apiClient(interceptor).sendOutcome("dev-1", "a")
            apiClient(interceptor).sendOutcome("dev-1", "a")
        }
        val keys = interceptor.sent.map { JSONObject(requireNotNull(it.body)).getString("idempotency_key") }
        assertEquals(2, keys.size)
        assertNotEquals(keys[0], keys[1])
        assertTrue(JSONObject(requireNotNull(interceptor.sent[0].body)).isNull("value"))
    }

    @Test
    fun sendOutcome_acceptsAnExplicitKeyAndRejectsAnOversizedOne() {
        val interceptor = FakeInterceptor(listOf(200))
        runBlocking { apiClient(interceptor).sendOutcome("dev-1", "a", idempotencyKey = "order-42") }
        assertEquals("order-42", JSONObject(requireNotNull(interceptor.sent[0].body)).getString("idempotency_key"))
        try {
            runBlocking { apiClient(interceptor).sendOutcome("dev-1", "a", idempotencyKey = "k".repeat(65)) }
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    // ── 403 external_id_verification_failed: deterministic, no retry, code surfaced ──

    @Test
    fun verificationFailure403IsNotRetriedAndSurfacesTheErrorCode() {
        // The real shape (crates/visionpush-api/src/error.rs:177-182): the server
        // ALWAYS nests {"error":{"code","message"}} — never a bare {"error":"<code>"}
        // string. `code` is the generic "forbidden" category here (`ApiError::Forbidden`
        // is a catch-all); the specific identifier is in `message`
        // (services/identity.rs:62-64).
        // A 200 is queued behind the 403 — it must never be reached.
        val interceptor = FakeInterceptor(
            listOf(403, 200),
            bodyJson = "{\"error\":{\"code\":\"forbidden\",\"message\":\"external_id_verification_failed\"}}",
        )
        val result = runBlocking {
            apiClient(interceptor).registerDevice(registration().copy(externalId = "victim"))
        }
        assertTrue(result.isFailure)
        val error = result.errorOrNull() as VisionPushError.ApiError
        assertEquals(403, error.httpStatus)
        assertEquals("forbidden", error.errorCode)
        assertEquals("external_id_verification_failed", error.message)
        assertTrue(error.isExternalIdVerificationFailed)
        assertEquals("a verification failure must not be retried", 1, interceptor.calls.get())
    }

    @Test
    fun identityErased410IsRecognizedByItsOwnDedicatedCode() {
        // Unlike external_id_verification_failed, this rejection DOES get its own code
        // (`ApiError::IdentityErased` — error.rs:132-136), not the generic "forbidden".
        val interceptor = FakeInterceptor(
            listOf(410),
            bodyJson = "{\"error\":{\"code\":\"identity_erased\",\"message\":\"This identity was erased.\"}}",
        )
        val result = runBlocking {
            apiClient(interceptor).registerDevice(registration().copy(externalId = "erased-user"))
        }
        assertTrue(result.isFailure)
        val error = result.errorOrNull() as VisionPushError.ApiError
        assertEquals(410, error.httpStatus)
        assertEquals("identity_erased", error.errorCode)
        assertTrue(error.isIdentityErased)
        assertEquals("a 410 must not be retried", 1, interceptor.calls.get())
    }

    @Test
    fun registrationJsonCarriesTheHashOnlyNextToAnExternalId() {
        val hash = "ab".repeat(32)
        val with = registration().copy(externalId = "user-1", externalIdHash = hash).toJson()
        assertEquals("user-1", with.getString("external_id"))
        assertEquals(hash, with.getString("external_id_hash"))

        val without = registration().copy(externalId = "user-1").toJson()
        assertEquals("user-1", without.getString("external_id"))
        assertFalse(without.has("external_id_hash"))

        val orphan = registration().copy(externalId = null, externalIdHash = hash).toJson()
        assertFalse(orphan.has("external_id"))
        assertFalse("a hash is bound to an id", orphan.has("external_id_hash"))
    }

    // ── clear_external_id is an explicit PUT ──────────────────────────────────

    @Test
    fun clearExternalId_sendsPutWithClearFlag() {
        val interceptor = FakeInterceptor(listOf(200))
        val result = runBlocking { apiClient(interceptor).clearExternalId("dev-1") }
        assertTrue(result.isSuccess)
        val s = interceptor.sent.single()
        assertEquals("PUT", s.method)
        assertEquals("/v1/devices/dev-1", s.path)
        val body = JSONObject(requireNotNull(s.body))
        assertTrue(body.getBoolean("clear_external_id"))
        assertFalse("must not also send external_id", body.has("external_id"))
    }

    // ── device binding ────────────────────────────────────────────────────────

    @Test
    fun appIdHeaderUsesTheSameNameAsTheOtherSdks() {
        // Nothing asserted this before, which is how Android came to send
        // `X-App-Id` while iOS and the web SDK send `X-VisionPush-App-Id`. The
        // server reads neither — it authorises from the Bearer key — so the
        // divergence was invisible in every test AND on the wire, and would only
        // have surfaced as Android looking like a different client to a WAF rule
        // or an access-log dashboard.
        val interceptor = FakeInterceptor(listOf(200))
        assertTrue(runBlocking { apiClient(interceptor).optOut("dev-1") }.isSuccess)
        val sent = interceptor.sent.single()
        assertEquals(config.appId, sent.headers["X-VisionPush-App-Id"])
        assertFalse(sent.headers.containsKey("X-App-Id"))
    }

    @Test
    fun installationId_isSentOnEveryRequestOnceKnown() {
        // The client key is extractable from the APK, so it only proves WHICH
        // APP is calling. Without this header on the wire the server cannot stop
        // one user acting on another user's device by its UUID.
        val interceptor = FakeInterceptor(listOf(200, 200))
        val client = apiClient(interceptor)
        client.installationId = "inst-abc"

        assertTrue(runBlocking { client.optOut("dev-1") }.isSuccess)
        assertTrue(runBlocking { client.clearExternalId("dev-1") }.isSuccess)

        assertEquals(2, interceptor.sent.size)
        interceptor.sent.forEach { sent ->
            assertEquals("inst-abc", sent.headers["X-VisionPush-Installation-Id"])
            assertEquals("Bearer ${config.apiKey}", sent.headers["Authorization"])
        }
    }

    @Test
    fun installationId_isOmittedUntilOneExistsAndAfterLogout() {
        // It is minted on registration, never on a bare API call, and clearing
        // local state wipes it — a blank header would be a claim the SDK cannot
        // back.
        val interceptor = FakeInterceptor(listOf(200, 200, 200))
        val client = apiClient(interceptor)

        assertTrue(runBlocking { client.optOut("dev-1") }.isSuccess)
        client.installationId = "   "
        assertTrue(runBlocking { client.optOut("dev-1") }.isSuccess)
        client.installationId = "inst-abc"
        client.installationId = null
        assertTrue(runBlocking { client.optOut("dev-1") }.isSuccess)

        assertEquals(3, interceptor.sent.size)
        interceptor.sent.forEach { sent ->
            assertFalse(sent.headers.containsKey("X-VisionPush-Installation-Id"))
        }
    }

    // ── US-15 user model ──────────────────────────────────────────────────────

    @Test
    fun registerDevice_parsesTheUserIdFromTheResponse() {
        val interceptor = FakeInterceptor(
            listOf(200),
            bodyJson = "{\"device_id\":\"dev-xyz\",\"is_new\":true,\"user_id\":\"user-abc\"}",
        )
        val result = runBlocking { apiClient(interceptor).registerDevice(registration()) }
        val outcome = (result as VisionPushResult.Success).data
        assertEquals("dev-xyz", outcome.deviceId)
        assertEquals("user-abc", outcome.userId)
    }

    @Test
    fun registerDevice_userIdIsNullWhenTheResponseOmitsIt() {
        val interceptor = FakeInterceptor(listOf(200), bodyJson = "{\"device_id\":\"dev-xyz\"}")
        val result = runBlocking { apiClient(interceptor).registerDevice(registration()) }
        assertNull((result as VisionPushResult.Success).data.userId)
    }

    @Test
    fun addUserAlias_sendsThePostWithLabelIdAndHash() {
        val interceptor = FakeInterceptor(listOf(200))
        val result = runBlocking { apiClient(interceptor).addUserAlias("dev-1", "loyalty_card", "LC-1", "ab".repeat(32)) }
        assertTrue(result.isSuccess)
        val s = interceptor.sent.single()
        assertEquals("POST", s.method)
        assertEquals("/v1/devices/dev-1/user/aliases", s.path)
        val body = JSONObject(requireNotNull(s.body))
        assertEquals("loyalty_card", body.getString("label"))
        assertEquals("LC-1", body.getString("alias_id"))
        assertEquals("ab".repeat(32), body.getString("alias_hash"))
    }

    @Test
    fun removeUserAlias_sendsTheDeleteWithLabelIdAndHash() {
        val interceptor = FakeInterceptor(listOf(200))
        val result = runBlocking { apiClient(interceptor).removeUserAlias("dev-1", "loyalty_card", "LC-1", "ab".repeat(32)) }
        assertTrue(result.isSuccess)
        val s = interceptor.sent.single()
        assertEquals("DELETE", s.method)
        assertEquals("/v1/devices/dev-1/user/aliases", s.path)
        val body = JSONObject(requireNotNull(s.body))
        assertEquals("loyalty_card", body.getString("label"))
        assertEquals("LC-1", body.getString("alias_id"))
    }

    @Test
    fun addUserSubscription_sendsTypeAndToken() {
        val interceptor = FakeInterceptor(listOf(201), bodyJson = "{\"subscription_id\":\"sub-1\"}")
        val result = runBlocking { apiClient(interceptor).addUserSubscription("dev-1", "email", "user@example.com") }
        assertTrue(result.isSuccess)
        val s = interceptor.sent.single()
        assertEquals("POST", s.method)
        assertEquals("/v1/devices/dev-1/user/subscriptions", s.path)
        val body = JSONObject(requireNotNull(s.body))
        assertEquals("email", body.getString("type"))
        assertEquals("user@example.com", body.getString("token"))
    }

    @Test
    fun addUserSubscription_surfacesA409AsATypedConflictError() {
        val interceptor = FakeInterceptor(
            listOf(409),
            bodyJson = "{\"error\":{\"code\":\"conflict\",\"message\":\"owned by another user\"}}",
        )
        val result = runBlocking { apiClient(interceptor).addUserSubscription("dev-1", "sms", "+4915123456789") }
        assertTrue(result.isFailure)
        val error = result.errorOrNull() as VisionPushError.ApiError
        assertEquals(409, error.httpStatus)
        assertTrue(error.isSubscriptionOwnedByAnotherUser)
        assertEquals("a 409 must not be retried", 1, interceptor.calls.get())
    }

    @Test
    fun updateDeviceTags_sendsAPatchToTheUserRouteNotTheDeviceRoute() {
        val interceptor = FakeInterceptor(listOf(200))
        val result = runBlocking { apiClient(interceptor).updateDeviceTags("dev-1", mapOf("plan" to "pro")) }
        assertTrue(result.isSuccess)
        val s = interceptor.sent.single()
        assertEquals("PATCH", s.method)
        assertEquals("/v1/devices/dev-1/user", s.path)
        assertEquals("pro", JSONObject(requireNotNull(s.body)).getJSONObject("tags").getString("plan"))
    }

    @Test
    fun getDeviceTags_readsTheFlatTagsObjectFromTheUserRouteNotADataEnvelope() {
        // Real shape of GET /v1/devices/{id}/user (services/user.rs:633-640
        // UserService::get_device_user): tags is a TOP-LEVEL key, not wrapped in
        // {"data": {...}} — the old GET /v1/devices/{id} route this used to call (and
        // which a client key cannot even read) never had the "data" shape either, so
        // the previous decoding always returned an empty map.
        val interceptor = FakeInterceptor(
            listOf(200),
            bodyJson = "{\"user_id\":\"user-1\",\"tags\":{\"plan\":\"pro\",\"level\":5}," +
                "\"language\":\"de\",\"country\":\"DE\",\"aliases\":{},\"subscriptions\":[]}",
        )
        val result = runBlocking { apiClient(interceptor).getDeviceTags("dev-1") }
        assertTrue(result.isSuccess)
        val tags = (result as VisionPushResult.Success).data
        assertEquals(mapOf("plan" to "pro", "level" to "5"), tags)
        val s = interceptor.sent.single()
        assertEquals("GET", s.method)
        assertEquals("/v1/devices/dev-1/user", s.path)
    }

    @Test
    fun removeDeviceTags_sendsAPatchToTheUserRouteNotTheDeviceRoute() {
        val interceptor = FakeInterceptor(listOf(200))
        val result = runBlocking { apiClient(interceptor).removeDeviceTags("dev-1", listOf("plan")) }
        assertTrue(result.isSuccess)
        val s = interceptor.sent.single()
        assertEquals("PATCH", s.method)
        assertEquals("/v1/devices/dev-1/user", s.path)
        val arr = JSONObject(requireNotNull(s.body)).getJSONArray("tags_to_remove")
        assertEquals("plan", arr.getString(0))
    }
}
