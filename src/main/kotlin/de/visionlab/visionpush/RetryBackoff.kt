package de.visionlab.visionpush

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Retry-delay policy shared by [ApiClient]: honours the server's `Retry-After` hint
 * and otherwise applies **equal jitter** to the running exponential back-off.
 *
 * Mirrors the iOS SDK (`ApiClient.swift` — `parseRetryAfter` / `retryDelayNanoseconds`)
 * so both native clients back off identically:
 *
 * - A usable `Retry-After` wins, capped at [RETRY_AFTER_CAP_MS] so a hostile or
 *   misconfigured server cannot stall the SDK for an hour.
 * - Otherwise the exponential delay is jittered into `[delay/2, delay]` — a fleet of
 *   devices that all lost the network at the same moment no longer retries in
 *   lock-step (the classic thundering-herd on recovery).
 * - `429 Too Many Requests` keeps a courtesy floor ([ApiClient] passes it in) that the
 *   jitter never undercuts.
 *
 * Pure Kotlin/JDK — unit-tested on a plain JVM.
 */
internal object RetryBackoff {

    /** Upper bound applied to any `Retry-After` value (60 s). */
    const val RETRY_AFTER_CAP_MS: Long = 60_000L

    /**
     * Parses an HTTP `Retry-After` header (RFC 7231 §7.1.3) into milliseconds to wait.
     *
     * Accepts delta-seconds (`"120"`) and all three HTTP-date formats recipients must
     * understand (IMF-fixdate, RFC 850, asctime). Dates are converted relative to
     * [nowMs]; a date in the past yields `0`. Returns `null` for an absent, blank,
     * negative or unparseable value — the caller then falls back to its own back-off.
     */
    fun parseRetryAfterMs(header: String?, nowMs: Long = System.currentTimeMillis()): Long? {
        val value = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        value.toLongOrNull()?.let { seconds ->
            return if (seconds >= 0) seconds * 1_000L else null
        }
        // Not RFC-conformant, but lenient like the iOS port: "1.5" → 1500 ms.
        value.toDoubleOrNull()?.let { seconds ->
            return if (seconds >= 0 && seconds.isFinite()) (seconds * 1_000.0).toLong() else null
        }

        for (pattern in HTTP_DATE_PATTERNS) {
            val format = SimpleDateFormat(pattern, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
                isLenient = false
            }
            val date = try {
                format.parse(value)
            } catch (_: ParseException) {
                null
            } ?: continue
            return (date.time - nowMs).coerceAtLeast(0L)
        }
        return null
    }

    /**
     * Chooses the wait before the next attempt.
     *
     * @param exponentialMs   The running exponential delay (500 ms → 1 s → 2 s → …).
     * @param retryAfterMs    Parsed `Retry-After`, or `null` when the server sent none.
     * @param rateLimitFloorMs Minimum wait applied when [isRateLimit] (a 429 response).
     * @param random          Uniform sample in `[0, 1)`; injectable for deterministic tests.
     */
    fun delayMs(
        exponentialMs: Long,
        retryAfterMs: Long?,
        isRateLimit: Boolean,
        rateLimitFloorMs: Long,
        random: Double = Math.random(),
    ): Long {
        if (retryAfterMs != null && retryAfterMs > 0) {
            return minOf(retryAfterMs, RETRY_AFTER_CAP_MS)
        }
        // Equal jitter: half the delay is fixed, the other half is random — always in
        // [exponentialMs/2, exponentialMs], never overshooting the cap.
        val half = exponentialMs / 2
        val jittered = half + (random.coerceIn(0.0, 1.0) * half).toLong()
        return if (isRateLimit) maxOf(jittered, rateLimitFloorMs) else jittered
    }

    /** RFC 7231 §7.1.1.1 — IMF-fixdate (preferred), RFC 850, and ANSI C asctime. */
    private val HTTP_DATE_PATTERNS = listOf(
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "EEEE, dd-MMM-yy HH:mm:ss zzz",
        "EEE MMM d HH:mm:ss yyyy",
    )
}
