package com.irvingguerra.litedash

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Radius
import com.irvingguerra.litedash.core.ui.Segmented
import com.irvingguerra.litedash.core.ui.SwitchRow
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.core.vehicle.bleScanPermission
import com.irvingguerra.litedash.feature.dashboard.TileTypes
import com.irvingguerra.litedash.feature.obd.ObdState
import com.irvingguerra.litedash.feature.tpms.PairState
import com.irvingguerra.litedash.feature.tpms.PressureUnit
import com.irvingguerra.litedash.feature.tpms.TireReading
import com.irvingguerra.litedash.feature.tpms.TpmsError
import com.irvingguerra.litedash.feature.tpms.TpmsRepository
import com.irvingguerra.litedash.feature.tpms.TpmsSource
import com.irvingguerra.litedash.feature.tpms.TpmsStatus
import com.irvingguerra.litedash.feature.tpms.Wheel
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Ajustes → Llantas, en pestañas: Sensores (de dónde se leen, el auto desde arriba —tocar una la
 * acomoda— y rotar), Presión y avisos, Calibrar (cada llanta contra el manómetro), En el inicio y Diagnóstico. Sin fuente
 * (módulo apagado), solo elegirla. Mientras se ve, las llantas se leen aunque nada más las muestre.
 */
@Composable
fun TiresSettings(d: Dependencies) {
    val repo = d.tpms
    DisposableEffect(repo) {
        val hold = repo.keepAwake()
        onDispose { hold.close() }
    }
    if (repo.source == null) return SourceCard(d)
    val motion = remember { TireMotion() }
    // Rotar sigue aunque se cambie de pestaña (el auto y las tarjetas viven aquí).
    val scope = rememberCoroutineScope()
    var chosen by remember { mutableStateOf<Wheel?>(null) }
    when (tabs(listOf(R.string.tab_sensors, R.string.tires_pressure, R.string.tires_calibrate, R.string.tires_on_home, R.string.tires_diagnostics), TIRES_TABS)) {
        // La fuente y el auto en una sola pestaña (el auto sale una vez).
        0 -> {
            SourceCard(d)
            // Por OBD cada llanta ya viene en su lugar (lo sabe el auto): no hay nada que acomodar.
            val arrange = repo.source != TpmsSource.OBD
            TiresCard(d, chosen, motion) { if (arrange) chosen = if (chosen == it) null else it }
            if (arrange) {
                chosen?.let { w -> ArrangeCard(repo, w, onClose = { chosen = null }) }
                    ?: Label(stringResource(R.string.tires_tap_hint), Typography.small, LocalColors.current.textMuted)
                RotateCard(repo, motion, scope)
            }
        }
        1 -> PressureCard(d)
        2 -> CalibrateCard(repo)
        3 -> HomeTiresCard(d)
        else -> DiagnosticsCard(repo)
    }
}

/** Las pestañas de Ajustes → Llantas (Sensores 0, Presión y avisos 1…). */
private const val TIRES_TABS = "tires"

/** La vista desde arriba en su tarjeta. */
@Composable
private fun TiresCard(d: Dependencies, selected: Wheel?, motion: TireMotion?, onWheel: (Wheel) -> Unit) {
    Card(Modifier.fillMaxWidth().widthIn(max = 860.dp).height(430.dp)) {
        TiresView(d, compact = false, onWheel = onWheel, Modifier.fillMaxSize().padding(18.dp), selected = selected, motion = motion)
    }
}

/**
 * Fuente: receptor USB, Bluetooth, el auto (OBD) o demostración, con su estado y qué hacer (apagarlo es el switch
 * de Módulos). Con el auto, «Reintentar» conecta el escáner (aunque se hubiera rendido).
 */
