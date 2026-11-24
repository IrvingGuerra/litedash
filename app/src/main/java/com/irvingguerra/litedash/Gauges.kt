package com.irvingguerra.litedash

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.vehicle.Signal
import com.irvingguerra.litedash.core.vehicle.watch
import com.irvingguerra.litedash.feature.car.CarStill
import com.irvingguerra.litedash.feature.obd.CHARGING_MAX_V
import com.irvingguerra.litedash.feature.obd.HOT_COOLANT_C
import com.irvingguerra.litedash.feature.obd.ObdState
import com.irvingguerra.litedash.feature.obd.RESTING_MIN_V
import java.util.Locale

/**
 * Velocímetro en el estilo de su [GaugeLook] (de fábrica, la Esfera). Lee la velocidad del
 * auto (OBD) si hay adaptador conectado; si no, del GPS (pide permiso la primera vez).
 */
@Composable
fun SpeedometerTile(d: Dependencies, look: GaugeLook) {
    // Con un adaptador ya guardado, se reconecta solo (sin abrir el Taller).
    val obdOn = rememberObd(d)?.state == ObdState.ON
    val (gps, ask) = rememberGpsPermission(d)
    if (!obdOn && !gps) {
        return ShortcutTile(Icons.speed, stringResource(R.string.tile_speedometer), stringResource(R.string.tap_to_enable), ask)
    }
    // Sin OBD, el GPS publica la velocidad en el bus mientras la pieza se ve.
    if (!obdOn) KeepTripRunning(d.trip)
    val kmh = d.bus.watch(Signal.SPEED)
    val miles = d.prefs.units.miles
    SpeedGaugeCard((kmh ?: 0f).toMiles(miles), miles, speedTag(d), look, d.prefs.carColor, rememberSpeedFront(d, look))
}

/**
 * Lo que va al frente del velocímetro: tu auto quieto (de frente, sin reflejo) o tu imagen; null si
 * no se pidió nada, no hay auto instalado o aún no hay imagen. Se arma una vez por cambio, no con cada lectura.
 */
@Composable
fun rememberSpeedFront(d: Dependencies, look: GaugeLook): (@Composable (Modifier) -> Unit)? {
    // Solo se busca el auto si va al frente (es barato: está en memoria).
    val pkg = if (look.front == GaugeFront.CAR) d.cars.pkg(d.prefs.carModel) else null
    val paint = d.prefs.carPaint
    return remember(look.front, look.image, pkg, paint) {
        when (look.front) {
            GaugeFront.NONE -> null
            GaugeFront.CAR -> pkg?.let { { m: Modifier -> CarStill(d.cars, it, paint, m, reflection = false) } }
            GaugeFront.IMAGE -> look.image.takeIf { it.isNotEmpty() }?.let { name -> { m: Modifier -> SavedTileImage(name, m) } }
        }
    }
}

/** De dónde viene la velocidad (OBD, GPS, CAN…: el nombre de su fuente, que ya es la sigla), para la etiqueta del velocímetro. */
fun speedTag(d: Dependencies) = d.bus.source(Signal.SPEED)?.name.orEmpty()

/**
 * La tarjeta del velocímetro con [speed] ya en la unidad que se ve (km/h o mph): la
 * usan la pieza y la vista previa de Personalizar medidor. [front]: ver [rememberSpeedFront].
 */
@Composable
fun SpeedGaugeCard(speed: Float, miles: Boolean, tag: String, look: GaugeLook, carColor: Color, front: (@Composable (Modifier) -> Unit)? = null) {
    val max = look.speedMax(miles).toFloat()
    // Marcas cada 20 km/h (10 mph); con 240 o más, cada 40 (20 mph) para que no se amontonen.
    val ticks = ticksFor(max, miles)
    val locale = currentLocale()
    // Los números de las marcas, con los dígitos del idioma: una vez por tope, no en cada cuadro.
    val labels = remember(max, ticks, locale) { List(ticks + 1) { i -> String.format(locale, "%d", (max * i / ticks).toInt()) } }
    GaugeCard(
        number = String.format(locale, "%.0f", speed),
        unit = speedUnit(miles),
        tag = tag,
        fraction = speed / max,
        ticks = ticks,
        look = look,
        carColor = carColor,
        labels = labels,
        front = front,
        title = stringResource(R.string.speed_title),
        arc = true,
    )
}

