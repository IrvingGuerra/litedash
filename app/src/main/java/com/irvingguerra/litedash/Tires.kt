package com.irvingguerra.litedash

import com.irvingguerra.litedash.feature.obd.ObdState
import com.irvingguerra.litedash.feature.obd.FactoryTires
import com.irvingguerra.litedash.feature.car.WheelSpots
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.geometry.Rect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Radius
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.core.vehicle.watch
import com.irvingguerra.litedash.feature.car.CAR_TOP_ASPECT
import com.irvingguerra.litedash.feature.car.CarTop
import com.irvingguerra.litedash.feature.tpms.PressureUnit
import com.irvingguerra.litedash.feature.tpms.TireAlert
import com.irvingguerra.litedash.feature.tpms.TireAlertKind
import com.irvingguerra.litedash.feature.tpms.TireSeverity
import com.irvingguerra.litedash.feature.tpms.TireSignals
import com.irvingguerra.litedash.feature.tpms.TpmsRepository
import com.irvingguerra.litedash.feature.tpms.TpmsSource
import com.irvingguerra.litedash.feature.tpms.TpmsStatus
import com.irvingguerra.litedash.feature.tpms.Wheel
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToInt

// Llantas: la vista desde arriba (Ajustes, pantalla completa y pieza grande), las pastillas
// sobre el auto, la pieza, el aviso, el testigo de la barra lateral y la tarjeta de Salud.

/** Cómo está una llanta, en una palabra. */
internal enum class TireState { GOOD, LOW, LEAK, HOT, BATTERY, LOST, NO_SENSOR }

/** El estado de una llanta y qué tan grave es (null = nada que avisar). */
internal data class TireStatus(val state: TireState, val severity: TireSeverity?) {
    val problem get() = severity != null
}

private fun TireAlertKind.state() = when (this) {
    TireAlertKind.LOW -> TireState.LOW
    TireAlertKind.FAST_LEAK, TireAlertKind.SLOW_LEAK -> TireState.LEAK
    TireAlertKind.HIGH_TEMP -> TireState.HOT
    TireAlertKind.LOW_BATTERY -> TireState.BATTERY
    TireAlertKind.LOST -> TireState.LOST
}

/** La alerta más grave de [w] manda; sin alertas, sin sensor o sin señal. */
internal fun TpmsRepository.statusOf(w: Wheel): TireStatus {
    alerts.firstOrNull { it.wheel == w }?.let { return TireStatus(it.kind.state(), it.severity) }
    val r = wheelReading(w)
    return when {
        r == null && w in assignment -> TireStatus(TireState.LOST, null)
        r == null -> TireStatus(TireState.NO_SENSOR, null)
        r.lost -> TireStatus(TireState.LOST, TireSeverity.WARNING)
        else -> TireStatus(TireState.GOOD, null)
    }
}

internal fun TireState.word() = when (this) {
    TireState.GOOD -> R.string.tire_good
    TireState.LOW -> R.string.tire_low
    TireState.LEAK -> R.string.tire_leak
    TireState.HOT -> R.string.tire_hot
    TireState.BATTERY -> R.string.tire_battery
    TireState.LOST -> R.string.tire_lost
    TireState.NO_SENSOR -> R.string.tire_none
}

internal fun wheelName(w: Wheel) = when (w) {
    Wheel.FRONT_LEFT -> R.string.tire_fl
    Wheel.FRONT_RIGHT -> R.string.tire_fr
    Wheel.REAR_LEFT -> R.string.tire_rl
    Wheel.REAR_RIGHT -> R.string.tire_rr
    Wheel.SPARE -> R.string.tire_spare
}

private fun wheelShort(w: Wheel) = when (w) {
    Wheel.FRONT_LEFT -> R.string.tire_fl_short
    Wheel.FRONT_RIGHT -> R.string.tire_fr_short
    Wheel.REAR_LEFT -> R.string.tire_rl_short
    Wheel.REAR_RIGHT -> R.string.tire_rr_short
    Wheel.SPARE -> R.string.tire_spare
}

/** Color de un estado: rojo si es peligro, ámbar si pide atención, null si todo bien. */
@Composable
internal fun severityColor(s: TireSeverity?): Color? = when (s) {
    TireSeverity.DANGER -> LocalColors.current.danger
    TireSeverity.WARNING -> LocalColors.current.warning
    null -> null
}

/**
 * Dónde cae cada rueda en la caja de [CarTop] (fracciones de ancho y alto): el dibujo y los
 * renders `planta` usan la misma caja.
 */
private fun wheelSpot(w: Wheel) = when (w) {
    Wheel.FRONT_LEFT -> Offset(0.07f, 0.281f)
    Wheel.FRONT_RIGHT -> Offset(0.93f, 0.281f)
    Wheel.REAR_LEFT -> Offset(0.07f, 0.719f)
    Wheel.REAR_RIGHT -> Offset(0.93f, 0.719f)
    Wheel.SPARE -> Offset(0.5f, 0.9f)
}

/**
 * Los halos de las llantas con problema sobre el auto desde arriba (caja de [CarTop]), con
 * [ProblemMarks] (igual que en Salud): solo un peligro late; un aviso leve se queda quieto.
 */
@Composable
private fun TireMarks(statuses: Map<Wheel, TireStatus>, modifier: Modifier, radius: Float = 0.06f) {
    val c = LocalColors.current
    val marks = statuses.mapNotNull { (w, st) ->
        if (!st.problem || w == Wheel.SPARE) return@mapNotNull null
        val danger = st.severity == TireSeverity.DANGER
        ProblemMark(wheelSpot(w).x, wheelSpot(w).y, if (danger) c.danger else c.warning, pulsing = danger)
    }
    ProblemMarks(marks, modifier, radius)
}

