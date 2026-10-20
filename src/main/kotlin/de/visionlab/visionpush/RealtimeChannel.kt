package de.visionlab.visionpush

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Owned-transport realtime WebSocket client — the device side of the VisionPush
 * realtime gateway (`crates/visionpush-api/src/routes/device_gateway.rs`).
 *
 * When connected, the device is **ONLINE** and pushes are delivered over this
 * persistent socket instead of FCM. This mirrors the Web SDK's
 * `RealtimeChannel.ts` so all clients implement the exact same protocol.
 *
 * ## Protocol (must match the server gateway)
 * 1. Open a WebSocket to `{baseUrl}/device/connect`. The scheme is derived from
 *    the API base URL (`http`→`ws`, `https`→`wss`).
 * 2. On open, the client immediately sends ONE text frame — the auth frame
 *    `{ "api_key", "installation_id", "app_id" }`.
 * 3. The server replies with text `{"type":"auth_ok"}` on success. On rejection it
 *    CLOSES the socket with a close frame whose code says why: `4401` (auth
 *    rejected — bad key, or key not for this app) or `4404` (device unknown or
 *    inactive). Both are TERMINAL: the channel logs a warning and does NOT
 *    reconnect — retrying would just be rejected again, every ~22 s, forever. A
 *    later explicit [connect] (e.g. `enableRealtime()` after re-registration)
 *    tries again. Every other close keeps the back-off reconnect.
 * 4. Inbound push frames are text JSON
 *    `{"type":"push","installation_id":...,"payload": <object>}`; the `payload`
 *    is delivered to [onPush].
 * 5. The server sends WS Ping frames every ~10 s; OkHttp auto-replies Pong, so
 *    no manual heartbeat is required. Other server frames are ignored.
 *
 * Online delivery is best-effort / at-most-once in Phase 1 (no ack required).
 *
 * ## Reliability
 * - Auto-reconnect with exponential back-off + jitter (capped ~30 s), reset on a
 *   successful `auth_ok`.
 * - Malformed frames are ignored — they never throw into the socket handler.
 * - Double-connect is guarded; all timers are cleared on [disconnect].
 *
 * The channel is transport-agnostic via [WebSocketFactory] so unit tests can
 * inject a fake socket without a network.
 *
 * @property baseUrl        REST API base URL (e.g. `https://api.visionpush.de/v1`).
 * @property apiKey         VisionPush API key (`vp_live_…` / `vp_test_…`).
 * @property installationId SDK-generated stable device identifier.
 * @property appId          UUID identifying the VisionPush application.
 * @property onPush         Invoked for every inbound `push` frame's payload.
 * @property onStatusChange Optional connection-state observer.
 */
