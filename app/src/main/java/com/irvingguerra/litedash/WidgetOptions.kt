package com.irvingguerra.litedash

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.LocalPlainCard
import com.irvingguerra.litedash.core.ui.SwitchRow
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.feature.apps.App
import com.irvingguerra.litedash.feature.dashboard.Tile
import com.irvingguerra.litedash.feature.dashboard.TileTypes

/**
 * Las opciones de un widget en su [Tile.data]: "clave=off" apaga una que de fábrica está prendida (lo de
 * fábrica no se escribe: los acomodos de antes se ven igual). Los widgets que abren una app (App, Teléfono,
 * Android Auto) llevan además la app al principio, sin "=".
 */
object WidgetFlags {
    const val WEATHER = "weather"
    const val DATE = "date"
    const val GREETING = "greeting"
    const val DAYS = "days"
    const val RANGE = "range"
    const val TIME = "time"
    const val AVERAGE = "average"
    const val HEADING = "heading"
    const val CAR = "car"
    const val ART = "art"
    const val SKIP = "skip"
    const val NAME = "name"

    fun on(data: String, key: String) = data.split(';').none { it == "$key=off" }

    fun set(data: String, key: String, on: Boolean): String =
        (data.split(';').filter { it.isNotEmpty() && !it.startsWith("$key=") } + if (on) emptyList() else listOf("$key=off")).joinToString(";")
}

/** La app que eligió el widget (lo de antes del primer ";", si no es una opción), o null. */
fun appKeyOf(data: String): String? = data.substringBefore(';').takeIf { it.isNotEmpty() && '=' !in it }

/** [data] con la app [key] (null = ninguna, la de fábrica), sin tocar sus opciones. */
fun withAppKey(data: String, key: String?): String =
    (listOfNotNull(key) + data.split(';').filter { '=' in it }).joinToString(";")

/** El clima que lleva el reloj [data]: null si se apagó o si ya hay un widget Clima a la vista. */
fun clockWeather(d: Dependencies, data: String): WeatherRepository? =
    d.weather.takeIf { WidgetFlags.on(data, WidgetFlags.WEATHER) && d.dashboard.shown.none { it.type == TileTypes.WEATHER } }

/** Los tipos que se personalizan aquí (los demás tienen su propio panel: medidores, mapa, imagen, auto). */
val WIDGET_OPTIONS = setOf(
    TileTypes.CLOCK, TileTypes.WEATHER, TileTypes.TRIP, TileTypes.TIRES, TileTypes.WORKSHOP, TileTypes.MUSIC,
    TileTypes.RADIO, TileTypes.APP, TileTypes.PHONE, TileTypes.PROJECTION,
)

/**
 * Personalizar un widget ([tileId]): a un lado (o arriba) el widget mismo, en vivo y a su tamaño (achicado
 * si no cabe; no se puede tocar), y al otro sus opciones. Cada cambio se guarda al momento. [cell] es lo que
 * mide una celda del inicio; [onPickApp] abre el selector de app para este widget.
 */
@Composable
fun CustomizeWidgetPanel(
    d: Dependencies,
    tileId: String,
    apps: List<App>,
    cell: DpSize,
    close: () -> Unit,
    onPickApp: () -> Unit,
    preview: @Composable (Tile) -> Unit,
) {
    val tile = d.dashboard.tiles.firstOrNull { it.id == tileId }
    if (tile == null) {
        // Cerrar escribe estado: fuera de la composición.
        LaunchedEffect(Unit) { close() }
        return
    }
    val name = stringResource(TileSpecs.first { it.type == tile.type }.name).lowercase(currentLocale())
    PanelScaffold(stringResource(R.string.widget_customize, name), close) {
        WidgetPanelLayout(
            preview = { m -> WidgetPreview(tile, cell, m) { preview(tile) } },
            options = { m -> Column(m.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) { WidgetOptionRows(d, tile, apps, onPickApp) } },
        )
    }
}

/** Vista previa y opciones: lado a lado en pantallas acostadas; paradas, una sobre otra. */
@Composable
private fun ColumnScope.WidgetPanelLayout(preview: @Composable (Modifier) -> Unit, options: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
        if (maxWidth > maxHeight) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            preview(Modifier.weight(1f).fillMaxHeight().padding(bottom = 16.dp))
            options(Modifier.weight(1f))
        } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            preview(Modifier.fillMaxWidth().weight(1f))
            options(Modifier.weight(1f))
        }
    }
}

