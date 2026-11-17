package com.yagay.supjav.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Leaderboard
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Visual design derived from the user's kaixinguo-android main theme:
 * pistachio green, neutral containers, adaptive poster density and site-driven category navigation alongside the core tabs.
 * Site APIs and local storage stay Supjav-native.
 */
object SupjavMetrics {
    val pageMargin = 16.dp
    val cardRadius = 12.dp
    val controlRadius = 12.dp
    val headerHeight = 56.dp
    val sectionGap = 14.dp
    val posterMinWidth = 104.dp
}

enum class SupjavTab(val title: String) {
    RECOMMEND("推荐"), CATEGORY("分类"), RANK("排行"), NEW("最新"), MINE("我的");
    fun icon(selected: Boolean): ImageVector = when (this) {
        RECOMMEND -> if (selected) Icons.Filled.Home else Icons.Outlined.Home
        CATEGORY -> if (selected) Icons.Filled.Category else Icons.Outlined.Category
        RANK -> if (selected) Icons.Filled.Leaderboard else Icons.Outlined.Leaderboard
        NEW -> if (selected) Icons.Filled.CalendarMonth else Icons.Outlined.CalendarMonth
        MINE -> if (selected) Icons.Filled.Star else Icons.Outlined.StarOutline
    }
}

@Composable
fun SupjavTheme(dark: Boolean, content: @Composable () -> Unit) {
    val palette = if (dark) darkColorScheme(
        primary = Color(0xFF7CC24A), onPrimary = Color(0xFF0F2A00),
        primaryContainer = Color(0xFF2F5E1C), onPrimaryContainer = Color(0xFFC3E58F),
        secondary = Color(0xFFBECBAE), secondaryContainer = Color(0xFF3F4A34),
        background = Color(0xFF121410), surface = Color(0xFF121410),
        surfaceVariant = Color(0xFF44483E), surfaceContainer = Color(0xFF1E201C),
        surfaceContainerLow = Color(0xFF1A1C18), surfaceContainerHigh = Color(0xFF282B26),
        onBackground = Color(0xFFE2E3DC), onSurface = Color(0xFFE2E3DC),
        onSurfaceVariant = Color(0xFFA9AD9F), outlineVariant = Color(0xFF44483E)
    ) else lightColorScheme(
        primary = Color(0xFF4A8C2A), onPrimary = Color.White,
        primaryContainer = Color(0xFFC3E58F), onPrimaryContainer = Color(0xFF0F2400),
        secondary = Color(0xFF56624B), secondaryContainer = Color(0xFFDAE7C9),
        background = Color(0xFFFDFDF8), surface = Color(0xFFFDFDF8),
        surfaceVariant = Color(0xFFE0E4D6), surfaceContainer = Color(0xFFF1F2EB),
        surfaceContainerLow = Color(0xFFF7F8F1), surfaceContainerHigh = Color(0xFFEBECE5),
        onBackground = Color(0xFF1A1C18), onSurface = Color(0xFF1A1C18),
        onSurfaceVariant = Color(0xFF5B6052), outlineVariant = Color(0xFFC4C8BA)
    )
    MaterialTheme(colorScheme = palette, content = content)
}

@Composable
fun SupjavHeader(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(SupjavMetrics.headerHeight)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
            } else Spacer(Modifier.width(8.dp))
            Text(title, Modifier.weight(1f).padding(start = 8.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(content = actions, verticalAlignment = Alignment.CenterVertically)
        }
    }
}

@Composable
fun SupjavPrimaryNavigation(selected: SupjavTab, onSelect: (SupjavTab) -> Unit) {
    NavigationBar(
        modifier = Modifier.navigationBarsPadding(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp
    ) {
        SupjavTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = selected == tab,
                onClick = { onSelect(tab) },
                icon = { Icon(tab.icon(selected == tab), tab.title) },
                label = { Text(tab.title, maxLines = 1, style = MaterialTheme.typography.labelSmall) },
                alwaysShowLabel = true
            )
        }
    }
}

@Composable
fun SupjavSectionTitle(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SupjavEmptyState(text: String, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium)
        if (onRetry != null) OutlinedButton(onClick = onRetry) { Text("重新尝试") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupjavModalSheet(
    title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
        Column(
            Modifier.fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SupjavSectionTitle(title)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            content()
        }
    }
}

@Composable
fun SupjavSettingToggle(
    title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
