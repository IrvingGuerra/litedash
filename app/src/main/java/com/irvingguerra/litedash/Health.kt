package com.irvingguerra.litedash

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.core.vehicle.Signal
import com.irvingguerra.litedash.core.vehicle.watch
import com.irvingguerra.litedash.feature.car.CAR_TOP_ASPECT
import com.irvingguerra.litedash.feature.car.CarTop
import com.irvingguerra.litedash.feature.obd.EngineReport
import com.irvingguerra.litedash.feature.obd.HealthLevel
import com.irvingguerra.litedash.feature.obd.ObdState
import com.irvingguerra.litedash.feature.obd.SystemHealth
import com.irvingguerra.litedash.feature.obd.Zone
import com.irvingguerra.litedash.feature.obd.engineAlert
import com.irvingguerra.litedash.feature.obd.healthOf
import com.irvingguerra.litedash.feature.obd.issues

/** Las partes que siempre se listan; ruedas y carrocería solo cuando algo las señala. */
private val MAIN_ZONES = listOf(Zone.ENGINE, Zone.INTAKE, Zone.COOLING, Zone.BATTERY, Zone.TRANSMISSION, Zone.EXHAUST)

/**
 * La pantalla Salud del Taller: tu auto desde arriba con un punto sobre cada parte que pide
 * atención (ámbar) o tiene falla (rojo); al lado, la frase de estado y una tarjeta por
 * sistema con su palabra de estado o su dato en vivo. Sin adaptador ([connected] = false)
 * el auto se ve apagado y las tarjetas en gris: la misma pantalla, nunca una vacía.
 * [onCodes] lleva a la pestaña de códigos al tocar una parte con códigos.
 */
@Composable
fun HealthView(d: Dependencies, report: EngineReport?, reading: Boolean, onRead: () -> Unit, connected: Boolean, onCodes: () -> Unit = {}, onTires: () -> Unit = {}) {
    val health = rememberHealth(d, report, connected)
    Card(Modifier.fillMaxWidth().widthIn(max = 860.dp)) {
        BoxWithConstraints(Modifier.padding(22.dp)) {
            val wide = maxWidth > 620.dp
            // El auto se mide por su alto: en una pantalla acostada no debe pasar de lo que se ve.
            val carHeight = if (wide) 330.dp else 270.dp
            val summary = @Composable { Summary(d, health, report, reading, connected) }
            val cards = @Composable { SystemTiles(d, health, report, connected, onCodes, onTires) }
            if (wide) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    CarWithMarks(d, health, connected, Modifier.padding(horizontal = 12.dp).height(carHeight))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        summary()
                        cards()
                        if (connected) Button(stringResource(R.string.obd_read_again), onRead)
                    }
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CarWithMarks(d, health, connected, Modifier.height(carHeight))
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        summary()
                        cards()
                        if (connected) Button(stringResource(R.string.obd_read_again), onRead)
                    }
                }
            }
        }
    }
}

/** La salud con lo que se sabe ahora: el reporte, la alerta en vivo y los datos del bus (solo conectado). */
@Composable
private fun rememberHealth(d: Dependencies, report: EngineReport?, connected: Boolean): List<SystemHealth> {
    if (!connected) return remember { healthOf(null, null, null, null, null) }
    val coolant = d.bus.watch(Signal.COOLANT)
    val voltage = d.bus.watch(Signal.VOLTAGE)
    val rpm = d.bus.watch(Signal.RPM)
    val alert = engineAlert(coolant, voltage, rpm)
    return remember(report, alert, coolant, voltage, rpm) { healthOf(report, alert, coolant, voltage, rpm) }
}

/**
 * Arriba de las tarjetas: "Salud del auto" en chico, la frase grande de estado (Todo bien,
 * 2 avisos, Conecta tu adaptador…) y, conectado, el adaptador y el VIN.
 */
