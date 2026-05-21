package com.siamakerlab.vibecoder.console.ui.console.input

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.siamakerlab.vibecoder.console.R
import com.siamakerlab.vibecoder.shared.dto.ActionTreeDto
import com.siamakerlab.vibecoder.shared.dto.CapabilityKey
import com.siamakerlab.vibecoder.shared.dto.ProjectActionDto

/**
 * Quick-action chips arranged as:
 *
 *   [tab1] [tab2] [tab3] [tab4]      ← category tabs (FilterChip)
 *   [chip][chip][chip][chip][chip…]  ← actions in the selected category (LazyRow)
 *
 * Each chip is rendered enabled iff every key in its `requires` is present and
 * `true` in [ActionTreeDto.capabilities]. Disabled chips reject taps and show
 * a per-capability toast explaining why on long-press.
 *
 * Tapping an enabled chip dispatches one of:
 *  - [ProjectActionDto.SendPrompt]    → onInsertText  (template inserted into input)
 *  - [ProjectActionDto.SnippetInsert] → onInsertText
 *  - others                           → onInvoke  (server-side dispatch via POST /actions/invoke)
 */
@Composable
fun QuickActionChips(
    tree: ActionTreeDto?,
    onInvoke: (actionId: String) -> Unit,
    onInsertText: (text: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tree == null || tree.categories.isEmpty()) return
    var selectedCategoryId by remember(tree) { mutableStateOf(tree.categories.first().id) }
    val selected = tree.categories.firstOrNull { it.id == selectedCategoryId } ?: tree.categories.first()
    val ctx = LocalContext.current
    val genericReason = stringResource(R.string.cap_unavailable_generic)
    val buildReason = stringResource(R.string.cap_unavailable_build)
    val gitReason = stringResource(R.string.cap_unavailable_git)
    val claudeReason = stringResource(R.string.cap_unavailable_claude_session)

    Column(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        // Tabs
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(tree.categories, key = { it.id }) { cat ->
                FilterChip(
                    selected = cat.id == selectedCategoryId,
                    onClick = { selectedCategoryId = cat.id },
                    label = { Text(cat.label, style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
        // Actions for the selected category
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(selected.actions, key = { it.id }) { action ->
                val missing = action.requires.filter { tree.capabilities[it] != true }
                val enabled = missing.isEmpty()
                ActionChip(
                    action = action,
                    enabled = enabled,
                    onClick = {
                        when (action) {
                            is ProjectActionDto.SendPrompt -> onInsertText(action.promptTemplate)
                            is ProjectActionDto.SnippetInsert -> onInsertText(action.text)
                            else -> onInvoke(action.id)
                        }
                    },
                    onShowReason = {
                        val msg = when (val key = missing.firstOrNull()) {
                            null -> genericReason
                            CapabilityKey.BUILD -> buildReason
                            CapabilityKey.GIT -> gitReason
                            CapabilityKey.CLAUDE_SESSION -> claudeReason
                            else -> if (key.startsWith("mcp:")) {
                                ctx.getString(R.string.cap_unavailable_mcp, key.removePrefix("mcp:"))
                            } else {
                                genericReason
                            }
                        }
                        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActionChip(
    action: ProjectActionDto,
    enabled: Boolean,
    onClick: () -> Unit,
    onShowReason: () -> Unit,
) {
    if (enabled) {
        AssistChip(
            onClick = onClick,
            label = { Text(action.label, style = MaterialTheme.typography.labelMedium) },
            shape = RoundedCornerShape(16.dp),
            colors = AssistChipDefaults.assistChipColors(),
        )
    } else {
        // AssistChip's own `enabled=false` suppresses its built-in click handling.
        // Wrap with combinedClickable so a tap (or long-press) on the disabled chip
        // still surfaces the "why" toast — silent failures are worse than no chip.
        AssistChip(
            onClick = { /* no-op when disabled */ },
            enabled = false,
            label = { Text(action.label, style = MaterialTheme.typography.labelMedium) },
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.combinedClickable(
                onClick = onShowReason,
                onLongClick = onShowReason,
            ),
        )
    }
}