/** Cuántas marcas lleva un velocímetro que llega a [max]. */
fun ticksFor(max: Float, miles: Boolean): Int {
    val step = if (miles) (if (max > 160f) 20f else 10f) else (if (max >= 240f) 40f else 20f)
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
    val shown: (value: Float, units: UnitChoice) -> Float,
    val unit: (units: UnitChoice) -> Int,
    val decimals: Int,
    /** El rango del arco, en la unidad del bus. */
    val min: Float,
    val max: Float,
    val warning: (Float) -> Boolean = { false },
    /** Desde dónde va la zona roja (unidad del bus); null = el último 20 %. */
    val red: Float? = null,
    /** En unidades de EE. UU., un rango propio con números redondos (convertido tal cual salían 12, 24, 35…). */
    val imperial: ImperialScale? = null,
) {
    /** Hasta dónde llega el arco (unidad del bus) con estas unidades. */
    fun maxFor(units: UnitChoice) = imperial?.takeIf { units.miles }?.max ?: max

    /** Cuántas marcas lleva con estas unidades. */
    fun ticksFor(units: UnitChoice) = imperial?.takeIf { units.miles }?.ticks ?: ticks

    /** Qué tanto del arco se llena, con el valor del bus. */
    fun fraction(v: Float, units: UnitChoice) = (v - min) / (maxFor(units) - min)

    /** Dónde empieza la zona roja (0–1). */
    fun redFrom(units: UnitChoice) = red?.let { fraction(it, units) } ?: 0.8f

    /** El número de la marca [i], en la unidad que se ve (las rpm en miles). */
    fun label(i: Int, units: UnitChoice, locale: Locale): String {
        val max = maxFor(units)
        val v = shown(min + (max - min) * i / ticksFor(units), units).let { if (shown(max, units) >= 1000f) it / 1000f else it }
        // Un cero con error de redondeo (-0.000001) no sale como "-0".
        return String.format(locale, "%.0f", if (kotlin.math.abs(v) < 0.5f) 0f else v)
    }
}

/** El rango en millas de un medidor: hasta [max] (unidad del bus) en [ticks] marcas. */
class ImperialScale(val max: Float, val ticks: Int)

// km/l → millas por galón y litros → galones (EE. UU.).
private const val MPG_PER_KML = 2.352f
private const val GAL_PER_L = 0.2642f

