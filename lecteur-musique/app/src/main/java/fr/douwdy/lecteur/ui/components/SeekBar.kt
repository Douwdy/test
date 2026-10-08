package fr.douwdy.lecteur.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import fr.douwdy.lecteur.ui.theme.Theme

/**
 * Barre de progression du morceau. On peut toucher un point ou faire glisser ;
 * [onScrub] donne la position pendant le geste (pour l'affichage), [onSeek] la position finale.
 */
@Composable
fun SeekBar(
    fraction: Float,
    onScrub: (Float?) -> Unit,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = Theme.colors
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = (dragging ?: fraction).coerceIn(0f, 1f)
    val thumb by animateFloatAsState(if (dragging != null) 9f else 6f, label = "thumb")
    val currentOnSeek by rememberUpdatedState(onSeek)
    val currentOnScrub by rememberUpdatedState(onScrub)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(shown, 0f..1f)
                if (enabled) {
                    setProgress { target ->
                        currentOnSeek(target.coerceIn(0f, 1f))
                        true
                    }
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset -> currentOnSeek((offset.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = (offset.x / size.width).coerceIn(0f, 1f)
                        currentOnScrub(dragging)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        dragging = (change.position.x / size.width).coerceIn(0f, 1f)
                        currentOnScrub(dragging)
                    },
                    onDragEnd = {
                        dragging?.let(currentOnSeek)
                        dragging = null
                        currentOnScrub(null)
                    },
                    onDragCancel = {
                        dragging = null
                        currentOnScrub(null)
                    },
                )
            },
    ) {
        val y = size.height / 2
        val stroke = 3.dp.toPx()
        val x = size.width * shown
        drawLine(colors.line, Offset(0f, y), Offset(size.width, y), stroke, StrokeCap.Round)
        drawLine(colors.text, Offset(0f, y), Offset(x, y), stroke, StrokeCap.Round)
        if (enabled) drawCircle(colors.text, thumb.dp.toPx(), Offset(x, y))
    }
}