/**
 * El widget a su tamaño real en el inicio ([cell] × celdas): así se ve igual que allá (cada widget cambia
 * según lo que mide). Si no cabe, se achica entero. Los toques no llegan a él.
 */
@Composable
private fun WidgetPreview(tile: Tile, cell: DpSize, modifier: Modifier, content: @Composable () -> Unit) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val width = cell.width * tile.width
        val height = cell.height * tile.height
        val scale = minOf(1f, maxWidth / width, maxHeight / height)
        Box(
            Modifier
                .requiredSize(width, height)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .padding(6.dp),
        ) {
            // Sin semántica: con TalkBack sus botones (play, abrir app) tampoco se pueden activar.
            Box(Modifier.clearAndSetSemantics {}) {
                CompositionLocalProvider(LocalPlainCard provides tile.plain) { content() }
            }
            // Encima: se traga los toques (la vista previa no abre ni reproduce nada).
            Box(
                Modifier.matchParentSize().pointerInput(Unit) {
                    awaitEachGesture {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                    }
                },
            )
        }
    }
}

/** Una opción prendida o apagada del widget ([WidgetFlags]); se guarda al momento. */
@Composable
private fun FlagRow(d: Dependencies, tile: Tile, key: String, title: Int) {
    SwitchRow(stringResource(title), WidgetFlags.on(tile.data, key), { d.dashboard.setData(tile.id, WidgetFlags.set(tile.data, key, it)) })
}

/** Las opciones de cada tipo de widget. */
@Composable
private fun WidgetOptionRows(d: Dependencies, tile: Tile, apps: List<App>, onPickApp: () -> Unit) {
    val c = LocalColors.current
    val data = tile.data
    when (tile.type) {
        TileTypes.CLOCK -> {
            FlagRow(d, tile, WidgetFlags.WEATHER, R.string.clock_show_weather)
            // Con el widget Clima en el inicio, el reloj no lo repite aunque esté prendido.
            if (d.dashboard.shown.any { it.type == TileTypes.WEATHER }) Label(stringResource(R.string.clock_weather_in_widget), Typography.small, c.textMuted)
            FlagRow(d, tile, WidgetFlags.DATE, R.string.option_date)
            if (d.prefs.driverName != null) FlagRow(d, tile, WidgetFlags.GREETING, R.string.option_greeting)
        }
        TileTypes.WEATHER -> {
            FlagRow(d, tile, WidgetFlags.RANGE, R.string.option_range)
            FlagRow(d, tile, WidgetFlags.DAYS, R.string.option_days)
            Label(stringResource(R.string.option_days_help), Typography.small, c.textMuted)
        }
        TileTypes.TRIP -> {
            Label(stringResource(R.string.option_trip_help), Typography.small, c.textMuted)
            FlagRow(d, tile, WidgetFlags.TIME, R.string.trip_time)
            FlagRow(d, tile, WidgetFlags.AVERAGE, R.string.trip_average)
            FlagRow(d, tile, WidgetFlags.HEADING, R.string.trip_heading)
        }
        TileTypes.TIRES, TileTypes.WORKSHOP -> FlagRow(d, tile, WidgetFlags.CAR, R.string.option_car)
        TileTypes.MUSIC, TileTypes.RADIO -> {
            FlagRow(d, tile, WidgetFlags.ART, if (tile.type == TileTypes.RADIO) R.string.option_logo else R.string.option_artwork)
            FlagRow(d, tile, WidgetFlags.SKIP, R.string.option_skip)
        }
        TileTypes.APP -> {
            FlagRow(d, tile, WidgetFlags.NAME, R.string.option_app_name)
            Button(stringResource(R.string.option_change_app), onPickApp)
        }
        TileTypes.PHONE, TileTypes.PROJECTION -> {
            val chosen = appKeyOf(data)?.let { d.apps.find(apps, it) }
            val default = stringResource(if (tile.type == TileTypes.PHONE) R.string.option_opens_dialer else R.string.option_opens_auto)
            Label(stringResource(R.string.option_opens), Typography.label, c.textMuted)
            Label(chosen?.name ?: default, Typography.subtitle)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(stringResource(R.string.option_change_app), onPickApp)
                // También si la elegida ya no está instalada (sigue guardada y volvería al reinstalarla).
                if (appKeyOf(data) != null) Button(default, { d.dashboard.setData(tile.id, withAppKey(data, null)) })
            }
        }
    }
}
