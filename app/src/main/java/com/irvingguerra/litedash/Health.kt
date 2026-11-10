package com.irvingguerra.litedash

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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SnapshotMutationPolicy
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.TilePadding
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.core.vehicle.Signal
import com.irvingguerra.litedash.core.vehicle.watch
import com.irvingguerra.litedash.feature.car.CAR_TOP_ASPECT
import com.irvingguerra.litedash.feature.car.CarPackage
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
 * las tarjetas van en gris: la misma pantalla, nunca una vacía. [onRead] (solo conectado)
 * vuelve a leer; [onCodes] lleva a la pestaña de códigos al tocar una parte con códigos.
 */
@Composable
fun HealthView(
    d: Dependencies,
    car: CarPackage?,
    report: EngineReport?,
    connected: Boolean,
    reading: Boolean = false,
    onRead: (() -> Unit)? = null,
    onCodes: () -> Unit = {},
    onTires: () -> Unit = {},
) {
    val health = rememberHealth(d, report, connected)
    Card(Modifier.fillMaxWidth().widthIn(max = 860.dp)) {
        BoxWithConstraints(Modifier.padding(22.dp)) {
            val wide = maxWidth > 620.dp
            // El auto se mide por su alto: en una pantalla acostada no debe pasar de lo que se ve.
            val carHeight = if (wide) 330.dp else 270.dp
            val details = @Composable {
                Summary(d, health, report, reading, connected)
                SystemTiles(d, health, report, connected, onCodes, onTires)
                if (onRead != null) Button(stringResource(R.string.obd_read_again), onRead)
            }
            if (wide) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    CarWithMarks(d, car, health, Modifier.padding(horizontal = 12.dp).height(carHeight))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) { details() }
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CarWithMarks(d, car, health, Modifier.height(carHeight))
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) { details() }
                }
            }
        }
    }
}

/** Las señales que deciden la salud en vivo (calor, carga y si el motor anda). */
private val HEALTH_SIGNALS = listOf(Signal.COOLANT, Signal.VOLTAGE, Signal.RPM)

/**
 * Misma salud si cada parte sigue con el mismo nivel, alerta y si tiene dato: así un rpm nuevo
 * no vuelve a armar la pantalla (el número en vivo lo lee cada tarjeta).
 */
private object SameLevels : SnapshotMutationPolicy<List<SystemHealth>> {
    override fun equivalent(a: List<SystemHealth>, b: List<SystemHealth>) =
        a.size == b.size && a.indices.all { i ->
            a[i].level == b[i].level && a[i].alert == b[i].alert && (a[i].value == null) == (b[i].value == null)
        }
}

/**
 * La salud con lo que se sabe ahora: el reporte, la alerta en vivo y los datos del bus (solo
 * conectado). Los datos se leen dentro de un [derivedStateOf]: quien la usa solo se vuelve a
 * dibujar cuando cambia el estado de alguna parte, no con cada lectura.
 */
