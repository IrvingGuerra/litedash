package com.irvingguerra.litedash.core.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Íconos propios en una cuadrícula de 24: trazo de 2 con puntas redondas, y
 * los de reproducción rellenos. Sin la librería de íconos de Material (pesa megas).
 */
object Icons {
    val car = icon(
        "M3 16v-3.5l2.4-4.8a1.5 1.5 0 0 1 1.3-.7h8.6a1.5 1.5 0 0 1 1.2.6l3.5 4.4h1a1 1 0 0 1 1 1v3 M9 16h6 M3 16h1.5 M19.5 16h1.5 M5 12h15",
        "M5 16a2 2 0 1 0 4 0a2 2 0 1 0 -4 0 M15 16a2 2 0 1 0 4 0a2 2 0 1 0 -4 0",
    )
    val apps = icon("M4 4h6v6h-6z M14 4h6v6h-6z M4 14h6v6h-6z M14 14h6v6h-6z")
    val settings = icon("M4 6h8 M16 6h4 M14 4v4 M4 12h2 M10 12h10 M8 10v4 M4 18h11 M19 18h1 M17 16v4")
    val volume = icon("M4 9.5h3.5l4.5-4v13l-4.5-4h-3.5z M16 9a4 4 0 0 1 0 6 M18.5 6.5a7.5 7.5 0 0 1 0 11")
    val minus = icon("M6 12h12")
    val plus = icon("M12 6v12 M6 12h12")
    val close = icon("M7 7l10 10 M17 7l-10 10")
    val done = icon("M5 12.5l4.5 4.5l9.5 -10")
    val navigate = icon("M12 3l7.5 17.5l-7.5 -4l-7.5 4z")
    val phone = icon("M5 4h4l2 5l-2.5 1.5a11 11 0 0 0 5 5l1.5 -2.5l5 2v4a2 2 0 0 1 -2 2a16 16 0 0 1 -15 -15a2 2 0 0 1 2 -2")
    val music = icon("M9 17v-12l10 -2v12 M3 17a3 3 0 1 0 6 0a3 3 0 1 0 -6 0 M13 15a3 3 0 1 0 6 0a3 3 0 1 0 -6 0")
    val search = icon("M4 10a6 6 0 1 0 12 0a6 6 0 1 0 -12 0 M20 20l-5.5 -5.5")
    val corner = icon("M19 11v8h-8 M19 19l-7 -7")
    val dashboard = icon("M4 4h7v9h-7z M13 4h7v5h-7z M13 11h7v9h-7z M4 15h7v5h-7z")
    val openExternal = icon("M14 4h6v6 M20 4l-9 9 M18 14v6h-14v-14h6")
    val theme = icon("M20 14.5a8 8 0 1 1 -10.5 -10.5a6.5 6.5 0 0 0 10.5 10.5z")
    val info = icon("M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0 M12 11v5 M12 7.5v.5")
    val back = icon("M9 14l-5 -5l5 -5 M4 9h11a5 5 0 0 1 0 10h-3")
    val home = icon("M4 11l8 -7l8 7 M6 9.5v10.5h12v-10.5 M10 20v-5h4v5")
    val expand = icon("M4 9v-5h5 M20 9v-5h-5 M4 15v5h5 M20 15v5h-5")
    val language = icon("M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0 M3.6 9h16.8 M3.6 15h16.8 M12 3a14 14 0 0 1 0 18 M12 3a14 14 0 0 0 0 18")
    val clock = icon("M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0 M12 7v5l3 3")
    val play = icon(fills = listOf("M7 4.5v15a1 1 0 0 0 1.5 .9l12 -7.5a1 1 0 0 0 0 -1.8l-12 -7.5a1 1 0 0 0 -1.5 .9z"))
    val pause = icon(fills = listOf("M6 4h4v16h-4z M14 4h4v16h-4z"))
    val previous = icon("M5 5v14", fills = listOf("M20 5.8v12.4a1 1 0 0 1 -1.6 .8l-8.6 -6.2a1 1 0 0 1 0 -1.6l8.6 -6.2a1 1 0 0 1 1.6 .8z"))
    val next = icon("M19 5v14", fills = listOf("M4 5.8v12.4a1 1 0 0 0 1.6 .8l8.6 -6.2a1 1 0 0 0 0 -1.6l-8.6 -6.2a1 1 0 0 0 -1.6 .8z"))

    private fun icon(vararg strokes: String, fills: List<String> = emptyList()): ImageVector {
        val b = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        for (t in strokes) b.addPath(
            addPathNodes(t),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        for (r in fills) b.addPath(addPathNodes(r), fill = SolidColor(Color.Black))
        return b.build()
    }
}

@Composable
fun Icon(icon: ImageVector, color: Color = LocalColors.current.text, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    Image(icon, contentDescription = null, modifier.size(size), colorFilter = ColorFilter.tint(color))
}
