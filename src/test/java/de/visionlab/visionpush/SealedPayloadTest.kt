package de.visionlab.visionpush

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64
import java.util.Random

/**
 * Sealed-payload decryption tests.
 *
 * The headline is a CROSS-LANGUAGE vector: the fixture below was produced by the
 * Rust `ece` crate (visionpush-core `seal::tests::emit_web_decrypt_fixture`) and is
 * the SAME fixture the web (WebCrypto) and iOS (CryptoKit) tests decrypt.
 * Decrypting it here with the JDK's javax.crypto proves this implementation is
 * byte-for-byte wire-compatible with the server seal — pure JVM, no device or FCM.
 */
class SealedPayloadTest {

    private val privB64 = "XvOaUs7GYeDFPwdn4RYvo2Xwquq2Gl4vJgT7_kZhKcQ"
    private val pubB64 =
        "BD4WUncpbD41c6T6_kecfXXgvmptHruYcwK5tQzpcukUY2sFXGuf8Kz7xlL_A2AmauWXzEY4NzlkPbEybvI47Z8"
    private val authB64 = "HUub15ebEQdpv10EOs9lAw"
    private val ctB64 =
        "eJu6Ac099cPdX3g4rRMGNgAAEABBBBECx-7qqsB2u3SJaLeeq0yXSB5xDXZactrwSwEJ2OkyblDctxJnywkOpU0j6urQ0k8veeZrlUYLtmPneYRro7OmwzS0BVFmtl9Z8hQ_2e0BNhgmR5zFi-dPakQtMGIE-L-Dg5Cs1S6KRASVy1ZYgdcD2plEaLzrIE2DhGWU7XpxhUjc1HFaogN3v2GTypfYUXNv4IVtt--Zp_ByJiz8SejCmfN1kJEw7zLOGiO7kArZswUCchRAbdNCL9-95NMvYg7W-MAS6PnqHSyZYK4jYm-BlbjRFNdc9RTsi0J17rDwI-tUXpWCmljAV80flttQl3mqh_JpGdJm_YIBPzNSS5Dl3uFRePXT-zYiYW2tP4mT-rYCmf2si_YWkUgjOB78YgSQ7DWfvsphfZbIBKmr_1dOoQrouRnjLwQYuopDxmy97K8uSIM0cdV7MpfWMvCCsg"

    private fun keys() = VisionPushSeal.DeviceKeys(
        VisionPushSeal.b64urlDecode(privB64),
        VisionPushSeal.b64urlDecode(pubB64),
        VisionPushSeal.b64urlDecode(authB64),
    )

    @Test
    fun decryptsRustProducedEnvelopeToExactPlaintext() {
        val obj = VisionPushSeal.openJson(ctB64, keys())
        assertEquals("Quartalszahlen SECRET_MARKER", obj.getString("title"))
        assertEquals("Umsatz +42% — streng vertraulich", obj.getString("body"))
        assertEquals("https://example.com/q3", obj.getString("url"))
        assertEquals("B", obj.getJSONObject("custom_data").getString("variant"))
    }

    @Test(expected = VisionPushSeal.SealException::class)
    fun wrongKeyFailsClosed() {
        VisionPushSeal.open(ctB64, VisionPushSeal.generateKeys().keys)
    }

    @Test(expected = VisionPushSeal.SealException::class)
    fun corruptCiphertextFailsClosed() {
        val tampered = ctB64.dropLast(1) + if (ctB64.endsWith("A")) "B" else "A"
        VisionPushSeal.open(tampered, keys())
    }

    @Test
    fun isSealedDiscriminates() {
        assertTrue(VisionPushSeal.isSealed(JSONObject("""{"enc":{"enc":"vp-seal-v1","ciphertext":"x"}}""")))
        assertFalse(VisionPushSeal.isSealed(JSONObject("""{"title":"Hi"}""")))
        assertFalse(VisionPushSeal.isSealed(JSONObject("""{"enc":{"enc":"other"}}""")))
    }

