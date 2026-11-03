package com.irvingguerra.litedash

import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
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

/** Cuántos segundos se ven en la gráfica y cada cuánto se toma un punto. */
private const val CHART_SECONDS = 90
private const val CHART_STEP_MS = 500L
private const val CHART_POINTS = CHART_SECONDS * 1000 / CHART_STEP_MS.toInt()

/** Hasta dos señales en la misma gráfica (la segunda en azul, con su propia escala). */
private const val MAX_CHART_SIGNALS = 2
private val Second = Color(0xFF4CA6FF)

/** Las señales que da este auto (las calculadas y el voltaje incluidos). */
@Composable
internal fun readableSignals(d: Dependencies): List<String> =
    remember(d.obd.supported) { (Pids.map { it.signal } + DerivedSignals.map { it.signal }).distinct().filter(d.obd::canRead) }

/** Gráficas en vivo: una o dos señales de los últimos [CHART_SECONDS] segundos. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiveCharts(d: Dependencies) {
    val c = LocalColors.current
    val signals = readableSignals(d)
    val chosen = remember { mutableStateListOf<String>() }
    LaunchedEffect(signals) { if (chosen.isEmpty()) chosen += listOf(Signal.RPM, Signal.SPEED).filter { it in signals } }
    SettingsCard(stringResource(R.string.obd_charts), stringResource(R.string.obd_charts_help)) {
        val locale = currentLocale()
        val imperial = imperialUnits(d.prefs.language)
        val series = chosen.map { signal -> signal to rememberSeries(d, signal) }
        for ((i, pair) in series.withIndex()) {
            val (signal, points) = pair
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Label("●", Typography.body, if (i == 0) c.accent else Second)
                Label(stringResource(sensorName(signal)), Typography.body, maxLines = 1, modifier = Modifier.weight(1f))
                val now = points.lastOrNull()
                val min = points.minOrNull()
                val max = points.maxOrNull()
                if (now != null && min != null && max != null) {
                    Label(
                        stringResource(R.string.obd_chart_range, sensorText(signal, min, imperial, locale), sensorText(signal, max, imperial, locale)),
                        Typography.small, c.textMuted, maxLines = 1,
                    )
                    Label(sensorText(signal, now, imperial, locale), Typography.subtitle, if (i == 0) c.accent else Second, maxLines = 1)
                }
            }
        }
        val grid = c.line
        Canvas(Modifier.fillMaxWidth().height(220.dp)) {
            for (k in 0..4) {
                val y = size.height * k / 4
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            }
            val capacity = CHART_POINTS
            for ((i, pair) in series.withIndex()) {
                val points = pair.second
                if (points.size < 2) continue
                // Cada señal con su propia escala (rpm y km/h no caben en la misma).
                val lo = points.min()
                val hi = points.max().let { if (it - lo < 1e-3f) lo + 1f else it }
                val path = Path()
                points.forEachIndexed { k, v ->
                    val x = size.width * (capacity - points.size + k) / (capacity - 1)
                    val y = size.height * (1 - (v - lo) / (hi - lo)) * 0.9f + size.height * 0.05f
                    if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, if (i == 0) c.accent else Second, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
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

/** Los últimos valores de [signal], uno cada [CHART_STEP_MS] (sin dato, no se agrega punto). */
@Composable
private fun rememberSeries(d: Dependencies, signal: String): List<Float> {
    val points = remember(signal) { mutableStateListOf<Float>() }
    d.bus.watch(signal)
    LaunchedEffect(signal) {
        val capacity = CHART_POINTS
        while (true) {
            d.bus.value(signal)?.let { v ->
                points += v
                if (points.size > capacity) points.removeRange(0, points.size - capacity)
            }
            delay(CHART_STEP_MS)
        }
    }
    return points
}

/** Cronómetro de 0 a 100 km/h (o 0 a 60 mph) con la velocidad del OBD o del GPS. */
@Composable
fun AccelTimerCard(d: Dependencies) {
    val c = LocalColors.current
    val imperial = imperialUnits(d.prefs.language)
    val target = if (imperial) 60f * KM_PER_MILE else 100f
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
    SettingsCard(stringResource(if (imperial) R.string.accel_title_mph else R.string.accel_title), stringResource(R.string.accel_help)) {
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
        val speedText = speed?.let { sensorText(Signal.SPEED, it, imperial, locale) } ?: "—"
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
    val imperial = imperialUnits(d.prefs.language)
    SettingsCard(stringResource(R.string.trips_title)) {
        SwitchRow(stringResource(R.string.trips_record), d.prefs.tripLog, { d.prefs.tripLog = it }, stringResource(R.string.trips_help))
        val trips = loaded ?: return@SettingsCard
        if (trips.isEmpty()) {
            Label(stringResource(R.string.trips_none), Typography.small, c.textMuted)
            return@SettingsCard
        }
        Button(stringResource(R.string.trips_export), { export.launch("litedash-trips.csv") }, primary = true)
        val dates = remember(locale) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale) }
        for (t in trips.take(30)) TripRow(t, dates, imperial, locale)
    }
}

@Composable
private fun TripRow(t: TripSummary, dates: DateFormat, imperial: Boolean, locale: java.util.Locale) {
    val c = LocalColors.current
    val km = if (imperial) String.format(locale, "%.1f mi", t.km.toMiles(true)) else String.format(locale, "%.1f km", t.km)
    val minutes = t.seconds / 60
    val time = String.format(locale, "%d:%02d h", minutes / 60, minutes % 60)
    val extras = buildList {
        add(stringResource(R.string.trip_avg, sensorText(Signal.SPEED, t.avgKmh, imperial, locale)))
        add(stringResource(R.string.trip_max, sensorText(Signal.SPEED, t.maxKmh, imperial, locale)))
        if (t.maxRpm > 0f) add(String.format(locale, "%.0f rpm", t.maxRpm))
        t.maxCoolant?.let { add(sensorText(Signal.COOLANT, it, imperial, locale)) }
        t.kmPerLiter?.let { add(sensorText(Signal.CONSUMPTION, it, imperial, locale)) }
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
    val on = d.prefs.tripLog && d.hasObdAdapter
    LaunchedEffect(on) { if (on) d.obd.connect() }
    if (!on || d.obdIfCreated()?.state != ObdState.ON) return
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
