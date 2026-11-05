package com.irvingguerra.litedash

import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.SwitchRow
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.core.vehicle.Signal
import com.irvingguerra.litedash.core.vehicle.watch
import com.irvingguerra.litedash.feature.obd.AccelState
import com.irvingguerra.litedash.feature.obd.AccelTimer
import com.irvingguerra.litedash.feature.obd.DerivedSignals
import com.irvingguerra.litedash.feature.obd.ObdState
import com.irvingguerra.litedash.feature.obd.Pids
import com.irvingguerra.litedash.feature.obd.TripRecorder
import com.irvingguerra.litedash.feature.obd.TripStore
import com.irvingguerra.litedash.feature.obd.TripSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/** Hasta dos señales en la misma gráfica (la segunda en azul, con su propia escala). */
private const val MAX_CHART_SIGNALS = 2
private val Second = Color(0xFF4CA6FF)

/** Qué tan lejos están dos colores en el círculo de tonos (0–180°). */
private fun hueDistance(a: Color, b: Color): Float {
    fun hue(c: Color) = FloatArray(3).also { android.graphics.Color.colorToHSV(c.toArgb(), it) }[0]
    val d = kotlin.math.abs(hue(a) - hue(b))
    return minOf(d, 360f - d)
}

/** Las señales que da este auto (las calculadas y el voltaje incluidos). */
@Composable
internal fun readableSignals(d: Dependencies): List<String> =
    remember(d.obd.supported) { (Pids.map { it.signal } + DerivedSignals.map { it.signal }).distinct().filter(d.obd::canRead) }

/**
 * Gráficas en vivo: una o dos señales de los últimos [SERIES_SECONDS] segundos. Al tocar
 * (o arrastrar) la gráfica se ve el valor de ese momento; al soltar, otra vez el de ahora.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiveCharts(d: Dependencies) {
    val c = LocalColors.current
    val signals = readableSignals(d)
    val chosen = remember { mutableStateListOf<String>() }
    LaunchedEffect(signals) { if (chosen.isEmpty()) chosen += listOf(Signal.RPM, Signal.SPEED).filter { it in signals } }
    // Dónde se está tocando la gráfica (0–1 del ancho); null = sin tocar.
    var touch by remember { mutableStateOf<Float?>(null) }
    SettingsCard(stringResource(R.string.obd_charts), stringResource(R.string.obd_charts_help)) {
        val locale = currentLocale()
        val units = d.prefs.units
        // Con llave: al quitar la primera, la otra conserva su historial.
        val series = chosen.map { signal -> signal to key(signal) { rememberSeries(d, signal) } }
        // La segunda serie no puede parecerse a la primera (con un azul o cian elegido): entonces va en ámbar.
        val colors = listOf(c.accent, if (hueDistance(c.accent, Second) < 40f) c.warning else Second)
        for ((i, pair) in series.withIndex()) {
            val (signal, points) = pair
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(colors[i]))
                Label(stringResource(sensorName(signal)), Typography.body, maxLines = 1, modifier = Modifier.weight(1f))
                // Tocando: el valor de ese momento ("—" donde aún no hay datos); si no, el de ahora.
                val at = touch
                val shown = if (at != null) pointAt(points, at) else points.lastOrNull()
                val min = points.minOrNull()
                val max = points.maxOrNull()
                if (min != null && max != null) {
                    Label(
                        stringResource(R.string.obd_chart_range, sensorText(signal, min, units, locale), sensorText(signal, max, units, locale)),
                        Typography.small, c.textMuted, maxLines = 1,
                    )
                    Label(shown?.let { sensorText(signal, it, units, locale) } ?: "—", Typography.subtitle, colors[i], maxLines = 1)
                }
            }
        }
        val grid = c.line
        val cursor = c.textMuted
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(220.dp)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        touch = (down.position.x / size.width).coerceIn(0f, 1f)
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            touch = (change.position.x / size.width).coerceIn(0f, 1f)
                        }
                        touch = null
                    }
                },
        ) {
            for (k in 0..4) {
                val y = size.height * k / 4
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            }
            val at = touch
            for ((i, pair) in series.withIndex()) {
                val points = pair.second
                if (points.size < 2) continue
                // Cada señal con su propia escala (rpm y km/h no caben en la misma).
                val lo = points.min()
                val hi = points.max().let { if (it - lo < 1e-3f) lo + 1f else it }
                fun yOf(v: Float) = size.height * (1 - (v - lo) / (hi - lo)) * 0.9f + size.height * 0.05f
                drawPath(seriesPath(points, size.width, SERIES_POINTS, ::yOf), colors[i], style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                val v = at?.let { pointAt(points, it) } ?: continue
                drawCircle(colors[i], 5.dp.toPx(), Offset(size.width * at, yOf(v)))
            }
            if (at != null) drawLine(cursor, Offset(size.width * at, 0f), Offset(size.width * at, size.height), strokeWidth = 1.dp.toPx())
        }
        // Primero la gráfica (que se vea sin bajar) y luego qué datos mostrar.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (s in signals) Button(stringResource(sensorName(s)), {
                when {
                    s in chosen -> chosen.remove(s)
                    chosen.size < MAX_CHART_SIGNALS -> chosen += s
                    else -> { chosen.removeAt(0); chosen += s }
                }
            }, primary = s in chosen)
        }
    }
}

/** El valor que había en [fraction] del ancho de la gráfica (null si ahí todavía no hay datos). */
private fun pointAt(points: List<Float>, fraction: Float): Float? {
    val k = (fraction * (SERIES_POINTS - 1)).roundToInt() - (SERIES_POINTS - points.size)
    return points.getOrNull(k)
}

