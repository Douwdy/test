package fr.douwdy.lecteur.data

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.MetadataRetriever
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.metadata.id3.Id3Decoder
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import fr.douwdy.lecteur.playback.audioExtractorsFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

/** Tags lus directement dans un fichier audio. Les champs absents du fichier restent null. */
class Tags(
    val title: String?,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val durationMs: Long?,
    /** Image de pochette telle qu'elle est stockée dans le fichier (JPEG, PNG…). */
    val artwork: ByteArray?,
) {
    val hasText: Boolean get() = title != null || artist != null || album != null
}

/**
 * Lit les tags avec les extracteurs d'ExoPlayer : commentaires Vorbis (FLAC, Ogg, Opus),
 * ID3 (MP3), atomes iTunes (M4A, ALAC), pochettes intégrées comprises ; plus les tags des WAV
 * et AIFF (bloc ID3, liste INFO), qu'ExoPlayer ignore. Les fichiers sans aucun tag donnent null.
 *
 * Sert de secours quand l'index d'Android (MediaStore) n'a pas su lire les tags d'un fichier,
 * ce qui arrive selon les téléphones pour tout ce qui n'est pas du MP3.
 */
@OptIn(UnstableApi::class)
object TagReader {

    suspend fun read(context: Context, uri: Uri): Tags? = withContext(Dispatchers.IO) {
        try {
            // Délai dépassé (fichier bizarre, stockage lent) : on garde ce que l'on savait déjà.
            val (entries, durationUs) = retrieve(context, uri) ?: return@withContext null
            // Fichier trop court ou illisible ici : ce n'est qu'un complément, on continue sans.
            val all = entries + runCatching { chunkTags(context, uri) }.getOrDefault(emptyList())
            all.toTags(durationUs).takeIf { it.hasText || it.artwork != null }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Fichier illisible, format inconnu ou délai dépassé : on garde ce que l'on savait déjà.
            null
        }
    }

    private suspend fun retrieve(context: Context, uri: Uri): Pair<List<Metadata>, Long>? {
        val retriever = MetadataRetriever.Builder(context, MediaItem.fromUri(uri))
            .setMediaSourceFactory(DefaultMediaSourceFactory(context, audioExtractorsFactory()))
            .build()
        return retriever.use {
            withTimeoutOrNull(TIMEOUT_MS) {
                val groups = it.retrieveTrackGroups().await()
                val durationUs = it.retrieveDurationUs().await()
                val metadata = buildList {
                    for (g in 0 until groups.length) {
                        val group = groups[g]
                        for (f in 0 until group.length) group.getFormat(f).metadata?.let(::add)
                    }
                }
                metadata to durationUs
            }
        }
    }

    private fun List<Metadata>.toTags(durationUs: Long): Tags {
        val meta = MediaMetadata.Builder().populateFromMetadata(this).build()
        // Media3 ignore les numéros de piste des commentaires Vorbis et tous les numéros de disque.
        var track: Int? = meta.trackNumber
        var disc: Int? = meta.discNumber
        for (metadata in this) {
            for (i in 0 until metadata.length()) {
                when (val entry = metadata[i]) {
                    is VorbisComment -> when (entry.key.uppercase()) {
                        "TRACKNUMBER" -> track = track ?: leadingNumber(entry.value)
                        "DISCNUMBER" -> disc = disc ?: leadingNumber(entry.value)
                    }
                    is TextInformationFrame -> when (entry.id) {
                        "TRCK" -> track = track ?: entry.values.firstOrNull()?.let(::leadingNumber)
                        "TPOS" -> disc = disc ?: entry.values.firstOrNull()?.let(::leadingNumber)
                    }
                }
            }
        }
        return Tags(
            title = meta.title.clean(),
            artist = meta.artist.clean(),
            album = meta.albumTitle.clean(),
            albumArtist = meta.albumArtist.clean(),
            trackNumber = track?.takeIf { it > 0 },
            discNumber = disc?.takeIf { it > 0 },
            durationMs = durationUs.takeIf { it != C.TIME_UNSET && it > 0 }?.let { it / 1000 },
            artwork = meta.artworkData,
        )
    }

    /** « 3/12 » → 3. */
    private fun leadingNumber(value: String): Int? = value.trim().substringBefore('/').trim().toIntOrNull()

