package fr.douwdy.lecteur.playback

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import com.google.common.util.concurrent.ListenableFuture
import fr.douwdy.lecteur.data.ArtworkLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future

/**
 * Pochettes de la notification et de l'écran de verrouillage. L'« artworkUri » d'un morceau est
 * soit une image déjà extraite, soit le fichier audio lui-même : on passe alors par [ArtworkLoader],
 * comme l'interface, qui sait aussi lire les pochettes qu'Android n'extrait pas (WAV, AIFF, Opus…).
 */
@OptIn(UnstableApi::class)
class ArtworkBitmapLoader(context: Context) : BitmapLoader {

    private val context = context.applicationContext
    private val images = DataSourceBitmapLoader(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun supportsMimeType(mimeType: String): Boolean = images.supportsMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = images.decodeBitmap(data)

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = scope.future {
        ArtworkLoader.load(context, uri, NOTIFICATION_SIZE_PX)
            ?: throw IllegalArgumentException("Pas de pochette pour $uri")
    }

    fun release() = scope.cancel()

    private companion object {
        const val NOTIFICATION_SIZE_PX = 512
    }
}
