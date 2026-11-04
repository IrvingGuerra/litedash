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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.isSpecified
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
import kotlin.math.roundToInt
import kotlin.math.sin

/** Del arco del medidor: empieza abajo a la izquierda y da 240° (como un tablero real). */
private const val START_ANGLE = 150f
private const val SWEEP = 240f

/** Grados libres antes del arco de neón, para que su punta redonda no tome el color del final. */
private const val NEON_PAD = 30f

/** Grosor (veces el del arco) de las capas tenues que hacen el brillo del neón, de afuera hacia adentro. */
private val NEON_GLOW = floatArrayOf(3.6f, 3.1f, 2.6f, 2.2f, 1.8f, 1.45f)

/**
 * Cómo se dibuja un medidor. [stored] es como se guarda en el acomodo (no se traduce);
 * [segmented] = en una fila su barra va de segmentos (si no, una barra lisa).
 */
enum class GaugeStyle(val stored: String, val title: Int, val segmented: Boolean = false) {
    /** Arco grueso que se llena (el de siempre). */
    ARC("arc", R.string.gauge_style_arc),
    /** Aguja clásica con marcas y números, como un tablero de verdad. */
    NEEDLE("needle", R.string.gauge_style_needle),
    /** El número enorme y una barra de segmentos abajo (como un HUD). */
    DIGITAL("digital", R.string.gauge_style_digital, segmented = true),
    /** Anillo completo alrededor del número. */
    RING("ring", R.string.gauge_style_ring),
    /** Luces de cambio deportivas: segmentos que crecen hacia la derecha. */
    BAR("bar", R.string.gauge_style_bar, segmented = true),
    /** Arco de rayitas que se encienden en degradado, sin aguja (elegante). */
    GRADIENT("gradient", R.string.gauge_style_gradient, segmented = true),
    /** Arco liso que brilla como luz de neón. */
    NEON("neon", R.string.gauge_style_neon),
    /** Cuadritos a lo largo del arco que se van llenando. */
    BLOCKS("blocks", R.string.gauge_style_blocks, segmented = true),
}

/** De qué color va lo que se llena. DYNAMIC va de verde a ámbar a rojo con el valor. */
enum class GaugeColor(val stored: String, val title: Int) {
    ACCENT("amber", R.string.gauge_color_accent),
    CAR("car", R.string.gauge_color_car),
    DYNAMIC("dynamic", R.string.gauge_color_dynamic),
    GREEN("green", R.string.gauge_color_green),
    BLUE("blue", R.string.gauge_color_blue),
    RED("red", R.string.gauge_color_red),
    WHITE("white", R.string.gauge_color_white),
    PURPLE("purple", R.string.gauge_color_purple),
    CYAN("cyan", R.string.gauge_color_cyan),
}

private val Green = Color(0xFF3DD68C)
private val Blue = Color(0xFF4CA6FF)
private val Purple = listOf(Color(0xFFC05CFF), Color(0xFF7B5CFF), Color(0xFF2F7BFF))
private val Cyan = listOf(Color(0xFF35F0FF), Color(0xFF2F8BFF))

/** Al elegir uno de estos estilos con el ámbar de fábrica, se ve mejor con su degradado. */
fun GaugeStyle.suggestedColor(): GaugeColor? = when (this) {
    GaugeStyle.GRADIENT, GaugeStyle.BLOCKS -> GaugeColor.PURPLE
    GaugeStyle.NEON -> GaugeColor.CYAN
    else -> null
}

/**
 * Cómo se ve un medidor; cada pieza guarda el suyo en su [com.irvingguerra.litedash.feature.dashboard.Tile.data]
 * ("rpm;style=needle;color=dynamic"). Lo que queda como de fábrica no se escribe, así los
 * acomodos de antes (solo la señal) se leen igual.
 */
