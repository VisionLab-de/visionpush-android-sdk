package de.visionlab.visionpush

import android.app.Application
import android.content.Context
import androidx.work.OneTimeWorkRequest
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Consent gating of the AUTOMATIC data flows (delivered/opened/dismissed events,
 * realtime, geofencing) and the realtime enable/teardown behaviour, driven through
 * the public [VisionPush] API under Robolectric.
 *
 * The public `trackOpened()` already threw `ConsentRequired`; these tests pin the
 * paths that used to bypass consent: [NotificationTracker.enqueueTrackingWork] (the
 * DELIVERED event from the display path and the OPENED/DISMISSED events from the tap
 * receiver land there), and `setConsentGiven(false)` tearing down realtime.
 *
 * [NotificationTracker.workEnqueuer] replaces WorkManager so the gate is observable
 * without initializing WorkManager; the main looper is never idled, so the async
 * registration `initializeWithConfig` posts never runs (no network).
 */
@RunWith(RobolectricTestRunner::class)
class ConsentGatingTest {

    private lateinit var context: Application
    private val enqueued = mutableListOf<OneTimeWorkRequest>()

    private fun config(consentRequired: Boolean) = VisionPushConfig(
        appId = "11111111-1111-1111-1111-111111111111",
        apiKey = "vp_test_key",
        apiBaseUrl = "https://api.visionpush.test/v1",
        consentRequired = consentRequired,
    )

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        VisionPush.resetForTesting()
        clearConsent()
        VisionPush.pushTokenProvider = { null } // never touch Firebase
        NotificationTracker.workEnqueuer = { _, request -> enqueued.add(request) }
    }

    @After
    fun tearDown() {
        NotificationTracker.workEnqueuer = null
        VisionPush.resetForTesting()
        clearConsent()
    }

    private fun clearConsent() {
        context.getSharedPreferences("visionpush_consent", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun enqueueDelivered(id: String = "n1") =
        NotificationTracker.enqueueTrackingWork(context, id, NotificationEvent.EventType.DELIVERED, actionId = null, campaignId = "c1")

    /** A registrar whose API client answers every call with 200 + a device id, registered synchronously. */
    private fun registeredRegistrar(cfg: VisionPushConfig): DeviceRegistrar {
        val interceptor = Interceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{\"device_id\":\"dev-123\"}".toResponseBody("application/json; charset=utf-8".toMediaType()))
                .build()
        }
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val registrar = DeviceRegistrar(context, cfg, ApiClient(cfg, clientOverride = client))
        VisionPush.pushTokenProvider = { "tok-1" }
        val result = runBlocking { registrar.register() }
        assertTrue(result.isSuccess)
        assertEquals("dev-123", registrar.deviceId)
        return registrar
    }

    // ── Tracking gate ─────────────────────────────────────────────────────────

    @Test
    fun automaticTrackingIsDroppedUntilConsentIsGiven() {
        VisionPush.initializeWithConfig(context, config(consentRequired = true))
        assertFalse(VisionPush.hasConsent)

        enqueueDelivered()
        assertTrue("no consent → nothing may be enqueued", enqueued.isEmpty())

        VisionPush.setConsentGiven(true)
        assertTrue(VisionPush.hasConsent)

        enqueueDelivered()
        assertEquals(1, enqueued.size)
        val input = enqueued[0].workSpec.input
        assertEquals("n1", input.getString(WorkerKeys.KEY_NOTIFICATION_ID))
        assertEquals("delivered", input.getString(WorkerKeys.KEY_EVENT_TYPE))
        assertEquals("c1", input.getString(WorkerKeys.KEY_CAMPAIGN_ID))
    }

    @Test
    fun trackingIsAllowedWhenConsentIsNotRequired() {
        VisionPush.initializeWithConfig(context, config(consentRequired = false))
        enqueueDelivered()
        assertEquals(1, enqueued.size)
    }

    @Test
    fun revokingConsentDropsSubsequentTrackingAndTearsDownRealtime() {
        val cfg = config(consentRequired = true)
        VisionPush.initializeWithConfig(context, cfg)
        VisionPush.setConsentGiven(true)

        // A connected realtime channel, as enableRealtime() would have created it.
        val factory = FakeWebSocketFactory()
        val channel = RealtimeChannel(
            baseUrl = cfg.apiBaseUrl, apiKey = cfg.apiKey, installationId = "inst-1", appId = cfg.appId,
            onPush = { _, _ -> }, factory = factory, scheduler = ManualReconnectScheduler(),
        )
        channel.connect()
        VisionPush.internalRealtimeChannel = channel
        assertEquals(1, factory.sockets.size)

        VisionPush.setConsentGiven(false)

        assertFalse(VisionPush.hasConsent)
        assertNull("realtime must be torn down on revocation", VisionPush.internalRealtimeChannel)
        assertTrue("the socket must be closed", factory.sockets[0].closed)
        assertEquals(RealtimeConnectionState.CLOSED, VisionPush.realtimeState)
        assertFalse(VisionPush.isGeofencingEnabled)

        enqueueDelivered()
        assertTrue("post-revocation events must be dropped", enqueued.isEmpty())
    }

    @Test
    fun explicitApisStillThrowConsentRequired() {
        VisionPush.initializeWithConfig(context, config(consentRequired = true))
        try {
            VisionPush.enableRealtime()
            throw AssertionError("expected ConsentRequired")
        } catch (_: VisionPushError.ConsentRequired) {
        }
        try {
            VisionPush.trackOpened("n1")
            throw AssertionError("expected ConsentRequired")
        } catch (_: VisionPushError.ConsentRequired) {
        }
        assertTrue(enqueued.isEmpty())
    }

    // ── Realtime: deferred connect + terminal close ───────────────────────────

    @Test
    fun enableRealtimeBeforeRegistrationIsDeferredUntilTheDeviceIsRegistered() {
        val cfg = config(consentRequired = false)
        VisionPush.initializeWithConfig(context, cfg)
        val factory = FakeWebSocketFactory()
        VisionPush.realtimeTransportFactory = factory
        VisionPush.realtimeReconnectScheduler = ManualReconnectScheduler()

        // `initialized` is true but registration (async) has not produced a device id.
        assertNull(VisionPush.deviceId)
        VisionPush.enableRealtime()
        assertNull("must not connect before a device exists", VisionPush.internalRealtimeChannel)
        assertTrue(factory.sockets.isEmpty())

        // Registration completes → the deferred connect happens automatically.
        val registrar = registeredRegistrar(cfg)
        VisionPush.internalDeviceRegistrar = registrar
        VisionPush.notifyDeviceRegistered("dev-123")

        assertNotNull(VisionPush.internalRealtimeChannel)
        assertEquals(1, factory.sockets.size)
        assertEquals("wss://api.visionpush.test/v1/device/connect", factory.lastUrl)
        factory.listeners[0].onOpen(factory.sockets[0], dummyWsResponse())
        val auth = JSONObject(factory.sockets[0].sent.single())
        assertEquals(registrar.anonymousId, auth.getString("installation_id"))
        assertEquals(cfg.appId, auth.getString("app_id"))
    }

    @Test
    fun disableRealtimeCancelsADeferredConnect() {
        val cfg = config(consentRequired = false)
        VisionPush.initializeWithConfig(context, cfg)
        val factory = FakeWebSocketFactory()
        VisionPush.realtimeTransportFactory = factory
        VisionPush.realtimeReconnectScheduler = ManualReconnectScheduler()

        VisionPush.enableRealtime() // deferred
        VisionPush.disableRealtime()

        VisionPush.internalDeviceRegistrar = registeredRegistrar(cfg)
        VisionPush.notifyDeviceRegistered("dev-123")
        assertNull(VisionPush.internalRealtimeChannel)
        assertTrue(factory.sockets.isEmpty())
    }

    @Test
    fun enableRealtimeWithARegisteredDeviceConnectsImmediately() {
        val cfg = config(consentRequired = false)
        VisionPush.initializeWithConfig(context, cfg)
        val factory = FakeWebSocketFactory()
        VisionPush.realtimeTransportFactory = factory
        VisionPush.realtimeReconnectScheduler = ManualReconnectScheduler()
        VisionPush.internalDeviceRegistrar = registeredRegistrar(cfg)

        VisionPush.enableRealtime()
        assertEquals(1, factory.sockets.size)
        assertEquals(RealtimeConnectionState.CONNECTING, VisionPush.realtimeState)
    }

    @Test
    fun terminalGatewayCloseStopsReconnectingThroughThePublicApi() {
        val cfg = config(consentRequired = false)
        VisionPush.initializeWithConfig(context, cfg)
        val factory = FakeWebSocketFactory()
        val scheduler = ManualReconnectScheduler()
        VisionPush.realtimeTransportFactory = factory
        VisionPush.realtimeReconnectScheduler = scheduler
        VisionPush.internalDeviceRegistrar = registeredRegistrar(cfg)
        VisionPush.enableRealtime()

        factory.listeners[0].onOpen(factory.sockets[0], dummyWsResponse())
        factory.listeners[0].onClosed(factory.sockets[0], RealtimeChannel.CLOSE_DEVICE_UNKNOWN, "device unknown")

        assertNull("4404 is terminal: no reconnect may be scheduled", scheduler.pending)
        assertEquals(RealtimeConnectionState.CLOSED, VisionPush.realtimeState)
        assertTrue(VisionPush.internalRealtimeChannel!!.isTerminated)

        // A later explicit enableRealtime() tries again with a fresh channel.
        VisionPush.enableRealtime()
        assertEquals(2, factory.sockets.size)
    }
}
