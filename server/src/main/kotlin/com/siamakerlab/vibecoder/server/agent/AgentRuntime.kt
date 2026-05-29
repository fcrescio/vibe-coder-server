package com.siamakerlab.vibecoder.server.agent

interface AgentRuntime {
    suspend fun sendPrompt(projectId: String, text: String)
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
