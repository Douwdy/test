package fr.douwdy.lecteur.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.douwdy.lecteur.R
import fr.douwdy.lecteur.data.Album
import fr.douwdy.lecteur.data.Library
import fr.douwdy.lecteur.ui.LibraryState
import fr.douwdy.lecteur.ui.MusicViewModel
import fr.douwdy.lecteur.ui.components.Artwork
import fr.douwdy.lecteur.ui.components.EntryRow
import fr.douwdy.lecteur.ui.components.Equalizer
import fr.douwdy.lecteur.ui.components.IconBtn
import fr.douwdy.lecteur.ui.components.Message
import fr.douwdy.lecteur.ui.components.PillButton
import fr.douwdy.lecteur.ui.components.PlayButtons
import fr.douwdy.lecteur.ui.components.RowState
import fr.douwdy.lecteur.ui.components.TrackRow
import fr.douwdy.lecteur.ui.components.Txt
import fr.douwdy.lecteur.ui.components.pressable
import fr.douwdy.lecteur.ui.theme.Icons
import fr.douwdy.lecteur.ui.theme.Theme
import kotlinx.coroutines.launch

private enum class LibraryTab(val label: Int) {
    Tracks(R.string.tab_tracks),
    Albums(R.string.tab_albums),
    Artists(R.string.tab_artists),
    Folders(R.string.tab_folders),
}

@Composable
fun LibraryScreen(
    viewModel: MusicViewModel,
    state: LibraryState,
    query: String,
    currentMediaId: String?,
    isPlaying: Boolean,
    bottomPadding: Dp,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenFolder: (String) -> Unit,
    onOpenFiles: () -> Unit,
) {
    var searching by rememberSaveable { mutableStateOf(query.isNotEmpty()) }
    val closeSearch = {
        searching = false
        viewModel.setQuery("")
    }
    BackHandler(enabled = searching, onBack = closeSearch)

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        AnimatedContent(
            targetState = searching,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "header",
        ) { isSearching ->
            if (isSearching) {
                SearchHeader(query, viewModel::setQuery, closeSearch)
            } else {
                Header(
                    onSearch = { searching = true },
                    onOpenFiles = onOpenFiles,
                    onRefresh = viewModel::refresh,
                )
            }
        }

        when (state) {
            LibraryState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Equalizer(playing = true, modifier = Modifier.size(width = 36.dp, height = 28.dp), bars = 4)
            }

            is LibraryState.Loaded -> if (state.library.isEmpty && query.isEmpty()) {
                Message(
                    title = stringResource(R.string.empty_library),
                    body = stringResource(R.string.empty_library_hint),
                    actions = {
                        PillButton(stringResource(R.string.action_open_files), Icons.Open, onOpenFiles)
                    },
                )
            } else {
                LibraryTabs(
                    library = state.library,
                    bottomPadding = bottomPadding,
                    currentMediaId = currentMediaId,
                    isPlaying = isPlaying,
                    viewModel = viewModel,
                    onOpenAlbum = onOpenAlbum,
                    onOpenArtist = onOpenArtist,
                    onOpenFolder = onOpenFolder,
                )
            }
        }
    }
}

@Composable
private fun Header(onSearch: () -> Unit, onOpenFiles: () -> Unit, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(stringResource(R.string.app_name), Theme.type.display, modifier = Modifier.weight(1f), maxLines = 1)
        IconBtn(Icons.Search, stringResource(R.string.action_search), onSearch)
        IconBtn(Icons.Open, stringResource(R.string.action_open_files), onOpenFiles)
        IconBtn(Icons.Refresh, stringResource(R.string.action_refresh), onRefresh)
    }
}

@Composable
private fun SearchHeader(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    val colors = Theme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 22.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBtn(Icons.Back, stringResource(R.string.action_close_search), onClose)
        Box(
            Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        ) {
            if (query.isEmpty()) {
                Txt(stringResource(R.string.search_hint), Theme.type.headline, color = colors.textFaint, maxLines = 1)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = Theme.type.headline.copy(color = colors.text),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
            )
        }
        if (query.isNotEmpty()) {
            IconBtn(Icons.Close, stringResource(R.string.action_clear_search), { onQueryChange("") })
        }
    }
}