val GaugeSpecs = listOf(
    GaugeSpec(
        Signal.RPM, R.string.gauge_rpm, null, Icons.tachometer, ticks = 8,
        shown = { v, _ -> v }, unit = { R.string.unit_rpm }, decimals = 0,
        min = 0f, max = 8000f, warning = { it >= 6500f }, red = 6500f,
    ),
    GaugeSpec(
        Signal.COOLANT, R.string.gauge_coolant, R.string.gauge_coolant_tag, Icons.thermometer, ticks = 9,
        shown = { v, u -> v.toFahrenheit(u.fahrenheit) }, unit = { if (it.fahrenheit) R.string.unit_f else R.string.unit_c }, decimals = 0,
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
        shown = { v, u -> if (u.miles) v * MPG_PER_KML else v }, unit = { if (it.miles) R.string.unit_mpg else R.string.unit_kml }, decimals = 1,
        // 0–70 mpg de 10 en 10.
        min = 0f, max = 30f, imperial = ImperialScale(70f / MPG_PER_KML, 7),
    ),
    GaugeSpec(
        Signal.BOOST, R.string.gauge_boost, R.string.gauge_boost_tag, Icons.turbo, ticks = 8,
        // El turbo se habla en psi (también en México): −15…25 psi para que la escala vaya de 5 en 5.
        shown = { v, _ -> v * 0.145f }, unit = { R.string.unit_psi }, decimals = 1,
        min = -15f / 0.145f, max = 25f / 0.145f,
    ),
    GaugeSpec(
        Signal.THROTTLE, R.string.gauge_throttle, R.string.gauge_throttle_tag, Icons.pedal, ticks = 5,
        shown = { v, _ -> v }, unit = { R.string.unit_percent }, decimals = 0,
        min = 0f, max = 100f,
    ),
    GaugeSpec(
        Signal.OIL, R.string.gauge_oil, R.string.gauge_oil_tag, Icons.oil, ticks = 9,
        shown = { v, u -> v.toFahrenheit(u.fahrenheit) }, unit = { if (it.fahrenheit) R.string.unit_f else R.string.unit_c }, decimals = 0,
        min = 40f, max = 150f, warning = { it >= 130f }, red = 130f,
    ),
    GaugeSpec(
        Signal.FUEL_RATE, R.string.gauge_fuel_rate, R.string.gauge_fuel_rate_tag, Icons.fuel, ticks = 6,
        shown = { v, u -> if (u.miles) v * GAL_PER_L else v }, unit = { if (it.miles) R.string.unit_galh else R.string.unit_lh }, decimals = 1,
        // 0–8 gal/h de 1 en 1.
        min = 0f, max = 30f, imperial = ImperialScale(8f / GAL_PER_L, 8),
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
    val obd = rememberObd(d)
    // Sin adaptador (o sin ese dato) se ve igual, vacío y con "—", y una nota corta: no un texto largo.
    if (obd?.state != ObdState.ON) {
        val note = stringResource(if (obd?.state == ObdState.CONNECTING) R.string.obd_connecting_short else R.string.gauge_no_obd)
        return SpecGaugeCard(spec, null, d.prefs.units, look, d.prefs.carColor, onClick = onWorkshop, note = note)
    }
    if (!obd.canRead(spec.signal)) return SpecGaugeCard(spec, null, d.prefs.units, look, d.prefs.carColor, onClick = onPick, note = stringResource(R.string.gauge_no_data))
    val value = d.bus.watch(spec.signal)
    // Un toque cambia qué dato muestra.
    SpecGaugeCard(spec, value, d.prefs.units, look, d.prefs.carColor, onClick = onPick)
}

/**
 * La tarjeta de un medidor de [spec] con [value] en la unidad del bus (null = sin
 * lectura: "—"); la usan la pieza y la vista previa de Personalizar medidor.
 */
@Composable
fun SpecGaugeCard(spec: GaugeSpec, value: Float?, units: UnitChoice, look: GaugeLook, carColor: Color, onClick: (() -> Unit)? = null, note: String? = null) {
    val locale = currentLocale()
    val labels = remember(spec, units, locale) { List(spec.ticksFor(units) + 1) { spec.label(it, units, locale) } }
    GaugeCard(
        number = value?.let { gaugeText(spec, it, units, locale) } ?: "—",
        unit = stringResource(spec.unit(units)),
        // La nota (sin OBD, conectando…) va donde va la etiqueta del dato.
        tag = note ?: spec.tag?.let { stringResource(it) } ?: "",
        fraction = value?.let { spec.fraction(it, units) } ?: 0f,
        ticks = spec.ticksFor(units),
        look = look,
        carColor = carColor,
        labels = labels,
        redFrom = spec.redFrom(units),
        warning = value != null && spec.warning(value),
        onClick = onClick,
        title = stringResource(spec.name),
    )
}

/** El número como se lee en la tarjeta (sin unidad). */
fun gaugeText(spec: GaugeSpec, value: Float, units: UnitChoice, locale: Locale): String =
    String.format(locale, "%.${spec.decimals}f", spec.shown(value, units))
