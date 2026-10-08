package fr.douwdy.lecteur.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.douwdy.lecteur.ui.theme.Theme

/** Texte de l'app (remplace le `Text` de Material). */
@Composable
fun Txt(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Theme.colors.text,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign? = null,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = if (align != null) style.copy(color = color, textAlign = align) else style.copy(color = color),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Icône teintée. */
@Composable
fun Glyph(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = Theme.colors.text,
    size: Dp = 20.dp,
    contentDescription: String? = null,
) {
    Image(
        imageVector = icon,
        contentDescription = contentDescription,
        colorFilter = ColorFilter.tint(tint),
        modifier = modifier.size(size),
    )
}

/** Clic avec un léger enfoncement à l'appui, à la place de l'ondulation de Material. */
fun Modifier.pressable(
    onClick: () -> Unit,
    pressedScale: Float = 0.9f,
    role: Role = Role.Button,
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }.clickable(interactionSource = source, indication = null, role = role, onClick = onClick)
}

/** Clic sur une ligne de liste : le fond s'éclaire pendant l'appui. */
fun Modifier.rowPressable(onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val background by animateColorAsState(
        targetValue = if (pressed) Theme.colors.surfaceHigh else Color.Transparent,
        animationSpec = tween(if (pressed) 60 else 300),
        label = "row",
    )
    background(background).clickable(interactionSource = source, indication = null, onClick = onClick)
}

@Composable
fun IconBtn(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Theme.colors.text,
    size: Dp = 44.dp,
    iconSize: Dp = 20.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .semantics { this.contentDescription = contentDescription }
            .pressable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(icon, tint = tint, size = iconSize)
    }
}

/** Bouton en pilule : plein (accent) ou en contour. */
@Composable
fun PillButton(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
) {
    val colors = Theme.colors
    val content = if (filled) colors.onAccent else colors.text
    Row(
        modifier = modifier
            .pressable(onClick, pressedScale = 0.96f)
            .height(44.dp)
            .then(
                if (filled) {
                    Modifier.background(colors.accent, CircleShape)
                } else {
                    Modifier.border(1.dp, colors.line, CircleShape)
                },
            )
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Glyph(icon, tint = content, size = 14.dp)
            Spacer(Modifier.width(8.dp))
        }
        Txt(text.uppercase(), Theme.type.label, color = content, maxLines = 1)
    }
}

/** Petit égaliseur animé, signe du morceau en cours. Immobile quand la lecture est en pause. */
@Composable
fun Equalizer(
    playing: Boolean,
    modifier: Modifier = Modifier,
    color: Color = Theme.colors.accent,
    bars: Int = 3,
) {
    val transition = rememberInfiniteTransition(label = "eq")
    val heights = List(bars) { i ->
        transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 380 + i * 130), RepeatMode.Reverse),
            label = "bar$i",
        )
    }
    Canvas(modifier) {
        val gap = size.width / (bars * 3 - 1)
        val barWidth = gap * 2
        heights.forEachIndexed { i, anim ->
            val fraction = if (playing) anim.value else PAUSED_HEIGHTS[i % PAUSED_HEIGHTS.size]
            val h = size.height * fraction
            drawRoundRect(
                color = color,
                topLeft = Offset(i * (barWidth + gap), size.height - h),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2),
            )
        }
    }
}

/** Hauteurs figées des barres quand la lecture est en pause. */
private val PAUSED_HEIGHTS = floatArrayOf(0.45f, 0.8f, 0.3f, 0.6f)

/** Message centré pour les écrans vides (bibliothèque vide, permission, pas de résultat). */
@Composable
fun Message(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    actions: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Txt(title, Theme.type.headline, align = TextAlign.Center)
        if (body != null) {
            Txt(body, Theme.type.body, color = Theme.colors.textDim, align = TextAlign.Center)
        }
        if (actions != null) {
            Spacer(Modifier.height(4.dp))
            actions()
        }
    }
}