/** Grados en la unidad del usuario ("28 °C" o "82 °F"). */
internal fun tempText(c: Float, fahrenheit: Boolean) = "${c.toFahrenheit(fahrenheit).roundToInt()} °${if (fahrenheit) "F" else "C"}"

/** Presión y temperatura de [w] por el bus: mientras se ve, las llantas se leen. */
@Composable
private fun watchTire(d: Dependencies, w: Wheel): Pair<Float?, Float?> =
    d.bus.watch(TireSignals.pressure(w)) to d.bus.watch(TireSignals.temp(w))

/** Las llantas que se muestran: las cuatro y la refacción si se pidió y tiene sensor. */
private fun TpmsRepository.shownWheels() =
    if (showSpare && Wheel.SPARE in assignment) Wheel.ROAD + Wheel.SPARE else Wheel.ROAD

/**
 * Las llantas moviéndose a otro lugar (rotar): cada tarjeta de [moves] (de → a) se desliza
 * con [progress] de 0 a 1; luego se intercambian los datos y todo regresa a 0 sin saltos.
 */
@Stable
class TireMotion {
    var moves by mutableStateOf<Map<Wheel, Wheel>>(emptyMap())
    val progress = Animatable(0f)
}

/** Largo mínimo de la línea entre la tarjeta y el auto (dp). */
private const val LEADER_DP = 64f

/**
 * Tus llantas desde arriba: el auto al centro y una tarjeta por llanta en su esquina, unida a
 * su rueda con una línea fina (como las de Salud). Una llanta con problema tiene un halo en su
 * rueda que late despacio; todo bien, nada se mueve. Sin fuente, el auto apagado y un mensaje
 * (con [onSettings], un botón para configurarlas).
 */
@Composable
fun TiresView(
    d: Dependencies,
    compact: Boolean,
    onWheel: (Wheel) -> Unit,
    modifier: Modifier = Modifier,
    selected: Wheel? = null,
    motion: TireMotion? = null,
    onSettings: (() -> Unit)? = null,
    onReady: (() -> Unit)? = null,
    onCarPlaced: ((LayoutCoordinates) -> Unit)? = null,
    carLayer: (GraphicsLayerScope.() -> Unit)? = null,
) {
    val c = LocalColors.current
    val repo = d.tpms
    if (repo.source == null) return NoTires(d, modifier, onSettings)
    val unit = repo.unit
    val fahrenheit = d.prefs.units.fahrenheit
    val wheels = repo.shownWheels()
    val statuses = wheels.associateWith { repo.statusOf(it) }

    Column(modifier) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val density = LocalDensity.current
            val w = maxWidth.value
            val h = maxHeight.value
            val gap = 12f
            val cw = if (compact) (w * 0.32f).coerceIn(96f, 170f) else (w * 0.28f).coerceIn(130f, 220f)
            val ch = if (compact) 78f else 122f
            val spare = Wheel.SPARE in wheels
            val spareH = if (spare) ch + gap else 0f
            val areaH = h - spareH
            val carH = minOf(areaH, (w - 2 * cw - 2 * gap) / CAR_TOP_ASPECT).coerceAtLeast(60f)
            val carW = carH * CAR_TOP_ASPECT
            val carX = (w - carW) / 2
            val carY = (areaH - carH) / 2
            fun wheelAt(wh: Wheel) = Offset(carX + carW * wheelSpot(wh).x, carY + carH * wheelSpot(wh).y)
            // Lugar de cada tarjeta (dp): las delanteras a la altura de su rueda, las traseras abajo sin encimarse.
            val frontY = (wheelAt(Wheel.FRONT_LEFT).y - ch / 2).coerceIn(0f, (areaH - 2 * ch - gap).coerceAtLeast(0f))
            val rearY = (wheelAt(Wheel.REAR_LEFT).y - ch / 2).coerceIn(frontY + ch + gap, (areaH - ch).coerceAtLeast(frontY + ch + gap))
            // En pantallas muy anchas las tarjetas se quedan cerca del auto (no en las orillas).
            val leftX = (carX - LEADER_DP - cw).coerceAtLeast(0f)
            val rightX = (carX + carW + LEADER_DP).coerceAtMost(w - cw)
            val slots = mapOf(
                Wheel.FRONT_LEFT to Offset(leftX, frontY),
                Wheel.FRONT_RIGHT to Offset(rightX, frontY),
                Wheel.REAR_LEFT to Offset(leftX, rearY),
                Wheel.REAR_RIGHT to Offset(rightX, rearY),
                Wheel.SPARE to Offset((w - cw) / 2, h - ch),
            )
            val px = density.density
            val line = c.textMuted.copy(alpha = 0.45f)

            // Líneas: de la orilla de cada tarjeta a su rueda (no mientras las tarjetas se mueven).
            Canvas(Modifier.fillMaxSize()) {
                val moving = motion != null && motion.progress.value > 0f
                for (wh in wheels) {
                    if (wh == Wheel.SPARE || (moving && wh in motion.moves)) continue
                    val slot = slots.getValue(wh)
                    val from = Offset(if (wh == Wheel.FRONT_LEFT || wh == Wheel.REAR_LEFT) slot.x + cw else slot.x, slot.y + ch / 2)
                    val to = wheelAt(wh)
                    drawLine(line, from * px, to * px, strokeWidth = 1.dp.toPx())
                    drawCircle(line, 2.5.dp.toPx(), to * px)
                }
            }
            Box(
                Modifier
                    .offset(carX.dp, carY.dp)
                    .size(carW.dp, carH.dp)
                    .then(if (onCarPlaced != null) Modifier.onGloballyPositioned(onCarPlaced) else Modifier)
                    .then(if (carLayer != null) Modifier.graphicsLayer(carLayer) else Modifier),
            ) {
                CarTop(d.cars, d.cars.pkg(d.prefs.carModel), d.prefs.carColor, Modifier.fillMaxSize(), onReady)
                TireMarks(statuses, Modifier.fillMaxSize())
            }
            for (wh in wheels) {
                val slot = slots.getValue(wh)
                val (kpa, temp) = watchTire(d, wh)
                val st = statuses.getValue(wh)
                val points = rememberTireSeries(repo, wh, unit)
                TireCard(
                    // Tarjetas angostas: el nombre corto ("Del. izq.") para que no se corte.
                    name = stringResource(if (compact || cw < 165f) wheelShort(wh) else wheelName(wh)),
                    pressure = kpa?.let { unit.format(it) } ?: "—",
                    unit = unit.label,
                    temp = temp?.let { tempText(it, fahrenheit) },
                    status = st,
                    // Con pocas muestras la línea sería un rayón: espera a tener unas cuantas.
                    series = if (compact || points.size < 4) null else points,
                    // Mínimo ±10 kPa a lo alto: una llanta estable se ve plana y una fuga, bajada clara.
                    sparkSpan = unit.fromKpa(20f),
                    compact = compact,
                    selected = selected == wh,
                    onClick = { onWheel(wh) },
                    modifier = Modifier
                        .offset(slot.x.dp, slot.y.dp)
                        .size(cw.dp, ch.dp)
                        .graphicsLayer {
                            // Rotar: la tarjeta viaja a su lugar nuevo.
                            val to = motion?.moves?.get(wh) ?: return@graphicsLayer
                            val target = slots.getValue(to)
                            val p = motion.progress.value
                            translationX = (target.x - slot.x) * p * px
                            translationY = (target.y - slot.y) * p * px
                        },
                )
            }
        }
        if (!compact) TiresFooter(repo, Modifier.padding(top = 10.dp))
    }
}

