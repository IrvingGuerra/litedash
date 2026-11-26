// La miniatura de un Diseño: tu pantalla con ese Diseño, dibujada a mano en un solo lienzo (sin widgets vivos ni imágenes).
package com.irvingguerra.litedash

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import com.irvingguerra.litedash.core.ui.CardStyle
import com.irvingguerra.litedash.core.ui.Colors
import com.irvingguerra.litedash.core.ui.MIN_OPACITY
import com.irvingguerra.litedash.core.ui.Radius
import com.irvingguerra.litedash.core.ui.themeColors
import com.irvingguerra.litedash.feature.dashboard.DashboardState
import com.irvingguerra.litedash.feature.dashboard.Grid
import com.irvingguerra.litedash.feature.dashboard.Tile
import com.irvingguerra.litedash.feature.dashboard.TileTypes
import com.irvingguerra.litedash.feature.car.SEDAN_ASPECT
import com.irvingguerra.litedash.feature.car.drawSedan
import com.irvingguerra.litedash.feature.dashboard.cardless
import com.irvingguerra.litedash.feature.dashboard.mapBackground
import com.irvingguerra.litedash.feature.dashboard.tiles
import com.irvingguerra.litedash.feature.map.MapLook
import com.irvingguerra.litedash.feature.map.MapPalette
import com.irvingguerra.litedash.feature.map.ROUTE_BLUE
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Los colores con que se dibuja la miniatura de un Diseño: su tema y tarjetas ([ui]), el haz de su fondo (base y dos
 * luces, como el de litedash.dev), su mapa y su auto.
 */
@Immutable
internal class DesignColors(val ui: Colors, val base: Color, val glowA: Color, val glowB: Color, val map: MapPalette, val car: Color, val glass: Boolean, val fill: Float) {
    /** Los puntitos de color junto al nombre: fondo, tarjetas, realce y auto. */
    val swatches get() = listOf(base, ui.surface, ui.accent, car)

    companion object {
        fun of(d: Design): DesignColors {
            val dark = d.theme != ThemeMode.LIGHT
            val ui = themeColors(dark, d.accent, d.cardColor)
            val (base, a, b) = HAZE[d.id] ?: Triple(ui.background, ui.surface, ui.background)
            val map = MapLook(style = d.mapStyle, palette = d.mapPalette, custom = d.mapColor)
                .colors(dark, (d.cardColor ?: d.accent)?.toArgb()?.and(0xFFFFFF))
            val glass = d.cardStyle == CardStyle.GLASS
            // Lisa entera; de vidrio, según «Cuánto se ven» (casi cristal = poco relleno).
            val fill = if (glass) 0.38f + 0.5f * (d.cardOpacity - MIN_OPACITY) / (1f - MIN_OPACITY) else 1f
            return DesignColors(ui, base, a, b, map, d.carColor, glass, fill)
        }

        /** El haz de cada fondo de litedash.dev (base, luz de arriba a la izquierda, luz de abajo a la derecha). */
        private val HAZE = mapOf(
            "graphite" to Triple(Color(0xFF111418), Color(0xFF3B352A), Color(0xFF1E2733)),
            "cloud" to Triple(Color(0xFFEDF0F3), Color(0xFFFFFDF8), Color(0xFFD9E1E9)),
            "track" to Triple(Color(0xFF100C0E), Color(0xFF2B1418), Color(0xFF5A0F1A)),
            "column" to Triple(Color(0xFF0C121A), Color(0xFF1A2534), Color(0xFF27456A)),
            "neon" to Triple(Color(0xFF110E28), Color(0xFF55186E), Color(0xFF0D3657)),
            "sand" to Triple(Color(0xFFEEE1CF), Color(0xFFFCF4E9), Color(0xFFE0CBB0)),
            "forest" to Triple(Color(0xFF12211A), Color(0xFF2E4D3A), Color(0xFF15281F)),
        )
    }
}

/**
 * La miniatura de un Diseño en esta pantalla ([grid]): su fondo (el haz de sus colores o, en Ruta, el mapa), sus
 * tarjetas con su piel y, en cada una, un dibujo sencillo de su widget (mapa con calles y ruta, el auto, el velocímetro,
 * la música, la hora, el clima). Lo de un módulo apagado no sale ([DashboardState.hidden]; se lee aquí para que cambie
 * al prenderlo o apagarlo).
 */
