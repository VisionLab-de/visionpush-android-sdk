package de.visionlab.visionpush

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [DeviceRegistrar]'s state-key change-detection and token-rotation logic.
 *
 * A [VisionPush.pushTokenProvider] override supplies a controllable push token so Firebase
 * is never touched, and a fake [Interceptor] counts the device-register API calls. The
 * registrar persists to (plain, under Robolectric) SharedPreferences, so a fresh
 * `applicationContext` per test isolates state.
 */
@RunWith(RobolectricTestRunner::class)
class DeviceRegistrarTest {

    private val config = VisionPushConfig(
        appId = "11111111-1111-1111-1111-111111111111",
        apiKey = "vp_test_key",
        apiBaseUrl = "https://api.visionpush.test/v1",
    )

    /** Token returned by the installed [VisionPush.pushTokenProvider]. Mutable to simulate rotation. */
    @Volatile private var currentToken: String? = "tok-A"

    /** One captured outbound request. */
    private data class Sent(val method: String, val path: String, val body: String?)

    /**
     * Counts every device-register HTTP request the registrar issues and records ALL
     * requests in order. [putStatus] lets a test fail the `PUT /devices/{id}` (external-id
     * clear) with a non-retryable status; [registerStatus] does the same for
     * `POST /devices/register` — a 403 answers with the server's identity-verification
     * rejection body.
     */
    private class CountingInterceptor : Interceptor {
        val registerCalls = AtomicInteger(0)
        val sent = CopyOnWriteArrayList<Sent>()
        @Volatile var putStatus = 200
        @Volatile var registerStatus = 200