/** Las muestras de los últimos 30 min de [w] en la unidad del usuario (se vuelven a leer con cada muestra nueva). */
@Composable
private fun rememberTireSeries(repo: TpmsRepository, w: Wheel, unit: PressureUnit): List<Float> {
    val bucket = repo.wheelReading(w)?.atMillis?.div(30_000L)
    return remember(w, unit, bucket) {
        repo.history(w, System.currentTimeMillis() - SPARK_MS).map { unit.fromKpa(it.kpa) }.takeLast(SPARK_POINTS)
    }
}

private const val SPARK_MS = 30 * 60_000L
private const val SPARK_POINTS = 60

/** Una llanta: presión grande, temperatura y estado en chico, y la línea de 30 min. */
@Composable
private fun TireCard(
    name: String,
    pressure: String,
    unit: String,
    temp: String?,
    status: TireStatus,
    series: List<Float>?,
    sparkSpan: Float,
    compact: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val c = LocalColors.current
    val shape = RoundedCornerShape(Radius.inner)
    val color = severityColor(status.severity)
    var m = modifier.clip(shape).background(c.surfaceHigh)
    if (selected) m = m.border(1.5.dp, c.accent, shape)
    Column(m.clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = if (compact) 8.dp else 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (color != null) Box(Modifier.size(7.dp).clip(CircleShape).background(color))
            Label(name, Typography.small, c.textMuted, maxLines = 1)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Label(pressure, Typography.title.copy(fontSize = if (compact) 22.sp else 30.sp), color ?: c.text, maxLines = 1)
            Label(unit, Typography.small, c.textMuted, maxLines = 1, modifier = Modifier.padding(start = 4.dp, bottom = if (compact) 3.dp else 5.dp))
        }
        val word = stringResource(status.state.word())
        val line = if (compact) (if (status.problem || temp == null) word else temp) else listOfNotNull(temp, word).joinToString(" · ")
        Label(line, Typography.small, color ?: c.textMuted, maxLines = 1)
        if (series != null) {
            Spacer(Modifier.weight(1f))
            Sparkline(series, color ?: c.textMuted, Modifier.fillMaxWidth().height(20.dp), capacity = SPARK_POINTS, minSpan = sparkSpan)
        }
    }
}

/** "Recomendada 32 psi · actualizada hace 5 s". */
@Composable
private fun TiresFooter(repo: TpmsRepository, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    val unit = repo.unit
    val front = unit.format(repo.recommendedFrontKpa)
    val rear = unit.format(repo.recommendedRearKpa)
    val recommended = if (front == rear) "$front ${unit.label}" else "$front / $rear ${unit.label}"
    val newest = Wheel.entries.mapNotNull { repo.wheelReading(it)?.atMillis }.maxOrNull()
    // Solo esta línea se rearma cada 5 s (para el "hace…").
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(5_000)
            value = System.currentTimeMillis()
        }
    }
    val parts = mutableListOf(stringResource(R.string.tires_recommended, recommended))
    if (newest != null) parts += stringResource(R.string.tires_updated, agoText(maxOf(now, newest) - newest))
    Label(parts.joinToString(" · "), Typography.small, c.textMuted, maxLines = 1, modifier = modifier.fillMaxWidth())
}

/** "5 s", "3 min" o "2 h". */
@Composable
private fun agoText(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 60 -> stringResource(R.string.ago_seconds, s.coerceAtLeast(1))
        s < 3600 -> stringResource(R.string.ago_minutes, s / 60)
        else -> stringResource(R.string.ago_hours, s / 3600)
    }
}

