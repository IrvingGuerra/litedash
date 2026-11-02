package com.irvingguerra.litedash

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
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
import com.irvingguerra.litedash.feature.obd.ObdState
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
    val c = LocalColors.current
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
    // El arco se anima y se lee solo al dibujar: la pieza no se recompone en cada cuadro.
    val shown = remember { Animatable(0f) }
    LaunchedEffect(speed, max) { shown.animateTo((speed ?: 0f).coerceIn(0f, max) / max, tween(250)) }
    val locale = LocalConfiguration.current.locales[0]
    Card(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
            val side = minOf(maxWidth, maxHeight * 1.15f)
            val big = side > 200.dp
            Canvas(Modifier.fillMaxSize()) {
                val stroke = (size.minDimension * 0.07f).coerceIn(6f, 22f)
                val radius = minOf(size.width, size.height * 1.15f) / 2 - stroke
                val center = Offset(size.width / 2, size.height / 2 + radius * 0.12f)
                val topLeft = Offset(center.x - radius, center.y - radius)
                val arc = Size(radius * 2, radius * 2)
                drawArc(c.surfaceHigh, START_ANGLE, SWEEP, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                if (shown.value > 0.002f) drawArc(c.accent, START_ANGLE, SWEEP * shown.value, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                // Marcas cada 20 km/h (10 mph), discretas, por dentro del arco.
                val steps = (max / if (imperial) 10f else 20f).toInt()
                for (i in 0..steps) {
                    val a = Math.toRadians((START_ANGLE + SWEEP * i / steps).toDouble())
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
                Label(
                    speed?.let { String.format(locale, "%.0f", it) } ?: "—",
                    Typography.clock.copy(fontSize = (side.value * 0.3f).coerceIn(28f, 110f).sp),
                    maxLines = 1,
                )
                Label(stringResource(if (imperial) R.string.unit_mph else R.string.unit_kmh), Typography.small, c.textMuted, maxLines = 1)
                if (big) {
                    val source = when (d.bus.source(Signal.SPEED)) {
                        Source.OBD -> "OBD"
                        Source.GPS -> "GPS"
                        Source.CAN -> "CAN"
                        null -> ""
                    }
                    Box(Modifier.padding(top = 6.dp)) { Label(source, Typography.label, c.accent, maxLines = 1) }
                }
            }
        }
    }
}