@Composable
private fun SourceCard(d: Dependencies) {
    val c = LocalColors.current
    val repo = d.tpms
    val permission = rememberPermissionAsk(bleScanPermission()) { repo.retry() }
    SettingsCard(stringResource(R.string.tires_source)) {
        Segmented(
            listOf(
                TpmsSource.USB to stringResource(R.string.tires_source_usb),
                TpmsSource.BLE to stringResource(R.string.tires_source_ble),
                TpmsSource.OBD to stringResource(R.string.tires_source_obd),
                TpmsSource.DEMO to stringResource(R.string.tires_source_demo),
            ),
            repo.source,
            { repo.source = it },
        )
        val source = repo.source ?: return@SettingsCard Label(stringResource(R.string.tires_source_help), Typography.small, c.textMuted)
        val problem = problemText(d)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val dot = when {
                repo.status == TpmsStatus.CONNECTED -> c.good
                problem != null -> c.danger
                else -> c.textMuted
            }
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Label(tiresStatusText(repo), Typography.body, maxLines = 1, modifier = Modifier.weight(1f))
            when {
                // Negado para siempre, «Permisos» lleva a los ajustes de LiteDash (Android ya no pregunta).
                repo.status == TpmsStatus.NO_PERMISSION && source == TpmsSource.BLE -> PermissionButtons(permission, stringResource(R.string.tires_grant))
                repo.status == TpmsStatus.NO_PERMISSION || repo.status == TpmsStatus.NO_DEVICE ->
                    Button(stringResource(R.string.retry), {
                        if (source == TpmsSource.OBD && d.obdAuto) {
                            obdStoppedByUser = false
                            d.obd.connect()
                        } else repo.retry()
                    })
            }
        }
        problem?.let { Label(it, Typography.small, c.danger) }
        if (source == TpmsSource.USB) Label(stringResource(R.string.tires_usb_help), Typography.small, c.textMuted)
        if (source == TpmsSource.BLE) Label(stringResource(R.string.tires_ble_help), Typography.small, c.textMuted)
        if (source == TpmsSource.OBD) Label(stringResource(R.string.tires_obd_help), Typography.small, c.textMuted)
    }
}

/** Lo que salió mal, en el idioma de la app (null si nada). Por el auto, por qué no conecta el escáner. */
@Composable
private fun problemText(d: Dependencies): String? {
    val repo = d.tpms
    repo.bleIssue?.let { return stringResource(bleProblemText(it)) }
    val res = when (repo.error) {
        null -> null
        TpmsError.USB_MISSING -> R.string.tires_error_usb_missing
        TpmsError.USB_DENIED -> R.string.tires_error_usb_denied
        TpmsError.USB_OPEN_FAILED -> R.string.tires_error_usb_open
        TpmsError.USB_UNPLUGGED -> R.string.tires_error_usb_unplugged
        TpmsError.USB_LOST -> R.string.tires_error_usb_lost
        TpmsError.SWAP_UNCONFIRMED -> R.string.tires_error_swap
        TpmsError.OBD_OFF -> d.obdIfCreated()?.takeIf { it.state == ObdState.ERROR }?.let { obdErrorText(it.errorKind) } ?: R.string.tires_error_obd_off
        TpmsError.OBD_UNSUPPORTED -> R.string.tires_error_obd_unsupported
    }
    return res?.let { stringResource(it) }
}

