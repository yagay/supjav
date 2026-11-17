package com.yagay.supjav.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yagay.supjav.SupjavApp
import com.yagay.supjav.model.VideoItem

/** Hongguo-style My hub: library, history, and settings without an extra bottom tab. */
@Composable
fun MyHubScreen(
    app: SupjavApp,
    dark: Boolean,
    onDarkChange: (Boolean) -> Unit,
    onVideo: (VideoItem) -> Unit
) {
    var section by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("收藏", "历史", "设置").forEachIndexed { index, name ->
                FilterChip(
                    selected = section == index, onClick = { section = index },
                    label = { Text(name) },
                    leadingIcon = {
                        Icon(
                            when (index) {
                                0 -> Icons.Outlined.Bookmarks
                                1 -> Icons.Outlined.History
                                else -> Icons.Outlined.Settings
                            }, null, Modifier.size(16.dp)
                        )
                    }
                )
            }
        }
        when (section) {
            0 -> LibraryScreen(app, onVideo, initialFavorites = true, showTabs = false)
            1 -> LibraryScreen(app, onVideo, initialFavorites = false, showTabs = false)
            else -> SettingsScreen(app, dark, onDarkChange, onExit = { section = 0 })
        }
    }
}