/** Sin fuente de llantas: el auto apagado y qué hacer. */
@Composable
private fun NoTires(d: Dependencies, modifier: Modifier, onSettings: (() -> Unit)?) {
    val c = LocalColors.current
    Box(modifier, contentAlignment = Alignment.Center) {
        CarTop(d.cars, d.cars.pkg(d.prefs.carModel), d.prefs.carColor, Modifier.fillMaxHeight().padding(vertical = 8.dp))
        Column(
            Modifier.clip(RoundedCornerShape(Radius.card)).background(c.surface.copy(alpha = 0.92f)).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Label(stringResource(R.string.tires_no_source), Typography.subtitle, maxLines = 2)
            if (onSettings != null) Button(stringResource(R.string.tires_setup), onSettings, primary = true)
        }
    }
}

/** La línea de estado de la fuente ("Receptor USB CH340", "Buscando…"). */
@Composable
internal fun tiresStatusText(repo: TpmsRepository): String = when (repo.status) {
    TpmsStatus.OFF -> stringResource(R.string.tires_status_off)
    TpmsStatus.SEARCHING -> stringResource(R.string.tires_status_searching)
    TpmsStatus.NO_DEVICE -> stringResource(if (repo.source == TpmsSource.BLE) R.string.tires_status_no_bluetooth else R.string.tires_status_no_device)
    TpmsStatus.NO_PERMISSION -> stringResource(R.string.tires_status_no_permission)
    TpmsStatus.CONNECTED -> when (repo.source) {
        TpmsSource.DEMO -> stringResource(R.string.tires_source_demo)
        TpmsSource.OBD -> stringResource(R.string.tires_obd_connected, repo.obdProfile.orEmpty())
        TpmsSource.BLE -> pluralStringResource(R.plurals.tires_sensors, repo.sensorCount, repo.sensorCount)
        else -> repo.detail ?: stringResource(R.string.tires_status_connected)
    }
}

// ---- Pantalla completa ----

/** Tamaño con que entra lo de las llantas a pantalla completa (crece a 1 mientras aparece). */
private const val TIRES_START_SCALE = 0.96f

/** Cuánto se inclina hacia atrás el auto de la pieza al subir la cámara (grados). */
private const val RISE_TILT = 55f

/** Cuánto llega inclinado el auto de arriba antes de quedar plano (grados). */
private const val LAND_TILT = 25f

/** Distancia de la cámara para las inclinaciones (más = menos deformación). */
private const val RISE_CAMERA = 12f

/**
 * Las llantas a pantalla completa, sobre el inicio: aparecen con [progress] (0 a 1, se lee solo
 * al dibujar): el fondo se funde y lo de adentro crece un poco desde [TIRES_START_SCALE]. Con
 * [origin] (el auto de la pieza tocada, en [from], coordenadas de la pantalla) "sube la cámara":
 * ese auto se inclina hacia atrás y se va desvaneciendo mientras el auto de arriba llega desde su
 * lugar ([rise] y [land]). Tocar una tarjeta muestra su gráfica de 24 h al lado; tocar fuera cierra.
 * [onCarWidth]: el ancho (px) del auto de arriba, para precargarlo a ese tamaño.
 */
@Composable
fun TiresFull(
    d: Dependencies,
    progress: () -> Float,
    onClose: () -> Unit,
    onSettings: () -> Unit,
    onReady: () -> Unit,
    from: Rect = Rect.Zero,
    origin: (@Composable (Modifier) -> Unit)? = null,
    onCarWidth: ((Int) -> Unit)? = null,
) {
    val c = LocalColors.current
    val density = LocalDensity.current
    var chart by remember { mutableStateOf<Wheel?>(null) }
    val shared = origin != null && !from.isEmpty
    // Dónde queda el auto de arriba en la pantalla: se lee solo al dibujar.
    var carTo by remember { mutableStateOf(Rect.Zero) }
    Box(Modifier.fillMaxSize()) {
        // La transparencia a cada cosa al dibujarla (sin capa aparte de toda la pantalla en cada cuadro del fundido).
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = progress(); compositingStrategy = CompositingStrategy.ModulateAlpha }
                .background(c.background)
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        )
        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp)
                .fillMaxSize()
                .graphicsLayer {
                    val t = progress()
                    alpha = t
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                    // Con el auto que llega no crece: su lugar final tiene que ser el medido.
                    if (!shared) {
                        val s = TIRES_START_SCALE + (1f - TIRES_START_SCALE) * t
                        scaleX = s
                        scaleY = s
                    }
                }
                // Lo de adentro no cierra: solo el fondo.
                .clickable(remember { MutableInteractionSource() }, indication = null) {},
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val repo = d.tpms
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.tire, c.accent, 26.dp)
                Label(stringResource(R.string.tires), Typography.title, maxLines = 1)
                if (repo.source != null) Label(tiresStatusText(repo), Typography.small, c.textMuted, maxLines = 1, modifier = Modifier.weight(1f))
                else Spacer(Modifier.weight(1f))
                IconButton(Icons.settings, onSettings, background = c.surfaceHigh)
                IconButton(Icons.close, onClose, background = c.surfaceHigh)
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val portrait = maxHeight > maxWidth
                val view = @Composable { m: Modifier ->
                    Card(m) {
                        TiresView(
                            d, compact = false, onWheel = { chart = if (chart == it) null else it }, Modifier.fillMaxSize().padding(18.dp),
                            selected = chart, onSettings = onSettings, onReady = onReady,
                            onCarPlaced = {
                                carTo = it.boundsInRoot()
                                onCarWidth?.invoke(it.size.width)
                            },
                            carLayer = if (shared) ({ land(progress(), from, carTo) }) else null,
                        )
                    }
                }
                val graph = @Composable { m: Modifier, w: Wheel -> Card(m) { TireHistoryChart(d, w, { chart = null }, Modifier.fillMaxSize().padding(18.dp)) } }
                if (portrait) Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    view(Modifier.fillMaxWidth().weight(1f))
                    chart?.let { graph(Modifier.fillMaxWidth().weight(0.8f), it) }
                } else Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    view(Modifier.fillMaxHeight().weight(1f))
                    chart?.let { graph(Modifier.fillMaxHeight().weight(0.8f), it) }
                }
            }
        }
        if (shared) {
            // Encima de las tarjetas (se va desvaneciendo) y solo mientras se mueve: abierto ya no se dibuja.
            val moving by remember { derivedStateOf { progress() < 1f } }
            if (moving) origin(
                Modifier
                    .offset { IntOffset(from.left.roundToInt(), from.top.roundToInt()) }
                    .size(with(density) { from.width.toDp() }, with(density) { from.height.toDp() })
                    .graphicsLayer { rise(progress(), from, carTo) },
            )
        }
    }
}