/**
 * Acomodar la llanta [w] (sin ventanitas: una tarjeta debajo de la vista). Bluetooth y demo:
 * elegir de los sensores que se oyen o "Identificar"; USB: emparejar con el receptor. En los
 * dos, intercambiar con otra llanta.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ArrangeCard(repo: TpmsRepository, w: Wheel, onClose: () -> Unit) {
    val c = LocalColors.current
    Card(Modifier.fillMaxWidth().widthIn(max = 860.dp)) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Label(stringResource(R.string.tires_arrange).uppercase(currentLocale()), Typography.label, c.textMuted, maxLines = 1)
                    Label(stringResource(wheelName(w)), Typography.title, maxLines = 1)
                }
                IconButton(Icons.close, onClose, size = 44.dp, background = c.surfaceHigh)
            }
            if (repo.source == TpmsSource.USB) UsbPairing(repo, w) else SensorPicker(repo, w)
            Label(stringResource(R.string.tires_swap_with), Typography.body)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (other in Wheel.entries.filter { it != w && (it != Wheel.SPARE || repo.showSpare) }) {
                    Button(stringResource(wheelName(other)), { if (!repo.swapping) repo.swap(w, other) })
                }
            }
            if (repo.swapping) Label(stringResource(R.string.tires_swapping), Typography.small, c.accent)
        }
    }
}

/** Bluetooth (y demo): los sensores que se oyen para ponerlos en [w], y el asistente "Identificar". */
@Composable
private fun SensorPicker(repo: TpmsRepository, w: Wheel) {
    val c = LocalColors.current
    // El asistente se pidió desde esta llanta: lo que encuentre va aquí.
    var waiting by remember(w) { mutableStateOf(false) }
    var confirmed by remember(w) { mutableStateOf<String?>(null) }
    LaunchedEffect(repo.identified, waiting) {
        val id = repo.identified ?: return@LaunchedEffect
        if (!waiting) return@LaunchedEffect
        repo.assign(w, id)
        confirmed = id
        waiting = false
    }
    if (repo.identifying && waiting) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Label(stringResource(R.string.tires_identify_help), Typography.body, c.accent, modifier = Modifier.weight(1f))
            Button(stringResource(R.string.cancel), { repo.cancelIdentify(); waiting = false })
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Label(stringResource(R.string.tires_identify_hint), Typography.small, c.textMuted, modifier = Modifier.weight(1f))
            Button(stringResource(R.string.tires_identify), { confirmed = null; waiting = true; repo.startIdentify() }, primary = true)
        }
    }
    confirmed?.let { Label(stringResource(R.string.tires_identified, shortId(it)), Typography.body, c.good) }
    val current = repo.assignment[w]
    val sensors = repo.nearby.sortedWith(compareByDescending<TireReading> { it.sensorId == current }.thenByDescending { it.rssi ?: Int.MIN_VALUE })
    if (sensors.isEmpty()) Label(stringResource(R.string.tires_no_sensors), Typography.small, c.textMuted)
    for (r in sensors.take(12)) {
        val at = repo.assignment.entries.firstOrNull { it.value == r.sensorId }?.key
        val shape = RoundedCornerShape(Radius.inner)
        Row(
            Modifier.fillMaxWidth().clip(shape).background(c.surfaceHigh).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Label(shortId(r.sensorId), Typography.body, maxLines = 1)
                val info = listOfNotNull(
                    r.pressureKpa?.let(repo.unit::text),
                    r.rssi?.let { stringResource(signalText(it)) },
                    at?.let { stringResource(R.string.tires_assigned_to, stringResource(wheelName(it))) },
                ).joinToString(" · ")
                Label(info, Typography.small, c.textMuted, maxLines = 1)
            }
            if (at == w) Button(stringResource(R.string.tires_remove), { repo.unassign(w) })
            else Button(stringResource(R.string.tires_put_here), { repo.assign(w, r.sensorId) }, primary = at == null)
        }
    }
}

/** Qué tan bien se oye un sensor, en palabras (sin dBm). */
internal fun signalText(rssi: Int) = when {
    rssi >= STRONG_DBM -> R.string.tires_signal_strong
    rssi >= MEDIUM_DBM -> R.string.tires_signal_medium
    else -> R.string.tires_signal_weak
}

private const val STRONG_DBM = -70
private const val MEDIUM_DBM = -85

/** El id corto que se reconoce (los últimos 6). */
private fun shortId(id: String) = if (id.length > 8) "…" + id.takeLast(6) else id

/** Receptor USB: aprender el sensor de [w], con la cuenta de 120 s. */
@Composable
private fun UsbPairing(repo: TpmsRepository, w: Wheel) {
    val c = LocalColors.current
    val mine = repo.pairingWheel == w
    var left by remember { mutableIntStateOf(PAIR_SECONDS) }
    LaunchedEffect(repo.pairing, mine) {
        if (repo.pairing != PairState.WAITING || !mine) return@LaunchedEffect
        val start = System.currentTimeMillis()
        while (true) {
            left = (PAIR_SECONDS - (System.currentTimeMillis() - start) / 1000).toInt().coerceAtLeast(0)
            delay(1_000)
        }
    }
    when {
        mine && repo.pairing == PairState.WAITING -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Label(stringResource(R.string.tires_pair_waiting, left), Typography.body, c.accent)
                Label(stringResource(R.string.tires_pair_help), Typography.small, c.textMuted)
            }
            Button(stringResource(R.string.cancel), repo::cancelPair)
        }
        else -> {
            if (mine && repo.pairing == PairState.DONE) Label(stringResource(R.string.tires_pair_done), Typography.body, c.good)
            if (mine && repo.pairing == PairState.TIMEOUT) Label(stringResource(R.string.tires_pair_timeout), Typography.body, c.danger)
            Button(stringResource(R.string.tires_pair), { repo.pairUsb(w) }, primary = true)
        }
    }
}

