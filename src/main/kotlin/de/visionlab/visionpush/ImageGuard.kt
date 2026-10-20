package de.visionlab.visionpush

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Pure (Android-free) size guards for remote notification images.
 *
 * [NotificationBuilder] downloads big-picture / large-icon bitmaps from server-supplied
 * URLs. Two independent limits keep a hostile or merely oversized image from taking the
 * host process down with an `OutOfMemoryError`:
 *
 * 1. **Byte cap** ([readCapped]) — enforced on the bytes actually read, not on the
 *    `Content-Length` header (which is `-1` on chunked responses and can lie anyway).
 * 2. **Pixel budget** ([sampleSizeFor]) — the decoded bitmap, not the compressed file, is
 *    what consumes heap (4 bytes/pixel). A 200 KB PNG can still decode to 100 MP. The
 *    caller measures the bounds with `inJustDecodeBounds` and downsamples with the
 *    returned power-of-two `inSampleSize`, or rejects the image outright.
 *
 * Kept free of Android types so the arithmetic is unit-testable on a plain JVM.
 */
internal object ImageGuard {

    /** Hard cap on the compressed download size (5 MB). */
    const val MAX_BYTES: Int = 5 * 1024 * 1024

    /** Decoded-pixel budget per bitmap (4 MP ≈ 16 MB ARGB_8888). */
    const val MAX_PIXELS: Long = 4_000_000L

    /** Largest `inSampleSize` we are willing to use before rejecting an image. */
    const val MAX_SAMPLE_SIZE: Int = 64

    /**
     * Reads [input] to the end and returns its bytes, or `null` as soon as more than
     * [maxBytes] have been read — regardless of any declared content length. The stream
     * is not closed; the caller owns it.
     */
    fun readCapped(input: InputStream, maxBytes: Int): ByteArray? {
        require(maxBytes >= 0) { "maxBytes must be non-negative" }
        val out = ByteArrayOutputStream(minOf(maxBytes, INITIAL_BUFFER_BYTES))
        val buffer = ByteArray(CHUNK_BYTES)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /**
     * Power-of-two `BitmapFactory.Options.inSampleSize` that brings a [width]×[height]
     * image within [maxPixels] decoded pixels. Returns `1` when the image already fits,
     * or `null` when the bounds are invalid (unparseable image → `0`/negative) or the
     * image would need a sample size above [MAX_SAMPLE_SIZE] — i.e. it is so large that
     * it is rejected rather than downsampled.
     */
    fun sampleSizeFor(width: Int, height: Int, maxPixels: Long = MAX_PIXELS): Int? {
        if (width <= 0 || height <= 0 || maxPixels <= 0) return null
        var sample = 1
        // BitmapFactory divides each dimension by inSampleSize (integer division), so
        // mirror that rounding when predicting the decoded pixel count.
        while ((width.toLong() / sample) * (height.toLong() / sample) > maxPixels) {
            sample *= 2
            if (sample > MAX_SAMPLE_SIZE) return null
        }
        return sample
    }

    private const val CHUNK_BYTES = 8 * 1024
    private const val INITIAL_BUFFER_BYTES = 64 * 1024
}
