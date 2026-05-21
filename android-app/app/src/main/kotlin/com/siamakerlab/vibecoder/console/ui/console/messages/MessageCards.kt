package com.siamakerlab.vibecoder.console.ui.console.messages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.siamakerlab.vibecoder.console.R
import com.siamakerlab.vibecoder.console.ui.console.ConsoleMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private val prettyJson = Json { prettyPrint = true; encodeDefaults = true }

@Composable
fun MessageRow(message: ConsoleMessage, modifier: Modifier = Modifier) {
    when (message) {
        is ConsoleMessage.SessionBanner -> SessionBanner(message, modifier)
        is ConsoleMessage.UserPrompt -> UserBubble(message, modifier)
        is ConsoleMessage.Assistant -> AssistantBubble(message, modifier)
        is ConsoleMessage.ToolUse -> ToolUseCard(message, modifier)
        is ConsoleMessage.ToolResult -> ToolResultCard(message, modifier)
        is ConsoleMessage.ErrorNotice -> ErrorBanner(message, modifier)
        is ConsoleMessage.SystemNotice -> SystemNotice(message, modifier)
        is ConsoleMessage.TurnDone -> TurnDoneRow(message, modifier)
        is ConsoleMessage.Unknown -> UnknownEventCard(message, modifier)
    }
}

@Composable
private fun SessionBanner(m: ConsoleMessage.SessionBanner, modifier: Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.padding(8.dp)) {
            Text(
                stringResource(R.string.console_session_started),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "id=${m.sessionId.take(16)}…  model=${m.model ?: "?"}",
                style = MaterialTheme.typography.bodySmall,
            )
            m.cwd?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun UserBubble(m: ConsoleMessage.UserPrompt, modifier: Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(
                m.text,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun AssistantBubble(m: ConsoleMessage.Assistant, modifier: Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
            modifier = Modifier.widthIn(max = 340.dp),
        ) {
            Text(
                m.text,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ToolUseCard(m: ConsoleMessage.ToolUse, modifier: Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable { expanded = !expanded },
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.padding(8.dp)) {
            Text("🔧 ${m.toolName}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(
                text = if (expanded) prettyOf(m.input) else previewOf(m.input),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun ToolResultCard(m: ConsoleMessage.ToolResult, modifier: Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val tint = if (m.isError) MaterialTheme.colorScheme.errorContainer
    else MaterialTheme.colorScheme.surfaceVariant
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable { expanded = !expanded },
        color = tint,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.padding(8.dp)) {
            val label = if (m.isError) {
                "✗ " + stringResource(R.string.console_tool_result_error)
            } else {
                "✓ " + stringResource(R.string.console_tool_result_ok)
            }
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (expanded) prettyOf(m.output) else previewOf(m.output),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun ErrorBanner(m: ConsoleMessage.ErrorNotice, modifier: Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.padding(8.dp)) {
            Text("⚠ ${m.code}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(m.message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SystemNotice(m: ConsoleMessage.SystemNotice, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                "• ${m.message}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun TurnDoneRow(m: ConsoleMessage.TurnDone, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            "${stringResource(R.string.console_turn_done_prefix)} (${m.reason}) —",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun UnknownEventCard(m: ConsoleMessage.Unknown, modifier: Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable { expanded = !expanded },
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.padding(8.dp)) {
            Text(
                "? " + stringResource(R.string.console_unknown_event),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (expanded) prettyOf(m.raw) else previewOf(m.raw),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private fun previewOf(element: JsonElement): String {
    val raw = element.toString()
    return if (raw.length > 120) raw.take(120) + "…" else raw
}

private fun prettyOf(element: JsonElement): String =
    runCatching { prettyJson.encodeToString(JsonElement.serializer(), element) }
        .getOrDefault(element.toString())