@Composable
private fun Summary(d: Dependencies, health: List<SystemHealth>, report: EngineReport?, reading: Boolean, connected: Boolean) {
    val c = LocalColors.current
    val issues = health.issues()
    val known = health.any { it.level != HealthLevel.UNKNOWN }
    val fault = health.any { it.level == HealthLevel.FAULT }
    val (text, color) = when {
        !connected -> stringResource(R.string.health_connect) to c.textMuted
        reading && !known -> stringResource(R.string.obd_reading) to c.textMuted
        issues == 0 && known -> stringResource(R.string.health_all_good) to Good
        issues > 0 -> pluralStringResource(R.plurals.health_issues, issues, issues) to (if (fault) c.danger else c.accent)
        else -> stringResource(R.string.health_unknown) to c.textMuted
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Label(stringResource(R.string.health_title).uppercase(), Typography.label, c.textMuted, maxLines = 1)
        Label(text, Typography.title.copy(fontSize = if (connected) 30.sp else 20.sp), color, maxLines = 2)
        if (connected) {
            val obd = d.obd
            val line = listOfNotNull(obd.target?.name, obd.adapter, report?.vin?.let { "VIN $it" }).joinToString(" · ")
            if (line.isNotEmpty()) Label(line, Typography.small, c.textMuted, maxLines = 1)
        }
    }
}

/** Una tarjeta por sistema: las seis principales siempre; ruedas y carrocería solo con algo que decir; y llantas si hay. */
@Composable
private fun SystemTiles(d: Dependencies, health: List<SystemHealth>, report: EngineReport?, connected: Boolean, onCodes: () -> Unit, onTires: () -> Unit) {
    val shown: List<SystemHealth?> = health.filter { it.zone in MAIN_ZONES || it.level == HealthLevel.WARN || it.level == HealthLevel.FAULT }
    // Con llantas configuradas, su tarjeta al final (null = la de llantas).
    val items = if (d.hasTpmsSource) shown + null else shown
    TileGrid(items, minWidth = 150.dp) { h, modifier ->
        if (h == null) TiresHealthTile(d, onTires, modifier) else SystemTile(d, h, report, connected, onCodes, modifier)
    }
}

/** La señal en vivo que define a cada parte (la de su tarjeta y su línea). */
private fun signalOf(zone: Zone) = when (zone) {
    Zone.COOLING -> Signal.COOLANT
    Zone.BATTERY -> Signal.VOLTAGE
    Zone.ENGINE -> Signal.RPM
    else -> null
}

/**
 * Una parte del auto: con falla o atención, la palabra grande en su color y los códigos
 * abajo; bien, su dato en vivo (o "Bien") y la línea de los últimos 90 s.
 */
@Composable
private fun SystemTile(d: Dependencies, h: SystemHealth, report: EngineReport?, connected: Boolean, onCodes: () -> Unit, modifier: Modifier) {
    val c = LocalColors.current
    val color = when (h.level) {
        HealthLevel.GOOD -> Good
        HealthLevel.WARN -> c.accent
        HealthLevel.FAULT -> c.danger
        HealthLevel.UNKNOWN -> c.textMuted
    }
    val signal = signalOf(h.zone)
    val locale = currentLocale()
    val units = d.prefs.units
    val value = h.value
    val word = when (h.level) {
        HealthLevel.FAULT -> stringResource(R.string.health_fault)
        HealthLevel.WARN -> stringResource(R.string.health_warn)
        HealthLevel.UNKNOWN -> stringResource(R.string.health_unknown)
        HealthLevel.GOOD -> if (value != null && signal != null) sensorText(signal, value, units, locale) else stringResource(R.string.health_good)
    }
    val readiness = report?.readiness
    val detail = when {
        h.codes.isNotEmpty() -> h.codes.joinToString(" · ")
        h.pendingCodes.isNotEmpty() -> h.pendingCodes.joinToString(" · ") + " · " + stringResource(R.string.health_pending)
        h.level == HealthLevel.WARN && h.alert == null -> stringResource(R.string.health_mil)
        h.alert != null && value != null && signal != null -> sensorText(signal, value, units, locale)
        h.zone == Zone.EXHAUST && readiness != null ->
            stringResource(R.string.health_monitors, readiness.monitors.count { it.complete }, readiness.monitors.size)
        else -> null
    }
    val series = if (connected && signal != null) rememberSeries(d, signal) else null
    val hasCodes = h.codes.isNotEmpty() || h.pendingCodes.isNotEmpty()
    StatTile(
        name = stringResource(zoneName(h.zone)),
        value = word,
        modifier = modifier,
        valueColor = when (h.level) {
            HealthLevel.GOOD -> if (value != null) c.text else Good
            HealthLevel.UNKNOWN -> c.textMuted
            else -> color
        },
        dot = color,
        detail = detail,
        detailColor = if (h.level == HealthLevel.WARN || h.level == HealthLevel.FAULT) color else c.textMuted,
        series = series,
        seriesColor = if (h.level == HealthLevel.GOOD) c.textMuted else color,
        onClick = if (hasCodes) onCodes else null,
    )
}

