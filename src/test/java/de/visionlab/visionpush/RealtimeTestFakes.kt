package de.visionlab.visionpush

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Shared no-network fakes for tests that drive a [RealtimeChannel] through the public
 * [VisionPush] API (which cannot reach RealtimeChannelTest's private fakes).
 */
internal class FakeWebSocket : WebSocket {
    val sent = CopyOnWriteArrayList<String>()
    @Volatile var closed = false
    @Volatile var closeReason: String? = null

    override fun request(): Request = throw UnsupportedOperationException()
    override fun queueSize(): Long = 0
    override fun send(text: String): Boolean { sent.add(text); return true }
    override fun send(bytes: ByteString): Boolean = true
    override fun close(code: Int, reason: String?): Boolean {
        closed = true; closeReason = reason; return true
    }
    override fun cancel() { closed = true }
}

/** Records each created socket and the listener driving it. */
internal class FakeWebSocketFactory : RealtimeChannel.WebSocketFactory {
    val sockets = CopyOnWriteArrayList<FakeWebSocket>()
    val listeners = CopyOnWriteArrayList<WebSocketListener>()
    @Volatile var lastUrl: String? = null

    override fun newWebSocket(url: String, listener: WebSocketListener): WebSocket {
        lastUrl = url
        val ws = FakeWebSocket()
        sockets.add(ws)
        listeners.add(listener)
        return ws
    }
}

/** Captures scheduled reconnects so the test can fire them on demand. */
internal class ManualReconnectScheduler : RealtimeChannel.ReconnectScheduler {
    @Volatile var pending: (() -> Unit)? = null
    @Volatile var lastDelay: Long = -1
    @Volatile var cancelled = false

    override fun schedule(delayMs: Long, task: () -> Unit): Any {
        lastDelay = delayMs
        pending = task
        return task
    }

    override fun cancel(token: Any) { cancelled = true; pending = null }

    fun fire() { pending?.also { pending = null }?.invoke() }
}

/** Minimal non-null [Response] for the OkHttp listener callbacks. */
internal fun dummyWsResponse(): Response = Response.Builder()
    .request(Request.Builder().url("https://example.com").build())
    .protocol(Protocol.HTTP_1_1)
    .code(101)
    .message("Switching Protocols")
    .build()