@Composable
internal fun DesignThumbnail(design: Design, grid: Grid, dashboard: DashboardState, modifier: Modifier = Modifier) {
    val off = TileSpecs.map { it.type }.filter(dashboard.hidden)
    val tiles = remember(design, grid, off) { design.template.tiles(grid, skip = { it in off }) }
    val colors = remember(design) { DesignColors.of(design) }
    val text = rememberTextMeasurer()
    Canvas(modifier.fillMaxWidth().aspectRatio(grid.columns.toFloat() / grid.rows).clip(RoundedCornerShape(Radius.inner))) {
        val k = colors
        if (design.template.mapBackground) drawMap(Rect(Offset.Zero, size), k, route = true)
        else {
            drawRect(k.base)
            drawRect(Brush.radialGradient(listOf(k.glowA, Color.Transparent), Offset(size.width * 0.15f, 0f), size.width * 0.7f))
            drawRect(Brush.radialGradient(listOf(k.glowB, Color.Transparent), Offset(size.width * 0.9f, size.height), size.width * 0.75f))
        }
        val cw = size.width / grid.columns
        val ch = size.height / grid.rows
        val gap = size.width * 0.006f
        for (t in tiles) {
            val r = Rect(t.col * cw + gap, t.row * ch + gap, (t.col + t.width) * cw - gap, (t.row + t.height) * ch - gap)
            val card = !t.plain && !t.cardless
            if (card) drawCard(r, k, onMap = design.template.mapBackground)
            drawWidget(t, r, k, text)
        }
    }
}

/** La tarjeta con su piel: de vidrio (relleno translúcido y un brillo arriba) o lisa con su orilla. */
private fun DrawScope.drawCard(r: Rect, k: DesignColors, onMap: Boolean) {
    val corner = CornerRadius(min(size.width * 0.016f, min(r.width, r.height) * 0.3f))
    // Sobre el mapa de fondo, ahumada con el color del fondo (como en el inicio).
    val fill = if (onMap) lerp(k.ui.background, k.ui.surface, 0.3f).copy(alpha = 0.82f) else k.ui.surface.copy(alpha = k.fill)
    drawRoundRect(fill, r.topLeft, r.size, corner)
    if (k.glass) drawRoundRect(
        Brush.verticalGradient(0f to Color.White.copy(alpha = if (k.ui.dark) 0.1f else 0.3f), 0.55f to Color.Transparent, startY = r.top, endY = r.bottom),
        r.topLeft, r.size, corner,
    )
    else drawRoundRect(k.ui.text.copy(alpha = 0.08f), r.topLeft, r.size, corner, style = Stroke(size.width * 0.0015f))
}

/** El dibujo de un widget dentro de su lugar [r]. */
private fun DrawScope.drawWidget(t: Tile, r: Rect, k: DesignColors, text: TextMeasurer) {
    val c = k.ui
    val p = min(r.width, r.height) * 0.1f
    val inner = Rect(r.left + p, r.top + p, r.right - p, r.bottom - p)
    when (t.type) {
        TileTypes.MAP -> drawMap(r.deflate(size.width * 0.002f), k, route = r.width > size.width * 0.2f, round = true)
        TileTypes.CAR -> drawCar(inner, k.car)
        TileTypes.SPEEDOMETER, TileTypes.GAUGE -> drawGauge(inner, t.data, k, text)
        TileTypes.MUSIC -> drawMusic(inner, k)
        TileTypes.RADIO -> {
            bar(inner.left, inner.top + inner.height * 0.25f, inner.width * 0.25f, inner.height * 0.12f, c.textMuted)
            label(text, "99.3", Offset(inner.left, inner.top + inner.height * 0.42f), inner.height * 0.32f, c.text)
        }
        TileTypes.CLOCK -> {
            val fs = min(inner.height * 0.5f, inner.width * 0.24f)
            label(text, "12:30", Offset(inner.left, inner.center.y - fs * 0.75f), fs, c.text)
            bar(inner.left, inner.center.y + fs * 0.55f, inner.width * 0.5f, fs * 0.16f, c.textMuted)
        }
        TileTypes.WEATHER -> drawWeather(inner, k, text)
        TileTypes.TRIP -> {
            bar(inner.left, inner.top + inner.height * 0.12f, inner.width * 0.3f, inner.height * 0.12f, c.textMuted)
            label(text, "23.4", Offset(inner.left, inner.top + inner.height * 0.3f), min(inner.height * 0.34f, inner.width * 0.2f), c.text)
            bar(inner.left, inner.bottom - inner.height * 0.1f, inner.width, inner.height * 0.06f, c.text.copy(alpha = 0.12f))
            bar(inner.left, inner.bottom - inner.height * 0.1f, inner.width * 0.6f, inner.height * 0.06f, c.accent)
        }
        TileTypes.TIRES -> drawTires(inner, k)
        else -> {
            // Lo demás (apps, teléfono…): su botón redondo y su nombre.
            val s = min(inner.width, inner.height) * 0.45f
            drawRoundRect(c.accent.copy(alpha = 0.85f), Offset(inner.center.x - s / 2, inner.center.y - s * 0.7f), Size(s, s), CornerRadius(s * 0.28f))
            bar(inner.center.x - s * 0.6f, inner.center.y + s * 0.5f, s * 1.2f, s * 0.16f, c.textMuted)
        }
    }
}

