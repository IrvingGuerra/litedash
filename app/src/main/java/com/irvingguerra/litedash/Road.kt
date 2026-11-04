package com.irvingguerra.litedash

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.core.vehicle.Signal
import com.irvingguerra.litedash.core.vehicle.watch
import com.irvingguerra.litedash.feature.car.CAR_ASPECT
import com.irvingguerra.litedash.feature.car.CarPackage
import com.irvingguerra.litedash.feature.car.CarStill
import com.irvingguerra.litedash.feature.car.REAR_TURN
import com.irvingguerra.litedash.feature.obd.ObdState
import kotlin.math.cos
import kotlin.math.sin

/** Metros de carretera entre el inicio de una raya del carril y la siguiente. */
private const val DASH_PERIOD_M = 12f

/** Metros de cada raya (el resto del periodo es el hueco). */
private const val DASH_M = 4f

/** Qué tan lejos de la cámara empieza a verse el piso (abajo de la pieza). */
private const val NEAR_M = 4f

/** Hasta dónde se dibujan rayas (más allá ya ni se distinguen en el horizonte). */
private const val FAR_M = 160f

/** Altura del horizonte (fracción del alto de la pieza). */
private const val HORIZON = 0.34f

/** A esta velocidad el efecto ya va completo (líneas de velocidad y orillas encendidas). */
private const val FULL_EFFECT_KMH = 110f

/** Líneas de velocidad que pasan a los lados. */
private const val STREAKS = 18

/**
 * Tu auto de espaldas en una carretera, como DuduAuto: las rayas de los carriles
 * avanzan con la velocidad (OBD o GPS) y arriba van la velocidad y los km del viaje.
 * Parado no se anima nada (no gasta). Un toque hace [onClick] (verla en grande).
 */
@Composable
fun RoadView(d: Dependencies, pkg: CarPackage?, onClick: () -> Unit, modifier: Modifier = Modifier, onReady: (() -> Unit)? = null) {
    val c = LocalColors.current
    // Con un adaptador ya guardado, se reconecta solo (como el velocímetro).
    val obdOn = rememberObd(d)?.state == ObdState.ON
    val (gps, ask) = rememberGpsPermission(d)
    // El GPS da los km del viaje (y la velocidad si no hay OBD) mientras la pieza se ve.
    if (gps) KeepTripRunning(d.trip)
    val kmh = d.bus.watch(Signal.SPEED)

    // Avance de las rayas en periodos (0..1) y de las líneas de velocidad: se leen solo al
    // dibujar, así la pieza no se recompone en cada cuadro. Solo corren en movimiento y,
    // al parar, lo que tarda el efecto en apagarse; ya en 0 no corre nada.
    var phase by remember { mutableFloatStateOf(0f) }
    var rush by remember { mutableFloatStateOf(0f) }
    // Qué tanto efecto lleva (0 parado, 1 a FULL_EFFECT_KMH); sube y baja suave.
    var effect by remember { mutableFloatStateOf(0f) }
    val speed by rememberUpdatedState(kmh ?: 0f)
    val moving = (kmh ?: 0f) > 1f
    LaunchedEffect(moving) {
        if (!moving && effect == 0f) return@LaunchedEffect
        var last = 0L
        while (moving || effect > 0.01f) withFrameNanos { t ->
            if (last != 0L) {
                val dt = (t - last) / 1e9f
                phase = (phase + speed / 3.6f * dt / DASH_PERIOD_M) % 1f
                val goal = (speed / FULL_EFFECT_KMH).coerceIn(0f, 1f)
                effect += (goal - effect) * (dt * 2f).coerceAtMost(1f)
                rush = (rush + dt * (0.4f + 1.6f * effect)) % 1f
            }
            last = t
        }
        // Ya casi apagado: se deja en 0 exacto para que no quede un brillo residual.
        effect = 0f
    }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick),
    ) {
        val height = maxHeight
        // Sin tarjeta: la carretera se desvanece hacia las cuatro orillas y se funde con el fondo.
        // Los degradados que solo dependen del tamaño y los colores se arman una vez por tamaño.
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithCache {
                    val paint = RoadPaint(size, c.surfaceHigh, c.text)
                    onDrawBehind {
                        drawRoad(paint, c.textMuted, c.text, phase, effect, rush)
                        drawRect(FADE_X, blendMode = BlendMode.DstIn)
                        drawRect(FADE_Y, blendMode = BlendMode.DstIn)
                    }
                },
        )

        // El auto de espaldas en el carril de en medio, con la defensa cerca del borde de abajo.
        val box = height * 0.55f
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .offset(y = -height * 0.03f)
                .width(box * CAR_ASPECT)
                .height(box),
        ) {
            if (pkg != null) {
                CarStill(d.cars, pkg, d.prefs.carColor, Modifier.fillMaxSize(), turn = REAR_TURN, onReady = onReady)
            } else {
                ReadyNow(onReady)
                Canvas(Modifier.fillMaxSize()) { drawRear(d.prefs.carColor) }
            }
        }

        RoadNumbers(d, kmh, height.value, Modifier.fillMaxWidth().padding(16.dp))
        if (!obdOn && !gps) {
            Box(Modifier.align(Alignment.TopCenter).padding(top = 16.dp)) {
                Button(stringResource(R.string.tap_to_enable), ask, primary = true)
            }
        }
    }
}

