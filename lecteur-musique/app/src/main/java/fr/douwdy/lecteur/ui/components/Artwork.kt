package fr.douwdy.lecteur.ui.components

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.get
import androidx.core.graphics.scale
import fr.douwdy.lecteur.data.ArtworkLoader
import fr.douwdy.lecteur.ui.theme.Icons
import fr.douwdy.lecteur.ui.theme.Theme
import kotlin.math.absoluteValue

/** Pochette du fichier [uri] décodée à [sizePx], ou null tant qu'elle charge / s'il n'en a pas. */
@Composable
fun rememberArtwork(uri: Uri?, sizePx: Int): Bitmap? {
    val context = LocalContext.current
    var bitmap by remember(uri, sizePx) {
        mutableStateOf(uri?.let { ArtworkLoader.cached(it, sizePx) })
    }
    LaunchedEffect(uri, sizePx) {
        if (uri != null && bitmap == null) bitmap = ArtworkLoader.load(context, uri, sizePx)
    }
    return bitmap
}

/** Couleur moyenne d'une pochette, pour teinter le fond du lecteur. */
fun Bitmap.averageColor(): Color {
    // Les vignettes d'Android peuvent être en mémoire graphique, illisibles pixel par pixel.
    val readable = if (config == Bitmap.Config.HARDWARE) copy(Bitmap.Config.ARGB_8888, false) else this
    return Color(readable.scale(1, 1)[0, 0])
}

/**
 * Pochette, ou à défaut un dégradé propre au morceau avec une note :
 * deux albums sans pochette ne se ressemblent pas.
 */
@Composable
fun Artwork(
    uri: Uri?,
    sizePx: Int,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    val bitmap = rememberArtwork(uri, sizePx)
    Box(modifier.clip(shape)) {
        Crossfade(targetState = bitmap, label = "artwork") { image ->
            if (image != null) {
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Placeholder(seed = uri?.toString().orEmpty())
            }
        }
    }
}

@Composable
private fun Placeholder(seed: String) {
    val colors = Theme.colors
    // Mélange le hash : des fichiers voisins (…/41, …/42) doivent avoir des teintes éloignées.
    val hue = ((seed.hashCode() * 2654435761L) % 360).absoluteValue.toFloat()
    val tint = Color.hsv(hue, 0.45f, 0.36f)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(tint, colors.surface))),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(Icons.Note, tint = colors.text.copy(alpha = 0.35f), size = maxWidth * 0.38f)
    }
}