private const val PAIR_SECONDS = 120

/** Las tres rotaciones de la llantera; cada una mueve las tarjetas y luego cambia los sensores. */
private enum class Rotation(val title: Int, val pairs: List<Pair<Wheel, Wheel>>) {
    FRONT_REAR(R.string.tires_rotate_front_rear, listOf(Wheel.FRONT_LEFT to Wheel.REAR_LEFT, Wheel.FRONT_RIGHT to Wheel.REAR_RIGHT)),
    CROSS(R.string.tires_rotate_cross, listOf(Wheel.FRONT_LEFT to Wheel.REAR_RIGHT, Wheel.FRONT_RIGHT to Wheel.REAR_LEFT)),
    SIDES(R.string.tires_rotate_sides, listOf(Wheel.FRONT_LEFT to Wheel.FRONT_RIGHT, Wheel.REAR_LEFT to Wheel.REAR_RIGHT)),
}

@Composable
private fun RotateCard(repo: TpmsRepository, motion: TireMotion, scope: CoroutineScope) {
    val c = LocalColors.current
    val busy = motion.busy
    SettingsCard(stringResource(R.string.tires_rotate), stringResource(R.string.tires_rotate_help)) {
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (r in Rotation.entries) {
                val shape = RoundedCornerShape(Radius.inner)
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(shape)
                        .background(c.surfaceHigh)
                        .clickable(enabled = !busy) {
                            motion.busy = true
                            scope.launch { rotate(repo, motion, r.pairs) }
                        }
                        .padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RotationDiagram(r, Modifier.size(width = 54.dp, height = 78.dp))
                    Label(stringResource(r.title), Typography.small, if (busy) c.textMuted else c.text, maxLines = 2)
                }
            }
        }
    }
}

/**
 * Mueve las tarjetas (≈400 ms) y luego cambia los sensores de lugar, uno por uno (por USB se
 * espera la confirmación del receptor entre cambios). Al final todo vuelve a su lugar ya con
 * los datos nuevos. Empezados los cambios se terminan todos (una rotación a medias revolvería los
 * sensores) y, pase lo que pase, las tarjetas regresan a su lugar.
 */
private suspend fun rotate(repo: TpmsRepository, motion: TireMotion, pairs: List<Pair<Wheel, Wheel>>) {
    try {
        motion.moves = pairs.flatMap { (a, b) -> listOf(a to b, b to a) }.toMap()
        motion.progress.snapTo(0f)
        motion.progress.animateTo(1f, tween(400, easing = FastOutSlowInEasing))
        withContext(NonCancellable) {
            for ((a, b) in pairs) {
                repo.swap(a, b)
                if (repo.source == TpmsSource.USB) withTimeoutOrNull(USB_SWAP_WAIT_MS) { snapshotFlow { repo.swapping }.first { !it } }
            }
        }
    } finally {
        withContext(NonCancellable) {
            motion.moves = emptyMap()
            motion.progress.snapTo(0f)
            motion.busy = false
        }
    }
}

/** Lo más que se espera a que el receptor USB confirme un cambio. */
private const val USB_SWAP_WAIT_MS = 7_000L

/** El dibujito de cada rotación: el auto, sus cuatro llantas y hacia dónde van. */
@Composable
private fun RotationDiagram(r: Rotation, modifier: Modifier) {
    val c = LocalColors.current
    val body = c.textMuted.copy(alpha = 0.35f)
    val tire = c.text
    val arrow = c.accent
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val tw = w * 0.18f
        val th = h * 0.2f
        drawRoundRect(body, Offset(w * 0.14f, h * 0.04f), Size(w * 0.72f, h * 0.92f), CornerRadius(w * 0.18f), style = Stroke(1.5.dp.toPx()))
        val spots = mapOf(
            Wheel.FRONT_LEFT to Offset(w * 0.12f, h * 0.27f),
            Wheel.FRONT_RIGHT to Offset(w * 0.88f, h * 0.27f),
            Wheel.REAR_LEFT to Offset(w * 0.12f, h * 0.73f),
            Wheel.REAR_RIGHT to Offset(w * 0.88f, h * 0.73f),
        )
        for (s in spots.values) drawRoundRect(tire, Offset(s.x - tw / 2, s.y - th / 2), Size(tw, th), CornerRadius(tw * 0.3f))
        for ((a, b) in r.pairs) {
            val from = spots.getValue(a)
            val to = spots.getValue(b)
            // La flecha va de llanta a llanta, apenas separada de cada una.
            val dir = (to - from) / (to - from).getDistance()
            arrowLine(arrow, from + dir * (th * 0.75f), to - dir * (th * 0.75f))
        }
    }
}