/** 0 antes de [a], 1 después de [b] y una curva suave en medio. */
private fun smooth(a: Float, b: Float, t: Float): Float {
    val x = ((t - a) / (b - a)).coerceIn(0f, 1f)
    return x * x * (3 - 2 * x)
}

/** Cuánto crece el auto de la pieza para medir lo mismo que el de arriba a lo largo. */
private fun Density.riseScale(from: Rect, to: Rect) = (to.height / carWidth(from, this)).coerceIn(0.85f, 2f)

/**
 * El auto de la pieza con la cámara subiendo ([t] de 0 a 1): se inclina hacia atrás desde abajo,
 * crece (o se encoge) hasta medir lo del auto de arriba, va hacia su lugar y se desvanece en la
 * segunda mitad. Sin saber aún dónde queda el de arriba, solo se desvanece.
 */
private fun GraphicsLayerScope.rise(t: Float, from: Rect, to: Rect) {
    alpha = 1f - smooth(0.4f, 0.85f, t)
    if (to.isEmpty) return
    val s = 1f + (riseScale(from, to) - 1f) * t
    transformOrigin = TransformOrigin(0.5f, 1f)
    cameraDistance = RISE_CAMERA
    // Se inclina pronto (con la curva de [t] casi no se notaba antes de desvanecerse).
    rotationX = RISE_TILT * (t / 0.6f).coerceAtMost(1f)
    scaleX = s
    scaleY = s
    // Con el pivote abajo, el centro de lo escalado queda a s/2 del alto sobre la orilla de abajo.
    translationX = (to.center.x - from.center.x) * t
    translationY = from.center.y + (to.center.y - from.center.y) * t - (from.bottom - from.height * s / 2)
}

/**
 * El auto de arriba llegando ([t] de 0 a 1): sale del centro de la pieza con el largo del auto de
 * ahí, todavía un poco inclinado, y aparece en la segunda mitad. Su caja ya va con la
 * transparencia [t] de las tarjetas: aquí solo lo que falta.
 */
private fun GraphicsLayerScope.land(t: Float, from: Rect, to: Rect) {
    if (to.isEmpty) return
    alpha = if (t > 0f) (smooth(0.4f, 0.95f, t) / t).coerceAtMost(1f) else 0f
    val s0 = 1f / riseScale(from, to)
    val s = s0 + (1f - s0) * t
    cameraDistance = RISE_CAMERA
    rotationX = -LAND_TILT * (1f - t)
    scaleX = s
    scaleY = s
    translationX = (from.center.x - to.center.x) * (1f - t)
    translationY = (from.center.y - to.center.y) * (1f - t)
}

private const val DAY_MS = 24 * 3_600_000L

/**
 * Las últimas 24 h de una llanta: la línea de presión con la recomendada y el límite de aviso
 * punteados. Al tocar (o arrastrar) se ve la presión y la hora de ese momento.
 */
