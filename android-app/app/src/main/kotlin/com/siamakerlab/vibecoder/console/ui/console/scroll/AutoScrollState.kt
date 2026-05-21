package com.siamakerlab.vibecoder.console.ui.console.scroll

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Bundles a LazyListState with two derived signals:
 *  - [isAtBottom]: true when the last item is visible (or list empty).
 *  - [hasUnreadBelow]: true when new items arrived while we were NOT at the bottom.
 *
 * The screen auto-scrolls to the latest only when [isAtBottom] was already true at the
 * moment new content arrived, so a user reading older messages isn't yanked back.
 */
class AutoScrollState(
    val listState: LazyListState,
    val isAtBottom: () -> Boolean,
    val hasUnreadBelow: () -> Boolean,
    val clearUnread: () -> Unit,
)

@Composable
fun rememberAutoScrollState(itemCount: Int): AutoScrollState {
    val listState = rememberLazyListState()
    var unread by remember { mutableStateOf(false) }

    val atBottom by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            if (total == 0) return@derivedStateOf true
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= total - 1
        }
    }

    // Watch new items: if user is not at the bottom when an item is added, flag unread.
    LaunchedEffect(itemCount) {
        if (!atBottom && itemCount > 0) unread = true
        if (atBottom) unread = false
    }

    // Whenever we scroll back to the bottom, clear the unread flag.
    LaunchedEffect(listState) {
        snapshotFlow { atBottom }
            .distinctUntilChanged()
            .collect { v -> if (v) unread = false }
    }

    return remember(listState) {
        AutoScrollState(
            listState = listState,
            isAtBottom = { atBottom },
            hasUnreadBelow = { unread },
            clearUnread = { unread = false },
        )
    }
}
