package com.irvingguerra.litedash

import android.os.Build
import com.irvingguerra.litedash.feature.tpms.BleProblem
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
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
    TireSeverity.WARNING -> LocalColors.current.accent
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

private const val COUNT_MS = 400
/** Largo mínimo de la línea entre la tarjeta y el auto (dp). */
private const val LEADER_DP = 64f
private const val STAGGER_MS = 60

/**
 * Tus llantas desde arriba: el auto al centro y una tarjeta por llanta en su esquina, unida a
 * su rueda con una línea fina (como las de Salud). Los números cuentan desde 0 la primera vez;
 * con [entrance] las tarjetas además salen del auto una tras otra. Una llanta con problema
 * tiene un halo en su rueda que late despacio; todo bien, nada se mueve. Sin fuente, el auto
 * apagado y un mensaje (con [onSettings], un botón para configurarlas).
 */
@Composable
fun TiresView(
    d: Dependencies,
    compact: Boolean,
    onWheel: (Wheel) -> Unit,
    modifier: Modifier = Modifier,
    selected: Wheel? = null,
    motion: TireMotion? = null,
    entrance: Boolean = false,
    onSettings: (() -> Unit)? = null,
) {
    val c = LocalColors.current
    val repo = d.tpms
    if (repo.source == null) return NoTires(d, modifier, onSettings)
    val unit = repo.unit
    val fahrenheit = d.prefs.units.fahrenheit
    val wheels = repo.shownWheels()
    val count = remember { Animatable(0f) }
    val appear = remember { Animatable(if (entrance) 0f else 1f) }
    LaunchedEffect(Unit) { count.animateTo(1f, tween(COUNT_MS, easing = FastOutSlowInEasing)) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(COUNT_MS + STAGGER_MS * 4, easing = LinearEasing)) }
    val statuses = wheels.associateWith { repo.statusOf(it) }
    val anyProblem = statuses.values.any { it.problem }
    val pulse = if (anyProblem) {
        rememberInfiniteTransition(label = "tires").animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart), label = "pulse")
    } else null

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
            val carCenter = Offset(carX + carW / 2, carY + carH / 2)
            val line = c.textMuted.copy(alpha = 0.45f)
            val accent = c.accent
            val danger = c.danger

            // Líneas: de la orilla de cada tarjeta a su rueda (no mientras las tarjetas se mueven).
            Canvas(Modifier.fillMaxSize()) {
                val moving = motion != null && motion.progress.value > 0f
                for (wh in wheels) {
                    if (wh == Wheel.SPARE || (moving && wh in motion.moves)) continue
                    val slot = slots.getValue(wh)
                    val from = Offset(if (wh == Wheel.FRONT_LEFT || wh == Wheel.REAR_LEFT) slot.x + cw else slot.x, slot.y + ch / 2)
                    val to = wheelAt(wh)
                    val a = appear.value
                    drawLine(line.copy(alpha = line.alpha * a), from * px, to * px, strokeWidth = 1.dp.toPx())
                    drawCircle(line.copy(alpha = line.alpha * a), 2.5.dp.toPx(), to * px)
                }
            }
            Box(Modifier.offset(carX.dp, carY.dp).size(carW.dp, carH.dp)) {
                CarTop(d.cars, d.cars.pkg(d.prefs.carModel), d.prefs.carColor, Modifier.fillMaxSize())
                if (anyProblem) Canvas(Modifier.fillMaxSize()) {
                    val r = size.width * 0.06f
                    for ((wh, st) in statuses) {
                        if (!st.problem || wh == Wheel.SPARE) continue
                        val color = if (st.severity == TireSeverity.DANGER) danger else accent
                        val at = Offset(size.width * wheelSpot(wh).x, size.height * wheelSpot(wh).y)
                        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.45f), Color.Transparent), at, r * 3.2f), r * 3.2f, at)
                        val t = pulse?.value ?: 0f
                        drawCircle(color.copy(alpha = (1f - t) * 0.6f), r * (1f + t * 2.2f), at, style = Stroke(r * 0.22f))
                        drawCircle(color, r * 0.6f, at)
                    }
                }
            }
            wheels.forEachIndexed { i, wh ->
                val slot = slots.getValue(wh)
                val (kpa, temp) = watchTire(d, wh)
                val st = statuses.getValue(wh)
                val points = rememberTireSeries(repo, wh, unit)
                TireCard(
                    // Tarjetas angostas: el nombre corto ("Del. izq.") para que no se corte.
                    name = stringResource(if (compact || cw < 165f) wheelShort(wh) else wheelName(wh)),
                    pressure = kpa?.let { unit.format(it * count.value) } ?: "—",
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
                            // Salen del auto una tras otra (60 ms) la primera vez.
                            val local = ((appear.value * (COUNT_MS + STAGGER_MS * 4) - STAGGER_MS * i) / COUNT_MS).coerceIn(0f, 1f)
                            val e = FastOutSlowInEasing.transform(local)
                            alpha = e
                            val center = Offset(slot.x + cw / 2, slot.y + ch / 2)
                            var dx = (carCenter.x - center.x) * (1f - e) * 0.6f
                            var dy = (carCenter.y - center.y) * (1f - e) * 0.6f
                            scaleX = 0.85f + 0.15f * e
                            scaleY = scaleX
                            // Rotar: la tarjeta viaja a su lugar nuevo.
                            val to = motion?.moves?.get(wh)
                            if (to != null) {
                                val target = slots.getValue(to)
                                val p = motion.progress.value
                                dx += (target.x - slot.x) * p
                                dy += (target.y - slot.y) * p
                            }
                            translationX = dx * px
                            translationY = dy * px
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
        CarTop(d.cars, d.cars.pkg(d.prefs.carModel), d.prefs.carColor, Modifier.fillMaxHeight().padding(vertical = 8.dp), muted = true)
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
        TpmsSource.BLE -> pluralStringResource(R.plurals.tires_sensors, repo.readings.size, repo.readings.size)
        else -> repo.detail ?: stringResource(R.string.tires_status_connected)
    }
}

