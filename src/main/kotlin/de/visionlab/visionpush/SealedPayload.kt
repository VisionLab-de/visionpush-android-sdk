package de.visionlab.visionpush

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * On-device sealed-payload decryption for VisionPush.
 *
 * A sealed push carries an opaque envelope instead of plaintext content. The
 * envelope's `ciphertext` is a complete RFC 8291 `aes128gcm` record — the exact
 * wire format the server produces (the Rust `ece` crate) and the web + iOS SDKs
 * decrypt. We decrypt it with the JDK's own crypto (P-256 ECDH + HKDF-SHA256 +
 * AES-128-GCM); no third-party dependency and no Android-only APIs, so the core is
 * pure-JVM unit-testable and runs unchanged in `onMessageReceived`. The device
 * private key never leaves the device.
 */
object VisionPushSeal {
    const val VERSION = "vp-seal-v1"
    const val ALG = "p256-aes128gcm-ece"

    /** The device's sealed-payload key material (raw bytes, as registered/stored). */
    data class DeviceKeys(
        val privateKey: ByteArray, // raw 32-byte P-256 scalar
        val publicKey: ByteArray,  // raw 65-byte uncompressed point (0x04 || X || Y)
        val auth: ByteArray,       // 16-byte auth secret
    )

    /** A freshly generated keypair + auth secret + base64url public parts to register. */
    data class GeneratedKeys(
        val keys: DeviceKeys,
        val pubkeyB64: String,
        val authB64: String,
        val alg: String,
    )

    class SealException(message: String) : Exception(message)

    /** True if a parsed `vp_payload` carries a sealed envelope under `enc`. */
    fun isSealed(payload: JSONObject): Boolean =
        payload.optJSONObject("enc")?.optString("enc") == VERSION

