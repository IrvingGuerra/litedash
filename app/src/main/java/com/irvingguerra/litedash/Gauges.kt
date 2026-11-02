package com.irvingguerra.litedash

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.core.vehicle.Signal
import com.irvingguerra.litedash.core.vehicle.Source
import com.irvingguerra.litedash.core.vehicle.watch
import com.irvingguerra.litedash.feature.obd.CHARGING_MAX_V
import com.irvingguerra.litedash.feature.obd.HOT_COOLANT_C
import com.irvingguerra.litedash.feature.obd.ObdState
import com.irvingguerra.litedash.feature.obd.RESTING_MIN_V
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/** Del arco del medidor: empieza abajo a la izquierda y da 240° (como un tablero real). */
private const val START_ANGLE = 150f
private const val SWEEP = 240f

/**
 * Velocímetro de arco: número grande y el arco que se llena. Lee la velocidad del
 * auto (OBD) si hay adaptador conectado; si no, del GPS (pide permiso la primera vez).
 */
@Composable
fun SpeedometerTile(d: Dependencies, language: String) {
    // Con un adaptador ya guardado, se reconecta solo (sin abrir el Taller).
    LaunchedEffect(Unit) { if (d.hasObdAdapter) d.obd.connect() }
    val obdOn = d.obdIfCreated()?.state == ObdState.ON
    var gps by remember { mutableStateOf(d.trip.hasPermission()) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { gps = it }
    if (!obdOn && !gps) {
        return ShortcutTile(Icons.speed, stringResource(R.string.tile_speedometer), stringResource(R.string.tap_to_enable)) {
            ask.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    // Sin OBD, el GPS publica la velocidad en el bus mientras la pieza se ve.
    if (!obdOn) KeepTripRunning(d.trip)
    val kmh = d.bus.watch(Signal.SPEED)
    val imperial = imperialUnits(language)
    val speed = kmh?.let { if (imperial) it / 1.609f else it }
    val max = if (imperial) 120f else 200f
    val source = when (d.bus.source(Signal.SPEED)) {
        Source.OBD -> "OBD"
        Source.GPS -> "GPS"
        Source.CAN -> "CAN"
        null -> ""
    }
    GaugeCard(
        number = speed?.let { String.format(LocalConfiguration.current.locales[0], "%.0f", it) } ?: "—",
        unit = stringResource(if (imperial) R.string.unit_mph else R.string.unit_kmh),
        tag = source,
        fraction = (speed ?: 0f) / max,
        // Marcas cada 20 km/h (10 mph).
        ticks = (max / if (imperial) 10f else 20f).toInt(),
    )
}

/**
 * Tarjeta de medidor: en una fila (la de abajo en 7") el número con una barra; con
 * más alto, el arco de tablero. [fraction] (0–1) se anima y se lee solo al dibujar:
 * la pieza no se recompone en cada cuadro. [warning] pinta de rojo lo que preocupa.
 */
@Composable
fun GaugeCard(number: String, unit: String, tag: String, fraction: Float, ticks: Int, warning: Boolean = false, onClick: (() -> Unit)? = null) {
    val c = LocalColors.current
    val color = if (warning) c.danger else c.accent
    val shown = remember { Animatable(0f) }
    LaunchedEffect(fraction) { shown.animateTo(fraction.coerceIn(0f, 1f), tween(250)) }
    Card(Modifier.fillMaxSize(), onClick = onClick) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
            if (maxHeight < 130.dp) CompactGauge(number, unit, tag, { shown.value }, maxHeight, color)
            else ArcGauge(number, unit, tag, { shown.value }, ticks, color, side = minOf(maxWidth, maxHeight * 1.15f))
        }
    }
}

@Composable
private fun ArcGauge(number: String, unit: String, tag: String, fraction: () -> Float, ticks: Int, color: Color, side: Dp) {
    val c = LocalColors.current
    Canvas(Modifier.fillMaxSize()) {
        val stroke = (size.minDimension * 0.07f).coerceIn(3.dp.toPx(), 12.dp.toPx())
        val radius = minOf(size.width, size.height * 1.15f) / 2 - stroke
        val center = Offset(size.width / 2, size.height / 2 + radius * 0.12f)
        val topLeft = Offset(center.x - radius, center.y - radius)
        val arc = Size(radius * 2, radius * 2)
        drawArc(c.surfaceHigh, START_ANGLE, SWEEP, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
        val f = fraction()
        if (f > 0.002f) drawArc(color, START_ANGLE, SWEEP * f, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
        // Marcas discretas por dentro del arco (una larga y una corta).
        for (i in 0..ticks) {
            val a = Math.toRadians((START_ANGLE + SWEEP * i / ticks).toDouble())
            val outer = radius - stroke * 1.2f
            val inner = outer - stroke * (if (i % 2 == 0) 0.9f else 0.5f)
            drawLine(
                c.textMuted.copy(alpha = 0.6f),
                Offset(center.x + outer * cos(a).toFloat(), center.y + outer * sin(a).toFloat()),
                Offset(center.x + inner * cos(a).toFloat(), center.y + inner * sin(a).toFloat()),
                strokeWidth = stroke * 0.18f,
                cap = StrokeCap.Round,
            )
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Label(number, Typography.clock.copy(fontSize = (side.value * 0.3f).coerceIn(28f, 110f).sp), maxLines = 1)
        Label(unit, Typography.small, c.textMuted, maxLines = 1)
        if (side > 200.dp && tag.isNotEmpty()) Box(Modifier.padding(top = 6.dp)) { Label(tag, Typography.label, color, maxLines = 1) }
    }
}

/**
 * Medidor de una fila: el número grande con su unidad y, abajo, una barra que se
 * llena ([fraction] se lee al dibujar).
 */
@Composable
private fun CompactGauge(number: String, unit: String, tag: String, fraction: () -> Float, height: Dp, color: Color) {
    val c = LocalColors.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Label(number, Typography.clock.copy(fontSize = (height.value * 0.55f).coerceIn(28f, 56f).sp), maxLines = 1)
            Label(unit, Typography.small, c.textMuted, maxLines = 1, modifier = Modifier.padding(start = 6.dp, bottom = 6.dp).weight(1f))
            if (tag.isNotEmpty()) Label(tag, Typography.label, color, maxLines = 1, modifier = Modifier.padding(bottom = 6.dp))
        }
        Canvas(Modifier.fillMaxWidth().height(6.dp)) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(c.surfaceHigh, cornerRadius = r)
            val f = fraction().coerceIn(0f, 1f)
            if (f > 0.002f) drawRoundRect(color, size = Size(size.width * f, size.height), cornerRadius = r)
        }
    }
}

/**
 * Un dato del auto que se puede poner como medidor: cómo se muestra (unidad y
 * decimales, en km o millas), su rango para el arco y cuándo preocupa.
 */
class GaugeSpec(
    val signal: String,
    val name: Int,
    /** Cortito, en mayúsculas en la tarjeta: dice qué número es (null si la unidad ya lo dice). */
    val tag: Int?,
    val icon: ImageVector,
    val ticks: Int,
    /** De la unidad del bus (km/h, °C, kPa…) a la que se muestra. */
    val shown: (value: Float, imperial: Boolean) -> Float,
    val unit: (imperial: Boolean) -> Int,
    val decimals: Int,
    /** Qué tanto del arco se llena, con el valor del bus. */
    val fraction: (Float) -> Float,
    val warning: (Float) -> Boolean = { false },
)

private fun range(min: Float, max: Float) = { v: Float -> (v - min) / (max - min) }
private fun fahrenheit(c: Float, imperial: Boolean) = if (imperial) c * 9 / 5 + 32 else c

val GaugeSpecs = listOf(
    GaugeSpec(
        Signal.RPM, R.string.gauge_rpm, null, Icons.tachometer, ticks = 8,
        shown = { v, _ -> v }, unit = { R.string.unit_rpm }, decimals = 0,
        fraction = range(0f, 8000f), warning = { it >= 6500f },
    ),
    GaugeSpec(
        Signal.COOLANT, R.string.gauge_coolant, R.string.gauge_coolant_tag, Icons.thermometer, ticks = 9,
        shown = ::fahrenheit, unit = { if (it) R.string.unit_f else R.string.unit_c }, decimals = 0,
        fraction = range(40f, 130f), warning = { it >= HOT_COOLANT_C },
    ),
    GaugeSpec(
        Signal.FUEL, R.string.gauge_fuel, R.string.gauge_fuel_tag, Icons.fuel, ticks = 4,
        shown = { v, _ -> v }, unit = { R.string.unit_percent }, decimals = 0,
        fraction = range(0f, 100f), warning = { it <= 12f },
    ),
    GaugeSpec(
        Signal.VOLTAGE, R.string.gauge_voltage, R.string.gauge_voltage_tag, Icons.battery, ticks = 6,
        shown = { v, _ -> v }, unit = { R.string.unit_volts }, decimals = 1,
        fraction = range(10f, 16f), warning = { it < RESTING_MIN_V || it > CHARGING_MAX_V },
    ),
    GaugeSpec(
        Signal.CONSUMPTION, R.string.gauge_consumption, R.string.gauge_consumption_tag, Icons.drop, ticks = 6,
        // km/l a millas por galón (EE. UU.).
        shown = { v, imperial -> if (imperial) v * 2.352f else v }, unit = { if (it) R.string.unit_mpg else R.string.unit_kml }, decimals = 1,
        fraction = range(0f, 30f),
    ),
    GaugeSpec(
        Signal.BOOST, R.string.gauge_boost, R.string.gauge_boost_tag, Icons.turbo, ticks = 8,
        // El turbo se habla en psi (también en México).
        shown = { v, _ -> v * 0.145f }, unit = { R.string.unit_psi }, decimals = 1,
        fraction = range(-100f, 170f),
    ),
)

fun gaugeOf(signal: String) = GaugeSpecs.firstOrNull { it.signal == signal }

/**
 * Pieza "Medidor": un dato del auto por OBD (el que guarda [signal]). Vacía invita a
 * elegir; sin adaptador lleva al Taller; si el auto no da ese dato, lo dice.
 */
@Composable
fun GaugeTile(d: Dependencies, signal: String, language: String, onPick: () -> Unit, onWorkshop: () -> Unit) {
    val spec = gaugeOf(signal)
        ?: return ShortcutTile(Icons.plus, stringResource(R.string.tile_gauge), stringResource(R.string.gauge_pick), onPick)
    // Con un adaptador ya guardado, se reconecta solo (sin abrir el Taller).
    LaunchedEffect(Unit) { if (d.hasObdAdapter) d.obd.connect() }
    val obd = d.obdIfCreated()
    val name = stringResource(spec.name)
    if (obd?.state != ObdState.ON) {
        val detail = if (obd?.state == ObdState.CONNECTING) R.string.obd_connecting_short else R.string.gauge_connect
        return ShortcutTile(spec.icon, name, stringResource(detail), onWorkshop)
    }
    if (!obd.canRead(spec.signal)) return ShortcutTile(spec.icon, name, stringResource(R.string.gauge_unsupported), onPick)
    val value = d.bus.watch(spec.signal)
    val imperial = imperialUnits(language)
    GaugeCard(
        number = value?.let { gaugeText(spec, it, imperial, LocalConfiguration.current.locales[0]) } ?: "—",
        unit = stringResource(spec.unit(imperial)),
        tag = spec.tag?.let { stringResource(it) } ?: "",
        fraction = value?.let(spec.fraction) ?: 0f,
        ticks = spec.ticks,
        warning = value != null && spec.warning(value),
        // Un toque cambia qué dato muestra.
        onClick = onPick,
    )
}

/** El número como se lee en la tarjeta (sin unidad). */
fun gaugeText(spec: GaugeSpec, value: Float, imperial: Boolean, locale: Locale): String =
    String.format(locale, "%.${spec.decimals}f", spec.shown(value, imperial))
