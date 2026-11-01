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
    val mic = icon("M9 5a3 3 0 0 1 6 0v6a3 3 0 0 1 -6 0z M5 11a7 7 0 0 0 14 0 M12 18v3")
    val bluetooth = icon("M7 7l10 10l-5 4v-18l5 4l-10 10")
    val wifi = icon("M2 8.5a15 15 0 0 1 20 0 M5 12a10 10 0 0 1 14 0 M8.5 15.5a5 5 0 0 1 7 0 M12 19h.01")
    val signal = icon("M5 19v-2 M10 19v-5 M15 19v-8 M20 19v-11")
    val location = icon("M12 21s-6 -5.5 -6 -10.5a6 6 0 0 1 12 0c0 5 -6 10.5 -6 10.5z M12 10.5h.01")
    // Clima: dibujados en código, sin imágenes.
    val sun = icon("M12 8a4 4 0 1 0 0 8a4 4 0 1 0 0 -8 M12 2v2 M12 20v2 M4.9 4.9l1.4 1.4 M17.7 17.7l1.4 1.4 M2 12h2 M20 12h2 M4.9 19.1l1.4 -1.4 M17.7 6.3l1.4 -1.4")
    val moon = icon("M20 14.5a8 8 0 1 1 -10.5 -10.5a6.5 6.5 0 0 0 10.5 10.5z")
    val cloud = icon("M7 18h10a4 4 0 0 0 0 -8a6 6 0 0 0 -11.5 1.5a3.3 3.3 0 0 0 1.5 6.5z")
    val partlyCloudy = icon("M8 4v1 M3.5 6l.7 .7 M2 10.5h1 M5 10.5a3.5 3.5 0 0 1 6.5 -1.7 M8 20h9a3.5 3.5 0 0 0 0 -7a5 5 0 0 0 -9.6 1.3a2.9 2.9 0 0 0 .6 5.7z")
    val rain = icon("M7 15h10a4 4 0 0 0 0 -8a6 6 0 0 0 -11.5 1.5a3.3 3.3 0 0 0 1.5 6.5z M8 18l-1 3 M12 18l-1 3 M16 18l-1 3")
    val storm = icon("M7 15h10a4 4 0 0 0 0 -8a6 6 0 0 0 -11.5 1.5a3.3 3.3 0 0 0 1.5 6.5z M13 15l-2 3.5h3l-2 3.5")
    val snow = icon("M7 15h10a4 4 0 0 0 0 -8a6 6 0 0 0 -11.5 1.5a3.3 3.3 0 0 0 1.5 6.5z M8 19h.01 M12 20h.01 M16 19h.01 M10 22h.01 M14 22h.01")
    val fog = icon("M4 9h16 M3 13h18 M5 17h14 M8 21h8")
    val speed = icon("M4.5 17a8.5 8.5 0 1 1 15 0 M12 14l3.5 -4.5 M12 14h.01")
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
