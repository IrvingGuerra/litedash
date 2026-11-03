package com.irvingguerra.litedash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.Colors
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography
import kotlin.math.cos
import kotlin.math.sin

/** Del arco del medidor: empieza abajo a la izquierda y da 240° (como un tablero real). */
private const val START_ANGLE = 150f
private const val SWEEP = 240f

/** Cómo se dibuja un medidor. [stored] es como se guarda en el acomodo (no se traduce). */
enum class GaugeStyle(val stored: String, val title: Int) {
    /** Arco grueso que se llena (el de siempre). */
    ARC("arco", R.string.gauge_style_arc),
    /** Aguja clásica con marcas y números, como un tablero de verdad. */
    NEEDLE("aguja", R.string.gauge_style_needle),
    /** El número enorme y una barra de segmentos abajo (como un HUD). */
    DIGITAL("digital", R.string.gauge_style_digital),
    /** Anillo completo alrededor del número. */
    RING("anillo", R.string.gauge_style_ring),
    /** Luces de cambio deportivas: segmentos que crecen hacia la derecha. */
    BAR("barra", R.string.gauge_style_bar),
}

/** De qué color va lo que se llena. DYNAMIC va de verde a ámbar a rojo con el valor. */
enum class GaugeColor(val stored: String, val title: Int) {
    ACCENT("ambar", R.string.gauge_color_accent),
    CAR("auto", R.string.gauge_color_car),
    DYNAMIC("dinamico", R.string.gauge_color_dynamic),
    GREEN("verde", R.string.gauge_color_green),
    BLUE("azul", R.string.gauge_color_blue),
    RED("rojo", R.string.gauge_color_red),
    WHITE("blanco", R.string.gauge_color_white),
}

private val Green = Color(0xFF3DD68C)
private val Blue = Color(0xFF4CA6FF)

/**
 * Cómo se ve un medidor; cada pieza guarda el suyo en su [com.irvingguerra.litedash.feature.dashboard.Tile.data]
 * ("rpm;estilo=aguja;color=dinamico"). Lo que queda como de fábrica no se escribe, así los
 * acomodos de antes (solo la señal) se leen igual.
 */
@Immutable
data class GaugeLook(
    val style: GaugeStyle = GaugeStyle.ARC,
    val color: GaugeColor = GaugeColor.ACCENT,
    /** Máximo del velocímetro en la unidad que se ve (km/h o mph); null = 200 km/h o 120 mph. */
    val max: Int? = null,
    val ticks: Boolean = true,
    val numbers: Boolean = false,
    val redline: Boolean = false,
) {
    fun encode(signal: String = ""): String = buildList {
        if (signal.isNotEmpty()) add(signal)
        if (style != GaugeStyle.ARC) add("estilo=${style.stored}")
        if (color != GaugeColor.ACCENT) add("color=${color.stored}")
        max?.let { add("max=$it") }
        if (!ticks) add("marcas=0")
        if (numbers) add("numeros=1")
        if (redline) add("roja=1")
    }.joinToString(";")

    companion object {
        fun parse(data: String): GaugeLook {
            val f = data.split(';').mapNotNull { part ->
                part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
            }.toMap()
            return GaugeLook(
                style = GaugeStyle.entries.firstOrNull { it.stored == f["estilo"] } ?: GaugeStyle.ARC,
                color = GaugeColor.entries.firstOrNull { it.stored == f["color"] } ?: GaugeColor.ACCENT,
                max = f["max"]?.toIntOrNull(),
                ticks = f["marcas"] != "0",
                numbers = f["numeros"] == "1",
                redline = f["roja"] == "1",
            )
        }
    }
}

/** La señal de un medidor OBD guardada en su pieza (lo primero, sin "="). */
fun signalOf(data: String) = data.substringBefore(';').takeIf { '=' !in it } ?: ""

/** Máximos del velocímetro para elegir, en km/h o mph; el primero es el de fábrica. */
fun speedMaxOptions(imperial: Boolean) = if (imperial) listOf(120, 100, 160, 200) else listOf(200, 160, 240, 320)

