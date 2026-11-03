package com.irvingguerra.litedash

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Radius
import com.irvingguerra.litedash.core.ui.Typography
import kotlinx.coroutines.delay

// Piezas compartidas del Taller: el historial corto de una señal, su línea fina y la
// tarjeta de dato (nombre chico, número grande, línea abajo), como la app Salud de Apple.

/** Cuántos segundos guarda el historial y cada cuánto se toma un punto. */
internal const val SERIES_SECONDS = 90
private const val SERIES_STEP_MS = 500L
internal const val SERIES_POINTS = SERIES_SECONDS * 1000 / SERIES_STEP_MS.toInt()

/**
 * Los últimos valores de [signal], uno cada medio segundo (sin dato, no se agrega punto).
 * Mientras se ve, el OBD lee esa señal; al salir de la pantalla se deja de leer.
 */
@Composable
internal fun rememberSeries(d: Dependencies, signal: String): List<Float> {
    val points = remember(signal) { mutableStateListOf<Float>() }
    // Pide la señal al OBD sin leerla aquí: quien llama no se rearma con cada dato, solo con cada punto.
    DisposableEffect(signal) {
        d.bus.want(signal)
        onDispose { d.bus.unwant(signal) }
    }
    LaunchedEffect(signal) {
        while (true) {
            d.bus.value(signal)?.let { v ->
                points += v
                if (points.size > SERIES_POINTS) points.removeRange(0, points.size - SERIES_POINTS)
            }
            delay(SERIES_STEP_MS)
        }
    }
    return points
}

/**
 * Línea fina de los últimos [SERIES_SECONDS] segundos con un velo suave debajo. Se llena
 * de derecha a izquierda (lo más nuevo siempre a la derecha); con menos de dos puntos no
 * dibuja nada pero guarda su lugar.
 */
@Composable
internal fun Sparkline(points: List<Float>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val lo = points.min()
        val hi = points.max().let { if (it - lo < 1e-3f) lo + 1f else it }
        val line = Path()
        points.forEachIndexed { k, v ->
            val x = size.width * (SERIES_POINTS - points.size + k) / (SERIES_POINTS - 1)
            val y = size.height * (1 - (v - lo) / (hi - lo)) * 0.8f + size.height * 0.1f
            if (k == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }
        val firstX = size.width * (SERIES_POINTS - points.size) / (SERIES_POINTS - 1)
        val fill = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(firstX, size.height)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.22f), Color.Transparent)))
        drawPath(line, color, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * Tarjeta de un dato: el punto de color y el nombre en chico, el valor grande, una línea de
 * detalle y, si hay historial, su [Sparkline]. Lisa, sobre [Colors.surfaceHigh].
 */
@Composable
internal fun StatTile(
    name: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = LocalColors.current.text,
    dot: Color? = null,
    detail: String? = null,
    detailColor: Color = LocalColors.current.textMuted,
    series: List<Float>? = null,
    seriesColor: Color = LocalColors.current.textMuted,
    onClick: (() -> Unit)? = null,
) {
    val c = LocalColors.current
    var m = modifier.clip(RoundedCornerShape(Radius.inner)).background(c.surfaceHigh)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Column(m.padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (dot != null) Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Label(name, Typography.small, c.textMuted, maxLines = 1)
        }
        Spacer(Modifier.height(4.dp))
        Label(value, Typography.title.copy(fontSize = 21.sp), valueColor, maxLines = 1)
        if (detail != null) Label(detail, Typography.small, detailColor, maxLines = 1)
        if (series != null) {
            Spacer(Modifier.weight(1f).heightIn(min = 6.dp))
            Sparkline(series, seriesColor, Modifier.fillMaxWidth().height(26.dp))
        }
    }
}

/**
 * Cuadrícula de tarjetas del mismo ancho: tantas columnas como quepan de [minWidth]
 * (de una a [maxColumns]); la última fila se completa con huecos para que no se estiren.
 * [content] recibe el modificador de su lugar (ancho y alto de la fila).
 */
@Composable
internal fun <T> TileGrid(
    items: List<T>,
    minWidth: Dp,
    modifier: Modifier = Modifier,
    maxColumns: Int = 3,
    gap: Dp = 10.dp,
    content: @Composable (T, Modifier) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = ((maxWidth + gap) / (minWidth + gap)).toInt().coerceIn(1, maxColumns)
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            // Las de una misma fila, del mismo alto (la más alta manda).
            for (row in items.chunked(columns)) Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(gap)) {
                for (item in row) content(item, Modifier.weight(1f).fillMaxHeight())
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
