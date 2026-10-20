package de.visionlab.visionpush

import android.app.Application
import android.os.Looper
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Public-API coverage for the US-15 user model — [VisionPush.login], [VisionPush.logout],
 * [VisionPush.addEmail], the deprecated [VisionPush.setExternalId]'s delegation, and the
 * [DeviceRegistrar.onUserIdChanged] hook (the mechanism [VisionPush.setOnUserChangedListener]
 * wires to) — driven under Robolectric with a fake transport, the same harness
 * [ConsentGatingTest] and [DeviceRegistrarTest] use.
 *
 * Every async call hops through [ApiClient.execute]'s `withContext(Dispatchers.IO)` onto a
 * REAL background thread (even though the fake interceptor never touches the network), so a
 * single [idle] of the Robolectric main looper can race it: [awaitUntil] polls instead of
 * trusting one `idle()` call to have already caught the posted-back continuation.
 *
 * [setUp] intentionally lets `initializeWithConfig`'s own implicit registration attempt run
 * (with no push token provider installed, so it fails instantly without touching Firebase or
 * the network) and drains it with one [idle] *before* swapping in the fake-server-backed
 * [DeviceRegistrar] / [ApiClient] this test drives — otherwise that implicit attempt would
 * pick up the real token provider installed below and hit the network with it.
 */
@RunWith(RobolectricTestRunner::class)
class UserIdentityTest {

