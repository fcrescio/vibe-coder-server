package com.siamakerlab.vibecoder.server.auth

import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * v0.26.0 — TOTP (Time-based One-Time Password, RFC 6238).
 *
 * Google Authenticator / 1Password / Authy 호환:
 *   - Algorithm: HMAC-SHA1
 *   - Period: 30s
 *   - Digits: 6
 *   - Secret encoding: Base32 (RFC 4648, no padding in the URI)
 *
 * 자체 구현 (라이브러리 추가 없음, ~80 LOC). JDK 표준 `javax.crypto.Mac` 만 사용.
 *
 * 사용 흐름 (서버):
 *   1. enable: generateSecret() → users.totpSecret = base32 → otpauthUri(...) →
 *      사용자가 Authenticator 로 QR 스캔 (또는 secret 수동 입력).
 *   2. verify on login: 6자리 코드 + 사용자의 totpSecret → window=1 (이전/현재/다음
 *      30s 슬롯 허용) 으로 비교. drift 보정.
 *   3. disable: users.totpSecret = null.
 */
object Totp {

    private const val PERIOD_SECONDS = 30L
    private const val DIGITS = 6
    private const val SECRET_BYTES = 20  // 160 bits, RFC 6238 권장

    /** 새 Base32 비밀키 생성. SecureRandom 기반. */
    fun generateSecret(): String {
        val raw = ByteArray(SECRET_BYTES)
        SecureRandom().nextBytes(raw)
        return Base32.encode(raw)
    }

    /**
     * otpauth://totp/<issuer>:<account>?secret=<base32>&issuer=<issuer>&algorithm=SHA1&digits=6&period=30
     *
     * Authenticator 앱이 QR 로 스캔할 수 있는 표준 URI. 외부 QR 생성기 없이도
     * 사용자가 직접 secret 을 입력해도 동일 결과.
     */
    fun otpauthUri(issuer: String, account: String, base32Secret: String): String {
        val label = URLEncoder.encode("$issuer:$account", StandardCharsets.UTF_8)
        val issuerEnc = URLEncoder.encode(issuer, StandardCharsets.UTF_8)
        return "otpauth://totp/$label?secret=$base32Secret&issuer=$issuerEnc&algorithm=SHA1&digits=6&period=30"
    }

    /**
     * 코드 검증. window=1 = 이전 30s + 현재 + 다음 30s 슬롯 모두 허용 (clock drift 보정).
     * 정상 단말은 거의 항상 현재 슬롯에서 통과.
     *
     * @return true = 매치, false = 불일치 또는 코드 형식 오류.
     */
    fun verify(base32Secret: String, code: String, windowSlots: Int = 1, nowSeconds: Long = System.currentTimeMillis() / 1000): Boolean {
        if (code.length != DIGITS || !code.all { it.isDigit() }) return false
        val key = runCatching { Base32.decode(base32Secret) }.getOrNull() ?: return false
        val expected = code.toInt()
        val currentSlot = nowSeconds / PERIOD_SECONDS
        for (offset in -windowSlots..windowSlots) {
            val v = generate(key, currentSlot + offset)
            if (constantTimeEquals(v, expected)) return true
        }
        return false
    }

    private fun generate(key: ByteArray, slot: Long): Int {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        val counter = ByteBuffer.allocate(8).putLong(slot).array()
        val hash = mac.doFinal(counter)
        // Dynamic truncation (RFC 4226 §5.3)
        val offset = (hash.last().toInt() and 0x0f)
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
                ((hash[offset + 1].toInt() and 0xff) shl 16) or
                ((hash[offset + 2].toInt() and 0xff) shl 8) or
                (hash[offset + 3].toInt() and 0xff)
        return binary % pow10(DIGITS)
    }

    private fun constantTimeEquals(a: Int, b: Int): Boolean {
        // 6자리 숫자라 timing attack 위험 사실상 없음. 그래도 일관된 정책.
        return a == b
    }

    private fun pow10(n: Int): Int {
        var r = 1
        repeat(n) { r *= 10 }
        return r
    }
}

/**
 * RFC 4648 Base32 (uppercase A-Z, 2-7). padding 자동.
 * google-authenticator 호환 위해 padding 은 encode 시엔 생략 가능.
 */
internal object Base32 {
    private const val ALPHA = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun encode(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val sb = StringBuilder((data.size * 8 + 4) / 5)
        var buffer = 0
        var bitsLeft = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                val idx = (buffer shr (bitsLeft - 5)) and 0x1f
                sb.append(ALPHA[idx])
                bitsLeft -= 5
            }
        }
        if (bitsLeft > 0) {
            val idx = (buffer shl (5 - bitsLeft)) and 0x1f
            sb.append(ALPHA[idx])
        }
        return sb.toString()
    }

    fun decode(s: String): ByteArray {
        val clean = s.trim().uppercase().replace(" ", "").trimEnd('=')
        if (clean.isEmpty()) return ByteArray(0)
        val out = ByteArray(clean.length * 5 / 8)
        var buffer = 0
        var bitsLeft = 0
        var idx = 0
        for (ch in clean) {
            val pos = ALPHA.indexOf(ch)
            require(pos >= 0) { "invalid base32 char: $ch" }
            buffer = (buffer shl 5) or pos
            bitsLeft += 5
            if (bitsLeft >= 8) {
                out[idx++] = ((buffer shr (bitsLeft - 8)) and 0xff).toByte()
                bitsLeft -= 8
            }
        }
        return out.copyOf(idx)
    }
}
