package fr.douwdy.lecteur.data

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Tags relus dans un fichier, tels que gardés en cache. Tous les champs peuvent manquer. */
data class CachedTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val durationMs: Long? = null,
    /** Pochette extraite du fichier, enregistrée à part. */
    val artworkFile: File? = null,
)

/**
 * Cache disque des tags relus par [TagReader], pour n'analyser chaque fichier qu'une fois.
 *
 * La clé inclut la date de modification et la taille : un fichier retagué est relu.
 * Un fichier sans tags est aussi mémorisé (entrée vide), pour ne pas le relire à chaque lancement.
 */
class TagStore(context: Context) {

    private val file = AtomicFile(File(context.filesDir, "tags.json"))
    private val artworkDir = File(context.filesDir, "pochettes")
    private val entries = ConcurrentHashMap<String, CachedTags>()
    @Volatile private var loaded = false

    fun get(key: String): CachedTags? {
        ensureLoaded()
        return entries[key]
    }

    fun put(key: String, tags: Tags?): CachedTags {
        ensureLoaded()
        val artwork = tags?.artwork?.let { bytes ->
            artworkDir.mkdirs()
            File(artworkDir, key.toFileName()).also { it.writeBytes(bytes) }
        }
        val cached = CachedTags(
            title = tags?.title,
            artist = tags?.artist,
            album = tags?.album,
            albumArtist = tags?.albumArtist,
            trackNumber = tags?.trackNumber,
            discNumber = tags?.discNumber,
            durationMs = tags?.durationMs,
            artworkFile = artwork,
        )
        entries[key] = cached
        return cached
    }

    /** Oublie les fichiers qui ne sont plus sur le téléphone (ou qui ont changé). */
    fun retainOnly(keys: Set<String>) {
        ensureLoaded()
        val stale = entries.keys - keys
        if (stale.isEmpty()) return
        stale.forEach { key -> entries.remove(key)?.artworkFile?.delete() }
        save()
    }

    @Synchronized
    fun save() {
        val root = JSONObject()
        for ((key, tags) in entries) {
            root.put(
                key,
                JSONObject().apply {
                    putOpt("title", tags.title)
                    putOpt("artist", tags.artist)
                    putOpt("album", tags.album)
                    putOpt("albumArtist", tags.albumArtist)
                    putOpt("track", tags.trackNumber)
                    putOpt("disc", tags.discNumber)
                    putOpt("duration", tags.durationMs)
                    putOpt("artwork", tags.artworkFile?.name)
                },
            )
        }
        val out = file.startWrite()
        try {
            out.write(JSONObject().put("version", VERSION).put("entries", root).toString().toByteArray())
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val root = runCatching { JSONObject(String(file.readFully())) }.getOrNull() ?: return
        if (root.optInt("version") != VERSION) return
        val all = root.optJSONObject("entries") ?: return
        for (key in all.keys()) {
            val o = all.getJSONObject(key)
            entries[key] = CachedTags(
                title = o.optStringOrNull("title"),
                artist = o.optStringOrNull("artist"),
                album = o.optStringOrNull("album"),
                albumArtist = o.optStringOrNull("albumArtist"),
                trackNumber = o.optIntOrNull("track"),
                discNumber = o.optIntOrNull("disc"),
                durationMs = if (o.has("duration")) o.getLong("duration") else null,
                artworkFile = o.optStringOrNull("artwork")?.let { File(artworkDir, it) }?.takeIf { it.exists() },
            )
        }
    }

    private fun JSONObject.optStringOrNull(name: String): String? = if (has(name)) getString(name) else null

    private fun JSONObject.optIntOrNull(name: String): Int? = if (has(name)) getInt(name) else null

    private fun String.toFileName() = replace(Regex("[^A-Za-z0-9_-]"), "_") + ".img"

    private companion object {
        /** À augmenter quand la lecture des tags s'améliore : tout est alors relu une fois. */
        const val VERSION = 2
    }
}