/** El problema de Bluetooth en el idioma de la app (null si no hay). */
@Composable
internal fun bleIssueText(repo: TpmsRepository): String? = when (repo.bleIssue) {
    null -> null
    // Hasta Android 11 buscar Bluetooth pide la ubicación; desde 12, "dispositivos cercanos".
    BleProblem.NO_PERMISSION -> stringResource(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) R.string.tires_ble_need_nearby else R.string.tires_ble_need_location)
    BleProblem.BLUETOOTH_OFF -> stringResource(R.string.tires_ble_off)
    BleProblem.LOCATION_OFF -> stringResource(R.string.tires_ble_location_off)
    BleProblem.UNSUPPORTED -> stringResource(R.string.tires_ble_unsupported)
    BleProblem.FAILED -> stringResource(R.string.tires_ble_failed)
}

// ---- Pantalla completa ----

/**
 * Las llantas a pantalla completa: crece desde [from] (la pieza o la pastilla que se tocó;
 * vacío = desde el centro) mientras el fondo tapa el inicio. [progress] va de 0 a 1 y se lee
 * solo al dibujar. Tocar una tarjeta muestra su gráfica de 24 h al lado.
 */
@Composable
fun TiresFull(d: Dependencies, from: Rect, progress: () -> Float, onClose: () -> Unit, onSettings: () -> Unit) {
    val c = LocalColors.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val insets = WindowInsets.safeDrawing
    var chart by remember { mutableStateOf<Wheel?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val to = with(density) {
            val pad = 16.dp.toPx()
            Rect(
                insets.getLeft(this, direction) + pad,
                insets.getTop(this) + pad,
                maxWidth.toPx() - insets.getRight(this, direction) - pad,
                maxHeight.toPx() - insets.getBottom(this) - pad,
            )
        }
        val start = if (from.isEmpty) 0.92f else (from.width / to.width).coerceIn(0.2f, 1f)
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = progress() }
                .background(c.background)
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        )
        Column(
            Modifier
                .offset { IntOffset(to.left.toInt(), to.top.toInt()) }
                .size(with(density) { to.width.toDp() }, with(density) { to.height.toDp() })
                .graphicsLayer {
                    val t = progress()
                    val scale = start + (1f - start) * t
                    scaleX = scale
                    scaleY = scale
                    alpha = (t * 1.6f).coerceAtMost(1f)
                    if (!from.isEmpty) {
                        translationX = (from.center.x - to.center.x) * (1f - t)
                        translationY = (from.center.y - to.center.y) * (1f - t)
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
                            selected = chart, entrance = true, onSettings = onSettings,
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
    }
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
    if (d.prefs.tiresOnCar != TiresOnCar.CORNERS || !d.hasTpmsSource) return
    val repo = d.tpms
    val statuses = Wheel.ROAD.associateWith { repo.statusOf(it) }
    val calm = statuses.values.none { it.problem }
    for (w in Wheel.ROAD) {
        val kpa = d.bus.watch(TireSignals.pressure(w))
        TirePill(kpa?.let { "${repo.unit.format(it)} ${repo.unit.label}" } ?: "—", statuses.getValue(w), onOpen, Modifier.align(corner(w)).padding(6.dp).graphicsLayer { alpha = if (calm) 0.7f else 1f })
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

private fun Color.compositeOver(bg: Color) = Color(
    red = red * alpha + bg.red * (1 - alpha),
    green = green * alpha + bg.green * (1 - alpha),
    blue = blue * alpha + bg.blue * (1 - alpha),
    alpha = 1f,
)

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
    val accent = LocalColors.current.accent
    val danger = LocalColors.current.danger
    val car = @Composable { m: Modifier ->
        Box(m.aspectRatio(CAR_TOP_ASPECT)) {
            CarTop(d.cars, d.cars.pkg(d.prefs.carModel), d.prefs.carColor, Modifier.fillMaxSize())
            if (statuses.values.any { it.problem }) Canvas(Modifier.fillMaxSize()) {
                val r = size.width * 0.08f
                for ((w, st) in statuses) {
                    if (!st.problem) continue
                    val color = if (st.severity == TireSeverity.DANGER) danger else accent
                    val at = Offset(size.width * wheelSpot(w).x, size.height * wheelSpot(w).y)
                    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.5f), Color.Transparent), at, r * 2.5f), r * 2.5f, at)
                    drawCircle(color, r * 0.5f, at)
                }
            }
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
    Notice(alertText(repo, a, d.prefs.units.fahrenheit), Icons.tireAlert, danger = a.severity == TireSeverity.DANGER) {
        Button(stringResource(R.string.tires_see), onOpen)
        Button(stringResource(R.string.alert_ok), { dismissed += a.wheel to a.kind }, primary = true)
    }
}

/** Testigo de llanta en la barra lateral: solo con un problema, del color del más grave. */
@Composable
fun TiresRailIndicator(d: Dependencies, size: androidx.compose.ui.unit.Dp, onOpen: () -> Unit) {
    if (!d.prefs.tiresRail || !d.hasTpmsSource) return
    val worst = d.tpms.alerts.firstOrNull() ?: return
    val color = severityColor(worst.severity) ?: return
    Box(contentAlignment = Alignment.Center) {
        IconButton(Icons.tireAlert, onOpen, size = size, color = color)
        Box(Modifier.align(Alignment.TopEnd).padding(top = size * 0.18f, end = size * 0.18f).size(8.dp).clip(CircleShape).background(color))
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
