package de.visionlab.visionpush

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Plain-JVM tests for [RetryBackoff]: `Retry-After` parsing (delta-seconds + the three
 * HTTP-date formats), the 60 s cap, equal jitter, and the 429 floor.
 */
class RetryBackoffTest {

    /** A whole-second "now" so date arithmetic is exact. */
    private val nowMs = 1_800_000_000_000L

    private fun httpDate(pattern: String, epochMs: Long): String =
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }.format(Date(epochMs))

    // ── parseRetryAfterMs ─────────────────────────────────────────────────────

    @Test
    fun parsesDeltaSeconds() {
        assertEquals(120_000L, RetryBackoff.parseRetryAfterMs("120", nowMs))
        assertEquals(5_000L, RetryBackoff.parseRetryAfterMs("  5  ", nowMs))
        assertEquals(0L, RetryBackoff.parseRetryAfterMs("0", nowMs))
    }

    @Test
    fun parsesFractionalSecondsLeniently() {
        assertEquals(1_500L, RetryBackoff.parseRetryAfterMs("1.5", nowMs))
    }

    @Test
    fun rejectsAbsentBlankNegativeOrGarbage() {
        assertNull(RetryBackoff.parseRetryAfterMs(null, nowMs))
        assertNull(RetryBackoff.parseRetryAfterMs("", nowMs))
        assertNull(RetryBackoff.parseRetryAfterMs("   ", nowMs))
        assertNull(RetryBackoff.parseRetryAfterMs("-1", nowMs))
        assertNull(RetryBackoff.parseRetryAfterMs("soon", nowMs))
        assertNull(RetryBackoff.parseRetryAfterMs("NaN", nowMs))
    }

    @Test
    fun parsesImfFixdateRelativeToNow() {
        val header = httpDate("EEE, dd MMM yyyy HH:mm:ss zzz", nowMs + 30_000L)
        assertEquals(30_000L, RetryBackoff.parseRetryAfterMs(header, nowMs))
    }

    @Test
    fun parsesRfc850AndAsctimeDates() {
        val rfc850 = httpDate("EEEE, dd-MMM-yy HH:mm:ss zzz", nowMs + 45_000L)
        assertEquals(45_000L, RetryBackoff.parseRetryAfterMs(rfc850, nowMs))

        val asctime = httpDate("EEE MMM d HH:mm:ss yyyy", nowMs + 10_000L)
        assertEquals(10_000L, RetryBackoff.parseRetryAfterMs(asctime, nowMs))
    }

    @Test
    fun pastDateYieldsZeroNotNegative() {
        val header = httpDate("EEE, dd MMM yyyy HH:mm:ss zzz", nowMs - 90_000L)
        assertEquals(0L, RetryBackoff.parseRetryAfterMs(header, nowMs))
    }

    // ── delayMs ───────────────────────────────────────────────────────────────

    @Test
    fun retryAfterWinsOverBackoffAndIsCappedAt60s() {
        assertEquals(3_000L, RetryBackoff.delayMs(500L, retryAfterMs = 3_000L, isRateLimit = false, rateLimitFloorMs = 2_000L, random = 0.5))
        // Hostile / huge value cannot stall the SDK.
        assertEquals(60_000L, RetryBackoff.delayMs(500L, retryAfterMs = 3_600_000L, isRateLimit = true, rateLimitFloorMs = 2_000L, random = 0.5))
        assertEquals(RetryBackoff.RETRY_AFTER_CAP_MS, 60_000L)
    }

    @Test
    fun retryAfterBelowTheRateLimitFloorIsHonoured() {
        // The server explicitly asked for 1 s — that beats our own 2 s courtesy floor.
        assertEquals(1_000L, RetryBackoff.delayMs(500L, retryAfterMs = 1_000L, isRateLimit = true, rateLimitFloorMs = 2_000L, random = 0.5))
    }

    @Test
    fun zeroOrMissingRetryAfterFallsBackToBackoff() {
        assertEquals(500L, RetryBackoff.delayMs(1_000L, retryAfterMs = 0L, isRateLimit = false, rateLimitFloorMs = 2_000L, random = 0.0))
        assertEquals(500L, RetryBackoff.delayMs(1_000L, retryAfterMs = null, isRateLimit = false, rateLimitFloorMs = 2_000L, random = 0.0))
    }

    @Test
    fun equalJitterStaysWithinHalfToFullDelay() {
        assertEquals(500L, RetryBackoff.delayMs(1_000L, null, false, 2_000L, random = 0.0))
        assertEquals(750L, RetryBackoff.delayMs(1_000L, null, false, 2_000L, random = 0.5))
        assertEquals(1_000L, RetryBackoff.delayMs(1_000L, null, false, 2_000L, random = 1.0))
        repeat(200) {
            val d = RetryBackoff.delayMs(8_000L, null, false, 2_000L)
            assertTrue("delay=$d", d in 4_000L..8_000L)
        }
    }

    @Test
    fun jitterNeverUndercutsTheRateLimitFloor() {
        // 500 ms base jittered to 250 ms, but a 429 must wait at least the floor.
        assertEquals(2_000L, RetryBackoff.delayMs(500L, null, isRateLimit = true, rateLimitFloorMs = 2_000L, random = 0.0))
        // …while a large enough back-off still applies above the floor.
        assertEquals(4_000L, RetryBackoff.delayMs(8_000L, null, isRateLimit = true, rateLimitFloorMs = 2_000L, random = 0.0))
    }
}
