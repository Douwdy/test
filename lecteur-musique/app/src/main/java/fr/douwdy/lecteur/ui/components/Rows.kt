package fr.douwdy.lecteur.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.douwdy.lecteur.R
import fr.douwdy.lecteur.data.Track
import fr.douwdy.lecteur.ui.theme.Icons
import fr.douwdy.lecteur.ui.theme.Theme
import java.util.Locale

private const val THUMB_PX = 144

/** État du morceau d'une ligne par rapport au lecteur. */
enum class RowState { Idle, Current, CurrentPlaying }

/**
 * Ligne d'un morceau. Le morceau en cours passe en orange, avec un égaliseur animé
 * à la place de la vignette ou du numéro de piste.
 */
@Composable
fun TrackRow(
    track: Track,
    state: RowState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showTrackNumber: Boolean = false,
) {
    val colors = Theme.colors
    val current = state != RowState.Idle
    Row(
        modifier = modifier
            .fillMaxWidth()
            .rowPressable(onClick)
            .padding(horizontal = 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showTrackNumber) {
            Box(Modifier.width(32.dp), contentAlignment = Alignment.CenterStart) {
                if (current) {
                    Equalizer(state == RowState.CurrentPlaying, Modifier.size(14.dp))
                } else {
                    Txt(
                        if (track.trackNumber > 0) String.format(Locale.ROOT, "%02d", track.trackNumber) else "—",
                        Theme.type.mono,
                        color = colors.textFaint,
                    )
                }
            }
        } else {
            Box(Modifier.size(48.dp)) {
                Artwork(track.uri, THUMB_PX, RoundedCornerShape(6.dp), Modifier.fillMaxSize())
                if (current) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Equalizer(state == RowState.CurrentPlaying, Modifier.size(16.dp))
                    }
                }
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Txt(
                track.title,
                Theme.type.title,
                color = if (current) colors.accent else colors.text,
                maxLines = 1,
            )
            Txt(
                stringResource(R.string.separator, track.artist, track.album),
                Theme.type.small,
                color = colors.textDim,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(12.dp))
        Txt(formatDuration(track.durationMs), Theme.type.mono, color = colors.textFaint)
    }
}

/** Ligne d'artiste ou de dossier. */
@Composable
fun EntryRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    artworkUri: Uri? = null,
    icon: ImageVector? = null,
) {
    val colors = Theme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .rowPressable(onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                Modifier
                    .size(52.dp)
                    .background(colors.surface, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Glyph(icon, tint = colors.accent)
            }
        } else {
            Artwork(artworkUri, THUMB_PX, CircleShape, Modifier.size(52.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Txt(title, Theme.type.title, maxLines = 1)
            Txt(subtitle, Theme.type.small, color = colors.textDim, maxLines = 1)
        }
        Glyph(Icons.Back, tint = colors.textFaint, size = 18.dp, modifier = Modifier.flipped())
    }
}

/** Boutons « Tout lire » et « Aléatoire » en tête de liste. */
@Composable
fun PlayButtons(
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PillButton(stringResource(R.string.play_all), Icons.Play, onPlay, Modifier.weight(1f))
        PillButton(stringResource(R.string.shuffle_all), Icons.Shuffle, onShuffle, Modifier.weight(1f), filled = false)
    }
}

/** Titre de section en capitales espacées, façon pochette de vinyle. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Txt(
        text.uppercase(),
        Theme.type.label,
        color = Theme.colors.textFaint,
        align = TextAlign.Start,
        modifier = modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

/** Retourne une icône horizontalement (flèche retour → flèche suivante). */
fun Modifier.flipped(): Modifier = graphicsLayer { scaleX = -1f }
