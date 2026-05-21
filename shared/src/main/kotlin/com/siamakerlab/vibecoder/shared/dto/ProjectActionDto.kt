package com.siamakerlab.vibecoder.shared.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Polymorphic action descriptor sent from server → Android.
 *
 * The Android quick-action chip system renders one chip per action and
 * routes its `invoke` event back to the server. Each subclass carries the
 * data the server needs to dispatch the right behavior — adding a new
 * action type is a code change here + a new branch on the dispatch side.
 *
 * New chips can also be added without code changes via the manifest system
 * (JSON manifests under server resources `/actions/` plus the workspace user file).
 *
 * ## `requires`
 *
 * Each variant carries a `requires: List<String>` of capability keys. The
 * server emits the current capability map alongside [ActionTreeDto.capabilities];
 * the client renders a chip as enabled iff every key in its `requires` is
 * marked true. See [CapabilityKey] for the recognized values.
 */
@Serializable
sealed class ProjectActionDto {
    abstract val id: String
    abstract val label: String
    abstract val icon: String?

    /** Capability keys that must all be available for this chip to be enabled. */
    abstract val requires: List<String>

    /** Insert a prompt template into the input box (optionally with variable substitution). */
    @Serializable
    @SerialName("SendPromptAction")
    data class SendPrompt(
        override val id: String,
        override val label: String,
        override val icon: String? = null,
        override val requires: List<String> = emptyList(),
        val promptTemplate: String,
        val variables: List<String> = emptyList(),
    ) : ProjectActionDto()

    /** Invoke a tool on a configured MCP server (auto-discovered from .mcp.json). */
    @Serializable
    @SerialName("InvokeMcpToolAction")
    data class InvokeMcpTool(
        override val id: String,
        override val label: String,
        override val icon: String? = null,
        override val requires: List<String> = emptyList(),
        val mcpServer: String,
        val toolName: String,
        val argsTemplate: JsonElement? = null,
    ) : ProjectActionDto()

    /** Run a whitelisted server-side action (build.debug, git.status, ...). */
    @Serializable
    @SerialName("RunServerAction")
    data class RunServerAction(
        override val id: String,
        override val label: String,
        override val icon: String? = null,
        override val requires: List<String> = emptyList(),
        val serverAction: String,
        val params: JsonElement? = null,
    ) : ProjectActionDto()

    /** Open a sub-palette of more actions (used to group rarely-used chips). */
    @Serializable
    @SerialName("OpenPaletteAction")
    data class OpenPalette(
        override val id: String,
        override val label: String,
        override val icon: String? = null,
        override val requires: List<String> = emptyList(),
        val paletteId: String,
    ) : ProjectActionDto()

    /** Insert raw text into the input box (user snippet). */
    @Serializable
    @SerialName("SnippetInsertAction")
    data class SnippetInsert(
        override val id: String,
        override val label: String,
        override val icon: String? = null,
        override val requires: List<String> = emptyList(),
        val text: String,
    ) : ProjectActionDto()

    /** Invoke a whitelisted Claude slash command (`status`, `cost`, `model`, ...). */
    @Serializable
    @SerialName("InvokeClaudeSlashCommandAction")
    data class InvokeClaudeSlashCommand(
        override val id: String,
        override val label: String,
        override val icon: String? = null,
        override val requires: List<String> = emptyList(),
        val command: String,
    ) : ProjectActionDto()
}

/**
 * Capability key constants used by [ProjectActionDto.requires] and
 * [ActionTreeDto.capabilities]. Keys are plain strings so manifest JSON can
 * declare them without depending on this object.
 */
object CapabilityKey {
    /** Gradle wrapper is invokable for the project. */
    const val BUILD = "build"
    /** `git` CLI is on PATH (server EnvDiagnostics reports OK). */
    const val GIT = "git"
    /** `claude` CLI is on PATH (server EnvDiagnostics reports OK). */
    const val CLAUDE_SESSION = "claude_session"

    /** Build the per-server MCP capability key. */
    fun mcp(server: String): String = "mcp:$server"
}

/** One row in the chip strip — a labeled bucket of related actions. */
@Serializable
data class ActionCategoryDto(
    val id: String,
    val label: String,
    val icon: String? = null,
    val actions: List<ProjectActionDto>,
)

/**
 * Tree of categories returned by `GET /api/projects/{id}/actions`.
 *
 * @property capabilities Live map of [CapabilityKey] → availability. The client
 * uses this together with each action's `requires` to render enabled/disabled
 * chips. Missing keys are treated as `false`.
 */
@Serializable
data class ActionTreeDto(
    val categories: List<ActionCategoryDto>,
    val capabilities: Map<String, Boolean> = emptyMap(),
)

/** Body for POST /api/projects/{id}/actions/invoke (and the corresponding WS frame). */
@Serializable
data class ActionInvokeRequestDto(
    val actionId: String,
    val params: JsonElement? = null,
)
