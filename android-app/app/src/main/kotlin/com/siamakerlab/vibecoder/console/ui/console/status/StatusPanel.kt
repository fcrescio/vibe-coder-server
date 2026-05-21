package com.siamakerlab.vibecoder.console.ui.console.status

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import com.siamakerlab.vibecoder.console.R
import com.siamakerlab.vibecoder.shared.dto.ClaudeStatusDto

/**
 * Collapsible card under the TopAppBar showing the Claude session snapshot.
 *
 * - Collapsed: 1-line summary ("Sonnet 4.6 · Pro · 87%").
 * - Expanded:  full key/value list.
 *
 * The refresh button hits GET /api/projects/{id}/claude/status.
 */
@Composable
fun StatusPanel(
    status: ClaudeStatusDto?,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 1.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            Modifier.fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            val dash = stringResource(R.string.status_dash)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = oneLineSummary(status),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier.padding(end = 4.dp),
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.status_refresh),
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) {
                        stringResource(R.string.status_collapse)
                    } else {
                        stringResource(R.string.status_expand)
                    },
                )
            }
            if (expanded && status != null) {
                Column(
                    Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    KvRow(stringResource(R.string.status_key_session), status.sessionId ?: dash)
                    KvRow(
                        stringResource(R.string.status_key_process),
                        if (status.processAlive) stringResource(R.string.status_alive)
                        else stringResource(R.string.status_idle),
                    )
                    KvRow(stringResource(R.string.status_key_model), status.model ?: dash)
                    KvRow(stringResource(R.string.status_key_plan), status.plan ?: dash)
                    KvRow(stringResource(R.string.status_key_quota), status.quotaRemaining ?: dash)
                    KvRow(stringResource(R.string.status_key_updated), status.updatedAt)
                }
            }
        }
    }
}

@Composable
private fun KvRow(key: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            key,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 2)
    }
}

@Composable
private fun oneLineSummary(status: ClaudeStatusDto?): String {
    if (status == null) return stringResource(R.string.status_unknown)
    val modelUnknown = stringResource(R.string.status_model_unknown)
    val alive = stringResource(R.string.status_alive)
    val idle = stringResource(R.string.status_idle)
    val prefix = stringResource(R.string.status_prefix)
    val parts = buildList {
        add(status.model ?: modelUnknown)
        status.plan?.let { add(it) }
        status.quotaRemaining?.let { add(it) }
        add(if (status.processAlive) alive else idle)
    }
    return "$prefix · ${parts.joinToString(" · ")}"
}
