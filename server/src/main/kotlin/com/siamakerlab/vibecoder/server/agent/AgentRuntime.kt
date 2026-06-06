package com.siamakerlab.vibecoder.server.agent

data class AgentPromptImage(
    val mimeType: String,
    val data: String,
)

interface AgentRuntime {
    suspend fun sendPrompt(projectId: String, text: String, images: List<AgentPromptImage> = emptyList())
    suspend fun startNew(projectId: String)
    suspend fun cancelTurn(projectId: String)
    fun isAlive(projectId: String): Boolean
    fun currentSessionId(projectId: String): String?
    fun isBusy(projectId: String): Boolean
    suspend fun shutdown()

    companion object {
        const val MAX_PROMPT_BYTES = 32 * 1024
        const val PROJECTS_TOPIC = "__projects__"
    }
}