@Immutable
data class GaugeLook(
    val style: GaugeStyle = GaugeStyle.ARC,
    val color: GaugeColor = GaugeColor.ACCENT,
    /** Máximo del velocímetro en km/h (en mph se ve su pareja, ver [speedMax]); null = 200 km/h o 120 mph. */
    val max: Int? = null,
    val ticks: Boolean = true,
    val numbers: Boolean = false,
    val redline: Boolean = false,
) {
    fun encode(signal: String = ""): String = buildList {
        if (signal.isNotEmpty()) add(signal)
        if (style != GaugeStyle.ARC) add("style=${style.stored}")
        if (color != GaugeColor.ACCENT) add("color=${color.stored}")
        max?.let { add("$MAX_KMH=$it") }
        if (!ticks) add("ticks=0")
        if (numbers) add("numbers=1")
        if (redline) add("redline=1")
    }.joinToString(";")

    companion object {
        fun parse(data: String): GaugeLook {
            val f = data.split(';').mapNotNull { part ->
                part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
            }.toMap()
            return GaugeLook(
                style = GaugeStyle.entries.firstOrNull { it.stored == f["style"] } ?: GaugeStyle.ARC,
                color = GaugeColor.entries.firstOrNull { it.stored == f["color"] } ?: GaugeColor.ACCENT,
                // Hasta la 1.4.61 se guardaba en la unidad que se veía ("max"): ver [migrateSpeedMax].
                max = f[MAX_KMH]?.toIntOrNull() ?: f[LEGACY_MAX]?.toIntOrNull()?.let { legacyMaxKmh(it, miles = it !in SPEED_MAX_KMH) },
                ticks = f["ticks"] != "0",
                numbers = f["numbers"] == "1",
                redline = f["redline"] == "1",
            )
        }
    }
}

/** La señal de un medidor OBD guardada en su pieza (lo primero, sin "="). */
fun signalOf(data: String) = data.substringBefore(';').takeIf { '=' !in it } ?: ""

/** Máximos del velocímetro para elegir, en km/h o mph (en parejas, misma posición); el primero es el de fábrica. */
private val SPEED_MAX_KMH = listOf(200, 160, 240, 320)
private val SPEED_MAX_MPH = listOf(120, 100, 160, 200)

fun speedMaxOptions(miles: Boolean) = if (miles) SPEED_MAX_MPH else SPEED_MAX_KMH

/** Hasta dónde llega el velocímetro en la unidad que se ve: 240 km/h ↔ 160 mph. */
fun GaugeLook.speedMax(miles: Boolean): Int {
    val kmh = max ?: SPEED_MAX_KMH.first()
    if (!miles) return kmh
    return SPEED_MAX_MPH.getOrNull(SPEED_MAX_KMH.indexOf(kmh)) ?: (kmh / KM_PER_MILE).roundToInt()
}

/** El máximo elegido en la unidad que se ve, como se guarda (km/h; null = el de fábrica). */
fun speedMaxToStore(shown: Int, miles: Boolean): Int? {
    val kmh = if (miles) SPEED_MAX_KMH.getOrNull(SPEED_MAX_MPH.indexOf(shown)) ?: (shown * KM_PER_MILE).roundToInt() else shown
    return kmh.takeIf { it != SPEED_MAX_KMH.first() }
}

/** Un máximo viejo (en la unidad que se veía con [miles]) a km/h. */
private fun legacyMaxKmh(value: Int, miles: Boolean): Int =
    if (miles) SPEED_MAX_KMH.getOrNull(SPEED_MAX_MPH.indexOf(value)) ?: (value * KM_PER_MILE).roundToInt() else value

/**
 * Los datos de un velocímetro guardado hasta la 1.4.61 (máximo en la unidad que se veía) con
 * el máximo ya en km/h, según las unidades de ahora ([miles]); null si no hay nada que cambiar.
 */
fun migrateSpeedMax(data: String, miles: Boolean): String? {
    val parts = data.split(';')
    val old = parts.firstOrNull { it.startsWith("$LEGACY_MAX=") } ?: return null
    val kmh = old.substringAfter('=').toIntOrNull()?.let { legacyMaxKmh(it, miles) }
    val rest = parts.filter { it != old && !it.startsWith("$MAX_KMH=") }
    val max = kmh?.takeIf { it != SPEED_MAX_KMH.first() }?.let { "$MAX_KMH=$it" }
    return (rest + listOfNotNull(max)).filter { it.isNotEmpty() }.joinToString(";")
}

