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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
 *
 * Selon les téléphones, l'index d'Android ne sait lire les tags que des MP3 : pour les autres formats
 * il donne le nom du fichier comme titre, un artiste inconnu et le nom du dossier comme album.
 * [enrich] relit alors les tags directement dans ces fichiers, et garde le résultat en cache.
 */
class MusicRepository(private val context: Context) {

    private val unknownArtist = context.getString(R.string.unknown_artist)
    private val unknownAlbum = context.getString(R.string.unknown_album)
    private val variousArtists = context.getString(R.string.various_artists)

    private val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }

    private val tagStore = TagStore(context)

    /** Lectures de tags simultanées : assez pour avancer vite, sans saturer le stockage. */
    private val readPermits = Semaphore(3)

    /** Bibliothèque selon MediaStore, complétée par les tags déjà relus lors des lancements précédents. */
    suspend fun load(): Library = withContext(Dispatchers.IO) {
        buildLibrary(queryTracks().map { track -> tagStore.get(track.cacheKey)?.let { track.withTags(it) } ?: track })
    }

    /**
     * Relit les tags des fichiers que MediaStore n'a pas su décrire, quelques-uns à la fois,
     * et émet la bibliothèque mise à jour au fil de l'analyse. Ne fait rien si tout est déjà connu.
     */
    fun enrich(library: Library): Flow<Library> = flow {
        val tracks = library.tracks.toMutableList()
        val pending = tracks.indices.filter { tracks[it].looksUntagged() && tagStore.get(tracks[it].cacheKey) == null }
        for (batch in pending.chunked(ENRICH_BATCH)) {
            val read = coroutineScope {
                batch.map { index ->
                    async {
                        readPermits.withPermit { index to TagReader.read(context, tracks[index].uri) }
                    }
                }.awaitAll()
            }
            var changed = false
            for ((index, tags) in read) {
                val cached = tagStore.put(tracks[index].cacheKey, tags)
                if (tags != null) {
                    tracks[index] = tracks[index].withTags(cached)
                    changed = true
                }
            }
            tagStore.save()
            if (changed) emit(buildLibrary(tracks))
        }
        tagStore.retainOnly(tracks.mapTo(HashSet()) { it.cacheKey })
    }.flowOn(Dispatchers.IO)

    /** Tags d'un fichier hors bibliothèque (ouvert depuis une autre app), sans cache. */
    suspend fun readTags(uri: Uri): Tags? = TagReader.read(context, uri)

    /**
     * Signes que MediaStore n'a pas lu les tags : artiste ou album inconnu, titre égal au nom du fichier,
     * album égal au nom du dossier (sa valeur par défaut), ou durée inconnue.
     */
    private fun Track.looksUntagged(): Boolean =
        artist == unknownArtist ||
            album == unknownAlbum ||
            title == fileName.substringBeforeLast('.') ||
            album == folderPath.substringAfterLast('/') ||
            durationMs <= 0

    private fun Track.withTags(tags: CachedTags): Track = copy(
        title = tags.title ?: title,
        artist = tags.artist ?: artist,
        album = tags.album ?: album,
        albumArtist = tags.albumArtist ?: albumArtist,
        trackNumber = tags.trackNumber ?: trackNumber,
        discNumber = tags.discNumber ?: discNumber,
        durationMs = durationMs.takeIf { it > 0 } ?: tags.durationMs ?: 0,
        artworkFile = tags.artworkFile ?: artworkFile,
    )

    private fun buildLibrary(tracks: List<Track>): Library {
        val sorted = tracks.sortedWith(compareBy(collator) { it.title })
        return Library(
            tracks = sorted,
            albums = groupAlbums(sorted),
            artists = groupArtists(sorted),
            folders = groupFolders(sorted),
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
            add(MediaStore.Audio.Media.DATE_MODIFIED)
            add(MediaStore.Audio.Media.SIZE)
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
        val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
        val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
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
                dateModified = cursor.getLong(modifiedCol),
                size = cursor.getLong(sizeCol),
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

    /**
     * Un album = un titre d'album et son artiste d'album ; sans artiste d'album, un titre dans un dossier
     * (les compilations sans tag d'artiste d'album restent ainsi groupées).
     */
    private fun albumKey(track: Track): String =
        track.album.lowercase() + "|" + (track.albumArtist?.lowercase() ?: track.folderPath)

    private fun groupAlbums(tracks: List<Track>): List<Album> =
        tracks.groupBy(::albumKey)
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
                    albumCount = artistTracks.distinctBy(::albumKey).size,
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

    private companion object {
        /** Fichiers analysés entre deux mises à jour de l'écran. */
        const val ENRICH_BATCH = 24
    }
}
