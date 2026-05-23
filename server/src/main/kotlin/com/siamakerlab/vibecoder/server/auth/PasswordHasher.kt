package com.siamakerlab.vibecoder.server.auth

import at.favre.lib.crypto.bcrypt.BCrypt

/**
 * BCrypt 비밀번호 해시/검증.
 *
 * cost 12 → 현대 CPU 기준 ~250ms / 검증. brute force 비용은 충분히 높이면서
 * 로그인 응답 지연은 사람이 거의 못 느낌.
 *
 * v0.12.4 — CharArray API 가 primary. String overload 는 호출 직후 임시 CharArray
 * 를 zeroing 해 메모리 노출 시간을 줄임. JVM String pool 에 이미 잡혀 있을 수 있어
 * 완벽 zeroing 은 불가능하나 best-practice — 힙 덤프 / core dump 노출 표면 축소.
 */
class PasswordHasher(private val cost: Int = 12) {

    fun hash(plain: CharArray): String {
        require(plain.isNotEmpty()) { "password must not be empty" }
        return BCrypt.withDefaults().hashToString(cost, plain)
    }

    /**
     * String 으로 받는 호환 경로. 호출 직후 임시 CharArray 를 zeroing.
     * String 자체는 JVM 이 GC 할 때까지 남으므로 호출자가 가능하면 CharArray API 사용 권장.
     */
    fun hash(plain: String): String {
        val arr = plain.toCharArray()
        try {
            return hash(arr)
        } finally {
            arr.fill(' ')
        }
    }

    fun verify(plain: CharArray, hash: String): Boolean {
        if (plain.isEmpty()) return false
        if (hash.isEmpty()) return false
        // hash 도 자체 CharArray 로 옮기되 hash 는 secret 아님 (storage 형태).
        // verifier 가 byte[] 로 내부 복사하므로 추가 zeroing 의미 없음.
        return runCatching {
            BCrypt.verifyer().verify(plain, hash.toCharArray()).verified
        }.getOrDefault(false)
    }

    fun verify(plain: String, hash: String): Boolean {
        val arr = plain.toCharArray()
        try {
            return verify(arr, hash)
        } finally {
            arr.fill(' ')
        }
    }
}

object PasswordPolicy {
    const val MIN_LENGTH = 8
    private val PATTERN = Regex("^(?=.*[A-Za-z])(?=.*\\d).{$MIN_LENGTH,}$")

    /**
     * 정책 검증. 위반 시 한국어 사유 반환, 통과 시 null.
     */
    fun violation(password: String): String? = when {
        password.length < MIN_LENGTH -> "비밀번호는 ${MIN_LENGTH}자 이상이어야 합니다."
        !password.any { it.isLetter() } -> "비밀번호에 영문자가 1개 이상 포함되어야 합니다."
        !password.any { it.isDigit() } -> "비밀번호에 숫자가 1개 이상 포함되어야 합니다."
        !PATTERN.matches(password) -> "비밀번호 형식이 정책에 맞지 않습니다 (영문+숫자 ${MIN_LENGTH}자 이상)."
        else -> null
    }
}

object UsernamePolicy {
    private val PATTERN = Regex("^[a-zA-Z0-9._-]{3,32}$")
    fun violation(username: String): String? = when {
        username.length < 3 -> "사용자명은 3자 이상이어야 합니다."
        username.length > 32 -> "사용자명은 32자 이하여야 합니다."
        !PATTERN.matches(username) -> "사용자명은 영문/숫자/._- 만 사용할 수 있습니다."
        else -> null
    }
}