/** El color de lo que se llena para el valor [f] (0–1); lo que preocupa, siempre rojo. */
private fun Colors.gauge(color: GaugeColor, car: Color, f: Float, warning: Boolean): Color = when {
    warning -> danger
    else -> when (color) {
        GaugeColor.ACCENT -> accent
        GaugeColor.CAR -> if (car.isSpecified()) car else accent
        GaugeColor.GREEN -> Green
        GaugeColor.BLUE -> Blue
        GaugeColor.RED -> danger
        GaugeColor.WHITE -> text
        GaugeColor.DYNAMIC -> if (f < 0.6f) lerp(Green, accent, f / 0.6f) else lerp(accent, danger, ((f - 0.6f) / 0.4f).coerceIn(0f, 1f))
    }
}

private fun Color.isSpecified() = this != Color.Unspecified

/** Lo que cada estilo necesita para dibujarse, además del número. */
private class GaugeParts(
    val look: GaugeLook,
    val car: Color,
    val warning: Boolean,
    val ticks: Int,
    val labels: (Int) -> String,
    /** Desde qué parte (0–1) empieza la zona roja. */
    val redFrom: Float,
    val fraction: () -> Float,
)

/**
 * Tarjeta de medidor en el estilo de [look]: en una fila (la de abajo en 7") el número
 * con una barra; con más alto, el estilo elegido. [fraction] (0–1) se anima y se lee
 * solo al dibujar: la pieza no se recompone en cada cuadro. [labels] da el número de
 * cada marca (para la aguja y "Números"); [warning] pinta de rojo lo que preocupa.
 */
@Composable
fun GaugeCard(
    number: String,
    unit: String,
    tag: String,
    fraction: Float,
    ticks: Int,
    look: GaugeLook = GaugeLook(),
    carColor: Color = Color.Unspecified,
    labels: (Int) -> String = { "" },
    redFrom: Float = 0.8f,
    warning: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val shown = remember { Animatable(0f) }
    LaunchedEffect(fraction) { shown.animateTo(fraction.coerceIn(0f, 1f), tween(250)) }
    val parts = GaugeParts(look, carColor, warning, ticks.coerceAtLeast(1), labels, redFrom.coerceIn(0f, 1f)) { shown.value }
    Card(Modifier.fillMaxSize(), onClick = onClick) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
            if (maxHeight < 130.dp) CompactGauge(number, unit, tag, parts, maxHeight)
            else when (look.style) {
                GaugeStyle.ARC -> ArcGauge(number, unit, tag, parts, side = minOf(maxWidth, maxHeight * 1.15f))
                GaugeStyle.NEEDLE -> NeedleGauge(number, unit, tag, parts, side = minOf(maxWidth, maxHeight * 1.15f))
                GaugeStyle.DIGITAL -> DigitalGauge(number, unit, tag, parts, maxWidth, maxHeight)
                GaugeStyle.RING -> RingGauge(number, unit, tag, parts, side = minOf(maxWidth, maxHeight))
                GaugeStyle.BAR -> BarGauge(number, unit, tag, parts, maxWidth, maxHeight)
            }
        }
    }
}

/** El círculo del arco (de la aguja también): centrado un poco abajo, del tamaño que quepa. */
private class Dial(size: Size, stroke: Float) {
    val radius = minOf(size.width, size.height * 1.15f) / 2 - stroke
    val center = Offset(size.width / 2, size.height / 2 + radius * 0.12f)
    val topLeft = Offset(center.x - radius, center.y - radius)
    val box = Size(radius * 2, radius * 2)

    fun at(angle: Float, r: Float): Offset {
        val a = Math.toRadians(angle.toDouble())
        return Offset(center.x + r * cos(a).toFloat(), center.y + r * sin(a).toFloat())
    }
}

/** Escribe [text] centrado en [at] (sin salirse por los lados del lienzo). */
private fun DrawScope.label(measurer: TextMeasurer, text: String, at: Offset, style: TextStyle) {
    if (text.isEmpty()) return
    val layout = measurer.measure(text, style)
    val x = (at.x - layout.size.width / 2f).coerceIn(0f, (size.width - layout.size.width).coerceAtLeast(0f))
    drawText(layout, topLeft = Offset(x, at.y - layout.size.height / 2f))
}

