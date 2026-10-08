package fr.douwdy.lecteur.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Icônes de l'app, dessinées sur une grille de 24 avec des traits arrondis.
 * Elles sont en noir et se teintent à l'affichage (voir `Glyph`).
 */
object Icons {
    val Play = icon("play") {
        solid("M8 5.2L18.6 12L8 18.8Z", rounded = true)
    }
    val Pause = icon("pause") {
        solid("M6.8 5h3.4v14H6.8zM13.8 5h3.4v14h-3.4z", rounded = true)
    }
    val Next = icon("next") {
        solid("M5.5 6L14.5 12L5.5 18Z", rounded = true)
        line("M18 6v12")
    }
    val Previous = icon("previous") {
        solid("M18.5 6L9.5 12L18.5 18Z", rounded = true)
        line("M6 6v12")
    }
    val Shuffle = icon("shuffle") {
        line("M3.5 7h3c3 0 4 2.2 5 5s2 5 5 5h4")
        line("M3.5 17h3c1.4 0 2.4-0.5 3.1-1.4")
        line("M13.9 8.4C14.6 7.5 15.6 7 17 7h3.5")
        line("M18 4.5L20.5 7L18 9.5")
        line("M18 14.5L20.5 17L18 19.5")
    }
    val Repeat = icon("repeat") {
        line("M4 11.5V9.5a2.5 2.5 0 0 1 2.5-2.5H19.5")
        line("M17 4.5L19.5 7L17 9.5")
        line("M20 12.5v2a2.5 2.5 0 0 1-2.5 2.5H4.5")
        line("M7 19.5L4.5 17L7 14.5")
    }
    val RepeatOne = icon("repeat_one") {
        line("M4 11.5V9.5a2.5 2.5 0 0 1 2.5-2.5H19.5")
        line("M17 4.5L19.5 7L17 9.5")
        line("M20 12.5v2a2.5 2.5 0 0 1-2.5 2.5H4.5")
        line("M7 19.5L4.5 17L7 14.5")
        line("M11 10.8l1.3-0.8v4.4", width = 1.5f)
    }
    val Search = icon("search") {
        line("M17 11a6 6 0 1 1-12 0a6 6 0 1 1 12 0z")
        line("M15.5 15.5L20 20")
    }
    val Close = icon("close") {
        line("M6 6l12 12M18 6L6 18")
    }
    val Back = icon("back") {
        line("M14.5 5.5L8 12l6.5 6.5")
    }
    val Down = icon("down") {
        line("M5.5 9l6.5 6.5L18.5 9")
    }
    val Queue = icon("queue") {
        line("M4 6.5h13M4 11.5h13M4 16.5h7")
        solid("M15 14v6l5-3z", rounded = true)
    }
    val Open = icon("open") {
        line("M3.5 8a2 2 0 0 1 2-2h4l2 2h7a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2z")
        line("M12 11v5M9.5 13.5h5")
    }
    val Folder = icon("folder") {
        line("M3.5 8a2 2 0 0 1 2-2h4l2 2h7a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2z")
    }
    val Refresh = icon("refresh") {
        line("M19.5 12a7.5 7.5 0 1 1-2.2-5.3")
        line("M19.5 4.5v3.8h-3.8")
    }
    val Note = icon("note") {
        line("M9 17.5V6.5l10-2v11")
        solid("M9 17.5a2.6 2.6 0 1 1-5.2 0a2.6 2.6 0 1 1 5.2 0zM19 15.5a2.6 2.6 0 1 1-5.2 0a2.6 2.6 0 1 1 5.2 0z")
    }

    private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

    private fun ImageVector.Builder.line(path: String, width: Float = 1.8f) {
        addPath(
            pathData = addPathNodes(path),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }

    /** Forme pleine ; [rounded] adoucit les angles avec un fin contour arrondi. */
    private fun ImageVector.Builder.solid(path: String, rounded: Boolean = false) {
        addPath(
            pathData = addPathNodes(path),
            fill = SolidColor(Color.Black),
            stroke = if (rounded) SolidColor(Color.Black) else null,
            strokeLineWidth = if (rounded) 1.6f else 0f,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
}