@Composable
private fun rememberHealth(d: Dependencies, report: EngineReport?, connected: Boolean): List<SystemHealth> {
    if (connected) DisposableEffect(d.bus) {
        HEALTH_SIGNALS.forEach(d.bus::want)
        onDispose { HEALTH_SIGNALS.forEach(d.bus::unwant) }
    }
    val health = remember(report, connected) {
        derivedStateOf(SameLevels) {
            if (!connected) return@derivedStateOf healthOf(null, null, null, null, null)
            val coolant = d.bus.value(Signal.COOLANT)
            val voltage = d.bus.value(Signal.VOLTAGE)
            val rpm = d.bus.value(Signal.RPM)
            healthOf(report, engineAlert(coolant, voltage, rpm), coolant, voltage, rpm)
        }
    }
    return health.value
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
        issues > 0 -> pluralStringResource(R.plurals.health_issues, issues, issues) to (if (fault) c.danger else c.warning)
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
 * abajo; bien, su dato en vivo (o "Bien") y la línea de los últimos 90 s. Solo esta tarjeta
 * lee el dato en vivo: cada lectura vuelve a dibujar esta, no toda la pantalla.
 */
@Composable
private fun SystemTile(d: Dependencies, h: SystemHealth, report: EngineReport?, connected: Boolean, onCodes: () -> Unit, modifier: Modifier) {
    val c = LocalColors.current
    val color = when (h.level) {
        HealthLevel.GOOD -> Good
        HealthLevel.WARN -> c.warning
        HealthLevel.FAULT -> c.danger
        HealthLevel.UNKNOWN -> c.textMuted
    }
    val signal = signalOf(h.zone)
    val locale = currentLocale()
    val units = d.prefs.units
    // La batería sin dato en vivo usa el voltaje que leyó el reporte.
    val live = if (connected && signal != null) d.bus.watch(signal) else null
    val value = live ?: report?.voltage?.takeIf { h.zone == Zone.BATTERY }
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
 * Tu auto desde arriba (su render, o la silueta si el paquete no la trae), del mismo color que
 * en Llantas, con los puntos de atención. El punto late despacio solo si hay una falla; sin
 * fallas nada se mueve.
 */
@Composable
private fun CarWithMarks(d: Dependencies, car: CarPackage?, health: List<SystemHealth>, modifier: Modifier) {
    val c = LocalColors.current
    val marks = health.filter { it.level == HealthLevel.WARN || it.level == HealthLevel.FAULT }.map {
        val fault = it.level == HealthLevel.FAULT
        ProblemMark(it.zone.x, it.zone.y, if (fault) c.danger else c.warning, pulsing = fault)
    }
    Box(modifier.aspectRatio(CAR_TOP_ASPECT)) {
        CarTop(d.cars, car, d.prefs.carColor, Modifier.fillMaxSize())
        ProblemMarks(marks, Modifier.fillMaxSize())
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
fun HealthTile(d: Dependencies, car: CarPackage?, showCar: Boolean = true, onClick: () -> Unit) {
    val c = LocalColors.current
    // Con un adaptador guardado se conecta sola (como los medidores), sin abrir el Taller.
    val obd = rememberObd(d)
    val connected = obd?.state == ObdState.ON
    // Los códigos se leen una vez por conexión: sin eso diría "Todo bien" sin haberlos visto. Si
    // ya hay reporte (lo leyó el Taller) no se vuelven a leer.
    LaunchedEffect(obd, connected) { if (connected && obd?.lastReport == null) obd?.report() }
    val report = if (connected) obd?.lastReport else null
    val health = rememberHealth(d, report, connected)
    val issues = health.issues()
    val known = report != null && health.any { it.level != HealthLevel.UNKNOWN }
    val detail = when {
        obd?.state == ObdState.CONNECTING -> stringResource(R.string.obd_connecting_short)
        connected && issues > 0 -> pluralStringResource(R.plurals.health_issues, issues, issues)
        connected && known -> stringResource(R.string.health_all_good)
        connected -> stringResource(R.string.obd_reading)
        else -> null
    }
    val detailColor = when {
        connected && issues > 0 -> if (health.any { it.level == HealthLevel.FAULT }) c.danger else c.warning
        connected && known -> Good
        else -> c.textMuted
    }
    Card(Modifier.fillMaxSize(), onClick = onClick) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(TilePadding)) {
            val tall = maxHeight > 150.dp
            val sideWidth = maxHeight * CAR_TOP_ASPECT
            if (!showCar) {
                // Sin el auto: solo el estado, al centro.
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Label(stringResource(R.string.workshop), Typography.subtitle, maxLines = 1)
                    if (detail != null) Label(detail, Typography.small, detailColor, maxLines = 1)
                }
            } else if (tall) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    CarWithMarks(d, car, health, Modifier.weight(1f).fillMaxHeight())
                    Spacer(Modifier.height(8.dp))
                    Label(stringResource(R.string.workshop), Typography.subtitle, maxLines = 1)
                    if (detail != null) Label(detail, Typography.small, detailColor, maxLines = 1)
                }
            } else {
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    CarWithMarks(d, car, health, Modifier.fillMaxHeight().width(sideWidth))
                    Column(Modifier.padding(start = 14.dp)) {
                        Label(stringResource(R.string.workshop), Typography.subtitle, maxLines = 1)
                        if (detail != null) Label(detail, Typography.small, detailColor, maxLines = 1)
                    }
                }
            }
        }
    }
}
