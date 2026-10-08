package fr.douwdy.lecteur.ui.components

import android.net.Uri
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import fr.douwdy.lecteur.data.ArtworkLoader

/**
 * Pochette du fichier [uri], ou une note de musique s'il n'en a pas.
 * [sizePx] est la taille de décodage : la plus petite suffisante pour l'affichage.
 */
@Composable
fun Artwork(
    uri: Uri?,
    sizePx: Int,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var image by remember(uri, sizePx) {
        mutableStateOf(uri?.let { ArtworkLoader.cached(it, sizePx)?.asImageBitmap() })
    }
    LaunchedEffect(uri, sizePx) {
        if (uri != null && image == null) {
            image = ArtworkLoader.load(context, uri, sizePx)?.asImageBitmap()
        }
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(targetState = image, label = "artwork") { bitmap: ImageBitmap? ->
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.fillMaxWidth(0.45f),
                    )
                }
            }
        }
    }
}
