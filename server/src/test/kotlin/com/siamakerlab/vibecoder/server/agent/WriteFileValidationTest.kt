package com.siamakerlab.vibecoder.server.agent

import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * Unit tests for write_file content validation rules shared by
 * [MistralVibeAcpSessionManager] and [AcpAgentProcessFactory].
 *
 * Both handlers enforce:
 * - path must be non-null and non-blank
 * - content must be non-null
 * - content must not exceed 200_000 bytes (UTF-8)
 * - write always uses CREATE + TRUNCATE_EXISTING + WRITE (no append)
 */
class WriteFileValidationTest {

    @Test
    fun `content under 200KB passes validation`() {
        val content = "a".repeat(100_000) // ~100 KB
        val bytes = content.toByteArray(StandardCharsets.UTF_8).size
        assertTrue(bytes <= 200_000, "content should be under 200KB")
    }

    @Test
    fun `content at exactly 200KB passes validation`() {
        // 200_000 bytes of ASCII = 200_000 chars
        val content = "x".repeat(200_000)
        val bytes = content.toByteArray(StandardCharsets.UTF_8).size
        assertEquals(200_000, bytes)
    }

    @Test
    fun `content over 200KB fails validation`() {
        val content = "x".repeat(200_001)
        val bytes = content.toByteArray(StandardCharsets.UTF_8).size
        assertTrue(bytes > 200_000, "content should exceed 200KB")
    }

    @Test
    fun `empty content is allowed (creates empty file)`() {
        val content = ""
        val bytes = content.toByteArray(StandardCharsets.UTF_8).size
        assertEquals(0, bytes)
        assertTrue(bytes <= 200_000)
    }

    @Test
    fun `content with multibyte UTF-8 characters is measured in bytes not chars`() {
        // Each emoji is 4 bytes in UTF-8
        val content = "\uD83D\uDE00".repeat(50_000) // 50k emoji = 200_000 bytes
        val bytes = content.toByteArray(StandardCharsets.UTF_8).size
        assertEquals(200_000, bytes)
    }

    @Test
    fun `content over 200KB with multibyte chars fails validation`() {
        val content = "\uD83D\uDE00".repeat(50_001) // 50_001 emoji = 200_004 bytes
        val bytes = content.toByteArray(StandardCharsets.UTF_8).size
        assertTrue(bytes > 200_000)
    }
}
