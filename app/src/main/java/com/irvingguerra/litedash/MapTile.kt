package com.irvingguerra.litedash

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.core.vehicle.Signal
import com.irvingguerra.litedash.core.vehicle.watch
import com.irvingguerra.litedash.feature.map.OsmMap
import com.irvingguerra.litedash.feature.map.TileStore

/**
 * Widget Mapa: OpenStreetMap con tu posición del GPS. GPS y descargas solo mientras se ve y la
 * app está al frente; sin permiso, el mismo botón que Viaje. El botón de la esquina abre tu app
 * de mapas (como Navegar).
 */
@Composable
fun MapTile(d: Dependencies, onNavigate: () -> Unit) {
    val c = LocalColors.current
    val (granted, ask) = rememberGpsPermission(d)
    if (!granted) return ShortcutTile(Icons.map, stringResource(R.string.tile_map), stringResource(R.string.tap_to_enable), ask)
    KeepTripRunning(d.trip)
    val context = LocalContext.current
    val store = remember { TileStore(context, "LiteDash/${BuildConfig.VERSION_NAME} (+https://litedash.web.app)") }
    LifecycleStartEffect(store) {
        store.resume()
        onStopOrDispose { store.pause() }
    }
    val position = d.trip.position
    // Del bus: la del OBD si hay escáner, si no la del GPS.
    val kmh = d.bus.watch(Signal.SPEED)
    Card(Modifier.fillMaxSize()) {
        if (position == null) {
            Label(stringResource(R.string.map_waiting), Typography.body, c.textMuted, maxLines = 2, modifier = Modifier.align(Alignment.Center).padding(16.dp))
        } else {
            OsmMap(store, position.latitude, position.longitude, d.trip.bearing, kmh, Modifier.fillMaxSize())
        }
        IconButton(Icons.navigate, onNavigate, Modifier.align(Alignment.TopEnd).padding(8.dp), size = 44.dp, background = c.surface.copy(alpha = 0.85f), color = c.accent)
    }
}