/**
 * Tu auto desde arriba (su render, o la silueta si el paquete no la trae) con los puntos de atención. El punto late despacio solo si hay una
 * falla; sin fallas nada se mueve.
 */
@Composable
private fun CarWithMarks(d: Dependencies, health: List<SystemHealth>, connected: Boolean, modifier: Modifier) {
    val c = LocalColors.current
    val marked = health.filter { it.level == HealthLevel.WARN || it.level == HealthLevel.FAULT }
    val anyFault = marked.any { it.level == HealthLevel.FAULT }
    // El latido se lee solo al dibujar: con una falla se repinta el Canvas, no se rearma el auto.
    val pulse = if (anyFault) {
        rememberInfiniteTransition(label = "fault").animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "pulse")
    } else null
    val accent = c.accent
    val danger = c.danger
    Box(modifier.aspectRatio(CAR_TOP_ASPECT)) {
        CarTop(d.cars, d.cars.pkg(d.prefs.carModel), d.prefs.carColor, Modifier.fillMaxSize(), muted = !connected)
        if (marked.isNotEmpty()) Canvas(Modifier.fillMaxSize()) {
            val r = size.width * 0.045f
            for (h in marked) {
                val at = Offset(size.width * h.zone.x, size.height * h.zone.y)
                val color = if (h.level == HealthLevel.FAULT) danger else accent
                // Halo suave; con falla, un anillo que se expande y se desvanece.
                drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.35f), Color.Transparent), at, r * 3f), r * 3f, at)
                if (h.level == HealthLevel.FAULT && pulse != null) {
                    val t = pulse.value
                    drawCircle(color.copy(alpha = (1f - t) * 0.6f), r * (1f + t * 2.2f), at, style = Stroke(r * 0.25f))
                }
                drawCircle(color, r, at)
                drawCircle(Color.White.copy(alpha = 0.9f), r * 0.35f, at)
            }
        }
    }
}

internal fun zoneName(zone: Zone) = when (zone) {
    Zone.ENGINE -> R.string.zone_engine
    Zone.INTAKE -> R.string.zone_intake
    Zone.COOLING -> R.string.zone_cooling
    Zone.BATTERY -> R.string.zone_battery
    Zone.TRANSMISSION -> R.string.zone_transmission
    Zone.EXHAUST -> R.string.zone_exhaust
    Zone.WHEELS -> R.string.zone_wheels
    Zone.BODY -> R.string.zone_body
}

/**
 * La pieza del inicio "Taller": el mini aéreo con sus puntos y una línea de estado
 * (N avisos, Todo bien, Conectando…, o nada si no hay adaptador). Un toque abre el Taller.
 */
@Composable
fun HealthTile(d: Dependencies, onClick: () -> Unit) {
    val c = LocalColors.current
    val obd = d.obdIfCreated()
    val connected = obd?.state == ObdState.ON
    val health = rememberHealth(d, obd?.lastReport, connected)
    val issues = health.issues()
    val known = health.any { it.level != HealthLevel.UNKNOWN }
    val detail = when {
        obd?.state == ObdState.CONNECTING -> stringResource(R.string.obd_connecting_short)
        connected && issues > 0 -> pluralStringResource(R.plurals.health_issues, issues, issues)
        connected && known -> stringResource(R.string.health_all_good)
        connected -> stringResource(R.string.obd_connected_short)
        else -> null
    }
    val detailColor = when {
        connected && issues > 0 -> if (health.any { it.level == HealthLevel.FAULT }) c.danger else c.accent
        connected && known -> Good
        else -> c.textMuted
    }
    Card(Modifier.fillMaxSize(), onClick = onClick) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(14.dp)) {
            val tall = maxHeight > 150.dp
            val sideWidth = maxHeight * CAR_TOP_ASPECT
            if (tall) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    CarWithMarks(d, health, connected, Modifier.weight(1f).fillMaxHeight())
                    Spacer(Modifier.height(8.dp))
                    Label(stringResource(R.string.workshop), Typography.subtitle, maxLines = 1)
                    if (detail != null) Label(detail, Typography.small, detailColor, maxLines = 1)
                }
            } else {
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    CarWithMarks(d, health, connected, Modifier.fillMaxHeight().width(sideWidth))
                    Column(Modifier.padding(start = 14.dp)) {
                        Label(stringResource(R.string.workshop), Typography.subtitle, maxLines = 1)
                        if (detail != null) Label(detail, Typography.small, detailColor, maxLines = 1)
                    }
                }
            }
        }
    }
}
