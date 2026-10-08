package fr.douwdy.lecteur.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import fr.douwdy.lecteur.R
import fr.douwdy.lecteur.playback.PlayerConnection
import fr.douwdy.lecteur.playback.PlayerUiState
import fr.douwdy.lecteur.playback.QueueEntry
import fr.douwdy.lecteur.ui.components.Artwork
import fr.douwdy.lecteur.ui.components.formatDuration
import fr.douwdy.lecteur.ui.components.rememberPlaybackPosition

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    state: PlayerUiState,
    connection: PlayerConnection,
    onCollapse: () -> Unit,
) {
    var showQueue by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCollapse) {
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(R.string.cd_collapse))
            }
            Text(
                stringResource(R.string.now_playing),
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { showQueue = true }) {
                Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = stringResource(R.string.cd_queue))
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            val coverPx = LocalWindowInfo.current.containerSize.width
            Artwork(
                uri = state.mediaUri,
                sizePx = coverPx,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f, matchHeightConstraintsFirst = true),
            )
        }

        Spacer(Modifier.height(24.dp))
        Text(
            state.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        val subtitle = listOfNotNull(state.artist, state.album).joinToString(" · ")
        if (subtitle.isNotEmpty()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Spacer(Modifier.height(16.dp))
        SeekBar(state, connection)
        Spacer(Modifier.height(8.dp))
        Controls(state, connection)
        Spacer(Modifier.height(32.dp))
    }

    if (showQueue) {
        ModalBottomSheet(onDismissRequest = { showQueue = false }) {
            QueueList(state.queue, onSelect = connection::skipTo)
        }
    }
}

@Composable
private fun SeekBar(state: PlayerUiState, connection: PlayerConnection) {
    val position by rememberPlaybackPosition(connection, state)
    // Valeur affichée pendant que l'utilisateur fait glisser le curseur.
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = state.durationMs.coerceAtLeast(1)
    val shown = dragging ?: (position.toFloat() / duration).coerceIn(0f, 1f)

    Slider(
        value = shown,
        onValueChange = { dragging = it },
        onValueChangeFinished = {
            dragging?.let { connection.seekTo((it * duration).toLong()) }
            dragging = null
        },
        enabled = state.durationMs > 0,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        val style = MaterialTheme.typography.labelMedium
        val color = MaterialTheme.colorScheme.onSurfaceVariant
        Text(formatDuration((shown * duration).toLong()), style = style, color = color)
        Text(formatDuration(state.durationMs), style = style, color = color)
    }
}

@Composable
private fun Controls(state: PlayerUiState, connection: PlayerConnection) {
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = connection::toggleShuffle) {
            Icon(
                Icons.Rounded.Shuffle,
                contentDescription = stringResource(R.string.cd_shuffle),
                tint = if (state.shuffle) active else inactive,
            )
        }
        IconButton(onClick = connection::previous, modifier = Modifier.size(56.dp)) {
            Icon(
                Icons.Rounded.SkipPrevious,
                contentDescription = stringResource(R.string.cd_previous),
                modifier = Modifier.size(36.dp),
            )
        }
        FilledIconButton(onClick = connection::togglePlayPause, modifier = Modifier.size(76.dp)) {
            Icon(
                if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = stringResource(if (state.isPlaying) R.string.cd_pause else R.string.cd_play),
                modifier = Modifier.size(40.dp),
            )
        }
        IconButton(onClick = connection::next, modifier = Modifier.size(56.dp)) {
            Icon(
                Icons.Rounded.SkipNext,
                contentDescription = stringResource(R.string.cd_next),
                modifier = Modifier.size(36.dp),
            )
        }
        IconButton(onClick = connection::cycleRepeatMode) {
            val (icon, label) = when (state.repeatMode) {
                Player.REPEAT_MODE_ONE -> Icons.Rounded.RepeatOne to R.string.cd_repeat_one
                Player.REPEAT_MODE_ALL -> Icons.Rounded.Repeat to R.string.cd_repeat_all
                else -> Icons.Rounded.Repeat to R.string.cd_repeat_off
            }
            Icon(
                icon,
                contentDescription = stringResource(label),
                tint = if (state.repeatMode == Player.REPEAT_MODE_OFF) inactive else active,
            )
        }
    }
}

@Composable
private fun QueueList(queue: List<QueueEntry>, onSelect: (Int) -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        val current = queue.indexOfFirst { it.isCurrent }
        if (current > 0) listState.scrollToItem(current - 1)
    }
    Text(
        stringResource(R.string.queue_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    )
    LazyColumn(state = listState) {
        items(queue, key = { it.index }) { entry ->
            val color = if (entry.isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            ListItem(
                modifier = Modifier.clickable { onSelect(entry.index) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                headlineContent = {
                    Text(
                        entry.title,
                        color = color,
                        fontWeight = if (entry.isCurrent) FontWeight.SemiBold else null,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = entry.artist?.let { artist ->
                    { Text(artist, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                },
            )
        }
    }
}
