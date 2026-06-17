package com.siamakerlab.vibecoder.server.claude

import com.siamakerlab.vibecoder.server.repo.ConversationTurnRepository
import java.util.concurrent.ConcurrentHashMap

class ConversationContextPressureService(
    private val repo: ConversationTurnRepository,
    private val enabled: Boolean = envBool("VIBECODER_CONTEXT_PRESSURE_ENABLED", default = true),
    private val warnBytes: Long = envLong("VIBECODER_CONTEXT_PRESSURE_WARN_BYTES", default = 350_000L),
    private val criticalBytes: Long = envLong("VIBECODER_CONTEXT_PRESSURE_CRITICAL_BYTES", default = 500_000L),
) {
    data class Warning(
        val level: Level,
        val turns: Long,
        val contentBytes: Long,
        val message: String,
    )

    enum class Level { WARN, CRITICAL }

    private val emitted = ConcurrentHashMap.newKeySet<String>()

    fun warningFor(projectId: String, sessionId: String?): Warning? {
        if (!enabled || sessionId.isNullOrBlank()) return null
        val weight = repo.sessionWeight(projectId, sessionId)
        val level = when {
            weight.contentBytes >= criticalBytes -> Level.CRITICAL
            weight.contentBytes >= warnBytes -> Level.WARN
            else -> return null
        }
        val key = "$projectId:$sessionId:$level"
        if (!emitted.add(key)) return null
        val mb = "%.2f".format(weight.contentBytes.toDouble() / (1024.0 * 1024.0))
        val action = if (level == Level.CRITICAL) {
            "Start a new session or run /compact before continuing with large changes."
        } else {
            "Consider /compact soon, especially before asking for broad edits."
        }
        return Warning(
            level = level,
            turns = weight.turns,
            contentBytes = weight.contentBytes,
            message = "Conversation context is getting large: $mb MiB persisted across ${weight.turns} turns. $action",
        )
    }

    companion object {
        private fun envBool(name: String, default: Boolean): Boolean =
            System.getenv(name)?.lowercase()?.let { it in setOf("1", "true", "yes", "on") } ?: default

        private fun envLong(name: String, default: Long): Long =
            System.getenv(name)?.toLongOrNull()?.takeIf { it > 0 } ?: default
    }
}
