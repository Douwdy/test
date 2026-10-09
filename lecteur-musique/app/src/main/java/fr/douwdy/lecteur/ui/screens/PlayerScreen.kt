package fr.douwdy.lecteur.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import fr.douwdy.lecteur.R
import fr.douwdy.lecteur.playback.PlayerConnection
import fr.douwdy.lecteur.playback.PlayerUiState
import fr.douwdy.lecteur.playback.QueueEntry
import fr.douwdy.lecteur.ui.components.Artwork
import fr.douwdy.lecteur.ui.components.Equalizer
import fr.douwdy.lecteur.ui.components.Glyph
import fr.douwdy.lecteur.ui.components.IconBtn
import fr.douwdy.lecteur.ui.components.SeekBar
import fr.douwdy.lecteur.ui.components.Txt
import fr.douwdy.lecteur.ui.components.averageColor
import fr.douwdy.lecteur.ui.components.formatDuration
import fr.douwdy.lecteur.ui.components.pressable
import fr.douwdy.lecteur.ui.components.rememberArtwork
import fr.douwdy.lecteur.ui.components.rememberPlaybackPosition
import fr.douwdy.lecteur.ui.components.rowPressable
import fr.douwdy.lecteur.ui.theme.Icons
import fr.douwdy.lecteur.ui.theme.Theme
import java.util.Locale

