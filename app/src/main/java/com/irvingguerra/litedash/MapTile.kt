package com.irvingguerra.litedash

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Segmented
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.core.vehicle.Signal
import com.irvingguerra.litedash.core.vehicle.watch
import com.irvingguerra.litedash.feature.map.MapDetail
import com.irvingguerra.litedash.feature.map.MapLook
import com.irvingguerra.litedash.feature.map.MapOrientation
import com.irvingguerra.litedash.feature.map.MapStyle
import com.irvingguerra.litedash.feature.map.MapView
import com.irvingguerra.litedash.feature.map.MapZoom
import com.irvingguerra.litedash.feature.map.MinimalPalette
import com.irvingguerra.litedash.feature.map.OsmMap
import com.irvingguerra.litedash.feature.map.OsmTiles
import com.irvingguerra.litedash.feature.map.TileStore
import com.irvingguerra.litedash.feature.map.VectorTiles
import com.irvingguerra.litedash.feature.map.tilePx

/**
 * Widget Mapa: OpenStreetMap con tu posición del GPS, como diga su [data] ([MapLook]). GPS y
 * descargas solo mientras se ve y la app está al frente; sin permiso, el mismo botón que Viaje.
 * El botón de la esquina abre tu app de mapas (como Navegar).
 */
@Composable
fun MapTile(d: Dependencies, data: String, onNavigate: () -> Unit) {
    val (granted, ask) = rememberGpsPermission(d)
    if (!granted) return ShortcutTile(Icons.map, stringResource(R.string.tile_map), stringResource(R.string.tap_to_enable), ask)
    val look = remember(data) { MapLook.parse(data) }
    Card(Modifier.fillMaxSize()) {
        LiveMap(d, look, Modifier.fillMaxSize())
        IconButton(Icons.navigate, onNavigate, Modifier.align(Alignment.TopEnd).padding(8.dp), size = 44.dp, background = LocalColors.current.surface.copy(alpha = 0.85f), color = LocalColors.current.accent)
    }
}

/** El mapa con el GPS y las teselas corriendo solo mientras se ve (el widget y la vista previa). */
@Composable
private fun LiveMap(d: Dependencies, look: MapLook, modifier: Modifier) {
    val c = LocalColors.current
    KeepTripRunning(d.trip)
    val context = LocalContext.current
    val density = LocalDensity.current.density
    // Una por fuente y colores: el minimalista se dibuja ya en los suyos.
    val dark = look.dark(c.dark)
    val store = remember(look.detail, dark) { newTileStore(context, look.detail, dark, density) }
    LifecycleStartEffect(store) {
        store.resume()
        onStopOrDispose { store.pause() }
    }
    val position = d.trip.position
    // Del bus: la del OBD si hay escáner, si no la del GPS.
    val kmh = d.bus.watch(Signal.SPEED)
    Box(modifier) {
        if (position == null) {
            Label(stringResource(R.string.map_waiting), Typography.body, c.textMuted, maxLines = 2, modifier = Modifier.align(Alignment.Center).padding(16.dp))
        } else {
            OsmMap(store, position.latitude, position.longitude, d.trip.bearing, kmh, look, Modifier.fillMaxSize())
        }
    }
}

private fun newTileStore(context: Context, detail: MapDetail, dark: Boolean, density: Float): TileStore {
    val source = when (detail) {
        MapDetail.FULL -> OsmTiles
        MapDetail.MINIMAL -> VectorTiles(context, if (dark) MinimalPalette.DARK else MinimalPalette.LIGHT, tilePx(density).toInt(), density)
    }
    return TileStore(context, "LiteDash/${BuildConfig.VERSION_NAME} (+https://litedash.web.app)", source)
}

/** Personalizar mapa: detalle, orientación, vista, zoom y colores, con el mapa en vivo como vista previa. */
@Composable
fun CustomizeMapPanel(d: Dependencies, tileId: String, close: () -> Unit) {
    val c = LocalColors.current
    val tile = d.dashboard.tiles.firstOrNull { it.id == tileId }
    if (tile == null) {
        // Cerrar escribe estado: fuera de la composición.
        LaunchedEffect(Unit) { close() }
        return
    }
    val look = MapLook.parse(tile.data)
    fun set(new: MapLook) = d.dashboard.setData(tileId, new.encode())
    val (granted, _) = rememberGpsPermission(d)
    PanelScaffold(stringResource(R.string.map_customize), close) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val preview = @Composable { m: Modifier -> if (granted) Card(m) { LiveMap(d, look, Modifier.fillMaxSize()) } }
            val options = @Composable { m: Modifier ->
                Column(m.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Label(stringResource(R.string.map_detail), Typography.label, c.textMuted)
                    Segmented(
                        listOf(MapDetail.FULL to stringResource(R.string.map_detail_full), MapDetail.MINIMAL to stringResource(R.string.map_detail_minimal)),
                        look.detail, { set(look.copy(detail = it)) },
                    )
                    Label(stringResource(if (look.detail == MapDetail.FULL) R.string.map_detail_full_help else R.string.map_detail_minimal_help), Typography.small, c.textMuted)
                    Label(stringResource(R.string.map_orientation), Typography.label, c.textMuted)
                    Segmented(
                        listOf(MapOrientation.NORTH to stringResource(R.string.map_north), MapOrientation.HEADING to stringResource(R.string.map_heading)),
                        look.orientation, { set(look.copy(orientation = it)) },
                    )
                    Label(stringResource(R.string.map_view), Typography.label, c.textMuted)
                    Segmented(
                        listOf(MapView.FLAT to stringResource(R.string.map_flat), MapView.TILTED to stringResource(R.string.map_tilted)),
                        look.view, { set(look.copy(view = it)) },
                    )
                    Label(stringResource(R.string.map_zoom), Typography.label, c.textMuted)
                    Segmented(
                        listOf(
                            MapZoom.AUTO to stringResource(R.string.map_zoom_auto), MapZoom.NEAR to stringResource(R.string.map_zoom_near),
                            MapZoom.MID to stringResource(R.string.map_zoom_mid), MapZoom.FAR to stringResource(R.string.map_zoom_far),
                        ),
                        look.zoom, { set(look.copy(zoom = it)) },
                    )
                    Label(stringResource(R.string.map_zoom_help), Typography.small, c.textMuted)
                    Label(stringResource(R.string.map_style), Typography.label, c.textMuted)
                    Segmented(
                        listOf(MapStyle.THEME to stringResource(R.string.map_style_theme), MapStyle.LIGHT to stringResource(R.string.map_style_light), MapStyle.DARK to stringResource(R.string.map_style_dark)),
                        look.style, { set(look.copy(style = it)) },
                    )
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