internal class RealtimeChannel(
    private val baseUrl: String,
    private val apiKey: String,
    private val installationId: String,
    private val appId: String,
    private val onPush: (payload: JSONObject, frame: RealtimePushFrame) -> Unit,
    private val onStatusChange: ((RealtimeConnectionState) -> Unit)? = null,
    private val factory: WebSocketFactory = OkHttpWebSocketFactory(),
    private val scheduler: ReconnectScheduler = HandlerReconnectScheduler(),
) {

    /**
     * Abstraction over OkHttp's `WebSocket.Factory` so tests can substitute a
     * fake transport. Returns a [WebSocket] that drives [listener] callbacks.
     */
    internal interface WebSocketFactory {
        fun newWebSocket(url: String, listener: WebSocketListener): WebSocket
    }

    /** Schedules delayed reconnect work. Injectable so tests run synchronously. */
    internal interface ReconnectScheduler {
        /** Runs [task] after [delayMs]; returns a token that [cancel] can clear. */
        fun schedule(delayMs: Long, task: () -> Unit): Any

        /** Cancels a previously scheduled [token] (best-effort). */
        fun cancel(token: Any)
    }

    // ── Runtime state ────────────────────────────────────────────────────────

    private val state = AtomicReference(RealtimeConnectionState.CLOSED)

    /** `true` once [disconnect] has been called — suppresses reconnects. */
    private val closedByUser = AtomicBoolean(false)

    /**
     * `true` after the server closed with a terminal code ([TERMINAL_CLOSE_CODES]) —
     * suppresses reconnects until the next explicit [connect].
     */
    private val terminated = AtomicBoolean(false)

    /** Close code of the most recent server-initiated close, or `-1` (diagnostics / tests). */
    @Volatile internal var lastCloseCode: Int = -1
        private set

    /** Guards against overlapping connect attempts. */
    private val connecting = AtomicBoolean(false)

    /** Consecutive failed attempts since the last successful auth. */
    private val reconnectAttempts = AtomicInteger(0)

    @Volatile private var socket: WebSocket? = null

    /** Pending reconnect token (from [ReconnectScheduler]), if any. */
    @Volatile private var reconnectToken: Any? = null

    /** notification_ids already delivered to [onPush] — dedups the at-least-once
     *  owned transport into exactly-once display. Bounded (oldest evicted). Only
     *  touched from [handlePush] on the serialized OkHttp reader thread. */
    private val seenNotificationIds = LinkedHashSet<String>()

    // ── Public interface ─────────────────────────────────────────────────────

    /**
     * Opens the socket and sends the auth frame. The channel keeps the socket
     * alive and auto-reconnects on unexpected closes until [disconnect] is
     * called. Calling [connect] while already open or connecting is a no-op.
     */
    fun connect() {
        closedByUser.set(false)
        terminated.set(false) // an explicit connect() may retry after a terminal close
        if (state.get() == RealtimeConnectionState.OPEN && socket != null) return
        if (!connecting.compareAndSet(false, true)) return
        openSocket()
    }

    /**
     * Closes the socket cleanly and stops all reconnect attempts. After
     * [disconnect] the channel can be reused by calling [connect] again.
     */
    fun disconnect() {
        closedByUser.set(true)
        connecting.set(false)
        clearReconnectTimer()

        val current = socket
        socket = null
        if (current != null) {
            try {
                current.close(NORMAL_CLOSURE, "client disconnect")
            } catch (e: Exception) {
                VisionPushLogger.w(TAG, "Error closing socket: ${e.message}")
            }
        }
        setState(RealtimeConnectionState.CLOSED)
        reconnectAttempts.set(0)
    }

    /** The current connection state. */
    val connectionState: RealtimeConnectionState get() = state.get()

    /** `true` when the channel is authenticated and online. */
    val isConnected: Boolean get() = state.get() == RealtimeConnectionState.OPEN

    /**
     * `true` when the gateway rejected this device with a terminal close code and the
     * channel has therefore stopped reconnecting. Cleared by the next [connect].
     */
    val isTerminated: Boolean get() = terminated.get()

    // ── Socket lifecycle ─────────────────────────────────────────────────────

    private fun openSocket() {
        val wsUrl = try {
            deriveWsUrl(baseUrl)
        } catch (e: Exception) {
            VisionPushLogger.w(TAG, "Invalid base URL '$baseUrl': ${e.message}")
            connecting.set(false)
            setState(RealtimeConnectionState.CLOSED)
            // No socket to drive a close → schedule a retry directly.
            if (!closedByUser.get()) scheduleReconnect()
            return
        }

        setState(RealtimeConnectionState.CONNECTING)
        VisionPushLogger.d(TAG, "Connecting to $wsUrl")
        socket = factory.newWebSocket(wsUrl, Listener())
    }

    /** Receives transport callbacks for the *current* socket attempt. */
    private inner class Listener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket !== socket) return // stale socket
            VisionPushLogger.d(TAG, "Socket open — sending auth frame")
            val frame = JSONObject().apply {
                put("api_key", apiKey)
                put("installation_id", installationId)
                put("app_id", appId)
            }
            // Failure to enqueue means the socket is dead → onFailure drives reconnect.
            if (!webSocket.send(frame.toString())) {
                VisionPushLogger.w(TAG, "Failed to enqueue auth frame")
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== socket) return
            handleFrame(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (webSocket !== socket) return
            // Gateway frames are text JSON; tolerate binary by decoding to UTF-8.
            handleFrame(bytes.utf8())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket === socket) lastCloseCode = code
            // Acknowledge the close so the connection finishes the handshake.
            try {
                webSocket.close(NORMAL_CLOSURE, null)
            } catch (_: Exception) {
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            handleDisconnected(webSocket, code, reason)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            VisionPushLogger.d(TAG, "Socket failure: ${t.message}")
            // A transport failure carries no server close code → never terminal.
            handleDisconnected(webSocket, closeCode = null, reason = t.message)
        }
    }

    /**
     * Parses and dispatches one inbound frame. Never throws into the socket
     * handler — malformed frames are ignored.
     */
    private fun handleFrame(text: String) {
        val type = try {
            JSONObject(text).optString("type")
        } catch (_: Exception) {
            VisionPushLogger.d(TAG, "Ignoring malformed frame")
            return
        }

        when (type) {
            "auth_ok" -> handleAuthOk()
            "push" -> handlePush(text)
            else -> VisionPushLogger.d(TAG, "Ignoring frame of type '$type'")
        }
    }

    private fun handleAuthOk() {
        if (state.get() == RealtimeConnectionState.OPEN) return // duplicate
        VisionPushLogger.d(TAG, "Authenticated (auth_ok)")
        connecting.set(false)
        reconnectAttempts.set(0) // reset back-off on a successful handshake
        setState(RealtimeConnectionState.OPEN)
    }

    private fun handlePush(text: String) {
        val frame = RealtimePushFrame.fromJson(text) ?: run {
            VisionPushLogger.d(TAG, "Ignoring push frame without a payload object")
            return
        }
        VisionPushLogger.d(TAG, "Push frame received")
        // Ack EVERY delivery (Phase-2 at-least-once) so the gateway advances its
        // cursor and stops redelivering — even a redelivered duplicate is acked. A
        // dropped ack just makes the gateway fall back to the provider path. Frames
        // with no notification_id are fire-and-forget. Confined to the OkHttp reader
        // thread (onMessage is serialized), so the seen-set needs no extra locking.
        if (frame.notificationId.isNotEmpty()) {
            sendAck(frame.notificationId)
            // Dedup DISPLAY: the at-least-once transport may redeliver the same
            // notification_id (reconnect re-drain / missed-ack re-send) — fire onPush
            // at most once. LinkedHashSet.add returns false for a repeat.
            if (!seenNotificationIds.add(frame.notificationId)) {
                VisionPushLogger.d(TAG, "Duplicate realtime push suppressed: ${frame.notificationId}")
                return
            }
            if (seenNotificationIds.size > MAX_SEEN_NOTIFICATION_IDS) {
                val it = seenNotificationIds.iterator()
                it.next()
                it.remove() // bounded — evict the oldest
            }
        }
        try {
            onPush(frame.payload, frame)
        } catch (e: Exception) {
            // A throwing consumer callback must never break the socket.
            VisionPushLogger.w(TAG, "Error in onPush handler: ${e.message}")
        }
    }

    /** Sends a delivery ack `{type:"ack",notification_id}` back over the socket.
     *  Best-effort — a dropped ack just means the gateway uses the provider fallback. */
    private fun sendAck(notificationId: String) {
        val ack = JSONObject().put("type", "ack").put("notification_id", notificationId)
        socket?.send(ack.toString())
    }

    /**
     * Shared teardown for `onClosed` / `onFailure`. Schedules a reconnect unless the
     * user disconnected or the server sent a terminal close code.
     *
     * @param closeCode The server's close-frame status, or `null` for a transport failure.
     */
    private fun handleDisconnected(webSocket: WebSocket, closeCode: Int?, reason: String?) {
        if (webSocket !== socket) return // stale socket from a prior attempt
        VisionPushLogger.d(TAG, "Socket disconnected (code=${closeCode ?: "n/a"})")
        if (closeCode != null) lastCloseCode = closeCode
        socket = null
        connecting.set(false)
        setState(RealtimeConnectionState.CLOSED)

        if (closedByUser.get()) {
            VisionPushLogger.d(TAG, "Not reconnecting — disconnect() was called")
            return
        }
        if (closeCode != null && closeCode in TERMINAL_CLOSE_CODES) {
            terminated.set(true)
            // Logged ONCE per rejection — there is no reconnect loop to repeat it.
            VisionPushLogger.w(
                TAG,
                "Realtime gateway rejected this device (close $closeCode: ${describeTerminalCode(closeCode)}" +
                        (reason?.takeIf { it.isNotBlank() }?.let { ", \"$it\"" } ?: "") +
                        ") – not reconnecting. Fix the API key / re-register the device, then call enableRealtime() again."
            )
            return
        }
        scheduleReconnect()
    }

    // ── Reconnect (exponential back-off + jitter) ────────────────────────────

    private fun scheduleReconnect() {
        if (reconnectToken != null) return // already scheduled

        val attempt = reconnectAttempts.getAndIncrement()
        // Exponential base*2^attempt, capped, with EQUAL jitter: capped/2 + up to
        // another capped/2 — always in [capped/2, capped], so it never overshoots
        // the cap (the old ±50% reached 1.5× the cap, 45 s on a 30 s cap).
        val exponential = RECONNECT_BASE_MS.toDouble() * Math.pow(2.0, attempt.toDouble())
        val capped = exponential.coerceAtMost(RECONNECT_MAX_MS.toDouble())
        val delay = (capped / 2 + Math.random() * (capped / 2)).toLong()

        VisionPushLogger.d(TAG, "Reconnecting in ${delay}ms (attempt ${attempt + 1})")
        reconnectToken = scheduler.schedule(delay) {
            reconnectToken = null
            if (closedByUser.get()) return@schedule
            // A reconnect is a fresh attempt; allow openSocket() to run.
            if (connecting.compareAndSet(false, true)) openSocket()
        }
    }

    private fun clearReconnectTimer() {
        reconnectToken?.let { scheduler.cancel(it) }
        reconnectToken = null
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun setState(next: RealtimeConnectionState) {
        if (state.getAndSet(next) == next) return
        onStatusChange?.let {
            try {
                it(next)
            } catch (e: Exception) {
                VisionPushLogger.w(TAG, "Error in onStatusChange handler: ${e.message}")
            }
        }
    }

    internal companion object {
        private const val TAG = "VP.RealtimeChannel"

        /** Path appended to the API base URL for the device gateway upgrade. */
        private const val CONNECT_PATH = "/device/connect"

        /** Initial reconnect back-off. */
        private const val RECONNECT_BASE_MS = 1_000L

        /** Reconnect back-off cap. */
        private const val RECONNECT_MAX_MS = 30_000L

        /** RFC 6455 normal-closure status code. */
        private const val NORMAL_CLOSURE = 1000

        /** Gateway close code: auth rejected (bad key / key not for this app). Terminal. */
        internal const val CLOSE_UNAUTHORIZED = 4401

        /** Gateway close code: device unknown or inactive. Terminal. */
        internal const val CLOSE_DEVICE_UNKNOWN = 4404

        /**
         * Server close codes after which reconnecting is pointless — the same request
         * would be rejected again. Mirrors `device_gateway.rs` (`CLOSE_UNAUTHORIZED`,
         * `CLOSE_DEVICE_UNKNOWN`).
         */
        internal val TERMINAL_CLOSE_CODES: Set<Int> = setOf(CLOSE_UNAUTHORIZED, CLOSE_DEVICE_UNKNOWN)

        private fun describeTerminalCode(code: Int): String = when (code) {
            CLOSE_UNAUTHORIZED -> "auth rejected"
            CLOSE_DEVICE_UNKNOWN -> "device unknown or inactive"
            else -> "terminal"
        }

        /** Cap on the realtime dedup set so it can't grow without bound. */
        private const val MAX_SEEN_NOTIFICATION_IDS = 512

        /**
         * Derives the WebSocket URL from the configured REST API base URL:
         * `http`→`ws`, `https`→`wss`, then appends the gateway connect path.
         * `ws`/`wss` URLs are used as-is.
         */
        internal fun deriveWsUrl(baseUrl: String): String {
            val trimmed = baseUrl.trimEnd('/')
            return when {
                trimmed.startsWith("https://") -> "wss://" + trimmed.removePrefix("https://") + CONNECT_PATH
                trimmed.startsWith("http://") -> "ws://" + trimmed.removePrefix("http://") + CONNECT_PATH
                trimmed.startsWith("wss://") || trimmed.startsWith("ws://") -> "$trimmed$CONNECT_PATH"
                else -> throw IllegalArgumentException(
                    "unsupported base URL scheme: \"$baseUrl\" (expected http(s) or ws(s))"
                )
            }
        }
    }
}

