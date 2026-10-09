package fr.douwdy.lecteur.data

import android.net.Uri
import java.io.File

/**
 * Un morceau de la bibliothèque : ce qu'en dit l'index d'Android (MediaStore), complété au besoin
 * par les tags relus dans le fichier (voir [TagReader]).
 */
data class Track(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String?,
    val durationMs: Long,
    /** Numéro de piste sans le numéro de disque (0 si inconnu). */
    val trackNumber: Int,
    /** Numéro de disque (0 si inconnu). */
    val discNumber: Int,
    val folderPath: String,
    val fileName: String,
    /** Date de modification (secondes) et taille du fichier : identifient sa version pour le cache des tags. */
    val dateModified: Long = 0,
    val size: Long = 0,
    /** Pochette extraite du fichier par l'app, quand Android ne la connaît pas. */
    val artworkFile: File? = null,
) {
    /** Clé utilisée comme identifiant de média dans le lecteur. */
    val mediaId: String get() = uri.toString()

    /** Clé du cache des tags : change dès que le fichier est modifié. */
    val cacheKey: String get() = "$id-$dateModified-$size"

    /** Ce qu'il faut charger pour afficher la pochette : l'image extraite, sinon le fichier audio. */
    val coverUri: Uri get() = artworkFile?.let(Uri::fromFile) ?: uri


}

data class Album(
    /** Titre et artiste de l'album (ou dossier), normalisés : regroupe les pistes d'un même disque. */
    val id: String,
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
