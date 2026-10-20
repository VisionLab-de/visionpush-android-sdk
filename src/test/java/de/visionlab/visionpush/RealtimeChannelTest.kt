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
    fun authOkResetsBackoffSoTheNextFailureUsesTheBaseDelay() {
        // The old version of this test asserted the base delay right after the FIRST
        // failure — with zero prior attempts the counter was already 0, so it passed
        // whether or not auth_ok reset anything. Grow the back-off first, then prove
        // auth_ok drops it back to the base.
        val factory = FakeFactory()
        val scheduler = ManualScheduler()
        val ch = channel(factory, scheduler)
        ch.connect()

        // Four consecutive failures: attempts 0..3 → equal-jitter delays
        // [500,1000], [1000,2000], [2000,4000], [4000,8000].
        repeat(4) { i ->
            val socket = factory.sockets[i]
            factory.listeners[i].onOpen(socket, dummyResponse())
            factory.listeners[i].onClosed(socket, 1006, "x")
            assertTrue("attempt $i delay=${scheduler.lastDelay}", scheduler.pending != null)
            scheduler.fire()
        }
        assertTrue("back-off must have grown, delay=${scheduler.lastDelay}", scheduler.lastDelay >= 4_000)
        assertEquals(5, factory.sockets.size)

        // Successful handshake on the 5th socket resets the counter…
        val socket = factory.sockets[4]
        val listener = factory.listeners[4]
        listener.onOpen(socket, dummyResponse())
        listener.onMessage(socket, """{"type":"auth_ok"}""")
        assertTrue(ch.isConnected)

        // …so the next drop schedules the BASE delay again (attempt 0 → [500, 1000]),
        // not attempt 4 ([8000, 16000]).
        listener.onClosed(socket, 1006, "x")
        assertTrue("delay=${scheduler.lastDelay}", scheduler.lastDelay in 500..1000)
    }

    // ── Terminal close codes (gateway rejected the device) ─────────────────────

    private fun connectedChannel(factory: FakeFactory, scheduler: ManualScheduler): RealtimeChannel {
        val ch = channel(factory, scheduler)
        ch.connect()
        factory.listeners[0].onOpen(factory.sockets[0], dummyResponse())
        return ch
    }

    @Test
    fun close4401AuthRejectedIsTerminalAndDoesNotReconnect() {
        val factory = FakeFactory()
        val scheduler = ManualScheduler()
        val ch = connectedChannel(factory, scheduler)

        factory.listeners[0].onClosed(factory.sockets[0], RealtimeChannel.CLOSE_UNAUTHORIZED, "unauthorized")

        assertEquals(RealtimeConnectionState.CLOSED, ch.connectionState)
        assertNull("4401 must not schedule a reconnect", scheduler.pending)
        assertTrue(ch.isTerminated)
        assertEquals(4401, ch.lastCloseCode)
        assertEquals(1, factory.sockets.size)
    }

    @Test
    fun close4404DeviceUnknownIsTerminalAndDoesNotReconnect() {
        val factory = FakeFactory()
        val scheduler = ManualScheduler()
        val ch = connectedChannel(factory, scheduler)

        // OkHttp delivers onClosing (server close frame) before onClosed.
        factory.listeners[0].onClosing(factory.sockets[0], RealtimeChannel.CLOSE_DEVICE_UNKNOWN, "device unknown")
        factory.listeners[0].onClosed(factory.sockets[0], RealtimeChannel.CLOSE_DEVICE_UNKNOWN, "device unknown")

        assertNull(scheduler.pending)
        assertTrue(ch.isTerminated)
        assertEquals(4404, ch.lastCloseCode)
    }

    @Test
    fun terminalCloseAfterAuthOkAlsoStopsReconnecting() {
        val factory = FakeFactory()
        val scheduler = ManualScheduler()
        val ch = connectedChannel(factory, scheduler)
        factory.listeners[0].onMessage(factory.sockets[0], """{"type":"auth_ok"}""")
        assertTrue(ch.isConnected)

        // e.g. the device was deactivated in the dashboard while online.
        factory.listeners[0].onClosed(factory.sockets[0], RealtimeChannel.CLOSE_DEVICE_UNKNOWN, "inactive")
        assertFalse(ch.isConnected)
        assertNull(scheduler.pending)
    }

    @Test
    fun nonTerminalCloseCodesStillReconnect() {
        for (code in listOf(1001, 1006, 1011, 4000, 4500)) {
            val factory = FakeFactory()
            val scheduler = ManualScheduler()
            val ch = connectedChannel(factory, scheduler)
            factory.listeners[0].onClosed(factory.sockets[0], code, "x")
            assertTrue("code $code must keep the back-off reconnect", scheduler.pending != null)
            assertFalse(ch.isTerminated)
        }
    }

    @Test
    fun transportFailureIsNeverTerminal() {
        val factory = FakeFactory()
        val scheduler = ManualScheduler()
        val ch = connectedChannel(factory, scheduler)
        factory.listeners[0].onFailure(factory.sockets[0], RuntimeException("reset"), null)
        assertTrue(scheduler.pending != null)
        assertFalse(ch.isTerminated)
    }

    @Test
    fun explicitConnectAfterTerminalCloseTriesAgain() {
        val factory = FakeFactory()
        val scheduler = ManualScheduler()
        val ch = connectedChannel(factory, scheduler)
        factory.listeners[0].onClosed(factory.sockets[0], RealtimeChannel.CLOSE_UNAUTHORIZED, "unauthorized")
        assertTrue(ch.isTerminated)

        ch.connect() // e.g. enableRealtime() after re-registration
        assertFalse(ch.isTerminated)
        assertEquals(2, factory.sockets.size)
        assertEquals(RealtimeConnectionState.CONNECTING, ch.connectionState)
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
