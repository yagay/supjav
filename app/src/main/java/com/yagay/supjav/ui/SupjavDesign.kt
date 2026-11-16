package com.yagay.supjav.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * UI rules adapted from the user's hongguo-android/rewrite-web-native AppDesign and UI guide.
 * One consistent theme, one set of card and sheet sizes, and one icon-only navigation.
 */
object SupjavMetrics {
    val pageMargin = 16.dp
    val cardRadius = 16.dp
    val controlRadius = 10.dp
    val headerHeight = 56.dp
    val navigationHeight = 48.dp
    val iconTarget = 40.dp
    val sectionGap = 12.dp
}

private val Accent = Color(0xFFE95336)

@Composable
fun SupjavTheme(dark: Boolean, content: @Composable () -> Unit) {
    val palette = if (dark) darkColorScheme(
        primary = Color(0xFFFF8973),
        onPrimary = Color(0xFF391813),
        secondary = Color(0xFFFFAD9F),
        background = Color(0xFF101114),
        surface = Color(0xFF1B1C20),
        surfaceVariant = Color(0xFF27282D),
        onBackground = Color(0xFFF2F2F5),
        onSurface = Color(0xFFF2F2F5),
        outlineVariant = Color(0xFF37383E)
    ) else lightColorScheme(
        primary = Accent,
        onPrimary = Color.White,
        secondary = Color(0xFFB24730),
        background = Color(0xFFF6F6F8),
        surface = Color.White,
        surfaceVariant = Color(0xFFFFEEE9),
        onBackground = Color(0xFF1D1E21),
        onSurface = Color(0xFF1D1E21),
        outlineVariant = Color(0xFFE6E6EA)
    )
    MaterialTheme(colorScheme = palette, content = content)
}

@Composable
fun SupjavHeader(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().height(SupjavMetrics.headerHeight)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.size(SupjavMetrics.iconTarget)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            } else Spacer(Modifier.width(8.dp))
            Text(
                title, modifier = Modifier.weight(1f).padding(start = 6.dp),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

@Composable
fun SupjavPrimaryNavigation(selected: Int, onSelect: (Int) -> Unit) {
    val icons: List<ImageVector> = listOf(
        Icons.Outlined.Home, Icons.Outlined.Explore, Icons.Outlined.PersonOutline
    )
    val labels = listOf("首页", "发现", "我的")
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 5.dp
    ) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding()
                .height(SupjavMetrics.navigationHeight).padding(horizontal = 28.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            labels.forEachIndexed { index, label ->
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                        .clickable { onSelect(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        icons[index],
                        contentDescription = label,
                        modifier = Modifier.size(27.dp),
                        tint = if (index == selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun SupjavSectionTitle(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (subtitle != null) Text(
            subtitle, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun SupjavEmptyState(
    text: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onRetry != null) OutlinedButton(onClick = onRetry) { Text("重试") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupjavModalSheet(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
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
    title: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