@Composable
private fun LibraryTabs(
    library: Library,
    bottomPadding: Dp,
    currentMediaId: String?,
    isPlaying: Boolean,
    viewModel: MusicViewModel,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenFolder: (String) -> Unit,
) {
    val tabs = LibraryTab.entries
    val pagerState = rememberPagerState { tabs.size }
    val scope = rememberCoroutineScope()
    val listPadding = PaddingValues(bottom = bottomPadding + 16.dp)
    val counts = listOf(library.tracks.size, library.albums.size, library.artists.size, library.folders.size)

    TabStrip(
        labels = tabs.map { stringResource(it.label) },
        counts = counts,
        selected = pagerState.currentPage,
        onSelect = { scope.launch { pagerState.animateScrollToPage(it) } },
    )
    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
        val noResults = @Composable { Message(stringResource(R.string.no_results)) }
        when (tabs[page]) {
            LibraryTab.Tracks -> LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding) {
                val tracks = library.tracks
                if (tracks.isEmpty()) {
                    item { noResults() }
                } else {
                    item(key = "buttons") {
                        PlayButtons(onPlay = { viewModel.playTracks(tracks) }, onShuffle = { viewModel.shuffle(tracks) })
                    }
                }
                itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
                    TrackRow(
                        track = track,
                        state = rowState(track.mediaId, currentMediaId, isPlaying),
                        onClick = { viewModel.playTracks(tracks, index) },
                    )
                }
            }

            LibraryTab.Albums -> AlbumsGrid(library.albums, listPadding, onOpenAlbum, noResults)

            LibraryTab.Artists -> LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding) {
                if (library.artists.isEmpty()) item { noResults() }
                items(library.artists, key = { it.name }) { artist ->
                    EntryRow(
                        title = artist.name,
                        subtitle = stringResource(
                            R.string.separator,
                            pluralStringResource(R.plurals.albums_count, artist.albumCount, artist.albumCount),
                            pluralStringResource(R.plurals.tracks_count, artist.tracks.size, artist.tracks.size),
                        ),
                        artworkUri = artist.tracks.first().coverUri,
                        onClick = { onOpenArtist(artist.name) },
                    )
                }
            }

            LibraryTab.Folders -> LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding) {
                if (library.folders.isEmpty()) item { noResults() }
                items(library.folders, key = { it.path }) { folder ->
                    EntryRow(
                        title = folder.name,
                        subtitle = stringResource(
                            R.string.separator,
                            pluralStringResource(R.plurals.tracks_count, folder.tracks.size, folder.tracks.size),
                            folder.path,
                        ),
                        icon = Icons.Folder,
                        onClick = { onOpenFolder(folder.path) },
                    )
                }
            }
        }
    }
}

fun rowState(mediaId: String, currentMediaId: String?, isPlaying: Boolean): RowState = when {
    mediaId != currentMediaId -> RowState.Idle
    isPlaying -> RowState.CurrentPlaying
    else -> RowState.Current
}

/** Onglets en grandes lettres serif, avec le nombre d'éléments en exposant. */
@Composable
private fun TabStrip(labels: List<String>, counts: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    val colors = Theme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val active = index == selected
            val color by animateColorAsState(if (active) colors.text else colors.textFaint, label = "tab")
            Column(Modifier.pressable({ onSelect(index) }, pressedScale = 0.95f)) {
                Row {
                    Txt(label, Theme.type.tab, color = color, maxLines = 1)
                    Txt(
                        counts[index].toString(),
                        Theme.type.label,
                        color = if (active) colors.accent else colors.textFaint,
                        modifier = Modifier.padding(start = 3.dp, top = 2.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .width(if (active) 18.dp else 0.dp)
                        .height(3.dp)
                        .background(colors.accent, RoundedCornerShape(2.dp)),
                )
            }
        }
    }
}

@Composable
private fun AlbumsGrid(
    albums: List<Album>,
    padding: PaddingValues,
    onOpenAlbum: (String) -> Unit,
    noResults: @Composable () -> Unit,
) {
    if (albums.isEmpty()) {
        noResults()
        return
    }
    val coverPx = with(LocalDensity.current) { 180.dp.roundToPx() }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 14.dp,
            end = 14.dp,
            top = 8.dp,
            bottom = padding.calculateBottomPadding(),
        ),
    ) {
        items(albums, key = { it.id }) { album ->
            Column(
                Modifier
                    .pressable({ onOpenAlbum(album.id) }, pressedScale = 0.96f)
                    .padding(6.dp),
            ) {
                Artwork(
                    uri = album.tracks.first().coverUri,
                    sizePx = coverPx,
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                )
                Spacer(Modifier.height(10.dp))
                Txt(album.title, Theme.type.title, maxLines = 1)
                Txt(album.artist, Theme.type.small, color = Theme.colors.textDim, maxLines = 1, align = TextAlign.Start)
            }
        }
    }
}