        val optOutCalls: Int get() = sent.count { it.method == "POST" && it.path.endsWith("/opt-out") }
        val clearCalls: List<Sent> get() = sent.filter { it.method == "PUT" && it.path.endsWith("/devices/dev-123") }

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val isRegister = request.url.encodedPath.endsWith("/devices/register")
            if (isRegister) registerCalls.incrementAndGet()
            val body = request.body?.let { b -> okio.Buffer().also { b.writeTo(it) }.readUtf8() }
            sent.add(Sent(request.method, request.url.encodedPath, body))
            val status = when {
                request.method == "PUT" -> putStatus
                isRegister -> registerStatus
                else -> 200
            }
            val responseBody = if (status == 403) {
                "{\"error\":\"external_id_verification_failed\"}"
            } else {
                "{\"device_id\":\"dev-123\"}"
            }
            val media = "application/json; charset=utf-8".toMediaType()
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message(if (status == 200) "OK" else "Error")
                .body(responseBody.toResponseBody(media))
                .build()
        }
    }

    private lateinit var interceptor: CountingInterceptor

    private val context: android.content.Context
        // RuntimeEnvironment ships with Robolectric — no extra androidx.test:core dep.
        get() = RuntimeEnvironment.getApplication()

    private fun newRegistrar(): DeviceRegistrar {
        interceptor = CountingInterceptor()
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val apiClient = ApiClient(config, clientOverride = client)
        return DeviceRegistrar(context, config, apiClient)
    }

    @Before
    fun installTokenProvider() {
        currentToken = "tok-A"
        VisionPush.pushTokenProvider = { currentToken }
    }

    @After
    fun clearTokenProvider() {
        VisionPush.pushTokenProvider = null
    }

    // ── First registration ───────────────────────────────────────────────────

    @Test
    fun firstRegisterCallsBackendAndStoresDeviceId() {
        val registrar = newRegistrar()
        val result = runBlocking { registrar.register() }
        assertTrue(result.isSuccess)
        assertEquals("dev-123", registrar.deviceId)
        assertEquals(1, interceptor.registerCalls.get())
    }

    // ── Unchanged state is skipped ───────────────────────────────────────────

    @Test
    fun secondRegisterWithUnchangedStateIsSkipped() {
        val registrar = newRegistrar()
        runBlocking {
            registrar.register()
            registrar.register() // identical token + state → must be skipped
        }
        assertEquals("only the first register should hit the backend", 1, interceptor.registerCalls.get())
    }

    // ── forceRefresh always re-registers ─────────────────────────────────────

    @Test
    fun forceRefreshAlwaysReRegisters() {
        val registrar = newRegistrar()
        runBlocking {
            registrar.register()
            registrar.register(forceRefresh = true)
        }
        assertEquals(2, interceptor.registerCalls.get())
    }

    // ── Token rotation triggers a fresh registration ─────────────────────────

    @Test
    fun tokenRotationTriggersNewRegistration() {
        val registrar = newRegistrar()
        runBlocking {
            registrar.register()              // token A
            currentToken = "tok-B"            // rotate
            registrar.register()              // token changed → not skipped
        }
        assertEquals(2, interceptor.registerCalls.get())
    }

    // ── State-key change (externalId) triggers a fresh registration ──────────

    @Test
    fun externalIdChangeTriggersNewRegistration() {
        val registrar = newRegistrar()
        runBlocking {
            registrar.register()              // baseline state
            registrar.externalId = "user-9"   // state-key component changes
            registrar.register()              // state changed → not skipped
        }
        assertEquals(2, interceptor.registerCalls.get())
    }

    // ── handleTokenRefresh forces a re-register and updates stored token ──────

    @Test
    fun handleTokenRefreshReRegistersWithNewToken() {
        val registrar = newRegistrar()
        runBlocking {
            registrar.register()
            val refreshed = registrar.handleTokenRefresh("tok-REFRESHED")
            assertTrue(refreshed.isSuccess)
        }
        assertEquals("tok-REFRESHED", registrar.lastRegisteredToken)
        assertEquals(2, interceptor.registerCalls.get())
    }

    // ── unregister clears local state ────────────────────────────────────────

    @Test
    fun unregisterDeletesAndClearsLocalState() {
        val registrar = newRegistrar()
        runBlocking {
            registrar.register()
            val result = registrar.unregister()
            assertTrue(result.isSuccess)
        }
        assertNull(registrar.deviceId)
        assertNull(registrar.lastRegisteredToken)
    }

    // ── No token available → TokenUnavailable failure, no backend call ───────

    @Test
    fun registerWithoutTokenReturnsTokenUnavailable() {
        currentToken = null
        val registrar = newRegistrar()
        val result = runBlocking { registrar.register() }
        assertTrue(result.isFailure)
        assertTrue(result.errorOrNull() is VisionPushError.TokenUnavailable)
        assertEquals(0, interceptor.registerCalls.get())
    }

    // ── setExternalId(null) is an explicit clear, not an omission ─────────────

    @Test
    fun clearingExternalIdSendsExplicitClearPutBeforeReRegistering() {
        val registrar = newRegistrar()
        runBlocking {
            registrar.register()
            registrar.externalId = "user-9"
            registrar.register()
        }
        assertEquals(2, interceptor.registerCalls.get())
        assertTrue(interceptor.clearCalls.isEmpty())
        // The upsert payload carried the id …
        assertEquals("user-9", JSONObject(requireNotNull(interceptor.sent.last().body)).getString("external_id"))

        registrar.externalId = null
        assertTrue(registrar.isExternalIdClearPending)
        val before = interceptor.sent.size
        runBlocking { registrar.register() }

        // … and clearing it is a dedicated PUT {"clear_external_id":true} sent BEFORE the
        // register POST (whose payload merely omits external_id = "unchanged").
        val afterClear = interceptor.sent.drop(before)
        assertEquals(listOf("PUT", "POST"), afterClear.map { it.method })
        assertEquals("/v1/devices/dev-123", afterClear[0].path)
        val clearBody = JSONObject(requireNotNull(afterClear[0].body))
        assertTrue(clearBody.getBoolean("clear_external_id"))
        assertFalse(clearBody.has("external_id"))
        assertFalse(JSONObject(requireNotNull(afterClear[1].body)).has("external_id"))
        assertFalse(registrar.isExternalIdClearPending)
        assertNull(registrar.externalId)
    }

    @Test
    fun clearBeforeAnyRegistrationHasNothingToClearOnTheServer() {
        val registrar = newRegistrar()
        registrar.externalId = "user-1"
        registrar.externalId = null
        assertTrue(registrar.isExternalIdClearPending)
        runBlocking { registrar.register() }
        assertTrue("no device id existed → no PUT", interceptor.clearCalls.isEmpty())
        assertFalse(registrar.isExternalIdClearPending)
        assertEquals(1, interceptor.registerCalls.get())
    }

    @Test
    fun failedClearStaysPendingAndIsRetriedOnTheNextSync() {
        val registrar = newRegistrar()
        runBlocking {
            registrar.register()
            registrar.externalId = "user-9"
            registrar.register()
        }
        interceptor.putStatus = 400 // non-retryable → immediate failure, no back-off delay
        registrar.externalId = null
        runBlocking { registrar.register() }
        assertEquals(1, interceptor.clearCalls.size)
        assertTrue("a failed clear must stay pending", registrar.isExternalIdClearPending)

        interceptor.putStatus = 200
        runBlocking { registrar.register(forceRefresh = true) }
        assertEquals(2, interceptor.clearCalls.size)
        assertFalse(registrar.isExternalIdClearPending)
    }

    @Test
    fun settingANewExternalIdCancelsAPendingClear() {
        val registrar = newRegistrar()
        runBlocking {
            registrar.register()
            registrar.externalId = "user-9"
            registrar.register()
        }
        registrar.externalId = null
        registrar.externalId = "user-10"
        assertFalse(registrar.isExternalIdClearPending)
        runBlocking { registrar.register() }
        assertTrue(interceptor.clearCalls.isEmpty())
        assertEquals("user-10", JSONObject(requireNotNull(interceptor.sent.last().body)).getString("external_id"))
    }

    // ── Identity verification: external_id_hash ───────────────────────────────

    /** A syntactically valid identity-verification hash (64 hex chars). */
    private val hash = "ab".repeat(32)

    private fun lastRegisterBody(): JSONObject =
        JSONObject(requireNotNull(interceptor.sent.last { it.path.endsWith("/devices/register") }.body))

    @Test
    fun registrationPayloadCarriesTheHashNextToTheExternalId() {
        val registrar = newRegistrar()
        val result = runBlocking { registrar.updateExternalIdentity("user-9", hash) }
        assertTrue(result.isSuccess)
        assertEquals("user-9", registrar.externalId)
        assertEquals("stored next to the id", hash, registrar.externalIdHash)
        val body = lastRegisterBody()
        assertEquals("user-9", body.getString("external_id"))
        assertEquals(hash, body.getString("external_id_hash"))
    }

    @Test
    fun registrationPayloadOmitsTheHashWhenNoneWasGiven() {
        val registrar = newRegistrar()
        runBlocking { registrar.updateExternalIdentity("user-9", null) }
        assertNull(registrar.externalIdHash)
        val body = lastRegisterBody()
        assertEquals("user-9", body.getString("external_id"))
        assertFalse(body.has("external_id_hash"))
    }

    @Test
    fun settingTheIdWithoutAHashDropsAPreviouslyStoredHash() {
        val registrar = newRegistrar()
        runBlocking {
            registrar.updateExternalIdentity("user-9", hash)
            registrar.updateExternalIdentity("user-10", null)
        }
        assertEquals("user-10", registrar.externalId)
        assertNull("a hash is bound to one id — never kept for another", registrar.externalIdHash)
        assertFalse(lastRegisterBody().has("external_id_hash"))
    }

    @Test
    fun clearingTheExternalIdClearsTheHashAndNeverSendsOneWithTheClear() {
        val registrar = newRegistrar()
        runBlocking { registrar.updateExternalIdentity("user-9", hash) }
        val before = interceptor.sent.size

        runBlocking { registrar.updateExternalIdentity(null, hash) }

        assertNull(registrar.externalId)
        assertNull(registrar.externalIdHash)
        val afterClear = interceptor.sent.drop(before)
        assertEquals(listOf("PUT", "POST"), afterClear.map { it.method })
        val clearBody = JSONObject(requireNotNull(afterClear[0].body))
        assertTrue(clearBody.getBoolean("clear_external_id"))
        assertFalse("clearing needs no hash", clearBody.has("external_id_hash"))
        val registerBody = JSONObject(requireNotNull(afterClear[1].body))
        assertFalse(registerBody.has("external_id"))
        assertFalse(registerBody.has("external_id_hash"))
    }

    @Test
    fun verificationFailureIsNotRetriedAndRestoresThePreviousIdentity() {
        val registrar = newRegistrar()
        val previousHash = "cd".repeat(32)
        runBlocking { assertTrue(registrar.updateExternalIdentity("user-old", previousHash).isSuccess) }
        assertEquals(1, interceptor.registerCalls.get())

        interceptor.registerStatus = 403
        val result = runBlocking { registrar.updateExternalIdentity("victim", hash) }

        assertTrue(result.isFailure)
        val error = result.errorOrNull() as VisionPushError.ApiError
        assertEquals(403, error.httpStatus)
        assertTrue(error.isExternalIdVerificationFailed)
        assertEquals("a verification failure must not be retried", 2, interceptor.registerCalls.get())
        // The rejected payload did carry the unverified id + hash …
        val rejected = lastRegisterBody()
        assertEquals("victim", rejected.getString("external_id"))
        assertEquals(hash, rejected.getString("external_id_hash"))
        // … but the device keeps its last ACCEPTED identity and stays syncable.
        assertEquals("user-old", registrar.externalId)
        assertEquals(previousHash, registrar.externalIdHash)
        assertFalse(registrar.isExternalIdClearPending)
    }

    @Test
    fun verificationFailureDuringASyncDropsTheUnverifiedIdentityWithoutAServerClear() {
        val registrar = newRegistrar()
        registrar.setExternalIdentity("victim", null) // e.g. stored before verification was enabled
        interceptor.registerStatus = 403

        val result = runBlocking { registrar.register() }

        assertTrue(result.isFailure)
        assertEquals("not retried", 1, interceptor.registerCalls.get())
        assertNull("the unverified id must not poison every later sync", registrar.externalId)
        assertNull(registrar.externalIdHash)
        assertFalse("dropping is local only — the server keeps what it has", registrar.isExternalIdClearPending)

        // The next sync registers cleanly: no id, no hash, and no clear PUT.
        interceptor.registerStatus = 200
        runBlocking { assertTrue(registrar.register().isSuccess) }
        assertTrue(interceptor.clearCalls.isEmpty())
        assertFalse(lastRegisterBody().has("external_id"))
        assertFalse(lastRegisterBody().has("external_id_hash"))
    }

    @Test
    fun unregisterClearsTheHashToo() {
        val registrar = newRegistrar()
        runBlocking {
            registrar.updateExternalIdentity("user-9", hash)
            registrar.unregister()
        }
        assertNull(registrar.externalId)
        assertNull(registrar.externalIdHash)
        assertNull(newRegistrar().externalIdHash)
    }

    // ── Opt-out survives the loss of the encrypted store (backup restore) ─────

    @Test
    fun optOutSurvivesLossOfTheEncryptedStoreAndIsReplayedOnTheNewDeviceRow() {
        val first = newRegistrar()
        runBlocking {
            first.register()
            assertTrue(first.setOptedOut(true).isSuccess)
        }
        assertTrue(first.isOptedOut)
        val installationBefore = first.anonymousId
        assertEquals(1, interceptor.optOutCalls)

        // Simulate a backup restore where the encrypted store cannot be opened: the
        // registrar starts from an EMPTY identity file. (Under Robolectric the registrar
        // already runs on the plain fallback; wiping both candidate files is the same
        // event either way.)
        for (file in listOf(DeviceRegistrar.PREFS_FILE_NAME, DeviceRegistrar.FALLBACK_PREFS_FILE_NAME)) {
            context.getSharedPreferences(file, android.content.Context.MODE_PRIVATE).edit().clear().commit()
        }

        val restored = newRegistrar()
        assertNull("identity is gone", restored.deviceId)
        assertNotEquals("a new installation id is minted", installationBefore, restored.anonymousId)
        assertTrue("opt-out must NOT revert to opted-in", restored.isOptedOut)

        // Re-registration creates a new device row — which starts opted-in on the
        // server — so the persisted opt-out is replayed onto it.
        runBlocking { assertTrue(restored.register().isSuccess) }
        assertEquals(1, interceptor.registerCalls.get())
        assertEquals("opt-out replayed for the new row", 1, interceptor.optOutCalls)
        assertEquals("/v1/devices/dev-123/opt-out", interceptor.sent.last().path)

        // A plain re-register of the SAME row does not replay again.
        runBlocking { restored.register(forceRefresh = true) }
        assertEquals(1, interceptor.optOutCalls)
    }

    @Test
    fun optOutSetBeforeFirstRegistrationIsReplayedOnRegister() {
        val registrar = newRegistrar()
        val early = runBlocking { registrar.setOptedOut(true) }
        assertTrue("no device yet", early.isFailure)
        assertTrue(registrar.isOptedOut)

        runBlocking { registrar.register() }
        assertEquals(1, interceptor.optOutCalls)
    }

    @Test
    fun optInStateIsNotReplayed() {
        val registrar = newRegistrar()
        runBlocking { registrar.register() }
        assertFalse(registrar.isOptedOut)
        assertEquals(0, interceptor.optOutCalls)
    }

    @Test
    fun unregisterClearsTheOptOutStateToo() {
        val registrar = newRegistrar()
        runBlocking {
            registrar.register()
            registrar.setOptedOut(true)
            registrar.unregister()
        }
        assertFalse(registrar.isOptedOut)
        assertFalse(newRegistrar().isOptedOut)
    }
}
