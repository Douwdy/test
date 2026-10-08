package fr.douwdy.lecteur.ui.screens

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.douwdy.lecteur.R
import fr.douwdy.lecteur.data.Track
import fr.douwdy.lecteur.ui.components.Artwork
import fr.douwdy.lecteur.ui.components.PlayButtons
import fr.douwdy.lecteur.ui.components.TrackRow
import fr.douwdy.lecteur.ui.components.formatDuration

/** Contenu d'un album, d'un artiste ou d'un dossier. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionScreen(
    title: String,
    subtitle: String?,
    tracks: List<Track>,
    currentMediaId: String?,
    onPlay: (startIndex: Int) -> Unit,
    onShuffle: () -> Unit,
    onBack: () -> Unit,
    coverUri: Uri? = null,
    isAlbum: Boolean = false,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            item(key = "header") {
                Header(title, subtitle, tracks, coverUri)
            }
            item(key = "buttons") {
                PlayButtons(onPlay = { onPlay(0) }, onShuffle = onShuffle)
            }
            itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
                TrackRow(
                    track = track,
                    isCurrent = track.mediaId == currentMediaId,
                    onClick = { onPlay(index) },
                    showArtwork = !isAlbum,
                    showTrackNumber = isAlbum,
                )
            }
        }
    }
}

@Composable
private fun Header(title: String, subtitle: String?, tracks: List<Track>, coverUri: Uri?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (coverUri != null) {
            val px = with(LocalDensity.current) { 220.dp.roundToPx() }
            Artwork(coverUri, px, RoundedCornerShape(16.dp), Modifier.size(220.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
        }
        Text(
            stringResource(
                R.string.separator,
                pluralStringResource(R.plurals.tracks_count, tracks.size, tracks.size),
                formatDuration(tracks.sumOf { it.durationMs }),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
