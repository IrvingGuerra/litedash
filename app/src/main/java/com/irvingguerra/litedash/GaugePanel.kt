package com.irvingguerra.litedash

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.LocalPlainCard
import com.irvingguerra.litedash.core.ui.Segmented
import com.irvingguerra.litedash.core.ui.SwitchRow
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.feature.dashboard.TileTypes
import kotlinx.coroutines.delay

/** Valores de muestra para la vista previa: sube, baja y llega a la zona roja. */
private val DEMO = listOf(0.18f, 0.52f, 0.86f, 0.34f, 0.97f, 0.62f)

/**
 * Personalizar un velocímetro, un medidor o la velocidad de la carretera ([tileId]): a la
 * izquierda se ve cómo queda (con valores de muestra que se mueven) y a la derecha el estilo,
 * el color, hasta dónde llega, marcas, números y zona roja. En la carretera, primero cómo va
 * la velocidad (velocímetro, solo el número u oculta). Cada cambio se guarda al momento.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CustomizeGaugePanel(d: Dependencies, tileId: String, close: () -> Unit, onPickSignal: () -> Unit) {
    val c = LocalColors.current
    val tile = d.dashboard.tiles.firstOrNull { it.id == tileId }
    if (tile == null) {
        // Cerrar escribe estado: fuera de la composición.
        LaunchedEffect(Unit) { close() }
        return
    }
    val road = tile.type == TileTypes.ROAD
    val roadLook = if (road) RoadLook.parse(tile.data) else null
    val speedometer = tile.type == TileTypes.SPEEDOMETER || road
    val signal = signalOf(tile.data)
    val spec = gaugeOf(signal)
    val look = GaugeLook.parse(tile.data)
    val units = d.prefs.units
    var applied by remember { mutableStateOf(false) }
    fun set(new: GaugeLook) {
        applied = false
        d.dashboard.setData(tileId, roadLook?.copy(gauge = new)?.encode() ?: new.encode(signal))
    }
    // Al cambiar de estilo o de aro, los que lucen con su degradado lo toman si seguía el color de fábrica.
    fun pickLook(new: GaugeLook) = set(new.copy(color = new.suggestedColor()?.takeIf { look.color == GaugeColor.ACCENT } ?: look.color))
    fun setRoad(new: RoadLook) {
        applied = false
        d.dashboard.setData(tileId, new.encode())
    }

    var demo by remember { mutableFloatStateOf(DEMO[0]) }
    LaunchedEffect(Unit) {
        var i = 0
        while (true) {
            delay(900)
            i = (i + 1) % DEMO.size
            demo = DEMO[i]
        }
    }

    PanelScaffold(stringResource(if (road) R.string.road_customize else R.string.gauge_customize), close) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val preview = @Composable { m: Modifier ->
                Box(m) {
                    // Con o sin tarjeta, como está en tu inicio.
                    CompositionLocalProvider(LocalPlainCard provides tile.plain) {
                        if (road) {
                            // La carretera misma, con la velocidad de muestra.
                            val demoKmh = demo * look.speedMax(miles = false)
                            // Con sus llantas si las lleva (las pastillas de muestra no abren nada).
                            RoadView(
                                d, d.cars.pkg(d.prefs.carModel), onClick = {}, look = roadLook ?: RoadLook(), demoKmh = demoKmh,
                                onTires = if (d.hasTpmsSource && showsTires(d, tile)) ({}) else null,
                            )
                        } else if (speedometer) {
                            val max = look.speedMax(units.miles).toFloat()
                            SpeedGaugeCard(demo * max, units.miles, "GPS", look, d.prefs.carColor, speedFront(d, look))
                        } else if (spec != null) {
                            SpecGaugeCard(spec, spec.min + (spec.max - spec.min) * demo, units, look, d.prefs.carColor)
                        }
                    }
                }
            }
            val options = @Composable { m: Modifier ->
                Column(m.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (roadLook != null) {
                        // Lo del viaje (como la pieza Viaje), cada dato con su interruptor.
                        Label(stringResource(R.string.tile_trip), Typography.label, c.textMuted)
                        for (item in RoadTrip.entries) {
                            SwitchRow(stringResource(item.title), item in roadLook.trip, { on ->
                                setRoad(roadLook.copy(trip = if (on) roadLook.trip + item else roadLook.trip - item))
                            })
                        }
                        // La presión de cada llanta junto a su rueda (solo con Llantas prendido).
                        if (d.hasTpmsSource) {
                            Label(stringResource(R.string.tile_tires), Typography.label, c.textMuted)
                            SwitchRow(stringResource(R.string.tires_show_pressure), showsTires(d, tile), { setRoad(roadLook.copy(tires = it)) })
                        }
                        Label(stringResource(R.string.road_speed), Typography.label, c.textMuted)
                        // Prendida o apagada como lo del viaje; prendida, número o velocímetro.
                        val shown = roadLook.speed != RoadSpeed.HIDDEN
                        SwitchRow(stringResource(R.string.road_speed_show), shown, { on ->
                            setRoad(roadLook.copy(speed = if (on) RoadSpeed.TEXT else RoadSpeed.HIDDEN))
                        })
                        if (shown) {
                            Segmented(
                                RoadSpeed.entries.mapNotNull { s -> s.title?.let { s to stringResource(it) } },
                                roadLook.speed,
                                { setRoad(roadLook.copy(speed = it)) },
                            )
                        }
                        // Solo el número u oculta: no hay velocímetro que personalizar.
                        if (roadLook.speed != RoadSpeed.GAUGE) return@Column
                    }
                    Label(stringResource(R.string.gauge_customize_help), Typography.small, c.textMuted)
                    Label(stringResource(R.string.gauge_style), Typography.label, c.textMuted)
                    Segmented(
                        GaugeStyle.entries.map { it to stringResource(it.title) },
                        look.style,
                        // La aguja se lee con números: se prenden solos la primera vez.
                        { pickLook(look.copy(style = it, numbers = look.numbers || it == GaugeStyle.NEEDLE)) },
                        Modifier.horizontalScroll(rememberScrollState()),
                    )
                    // El aro de la Esfera: liso, neón o cuadros.
                    if (look.style == GaugeStyle.DIAL) {
                        Label(stringResource(R.string.gauge_rim), Typography.label, c.textMuted)
                        Segmented(GaugeRim.entries.map { it to stringResource(it.title) }, look.rim, { pickLook(look.copy(rim = it)) })
                    }
                    Label(stringResource(R.string.gauge_color), Typography.label, c.textMuted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (color in GaugeColor.entries) Swatch(color, d.prefs.carColor, color == look.color) { set(look.copy(color = color)) }
                    }
                    Label(stringResource(look.color.title), Typography.small, c.textMuted)
                    if (speedometer) {
                        Label(stringResource(R.string.gauge_max), Typography.label, c.textMuted)
                        val unit = stringResource(if (units.miles) R.string.unit_mph else R.string.unit_kmh)
                        val choices = speedMaxOptions(units.miles)
                        Segmented(
                            choices.sorted().map { it to "$it $unit" },
                            look.speedMax(units.miles),
                            // Se guarda en km/h (al cambiar a mph se ve su pareja); el de fábrica no se guarda.
                            { set(look.copy(max = speedMaxToStore(it, units.miles))) },
                            Modifier.horizontalScroll(rememberScrollState()),
                        )
                    }
                    SwitchRow(stringResource(R.string.gauge_ticks), look.ticks, { set(look.copy(ticks = it)) })
                    SwitchRow(stringResource(R.string.gauge_numbers), look.numbers, { set(look.copy(numbers = it)) })
                    SwitchRow(stringResource(R.string.gauge_redline), look.redline, { set(look.copy(redline = it)) })
                    // Solo el velocímetro (no la carretera, que ya lleva el auto). "Tu auto", si hay uno instalado.
                    if (tile.type == TileTypes.SPEEDOMETER) {
                        Label(stringResource(R.string.gauge_front), Typography.label, c.textMuted)
                        val hasCar = d.cars.pkg(d.prefs.carModel) != null
                        Segmented(
                            GaugeFront.entries.filter { it != GaugeFront.CAR || hasCar || look.front == it }.map { it to stringResource(it.title) },
                            look.front,
                            { set(look.copy(front = it)) },
                        )
                        if (look.front == GaugeFront.IMAGE) {
                            ImageEditor(
                                d, tile.id, look.image, stringResource(R.string.gauge_front_image_empty),
                                placeholder = false,
                                onRemoved = { set(look.copy(image = "")) },
                            ) { set(look.copy(image = it)) }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (!speedometer) Button(stringResource(R.string.gauge_change_signal), onPickSignal)
                        Button(stringResource(R.string.gauge_apply_all), {
                            // Mismo estilo en todos; cada uno conserva su dato y, los velocímetros, hasta dónde llegan
                            // (la carretera, además, cómo va su velocidad). Una sola escritura del acomodo para todos.
                            d.dashboard.changeData(
                                d.dashboard.tiles
                                    .filter { it.type == TileTypes.SPEEDOMETER || it.type == TileTypes.GAUGE || it.type == TileTypes.ROAD }
                                    .associate {
                                        // Lo del frente solo va en los velocímetros y cada uno conserva su imagen (nunca dos
                                        // comparten un PNG). Desde un medidor o la carretera, su frente tampoco cambia.
                                        val current = GaugeLook.parse(it.data)
                                        val speedo = it.type == TileTypes.SPEEDOMETER
                                        val own = look.copy(
                                            max = current.max,
                                            front = when {
                                                !speedo -> GaugeFront.NONE
                                                tile.type == TileTypes.SPEEDOMETER -> look.front
                                                else -> current.front
                                            },
                                            image = if (speedo) current.image else "",
                                        )
                                        it.id to if (it.type == TileTypes.ROAD) RoadLook.parse(it.data).copy(gauge = own).encode() else own.encode(signalOf(it.data))
                                    },
                            )
                            applied = true
                        })
                    }
                    if (applied) Label(stringResource(R.string.gauge_applied), Typography.small, c.accent)
                    Box(Modifier.height(12.dp))
                }
            }
            val previewHeight = maxHeight * 0.4f
            if (maxWidth > maxHeight) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    preview(Modifier.weight(1f).fillMaxHeight().padding(bottom = 16.dp))
                    options(Modifier.weight(1f))
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    preview(Modifier.fillMaxWidth().height(previewHeight))
                    options(Modifier.weight(1f))
                }
            }
        }
    }
}

/** Un círculo de color para elegir; el dinámico va en degradado y el del auto, de su color. */
@Composable
private fun Swatch(color: GaugeColor, car: Color, selected: Boolean, onClick: () -> Unit) {
    val c = LocalColors.current
    val ramp = c.ramp(color, car)
    val brush = when {
        color == GaugeColor.DYNAMIC -> Brush.sweepGradient(ramp + ramp.first())
        ramp.size == 1 -> SolidColor(ramp[0])
        else -> Brush.linearGradient(ramp)
    }
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .border(if (selected) 3.dp else 1.dp, if (selected) c.text else c.line, CircleShape)
            .clickable(onClick = onClick)
            .padding(6.dp)
            .clip(CircleShape)
            .background(brush),
    )
}