private const val MAX_KMH = "maxkmh"
private const val LEGACY_MAX = "max"

/** Cómo va la velocidad en la carretera: como velocímetro, solo el número (de fábrica) u oculta. */
enum class RoadSpeed(val stored: String, val title: Int) {
    TEXT("text", R.string.road_speed_text),
    GAUGE("gauge", R.string.road_speed_gauge),
    HIDDEN("hidden", R.string.road_speed_hidden),
}

/**
 * Cómo se ve la carretera; se guarda en su [com.irvingguerra.litedash.feature.dashboard.Tile.data]
 * como un velocímetro ([gauge]) más "speed=…" ([speed]). Lo de fábrica no se escribe.
 */
@Immutable
data class RoadLook(val speed: RoadSpeed = RoadSpeed.TEXT, val gauge: GaugeLook = GaugeLook()) {
    fun encode(): String = (listOf(gauge.encode()) + listOfNotNull(speed.takeIf { it != RoadSpeed.TEXT }?.let { "$SPEED=${it.stored}" }))
        .filter { it.isNotEmpty() }
        .joinToString(";")

    companion object {
        private const val SPEED = "speed"

        fun parse(data: String) = RoadLook(
            speed = RoadSpeed.entries.firstOrNull { "$SPEED=${it.stored}" in data.split(';') } ?: RoadSpeed.TEXT,
            gauge = GaugeLook.parse(data),
        )
    }
}

/**
 * Los colores de lo que se llena, de principio a fin del medidor, repartidos parejo.
 * Uno solo = color fijo; los estilos con degradado oscurecen un poco el inicio para que
 * se note el avance. Es la única fuente del color de cada valor (ver [gauge]).
 */
fun Colors.ramp(color: GaugeColor, car: Color): List<Color> = when (color) {
    GaugeColor.ACCENT -> listOf(accent)
    GaugeColor.CAR -> listOf(if (car.isSpecified) car else accent)
    GaugeColor.GREEN -> listOf(Green)
    GaugeColor.BLUE -> listOf(Blue)
    GaugeColor.RED -> listOf(danger)
    GaugeColor.WHITE -> listOf(text)
    // Verde → ámbar hasta el 60 % y ámbar → rojo en el resto. Con los colores repartidos
    // parejo (cada 20 %), los intermedios sobre la misma recta dan justo ese quiebre.
    GaugeColor.DYNAMIC -> listOf(Green, lerp(Green, accent, 1f / 3f), lerp(Green, accent, 2f / 3f), accent, lerp(accent, danger, 0.5f), danger)
    GaugeColor.PURPLE -> Purple
    GaugeColor.CYAN -> Cyan
}

/** El color de [ramp] en [f] (0–1). */
private fun colorAt(ramp: List<Color>, f: Float): Color {
    if (ramp.size == 1) return ramp[0]
    val x = f.coerceIn(0f, 1f) * (ramp.size - 1)
    val i = x.toInt().coerceAtMost(ramp.size - 2)
    return lerp(ramp[i], ramp[i + 1], x - i)
}

/** El degradado de un medidor: con un solo color, del color apagado al color. */
private fun Colors.shades(p: GaugeParts): List<Color> = when {
    p.warning -> listOf(danger, danger)
    else -> ramp(p.look.color, p.car).let { if (it.size == 1) listOf(lerp(it[0], background, 0.45f), it[0]) else it }
}

/** El color de lo que se llena para el valor [f] (0–1); lo que preocupa, siempre rojo. */
private fun Colors.gauge(color: GaugeColor, car: Color, f: Float, warning: Boolean): Color =
    if (warning) danger else colorAt(ramp(color, car), f)

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

