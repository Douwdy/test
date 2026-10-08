package fr.douwdy.lecteur.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.douwdy.lecteur.R
import fr.douwdy.lecteur.data.Track
import fr.douwdy.lecteur.ui.components.Artwork
import fr.douwdy.lecteur.ui.components.IconBtn
import fr.douwdy.lecteur.ui.components.PlayButtons
import fr.douwdy.lecteur.ui.components.TrackRow
import fr.douwdy.lecteur.ui.components.Txt
import fr.douwdy.lecteur.ui.components.formatDuration
import fr.douwdy.lecteur.ui.theme.Icons
import fr.douwdy.lecteur.ui.theme.Theme

/** Contenu d'un album, d'un artiste ou d'un dossier. */
@Composable
fun CollectionScreen(
    kind: String,
    title: String,
    subtitle: String?,
    tracks: List<Track>,
    currentMediaId: String?,
    isPlaying: Boolean,
    bottomPadding: Dp,
    onPlay: (startIndex: Int) -> Unit,
    onShuffle: () -> Unit,
    onBack: () -> Unit,
    coverUri: Uri? = null,
    isAlbum: Boolean = false,
) {
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding + 16.dp)) {
            item(key = "header") {
                if (coverUri != null) {
                    CoverHeader(kind, title, subtitle, coverUri)
                } else {
                    TextHeader(kind, title, subtitle)
                }
            }
            item(key = "meta") {
                Txt(
                    stringResource(
                        R.string.separator,
                        pluralStringResource(R.plurals.tracks_count, tracks.size, tracks.size),
                        formatDuration(tracks.sumOf { it.durationMs }),
                    ).uppercase(),
                    Theme.type.label,
                    color = Theme.colors.textDim,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
            item(key = "buttons") {
                PlayButtons(onPlay = { onPlay(0) }, onShuffle = onShuffle)
            }
            itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
                TrackRow(
                    track = track,
                    state = rowState(track.mediaId, currentMediaId, isPlaying),
                    onClick = { onPlay(index) },
                    showTrackNumber = isAlbum,
                )
            }
        }

        // Bouton retour flottant, lisible sur la pochette comme sur le fond.
        IconBtn(
            icon = Icons.Back,
            contentDescription = stringResource(R.string.action_back),
            onClick = onBack,
            modifier = Modifier
                .statusBarsPadding()
                .padding(8.dp)
                .clip(CircleShape)
                .background(Theme.colors.background.copy(alpha = 0.6f)),
        )
    }
}

/** Pochette en pleine largeur qui se fond dans le fond, titre posé dessus. */
@Composable
private fun CoverHeader(kind: String, title: String, subtitle: String?, coverUri: Uri) {
    val colors = Theme.colors
    val coverPx = LocalWindowInfo.current.containerSize.width
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f),
    ) {
        Artwork(coverUri, coverPx, RectangleShape, Modifier.fillMaxSize())
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.45f to Color.Transparent,
                        1f to colors.background,
                    ),
                ),
        )
        HeaderText(kind, title, subtitle, Modifier.align(Alignment.BottomStart))
    }
}

@Composable
private fun TextHeader(kind: String, title: String, subtitle: String?) {
    Column(Modifier.statusBarsPadding()) {
        Spacer(Modifier.height(64.dp))
        HeaderText(kind, title, subtitle)
    }
}

@Composable
private fun HeaderText(kind: String, title: String, subtitle: String?, modifier: Modifier = Modifier) {
    val colors = Theme.colors
    Column(modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Txt(kind.uppercase(), Theme.type.label, color = colors.accent)
        Spacer(Modifier.height(6.dp))
        Txt(title, Theme.type.display, maxLines = 3)
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Txt(subtitle, Theme.type.title, color = colors.textDim, maxLines = 2)
        }
    }
}
