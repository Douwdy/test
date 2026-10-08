package fr.douwdy.lecteur.data

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import fr.douwdy.lecteur.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Collator

/**
 * Lit la musique indexée par Android (MediaStore) et la regroupe en albums, artistes et dossiers.
 *
 * Tous les fichiers audio sont pris, quel que soit leur format : c'est le lecteur qui décide ensuite
 * s'il sait les décoder. Seuls les sons système (sonneries, notifications, alarmes) sont écartés.
 */
class MusicRepository(private val context: Context) {

    private val unknownArtist = context.getString(R.string.unknown_artist)
    private val unknownAlbum = context.getString(R.string.unknown_album)
    private val variousArtists = context.getString(R.string.various_artists)

    private val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }

    suspend fun load(): Library = withContext(Dispatchers.IO) {
        val tracks = queryTracks().sortedWith(compareBy(collator) { it.title })
        Library(
            tracks = tracks,
            albums = groupAlbums(tracks),
            artists = groupArtists(tracks),
            folders = groupFolders(tracks),
        )
    }

    /** Émet à chaque ajout, suppression ou modification de fichier audio sur le téléphone. */
    fun changes(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        context.contentResolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            true,
            observer,
        )
        awaitClose { context.contentResolver.unregisterContentObserver(observer) }
    }

    private fun queryTracks(): List<Track> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.ALBUM_ID)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.TRACK)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.RELATIVE_PATH)
            } else {
                @Suppress("DEPRECATION")
                add(MediaStore.Audio.Media.DATA)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(MediaStore.Audio.Media.ALBUM_ARTIST)
            }
        }.toTypedArray()
        val selection = buildString {
            append("${MediaStore.Audio.Media.IS_RINGTONE} = 0")
            append(" AND ${MediaStore.Audio.Media.IS_NOTIFICATION} = 0")
            append(" AND ${MediaStore.Audio.Media.IS_ALARM} = 0")
        }

        val cursor = context.contentResolver.query(collection, projection, selection, null, null)
            ?: return emptyList()
        return cursor.use { readTracks(it, collection) }
    }

    private fun readTracks(cursor: Cursor, collection: Uri): List<Track> {
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
        val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
        val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        val trackCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
        val pathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
        } else {
            @Suppress("DEPRECATION")
            cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
        }
        val albumArtistCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ARTIST)
        } else {
            -1
        }

        val tracks = ArrayList<Track>(cursor.count)
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idCol)
            val fileName = cursor.getString(nameCol).orEmpty()
            // Sur les anciennes versions, TRACK vaut « disque × 1000 + piste ».
            val rawTrack = cursor.getInt(trackCol)
            tracks += Track(
                id = id,
                uri = ContentUris.withAppendedId(collection, id),
                title = cursor.getString(titleCol).cleanTag() ?: fileName.substringBeforeLast('.'),
                artist = cursor.getString(artistCol).cleanTag() ?: unknownArtist,
                album = cursor.getString(albumCol).cleanTag() ?: unknownAlbum,
                albumArtist = if (albumArtistCol >= 0) cursor.getString(albumArtistCol).cleanTag() else null,
                albumId = cursor.getLong(albumIdCol),
                durationMs = cursor.getLong(durationCol),
                trackNumber = rawTrack % 1000,
                discNumber = rawTrack / 1000,
                folderPath = folderOf(cursor.getString(pathCol).orEmpty()),
                fileName = fileName,
            )
        }
        return tracks
    }

    /** Ramène RELATIVE_PATH (« Music/Album/ ») ou DATA (chemin absolu du fichier) au dossier parent. */
    private fun folderOf(raw: String): String {
        val path = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            raw.trimEnd('/')
        } else {
            File(raw).parent.orEmpty().removePrefix("/storage/emulated/0").trim('/')
        }
        return path.ifEmpty { "/" }
    }

    private fun groupAlbums(tracks: List<Track>): List<Album> =
        tracks.groupBy { it.albumId }
            .map { (id, albumTracks) ->
                val sorted = albumTracks.sortedWith(
                    compareBy<Track> { it.discNumber }
                        .thenBy { it.trackNumber }
                        .thenBy(collator) { it.title },
                )
                Album(
                    id = id,
                    title = sorted.first().album,
                    artist = albumArtistOf(sorted),
                    tracks = sorted,
                )
            }
            .sortedWith(compareBy(collator) { it.title })

    private fun albumArtistOf(tracks: List<Track>): String {
        tracks.firstNotNullOfOrNull { it.albumArtist }?.let { return it }
        val artists = tracks.map { it.artist }.distinct()
        return artists.singleOrNull() ?: variousArtists
    }

    private fun groupArtists(tracks: List<Track>): List<Artist> =
        tracks.groupBy { it.artist }
            .map { (name, artistTracks) ->
                Artist(
                    name = name,
                    albumCount = artistTracks.distinctBy { it.albumId }.size,
                    tracks = artistTracks.sortedWith(
                        compareBy<Track, String>(collator) { it.album }
                            .thenBy { it.discNumber }
                            .thenBy { it.trackNumber },
                    ),
                )
            }
            .sortedWith(compareBy(collator) { it.name })

    private fun groupFolders(tracks: List<Track>): List<Folder> =
        tracks.groupBy { it.folderPath }
            .map { (path, folderTracks) ->
                Folder(
                    path = path,
                    name = path.substringAfterLast('/').ifEmpty { path },
                    tracks = folderTracks.sortedWith(compareBy(collator) { it.fileName }),
                )
            }
            .sortedWith(compareBy(collator) { it.name })

    /** MediaStore renvoie parfois « <unknown> » au lieu d'une valeur vide. */
    private fun String?.cleanTag(): String? =
        this?.trim()?.takeUnless { it.isEmpty() || it == MediaStore.UNKNOWN_STRING }
}