    private lateinit var context: Application
    private lateinit var server: FakeUserServer
    private lateinit var registrar: DeviceRegistrar

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /**
     * Polls [idle] (plus a tiny real sleep between attempts, to give the IO-dispatcher's
     * background thread a chance to post its continuation back onto the main looper) until
     * [condition] holds, or fails after [timeoutMs].
     */
    private fun awaitUntil(timeoutMs: Long = 3_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            idle()
            if (condition()) return
            if (System.nanoTime() > deadline) fail("timed out waiting for the async result")
            Thread.sleep(5)
        }
    }

    /** Triggers [block] with a capturing callback and waits for it to fire. */
    private fun <T> awaitResult(block: ((VisionPushResult<T>) -> Unit) -> Unit): VisionPushResult<T> {
        var result: VisionPushResult<T>? = null
        block { result = it }
        awaitUntil { result != null }
        return requireNotNull(result)
    }

    private data class Sent(val method: String, val path: String, val body: String?)

    /**
     * A minimal US-15 server: `POST .../devices/register` reports a deterministic `user_id`
     * for whatever `external_id` the body carries (so two logins with the same id land on the
     * same user, like the real server), or the current anonymous user when none is carried;
     * `PUT .../dev-123 {clear_external_id:true}` mints a NEW anonymous user for the next
     * register (OneSignal `logout()` semantics). Subscription adds answer [subscriptionStatus]
     * (409 simulates "owned by another user").
     */
    private class FakeUserServer : Interceptor {
        val sent = CopyOnWriteArrayList<Sent>()
        @Volatile var subscriptionStatus = 201
        private var anonymousSeq = 0
        private var currentAnonymousUserId = "anon-0"

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val body = request.body?.let { b -> okio.Buffer().also { b.writeTo(it) }.readUtf8() }
            val path = request.url.encodedPath
            sent.add(Sent(request.method, path, body))

            val (status, responseBody) = when {
                path.endsWith("/devices/register") -> {
                    val externalId = JSONObject(body ?: "{}").optString("external_id").takeIf { it.isNotBlank() }
                    val userId = externalId?.let { "user-for-$it" } ?: currentAnonymousUserId
                    200 to "{\"device_id\":\"dev-123\",\"is_new\":false,\"user_id\":\"$userId\"}"
                }
                request.method == "PUT" && path.endsWith("/devices/dev-123") -> {
                    if (JSONObject(body ?: "{}").optBoolean("clear_external_id", false)) {
                        anonymousSeq++
                        currentAnonymousUserId = "anon-$anonymousSeq"
                    }
                    204 to "{}"
                }
                path.endsWith("/user/subscriptions") ->
                    if (subscriptionStatus == 409) {
                        409 to "{\"error\":{\"code\":\"conflict\",\"message\":\"owned by another user\"}}"
                    } else {
                        subscriptionStatus to "{\"subscription_id\":\"sub-1\"}"
                    }
                else -> 200 to "{}"
            }
            val media = "application/json; charset=utf-8".toMediaType()
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message(if (status in 200..299) "OK" else "Error")
                .body(responseBody.toResponseBody(media))
                .build()
        }
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        VisionPush.resetForTesting()
        VisionPush.pushTokenProvider = { null } // the implicit init registration must not touch Firebase/network
        val cfg = VisionPushConfig(
            appId = "11111111-1111-1111-1111-111111111111",
            apiKey = "vp_test_key",
            apiBaseUrl = "https://api.visionpush.test/v1",
        )
        VisionPush.initializeWithConfig(context, cfg)
        idle() // drains the harmless, provider-less implicit registration

        server = FakeUserServer()
        val client = OkHttpClient.Builder().addInterceptor(server).build()
        registrar = DeviceRegistrar(context, cfg, ApiClient(cfg, clientOverride = client))
        VisionPush.pushTokenProvider = { "tok-1" }
        assertTrue(runBlocking { registrar.register() }.isSuccess)
        assertEquals("anon-0", registrar.userId)

        VisionPush.internalDeviceRegistrar = registrar
        VisionPush.internalApiClient = ApiClient(cfg, clientOverride = client)
    }

    @After
    fun tearDown() {
        VisionPush.resetForTesting()
    }

    // ── login → userId changes ───────────────────────────────────────────────

    @Test
    fun login_changesTheUserIdToTheOneTheServerReports() {
        val result = awaitResult<String?> { cb -> VisionPush.login("user-9", callback = cb) }

        val success = result as VisionPushResult.Success
        assertEquals("user-for-user-9", success.data)
        assertEquals("user-for-user-9", VisionPush.userId)
        assertEquals("user-9", VisionPush.externalId)
        val lastRegister = server.sent.last { it.path.endsWith("/devices/register") }
        assertEquals("user-9", JSONObject(requireNotNull(lastRegister.body)).getString("external_id"))
    }

    // ── logout → a NEW, different anonymous userId, device kept ──────────────

    @Test
    fun logout_assignsABrandNewAnonymousUserId() {
        awaitResult<String?> { cb -> VisionPush.login("user-9", callback = cb) }
        val loggedInUserId = VisionPush.userId

        val result = awaitResult<String?> { cb -> VisionPush.logout(cb) }

        val newUserId = (result as VisionPushResult.Success).data
        assertNotEquals("must not keep the identified user's id", loggedInUserId, newUserId)
        assertNotEquals("must not reuse the original anonymous id either", "anon-0", newUserId)
        assertEquals(newUserId, VisionPush.userId)
        assertNull(VisionPush.externalId)
    }

    @Test
    fun logout_keepsTheDeviceRegisteredAndOnlyChangesTheUserId() {
        awaitResult<String?> { cb -> VisionPush.login("user-9", callback = cb) }
        val deviceIdBeforeLogout = registrar.deviceId
        val loggedInUserId = VisionPush.userId

        awaitResult<String?> { cb -> VisionPush.logout(cb) }

        // The device keeps its id and registered, initialized state — unlike
        // deleteDevice(), logout() never unregisters or resets the SDK.
        assertEquals(deviceIdBeforeLogout, registrar.deviceId)
        assertTrue(
            "logout() must not unregister the device",
            server.sent.none { it.method == "DELETE" }
        )
        assertNotEquals(loggedInUserId, VisionPush.userId)
    }

    // ── the deprecated setExternalId delegates to login()/logout() ───────────

    @Test
    @Suppress("DEPRECATION")
    fun setExternalId_withAnIdDelegatesToLogin() {
        val result = awaitResult<Unit> { cb -> VisionPush.setExternalId("user-9", callback = cb) }

        assertTrue(result is VisionPushResult.Success)
        assertEquals("user-9", VisionPush.externalId)
        assertEquals("user-for-user-9", VisionPush.userId)
    }

    @Test
    @Suppress("DEPRECATION")
    fun setExternalId_withNullDelegatesToLogout() {
        awaitResult<Unit> { cb -> VisionPush.setExternalId("user-9", callback = cb) }
        val loggedInUserId = VisionPush.userId

        val result = awaitResult<Unit> { cb -> VisionPush.setExternalId(null, callback = cb) }

        assertTrue(result is VisionPushResult.Success)
        assertNull(VisionPush.externalId)
        assertNotEquals(loggedInUserId, VisionPush.userId)
        assertTrue(
            "logout()'s clear_external_id PUT must have been sent",
            server.sent.any {
                it.method == "PUT" && it.path.endsWith("/devices/dev-123") &&
                    JSONObject(requireNotNull(it.body)).optBoolean("clear_external_id")
            }
        )
    }

    // ── addEmail: a 409 becomes a typed error ─────────────────────────────────

    @Test
    fun addEmail_surfacesA409AsATypedSubscriptionConflictError() {
        server.subscriptionStatus = 409

        val result = awaitResult<Unit> { cb -> VisionPush.addEmail("taken@example.com", cb) }

        val failure = result as VisionPushResult.Failure
        val error = failure.error as VisionPushError.ApiError
        assertEquals(409, error.httpStatus)
        assertTrue(error.isSubscriptionOwnedByAnotherUser)
    }

    @Test
    fun addEmail_succeedsAndSendsTheLowercasedTrimmedAddress() {
        val result = awaitResult<Unit> { cb -> VisionPush.addEmail(" User@Example.com ", cb) }

        assertTrue(result is VisionPushResult.Success)
        val call = server.sent.last { it.path.endsWith("/user/subscriptions") }
        val body = JSONObject(requireNotNull(call.body))
        assertEquals("email", body.getString("type"))
        assertEquals("user@example.com", body.getString("token"))
    }

    // ── the user-change hook fires on login/logout, not on a no-op re-register ─

    @Test
    fun onUserIdChangedHookFiresOnlyWhenTheUserIdActuallyChanges() {
        // The exact hook VisionPush.initializeWithConfig wires to the public
        // setOnUserChangedListener; exercised directly since this test swaps in
        // its own registrar after that one-time wiring already ran (see setUp).
        val changes = mutableListOf<String?>()
        registrar.onUserIdChanged = { changes.add(it) }

        awaitResult<String?> { cb -> VisionPush.login("user-9", callback = cb) }
        assertEquals(listOf("user-for-user-9"), changes)

        // Forcing a fresh register with the SAME external id gets the SAME user_id
        // back — must not re-fire.
        awaitResult<String> { cb -> VisionPush.syncDevice(cb) }
        assertEquals("re-registering as the same user must not re-fire the listener", 1, changes.size)

        awaitResult<String?> { cb -> VisionPush.logout(cb) }
        assertEquals(2, changes.size)
        assertEquals(registrar.userId, changes.last())
        assertNotEquals(changes[0], changes[1])
    }
}