/** Connection state of a [RealtimeChannel]. */
enum class RealtimeConnectionState { CONNECTING, OPEN, CLOSED }

/**
 * A parsed inbound `push` frame from the realtime gateway.
 *
 * @property installationId The device the frame is addressed to (may be empty).
 * @property notificationId The frame's stable id (empty if absent) — used to ack the
 *                          delivery and dedup duplicate (redelivered) displays.
 * @property payload        The notification content object delivered to the host.
 */
data class RealtimePushFrame(
    val installationId: String,
    val notificationId: String,
    val payload: JSONObject,
) {
    internal companion object {
        /** Parses a `push` frame, or returns `null` when it has no payload object. */
        fun fromJson(text: String): RealtimePushFrame? {
            return try {
                val obj = JSONObject(text)
                val payload = obj.optJSONObject("payload") ?: return null
                RealtimePushFrame(
                    installationId = obj.optString("installation_id", ""),
                    notificationId = obj.optString("notification_id", ""),
                    payload = payload,
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

/** Default [RealtimeChannel.WebSocketFactory] backed by OkHttp. */
internal class OkHttpWebSocketFactory(
    private val client: OkHttpClient = sharedClient,
) : RealtimeChannel.WebSocketFactory {

    override fun newWebSocket(
        url: String,
        listener: WebSocketListener,
    ): WebSocket {
        val request = Request.Builder().url(url).build()
        return client.newWebSocket(request, listener)
    }

    private companion object {
        /**
         * A dedicated long-lived client for the realtime socket. `pingInterval`
         * is left at 0 (disabled) because the SERVER drives pings every ~10 s and
         * OkHttp auto-replies Pong; we only need read timeouts disabled so the
         * idle socket is not torn down between pushes.
         */
        val sharedClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // no read timeout on a persistent socket
            .build()
    }
}

/**
 * Default [RealtimeChannel.ReconnectScheduler] using a background [android.os.Handler]
 * so reconnect work runs off the caller thread without blocking.
 */
internal class HandlerReconnectScheduler : RealtimeChannel.ReconnectScheduler {

    private val handler: android.os.Handler by lazy {
        val thread = android.os.HandlerThread("VP.Realtime").apply { start() }
        android.os.Handler(thread.looper)
    }

    override fun schedule(delayMs: Long, task: () -> Unit): Any {
        val runnable = Runnable { task() }
        handler.postDelayed(runnable, delayMs)
        return runnable
    }

    override fun cancel(token: Any) {
        (token as? Runnable)?.let { handler.removeCallbacks(it) }
    }
}
