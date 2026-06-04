package de.visionlab.visionpush

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Unit tests for [RealtimeChannel].
 *
 * They inject a fake [RealtimeChannel.WebSocketFactory] + a deterministic
 * [RealtimeChannel.ReconnectScheduler], so the full handshake / reconnect
 * lifecycle is exercised without a real network. Robolectric provides a working
 * `android.util.Log` (the channel logs via [VisionPushLogger]); the same runner
 * convention is used by [InAppMessageControllerTest].
 */
@RunWith(RobolectricTestRunner::class)
class RealtimeChannelTest {

    private val appId = "11111111-1111-1111-1111-111111111111"

    // ── Fakes ────────────────────────────────────────────────────────────────

    /** A no-network [WebSocket] that records what the channel sends. */
    private class FakeWebSocket : WebSocket {
        val sent = CopyOnWriteArrayList<String>()
        var closed = false
        var closeReason: String? = null

        override fun request() = throw UnsupportedOperationException()
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean { sent.add(text); return true }
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?): Boolean {
            closed = true; closeReason = reason; return true
        }
        override fun cancel() { closed = true }
    }

    /**
     * Records each created socket and the listener driving it, so tests can feed
     * server frames in and assert behaviour.
     */
    private class FakeFactory : RealtimeChannel.WebSocketFactory {
        val sockets = CopyOnWriteArrayList<FakeWebSocket>()
        val listeners = CopyOnWriteArrayList<WebSocketListener>()
        var lastUrl: String? = null

        override fun newWebSocket(url: String, listener: WebSocketListener): WebSocket {
            lastUrl = url
            val ws = FakeWebSocket()
            sockets.add(ws)
            listeners.add(listener)
            return ws
        }
    }

    /** Captures scheduled reconnects so the test can fire them on demand. */
    private class ManualScheduler : RealtimeChannel.ReconnectScheduler {
        var pending: (() -> Unit)? = null
        var lastDelay: Long = -1
        var cancelled = false

        override fun schedule(delayMs: Long, task: () -> Unit): Any {
            lastDelay = delayMs
            pending = task
            return task
        }

        override fun cancel(token: Any) { cancelled = true; pending = null }

        /** Fires the pending reconnect, if any. */
        fun fire() { pending?.also { pending = null }?.invoke() }
    }

    private fun channel(
        factory: FakeFactory,
        scheduler: ManualScheduler = ManualScheduler(),
        onPush: (JSONObject, RealtimePushFrame) -> Unit = { _, _ -> },
        onStatus: ((RealtimeConnectionState) -> Unit)? = null,
        baseUrl: String = "https://api.visionpush.de/v1",
    ) = RealtimeChannel(
        baseUrl = baseUrl,
        apiKey = "vp_live_test",
        installationId = "inst-1",
        appId = appId,
        onPush = onPush,
        onStatusChange = onStatus,
        factory = factory,
        scheduler = scheduler,
    )

    // ── URL derivation ─────────────────────────────────────────────────────────

    @Test
    fun derivesWssFromHttps() {
        assertEquals(
            "wss://api.visionpush.de/v1/device/connect",
            RealtimeChannel.deriveWsUrl("https://api.visionpush.de/v1"),
        )
    }

    @Test
    fun derivesWsFromHttpAndStripsTrailingSlash() {
        assertEquals(
            "ws://localhost:8090/v1/device/connect",
            RealtimeChannel.deriveWsUrl("http://localhost:8090/v1/"),
        )
    }

    @Test
    fun keepsExplicitWsScheme() {
        assertEquals(
            "ws://host/device/connect",
            RealtimeChannel.deriveWsUrl("ws://host"),
        )
    }

    // ── Handshake ──────────────────────────────────────────────────────────────

    @Test
    fun sendsAuthFrameOnOpen() {
        val factory = FakeFactory()
        val ch = channel(factory)
        ch.connect()

        // Drive the open callback as OkHttp would.
        factory.listeners[0].onOpen(factory.sockets[0], dummyResponse())

        assertEquals(1, factory.sockets[0].sent.size)
        val frame = JSONObject(factory.sockets[0].sent[0])
        assertEquals("vp_live_test", frame.getString("api_key"))
        assertEquals("inst-1", frame.getString("installation_id"))
        assertEquals(appId, frame.getString("app_id"))
        assertEquals(RealtimeConnectionState.CONNECTING, ch.connectionState)
    }

    @Test
    fun authOkTransitionsToOpen() {
        val factory = FakeFactory()
        val states = mutableListOf<RealtimeConnectionState>()
        val ch = channel(factory, onStatus = { states.add(it) })
        ch.connect()
        val listener = factory.listeners[0]
        listener.onOpen(factory.sockets[0], dummyResponse())

        listener.onMessage(factory.sockets[0], """{"type":"auth_ok"}""")

        assertTrue(ch.isConnected)
        assertEquals(RealtimeConnectionState.OPEN, ch.connectionState)
        assertEquals(listOf(RealtimeConnectionState.CONNECTING, RealtimeConnectionState.OPEN), states)
    }

    // ── Push delivery ───────────────────────────────────────────────────────────

    @Test
    fun deliversPushPayloadToCallback() {
        val factory = FakeFactory()
        val received = mutableListOf<RealtimePushFrame>()
        val ch = channel(factory, onPush = { _, frame -> received.add(frame) })
        ch.connect()
        val listener = factory.listeners[0]
        listener.onOpen(factory.sockets[0], dummyResponse())
        listener.onMessage(factory.sockets[0], """{"type":"auth_ok"}""")

        listener.onMessage(
            factory.sockets[0],
            """{"type":"push","installation_id":"inst-1","payload":{"id":"n1","title":"Hi"}}""",
        )

        assertEquals(1, received.size)
        assertEquals("inst-1", received[0].installationId)
        assertEquals("n1", received[0].payload.getString("id"))
        assertEquals("Hi", received[0].payload.getString("title"))
    }

    @Test
    fun ignoresMalformedFrameWithoutThrowing() {
        val factory = FakeFactory()
        var pushes = 0
        val ch = channel(factory, onPush = { _, _ -> pushes++ })
        ch.connect()
        val listener = factory.listeners[0]
        listener.onOpen(factory.sockets[0], dummyResponse())

        // Garbage + a push without a payload object — both must be ignored.
        listener.onMessage(factory.sockets[0], "not json at all")
        listener.onMessage(factory.sockets[0], """{"type":"push"}""")

        assertEquals(0, pushes)
        assertFalse(ch.isConnected)
    }

    @Test
    fun pushCallbackThrowingDoesNotBreakSocket() {
        val factory = FakeFactory()
        val ch = channel(factory, onPush = { _, _ -> throw RuntimeException("boom") })
        ch.connect()
        val listener = factory.listeners[0]
        listener.onOpen(factory.sockets[0], dummyResponse())
        listener.onMessage(factory.sockets[0], """{"type":"auth_ok"}""")

        // Should not propagate.
        listener.onMessage(
            factory.sockets[0],
            """{"type":"push","payload":{"id":"n1","title":"x"}}""",
        )

        assertTrue(ch.isConnected)
    }

    // ── Reconnect ──────────────────────────────────────────────────────────────

    @Test
    fun reconnectsAfterUnexpectedClose() {
        val factory = FakeFactory()
        val scheduler = ManualScheduler()
        val ch = channel(factory, scheduler)
        ch.connect()
        val listener = factory.listeners[0]
        listener.onOpen(factory.sockets[0], dummyResponse())
        listener.onMessage(factory.sockets[0], """{"type":"auth_ok"}""")

        // Server drops the socket (auth failure path closes with no frame, too).
        listener.onClosed(factory.sockets[0], 1006, "gone")

        assertEquals(RealtimeConnectionState.CLOSED, ch.connectionState)
        assertTrue("a reconnect should be scheduled", scheduler.pending != null)

        // Fire the scheduled reconnect → a new socket is created.
        scheduler.fire()
        assertEquals(2, factory.sockets.size)
    }

    @Test
    fun disconnectStopsReconnect() {
        val factory = FakeFactory()
        val scheduler = ManualScheduler()
        val ch = channel(factory, scheduler)
        ch.connect()
        val listener = factory.listeners[0]
        listener.onOpen(factory.sockets[0], dummyResponse())

        ch.disconnect()
        assertTrue(factory.sockets[0].closed)

        // A late close callback must NOT schedule a reconnect.
        listener.onClosed(factory.sockets[0], 1000, "client disconnect")
        assertNull(scheduler.pending)
        assertEquals(RealtimeConnectionState.CLOSED, ch.connectionState)
    }

    @Test
    fun authOkResetsBackoffSoSecondFailureUsesBaseDelay() {
        val factory = FakeFactory()
        val scheduler = ManualScheduler()
        val ch = channel(factory, scheduler)
        ch.connect()
        var listener = factory.listeners[0]
        listener.onOpen(factory.sockets[0], dummyResponse())
        listener.onMessage(factory.sockets[0], """{"type":"auth_ok"}""") // reset attempts

        // First unexpected close after a good auth → base-ish delay (attempt 0).
        listener.onClosed(factory.sockets[0], 1006, "x")
        // attempt 0: capped = 1000ms, jitter ±50% → within [500, 1500].
        assertTrue("delay=${scheduler.lastDelay}", scheduler.lastDelay in 500..1500)
    }

    @Test
    fun doubleConnectIsGuarded() {
        val factory = FakeFactory()
        val ch = channel(factory)
        ch.connect()
        ch.connect() // second call must not open a second socket pre-handshake.
        assertEquals(1, factory.sockets.size)
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** Minimal non-null [Response] for the OkHttp listener callbacks. */
    private fun dummyResponse(): Response = Response.Builder()
        .request(okhttp3.Request.Builder().url("https://example.com").build())
        .protocol(okhttp3.Protocol.HTTP_1_1)
        .code(101)
        .message("Switching Protocols")
        .build()
}