    private fun CharSequence?.clean(): String? = this?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Tags rangés dans les blocs d'un WAV (RIFF) ou d'un AIFF, qu'ExoPlayer ignore :
     * bloc « id3 » (WAV et AIFF) et liste « INFO » (WAV), convertie en trames ID3 équivalentes.
     */
    private fun chunkTags(context: Context, uri: Uri): List<Metadata> {
        val stream = context.contentResolver.openInputStream(uri) ?: return emptyList()
        return DataInputStream(stream.buffered()).use { input ->
            val header = ByteArray(12)
            input.readFully(header)
            val type = String(header, 0, 4, Charsets.US_ASCII)
            val form = String(header, 8, 4, Charsets.US_ASCII)
            val littleEndian = when {
                type == "RIFF" && form == "WAVE" -> true
                type == "FORM" && (form == "AIFF" || form == "AIFC") -> false
                else -> return@use emptyList()
            }
            readChunks(input, littleEndian)
        }
    }

    private fun readChunks(input: DataInputStream, littleEndian: Boolean): List<Metadata> {
        val found = mutableListOf<Metadata>()
        val id = ByteArray(4)
        try {
            while (true) {
                input.readFully(id)
                val size = input.readSize(littleEndian)
                val padded = size + (size and 1)
                val name = String(id, Charsets.US_ASCII)
                when {
                    name.equals("id3 ", ignoreCase = true) && size <= MAX_CHUNK_BYTES -> {
                        val data = ByteArray(size.toInt()).also(input::readFully)
                        Id3Decoder().decode(data, data.size)?.let(found::add)
                        input.skipFully(padded - size)
                    }
                    name == "LIST" && littleEndian && size in 4..MAX_CHUNK_BYTES -> {
                        val data = ByteArray(size.toInt()).also(input::readFully)
                        if (String(data, 0, 4, Charsets.US_ASCII) == "INFO") parseInfo(data)?.let(found::add)
                        input.skipFully(padded - size)
                    }
                    else -> input.skipFully(padded)
                }
            }
        } catch (_: EOFException) {
            // Fin du fichier : tous les blocs ont été vus.
        }
        return found
    }

    /** Liste « INFO » d'un WAV : INAM (titre), IART (artiste), IPRD (album), IPRT/ITRK (piste). */
    private fun parseInfo(data: ByteArray): Metadata? {
        val frames = mutableListOf<TextInformationFrame>()
        var i = 4
        while (i + 8 <= data.size) {
            val key = String(data, i, 4, Charsets.US_ASCII)
            val size = (data[i + 4].toInt() and 0xFF) or ((data[i + 5].toInt() and 0xFF) shl 8) or
                ((data[i + 6].toInt() and 0xFF) shl 16) or ((data[i + 7].toInt() and 0xFF) shl 24)
            if (size < 0 || i + 8 + size > data.size) break
            val value = decodeText(data, i + 8, size)
            val id3 = INFO_TO_ID3[key]
            if (id3 != null && value.isNotEmpty()) frames += TextInformationFrame(id3, null, listOf(value))
            i += 8 + size + (size and 1)
        }
        return frames.takeIf { it.isNotEmpty() }?.let { Metadata(it) }
    }

    /** UTF-8 si le texte est valide, sinon Latin-1 (vieux fichiers Windows). */
    private fun decodeText(data: ByteArray, offset: Int, length: Int): String {
        var end = offset + length
        while (end > offset && data[end - 1] == 0.toByte()) end--
        val bytes = data.copyOfRange(offset, end)
        val decoder = Charsets.UTF_8.newDecoder()
        val text = runCatching { decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString() }
            .getOrElse { String(bytes, Charsets.ISO_8859_1) }
        return text.trim()
    }

    private fun DataInputStream.readSize(littleEndian: Boolean): Long {
        val raw = readInt()
        return (if (littleEndian) Integer.reverseBytes(raw) else raw).toLong() and 0xFFFFFFFFL
    }

    private fun InputStream.skipFully(count: Long) {
        var left = count
        while (left > 0) {
            val skipped = skip(left)
            if (skipped <= 0) {
                if (read() == -1) throw EOFException()
                left--
            } else {
                left -= skipped
            }
        }
    }

    private val INFO_TO_ID3 = mapOf(
        "INAM" to "TIT2",
        "IART" to "TPE1",
        "IPRD" to "TALB",
        "IPRT" to "TRCK",
        "ITRK" to "TRCK",
    )

    private const val TIMEOUT_MS = 10_000L
    private const val MAX_CHUNK_BYTES = 16L * 1024 * 1024
}