private fun DrawScope.arrowLine(color: androidx.compose.ui.graphics.Color, a: Offset, b: Offset) {
    val stroke = 1.6.dp.toPx()
    drawLine(color, a, b, stroke, StrokeCap.Round)
    val dir = (b - a) / (b - a).getDistance()
    val normal = Offset(-dir.y, dir.x)
    val head = 4.dp.toPx()
    for ((tip, back) in listOf(b to -dir, a to dir)) {
        drawLine(color, tip, tip + back * head + normal * head * 0.7f, stroke, StrokeCap.Round)
        drawLine(color, tip, tip + back * head - normal * head * 0.7f, stroke, StrokeCap.Round)
    }
}

/** Presión recomendada, unidad, cuándo avisar y los dos avisos juntos (el del inicio depende del otro). */
@Composable
private fun PressureCard(d: Dependencies) {
    val repo = d.tpms
    val fahrenheit = d.prefs.units.fahrenheit
    val unit = repo.unit
    val locale = currentLocale()
    // Hasta dónde baja antes de avisar; con delantera y trasera distintas se dicen las dos.
    fun lowText(kpa: Float) = unit.text(kpa * (100 - repo.lowPercent) / 100f, locale)
    val lowFront = lowText(repo.recommendedFrontKpa)
    val lowRear = lowText(repo.recommendedRearKpa)
    val lowHelp = if (lowFront == lowRear) stringResource(R.string.tires_low_percent_help, lowFront)
    else stringResource(R.string.tires_low_percent_help_both, lowFront, lowRear)
    SettingsCard(stringResource(R.string.tires_pressure), stringResource(R.string.tires_pressure_help)) {
        Segmented(PressureUnit.entries.map { it to it.label }, unit, { repo.unit = it })
        Stepper(stringResource(R.string.tires_front), unit.text(repo.recommendedFrontKpa, locale),
            { repo.recommendedFrontKpa = stepKpa(repo.recommendedFrontKpa, unit, -1, RECOMMENDED_KPA) },
            { repo.recommendedFrontKpa = stepKpa(repo.recommendedFrontKpa, unit, 1, RECOMMENDED_KPA) })
        Stepper(stringResource(R.string.tires_rear), unit.text(repo.recommendedRearKpa, locale),
            { repo.recommendedRearKpa = stepKpa(repo.recommendedRearKpa, unit, -1, RECOMMENDED_KPA) },
            { repo.recommendedRearKpa = stepKpa(repo.recommendedRearKpa, unit, 1, RECOMMENDED_KPA) })
        Stepper(stringResource(R.string.tires_low_percent), stringResource(R.string.value_with_unit, String.format(locale, "%d", repo.lowPercent), stringResource(R.string.unit_percent)),
            { repo.lowPercent = (repo.lowPercent - 5).coerceIn(10, 30) },
            { repo.lowPercent = (repo.lowPercent + 5).coerceIn(10, 30) },
            help = lowHelp)
        Stepper(stringResource(R.string.tires_high_temp), tempText(repo.highTempC, fahrenheit),
            { repo.highTempC = (repo.highTempC - 5f).coerceIn(50f, 100f) },
            { repo.highTempC = (repo.highTempC + 5f).coerceIn(50f, 100f) })
        SwitchRow(stringResource(R.string.tires_show_spare), repo.showSpare, { repo.showSpare = it })
        SwitchRow(stringResource(R.string.tires_alerts), repo.alertsEnabled, { repo.alertsEnabled = it }, stringResource(R.string.tires_alerts_help))
        // Sin avisos no hay aviso en el inicio: el interruptor se cambia por la nota.
        if (repo.alertsEnabled) SwitchRow(stringResource(R.string.tires_banner), d.prefs.tiresBanner, { d.prefs.tiresBanner = it })
        else Label(stringResource(R.string.tires_alerts_off_note), Typography.small, LocalColors.current.warning)
    }
}

