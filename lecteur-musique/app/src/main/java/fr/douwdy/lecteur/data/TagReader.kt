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
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

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
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return emptyList()
        return descriptor.use { FileInputStream(it.fileDescriptor).channel.use { channel -> chunkTags(channel) } }
    }

    private fun chunkTags(file: FileChannel): List<Metadata> {
        val length = file.size()
        val header = file.readAt(0, 12) ?: return emptyList()
        val type = String(header, 0, 4, Charsets.US_ASCII)
        val form = String(header, 8, 4, Charsets.US_ASCII)
        val littleEndian = when {
            type == "RIFF" && form == "WAVE" -> true
            type == "FORM" && (form == "AIFF" || form == "AIFC") -> false
            else -> return emptyList()
        }

        val found = mutableListOf<Metadata>()
        var hasId3 = false
        var position = 12L
        while (position + 8 <= length) {
            val chunk = file.readAt(position, 8) ?: break
            val name = String(chunk, 0, 4, Charsets.US_ASCII)
            val size = chunk.sizeAt(4, littleEndian)
            val body = position + 8
            // Taille impossible : enregistrement en flux (0xFFFFFFFF), fichier tronqué ou parcours décalé
            // par un bloc impair sans octet de remplissage. La suite est cherchée autrement, plus bas.
            if (size > length - body) break
            when {
                name.equals("id3 ", ignoreCase = true) && size <= MAX_CHUNK_BYTES ->
                    file.readAt(body, size.toInt())?.let(::decodeId3)?.let {
                        found += it
                        hasId3 = true
                    }
                name == "LIST" && littleEndian && size in 4..MAX_CHUNK_BYTES -> {
                    val data = file.readAt(body, size.toInt())
                    if (data != null && String(data, 0, 4, Charsets.US_ASCII) == "INFO") parseInfo(data)?.let(found::add)
                }
            }
            position = body + size + (size and 1)
        }
        // Pas d'ID3 trouvé en suivant les blocs : il peut être collé après le RIFF, ou après un bloc mal formé.
        if (!hasId3) findId3InTail(file, length)?.let(found::add)
        return found
    }

    /**
     * Cherche un tag ID3v2 complet dans la fin du fichier, où les logiciels de tag l'ajoutent.
     * L'en-tête est vérifié (version, taille « synchsafe », fin dans le fichier) pour ne pas
     * confondre trois octets « ID3 » pris au hasard dans le son ou une image.
     */
    private fun findId3InTail(file: FileChannel, length: Long): Metadata? {
        val windowStart = (length - TAIL_SCAN_BYTES).coerceAtLeast(0)
        val window = file.readAt(windowStart, (length - windowStart).toInt()) ?: return null
        var i = 0
        while (i + 10 <= window.size) {
            if (window[i] == 'I'.code.toByte() && window[i + 1] == 'D'.code.toByte() && window[i + 2] == '3'.code.toByte()) {
                val tagSize = id3TagSize(window, i)
                if (tagSize != null && i + tagSize <= window.size) {
                    decodeId3(window.copyOfRange(i, i + tagSize))?.let { return it }
                }
            }
            i++
        }
        return null
    }

    /** Taille totale d'un tag ID3v2 commençant à [offset], ou null si l'en-tête n'est pas valide. */
    private fun id3TagSize(data: ByteArray, offset: Int): Int? {
        val version = data[offset + 3].toInt() and 0xFF
        val revision = data[offset + 4].toInt() and 0xFF
        val flags = data[offset + 5].toInt() and 0xFF
        if (version !in 2..4 || revision == 0xFF) return null
        var size = 0
        for (k in 6..9) {
            val b = data[offset + k].toInt() and 0xFF
            if (b >= 0x80) return null
            size = (size shl 7) or b
        }
        if (size == 0) return null
        val footer = if (version == 4 && flags and 0x10 != 0) 10 else 0
        return 10 + size + footer
    }

    private fun decodeId3(data: ByteArray): Metadata? =
        runCatching { Id3Decoder().decode(data, data.size) }.getOrNull()?.takeIf { it.length() > 0 }

    private fun FileChannel.readAt(position: Long, count: Int): ByteArray? {
        if (count < 0) return null
        val buffer = ByteBuffer.allocate(count)
        var at = position
        while (buffer.hasRemaining()) {
            val read = read(buffer, at)
            if (read <= 0) return null
            at += read
        }
        return buffer.array()
    }

    private fun ByteArray.sizeAt(offset: Int, littleEndian: Boolean): Long {
        val b = (0..3).map { this[offset + it].toLong() and 0xFF }
        return if (littleEndian) {
            b[0] or (b[1] shl 8) or (b[2] shl 16) or (b[3] shl 24)
        } else {
            (b[0] shl 24) or (b[1] shl 16) or (b[2] shl 8) or b[3]
        }
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
        val text = runCatching { decoder.decode(ByteBuffer.wrap(bytes)).toString() }
            .getOrElse { String(bytes, Charsets.ISO_8859_1) }
        return text.trim()
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

    /** Fin de fichier explorée pour retrouver un ID3 hors blocs : de quoi contenir une grande pochette. */
    private const val TAIL_SCAN_BYTES = 8L * 1024 * 1024
}