/** Una raya redondeada (el texto de mentiras). */
private fun DrawScope.bar(x: Float, y: Float, w: Float, h: Float, color: Color) =
    drawRoundRect(color, Offset(x, y), Size(w, h), CornerRadius(h / 2))

/** Texto de verdad, de [px] de alto, con su esquina de arriba en [at]. */
private fun DrawScope.label(text: TextMeasurer, s: String, at: Offset, px: Float, color: Color) {
    if (px < 3f) return
    drawText(text, s, at, TextStyle(color = color, fontSize = px.toSp(), fontWeight = FontWeight.SemiBold))
}

/** El mapa: tierra, agua, un parque, calles y avenida en su paleta y, si [route], la ruta azul y tú. */
private fun DrawScope.drawMap(r: Rect, k: DesignColors, route: Boolean, round: Boolean = false) {
    val m = k.map
    val path = Path().apply {
        if (round) addRoundRect(androidx.compose.ui.geometry.RoundRect(r, CornerRadius(min(size.width * 0.016f, min(r.width, r.height) * 0.3f))))
        else addRect(r)
    }
    clipPath(path) {
        drawRect(Color(m.land), r.topLeft, r.size)
        fun at(x: Float, y: Float) = Offset(r.left + r.width * x, r.top + r.height * y)
        drawPath(Path().apply {
            moveTo(r.left, r.top + r.height * 0.82f)
            quadraticTo(at(0.5f, 0.7f).x, at(0.5f, 0.7f).y, r.right, r.top + r.height * 0.86f)
            lineTo(r.right, r.bottom); lineTo(r.left, r.bottom); close()
        }, Color(m.water))
        drawRect(Color(m.park), at(0.6f, 0.12f), Size(r.width * 0.16f, r.height * 0.18f))
        val street = size.width * 0.004f
        val streets = listOf(at(-0.1f, 0.2f) to at(1.1f, 0.26f), at(0.18f, -0.1f) to at(0.24f, 1.1f), at(0.78f, -0.1f) to at(0.84f, 1.1f), at(-0.1f, 0.62f) to at(1.1f, 0.58f))
        for ((a, b) in streets) drawLine(Color(m.casings[3]), a, b, street * 1.6f)
        for ((a, b) in streets) drawLine(Color(m.roads[3]), a, b, street)
        val avenue = at(-0.1f, 0.47f) to at(1.1f, 0.4f)
        drawLine(Color(m.casings[0]), avenue.first, avenue.second, street * 2.6f)
        drawLine(Color(m.roads[0]), avenue.first, avenue.second, street * 1.8f)
        drawLine(Color(m.casings[1]), at(0.5f, -0.1f), at(0.47f, 1.1f), street * 2f)
        drawLine(Color(m.roads[1]), at(0.5f, -0.1f), at(0.47f, 1.1f), street * 1.3f)
        if (!route) return@clipPath
        val line = Path().apply {
            val a = at(0.485f, 0.7f); moveTo(a.x, a.y)
            val b = at(0.49f, 0.44f); lineTo(b.x, b.y)
            val c = at(0.81f, 0.41f); lineTo(c.x, c.y)
            val d = at(0.8f, 0.18f); lineTo(d.x, d.y)
        }
        drawPath(line, ROUTE_BLUE.copy(alpha = 0.35f), style = Stroke(street * 4f, cap = StrokeCap.Round))
        drawPath(line, ROUTE_BLUE, style = Stroke(street * 2.2f, cap = StrokeCap.Round))
        val me = at(0.485f, 0.7f)
        drawCircle(Color.White, street * 3f, me)
        drawCircle(Color(0xFF4C9BE6), street * 2.2f, me)
    }
}