/** El color de la etiqueta (OBD, GPS…) junto al número: el de la mitad del medidor, igual en todos los estilos. */
private fun GaugeParts.readoutColor(c: Colors) = c.gauge(look.color, car, 0.5f, warning)

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
                GaugeStyle.GRADIENT -> GradientGauge(number, unit, tag, parts, side = minOf(maxWidth, maxHeight * 1.15f))
                GaugeStyle.NEON -> NeonGauge(number, unit, tag, parts, side = minOf(maxWidth, maxHeight * 1.15f))
                GaugeStyle.BLOCKS -> BlocksGauge(number, unit, tag, parts, side = minOf(maxWidth, maxHeight * 1.15f))
            }
        }
    }
}

/** El círculo del arco (de la aguja también): centrado un poco abajo, del tamaño que quepa. */
private class Dial(size: Size, stroke: Float, margin: Float = 0f) {
    val radius = minOf(size.width, size.height * 1.15f) / 2 - stroke - margin
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
    Readout(number, unit, tag, (side.value * 0.3f).coerceIn(28f, 130f), p.readoutColor(c), side > 200.dp)
}

@Composable
private fun NeedleGauge(number: String, unit: String, tag: String, p: GaugeParts, side: Dp) {
    val c = LocalColors.current
    val measurer = rememberTextMeasurer()
    val tickStyle = tickText(side, c.text.copy(alpha = 0.85f))
    Canvas(Modifier.fillMaxSize()) {
        val stroke = (size.minDimension * 0.05f).coerceIn(3.dp.toPx(), 22.dp.toPx())
        // Los números van por fuera del arco: así la aguja nunca pasa encima de ellos.
        val margin = if (p.look.numbers) tickStyle.fontSize.toPx() * 1.7f else 0f
        val d = Dial(size, stroke, margin)
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
            if (p.look.numbers && major && (i / 2) % 2 == 0) label(measurer, p.labels(i / 2), d.at(angle, d.radius + stroke * 0.4f + margin * 0.55f), tickStyle)
        }
        // La aguja con su cola y el centro.
        val angle = START_ANGLE + SWEEP * f
        drawLine(color, d.at(angle + 180f, stroke * 1.4f), d.at(angle, d.radius - stroke * 1.3f), strokeWidth = stroke * 0.42f, cap = StrokeCap.Round)
        drawCircle(color, stroke * 1.05f, d.center)
        drawCircle(c.background, stroke * 0.45f, d.center)
    }
    // La lectura abajo del centro, donde la aguja no pasa.
    Readout(number, unit, tag, (side.value * 0.13f).coerceIn(22f, 64f), p.readoutColor(c), side > 260.dp, Modifier.offset(y = side * 0.24f))
}

@Composable
private fun DigitalGauge(number: String, unit: String, tag: String, p: GaugeParts, width: Dp, height: Dp) {
    val c = LocalColors.current
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Readout(number, unit, tag, minOf(height.value * 0.45f, width.value * 0.3f).coerceIn(36f, 200f), p.readoutColor(c), height > 220.dp)
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
    Readout(number, unit, tag, (side.value * 0.26f).coerceIn(28f, 130f), p.readoutColor(c), side > 220.dp)
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
                if (tag.isNotEmpty()) Label(tag, Typography.label, p.readoutColor(c), maxLines = 1)
            }
        }
    }
}

/** El número y su unidad al centro del arco, para los estilos sin aguja. */
@Composable
private fun CenterReadout(number: String, unit: String, tag: String, p: GaugeParts, side: Dp) {
    val c = LocalColors.current
    Readout(number, unit, tag, (side.value * 0.26f).coerceIn(28f, 120f), p.readoutColor(c), side > 220.dp, Modifier.offset(y = side * 0.04f))
}

/** Los números del principio y del final, abajo de las puntas del arco. */
private fun DrawScope.ends(measurer: TextMeasurer, p: GaugeParts, d: Dial, r: Float, style: TextStyle) {
    if (!p.look.numbers) return
    val dy = style.fontSize.toPx() * 1.2f
    label(measurer, p.labels(0), d.at(START_ANGLE, r) + Offset(0f, dy), style)
    label(measurer, p.labels(p.ticks), d.at(START_ANGLE + SWEEP, r) + Offset(0f, dy), style)
}