/** Cronómetro de 0 a 100 km/h (o 0 a 60 mph) con la velocidad del OBD o del GPS. */
@Composable
fun AccelTimerCard(d: Dependencies) {
    val c = LocalColors.current
    val units = d.prefs.units
    val target = if (units.miles) 60f * KM_PER_MILE else 100f
    val timer = remember(target) { AccelTimer(target) }
    var state by remember { mutableStateOf(timer.state) }
    var now by remember { mutableIntStateOf(0) }
    val speed = d.bus.watch(Signal.SPEED)
    LaunchedEffect(speed) {
        if (speed == null) return@LaunchedEffect
        timer.update(SystemClock.elapsedRealtime(), speed)
        state = timer.state
        (state as? AccelState.Done)?.let { done ->
            if (d.prefs.bestAccel <= 0f || done.seconds < d.prefs.bestAccel) d.prefs.bestAccel = done.seconds
        }
    }
    // Mientras corre, el reloj avanza aunque la velocidad tarde en llegar.
    LaunchedEffect(state) { while (state is AccelState.Running) { now++; delay(50) } }
    val locale = currentLocale()
    SettingsCard(stringResource(if (units.miles) R.string.accel_title_mph else R.string.accel_title), stringResource(R.string.accel_help)) {
        val shown = when (val s = state) {
            is AccelState.Running -> { now; (SystemClock.elapsedRealtime() - s.startMs) / 1000f }
            is AccelState.Done -> s.seconds
            else -> 0f
        }
        Label(String.format(locale, "%.2f s", shown), Typography.clock.copy(fontSize = 96.sp), if (state is AccelState.Done) c.accent else c.text, maxLines = 1)
        Label(
            stringResource(
                when (state) {
                    AccelState.WaitStop -> R.string.accel_wait_stop
                    AccelState.Ready -> R.string.accel_ready
                    is AccelState.Running -> R.string.accel_running
                    is AccelState.Done -> R.string.accel_done
                },
            ),
            Typography.subtitle, maxLines = 2,
        )
        val speedText = speed?.let { sensorText(Signal.SPEED, it, units, locale) } ?: "—"
        val source = d.bus.source(Signal.SPEED)?.name ?: ""
        Label("$speedText · $source", Typography.body, c.textMuted, maxLines = 1)
        if (d.prefs.bestAccel > 0f) Label(stringResource(R.string.accel_best, String.format(locale, "%.2f s", d.prefs.bestAccel)), Typography.body, c.accent)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(stringResource(R.string.accel_reset), { timer.reset(); state = timer.state })
            if (d.prefs.bestAccel > 0f) Button(stringResource(R.string.accel_clear_best), { d.prefs.bestAccel = 0f })
        }
    }
}

