package com.siamakerlab.vibecoder.server.notify

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
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * v0.50.0 — Phase 29 RFC 8291 Web Push payload encryption (`aes128gcm` content-encoding).
 *
 * Pure JDK 8+ stdlib (`javax.crypto` + `java.security`); no BouncyCastle / web-push-java.
 *
 * Algorithm (per RFC 8291 §3.4):
 *   1. Generate ephemeral P-256 keypair (AS = Application Server).
 *   2. ECDH shared secret with UA public key (from subscription.p256dh).
 *   3. IKM       = HKDF(salt = auth_secret, IKM = ECDH, info = "WebPush: info" || 0x00 || ua_pub || as_pub, L = 32)
 *   4. CEK       = HKDF(salt = salt(16 rand), IKM = IKM, info = "Content-Encoding: aes128gcm" || 0x00, L = 16)
 *   5. NONCE     = HKDF(salt = salt,         IKM = IKM, info = "Content-Encoding: nonce"     || 0x00, L = 12)
 *   6. plaintext_with_pad = payload || 0x02 || zeroes (so the total record length stays under [recordSize] - 16)
 *   7. ciphertext = AES-128-GCM(key = CEK, iv = NONCE, plaintext_with_pad) — includes 16-byte tag
 *   8. body = salt(16) || record_size(4 BE) || keyid_len(1) || as_public(65) || ciphertext
 *
 * The returned ByteArray is what goes straight into the POST body with
 * `Content-Encoding: aes128gcm` (RFC 8188).
 */
object Aes128GcmEncrypt {

    private const val RECORD_SIZE = 4096
    private val random = SecureRandom()

    private val ecParameterSpec: ECParameterSpec by lazy {
        val params = AlgorithmParameters.getInstance("EC")
        params.init(ECGenParameterSpec("secp256r1"))
        params.getParameterSpec(ECParameterSpec::class.java)
    }

    /**
     * Encrypt [payload] for the given subscription.
     *
     * @param payload    plaintext bytes (e.g. UTF-8 JSON)
     * @param uaPublicRaw  subscription.p256dh as raw 65-byte uncompressed point (04||X||Y)
     * @param authSecret subscription.auth, 16 bytes
     */
    fun encrypt(payload: ByteArray, uaPublicRaw: ByteArray, authSecret: ByteArray): ByteArray {
        require(uaPublicRaw.size == 65 && uaPublicRaw[0] == 0x04.toByte()) {
            "ua public key must be 65-byte uncompressed P-256 point (got ${uaPublicRaw.size} bytes)"
        }
        require(authSecret.size == 16) { "auth secret must be 16 bytes (got ${authSecret.size})" }
        require(payload.size <= RECORD_SIZE - 16 - 1) {
            "payload too large for single record: ${payload.size} > ${RECORD_SIZE - 17}"
        }

        // 1-2. ephemeral keypair + ECDH.
        val kpg = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val asKp = kpg.generateKeyPair()
        val asPrivate = asKp.private as ECPrivateKey
        val asPublic = asKp.public as ECPublicKey
        val asPublicRaw = uncompressedRaw(asPublic)

        val uaPublicKey = decodeP256Point(uaPublicRaw)
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(asPrivate)
        ka.doPhase(uaPublicKey, true)
        val ecdh = ka.generateSecret()  // 32 bytes

        // 3. IKM = HKDF(salt=auth, IKM=ecdh, info="WebPush: info\0" + ua_pub + as_pub, L=32)
        val infoIkm = concat(
            "WebPush: info".toByteArray(Charsets.US_ASCII),
            byteArrayOf(0),
            uaPublicRaw,
            asPublicRaw,
        )
        val ikm = hkdfSha256(salt = authSecret, ikm = ecdh, info = infoIkm, length = 32)

        // 4-5. salt(16 rand) → CEK + NONCE.
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val cek = hkdfSha256(
            salt = salt, ikm = ikm,
            info = "Content-Encoding: aes128gcm".toByteArray(Charsets.US_ASCII) + byteArrayOf(0),
            length = 16,
        )
        val nonce = hkdfSha256(
            salt = salt, ikm = ikm,
            info = "Content-Encoding: nonce".toByteArray(Charsets.US_ASCII) + byteArrayOf(0),
            length = 12,
        )

        // 6. plaintext with padding: payload || 0x02 || zeros (until record_size - 16 = 4080 bytes).
        val maxPlainLen = RECORD_SIZE - 16  // GCM tag is 16 bytes
        val plain = ByteArray(maxPlainLen)
        System.arraycopy(payload, 0, plain, 0, payload.size)
        plain[payload.size] = 0x02  // last-record marker
        // remainder already zero-filled by ByteArray constructor.

        // 7. AES-128-GCM.
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce))
        val ciphertext = cipher.doFinal(plain)

        // 8. Header: salt(16) || record_size(4 BE) || keyid_len(1) || keyid(65) || ciphertext.
        val out = java.io.ByteArrayOutputStream()
        out.write(salt)
        out.write(intBe(RECORD_SIZE))
        out.write(byteArrayOf(asPublicRaw.size.toByte()))  // 65
        out.write(asPublicRaw)
        out.write(ciphertext)
        return out.toByteArray()
    }

    // HKDF-SHA256 (RFC 5869). When [length] ≤ 32 (one HMAC block) we collapse extract+expand.
    private fun hkdfSha256(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length <= 32) { "this minimal HKDF supports L ≤ 32 (got $length)" }
        // extract
        val prk = hmacSha256(key = salt, data = ikm)
        // expand: T(1) = HMAC(PRK, info || 0x01)
        val expanded = hmacSha256(key = prk, data = info + byteArrayOf(0x01))
        return expanded.copyOf(length)
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun uncompressedRaw(pub: ECPublicKey): ByteArray {
        val x = unsignedFixed(pub.w.affineX, 32)
        val y = unsignedFixed(pub.w.affineY, 32)
        val raw = ByteArray(65)
        raw[0] = 0x04
        System.arraycopy(x, 0, raw, 1, 32)
        System.arraycopy(y, 0, raw, 33, 32)
        return raw
    }

    private fun decodeP256Point(raw: ByteArray): ECPublicKey {
        val x = BigInteger(1, raw.copyOfRange(1, 33))
        val y = BigInteger(1, raw.copyOfRange(33, 65))
        return KeyFactory.getInstance("EC").generatePublic(
            ECPublicKeySpec(ECPoint(x, y), ecParameterSpec),
        ) as ECPublicKey
    }

    private fun unsignedFixed(v: BigInteger, len: Int): ByteArray {
        val raw = v.toByteArray()
        return when {
            raw.size == len -> raw
            raw.size == len + 1 && raw[0].toInt() == 0 -> raw.copyOfRange(1, raw.size)
            raw.size < len -> ByteArray(len).also { raw.copyInto(it, len - raw.size) }
            else -> raw.copyOfRange(raw.size - len, raw.size)
        }
    }

    private fun intBe(v: Int): ByteArray = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte(),
    )

    private fun concat(vararg parts: ByteArray): ByteArray {
        val total = parts.sumOf { it.size }
        val out = ByteArray(total)
        var off = 0
        for (p in parts) {
            p.copyInto(out, off)
            off += p.size
        }
        return out
    }
}
