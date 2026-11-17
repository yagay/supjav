package com.yagay.supjav.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yagay.supjav.model.FeedKind

/**
 * One compact site-search field inside the current tab. It never becomes
 * a separate destination and never replaces the active tab or its scroll
 * state. Server-side searching only happens when explicitly submitted.
 */
@Composable
fun InlineSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    kind: FeedKind,
    onKindChange: (FeedKind) -> Unit,
    activeValue: String,
    activeKind: FeedKind,
    recent: List<String>,
    onSubmit: (String, FeedKind) -> Unit,
    onClear: () -> Unit,
    onRecent: (String) -> Unit,
    onClearRecent: () -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun submit() {
        if (query.isBlank()) {
            expanded = !expanded
            return
        }
        onSubmit(query, kind)
        focusManager.clearFocus()
        keyboard?.hide()
        expanded = false
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = SupjavMetrics.pageMargin, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth()
                .heightIn(min = 48.dp)
                .onFocusChanged { if (it.isFocused) expanded = true },
            shape = RoundedCornerShape(SupjavMetrics.controlRadius),
            singleLine = true,
            placeholder = { Text("搜索作品、番号、演员或标签") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = {
                if (activeValue.isNotBlank()) {
                    IconButton(onClick = {
                        onClear()
                        expanded = false
                        focusManager.clearFocus()
                    }) {
                        Icon(Icons.Outlined.Close, "清除搜索")
                    }
                } else {
                    IconButton(onClick = { submit() }) {
                        Icon(Icons.Outlined.Search, "提交搜索")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit() })
        )
        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(FeedKind.SEARCH, FeedKind.CATEGORY, FeedKind.TAG,
                        FeedKind.ACTOR, FeedKind.MAKER).forEach { option ->
                        FilterChip(
                            selected = kind == option,
                            onClick = { onKindChange(option) },
                            label = { Text(option.title) }
                        )
                    }
                }
                if (recent.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("最近搜索", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        IconButton(
                            onClick = onClearRecent,
                            modifier = Modifier.size(30.dp)
                        ) { Icon(Icons.Outlined.DeleteOutline, "清除最近搜索",
                                modifier = Modifier.size(19.dp)) }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(recent.take(8)) { term ->
                            AssistChip(
                                onClick = {
                                    onRecent(term)
                                    expanded = false
                                    focusManager.clearFocus()
                                    keyboard?.hide()
                                },
                                label = { Text(term, maxLines = 1) }
                            )
                        }
                    }
                }
            }
        }
        if (activeValue.isNotBlank() && !expanded) {
            Row(
                Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "搜索结果 · ${activeKind.title}：${activeValue}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                TextButton(onClick = { onClear(); expanded = false }) { Text("返回列表") }
            }
        }
    }
}
