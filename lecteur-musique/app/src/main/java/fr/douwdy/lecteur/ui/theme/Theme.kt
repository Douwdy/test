package fr.douwdy.lecteur.ui.theme

import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import fr.douwdy.lecteur.R

/** Palette : noir chaud, crème, et un orange vif pour ce qui est en train de jouer. */
@Immutable
data class Palette(
    val background: Color = Color(0xFF0F0E0C),
    val surface: Color = Color(0xFF1A1815),
    val surfaceHigh: Color = Color(0xFF25221E),
    val line: Color = Color(0xFF2F2B26),
    val text: Color = Color(0xFFF3EDE2),
    val textDim: Color = Color(0xFF9E968A),
    val textFaint: Color = Color(0xFF5E574E),
    val accent: Color = Color(0xFFFF5B2E),
    val onAccent: Color = Color(0xFF140C08),
)

private val Serif = FontFamily(
    Font(R.font.instrument_serif, FontWeight.Normal, FontStyle.Normal),
    Font(R.font.instrument_serif_italic, FontWeight.Normal, FontStyle.Italic),
)

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun variable(res: Int, vararg weights: Int) = FontFamily(
    weights.map { w ->
        Font(res, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    },
)

private val Grotesk = variable(R.font.space_grotesk, 400, 500, 700)
private val Mono = variable(R.font.jetbrains_mono, 400, 500)

/** Styles de texte de l'app : serif pour les titres, grotesque pour le texte, mono pour les chiffres. */
@Immutable
data class Type(
    /** Grand titre de page (« Lecteur », nom d'album). */
    val display: TextStyle = TextStyle(fontFamily = Serif, fontSize = 52.sp, lineHeight = 52.sp, letterSpacing = (-0.01).em),
    /** Titre du morceau dans le lecteur plein écran. */
    val headline: TextStyle = TextStyle(fontFamily = Serif, fontSize = 34.sp, lineHeight = 38.sp),
    /** Onglets de la bibliothèque. */
    val tab: TextStyle = TextStyle(fontFamily = Serif, fontSize = 26.sp, lineHeight = 30.sp),
    val title: TextStyle = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight(500), fontSize = 16.sp, lineHeight = 21.sp),
    val body: TextStyle = TextStyle(fontFamily = Grotesk, fontSize = 14.sp, lineHeight = 19.sp),
    val small: TextStyle = TextStyle(fontFamily = Grotesk, fontSize = 13.sp, lineHeight = 17.sp),
    /** Étiquettes en capitales espacées et durées. */
    val label: TextStyle = TextStyle(fontFamily = Mono, fontWeight = FontWeight(500), fontSize = 11.sp, letterSpacing = 0.12.em),
    val mono: TextStyle = TextStyle(fontFamily = Mono, fontSize = 12.sp),
)

val LocalPalette = staticCompositionLocalOf { Palette() }
val LocalType = staticCompositionLocalOf { Type() }

/** Accès court au thème : `Theme.colors.accent`, `Theme.type.title`. */
object Theme {
    val colors: Palette
        @Composable get() = LocalPalette.current
    val type: Type
        @Composable get() = LocalType.current
}

@Composable
fun LecteurTheme(content: @Composable () -> Unit) {
    val palette = Palette()
    CompositionLocalProvider(
        LocalPalette provides palette,
        LocalType provides Type(),
        LocalTextSelectionColors provides TextSelectionColors(
            handleColor = palette.accent,
            backgroundColor = palette.accent.copy(alpha = 0.35f),
        ),
        content = content,
    )
}
