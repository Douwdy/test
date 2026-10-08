package fr.douwdy.lecteur.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.douwdy.lecteur.R
import fr.douwdy.lecteur.playback.PlayerConnection
import fr.douwdy.lecteur.playback.PlayerUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Position de lecture, mise à jour régulièrement tant que la musique joue. */
@Composable
fun rememberPlaybackPosition(connection: PlayerConnection, state: PlayerUiState): State<Long> {
    val position = remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.isPlaying, state.mediaId) {
        position.longValue = connection.currentPositionMs
        while (state.isPlaying && isActive) {
            delay(250)
            position.longValue = connection.currentPositionMs
        }
    }
    return position
}

@Composable
fun MiniPlayer(
    state: PlayerUiState,
    connection: PlayerConnection,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val position = rememberPlaybackPosition(connection, state)
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
    ) {
        Column {
            LinearProgressIndicator(
                progress = {
                    if (state.durationMs > 0) (position.value.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
                },
                modifier = Modifier.fillMaxWidth(),
                trackColor = Color.Transparent,
                drawStopIndicator = {},
                gapSize = 0.dp,
            )
            Row(
                modifier = Modifier
                    .clickable(onClick = onOpen)
                    .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(state.mediaUri, 144, RoundedCornerShape(8.dp), Modifier.size(44.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        state.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    state.artist?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = connection::togglePlayPause) {
                    Icon(
                        if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(if (state.isPlaying) R.string.cd_pause else R.string.cd_play),
                    )
                }
                IconButton(onClick = connection::next) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = stringResource(R.string.cd_next))
                }
            }
        }
    }
}
