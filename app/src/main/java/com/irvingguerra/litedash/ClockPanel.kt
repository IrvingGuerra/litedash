package com.irvingguerra.litedash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.SwitchRow
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.feature.dashboard.TileTypes

/** Personalizar el reloj ([tileId]): si lleva el clima ([ClockWeather]). */
@Composable
fun CustomizeClockPanel(d: Dependencies, tileId: String, close: () -> Unit) {
    val c = LocalColors.current
    val tile = d.dashboard.tiles.firstOrNull { it.id == tileId }
    if (tile == null) {
        // Cerrar escribe estado: fuera de la composición.
        LaunchedEffect(Unit) { close() }
        return
    }
    PanelScaffold(stringResource(R.string.clock_customize), close) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SwitchRow(stringResource(R.string.clock_show_weather), ClockWeather.parse(tile.data), { d.dashboard.setData(tileId, ClockWeather.encode(it)) })
            // Con el widget Clima en el inicio, el reloj no lo repite aunque esté prendido.
            if (d.dashboard.shown.any { it.type == TileTypes.WEATHER }) {
                Label(stringResource(R.string.clock_weather_in_widget), Typography.small, c.textMuted)
            }
        }
    }
}

/** El clima que lleva el reloj [data]: null si se apagó o si ya hay un widget Clima a la vista. */
fun clockWeather(d: Dependencies, data: String): WeatherRepository? =
    d.weather.takeIf { ClockWeather.parse(data) && d.dashboard.shown.none { it.type == TileTypes.WEATHER } }