@Composable
private fun GradientGauge(number: String, unit: String, tag: String, p: GaugeParts, side: Dp) {
    val c = LocalColors.current
    val measurer = rememberTextMeasurer()
    val endStyle = tickText(side, c.text.copy(alpha = 0.85f))
    Canvas(Modifier.fillMaxSize()) {
        val band = (size.minDimension * 0.14f).coerceIn(10.dp.toPx(), 64.dp.toPx())
        val d = Dial(size, band * 0.25f, if (p.look.numbers) endStyle.fontSize.toPx() * 1.4f else 0f)
        val shades = c.shades(p)
        val f = p.fraction()
        val count = 44
        val outer = d.radius
        val inner = outer - band
        // Resplandor suave detrás de lo encendido.
        if (f > 0.002f) drawArc(colorAt(shades, f).copy(alpha = 0.10f), START_ANGLE, SWEEP * f, false,
            Offset(d.center.x - outer + band / 2, d.center.y - outer + band / 2), Size((outer - band / 2) * 2, (outer - band / 2) * 2),
            style = Stroke(band * 1.6f))
        for (k in 0..count) {
            val pos = k.toFloat() / count
            val angle = START_ANGLE + SWEEP * pos
            val lit = pos <= f && f > 0.002f
            val red = p.look.redline && pos >= p.redFrom
            val color = when {
                lit && red -> c.danger
                lit -> colorAt(shades, pos)
                red -> c.danger.copy(alpha = 0.25f)
                else -> colorAt(shades, pos).copy(alpha = 0.13f)
            }
            val long = p.look.ticks && k % 4 == 0
            drawLine(color, d.at(angle, outer), d.at(angle, inner - if (long) band * 0.18f else 0f), strokeWidth = band * 0.11f, cap = StrokeCap.Round)
        }
        // Un filo claro por fuera, como en los tableros nuevos.
        val rim = Stroke((band * 0.06f).coerceAtLeast(1.5f), cap = StrokeCap.Round)
        val rimBox = Size((outer + band * 0.16f) * 2, (outer + band * 0.16f) * 2)
        val rimAt = Offset(d.center.x - outer - band * 0.16f, d.center.y - outer - band * 0.16f)
        drawArc(c.text.copy(alpha = 0.12f), START_ANGLE, SWEEP, false, rimAt, rimBox, style = rim)
        if (f > 0.002f) drawArc(c.text.copy(alpha = 0.85f), START_ANGLE, SWEEP * f, false, rimAt, rimBox, style = rim)
        ends(measurer, p, d, inner + band / 2, endStyle)
    }
    CenterReadout(number, unit, tag, p, side)
}

