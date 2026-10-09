package fr.douwdy.lecteur.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Collections

/**
 * Récupère la pochette intégrée à un fichier audio, avec un cache mémoire.
 * Marche aussi bien pour les morceaux de la bibliothèque que pour un fichier ouvert depuis une autre app.
 */
object ArtworkLoader {

    private val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    /** Fichiers déjà inspectés qui n'ont pas de pochette, pour ne pas les relire à chaque défilement. */
    private val missing = Collections.synchronizedSet(HashSet<String>())

    /** Limite les lectures simultanées quand on fait défiler une longue liste. */
    private val permits = Semaphore(4)

    fun cached(uri: Uri, sizePx: Int): Bitmap? = cache.get(key(uri, sizePx))

    suspend fun load(context: Context, uri: Uri, sizePx: Int): Bitmap? {
        val key = key(uri, sizePx)
        cache.get(key)?.let { return it }
        if (key in missing) return null

        val bitmap = permits.withPermit {
            withContext(Dispatchers.IO) {
                if (uri.isExtractedImage()) {
                    extractedImage(uri, sizePx)
                } else {
                    thumbnail(context, uri, sizePx) ?: embeddedPicture(context, uri, sizePx)
                }
            }
        }
        if (bitmap != null) cache.put(key, bitmap) else missing += key
        return bitmap
    }

    private fun key(uri: Uri, sizePx: Int) = "$sizePx|$uri"

    /** Pochette déjà extraite du fichier par l'app (voir TagStore) : une image, pas un fichier audio. */
    private fun Uri.isExtractedImage() = scheme == "file" && path?.endsWith(".img") == true

    private fun extractedImage(uri: Uri, sizePx: Int): Bitmap? {
        val path = uri.path ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds, sizePx) })
    }

    /** Plus grand facteur de réduction qui garde l'image au moins aussi grande que [sizePx]. */
    private fun sampleSize(bounds: BitmapFactory.Options, sizePx: Int): Int {
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= sizePx && bounds.outHeight / (sample * 2) >= sizePx) {
            sample *= 2
        }
        return sample
    }

    /** Vignette générée et mise en cache par Android (morceaux de la bibliothèque uniquement). */
    private fun thumbnail(context: Context, uri: Uri, sizePx: Int): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || uri.authority != "media") return null
        return runCatching {
            context.contentResolver.loadThumbnail(uri, Size(sizePx, sizePx), null)
        }.getOrNull()
    }

    private fun embeddedPicture(context: Context, uri: Uri, sizePx: Int): Bitmap? {
        val bytes = runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.embeddedPicture
            } finally {
                retriever.release()
            }
        }.getOrNull() ?: return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds, sizePx) },
        )
    }
}