@Composable
fun PlayerScreen(
    state: PlayerUiState,
    connection: PlayerConnection,
    onCollapse: () -> Unit,
) {
    val colors = Theme.colors
    var showQueue by rememberSaveable { mutableStateOf(false) }
    val coverPx = LocalWindowInfo.current.containerSize.width

    // Le haut de l'écran prend la couleur dominante de la pochette.
    val artwork = rememberArtwork(state.artworkUri, coverPx)
    val dominant = remember(artwork) { artwork?.averageColor() }
    val glow by animateColorAsState(
        targetValue = dominant?.let { lerp(it, colors.background, 0.35f) } ?: colors.surfaceHigh,
        animationSpec = tween(700),
        label = "glow",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .background(Brush.verticalGradient(0f to glow, 0.75f to colors.background))
            .systemBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBtn(Icons.Down, stringResource(R.string.cd_collapse), onCollapse)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Txt(stringResource(R.string.now_playing).uppercase(), Theme.type.label, color = colors.textDim)
                state.album?.let { Txt(it, Theme.type.small, maxLines = 1, align = TextAlign.Center) }
            }
            IconBtn(
                Icons.Queue,
                stringResource(R.string.cd_queue),
                { showQueue = !showQueue },
                tint = if (showQueue) colors.accent else colors.text,
            )
        }

        AnimatedContent(
            targetState = showQueue,
            transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.96f)) togetherWith fadeOut() },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            label = "cover-queue",
        ) { queue ->
            if (queue) {
                QueueList(state.queue, state.isPlaying) { connection.skipTo(it) }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Artwork(
                        uri = state.artworkUri,
                        sizePx = coverPx,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f, matchHeightConstraintsFirst = true)
                            .shadow(32.dp, RoundedCornerShape(10.dp)),
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Txt(state.title, Theme.type.headline, maxLines = 2, align = TextAlign.Center)
        state.artist?.let {
            Spacer(Modifier.height(4.dp))
            Txt(it, Theme.type.title, color = colors.textDim, maxLines = 1, align = TextAlign.Center)
        }

        Spacer(Modifier.height(20.dp))
        Progress(state, connection)
        Spacer(Modifier.height(12.dp))
        Controls(state, connection)
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun Progress(state: PlayerUiState, connection: PlayerConnection) {
    val position by rememberPlaybackPosition(connection, state)
    var scrub by remember { mutableStateOf<Float?>(null) }
    val duration = state.durationMs.coerceAtLeast(1)
    val fraction = (position.toFloat() / duration).coerceIn(0f, 1f)

    SeekBar(
        fraction = fraction,
        onScrub = { scrub = it },
        onSeek = { connection.seekTo((it * duration).toLong()) },
        enabled = state.durationMs > 0,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        val shown = scrub?.let { (it * duration).toLong() } ?: position
        Txt(formatDuration(shown), Theme.type.mono, color = Theme.colors.textDim)
        Txt(formatDuration(state.durationMs), Theme.type.mono, color = Theme.colors.textDim)
    }
}

@Composable
private fun Controls(state: PlayerUiState, connection: PlayerConnection) {
    val colors = Theme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Toggle(
            icon = Icons.Shuffle,
            label = stringResource(R.string.cd_shuffle),
            active = state.shuffle,
            onClick = connection::toggleShuffle,
        )
        IconBtn(Icons.Previous, stringResource(R.string.cd_previous), connection::previous, size = 56.dp, iconSize = 26.dp)
        Box(
            modifier = Modifier
                .size(78.dp)
                .pressable(connection::togglePlayPause, pressedScale = 0.92f)
                .shadow(20.dp, CircleShape)
                .clip(CircleShape)
                .background(colors.accent),
            contentAlignment = Alignment.Center,
        ) {
            Glyph(
                if (state.isPlaying) Icons.Pause else Icons.Play,
                tint = colors.onAccent,
                size = 28.dp,
                contentDescription = stringResource(if (state.isPlaying) R.string.cd_pause else R.string.cd_play),
            )
        }
        IconBtn(Icons.Next, stringResource(R.string.cd_next), connection::next, size = 56.dp, iconSize = 26.dp)
        val repeatLabel = when (state.repeatMode) {
            Player.REPEAT_MODE_ONE -> R.string.cd_repeat_one
            Player.REPEAT_MODE_ALL -> R.string.cd_repeat_all
            else -> R.string.cd_repeat_off
        }
        Toggle(
            icon = Icons.Repeat,
            label = stringResource(repeatLabel),
            active = state.repeatMode != Player.REPEAT_MODE_OFF,
            onClick = connection::cycleRepeatMode,
            // Font Awesome Free n'a pas d'icône « répéter un titre » : un « 1 » s'ajoute à la répétition.
            badge = if (state.repeatMode == Player.REPEAT_MODE_ONE) "1" else null,
        )
    }
}

/** Bouton à deux états : orange avec un point dessous quand il est actif. */
@Composable
private fun Toggle(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    badge: String? = null,
) {
    val colors = Theme.colors
    val tint by animateColorAsState(if (active) colors.accent else colors.textDim, label = "toggle")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            IconBtn(icon, label, onClick, tint = tint)
            if (badge != null) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 4.dp, end = 2.dp)
                        .size(15.dp)
                        .clip(CircleShape)
                        .background(colors.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Txt(badge, Theme.type.label.copy(fontSize = 9.sp), color = colors.onAccent)
                }
            }
        }
        Box(
            Modifier
                .size(4.dp)
                .clip(CircleShape)
                .background(if (active) colors.accent else Color.Transparent),
        )
    }
}

@Composable
private fun QueueList(queue: List<QueueEntry>, isPlaying: Boolean, onSelect: (Int) -> Unit) {
    val colors = Theme.colors
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        val current = queue.indexOfFirst { it.isCurrent }
        if (current > 0) listState.scrollToItem(current - 1)
    }
    Column(Modifier.fillMaxSize()) {
        Txt(
            stringResource(R.string.queue_title),
            Theme.type.tab,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(queue, key = { it.index }) { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .rowPressable { onSelect(entry.index) }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(34.dp)) {
                        if (entry.isCurrent) {
                            Equalizer(isPlaying, Modifier.size(14.dp))
                        } else {
                            Txt(
                                String.format(Locale.ROOT, "%02d", entry.index + 1),
                                Theme.type.mono,
                                color = colors.textFaint,
                            )
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Txt(
                            entry.title,
                            Theme.type.title,
                            color = if (entry.isCurrent) colors.accent else colors.text,
                            maxLines = 1,
                        )
                        entry.artist?.let { Txt(it, Theme.type.small, color = colors.textDim, maxLines = 1) }
                    }
                }
            }
        }
    }
}
