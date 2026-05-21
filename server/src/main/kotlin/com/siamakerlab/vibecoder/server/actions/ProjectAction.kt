package com.siamakerlab.vibecoder.server.actions

import com.siamakerlab.vibecoder.shared.dto.ActionCategoryDto
import com.siamakerlab.vibecoder.shared.dto.ProjectActionDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Server-internal representation of one quick-action chip.
 *
 * Action manifests on disk use a slightly looser shape (no auto-discovered MCP entries
 * yet) so we keep these classes separate from [ProjectActionDto] — the registry
 * translates between the two when serving the Android client.
 */
@Serializable
sealed class ProjectAction {
    abstract val id: String
    abstract val label: String
    abstract val icon: String?
    /** Capability keys that must be true for the chip to be enabled on the client. */
    abstract val requires: List<String>

    @Serializable
    @SerialName("SendPromptAction")
    data class SendPrompt(
        override val id: String,
        override val label: String,
        override val icon: String? = null,
        override val requires: List<String> = emptyList(),
        val promptTemplate: String,
        val variables: List<String> = emptyList(),
    ) : ProjectAction()

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
    ) : ProjectAction()

    @Serializable
    @SerialName("RunServerAction")
    data class RunServerAction(
        override val id: String,
        override val label: String,
        override val icon: String? = null,
        override val requires: List<String> = emptyList(),
        val serverAction: String,
        val params: JsonElement? = null,
    ) : ProjectAction()

    @Serializable
    @SerialName("OpenPaletteAction")
    data class OpenPalette(
        override val id: String,
        override val label: String,
        override val icon: String? = null,
        override val requires: List<String> = emptyList(),
        val paletteId: String,
    ) : ProjectAction()

    @Serializable
    @SerialName("SnippetInsertAction")
    data class SnippetInsert(
        override val id: String,
        override val label: String,
        override val icon: String? = null,
        override val requires: List<String> = emptyList(),
        val text: String,
    ) : ProjectAction()

    @Serializable
    @SerialName("InvokeClaudeSlashCommandAction")
    data class InvokeClaudeSlashCommand(
        override val id: String,
        override val label: String,
        override val icon: String? = null,
        override val requires: List<String> = emptyList(),
        val command: String,
    ) : ProjectAction()
}

@Serializable
data class ActionCategory(
    val id: String,
    val label: String,
    val icon: String? = null,
    val actions: List<ProjectAction> = emptyList(),
)

@Serializable
data class ActionManifest(
    val categories: List<ActionCategory> = emptyList(),
)

/** Convert internal ProjectAction → wire DTO (today the mapping is 1:1). */
fun ProjectAction.toDto(): ProjectActionDto = when (this) {
    is ProjectAction.SendPrompt -> ProjectActionDto.SendPrompt(
        id, label, icon, requires, promptTemplate, variables,
    )
    is ProjectAction.InvokeMcpTool -> ProjectActionDto.InvokeMcpTool(
        id, label, icon, requires, mcpServer, toolName, argsTemplate,
    )
    is ProjectAction.RunServerAction -> ProjectActionDto.RunServerAction(
        id, label, icon, requires, serverAction, params,
    )
    is ProjectAction.OpenPalette -> ProjectActionDto.OpenPalette(
        id, label, icon, requires, paletteId,
    )
    is ProjectAction.SnippetInsert -> ProjectActionDto.SnippetInsert(
        id, label, icon, requires, text,
    )
    is ProjectAction.InvokeClaudeSlashCommand -> ProjectActionDto.InvokeClaudeSlashCommand(
        id, label, icon, requires, command,
    )
}

fun ActionCategory.toDto(): ActionCategoryDto =
    ActionCategoryDto(id = id, label = label, icon = icon, actions = actions.map { it.toDto() })
