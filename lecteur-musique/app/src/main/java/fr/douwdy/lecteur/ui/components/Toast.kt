package fr.douwdy.lecteur.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import fr.douwdy.lecteur.ui.theme.Theme
import kotlinx.coroutines.delay

/** Message éphémère affiché en bas de l'écran (remplace le Snackbar de Material). */
@Stable
class ToastState {
    var message by mutableStateOf<String?>(null)
        private set

    suspend fun show(text: String) {
        message = text
        delay(4_000)
        message = null
    }
}

@Composable
fun ToastHost(state: ToastState, modifier: Modifier = Modifier) {
    // Garde le dernier texte pendant l'animation de sortie.
    var lastMessage by remember { mutableStateOf("") }
    state.message?.let { lastMessage = it }

    AnimatedVisibility(
        visible = state.message != null,
        modifier = modifier,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
    ) {
        val shape = RoundedCornerShape(14.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .shadow(12.dp, shape)
                .clip(shape)
                .background(Theme.colors.text)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(18.dp)
                    .background(Theme.colors.accent, RoundedCornerShape(2.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Txt(lastMessage, Theme.type.body, color = Theme.colors.background)
        }
    }
}