@Composable
private fun NeonGauge(number: String, unit: String, tag: String, p: GaugeParts, side: Dp) {
    val c = LocalColors.current
    val measurer = rememberTextMeasurer()
    val endStyle = tickText(side, c.textMuted)
    Canvas(Modifier.fillMaxSize()) {
        val stroke = (size.minDimension * 0.05f).coerceIn(4.dp.toPx(), 24.dp.toPx())
        val d = Dial(size, stroke * 2.2f, if (p.look.numbers) endStyle.fontSize.toPx() * 1.4f else 0f)
        val shades = c.shades(p)
        val f = p.fraction()
        drawArc(c.surfaceHigh, START_ANGLE, SWEEP, false, d.topLeft, d.box, style = Stroke(stroke, cap = StrokeCap.Round))
        if (p.look.redline) drawArc(c.danger.copy(alpha = 0.3f), START_ANGLE + SWEEP * p.redFrom, SWEEP * (1 - p.redFrom), false, d.topLeft, d.box, style = Stroke(stroke))
        if (p.look.ticks) for (i in 0..p.ticks) {
            val angle = START_ANGLE + SWEEP * i / p.ticks
            drawCircle(c.textMuted.copy(alpha = 0.5f), stroke * 0.14f, d.at(angle, d.radius - stroke * 1.6f))
        }
        if (f > 0.002f) {
            // El degradado sigue al arco: se gira el lienzo para que empiece en NEON_PAD°, y lo que
            // queda antes (la punta redonda del inicio) toma el primer color en vez del último.
            val brush = Brush.sweepGradient(
                *(shades.mapIndexed { i, col -> (NEON_PAD + SWEEP * i / (shades.size - 1).coerceAtLeast(1)) / 360f to col } +
                    (1f to shades.first())).toTypedArray(),
                center = d.center,
            )
            rotate(START_ANGLE - NEON_PAD, d.center) {
                // Capas anchas y tenues debajo = el brillo del neón (sin desenfoque, que es caro).
                for (w in NEON_GLOW) {
                    drawArc(brush, NEON_PAD, SWEEP * f, false, d.topLeft, d.box, alpha = 0.055f, style = Stroke(stroke * w, cap = StrokeCap.Round))
                }
                drawArc(brush, NEON_PAD, SWEEP * f, false, d.topLeft, d.box, style = Stroke(stroke, cap = StrokeCap.Round))
                drawArc(Color.White.copy(alpha = 0.55f), NEON_PAD, SWEEP * f, false, d.topLeft, d.box, style = Stroke(stroke * 0.25f, cap = StrokeCap.Round))
            }
        }
        ends(measurer, p, d, d.radius, endStyle)
    }
    CenterReadout(number, unit, tag, p, side)
}

@Composable
private fun BlocksGauge(number: String, unit: String, tag: String, p: GaugeParts, side: Dp) {
    val c = LocalColors.current
    val measurer = rememberTextMeasurer()
    val endStyle = tickText(side, c.textMuted)
    Canvas(Modifier.fillMaxSize()) {
        val cell = (size.minDimension * 0.085f).coerceIn(6.dp.toPx(), 40.dp.toPx())
        val d = Dial(size, cell * 0.6f, if (p.look.numbers) endStyle.fontSize.toPx() * 1.4f else 0f)
        val shades = c.shades(p)
        val f = p.fraction()
        val r = d.radius - cell / 2
        // Cuántos cuadros caben a lo largo del arco dejando un hueco entre cada uno.
        val count = ((Math.toRadians(SWEEP.toDouble()) * r) / (cell * 1.3f)).toInt().coerceIn(8, 28)
        for (k in 0 until count) {
            val pos = (k + 0.5f) / count
            val angle = START_ANGLE + SWEEP * pos
            val lit = pos <= f && f > 0.002f
            val red = p.look.redline && pos >= p.redFrom
            val color = when {
                lit && red -> c.danger
                lit -> colorAt(shades, pos)
                red -> c.danger.copy(alpha = 0.22f)
                else -> c.surfaceHigh
            }
            val at = d.at(angle, r)
            rotate(angle + 90f, at) {
                if (lit) drawRoundRect(color.copy(alpha = 0.18f), at - Offset(cell * 0.75f, cell * 0.75f), Size(cell * 1.5f, cell * 1.5f), CornerRadius(cell * 0.4f))
                drawRoundRect(color, at - Offset(cell / 2, cell / 2), Size(cell, cell), CornerRadius(cell * 0.24f))
            }
        }
        ends(measurer, p, d, r, endStyle)
    }
    CenterReadout(number, unit, tag, p, side)
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
            if (tag.isNotEmpty()) Label(tag, Typography.label, p.readoutColor(c), maxLines = 1, modifier = Modifier.padding(bottom = 6.dp))
        }
        if (p.look.style.segmented) {
            Segments(p, Modifier.fillMaxWidth().height(8.dp), count = 20, wedge = false)
        } else Canvas(Modifier.fillMaxWidth().height(6.dp)) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(c.surfaceHigh, cornerRadius = r)
            val f = p.fraction().coerceIn(0f, 1f)
            if (f > 0.002f) drawRoundRect(c.gauge(p.look.color, p.car, f, p.warning), size = Size(size.width * f, size.height), cornerRadius = r)
        }
    }
}
