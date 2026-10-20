package de.visionlab.visionpush

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Plain-JVM tests for [ImageGuard] — the byte cap and pixel budget that keep an
 * oversized notification image from OOM-crashing the host app.
 */
class ImageGuardTest {

    // ── readCapped ────────────────────────────────────────────────────────────

    @Test
    fun readCapped_returnsAllBytesWithinCap() {
        val data = ByteArray(10_000) { (it % 251).toByte() }
        val out = ImageGuard.readCapped(ByteArrayInputStream(data), maxBytes = 20_000)
        assertNotNull(out)
        assertArrayEquals(data, out)
    }

    @Test
    fun readCapped_acceptsInputExactlyAtCap() {
        val data = ByteArray(8 * 1024 + 3) { 7 }
        val out = ImageGuard.readCapped(ByteArrayInputStream(data), maxBytes = data.size)
        assertNotNull(out)
        assertEquals(data.size, out!!.size)
    }

    @Test
    fun readCapped_returnsNullOneBytePastCap() {
        val data = ByteArray(4_097) { 1 }
        assertNull(ImageGuard.readCapped(ByteArrayInputStream(data), maxBytes = 4_096))
    }

    @Test
    fun readCapped_stopsReadingAnUnboundedStream() {
        // A chunked response has contentLength == -1 and could be endless; the cap must
        // be enforced on the bytes actually read, and the read must terminate.
        var served = 0L
        val endless = object : InputStream() {
            override fun read(): Int { served++; return 0 }
            override fun read(b: ByteArray, off: Int, len: Int): Int { served += len; return len }
        }
        assertNull(ImageGuard.readCapped(endless, maxBytes = 64 * 1024))
        // Read no more than the cap plus one buffer chunk.
        assertTrue("served=$served", served <= 64 * 1024 + 8 * 1024)
    }

    @Test
    fun readCapped_emptyStreamIsEmptyArray() {
        val out = ImageGuard.readCapped(ByteArrayInputStream(ByteArray(0)), maxBytes = 10)
        assertNotNull(out)
        assertEquals(0, out!!.size)
    }

    // ── sampleSizeFor ─────────────────────────────────────────────────────────

    @Test
    fun sampleSizeFor_smallImageIsNotDownsampled() {
        assertEquals(1, ImageGuard.sampleSizeFor(1_024, 768, maxPixels = 4_000_000L))
    }

    @Test
    fun sampleSizeFor_imageExactlyAtBudgetFits() {
        assertEquals(1, ImageGuard.sampleSizeFor(2_000, 2_000, maxPixels = 4_000_000L))
    }

    @Test
    fun sampleSizeFor_downsamplesByPowersOfTwo() {
        // 16 MP → /2 per side = 4 MP (fits)
        assertEquals(2, ImageGuard.sampleSizeFor(4_000, 4_000, maxPixels = 4_000_000L))
        // 64 MP → /4 per side = 4 MP (fits)
        assertEquals(4, ImageGuard.sampleSizeFor(8_000, 8_000, maxPixels = 4_000_000L))
        // 12 MP landscape → /2 = 3 MP
        assertEquals(2, ImageGuard.sampleSizeFor(4_000, 3_000, maxPixels = 4_000_000L))
    }

    @Test
    fun sampleSizeFor_resultAlwaysWithinBudget() {
        val budget = 4_000_000L
        for ((w, h) in listOf(3_000 to 3_000, 5_000 to 7_000, 10_000 to 10_000, 30_000 to 2_000)) {
            val s = ImageGuard.sampleSizeFor(w, h, budget)
            assertNotNull("$w x $h", s)
            val pixels = (w.toLong() / s!!) * (h.toLong() / s)
            assertTrue("$w x $h @ $s → $pixels px", pixels <= budget)
        }
    }

    @Test
    fun sampleSizeFor_rejectsInvalidBounds() {
        // BitmapFactory reports 0/-1 bounds for undecodable data.
        assertNull(ImageGuard.sampleSizeFor(0, 0))
        assertNull(ImageGuard.sampleSizeFor(-1, 100))
        assertNull(ImageGuard.sampleSizeFor(100, 0))
    }

    @Test
    fun sampleSizeFor_rejectsImageBeyondMaxSampleSize() {
        // 10^12 pixels would need inSampleSize 512 — above MAX_SAMPLE_SIZE → reject.
        assertNull(ImageGuard.sampleSizeFor(1_000_000, 1_000_000, maxPixels = 4_000_000L))
    }

    @Test
    fun sampleSizeFor_largestAcceptedSampleIsMax() {
        // 100k × 100k = 10^10 px → /64 per side = 2.44 MP → accepted at exactly 64.
        assertEquals(ImageGuard.MAX_SAMPLE_SIZE, ImageGuard.sampleSizeFor(100_000, 100_000, maxPixels = 4_000_000L))
    }

    @Test
    fun defaults_areTheDocumentedLimits() {
        assertEquals(5 * 1024 * 1024, ImageGuard.MAX_BYTES)
        assertEquals(4_000_000L, ImageGuard.MAX_PIXELS)
    }
}