@Composable
private fun TireHistoryChart(d: Dependencies, w: Wheel, onClose: () -> Unit, modifier: Modifier) {
    val c = LocalColors.current
    val repo = d.tpms
    val unit = repo.unit
    val bucket = repo.wheelReading(w)?.atMillis?.div(30_000L)
    val now = remember(bucket) { System.currentTimeMillis() }
    val samples = remember(w, bucket) { repo.history(w, now - DAY_MS) }
    var touch by remember { mutableStateOf<Float?>(null) }
    val rec = repo.limits.recommended(w)
    val low = rec * (100 - repo.lowPercent) / 100f
    val locale = currentLocale()
    val times = remember(locale) { DateFormat.getTimeInstance(DateFormat.SHORT, locale) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label(stringResource(wheelName(w)), Typography.subtitle, maxLines = 1)
                Label(stringResource(R.string.tires_last_day), Typography.small, c.textMuted, maxLines = 1)
            }
            IconButton(Icons.close, onClose, size = 44.dp)
        }
        val at = touch
        val picked = if (at != null && samples.isNotEmpty()) samples.minByOrNull { abs(it.atMillis - (now - DAY_MS + (at * DAY_MS).toLong())) } else samples.lastOrNull()
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Label(picked?.let { "${unit.format(it.kpa)} ${unit.label}" } ?: "—", Typography.title.copy(fontSize = 28.sp), maxLines = 1)
            if (picked != null) Label(times.format(Date(picked.atMillis)), Typography.small, c.textMuted, maxLines = 1, modifier = Modifier.padding(bottom = 4.dp))
        }
        if (samples.size < 2) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Label(stringResource(R.string.tires_no_history), Typography.small, c.textMuted)
            }
            return@Column
        }
        val lo = minOf(samples.minOf { it.kpa }, low) - 5f
        val hi = maxOf(samples.maxOf { it.kpa }, rec) + 5f
        val lineColor = c.accent
        val grid = c.line
        val muted = c.textMuted
        val danger = c.danger
        Canvas(
            Modifier
                .fillMaxWidth()
                .weight(1f)
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
            fun xOf(t: Long) = size.width * ((t - (now - DAY_MS)).toFloat() / DAY_MS)
            fun yOf(v: Float) = size.height * (1 - (v - lo) / (hi - lo))
            for (k in 0..4) drawLine(grid, Offset(0f, size.height * k / 4), Offset(size.width, size.height * k / 4), strokeWidth = 1.dp.toPx())
            val dash = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))
            drawLine(muted, Offset(0f, yOf(rec)), Offset(size.width, yOf(rec)), strokeWidth = 1.dp.toPx(), pathEffect = dash)
            drawLine(danger.copy(alpha = 0.6f), Offset(0f, yOf(low)), Offset(size.width, yOf(low)), strokeWidth = 1.dp.toPx(), pathEffect = dash)
            val path = Path()
            var last: Long? = null
            for (s in samples) {
                // Un hueco de más de 5 min (auto apagado) corta la línea.
                val gap = last == null || s.atMillis - last > 5 * 60_000L
                if (gap) path.moveTo(xOf(s.atMillis), yOf(s.kpa)) else path.lineTo(xOf(s.atMillis), yOf(s.kpa))
                last = s.atMillis
            }
            drawPath(path, lineColor, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            if (at != null && picked != null) {
                val x = xOf(picked.atMillis)
                drawLine(muted, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
                drawCircle(lineColor, 5.dp.toPx(), Offset(x, yOf(picked.kpa)))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Label(stringResource(R.string.ago_hours, 24), Typography.small, c.textMuted, maxLines = 1, modifier = Modifier.weight(1f))
            Label(stringResource(R.string.tires_now), Typography.small, c.textMuted, maxLines = 1)
        }
    }
}

// ---- Inicio ----

private fun corner(w: Wheel) = when (w) {
    Wheel.FRONT_LEFT -> Alignment.TopStart
    Wheel.FRONT_RIGHT -> Alignment.TopEnd
    Wheel.REAR_LEFT -> Alignment.BottomStart
    else -> Alignment.BottomEnd
}

/**
 * Cuatro pastillas en las esquinas de la pieza del auto (delantera izquierda arriba a la
 * izquierda…) con la presión y un punto. Todo bien: discretas (70 %); la de un problema, de
 * su color. Solo sus propios toques: el auto sigue girando con el dedo.
 */
@Composable
fun BoxScope.TirePills(d: Dependencies, onOpen: () -> Unit) {
    if (d.prefs.tiresOnCar == TiresOnCar.OFF || !d.hasTpmsSource) return
    RoadPills(d, onOpen) { Modifier.align(corner(it)).padding(6.dp) }
}

/**
 * Las pastillas de las cuatro llantas que ruedan ([modifier] acomoda la de cada una). Todo bien:
 * discretas (70 %); la de un problema, de su color.
 */
@Composable
private fun RoadPills(d: Dependencies, onOpen: () -> Unit, modifier: (Wheel) -> Modifier = { Modifier }) {
    val statuses = Wheel.ROAD.associateWith { d.tpms.statusOf(it) }
    val calm = statuses.values.none { it.problem }
    for (w in Wheel.ROAD) {
        TirePill(pillText(d, w), statuses.getValue(w), onOpen, modifier(w).graphicsLayer { alpha = if (calm) 0.7f else 1f })
    }
}

/** La presión de [w] con su unidad ("32 psi"), o "—" sin lectura. */
@Composable
private fun pillText(d: Dependencies, w: Wheel): String {
    val unit = d.tpms.unit
    return d.bus.watch(TireSignals.pressure(w))?.let { "${unit.format(it)} ${unit.label}" } ?: "—"
}

/**
 * Las pastillas de las llantas que se ven, en una fila justo debajo del auto 3D, cada una bajo su
 * rueda ([spots], del giro): al girarlo aparecen y se van con las ruedas. Las que tapa el auto no
 * salen (un problema en ellas lo dice el aviso de arriba). Si dos chocan se recorren lo justo, sin
 * salirse de la pieza. [spots] se lee al acomodar: al girar solo se mueven, no se vuelven a armar.
 */
@Composable
fun WheelPills(d: Dependencies, spots: () -> WheelSpots, onOpen: () -> Unit) {
    Layout(content = { RoadPills(d, onOpen) }, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
        val s = spots()
        val gap = 4.dp.roundToPx()
        // De izquierda a derecha como se ven; las tapadas ni se acomodan (no se dibujan).
        val shown = Wheel.ROAD.indices.filter { s.visible[it] }.sortedBy { s.points[it].x }
        val pills = shown.map { measurables[it].measure(Constraints()) }
        val width = constraints.maxWidth
        // La fila va justo debajo de la rueda que pisa más abajo (la más cercana).
        val lowest = shown.maxOfOrNull { s.points[it].y } ?: 0f
        val row = (lowest + gap).roundToInt().coerceIn(0, (constraints.maxHeight - (pills.maxOfOrNull { it.height } ?: 0)).coerceAtLeast(0))
        // Cada una centrada en su rueda y luego sin choques: se recorren a la derecha y, si la
        // última se sale, de regreso desde la derecha.
        val left = IntArray(pills.size) { (s.points[shown[it]].x - pills[it].width / 2f).roundToInt() }
        var edge = 0
        for (n in pills.indices) {
            left[n] = maxOf(left[n], edge)
            edge = left[n] + pills[n].width + gap
        }
        edge = width
        for (n in pills.indices.reversed()) {
            left[n] = minOf(left[n], edge - pills[n].width)
            edge = left[n] - gap
        }
        layout(width, constraints.maxHeight) {
            pills.forEachIndexed { n, p -> p.place(left[n].coerceAtLeast(0), row) }
        }
    }
}

