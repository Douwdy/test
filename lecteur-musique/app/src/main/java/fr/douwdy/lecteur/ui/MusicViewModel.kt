package fr.douwdy.lecteur.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import fr.douwdy.lecteur.data.Library
import fr.douwdy.lecteur.data.MusicRepository
import fr.douwdy.lecteur.data.Track
import fr.douwdy.lecteur.playback.PlayerConnection
import fr.douwdy.lecteur.playback.externalMediaItem
import fr.douwdy.lecteur.playback.toMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer

sealed interface LibraryState {
    data object Loading : LibraryState
    data class Loaded(val library: Library) : LibraryState
}

class MusicViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MusicRepository(application)
    val player = PlayerConnection(application, viewModelScope)

    private val _library = MutableStateFlow<LibraryState>(LibraryState.Loading)
    val library: StateFlow<LibraryState> = _library.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** Bibliothèque filtrée par la recherche (insensible à la casse et aux accents). */
    val filtered: StateFlow<LibraryState> = combine(_library, _query) { state, query ->
        val needle = query.normalized()
        if (state !is LibraryState.Loaded || needle.isEmpty()) return@combine state
        val lib = state.library
        fun Track.matches() = title.normalized().contains(needle) ||
            artist.normalized().contains(needle) ||
            album.normalized().contains(needle)
        LibraryState.Loaded(
            Library(
                tracks = lib.tracks.filter { it.matches() },
                albums = lib.albums.filter {
                    it.title.normalized().contains(needle) || it.artist.normalized().contains(needle)
                },
                artists = lib.artists.filter { it.name.normalized().contains(needle) },
                folders = lib.folders.filter { it.path.normalized().contains(needle) },
            ),
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryState.Loading)

    private var loaded = false

    /** Charge la bibliothèque une seule fois (à appeler une fois la permission accordée). */
    @OptIn(FlowPreview::class)
    fun loadLibrary() {
        if (loaded) return
        loaded = true
        refresh()
        // Recharge quand des morceaux sont ajoutés ou supprimés (copie depuis l'ordinateur, téléchargement…).
        viewModelScope.launch {
            repository.changes().debounce(2_000).collect { refresh() }
        }
    }

    private var refreshJob: Job? = null

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _library.value = LibraryState.Loaded(repository.load())
        }
    }

    fun setQuery(value: String) {
        _query.value = value
    }

    fun playTracks(tracks: List<Track>, startIndex: Int = 0) {
        player.play(tracks.map { it.toMediaItem() }, startIndex)
    }

    fun shuffle(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        player.play(tracks.map { it.toMediaItem() }, tracks.indices.random(), shuffle = true)
    }

    /** Lit des fichiers venant d'une autre app ou du sélecteur de fichiers. */
    fun playUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) {
                uris.map { externalMediaItem(it, displayNameOf(it)) }
            }
            player.play(items)
        }
    }

    private fun displayNameOf(uri: Uri): String? = runCatching {
        getApplication<Application>().contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment

    override fun onCleared() {
        player.release()
    }
}

private val DIACRITICS = Regex("\\p{Mn}+")

private fun String.normalized(): String =
    Normalizer.normalize(trim().lowercase(), Normalizer.Form.NFD).replace(DIACRITICS, "")
