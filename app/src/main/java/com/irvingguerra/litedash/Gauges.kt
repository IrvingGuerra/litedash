package com.irvingguerra.litedash

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.vehicle.Signal
import com.irvingguerra.litedash.core.vehicle.Source
import com.irvingguerra.litedash.core.vehicle.watch
import com.irvingguerra.litedash.feature.obd.CHARGING_MAX_V
import com.irvingguerra.litedash.feature.obd.HOT_COOLANT_C
import com.irvingguerra.litedash.feature.obd.ObdState
import com.irvingguerra.litedash.feature.obd.RESTING_MIN_V
import java.util.Locale

/**
 * Velocímetro de arco: número grande y el arco que se llena. Lee la velocidad del
 * auto (OBD) si hay adaptador conectado; si no, del GPS (pide permiso la primera vez).
 */
@Composable
fun SpeedometerTile(d: Dependencies, look: GaugeLook) {
    // Con un adaptador ya guardado, se reconecta solo (sin abrir el Taller).
    AutoConnectObd(d)
    val obdOn = d.obdIfCreated()?.state == ObdState.ON
    val (gps, ask) = rememberGpsPermission(d)
    if (!obdOn && !gps) {
        return ShortcutTile(Icons.speed, stringResource(R.string.tile_speedometer), stringResource(R.string.tap_to_enable), ask)
    }
    // Sin OBD, el GPS publica la velocidad en el bus mientras la pieza se ve.
    if (!obdOn) KeepTripRunning(d.trip)
    val kmh = d.bus.watch(Signal.SPEED)
    val imperial = imperialUnits(d.prefs.language)
    val source = when (d.bus.source(Signal.SPEED)) {
        Source.OBD -> "OBD"
        Source.GPS -> "GPS"
        Source.CAN -> "CAN"
        null -> ""
    }
    SpeedGaugeCard((kmh ?: 0f).toMiles(imperial), imperial, source, look, d.prefs.carColor)
}

/**
 * La tarjeta del velocímetro con [speed] ya en la unidad que se ve (km/h o mph): la
 * usan la pieza y la vista previa de Personalizar medidor.
 */
@Composable
fun SpeedGaugeCard(speed: Float, imperial: Boolean, tag: String, look: GaugeLook, carColor: Color) {
    val max = (look.max ?: speedMaxOptions(imperial).first()).toFloat()
    // Marcas cada 20 km/h (10 mph); con 240 o más, cada 40 (20 mph) para que no se amontonen.
    val ticks = ticksFor(max, imperial)
    GaugeCard(
        number = String.format(currentLocale(), "%.0f", speed),
        unit = stringResource(if (imperial) R.string.unit_mph else R.string.unit_kmh),
        tag = tag,
        fraction = speed / max,
        ticks = ticks,
        look = look,
        carColor = carColor,
        labels = { i -> (max * i / ticks).toInt().toString() },
    )
}

/** Cuántas marcas lleva un velocímetro que llega a [max]. */
fun ticksFor(max: Float, imperial: Boolean): Int {
    val step = if (imperial) (if (max > 160f) 20f else 10f) else (if (max >= 240f) 40f else 20f)
    return (max / step).toInt().coerceAtLeast(1)
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
    /** El rango del arco, en la unidad del bus. */
    val min: Float,
    val max: Float,
    val warning: (Float) -> Boolean = { false },
    /** Desde dónde va la zona roja (unidad del bus); null = el último 20 %. */
    val red: Float? = null,
) {
    /** Qué tanto del arco se llena, con el valor del bus. */
    fun fraction(v: Float) = (v - min) / (max - min)

    /** Dónde empieza la zona roja (0–1). */
    val redFrom get() = red?.let(::fraction) ?: 0.8f

    /** El número de la marca [i] de [ticks], en la unidad que se ve (las rpm en miles). */
    fun label(i: Int, imperial: Boolean, locale: Locale): String {
        val v = shown(min + (max - min) * i / ticks, imperial)
        return String.format(locale, "%.0f", if (shown(max, imperial) >= 1000f) v / 1000f else v)
    }
}