/** Orilla del auto de espaldas en su caja (desde cada lado), en fracción del ancho: ahí junto van las pastillas. */
private const val REAR_CAR_SIDE = 0.27f

/** A qué altura de la caja del auto de espaldas va la pastilla de [w]: las traseras en su rueda, las delanteras (ocultas detrás) más arriba. */
private fun rearPillY(w: Wheel) = if (w == Wheel.REAR_LEFT || w == Wheel.REAR_RIGHT) 0.84f else 0.5f

/**
 * Las cuatro pastillas junto a las ruedas del auto de espaldas de la carretera ([modifier] = la caja
 * del auto): las izquierdas a su izquierda, las derechas a su derecha, a la altura de cada rueda. Igual
 * que las de las esquinas: discretas si todo va bien y del color del problema si no.
 */
@Composable
fun RoadTirePills(d: Dependencies, onOpen: () -> Unit, modifier: Modifier) {
    if (d.prefs.tiresOnCar == TiresOnCar.OFF || !d.hasTpmsSource) return
    Layout(content = { RoadPills(d, onOpen) }, modifier = modifier) { measurables, constraints ->
        val gap = 6.dp.roundToPx()
        val pills = measurables.map { it.measure(Constraints()) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            Wheel.ROAD.forEachIndexed { i, w ->
                val p = pills[i]
                val left = w == Wheel.FRONT_LEFT || w == Wheel.REAR_LEFT
                val x = if (left) (constraints.maxWidth * REAR_CAR_SIDE).toInt() - p.width - gap else (constraints.maxWidth * (1 - REAR_CAR_SIDE)).toInt() + gap
                p.place(x, (constraints.maxHeight * rearPillY(w)).toInt() - p.height / 2)
            }
        }
    }
}

@Composable
private fun TirePill(text: String, status: TireStatus, onClick: () -> Unit, modifier: Modifier) {
    val c = LocalColors.current
    val color = severityColor(status.severity)
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background((if (color != null) color.copy(alpha = 0.18f).compositeOver(c.surface) else c.surface).copy(alpha = 0.88f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color ?: if (status.state == TireState.GOOD) Good else c.textMuted))
        Label(text, Typography.small.copy(fontWeight = FontWeight.SemiBold), color ?: c.text, maxLines = 1)
    }
}

/**
 * La pieza Llantas: chica (1×1) los cuatro números; mediana el auto desde arriba con los
 * valores a los lados; grande la vista completa. Sin fuente, "Configura tus llantas".
 */
@Composable
fun TiresTile(d: Dependencies, onOpen: () -> Unit, onSettings: () -> Unit) {
    val c = LocalColors.current
    val has = d.hasTpmsSource
    Card(Modifier.fillMaxSize(), onClick = if (has) onOpen else onSettings) {
        if (!has) {
            Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.tire, c.accent, 28.dp)
                Label(stringResource(R.string.tires_configure), Typography.small, c.textMuted, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
            }
            return@Card
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(10.dp)) {
            when {
                maxWidth >= 300.dp && maxHeight >= 200.dp -> TiresView(d, compact = maxHeight < 330.dp, onWheel = { onOpen() }, Modifier.fillMaxSize())
                maxWidth >= 170.dp || maxHeight >= 170.dp -> TiresMedium(d, tall = maxHeight > maxWidth * 1.3f, bare = maxWidth < 260.dp)
                else -> TiresSmall(d)
            }
        }
    }
}

/** Valor de una llanta en la pieza: punto de estado y la presión (sin unidad en [bare]). */
@Composable
private fun TileValue(d: Dependencies, w: Wheel, bare: Boolean, size: Int, gap: Int = 6) {
    val c = LocalColors.current
    val repo = d.tpms
    val kpa = d.bus.watch(TireSignals.pressure(w))
    val st = repo.statusOf(w)
    val color = severityColor(st.severity)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap.dp)) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color ?: if (st.state == TireState.GOOD) Good else c.textMuted))
        Label(kpa?.let { repo.unit.format(it) } ?: "—", Typography.title.copy(fontSize = size.sp), color ?: c.text, maxLines = 1)
        if (!bare) Label(repo.unit.label, Typography.small, c.textMuted, maxLines = 1)
    }
}

@Composable
private fun TiresSmall(d: Dependencies) {
    // Cuadrícula 2×2: cada llanta centrada en su cuarto.
    Column(Modifier.fillMaxSize()) {
        for (row in listOf(Wheel.FRONT_LEFT to Wheel.FRONT_RIGHT, Wheel.REAR_LEFT to Wheel.REAR_RIGHT)) {
            Row(Modifier.fillMaxWidth().weight(1f)) {
                for (w in listOf(row.first, row.second)) Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    TileValue(d, w, bare = true, size = 16, gap = 4)
                }
            }
        }
    }
}

/**
 * Mediana: el auto desde arriba con sus cuatro valores. Ancha: los valores a los lados;
 * alta (1×2): arriba las delanteras y abajo las traseras. Angosta, sin la unidad.
 */