/** La velocidad a la izquierda y los km del viaje a la derecha, del tamaño de la pieza. */
@Composable
private fun RoadNumbers(d: Dependencies, kmh: Float?, heightDp: Float, modifier: Modifier) {
    val c = LocalColors.current
    val miles = d.prefs.units.miles
    val locale = currentLocale()
    val speed = kmh?.toMiles(miles)
    val distance = d.trip.meters.metersToDistance(miles)
    val big = (heightDp * 0.16f).coerceIn(30f, 96f)
    Row(modifier) {
        Column(Modifier.weight(1f)) {
            Label(String.format(locale, "%.0f", speed ?: 0f), Typography.clock.copy(fontSize = big.sp), maxLines = 1)
            Label(stringResource(if (miles) R.string.unit_mph else R.string.unit_kmh), Typography.small, c.textMuted, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End) {
            Label(
                String.format(locale, "%.1f %s", distance, if (miles) "mi" else "km"),
                Typography.clock.copy(fontSize = (big * 0.45f).coerceAtLeast(20f).sp),
                maxLines = 1,
            )
            Label(stringResource(R.string.road_trip), Typography.small, c.textMuted, maxLines = 1)
        }
    }
}

/** Las máscaras que desvanecen la carretera hacia las cuatro orillas (no dependen de nada). */
private val FADE_X = Brush.horizontalGradient(0f to Color.Transparent, 0.12f to Color.Black, 0.88f to Color.Black, 1f to Color.Transparent)
private val FADE_Y = Brush.verticalGradient(0f to Color.Transparent, 0.2f to Color.Black, 0.8f to Color.Black, 1f to Color.Transparent)

/** Los dos lados de la carretera (izquierda y derecha), sin armar una lista en cada cuadro. */
private val SIDES = floatArrayOf(-1f, 1f)

/**
 * Lo que la carretera reutiliza en cada cuadro: los degradados que solo dependen del
 * tamaño y los colores, y un [path] que se limpia antes de cada figura.
 */
private class RoadPaint(size: Size, asphalt: Color, glow: Color) {
    val asphalt = Brush.verticalGradient(0f to asphalt.copy(alpha = 0f), 1f to asphalt, startY = size.height * HORIZON, endY = size.height)
    /** El brillo del horizonte a toda intensidad; se atenúa con el efecto al dibujarlo. */
    val glow = Brush.radialGradient(listOf(glow.copy(alpha = 0.16f), Color.Transparent), Offset(size.width / 2, size.height * HORIZON), size.width * 0.45f)
    val path = Path()
}

/**
 * La carretera en perspectiva: el asfalto que se desvanece hacia el horizonte, las
 * orillas y las rayas de tres carriles. Un punto a [z] metros de la cámara cae en
 * y = horizonte + (abajo − horizonte) · NEAR_M / z, y el ancho se reduce igual.
 * Con [effect] (0–1, sube con la velocidad) el horizonte y las orillas brillan (del
 * [glow], neutro: sin el ámbar) y pasan líneas de velocidad a los lados ([rush] las mueve).
 */
private fun DrawScope.drawRoad(paint: RoadPaint, lines: Color, glow: Color, phase: Float, effect: Float, rush: Float) {
    val w = size.width
    val h = size.height
    val horizon = h * HORIZON
    val center = w / 2
    // Media carretera a la altura de abajo: casi todo el ancho.
    val halfNear = w * 0.62f
    fun y(z: Float) = horizon + (h - horizon) * NEAR_M / z
    fun half(z: Float) = halfNear * NEAR_M / z

    val path = paint.path
    // El brillo del horizonte, hacia donde vas.
    if (effect > 0.01f) drawCircle(paint.glow, w * 0.45f, Offset(center, horizon), alpha = effect)
    path.reset()
    path.moveTo(center - halfNear, h)
    path.lineTo(center + halfNear, h)
    path.lineTo(center + 1f, horizon)
    path.lineTo(center - 1f, horizon)
    path.close()
    drawPath(path, paint.asphalt)

    // Orillas: líneas continuas que se pierden en el horizonte.
    val edgeColor = lerp(lines.copy(alpha = 0.55f), glow.copy(alpha = 0.85f), effect)
    val edge = Brush.verticalGradient(0f to edgeColor.copy(alpha = 0f), 1f to edgeColor, startY = horizon, endY = h)
    val edgeWidth = w * 0.006f
    for (side in SIDES) {
        path.reset()
        path.moveTo(center + side * (halfNear * 0.96f), h)
        path.lineTo(center + side * (halfNear * 0.96f - edgeWidth * 4), h)
        path.lineTo(center, horizon)
        path.close()
        drawPath(path, edge)
    }

    // Rayas de los carriles (a un tercio de cada lado) que vienen hacia ti.
    val lane = 1f / 3f
    var z = NEAR_M - phase * DASH_PERIOD_M
    while (z < FAR_M) {
        val near = maxOf(z, NEAR_M * 0.98f)
        val far = z + DASH_M
        if (far > near) {
            // Más tenues a lo lejos.
            val alpha = (0.6f * (1f - far / FAR_M)).coerceIn(0f, 0.6f)
            for (side in SIDES) {
                val x0 = center + side * half(near) * lane
                val x1 = center + side * half(far) * lane
                val t0 = half(near) * 0.012f + 1f
                val t1 = half(far) * 0.012f + 0.5f
                path.reset()
                path.moveTo(x0 - t0, y(near))
                path.lineTo(x0 + t0, y(near))
                path.lineTo(x1 + t1, y(far))
                path.lineTo(x1 - t1, y(far))
                path.close()
                drawPath(path, lines.copy(alpha = alpha))
            }
        }
        z += DASH_PERIOD_M
    }

    // Líneas de velocidad: salen del horizonte hacia las orillas de la pieza, fuera del
    // asfalto, y se alargan conforme se acercan.
    if (effect > 0.02f) {
        val vanish = Offset(center, horizon)
        for (i in 0 until STREAKS) {
            // Ángulo y desfase fijos por línea (parecen al azar, no parpadean).
            val side = if (i % 2 == 0) -1f else 1f
            val spread = ((i * 0.618f) % 1f)
            // Entre 22° arriba y 18° abajo del horizonte: a los lados, sin cruzar el asfalto.
            val angle = Math.toRadians((-22f + spread * 40f).toDouble())
            val t = (rush + i * 0.37f) % 1f
            val reach = w * 0.75f
            val start = reach * t * t
            val length = reach * (0.05f + 0.22f * t) * effect
            val dir = Offset(side * cos(angle).toFloat(), sin(angle).toFloat())
            val a = vanish + dir * start
            val b = vanish + dir * (start + length)
            drawLine(lines.copy(alpha = 0.45f * effect * t), a, b, strokeWidth = (1f + 2.5f * t) * density, cap = StrokeCap.Round)
        }
    }
}

/** Sin modelo del catálogo: un auto de espaldas sencillo, del color del tuyo. */
private fun DrawScope.drawRear(color: Color) {
    val w = size.width
    val h = size.height
    val bodyW = w * 0.34f
    val left = (w - bodyW) / 2
    val wheel = Color(0xFF121417)
    // Llantas, carrocería, cristal trasero y calaveras.
    drawRoundRect(wheel, Offset(left + bodyW * 0.04f, h * 0.70f), Size(bodyW * 0.16f, h * 0.24f), CornerRadius(h * 0.03f))
    drawRoundRect(wheel, Offset(left + bodyW * 0.80f, h * 0.70f), Size(bodyW * 0.16f, h * 0.24f), CornerRadius(h * 0.03f))
    drawRoundRect(color, Offset(left + bodyW * 0.08f, h * 0.22f), Size(bodyW * 0.84f, h * 0.36f), CornerRadius(h * 0.10f))
    drawRoundRect(color, Offset(left, h * 0.46f), Size(bodyW, h * 0.36f), CornerRadius(h * 0.07f))
    drawRoundRect(Color(0xFF1B1F24), Offset(left + bodyW * 0.17f, h * 0.27f), Size(bodyW * 0.66f, h * 0.19f), CornerRadius(h * 0.05f))
    val light = Color(0xFFE5484D)
    drawRoundRect(light, Offset(left + bodyW * 0.03f, h * 0.53f), Size(bodyW * 0.24f, h * 0.07f), CornerRadius(h * 0.03f))
    drawRoundRect(light, Offset(left + bodyW * 0.73f, h * 0.53f), Size(bodyW * 0.24f, h * 0.07f), CornerRadius(h * 0.03f))
    drawRoundRect(Color.Black.copy(alpha = 0.25f), Offset(left + bodyW * 0.30f, h * 0.66f), Size(bodyW * 0.40f, h * 0.08f), CornerRadius(h * 0.02f))
}