val GaugeSpecs = listOf(
    GaugeSpec(
        Signal.RPM, R.string.gauge_rpm, null, Icons.tachometer, ticks = 8,
        shown = { v, _ -> v }, unit = { R.string.unit_rpm }, decimals = 0,
        min = 0f, max = 8000f, warning = { it >= 6500f }, red = 6500f,
    ),
    GaugeSpec(
        Signal.COOLANT, R.string.gauge_coolant, R.string.gauge_coolant_tag, Icons.thermometer, ticks = 9,
        shown = { v, imperial -> v.toFahrenheit(imperial) }, unit = { if (it) R.string.unit_f else R.string.unit_c }, decimals = 0,
        min = 40f, max = 130f, warning = { it >= HOT_COOLANT_C }, red = HOT_COOLANT_C,
    ),
    GaugeSpec(
        Signal.FUEL, R.string.gauge_fuel, R.string.gauge_fuel_tag, Icons.fuel, ticks = 4,
        shown = { v, _ -> v }, unit = { R.string.unit_percent }, decimals = 0,
        min = 0f, max = 100f, warning = { it <= 12f },
    ),
    GaugeSpec(
        Signal.VOLTAGE, R.string.gauge_voltage, R.string.gauge_voltage_tag, Icons.battery, ticks = 6,
        shown = { v, _ -> v }, unit = { R.string.unit_volts }, decimals = 1,
        min = 10f, max = 16f, warning = { it < RESTING_MIN_V || it > CHARGING_MAX_V },
    ),
    GaugeSpec(
        Signal.CONSUMPTION, R.string.gauge_consumption, R.string.gauge_consumption_tag, Icons.drop, ticks = 6,
        // km/l a millas por galón (EE. UU.).
        shown = { v, imperial -> if (imperial) v * 2.352f else v }, unit = { if (it) R.string.unit_mpg else R.string.unit_kml }, decimals = 1,
        min = 0f, max = 30f,
    ),
    GaugeSpec(
        Signal.BOOST, R.string.gauge_boost, R.string.gauge_boost_tag, Icons.turbo, ticks = 8,
        // El turbo se habla en psi (también en México).
        shown = { v, _ -> v * 0.145f }, unit = { R.string.unit_psi }, decimals = 1,
        min = -100f, max = 170f,
    ),
    GaugeSpec(
        Signal.THROTTLE, R.string.gauge_throttle, R.string.gauge_throttle_tag, Icons.pedal, ticks = 5,
        shown = { v, _ -> v }, unit = { R.string.unit_percent }, decimals = 0,
        min = 0f, max = 100f,
    ),
    GaugeSpec(
        Signal.OIL, R.string.gauge_oil, R.string.gauge_oil_tag, Icons.oil, ticks = 9,
        shown = { v, imperial -> v.toFahrenheit(imperial) }, unit = { if (it) R.string.unit_f else R.string.unit_c }, decimals = 0,
        min = 40f, max = 150f, warning = { it >= 130f }, red = 130f,
    ),
    GaugeSpec(
        Signal.FUEL_RATE, R.string.gauge_fuel_rate, R.string.gauge_fuel_rate_tag, Icons.fuel, ticks = 6,
        // Litros por hora a galones (EE. UU.) por hora.
        shown = { v, imperial -> if (imperial) v * 0.2642f else v }, unit = { if (it) R.string.unit_galh else R.string.unit_lh }, decimals = 1,
        min = 0f, max = 30f,
    ),
)

fun gaugeOf(signal: String) = GaugeSpecs.firstOrNull { it.signal == signal }

/**
 * Pieza "Medidor": un dato del auto por OBD (el que guarda [signal]). Vacía invita a
 * elegir; sin adaptador lleva al Taller; si el auto no da ese dato, lo dice.
 */
@Composable
fun GaugeTile(d: Dependencies, signal: String, look: GaugeLook, onPick: () -> Unit, onWorkshop: () -> Unit) {
    val spec = gaugeOf(signal)
        ?: return ShortcutTile(Icons.plus, stringResource(R.string.tile_gauge), stringResource(R.string.gauge_pick), onPick)
    // Con un adaptador ya guardado, se reconecta solo (sin abrir el Taller).
    AutoConnectObd(d)
    val obd = d.obdIfCreated()
    val name = stringResource(spec.name)
    if (obd?.state != ObdState.ON) {
        val detail = if (obd?.state == ObdState.CONNECTING) R.string.obd_connecting_short else R.string.gauge_connect
        return ShortcutTile(spec.icon, name, stringResource(detail), onWorkshop)
    }
    if (!obd.canRead(spec.signal)) return ShortcutTile(spec.icon, name, stringResource(R.string.gauge_unsupported), onPick)
    val value = d.bus.watch(spec.signal)
    // Un toque cambia qué dato muestra.
    SpecGaugeCard(spec, value, imperialUnits(d.prefs.language), look, d.prefs.carColor, onClick = onPick)
}

/**
 * La tarjeta de un medidor de [spec] con [value] en la unidad del bus (null = sin
 * lectura: "—"); la usan la pieza y la vista previa de Personalizar medidor.
 */
@Composable
fun SpecGaugeCard(spec: GaugeSpec, value: Float?, imperial: Boolean, look: GaugeLook, carColor: Color, onClick: (() -> Unit)? = null) {
    val locale = currentLocale()
    GaugeCard(
        number = value?.let { gaugeText(spec, it, imperial, locale) } ?: "—",
        unit = stringResource(spec.unit(imperial)),
        tag = spec.tag?.let { stringResource(it) } ?: "",
        fraction = value?.let(spec::fraction) ?: 0f,
        ticks = spec.ticks,
        look = look,
        carColor = carColor,
        labels = { spec.label(it, imperial, locale) },
        redFrom = spec.redFrom,
        warning = value != null && spec.warning(value),
        onClick = onClick,
    )
}

/** El número como se lee en la tarjeta (sin unidad). */
fun gaugeText(spec: GaugeSpec, value: Float, imperial: Boolean, locale: Locale): String =
    String.format(locale, "%.${spec.decimals}f", spec.shown(value, imperial))