/** Ajustes → Taller → Viajes: guardar cada viaje, la lista y exportarla. */
@Composable
fun TripsCard(d: Dependencies) {
    val c = LocalColors.current
    val context = LocalContext.current
    val store = remember { TripStore(context) }
    // null mientras se lee el archivo (para no mostrar "sin viajes" un instante).
    val loaded by produceState<List<TripSummary>?>(null, d.prefs.tripLog, tripsRevision.intValue) { value = store.all() }
    val scope = rememberCoroutineScope()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val text = store.csv()
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }
        }
    }
    val locale = currentLocale()
    val units = d.prefs.units
    SettingsCard(stringResource(R.string.trips_title)) {
        SwitchRow(stringResource(R.string.trips_record), d.prefs.tripLog, { d.prefs.tripLog = it }, stringResource(R.string.trips_help))
        val trips = loaded ?: return@SettingsCard
        if (trips.isEmpty()) {
            Label(stringResource(R.string.trips_none), Typography.small, c.textMuted)
            return@SettingsCard
        }
        Button(stringResource(R.string.trips_export), { export.launch("litedash-trips.csv") }, primary = true)
        val dates = remember(locale) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale) }
        for (t in trips.take(30)) TripRow(t, dates, units, locale)
    }
}

@Composable
private fun TripRow(t: TripSummary, dates: DateFormat, units: UnitChoice, locale: java.util.Locale) {
    val c = LocalColors.current
    val km = if (units.miles) String.format(locale, "%.1f mi", t.km.toMiles(true)) else String.format(locale, "%.1f km", t.km)
    val minutes = t.seconds / 60
    val time = String.format(locale, "%d:%02d h", minutes / 60, minutes % 60)
    val extras = buildList {
        add(stringResource(R.string.trip_avg, sensorText(Signal.SPEED, t.avgKmh, units, locale)))
        add(stringResource(R.string.trip_max, sensorText(Signal.SPEED, t.maxKmh, units, locale)))
        if (t.maxRpm > 0f) add(String.format(locale, "%.0f rpm", t.maxRpm))
        t.maxCoolant?.let { add(sensorText(Signal.COOLANT, it, units, locale)) }
        t.kmPerLiter?.let { add(sensorText(Signal.CONSUMPTION, it, units, locale)) }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Label(dates.format(Date(t.start)), Typography.body, maxLines = 1, modifier = Modifier.weight(1f))
            Label("$km · $time", Typography.subtitle, c.accent, maxLines = 1)
        }
        Label(extras.joinToString(" · "), Typography.small, c.textMuted, maxLines = 2)
    }
}

/** Sube cuando se guarda un viaje, para que la lista se vuelva a leer. */
private val tripsRevision = mutableIntStateOf(0)

/**
 * Lo que se lee para el registro de viajes (y solo mientras se está grabando). De consumo,
 * un solo PID: el gasto (5E) si el auto lo da; si no, el aire (MAF) para calcularlo.
 */
private fun tripSignals(d: Dependencies): List<String> =
    listOf(Signal.SPEED, Signal.RPM, Signal.COOLANT, if (d.obd.canRead(Signal.FUEL_RATE)) Signal.FUEL_RATE else Signal.MAF)

/**
 * Para guardar el viaje al terminar: vive con el proceso, no con la pantalla (el guardado
 * pasa justo cuando el composable sale, y su propio scope ya estaría cancelado).
 */
private val tripSaveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Graba el viaje mientras el OBD esté conectado y "Registrar viajes" prendido: una lectura
 * por segundo; al desconectarse (o apagar el auto) se guarda el resumen. Vive en el inicio,
 * como las alertas, así sigue grabando con Waze o la música al frente.
 */
@Composable
fun TripRecorderEffect(d: Dependencies) {
    if (rememberObd(d, d.prefs.tripLog)?.state != ObdState.ON) return
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val signals = tripSignals(d)
        for (s in signals) d.bus.want(s)
        onDispose { for (s in signals) d.bus.unwant(s) }
    }
    val recorder = remember { TripRecorder(System.currentTimeMillis()) }
    LaunchedEffect(recorder) {
        while (true) {
            delay(1_000)
            recorder.add(
                System.currentTimeMillis(),
                d.bus.value(Signal.SPEED), d.bus.value(Signal.RPM), d.bus.value(Signal.COOLANT),
                d.bus.value(Signal.MAF), d.bus.value(Signal.FUEL_RATE),
            )
        }
    }
    // Al desconectarse (o salir de LiteDash del todo) se guarda lo que se juntó.
    DisposableEffect(recorder) {
        onDispose {
            recorder.summary()?.let { trip ->
                val store = TripStore(context.applicationContext)
                tripSaveScope.launch {
                    store.add(trip)
                    tripsRevision.intValue++
                }
            }
        }
    }
}