    @Test
    fun unsealFcmDataMergesContentAndDropsEnvelope() {
        val sealed = JSONObject()
            .put("id", "n1")
            .put("channel_id", "default")
            .put("title", "🔒 Encrypted message")
            .put("vp_sealed", true)
            .put(
                "enc",
                JSONObject().put("enc", "vp-seal-v1").put("alg", "p256-aes128gcm-ece").put("ciphertext", ctB64),
            )
        val out = VisionPushSeal.unsealFcmData(mapOf("vp_payload" to sealed.toString()), keys())
        val p = JSONObject(out["vp_payload"]!!)
        assertEquals("Quartalszahlen SECRET_MARKER", p.getString("title"))
        assertEquals("https://example.com/q3", p.getString("url"))
        assertEquals("n1", p.getString("id"))
        assertFalse(p.has("enc"))
    }

    @Test
    fun unsealFcmDataFailsClosedWithoutKeys() {
        val sealed = JSONObject()
            .put("title", "🔒 Encrypted message")
            .put("enc", JSONObject().put("enc", "vp-seal-v1").put("ciphertext", ctB64))
        val out = VisionPushSeal.unsealFcmData(mapOf("vp_payload" to sealed.toString()), null)
        val p = JSONObject(out["vp_payload"]!!)
        assertEquals("🔒 Encrypted message", p.getString("title"))
        assertFalse(p.toString().contains("SECRET_MARKER"))
    }

    @Test
    fun generateKeysShape() {
        val g = VisionPushSeal.generateKeys()
        assertEquals(32, g.keys.privateKey.size)
        assertEquals(65, g.keys.publicKey.size)
        assertEquals(0x04, g.keys.publicKey[0].toInt() and 0xFF)
        assertEquals(16, g.keys.auth.size)
        assertEquals(VisionPushSeal.ALG, g.alg)
    }

    // ── Base64Url codec (hand-rolled: java.util.Base64 needs API 26, minSdk is 21) ──

    @Test
    fun base64url_matchesJdkReferenceForAllLengths() {
        val rnd = Random(0x5EA1)
        val jdkUnpadded = Base64.getUrlEncoder().withoutPadding()
        val jdkPadded = Base64.getUrlEncoder()
        val jdkDecoder = Base64.getUrlDecoder()
        for (len in 0..96) {
            repeat(8) {
                val bytes = ByteArray(len).also(rnd::nextBytes)
                val ours = Base64Url.encode(bytes)
                assertEquals("encode len=$len", jdkUnpadded.encodeToString(bytes), ours)
                assertArrayEquals("decode unpadded len=$len", bytes, Base64Url.decode(ours))
                assertArrayEquals("decode padded len=$len", bytes, Base64Url.decode(jdkPadded.encodeToString(bytes)))
                assertArrayEquals("jdk decodes ours len=$len", bytes, jdkDecoder.decode(ours))
            }
        }
    }

    @Test
    fun base64url_usesUrlSafeAlphabetWithoutPadding() {
        // 0xFB 0xFF is "+/8=" in classic base64 and "-_8" in base64url.
        val enc = Base64Url.encode(byteArrayOf(0xFB.toByte(), 0xFF.toByte()))
        assertEquals("-_8", enc)
        assertEquals("", Base64Url.encode(ByteArray(0)))
        assertArrayEquals(ByteArray(0), Base64Url.decode(""))
    }

    @Test
    fun base64url_rejectsMalformedInputLikeTheJdk() {
        for (bad in listOf("a", "abcde", "abc+", "ab/c", "ab=c", "ab cd", "\u00e4bcd")) {
            try {
                Base64Url.decode(bad)
                fail("expected IllegalArgumentException for '$bad'")
            } catch (expected: IllegalArgumentException) {
                // matches java.util.Base64.getUrlDecoder(), which open() relies on
            }
        }
    }
}