/** El número grande, su unidad y la etiqueta (OBD, GPS…) debajo si cabe. */
@Composable
private fun Readout(number: String, unit: String, tag: String, size: Float, color: Color, showTag: Boolean, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Label(number, Typography.clock.copy(fontSize = size.sp), maxLines = 1)
        Label(unit, Typography.small, c.textMuted, maxLines = 1)
        if (showTag && tag.isNotEmpty()) Box(Modifier.padding(top = 6.dp)) { Label(tag, Typography.label, color, maxLines = 1) }
    }
}

/** El estilo de los números de las marcas, del tamaño del medidor. */
private fun tickText(side: Dp, color: Color) =
    TextStyle(fontSize = (side.value * 0.055f).coerceIn(10f, 20f).sp, fontWeight = FontWeight.Medium, color = color)

@Composable
private fun ArcGauge(number: String, unit: String, tag: String, p: GaugeParts, side: Dp) {
    val c = LocalColors.current
    val measurer = rememberTextMeasurer()
    val tickStyle = tickText(side, c.textMuted)
    Canvas(Modifier.fillMaxSize()) {
        // El arco engrosa con el medidor: en grande, 12 dp se veía como un hilo.
        val stroke = (size.minDimension * 0.075f).coerceIn(3.dp.toPx(), 34.dp.toPx())
        val d = Dial(size, stroke)
        drawArc(c.surfaceHigh, START_ANGLE, SWEEP, false, d.topLeft, d.box, style = Stroke(stroke, cap = StrokeCap.Round))
        if (p.look.redline) {
            drawArc(c.danger.copy(alpha = 0.35f), START_ANGLE + SWEEP * p.redFrom, SWEEP * (1 - p.redFrom), false, d.topLeft, d.box, style = Stroke(stroke))
        }
        val f = p.fraction()
        if (f > 0.002f) {
            drawArc(c.gauge(p.look.color, p.car, f, p.warning), START_ANGLE, SWEEP * f, false, d.topLeft, d.box, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        // Marcas discretas por dentro del arco (una larga y una corta) y, si se piden, sus números.
        for (i in 0..p.ticks) {
            val angle = START_ANGLE + SWEEP * i / p.ticks
            val outer = d.radius - stroke * 1.2f
            if (p.look.ticks) {
                val inner = outer - stroke * (if (i % 2 == 0) 0.9f else 0.5f)
                drawLine(c.textMuted.copy(alpha = 0.6f), d.at(angle, outer), d.at(angle, inner), strokeWidth = stroke * 0.18f, cap = StrokeCap.Round)
            }
            if (p.look.numbers && i % 2 == 0) label(measurer, p.labels(i), d.at(angle, outer - stroke * 2.1f), tickStyle)
        }
    }
    Readout(number, unit, tag, (side.value * 0.3f).coerceIn(28f, 130f), c.gauge(p.look.color, p.car, 0.5f, p.warning), side > 200.dp)
}

@Composable
private fun NeedleGauge(number: String, unit: String, tag: String, p: GaugeParts, side: Dp) {
    val c = LocalColors.current
    val measurer = rememberTextMeasurer()
    val tickStyle = tickText(side, c.text.copy(alpha = 0.85f))
    Canvas(Modifier.fillMaxSize()) {
        val stroke = (size.minDimension * 0.05f).coerceIn(3.dp.toPx(), 22.dp.toPx())
        val d = Dial(size, stroke)
        val thin = Stroke(stroke * 0.45f, cap = StrokeCap.Round)
        drawArc(c.surfaceHigh, START_ANGLE, SWEEP, false, d.topLeft, d.box, style = thin)
        if (p.look.redline) {
            drawArc(c.danger, START_ANGLE + SWEEP * p.redFrom, SWEEP * (1 - p.redFrom), false, d.topLeft, d.box, style = Stroke(stroke * 0.9f))
        }
        val f = p.fraction()
        val color = c.gauge(p.look.color, p.car, f, p.warning)
        if (f > 0.002f) drawArc(color, START_ANGLE, SWEEP * f, false, d.topLeft, d.box, style = thin)
        // Marcas: una larga con su número y una corta entre cada par.
        val steps = p.ticks * 2
        for (i in 0..steps) {
            val angle = START_ANGLE + SWEEP * i / steps
            val major = i % 2 == 0
            val outer = d.radius - stroke * 0.9f
            if (p.look.ticks || major) {
                val inner = outer - stroke * (if (major) 1.3f else 0.6f)
                drawLine(
                    if (major) c.text.copy(alpha = 0.8f) else c.textMuted.copy(alpha = 0.6f),
                    d.at(angle, outer), d.at(angle, inner),
                    strokeWidth = stroke * (if (major) 0.22f else 0.14f), cap = StrokeCap.Round,
                )
            }
            if (p.look.numbers && major && (i / 2) % 2 == 0) label(measurer, p.labels(i / 2), d.at(angle, outer - stroke * 2.6f), tickStyle)
        }
        // La aguja con su cola y el centro.
        val angle = START_ANGLE + SWEEP * f
        drawLine(color, d.at(angle + 180f, stroke * 1.4f), d.at(angle, d.radius - stroke * 1.3f), strokeWidth = stroke * 0.42f, cap = StrokeCap.Round)
        drawCircle(color, stroke * 1.05f, d.center)
        drawCircle(c.background, stroke * 0.45f, d.center)
    }
    // La lectura abajo del centro, donde la aguja no pasa.
    Readout(number, unit, tag, (side.value * 0.13f).coerceIn(22f, 64f), c.gauge(p.look.color, p.car, 0.5f, p.warning), side > 260.dp, Modifier.offset(y = side * 0.24f))
}

@Composable
private fun DigitalGauge(number: String, unit: String, tag: String, p: GaugeParts, width: Dp, height: Dp) {
    val c = LocalColors.current
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Readout(number, unit, tag, minOf(height.value * 0.45f, width.value * 0.3f).coerceIn(36f, 200f), c.gauge(p.look.color, p.car, 0.5f, p.warning), height > 220.dp)
        Box(Modifier.height((height.value * 0.06f).coerceIn(6f, 12f).dp))
        Segments(p, Modifier.fillMaxWidth(0.86f).height((height.value * 0.06f).coerceIn(6f, 22f).dp), count = 24, wedge = false)
    }
}

@Composable
private fun RingGauge(number: String, unit: String, tag: String, p: GaugeParts, side: Dp) {
    val c = LocalColors.current
    val measurer = rememberTextMeasurer()
    val tickStyle = tickText(side, c.textMuted)
    Canvas(Modifier.fillMaxSize()) {
        val stroke = (size.minDimension * 0.08f).coerceIn(4.dp.toPx(), 36.dp.toPx())
        val radius = size.minDimension / 2 - stroke
        val center = Offset(size.width / 2, size.height / 2)
        val topLeft = Offset(center.x - radius, center.y - radius)
        val box = Size(radius * 2, radius * 2)
        drawCircle(c.surfaceHigh, radius, center, style = Stroke(stroke))
        if (p.look.redline) drawArc(c.danger.copy(alpha = 0.35f), -90f + 360f * p.redFrom, 360f * (1 - p.redFrom), false, topLeft, box, style = Stroke(stroke))
        val f = p.fraction()
        if (f > 0.002f) drawArc(c.gauge(p.look.color, p.car, f, p.warning), -90f, 360f * f, false, topLeft, box, style = Stroke(stroke, cap = StrokeCap.Round))
        for (i in 0 until p.ticks) {
            val a = Math.toRadians((-90.0 + 360.0 * i / p.ticks))
            fun at(r: Float) = Offset(center.x + r * cos(a).toFloat(), center.y + r * sin(a).toFloat())
            if (p.look.ticks) drawCircle(c.textMuted.copy(alpha = 0.6f), stroke * 0.12f, at(radius - stroke * 1.15f))
            if (p.look.numbers && i % 2 == 0) label(measurer, p.labels(i), at(radius - stroke * 2.3f), tickStyle)
        }
    }
    Readout(number, unit, tag, (side.value * 0.26f).coerceIn(28f, 130f), c.gauge(p.look.color, p.car, 0.5f, p.warning), side > 220.dp)
}

@Composable
private fun BarGauge(number: String, unit: String, tag: String, p: GaugeParts, width: Dp, height: Dp) {
    val c = LocalColors.current
    val measurer = rememberTextMeasurer()
    val tickStyle = tickText(minOf(width, height * 1.6f), c.textMuted)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
        Box(Modifier.fillMaxWidth().height(height * 0.5f)) {
            Segments(p, Modifier.fillMaxSize().padding(bottom = if (p.look.numbers) 22.dp else 0.dp), count = 16, wedge = true)
            if (p.look.numbers) Canvas(Modifier.fillMaxSize()) {
                for (i in 0..p.ticks step 2) {
                    label(measurer, p.labels(i), Offset(size.width * i / p.ticks, size.height - 10.dp.toPx()), tickStyle)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Label(number, Typography.clock.copy(fontSize = minOf(height.value * 0.3f, width.value * 0.25f).coerceIn(32f, 160f).sp), maxLines = 1)
            Column(Modifier.padding(start = 10.dp, bottom = 10.dp)) {
                Label(unit, Typography.small, c.textMuted, maxLines = 1)
                if (tag.isNotEmpty()) Label(tag, Typography.label, c.gauge(p.look.color, p.car, 0.5f, p.warning), maxLines = 1)
            }
        }
    }
}

/**
 * Barra de [count] segmentos que se encienden con el valor. [wedge] = crecen de alto
 * hacia la derecha (luces de cambio). Con "Dinámico" cada segmento toma el color de
 * su lugar; la zona roja se ve tenue aunque no esté encendida.
 */
@Composable
private fun Segments(p: GaugeParts, modifier: Modifier, count: Int, wedge: Boolean) {
    val c = LocalColors.current
    Canvas(modifier) {
        val gap = size.width * 0.012f
        val w = (size.width - gap * (count - 1)) / count
        val f = p.fraction()
        for (k in 0 until count) {
            val pos = (k + 0.5f) / count
            val lit = pos <= f
            val red = p.look.redline && pos >= p.redFrom
            val color = when {
                lit && red -> c.danger
                lit -> c.gauge(p.look.color, p.car, pos, p.warning)
                red -> c.danger.copy(alpha = 0.22f)
                else -> c.surfaceHigh
            }
            val h = if (wedge) size.height * (0.35f + 0.65f * (k + 1f) / count) else size.height
            drawRoundRect(color, Offset(k * (w + gap), size.height - h), Size(w, h), CornerRadius(minOf(w, h) * 0.3f))
        }
    }
}

/**
 * Medidor de una fila: el número grande con su unidad y, abajo, una barra que se
 * llena (de segmentos en los estilos Digital y Barra).
 */
@Composable
private fun CompactGauge(number: String, unit: String, tag: String, p: GaugeParts, height: Dp) {
    val c = LocalColors.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Label(number, Typography.clock.copy(fontSize = (height.value * 0.55f).coerceIn(28f, 56f).sp), maxLines = 1)
            Label(unit, Typography.small, c.textMuted, maxLines = 1, modifier = Modifier.padding(start = 6.dp, bottom = 6.dp).weight(1f))
            if (tag.isNotEmpty()) Label(tag, Typography.label, c.gauge(p.look.color, p.car, 0.5f, p.warning), maxLines = 1, modifier = Modifier.padding(bottom = 6.dp))
        }
        if (p.look.style == GaugeStyle.DIGITAL || p.look.style == GaugeStyle.BAR) {
            Segments(p, Modifier.fillMaxWidth().height(8.dp), count = 20, wedge = false)
        } else Canvas(Modifier.fillMaxWidth().height(6.dp)) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(c.surfaceHigh, cornerRadius = r)
            val f = p.fraction().coerceIn(0f, 1f)
            if (f > 0.002f) drawRoundRect(c.gauge(p.look.color, p.car, f, p.warning), size = Size(size.width * f, size.height), cornerRadius = r)
        }
    }
}
