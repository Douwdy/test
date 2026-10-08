package fr.douwdy.lecteur.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.douwdy.lecteur.R
import fr.douwdy.lecteur.data.Album
import fr.douwdy.lecteur.data.Library
import fr.douwdy.lecteur.ui.LibraryState
import fr.douwdy.lecteur.ui.MusicViewModel
import fr.douwdy.lecteur.ui.components.Artwork
import fr.douwdy.lecteur.ui.components.EmptyMessage
import fr.douwdy.lecteur.ui.components.EntryRow
import fr.douwdy.lecteur.ui.components.PlayButtons
import fr.douwdy.lecteur.ui.components.TrackRow
import kotlinx.coroutines.launch

private enum class LibraryTab(val label: Int) {
    Tracks(R.string.tab_tracks),
    Albums(R.string.tab_albums),
    Artists(R.string.tab_artists),
    Folders(R.string.tab_folders),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: MusicViewModel,
    state: LibraryState,
    query: String,
    currentMediaId: String?,
    onOpenAlbum: (Long) -> Unit,
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

    Scaffold(
        topBar = {
            if (searching) {
                SearchBar(query = query, onQueryChange = viewModel::setQuery, onClose = closeSearch)
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    actions = {
                        IconButton(onClick = { searching = true }) {
                            Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.action_search))
                        }
                        OverflowMenu(onOpenFiles = onOpenFiles, onRefresh = viewModel::refresh)
                    },
                )
            }
        },
    ) { padding ->
        when (state) {
            LibraryState.Loading -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            is LibraryState.Loaded -> if (state.library.isEmpty && query.isEmpty()) {
                EmptyMessage(
                    title = stringResource(R.string.empty_library),
                    body = stringResource(R.string.empty_library_hint),
                    modifier = Modifier.padding(padding),
                    action = {
                        FilledTonalButton(onClick = onOpenFiles) {
                            Text(stringResource(R.string.action_open_files))
                        }
                    },
                )
            } else {
                LibraryTabs(
                    library = state.library,
                    padding = padding,
                    currentMediaId = currentMediaId,
                    viewModel = viewModel,
                    onOpenAlbum = onOpenAlbum,
                    onOpenArtist = onOpenArtist,
                    onOpenFolder = onOpenFolder,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_close_search))
            }
        },
        title = {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text(stringResource(R.string.search_hint)) },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
            )
        },
        actions = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Rounded.Close, contentDescription = null)
                }
            }
        },
    )
}

@Composable
private fun OverflowMenu(onOpenFiles: () -> Unit, onRefresh: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.action_more))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_open_files)) },
                leadingIcon = { Icon(Icons.Rounded.FolderOpen, contentDescription = null) },
                onClick = {
                    expanded = false
                    onOpenFiles()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_refresh)) },
                leadingIcon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                onClick = {
                    expanded = false
                    onRefresh()
                },
            )
        }
    }
}

@Composable
private fun LibraryTabs(
    library: Library,
    padding: PaddingValues,
    currentMediaId: String?,
    viewModel: MusicViewModel,
    onOpenAlbum: (Long) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenFolder: (String) -> Unit,
) {
    val tabs = LibraryTab.entries
    val pagerState = rememberPagerState { tabs.size }
    val scope = rememberCoroutineScope()
    val listPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 8.dp)

    Column(Modifier.padding(top = padding.calculateTopPadding())) {
        PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
            tabs.forEachIndexed { index, tab ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                    text = { Text(stringResource(tab.label), maxLines = 1) },
                )
            }
        }
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            when (tabs[page]) {
                LibraryTab.Tracks -> TracksTab(library, listPadding, currentMediaId, viewModel)
                LibraryTab.Albums -> AlbumsTab(library.albums, listPadding, onOpenAlbum)
                LibraryTab.Artists -> LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding) {
                    if (library.artists.isEmpty()) item { EmptyMessage(stringResource(R.string.no_results)) }
                    items(library.artists, key = { it.name }) { artist ->
                        EntryRow(
                            title = artist.name,
                            subtitle = stringResource(
                                R.string.separator,
                                pluralStringResource(R.plurals.albums_count, artist.albumCount, artist.albumCount),
                                pluralStringResource(R.plurals.tracks_count, artist.tracks.size, artist.tracks.size),
                            ),
                            artworkUri = artist.tracks.first().uri,
                            onClick = { onOpenArtist(artist.name) },
                        )
                    }
                }
                LibraryTab.Folders -> LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding) {
                    if (library.folders.isEmpty()) item { EmptyMessage(stringResource(R.string.no_results)) }
                    items(library.folders, key = { it.path }) { folder ->
                        EntryRow(
                            title = folder.name,
                            subtitle = stringResource(
                                R.string.separator,
                                pluralStringResource(R.plurals.tracks_count, folder.tracks.size, folder.tracks.size),
                                folder.path,
                            ),
                            icon = Icons.Rounded.Folder,
                            onClick = { onOpenFolder(folder.path) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TracksTab(
    library: Library,
    padding: PaddingValues,
    currentMediaId: String?,
    viewModel: MusicViewModel,
) {
    val tracks = library.tracks
    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        if (tracks.isEmpty()) {
            item { EmptyMessage(stringResource(R.string.no_results)) }
        } else {
            item(key = "buttons") {
                PlayButtons(
                    onPlay = { viewModel.playTracks(tracks) },
                    onShuffle = { viewModel.shuffle(tracks) },
                )
            }
        }
        itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
            TrackRow(
                track = track,
                isCurrent = track.mediaId == currentMediaId,
                onClick = { viewModel.playTracks(tracks, index) },
            )
        }
    }
}

@Composable
private fun AlbumsTab(albums: List<Album>, padding: PaddingValues, onOpenAlbum: (Long) -> Unit) {
    if (albums.isEmpty()) {
        EmptyMessage(stringResource(R.string.no_results))
        return
    }
    val coverPx = with(LocalDensity.current) { 180.dp.roundToPx() }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = 12.dp,
            bottom = padding.calculateBottomPadding(),
        ),
    ) {
        items(albums, key = { it.id }) { album ->
            Column(
                Modifier
                    .clickable { onOpenAlbum(album.id) }
                    .padding(6.dp),
            ) {
                Artwork(
                    uri = album.tracks.first().uri,
                    sizePx = coverPx,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    album.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    album.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