/**
 * El ajuste de cada llanta, con lo que marca ahora (ya ajustado): se mide con el manómetro y se
 * sube o baja hasta que coincida. Cada sensor puede venir corrido distinto y su ajuste va con él;
 * una llanta sin sensor no se puede ajustar.
 */
@Composable
private fun CalibrateCard(repo: TpmsRepository) {
    val unit = repo.unit
    val locale = currentLocale()
    val wheels = if (repo.showSpare) Wheel.entries else Wheel.ROAD
    SettingsCard(stringResource(R.string.tires_offset), stringResource(R.string.tires_offset_help)) {
        // Varios sensores Bluetooth dan presión absoluta y restan la del mar: en lugares altos marcan de menos.
        if (repo.source == TpmsSource.BLE) Label(stringResource(R.string.tires_offset_altitude), Typography.small, LocalColors.current.textMuted)
        for (w in wheels) {
            val kpa = repo.wheelReading(w)?.takeUnless { it.lost }?.pressureKpa
            val offset = repo.offsetKpa(w)
            val hasSensor = repo.assignment[w] != null
            Stepper(stringResource(wheelName(w)), if (hasSensor) offsetText(offset, unit, locale) else "—",
                { repo.setOffsetKpa(w, stepKpa(offset, unit, -1, OFFSET_KPA)) },
                { repo.setOffsetKpa(w, stepKpa(offset, unit, 1, OFFSET_KPA)) },
                help = kpa?.let { stringResource(R.string.tires_reads, unit.text(it, locale)) } ?: stringResource(R.string.tire_none),
                enabled = hasSensor)
        }
    }
}

private val RECOMMENDED_KPA = 100f..450f
private val OFFSET_KPA = -35f..35f

/** El paso de cada unidad: 1 psi, 0.1 bar o 5 kPa. */
private fun PressureUnit.step() = when (this) {
    PressureUnit.PSI -> 1f
    PressureUnit.BAR -> 0.1f
    PressureUnit.KPA -> 5f
}

/** Sube o baja un paso redondeando a la unidad que se ve (sin arrastrar decimales de kPa ni un "−0"). */
internal fun stepKpa(kpa: Float, unit: PressureUnit, dir: Int, range: ClosedFloatingPointRange<Float>): Float {
    val step = unit.step()
    val shown = (unit.fromKpa(kpa) / step).roundToInt() + dir
    return unit.toKpa(shown * step).coerceIn(range).let { if (abs(it) < 0.01f) 0f else it }
}

/** El ajuste con su signo ("+2 psi", "−0.1 bar"; sin signo si es cero a la vista). */
internal fun offsetText(kpa: Float, unit: PressureUnit, locale: Locale = Locale.US): String {
    val text = unit.text(abs(kpa), locale)
    val shown = unit.format(abs(kpa)).any { it in '1'..'9' }
    val sign = if (!shown) "" else if (kpa > 0) "+" else "−"
    return sign + text
}

/** Un valor con − y + a la derecha. */
@Composable
private fun Stepper(title: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit, help: String? = null, enabled: Boolean = true) {
    val c = LocalColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Label(title, Typography.body)
            if (help != null) Label(help, Typography.small, c.textMuted)
        }
        // Deshabilitado: los botones se ven apagados y no hacen nada.
        val buttons = if (enabled) Modifier else Modifier.alpha(DISABLED_ALPHA)
        IconButton(Icons.minus, if (enabled) onMinus else ({}), buttons, size = 44.dp, background = c.surfaceHigh)
        Label(value, Typography.subtitle.copy(textAlign = TextAlign.Center), maxLines = 1, modifier = Modifier.width(92.dp))
        IconButton(Icons.plus, if (enabled) onPlus else ({}), buttons, size = 44.dp, background = c.surfaceHigh)
    }
}