/** Tu auto en su color: el mismo de fábrica que sale en el inicio a quien no ha bajado un modelo ([drawSedan]). */
private fun DrawScope.drawCar(r: Rect, color: Color) {
    val w = min(r.width, r.height * SEDAN_ASPECT) * 0.92f
    val h = w / SEDAN_ASPECT
    val x = r.center.x - w / 2
    val y = r.center.y - h / 2
    inset(x, y, size.width - x - w, size.height - y - h) { drawSedan(color) }
}

/** El velocímetro (o un medidor): esfera con su arco del color del Diseño, aguja con números o digital. */
private fun DrawScope.drawGauge(r: Rect, data: String, k: DesignColors, text: TextMeasurer) {
    val c = k.ui
    val color = when {
        "color=red" in data -> Color(0xFFFF3B30)
        "color=cyan" in data -> Color(0xFF35F0FF)
        else -> c.accent
    }
    if ("digital" in data || r.width > r.height * 2.4f) {
        label(text, "48", Offset(r.left, r.center.y - r.height * 0.4f), r.height * 0.6f, c.text)
        val rad = r.height * 0.32f
        drawCircle(Color.White, rad, Offset(r.right - rad, r.center.y))
        drawCircle(Color(0xFFE5484D), rad * 0.82f, Offset(r.right - rad, r.center.y), style = Stroke(rad * 0.18f))
        return
    }
    val rad = min(r.width * 0.45f, r.height * 0.5f)
    val center = Offset(r.center.x, r.top + r.height * 0.55f)
    val box = Rect(center, rad)
    val stroke = rad * 0.08f
    drawArc(c.text.copy(alpha = 0.12f), 150f, 240f, false, box.topLeft, box.size, style = Stroke(stroke, cap = StrokeCap.Round))
    if ("needle" in data) {
        for (i in 0..8) {
            val a = Math.toRadians(150.0 + 30 * i)
            val o = Offset(center.x + rad * cos(a).toFloat(), center.y + rad * sin(a).toFloat())
            val n = Offset(center.x + rad * 0.82f * cos(a).toFloat(), center.y + rad * 0.82f * sin(a).toFloat())
            drawLine(c.text.copy(alpha = 0.6f), o, n, stroke * 0.5f)
        }
        drawArc(color, 342f, 48f, false, box.topLeft, box.size, style = Stroke(stroke))
        val a = Math.toRadians(150.0 + 240 * 0.3)
        drawLine(color, center, Offset(center.x + rad * 0.85f * cos(a).toFloat(), center.y + rad * 0.85f * sin(a).toFloat()), stroke * 0.6f, StrokeCap.Round)
        drawCircle(c.text, stroke * 0.9f, center)
        return
    }
    if ("neon" in data) drawArc(color.copy(alpha = 0.3f), 150f, 72f, false, box.topLeft, box.size, style = Stroke(stroke * 3f, cap = StrokeCap.Round))
    drawArc(color, 150f, 72f, false, box.topLeft, box.size, style = Stroke(stroke, cap = StrokeCap.Round))
    val fs = rad * 0.55f
    val measured = text.measure("48", TextStyle(fontSize = fs.toSp(), fontWeight = FontWeight.SemiBold))
    drawText(measured, c.text, Offset(center.x - measured.size.width / 2f, center.y - measured.size.height / 2f))
}