    private fun ecParams(): ECParameterSpec =
        AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec("secp256r1"))
            getParameterSpec(ECParameterSpec::class.java)
        }

    private fun fixedLen(b: BigInteger, len: Int): ByteArray {
        val raw = b.toByteArray() // may have a leading sign byte or be short
        val out = ByteArray(len)
        when {
            raw.size == len -> System.arraycopy(raw, 0, out, 0, len)
            raw.size == len + 1 -> System.arraycopy(raw, 1, out, 0, len) // drop sign byte
            raw.size < len -> System.arraycopy(raw, 0, out, len - raw.size, raw.size)
            else -> System.arraycopy(raw, raw.size - len, out, 0, len)
        }
        return out
    }

    /** Generate a P-256 keypair + 16-byte auth secret for sealed delivery. */
    fun generateKeys(): GeneratedKeys {
        val kpg = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val kp = kpg.generateKeyPair()
        val priv = kp.private as ECPrivateKey
        val pub = kp.public as ECPublicKey
        val privRaw = fixedLen(priv.s, 32)
        val w = pub.w
        val pubRaw = ByteArray(65)
        pubRaw[0] = 0x04
        System.arraycopy(fixedLen(w.affineX, 32), 0, pubRaw, 1, 32)
        System.arraycopy(fixedLen(w.affineY, 32), 0, pubRaw, 33, 32)
        val auth = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return GeneratedKeys(
            keys = DeviceKeys(privRaw, pubRaw, auth),
            pubkeyB64 = b64urlEncode(pubRaw),
            authB64 = b64urlEncode(auth),
            alg = ALG,
        )
    }

    /** HKDF-SHA256(salt, ikm, info) → `len` bytes (RFC 5869). */
    private fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, len: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        // Extract.
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        // Expand.
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(len)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < len) {
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal() // Mac auto-resets, retaining the PRK key
            val n = minOf(t.size, len - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }

    /**
     * Decrypt a sealed envelope's `ciphertext` (base64url RFC 8291 record) to its
     * plaintext bytes using the device keys. Throws (fails closed) on any error.
     */
    fun open(ciphertextB64: String, keys: DeviceKeys): ByteArray {
        val record = try {
            b64urlDecode(ciphertextB64)
        } catch (e: IllegalArgumentException) {
            throw SealException("malformed base64url")
        }
        if (record.size < 21) throw SealException("record too short")

        val salt = record.copyOfRange(0, 16)
        val idlen = record[20].toInt() and 0xFF
        val headerEnd = 21 + idlen
        if (record.size <= headerEnd) throw SealException("truncated header")
        val serverEpk = record.copyOfRange(21, headerEnd) // server ephemeral public key (65)
        val body = record.copyOfRange(headerEnd, record.size) // ciphertext || 16-byte tag
        if (body.size <= 16) throw SealException("missing ciphertext/tag")

        val ecSpec = ecParams()
        val kf = KeyFactory.getInstance("EC")
        val privKey = kf.generatePrivate(ECPrivateKeySpec(BigInteger(1, keys.privateKey), ecSpec))
        if (serverEpk.size != 65 || serverEpk[0].toInt() != 0x04) throw SealException("bad server epk")
        val sx = BigInteger(1, serverEpk.copyOfRange(1, 33))
        val sy = BigInteger(1, serverEpk.copyOfRange(33, 65))
        val pubKey = kf.generatePublic(ECPublicKeySpec(ECPoint(sx, sy), ecSpec))

        val ka = KeyAgreement.getInstance("ECDH").apply {
            init(privKey)
            doPhase(pubKey, true)
        }
        val shared = ka.generateSecret() // 32-byte shared secret

        // RFC 8291 IKM, then RFC 8188 CEK + nonce.
        val keyInfo = "WebPush: info".toByteArray(Charsets.US_ASCII) + byteArrayOf(0) + keys.publicKey + serverEpk
        val ikm = hkdf(keys.auth, shared, keyInfo, 32)
        val cek = hkdf(salt, ikm, "Content-Encoding: aes128gcm".toByteArray(Charsets.US_ASCII) + byteArrayOf(0), 16)
        val nonce = hkdf(salt, ikm, "Content-Encoding: nonce".toByteArray(Charsets.US_ASCII) + byteArrayOf(0), 12)

        val plainPadded = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce))
                doFinal(body) // body includes the 16-byte tag; GCM verifies it
            }
        } catch (e: Exception) {
            throw SealException("decrypt failed")
        }

        // Strip RFC 8188 padding: trailing zero bytes then the delimiter (0x01/0x02).
        var end = plainPadded.size - 1
        while (end >= 0 && plainPadded[end].toInt() == 0) end--
        if (end < 0) throw SealException("no padding delimiter")
        return plainPadded.copyOfRange(0, end)
    }

    /** Decrypt + JSON-parse the recovered content object. */
    fun openJson(ciphertextB64: String, keys: DeviceKeys): JSONObject =
        JSONObject(String(open(ciphertextB64, keys), Charsets.UTF_8))

    /**
     * Unseal an FCM data map: if its `vp_payload` carries a sealed envelope (Mode
     * A/B) and `keys` are available, decrypt and merge the recovered content fields
     * onto `vp_payload` (dropping the opaque envelope), then return a copy with the
     * rewritten `vp_payload`. Otherwise returns the map unchanged.
     *
     * **Fails closed:** on missing keys or any decrypt error the ORIGINAL vp_payload
     * is kept — and the server already put a neutral placeholder title there for
     * sealed sends, so the notification still shows something safe, never the
     * plaintext and never a crash. Pure JVM (org.json only) so it is unit-testable
     * off-device; `onMessageReceived` is then a one-line call.
     */
    fun unsealFcmData(data: Map<String, String>, keys: DeviceKeys?): Map<String, String> {
        val raw = data["vp_payload"] ?: return data
        val payload = try {
            JSONObject(raw)
        } catch (e: Exception) {
            return data
        }
        if (!isSealed(payload) || keys == null) return data
        return try {
            val ciphertext = payload.getJSONObject("enc").getString("ciphertext")
            val inner = openJson(ciphertext, keys)
            val it = inner.keys()
            while (it.hasNext()) {
                val k = it.next()
                payload.put(k, inner.get(k))
            }
            payload.remove("enc")
            payload.remove("vp_sealed")
            data.toMutableMap().apply { put("vp_payload", payload.toString()) }
        } catch (e: Exception) {
            data // fail closed — keep the placeholder vp_payload
        }
    }

    fun b64urlDecode(s: String): ByteArray = Base64.getUrlDecoder().decode(s)
    fun b64urlEncode(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
}
