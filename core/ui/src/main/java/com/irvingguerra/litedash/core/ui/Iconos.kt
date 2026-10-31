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
object Iconos {
    val auto = icono(
        "M3 16v-3.5l2.4-4.8a1.5 1.5 0 0 1 1.3-.7h8.6a1.5 1.5 0 0 1 1.2.6l3.5 4.4h1a1 1 0 0 1 1 1v3 M9 16h6 M3 16h1.5 M19.5 16h1.5 M5 12h15",
        "M5 16a2 2 0 1 0 4 0a2 2 0 1 0 -4 0 M15 16a2 2 0 1 0 4 0a2 2 0 1 0 -4 0",
    )
    val apps = icono("M4 4h6v6h-6z M14 4h6v6h-6z M4 14h6v6h-6z M14 14h6v6h-6z")
    val ajustes = icono("M4 6h8 M16 6h4 M14 4v4 M4 12h2 M10 12h10 M8 10v4 M4 18h11 M19 18h1 M17 16v4")
    val volumen = icono("M4 9.5h3.5l4.5-4v13l-4.5-4h-3.5z M16 9a4 4 0 0 1 0 6 M18.5 6.5a7.5 7.5 0 0 1 0 11")
    val menos = icono("M6 12h12")
    val mas = icono("M12 6v12 M6 12h12")
    val cerrar = icono("M7 7l10 10 M17 7l-10 10")
    val listo = icono("M5 12.5l4.5 4.5l9.5 -10")
    val navegar = icono("M12 3l7.5 17.5l-7.5 -4l-7.5 4z")
    val telefono = icono("M5 4h4l2 5l-2.5 1.5a11 11 0 0 0 5 5l1.5 -2.5l5 2v4a2 2 0 0 1 -2 2a16 16 0 0 1 -15 -15a2 2 0 0 1 2 -2")
    val musica = icono("M9 17v-12l10 -2v12 M3 17a3 3 0 1 0 6 0a3 3 0 1 0 -6 0 M13 15a3 3 0 1 0 6 0a3 3 0 1 0 -6 0")
    val buscar = icono("M4 10a6 6 0 1 0 12 0a6 6 0 1 0 -12 0 M20 20l-5.5 -5.5")
    val esquina = icono("M19 11v8h-8 M19 19l-7 -7")
    val tablero = icono("M4 4h7v9h-7z M13 4h7v5h-7z M13 11h7v9h-7z M4 15h7v5h-7z")
    val reloj = icono("M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0 M12 7v5l3 3")
    val play = icono(rellenos = listOf("M7 4.5v15a1 1 0 0 0 1.5 .9l12 -7.5a1 1 0 0 0 0 -1.8l-12 -7.5a1 1 0 0 0 -1.5 .9z"))
    val pausa = icono(rellenos = listOf("M6 4h4v16h-4z M14 4h4v16h-4z"))
    val anterior = icono("M5 5v14", rellenos = listOf("M20 5.8v12.4a1 1 0 0 1 -1.6 .8l-8.6 -6.2a1 1 0 0 1 0 -1.6l8.6 -6.2a1 1 0 0 1 1.6 .8z"))
    val siguiente = icono("M19 5v14", rellenos = listOf("M4 5.8v12.4a1 1 0 0 0 1.6 .8l8.6 -6.2a1 1 0 0 0 0 -1.6l-8.6 -6.2a1 1 0 0 0 -1.6 .8z"))

    private fun icono(vararg trazos: String, rellenos: List<String> = emptyList()): ImageVector {
        val b = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        for (t in trazos) b.addPath(
            addPathNodes(t),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        for (r in rellenos) b.addPath(addPathNodes(r), fill = SolidColor(Color.Black))
        return b.build()
    }
}

@Composable
fun Icono(icono: ImageVector, color: Color = LocalColores.current.texto, tam: Dp = 24.dp, modifier: Modifier = Modifier) {
    Image(icono, contentDescription = null, modifier.size(tam), colorFilter = ColorFilter.tint(color))
}