/** La música: carátula, título y los controles (en fila si es baja). */
private fun DrawScope.drawMusic(r: Rect, k: DesignColors) {
    val c = k.ui
    val row = r.width > r.height * 1.9f
    val art = if (row) r.height else min(r.width * 0.6f, r.height * 0.5f)
    val artAt = if (row) r.topLeft else Offset(r.center.x - art / 2, r.top)
    drawRoundRect(Brush.linearGradient(listOf(Color(0xFF2B2350), Color(0xFF8E3B6B), Color(0xFFF08A4B)), artAt, artAt + Offset(art, art)), artAt, Size(art, art), CornerRadius(art * 0.16f))
    drawCircle(Color(0xFFFFD7A8).copy(alpha = 0.85f), art * 0.16f, artAt + Offset(art * 0.64f, art * 0.38f))
    val play = min(r.height * (if (row) 0.32f else 0.11f), r.width * 0.1f)
    val playAt = if (row) Offset(r.right - play * 1.2f, r.center.y) else Offset(r.center.x, r.bottom - play)
    if (row) {
        val tx = r.left + art + r.height * 0.18f
        val tw = (playAt.x - play * 4f - tx).coerceAtLeast(0f)
        bar(tx, r.center.y - r.height * 0.2f, tw, r.height * 0.14f, c.text.copy(alpha = 0.85f))
        bar(tx, r.center.y + r.height * 0.06f, tw * 0.6f, r.height * 0.12f, c.textMuted)
    } else {
        val ty = artAt.y + art + r.height * 0.06f
        bar(r.center.x - r.width * 0.3f, ty, r.width * 0.6f, r.height * 0.06f, c.text.copy(alpha = 0.85f))
        bar(r.center.x - r.width * 0.2f, ty + r.height * 0.09f, r.width * 0.4f, r.height * 0.05f, c.textMuted)
    }
    drawCircle(c.text, play, playAt)
    drawPath(triangle(playAt + Offset(play * 0.1f, 0f), play * 0.5f, right = true), c.surface)
    drawPath(triangle(playAt + Offset(play * 2.4f, 0f), play * 0.45f, right = true), c.text)
    drawPath(triangle(playAt - Offset(play * 2.4f, 0f), play * 0.45f, right = false), c.text)
}

/** Un triángulo de [s] de alto que apunta a la derecha (o a la izquierda), centrado en [at]. */
private fun triangle(at: Offset, s: Float, right: Boolean) = Path().apply {
    val d = if (right) 1f else -1f
    moveTo(at.x - d * s * 0.45f, at.y - s * 0.55f)
    lineTo(at.x + d * s * 0.55f, at.y)
    lineTo(at.x - d * s * 0.45f, at.y + s * 0.55f)
    close()
}

/** El clima: sol en el realce, una nube y la temperatura. */
private fun DrawScope.drawWeather(r: Rect, k: DesignColors, text: TextMeasurer) {
    val c = k.ui
    val s = min(r.height * 0.7f, r.width * 0.3f)
    val at = Offset(r.left + s * 0.5f, r.center.y)
    drawCircle(c.accent, s * 0.28f, at + Offset(s * 0.12f, -s * 0.14f))
    val cloud = c.text.copy(alpha = 0.9f)
    drawCircle(cloud, s * 0.2f, at + Offset(-s * 0.12f, s * 0.1f))
    drawCircle(cloud, s * 0.26f, at + Offset(s * 0.08f, s * 0.04f))
    drawRoundRect(cloud, at + Offset(-s * 0.32f, s * 0.08f), Size(s * 0.68f, s * 0.24f), CornerRadius(s * 0.12f))
    label(text, "24°", Offset(r.left + s * 1.05f, r.center.y - s * 0.45f), s * 0.62f, c.text)
}

/** Las llantas: el auto visto desde arriba y una bolita por llanta. */
private fun DrawScope.drawTires(r: Rect, k: DesignColors) {
    val h = r.height * 0.8f
    val w = h * 0.45f
    val at = Offset(r.center.x - w / 2, r.center.y - h / 2)
    drawRoundRect(k.car, at, Size(w, h), CornerRadius(w * 0.35f))
    for ((fx, fy) in listOf(-0.35f to 0.2f, 1.35f to 0.2f, -0.35f to 0.78f, 1.35f to 0.78f)) {
        drawCircle(k.ui.good, w * 0.12f, at + Offset(w * fx, h * fy))
    }
}
