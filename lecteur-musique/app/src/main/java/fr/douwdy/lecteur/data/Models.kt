package fr.douwdy.lecteur.data

import android.content.ContentUris
import android.net.Uri
import androidx.core.net.toUri

/** Un morceau de la bibliothèque, tel qu'indexé par Android (MediaStore). */
data class Track(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String?,
    val albumId: Long,
    val durationMs: Long,
    /** Numéro de piste sans le numéro de disque (0 si inconnu). */
    val trackNumber: Int,
    /** Numéro de disque (0 si inconnu). */
    val discNumber: Int,
    val folderPath: String,
    val fileName: String,
) {
    /** Clé utilisée comme identifiant de média dans le lecteur. */
    val mediaId: String get() = uri.toString()

    /** Pochette d'album exposée par MediaStore (utilisée par la notification). */
    val albumArtUri: Uri
        get() = ContentUris.withAppendedId(ALBUM_ART_BASE, albumId)

    private companion object {
        val ALBUM_ART_BASE: Uri = "content://media/external/audio/albumart".toUri()
    }
}

data class Album(
    val id: Long,
    val title: String,
    val artist: String,
    val tracks: List<Track>,
) {
    val durationMs: Long get() = tracks.sumOf { it.durationMs }
}

data class Artist(
    val name: String,
    val albumCount: Int,
    val tracks: List<Track>,
)

data class Folder(
    /** Chemin complet, sert aussi d'identifiant. */
    val path: String,
    val name: String,
    val tracks: List<Track>,
)

data class Library(
    val tracks: List<Track>,
    val albums: List<Album>,
    val artists: List<Artist>,
    val folders: List<Folder>,
) {
    val isEmpty: Boolean get() = tracks.isEmpty()

    companion object {
        val EMPTY = Library(emptyList(), emptyList(), emptyList(), emptyList())
    }
}
