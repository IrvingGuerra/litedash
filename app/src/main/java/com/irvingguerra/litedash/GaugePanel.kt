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
import androidx.compose.ui.platform.LocalConfiguration
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
 * Personalizar un velocímetro o un medidor ([tileId]): a la izquierda se ve cómo queda
 * (con valores de muestra que se mueven) y a la derecha el estilo, el color, hasta
 * dónde llega, marcas, números y zona roja. Cada cambio se guarda al momento.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CustomizeGaugePanel(d: Dependencies, tileId: String, close: () -> Unit, onPickSignal: () -> Unit) {
    val c = LocalColors.current
    val tile = d.dashboard.tiles.firstOrNull { it.id == tileId } ?: return close()
    val speedometer = tile.type == TileTypes.SPEEDOMETER
    val signal = signalOf(tile.data)
    val spec = gaugeOf(signal)
    val look = GaugeLook.parse(tile.data)
    val imperial = imperialUnits(d.prefs.language)
    val locale = LocalConfiguration.current.locales[0]
    var applied by remember { mutableStateOf(false) }
    fun set(new: GaugeLook) {
        applied = false
        d.dashboard.setData(tileId, new.encode(signal))
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

    PanelScaffold(stringResource(R.string.gauge_customize), close) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val preview = @Composable { m: Modifier ->
                Box(m) {
                    // Con o sin tarjeta, como está en tu inicio.
                    CompositionLocalProvider(LocalPlainCard provides tile.plain) {
                        if (speedometer) {
                            val max = (look.max ?: speedMaxOptions(imperial).first()).toFloat()
                            val ticks = ticksFor(max, imperial)
                            GaugeCard(
                                number = (demo * max).toInt().toString(),
                                unit = stringResource(if (imperial) R.string.unit_mph else R.string.unit_kmh),
                                tag = "GPS",
                                fraction = demo,
                                ticks = ticks,
                                look = look,
                                carColor = d.prefs.carColor,
                                labels = { i -> (max * i / ticks).toInt().toString() },
                            )
                        } else if (spec != null) {
                            val value = spec.min + (spec.max - spec.min) * demo
                            GaugeCard(
                                number = gaugeText(spec, value, imperial, locale),
                                unit = stringResource(spec.unit(imperial)),
                                tag = spec.tag?.let { stringResource(it) } ?: "",
                                fraction = demo,
                                ticks = spec.ticks,
                                look = look,
                                carColor = d.prefs.carColor,
                                labels = { spec.label(it, imperial) },
                                redFrom = spec.redFrom,
                                warning = spec.warning(value),
                            )
                        }
                    }
                }
            }
            val options = @Composable { m: Modifier ->
                Column(m.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Label(stringResource(R.string.gauge_customize_help), Typography.small, c.textMuted)
                    Label(stringResource(R.string.gauge_style), Typography.label, c.textMuted)
                    Segmented(
                        GaugeStyle.entries.map { it to stringResource(it.title) },
                        look.style,
                        // La aguja se lee con números: se prenden solos la primera vez.
                        {
                            set(look.copy(
                                style = it,
                                numbers = look.numbers || it == GaugeStyle.NEEDLE,
                                // Los estilos con degradado lucen con el suyo si seguía el ámbar de fábrica.
                                color = it.suggestedColor()?.takeIf { look.color == GaugeColor.ACCENT } ?: look.color,
                            ))
                        },
                        Modifier.horizontalScroll(rememberScrollState()),
                    )
                    Label(stringResource(R.string.gauge_color), Typography.label, c.textMuted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (color in GaugeColor.entries) Swatch(color, d.prefs.carColor, color == look.color) { set(look.copy(color = color)) }
                    }
                    Label(stringResource(look.color.title), Typography.small, c.textMuted)
                    if (speedometer) {
                        Label(stringResource(R.string.gauge_max), Typography.label, c.textMuted)
                        val unit = stringResource(if (imperial) R.string.unit_mph else R.string.unit_kmh)
                        val choices = speedMaxOptions(imperial)
                        Segmented(
                            choices.sorted().map { it to "$it $unit" },
                            look.max ?: choices.first(),
                            // El de fábrica no se guarda (así se lee igual en km/h y en mph).
                            { set(look.copy(max = it.takeIf { v -> v != choices.first() })) },
                            Modifier.horizontalScroll(rememberScrollState()),
                        )
                    }
                    SwitchRow(stringResource(R.string.gauge_ticks), look.ticks, { set(look.copy(ticks = it)) })
                    SwitchRow(stringResource(R.string.gauge_numbers), look.numbers, { set(look.copy(numbers = it)) })
                    SwitchRow(stringResource(R.string.gauge_redline), look.redline, { set(look.copy(redline = it)) })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (!speedometer) Button(stringResource(R.string.gauge_change_signal), onPickSignal)
                        Button(stringResource(R.string.gauge_apply_all), {
                            // Mismo estilo en todos; cada uno conserva su dato y, los velocímetros, hasta dónde llegan.
                            for (t in d.dashboard.tiles) {
                                if (t.type != TileTypes.SPEEDOMETER && t.type != TileTypes.GAUGE) continue
                                val own = GaugeLook.parse(t.data)
                                d.dashboard.setData(t.id, look.copy(max = own.max).encode(signalOf(t.data)))
                            }
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
        ramp.size == 1 -> Brush.linearGradient(listOf(ramp[0], ramp[0]))
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
