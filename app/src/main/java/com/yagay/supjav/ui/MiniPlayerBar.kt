package com.yagay.supjav.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.yagay.supjav.SupjavApp

@Composable
fun MiniPlayerBar(app: SupjavApp, onClick: () -> Unit) {
    val engine = app.playerEngine
    val current = engine.currentVideo ?: return
    var playing by remember { mutableStateOf(engine.player.isPlaying) }
    DisposableEffect(engine.player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
        }
        engine.player.addListener(listener)
        onDispose { engine.player.removeListener(listener) }
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        shape = RoundedCornerShape(SupjavMetrics.cardRadius),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 5.dp
    ) {
        Row(
            Modifier.fillMaxWidth().height(52.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                Modifier.weight(1f).clickable(onClick = onClick).padding(horizontal = 12.dp)
            ) {
                Text(current.title, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("点按返回播放器", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { if (playing) engine.player.pause() else engine.player.play() }) {
                Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, "播放或暂停")
            }
            IconButton(onClick = { engine.stop() }) {
                Icon(Icons.Outlined.Close, "停止")
            }
        }
    }
}
