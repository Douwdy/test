package fr.douwdy.lecteur.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fr.douwdy.lecteur.R
import fr.douwdy.lecteur.playback.PlayerConnection
import fr.douwdy.lecteur.playback.PlayerUiState
import fr.douwdy.lecteur.ui.theme.Icons
import fr.douwdy.lecteur.ui.theme.Theme
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

/** Carte flottante en bas de l'écran : morceau en cours, lecture/pause, suivant. */
@Composable
fun MiniPlayer(
    state: PlayerUiState,
    connection: PlayerConnection,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Theme.colors
    val position = rememberPlaybackPosition(connection, state)
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .shadow(16.dp, shape)
            .clip(shape)
            .background(colors.surfaceHigh)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onOpen),
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 6.dp, top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(state.artworkUri, 144, RoundedCornerShape(10.dp), Modifier.size(46.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Txt(state.title, Theme.type.title, maxLines = 1)
                state.artist?.let { Txt(it, Theme.type.small, color = colors.textDim, maxLines = 1) }
            }
            Box(
                Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(colors.text)
                    .pressable(connection::togglePlayPause),
                contentAlignment = Alignment.Center,
            ) {
                Glyph(
                    if (state.isPlaying) Icons.Pause else Icons.Play,
                    tint = colors.background,
                    size = 16.dp,
                    contentDescription = stringResource(if (state.isPlaying) R.string.cd_pause else R.string.cd_play),
                )
            }
            IconBtn(Icons.Next, stringResource(R.string.cd_next), connection::next, iconSize = 18.dp)
        }
        // Fine ligne de progression en bas de la carte.
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(2.dp),
        ) {
            val fraction = if (state.durationMs > 0) {
                (position.value.toFloat() / state.durationMs).coerceIn(0f, 1f)
            } else {
                0f
            }
            drawLine(colors.accent, Offset.Zero, Offset(size.width * fraction, 0f), size.height * 2)
        }
    }
}