private const val DISABLED_ALPHA = 0.35f

/** Qué se ve en el inicio: pastillas sobre el auto, qué hace tocarlo y el widget (el aviso va en Presión y avisos). */
@Composable
private fun HomeTiresCard(d: Dependencies) {
    val c = LocalColors.current
    SettingsCard(stringResource(R.string.tires_on_home), stringResource(R.string.tires_tile_hint)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Label(stringResource(R.string.tires_on_car), Typography.body)
                Label(stringResource(R.string.tires_on_car_help), Typography.small, c.textMuted)
            }
            Segmented(
                listOf(
                    TiresOnCar.WHEELS to stringResource(R.string.tires_on_car_wheels),
                    TiresOnCar.CORNERS to stringResource(R.string.tires_on_car_corners),
                    TiresOnCar.OFF to stringResource(R.string.tires_on_car_off),
                ),
                d.prefs.tiresOnCar,
                { on ->
                    d.prefs.tiresOnCar = on
                    // Aquí se decide para todos: la carretera y el auto 3D dejan su propio ajuste.
                    d.dashboard.changeDataEverywhere {
                        if ((it.type == TileTypes.ROAD || it.type == TileTypes.CAR) && TileTires.parse(it.data) != null) {
                            if (it.type == TileTypes.ROAD) RoadLook.parse(it.data).copy(tires = null).encode() else ""
                        } else null
                    }
                },
            )
        }
        // En las ruedas necesita saber dónde pisa cada una (se baja aparte); mientras, van en las esquinas.
        val car = d.cars.pkg(d.prefs.carModel)
        if (d.prefs.tiresOnCar == TiresOnCar.WHEELS && car != null && car.wheels == null && car.id !in d.cars.withoutWheels) {
            Label(stringResource(R.string.tires_on_car_waiting), Typography.small, c.textMuted)
        }
        // Tocar el auto 3D: la cámara sube a ver las llantas, o solo crece como siempre.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(stringResource(R.string.tires_car_tap), Typography.body, modifier = Modifier.weight(1f).padding(end = 12.dp))
            Segmented(
                listOf(CarTap.BIG to stringResource(R.string.tires_car_tap_big), CarTap.TIRES to stringResource(R.string.tires_car_tap_tires)),
                d.prefs.carTap,
                { d.prefs.carTap = it },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Label(stringResource(R.string.tires_tile), Typography.body)
                Label(stringResource(R.string.tile_tires_help), Typography.small, c.textMuted)
            }
            if (d.dashboard.allTiles.any { it.type == TileTypes.TIRES }) Label(stringResource(R.string.on_your_home), Typography.small, c.accent)
            else Button(stringResource(R.string.add), { d.dashboard.addTile(TileTypes.TIRES) })
        }
    }
}

/** Lo que llega del receptor o de sensores que no se reconocen, para mandárnoslo. */
@Composable
private fun DiagnosticsCard(repo: TpmsRepository) {
    val c = LocalColors.current
    val context = LocalContext.current
    SettingsCard(stringResource(R.string.tires_diagnostics), stringResource(R.string.tires_diagnostics_help)) {
        val lines = when (repo.source) {
            TpmsSource.USB -> repo.usbLog.toList()
            TpmsSource.BLE -> repo.unknownAdverts.map { a ->
                listOfNotNull(
                    a.address, a.name, a.companyId?.let { "0x%04X".format(it) }, a.manufacturerHex,
                    a.serviceUuids.takeIf { it.isNotEmpty() }?.joinToString(","), a.serviceDataHex, "${a.rssi} dBm",
                ).joinToString(" ")
            }
            else -> emptyList()
        }
        if (lines.isEmpty()) return@SettingsCard Label(stringResource(R.string.tires_diagnostics_empty), Typography.small, c.textMuted)
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.inner)).background(c.surfaceHigh).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for (l in lines.takeLast(40)) Label(l, Typography.small.copy(fontFamily = FontFamily.Monospace), c.textMuted, maxLines = 2)
        }
        Button(stringResource(R.string.tires_copy), {
            val text = "LiteDash ${BuildConfig.VERSION_NAME} · ${repo.source?.key}\n" + lines.joinToString("\n")
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("LiteDash", text))
        })
    }
}