@Composable
private fun TiresMedium(d: Dependencies, tall: Boolean, bare: Boolean) {
    val repo = d.tpms
    val statuses = Wheel.ROAD.associateWith { repo.statusOf(it) }
    val car = @Composable { m: Modifier ->
        Box(m.aspectRatio(CAR_TOP_ASPECT)) {
            CarTop(d.cars, d.cars.pkg(d.prefs.carModel), d.prefs.carColor, Modifier.fillMaxSize())
            TireMarks(statuses, Modifier.fillMaxSize(), radius = 0.067f)
        }
    }
    if (tall) Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TileValue(d, Wheel.FRONT_LEFT, bare = true, size = 17)
            TileValue(d, Wheel.FRONT_RIGHT, bare = true, size = 17)
        }
        car(Modifier.weight(1f).padding(vertical = 6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TileValue(d, Wheel.REAR_LEFT, bare = true, size = 17)
            TileValue(d, Wheel.REAR_RIGHT, bare = true, size = 17)
        }
    } else Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceAround) {
            TileValue(d, Wheel.FRONT_LEFT, bare = bare, size = 20)
            TileValue(d, Wheel.REAR_LEFT, bare = bare, size = 20)
        }
        car(Modifier.fillMaxHeight().padding(horizontal = 6.dp))
        Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceAround, horizontalAlignment = Alignment.End) {
            TileValue(d, Wheel.FRONT_RIGHT, bare = bare, size = 20)
            TileValue(d, Wheel.REAR_RIGHT, bare = bare, size = 20)
        }
    }
}

/** Texto del aviso: "Trasera derecha · 24 psi · baja". */
@Composable
private fun alertText(repo: TpmsRepository, a: TireAlert, fahrenheit: Boolean): String {
    val r = repo.wheelReading(a.wheel)
    val value = when (a.kind) {
        TireAlertKind.HIGH_TEMP -> r?.tempC?.let { tempText(it, fahrenheit) }
        TireAlertKind.LOST -> null
        else -> r?.pressureKpa?.let { "${repo.unit.format(it)} ${repo.unit.label}" }
    }
    val what = stringResource(
        when (a.kind) {
            TireAlertKind.LOW -> if (a.severity == TireSeverity.DANGER) R.string.tire_alert_very_low else R.string.tire_alert_low
            TireAlertKind.FAST_LEAK -> R.string.tire_alert_fast_leak
            TireAlertKind.SLOW_LEAK -> R.string.tire_alert_slow_leak
            TireAlertKind.HIGH_TEMP -> R.string.tire_alert_hot
            TireAlertKind.LOW_BATTERY -> R.string.tire_alert_battery
            TireAlertKind.LOST -> R.string.tire_alert_lost
        },
    )
    return listOfNotNull(stringResource(wheelName(a.wheel)), value, what).joinToString(" · ")
}

/**
 * Aviso arriba del inicio con la alerta más grave de las llantas (como el de motor). "Entendido"
 * la calla (por llanta y tipo) hasta que se resuelva y vuelva a pasar.
 */
@Composable
fun TiresAlertBanner(d: Dependencies, onOpen: () -> Unit) {
    if (!d.prefs.tiresBanner || !d.hasTpmsSource) return
    val repo = d.tpms
    if (!repo.alertsEnabled) return
    val dismissed = remember { mutableStateListOf<Pair<Wheel, TireAlertKind>>() }
    val alerts = repo.alerts
    LaunchedEffect(alerts) { dismissed.removeAll { k -> alerts.none { it.wheel to it.kind == k } } }
    val a = alerts.firstOrNull { (it.wheel to it.kind) !in dismissed } ?: return
    Notice(alertText(repo, a, d.prefs.units.fahrenheit), Icons.tireAlert, iconColor = severityColor(a.severity)) {
        Button(stringResource(R.string.tires_see), onOpen)
        Button(stringResource(R.string.alert_ok), { dismissed += a.wheel to a.kind }, primary = true)
    }
}

/** La tarjeta "Llantas" en Salud del Taller: estado y la presión más baja; abre la vista de llantas. */
@Composable
internal fun TiresHealthTile(d: Dependencies, onClick: () -> Unit, modifier: Modifier) {
    val c = LocalColors.current
    val repo = d.tpms
    val worst = Wheel.ROAD.map { it to repo.statusOf(it) }.firstOrNull { it.second.problem }
    val lowest = Wheel.ROAD.mapNotNull { w -> d.bus.watch(TireSignals.pressure(w))?.let { w to it } }.minByOrNull { it.second }
    val color = severityColor(worst?.second?.severity)
    val word = when {
        worst != null -> stringResource(worst.second.state.word())
        lowest != null -> stringResource(R.string.tire_good)
        else -> stringResource(R.string.health_unknown)
    }
    StatTile(
        name = stringResource(R.string.tires),
        value = word,
        modifier = modifier,
        valueColor = color ?: if (lowest != null) Good else c.textMuted,
        dot = color ?: if (lowest != null) Good else c.textMuted,
        detail = (worst?.first ?: lowest?.first)?.let { w ->
            val kpa = d.bus.value(TireSignals.pressure(w))
            listOfNotNull(stringResource(wheelName(w)), kpa?.let { "${repo.unit.format(it)} ${repo.unit.label}" }).joinToString(" · ")
        },
        detailColor = color ?: c.textMuted,
        onClick = onClick,
    )
}

/**
 * La fuente "Auto (OBD)" de Llantas: con el módulo OBD prendido y adaptador guardado, conecta el
 * escáner si está apagado (no tras un error: ahí reintenta él solo unas veces, o el Taller) y le pide
 * la presión de fábrica.
 */
internal suspend fun factoryTires(d: Dependencies): FactoryTires {
    if (!d.obdAuto) return FactoryTires.NotConnected
    val obd = d.obd
    if (obd.state == ObdState.OFF) obd.connect()
    return obd.factoryTires()
}
