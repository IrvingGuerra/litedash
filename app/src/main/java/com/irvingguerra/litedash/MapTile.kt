package com.irvingguerra.litedash

import android.content.Context
import android.os.Build
import android.os.StatFs
import android.os.storage.StorageManager
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Radius
import com.irvingguerra.litedash.core.ui.Segmented
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.core.vehicle.Signal
import com.irvingguerra.litedash.core.vehicle.watch
import com.irvingguerra.litedash.feature.apps.App
import com.irvingguerra.litedash.feature.map.MapCamera
import com.irvingguerra.litedash.feature.map.MapCanvas
import com.irvingguerra.litedash.feature.map.MapDetail
import com.irvingguerra.litedash.feature.map.MapLook
import com.irvingguerra.litedash.feature.map.MapOrientation
import com.irvingguerra.litedash.feature.map.MapStyle
import com.irvingguerra.litedash.feature.map.MapZoom
import com.irvingguerra.litedash.feature.map.rememberFreeMapCamera
import com.irvingguerra.litedash.feature.map.rememberMapCamera
import com.irvingguerra.litedash.feature.map.zoomToFit
import com.irvingguerra.litedash.feature.dashboard.TileTypes
import com.irvingguerra.litedash.feature.map.MinimalPalette
import com.irvingguerra.litedash.feature.map.OfflineError
import com.irvingguerra.litedash.feature.map.OfflineArea
import com.irvingguerra.litedash.feature.map.OfflineMaps
import com.irvingguerra.litedash.feature.map.OsmTiles
import com.irvingguerra.litedash.feature.map.TileStore
import com.irvingguerra.litedash.feature.map.VectorTiles
import com.irvingguerra.litedash.feature.map.tilePx
import com.irvingguerra.litedash.feature.music.NotificationService
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Widget Mapa: tu posición del GPS en el mapa (minimalista de OpenFreeMap o completo de OpenStreetMap),
 * como diga su [data] ([MapLook]); navegando, la siguiente vuelta flota arriba a la izquierda y Casa y
 * Trabajo, abajo a la derecha (sobre los créditos). Un toque hace [onTap] (tu app de mapas o el mapa en grande, según
 * Ajustes → Apps → Navegación); con la app externa, el botón de la esquina la abre (sin ella no hay botón). En una fila, o sin permiso de ubicación, se ve
 * como el widget Navegar de antes (vuelta, Casa y Trabajo) y, sin permiso, un botón para pedirlo. GPS y descargas solo
 * mientras se ve.
 */
@Composable
fun MapTile(d: Dependencies, tileId: String, data: String, app: App?, editing: Boolean, onPlace: (String) -> Unit, onNavigate: () -> Unit, onTap: () -> Unit) {
    val c = LocalColors.current
    val (granted, ask) = rememberGpsPermission(d)
    val look = remember(data) { MapLook.parse(data) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (!granted || maxHeight < COMPACT_HEIGHT) {
            // Un toque, igual que al mapa en vivo: tu app de mapas o el mapa en grande.
            Box(Modifier.fillMaxSize()) {
                NavigationTile(d.navigation, app, d.prefs.homeAddress, d.prefs.workAddress, onPlace, onTap, endInset = if (!editing && !granted) 46.dp else 0.dp)
                // Sin permiso, el botón lo pide. Al acomodar se esconde (estorba al botón de tamaño).
                if (!editing && !granted) IconButton(Icons.map, ask, Modifier.align(Alignment.TopEnd).padding(6.dp), size = 40.dp, background = c.surfaceHigh, color = c.accent)
            }
            return@BoxWithConstraints
        }
        val external = d.prefs.mapExternal && app != null
        val turnWidth = maxWidth - if (external) 68.dp else 16.dp
        val withPlaces = maxHeight >= PLACES_HEIGHT
        Card(Modifier.fillMaxSize()) {
            LiveMap(d, look, Modifier.fillMaxSize(), cameraKey = tileId, onTap = onTap)
            // La vuelta no llega al botón de la app.
            NextTurn(d, Modifier.align(Alignment.TopStart).padding(8.dp).widthIn(max = turnWidth))
            if (withPlaces) {
                PlacePills(
                    d.prefs.homeAddress, d.prefs.workAddress, onPlace,
                    Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = ATTRIBUTION_CLEARANCE),
                )
            }
            // Con "Usar app externa" apagado no hay botón que te saque de LiteDash (se queda en el mapa en grande).
            if (external) IconButton(Icons.navigate, onNavigate, Modifier.align(Alignment.TopEnd).padding(8.dp), size = 44.dp, background = c.surface.copy(alpha = 0.85f), color = c.accent)
        }
    }
}

/**
 * El mapa de LiteDash en grande (los accesos directos al mapa con "Usar app externa" apagado), como el widget
 * [data] si hay uno: el mapa en vivo (con dos dedos se acerca o aleja), la siguiente vuelta, Casa y Trabajo, y botones
 * para abrir tu app de mapas y para cerrar.
 */
@Composable
fun MapExpanded(d: Dependencies, tileId: String?, data: String, app: App?, onPlace: (String) -> Unit, onNavigate: () -> Unit, close: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    val (granted, ask) = rememberGpsPermission(d)
    val look = remember(data) { MapLook.parse(data) }
    Card(modifier.fillMaxSize()) {
        if (granted) LiveMap(d, look, Modifier.fillMaxSize(), cameraKey = tileId)
        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Button(stringResource(R.string.tap_to_enable), ask, primary = true) }
        NextTurn(d, Modifier.align(Alignment.TopStart).padding(12.dp).widthIn(max = 520.dp))
        Row(Modifier.align(Alignment.TopEnd).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (app != null) IconButton(Icons.navigate, onNavigate, size = 48.dp, background = c.surface.copy(alpha = 0.9f), color = c.accent)
            IconButton(Icons.close, close, size = 48.dp, background = c.surface.copy(alpha = 0.9f))
        }
        PlacePills(
            d.prefs.homeAddress, d.prefs.workAddress, onPlace,
            Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = ATTRIBUTION_CLEARANCE),
        )
    }
}

/** La siguiente vuelta que dice el aviso de Waze o Maps, en una tarjeta sobre el mapa (nada si no navegas). */
@Composable
private fun NextTurn(d: Dependencies, modifier: Modifier) {
    val c = LocalColors.current
    val repo = d.navigation
    DisposableEffect(repo) {
        repo.use { NotificationService.active() }
        onDispose { repo.release() }
    }
    val instruction = repo.instruction ?: return
    Row(modifier.clip(RoundedCornerShape(Radius.inner)).background(c.surface).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TurnArrow(instruction, 44.dp, Radius.small)
        Column(Modifier.padding(start = 10.dp, end = 6.dp)) {
            if (instruction.title.isNotBlank()) Label(instruction.title, Typography.title, maxLines = 1)
            if (instruction.text.isNotBlank()) Label(instruction.text, Typography.small, c.textMuted, maxLines = 1)
        }
    }
}

/** Abajo de esto el mapa no luce: se ve como el widget Navegar. */
private val COMPACT_HEIGHT = 120.dp

/** Más bajo, Casa y Trabajo se encimarían con la vuelta: se quedan solo en el modo de una fila. */
private val PLACES_HEIGHT = 170.dp

/** Lo que ocupan los créditos de OpenStreetMap abajo a la derecha (obligatorios: nada los tapa). */
private val ATTRIBUTION_CLEARANCE = 56.dp

/**
 * El mapa con el GPS y las teselas corriendo solo mientras se ve (el widget, en grande y la vista previa)
 * y nada lo tapa ([LocalHomeCovered]). [onTap] = qué hace un toque (sin él, nada); con un dedo se mueve y abajo
 * a la izquierda van el botón para regresar a ti, la calle donde vas y si el mapa es en línea o bajado.
 */
@Composable
private fun LiveMap(d: Dependencies, look: MapLook, modifier: Modifier, cameraKey: String? = null, onTap: (() -> Unit)? = null) {
    val c = LocalColors.current
    KeepTripRunning(d.trip)
    val context = LocalContext.current
    val store = rememberTileStore(look, c.dark)
    val position = d.trip.position
    // Del bus (la del OBD si hay escáner, si no la del GPS): andando (o con Waze/Maps navegando) va de viaje.
    val kmh = d.bus.watch(Signal.SPEED)
    val navigating = d.navigation.instruction != null
    val camera = sharedMapCamera(cameraKey)
    // Solo cambia al mover o centrar el mapa (no con cada movimiento del dedo).
    val centered by remember(camera) { derivedStateOf { camera.centered } }
    BoxWithConstraints(modifier) {
        if (position == null) {
            Label(stringResource(R.string.map_waiting), Typography.body, c.textMuted, maxLines = 2, modifier = Modifier.align(Alignment.Center).padding(16.dp))
            return@BoxWithConstraints
        }
        MapCanvas(store, position.latitude, position.longitude, d.trip.bearing, kmh, look, Modifier.fillMaxSize(), camera, navigating, onTap = onTap)
        // Abajo a la izquierda (a la derecha van los créditos): centrar si lo moviste, la calle y si es en línea o bajado.
        val offline = look.detail == MapDetail.MINIMAL && OfflineMaps.get(context).covers(position.latitude, position.longitude)
        // Angosto, los créditos ocupan dos renglones a lo ancho: lo de la izquierda va arriba de ellos y sin el estado.
        val narrow = maxWidth < NARROW_MAP
        val showStatus = maxHeight >= STATUS_HEIGHT && !narrow
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = 8.dp, end = 8.dp, bottom = if (narrow) 44.dp else 8.dp).widthIn(max = if (narrow) maxWidth else maxWidth * 0.55f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (!centered) IconButton(Icons.recenter, camera::recenter, size = 44.dp, background = c.surface.copy(alpha = 0.9f), color = c.accent)
            camera.street?.let { MapPill(Icons.location, it, c.text) }
            if (showStatus) {
                MapPill(
                    if (offline) Icons.offline else Icons.map,
                    stringResource(if (offline) R.string.map_offline_now else R.string.map_online_now),
                    c.textMuted,
                )
            }
        }
    }
}

/**
 * Una cámara por widget Mapa ([key] = su id), la misma en el widget y en grande: lo que muevas o acerques se conserva
 * al abrirlo o cerrarlo (solo cambia el tamaño). Sin [key] (vistas previas), una propia.
 */
private val mapCameras = HashMap<String, MapCamera>()

@Composable
private fun sharedMapCamera(key: String?): MapCamera =
    if (key == null) rememberMapCamera() else remember(key) { mapCameras.getOrPut(key) { MapCamera() } }

/** Una pastilla sobre el mapa: ícono y un texto corto. */
@Composable
private fun MapPill(icon: ImageVector, text: String, color: Color) {
    val c = LocalColors.current
    Row(
        Modifier.clip(RoundedCornerShape(Radius.small)).background(c.surface.copy(alpha = 0.88f)).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, c.accent, 14.dp)
        Label(text, Typography.small, color, maxLines = 1)
    }
}

/** Más bajo, el mapa no lleva la pastilla de en línea o sin conexión (estorba). */
private val STATUS_HEIGHT = 220.dp

/** Más angosto, los créditos del mapa van en dos renglones (como en `MapCanvas`). */
private val NARROW_MAP = 320.dp

/** Como se presenta LiteDash ante los servidores de mapas (lo piden sus políticas de uso). */
private val MAP_USER_AGENT = "LiteDash/${BuildConfig.VERSION_NAME} (+https://litedash.dev)"

/**
 * Las teselas de [look], bajando solo mientras se ve y nada lo tapa: una por fuente y, solo el minimalista (se
 * dibuja ya en sus colores), por claro u oscuro; el completo usa un filtro.
 */
@Composable
private fun rememberTileStore(look: MapLook, themeDark: Boolean): TileStore {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val darkTiles = look.detail == MapDetail.MINIMAL && look.dark(themeDark)
    val store = remember(look.detail, darkTiles) { newTileStore(context, look.detail, darkTiles, density) }
    val covered = LocalHomeCovered.current
    LifecycleStartEffect(store, covered) {
        if (!covered) store.resume()
        onStopOrDispose { store.pause() }
    }
    return store
}

private fun newTileStore(context: Context, detail: MapDetail, dark: Boolean, density: Float): TileStore {
    val source = when (detail) {
        MapDetail.FULL -> OsmTiles
        MapDetail.MINIMAL -> VectorTiles(context, if (dark) MinimalPalette.DARK else MinimalPalette.LIGHT, tilePx(density).toInt(), density, MAP_USER_AGENT)
    }
    return TileStore(context, MAP_USER_AGENT, source)
}

/** Personalizar mapa: estilo, orientación, zoom y colores de este widget, con el mapa en vivo como vista previa. */
@Composable
fun CustomizeMapPanel(d: Dependencies, tileId: String, close: () -> Unit) {
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
                    MapLookOptions(look, ::set)
                    OfflineSection(d, look.detail)
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

/**
 * Ajustes → Apps → Navegación → Mapa de LiteDash: las mismas opciones que el panel de un widget, para todos los
 * widgets Mapa y el mapa en grande ([applyMapLook]), con el mapa en vivo como vista previa.
 */
@Composable
internal fun MapLookSettings(d: Dependencies) {
    val look = MapLook.parse(d.prefs.mapLook.orEmpty())
    val (granted, _) = rememberGpsPermission(d)
    val options = @Composable { m: Modifier ->
        Column(m, verticalArrangement = Arrangement.spacedBy(14.dp)) { MapLookOptions(look) { applyMapLook(d, it) } }
    }
    if (!granted) return options(Modifier.fillMaxWidth())
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= SETTINGS_SIDE_BY_SIDE) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Card(Modifier.weight(1f).height(SETTINGS_PREVIEW_HEIGHT)) { LiveMap(d, look, Modifier.fillMaxSize()) }
                options(Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Card(Modifier.fillMaxWidth().height(OFFLINE_PREVIEW_HEIGHT)) { LiveMap(d, look, Modifier.fillMaxSize()) }
                options(Modifier.fillMaxWidth())
            }
        }
    }
}

/** Ajustes → Apps → Navegación → Sin conexión (con el estilo que tenga el mapa de LiteDash). */
@Composable
internal fun MapOfflineSettings(d: Dependencies) {
    OfflineSection(d, MapLook.parse(d.prefs.mapLook.orEmpty()).detail)
}

/** El aspecto de LiteDash: se guarda y se pone en todos los widgets Mapa del inicio (de una sola escritura). */
private fun applyMapLook(d: Dependencies, look: MapLook) {
    val data = look.encode()
    d.prefs.mapLook = data.ifEmpty { null }
    d.dashboard.changeData(d.dashboard.tiles.filter { it.type == TileTypes.MAP }.associate { it.id to data })
}

/** Ancho desde el que la vista previa de Ajustes va a un lado de las opciones. */
private val SETTINGS_SIDE_BY_SIDE = 640.dp
private val SETTINGS_PREVIEW_HEIGHT = 340.dp

/** Las opciones del aspecto (las comparten el panel de un widget y Ajustes). */
@Composable
private fun MapLookOptions(look: MapLook, set: (MapLook) -> Unit) {
    val c = LocalColors.current
    Label(stringResource(R.string.map_detail), Typography.label, c.textMuted)
    Segmented(
        listOf(MapDetail.MINIMAL to stringResource(R.string.map_detail_minimal), MapDetail.FULL to stringResource(R.string.map_detail_full)),
        look.detail, { set(look.copy(detail = it)) },
    )
    Label(stringResource(if (look.detail == MapDetail.FULL) R.string.map_detail_full_help else R.string.map_detail_minimal_help), Typography.small, c.textMuted)
    Label(stringResource(R.string.map_orientation), Typography.label, c.textMuted)
    Segmented(
        listOf(MapOrientation.NORTH to stringResource(R.string.map_north), MapOrientation.HEADING to stringResource(R.string.map_heading)),
        look.orientation, { set(look.copy(orientation = it)) },
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
}

/**
 * Sin conexión: las zonas guardadas (tocar una la muestra en el mapa, con Volver a bajar y Borrar); el radio y un mapa con su círculo para elegir la zona (se arrastra;
 * «Mi ubicación» regresa), su tamaño aproximado y lo libre; mientras baja, el avance y Cancelar. Solo el minimalista
 * (OSM no deja bajar en bloque).
 */
@Composable
private fun OfflineSection(d: Dependencies, detail: MapDetail) {
    val c = LocalColors.current
    Label(stringResource(R.string.map_offline), Typography.label, c.textMuted)
    if (detail != MapDetail.MINIMAL) {
        Label(stringResource(R.string.map_offline_full), Typography.small, c.textMuted)
        return
    }
    KeepTripRunning(d.trip)
    val context = LocalContext.current
    val offline = remember { OfflineMaps.get(context) }
    val position = d.trip.position
    Label(stringResource(R.string.map_offline_help), Typography.small, c.textMuted)
    val locale = currentLocale()
    val dateFormat = remember(locale) { SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "dMMM"), locale) }
    // La zona guardada que se está viendo (null = elegir una nueva); si se borra, se vuelve a elegir.
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = offline.areas.firstOrNull { it.id == selectedId }
    for (area in offline.areas) {
        val here = position != null && area.contains(position.latitude, position.longitude)
        val chosen = area.id == selectedId
        Label(
            stringResource(
                R.string.map_offline_area, stringResource(if (here) R.string.map_offline_here else R.string.map_offline_other),
                area.km, Formatter.formatShortFileSize(context, area.bytes), dateFormat.format(Date(area.savedAt)),
            ),
            Typography.body, if (chosen) c.accent else c.text, maxLines = 2,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.inner))
                .background(if (chosen) c.accent.copy(alpha = 0.18f) else c.surfaceHigh)
                .clickable { selectedId = if (chosen) null else area.id }
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
    val job = offline.job
    if (job != null && job.error == null) {
        val fraction = if (job.total > 0) job.done.toFloat() / job.total else 0f
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.surfaceHigh)) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(c.accent))
        }
        // Sin total todavía se están contando las teselas (un instante; de 200 km, un poco más).
        if (job.total > 0) Label(stringResource(R.string.map_offline_progress, job.done, job.total, Formatter.formatShortFileSize(context, job.bytes)), Typography.small, c.textMuted)
        if (job.waiting) Label(stringResource(R.string.map_offline_waiting_server), Typography.small, c.warning)
        Button(stringResource(R.string.cancel), { offline.cancel() })
        return
    }
    job?.error?.let { error ->
        Label(stringResource(if (error == OfflineError.BLOCKED) R.string.map_offline_blocked else R.string.map_offline_failed), Typography.small, c.warning)
    }
    var km by rememberSaveable { mutableIntStateOf(OFFLINE_RADII[1]) }
    // Con una zona elegida el radio es el suyo.
    if (selected == null) Segmented(OFFLINE_RADII.map { it to "$it km" }, km, { km = it })
    if (position == null) {
        Button(stringResource(R.string.map_offline_download), {}, primary = true, enabled = false)
        Label(stringResource(R.string.map_offline_waiting), Typography.small, c.textMuted)
    } else {
        OfflinePicker(offline, position.latitude, position.longitude, km, selected) { selectedId = null }
    }
}

/**
 * El mapa para elegir la zona (minimalista, plano, norte arriba) con el círculo de [km] alrededor de su centro y el
 * zoom para que quepa; se arrastra hasta ~2 000 km. Abajo, el tamaño, lo libre y el botón para bajarla. Con una zona
 * guardada elegida ([selected]) se ve esa, con Volver a bajar y Borrar; «Mi ubicación» la suelta ([onClear]).
 */
@Composable
private fun OfflinePicker(offline: OfflineMaps, lat: Double, lon: Double, km: Int, selected: OfflineArea?, onClear: () -> Unit) {
    val c = LocalColors.current
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val look = remember { MapLook() }
    val store = rememberTileStore(look, c.dark)
    val camera = rememberFreeMapCamera()
    val centered by remember(camera) { derivedStateOf { camera.centered } }
    // Al elegir una zona el mapa va a ella; al soltarla, de regreso a ti.
    LaunchedEffect(selected?.id) {
        if (selected != null) camera.lookAt(selected.lat, selected.lon, lat, lon) else if (!camera.centered) camera.recenter()
    }
    val ringKm = selected?.km ?: km
    Card(Modifier.fillMaxWidth().height(OFFLINE_PREVIEW_HEIGHT)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val viewPx = with(LocalDensity.current) { minOf(maxWidth, maxHeight).toPx() }
            val zoom = zoomToFit(ringKm.toFloat(), selected?.lat ?: lat, viewPx, tilePx(density))
            MapCanvas(store, lat, lon, null, null, look, Modifier.fillMaxSize(), camera, fixedZoom = zoom, ringKm = ringKm.toFloat())
            if (!centered) {
                Button(stringResource(R.string.map_offline_my_location), { onClear(); camera.recenter() }, Modifier.align(Alignment.TopStart).padding(8.dp))
            }
        }
    }
    if (selected != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(stringResource(R.string.map_offline_redownload), {
                val source = VectorTiles(context, MinimalPalette.LIGHT, tilePx(density).toInt(), density, MAP_USER_AGENT)
                offline.download(selected.lat, selected.lon, selected.km, source, MAP_USER_AGENT)
            }, primary = true)
            Confirmed(stringResource(R.string.map_offline_delete), stringResource(R.string.map_offline_delete_question), stringResource(R.string.confirm_delete),
                message = stringResource(R.string.map_offline_delete_message)) { offline.delete(selected.id) }
        }
        return
    }
    // El tamaño depende de la latitud (no de cada metro): se recalcula solo al cambiar de radio o de zona (a ~10 km).
    val here by rememberUpdatedState(lat to lon)
    val zone by remember(camera) {
        derivedStateOf { camera.center(here.first, here.second).let { (a, b) -> Math.round(a * 10) / 10.0 to Math.round(b * 10) / 10.0 } }
    }
    val tiles by produceState<Int?>(null, zone, km) { value = offline.estimate(zone.first, zone.second, km) }
    // Lo libre se vuelve a leer al terminar o borrar una zona.
    val free by produceState<Long?>(null, offline.areas) { value = withContext(Dispatchers.IO) { freeBytes(context) } }
    val bytes = tiles?.let { it * OfflineMaps.AVG_TILE_BYTES }
    val fits = bytes != null && free?.let { bytes + FREE_MARGIN_BYTES <= it } ?: false
    if (bytes != null) {
        val big = bytes > BIG_ZONE_BYTES
        Label(
            free?.let { stringResource(R.string.map_offline_size_free, Formatter.formatShortFileSize(context, bytes), Formatter.formatShortFileSize(context, it)) }
                ?: stringResource(R.string.map_offline_size, Formatter.formatShortFileSize(context, bytes)),
            Typography.small, if (big) c.warning else c.textMuted,
        )
        if (big && fits) Label(stringResource(R.string.map_offline_big), Typography.small, c.warning)
        if (free != null && !fits) Label(stringResource(R.string.map_offline_no_room), Typography.small, c.warning)
    }
    Button(stringResource(R.string.map_offline_download), {
        val (centerLat, centerLon) = camera.center(lat, lon)
        val source = VectorTiles(context, MinimalPalette.LIGHT, tilePx(density).toInt(), density, MAP_USER_AGENT)
        offline.download(centerLat, centerLon, km, source, MAP_USER_AGENT)
    }, primary = true, enabled = fits)
}

/** Radios que se pueden bajar (km); 25, de fábrica. */
private val OFFLINE_RADII = listOf(10, 25, 50, 100, 200)

/** Alto del mapa para elegir la zona (y de la vista previa de Ajustes en pantallas angostas). */
private val OFFLINE_PREVIEW_HEIGHT = 240.dp

/** Desde aquí la descarga se avisa en ámbar (tarda y llena la memoria). */
private const val BIG_ZONE_BYTES = 500_000_000L

/** Lo que se deja libre además de la zona (Android y otras apps lo necesitan). */
private const val FREE_MARGIN_BYTES = 200_000_000L

/** Lo libre para la app (desde Android 8 cuenta también la caché que el sistema puede soltar). */
private fun freeBytes(context: Context): Long {
    val dir = context.filesDir
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        runCatching {
            val storage = context.getSystemService(StorageManager::class.java)
            return storage.getAllocatableBytes(storage.getUuidForPath(dir))
        }
    }
    return StatFs(dir.path).availableBytes
}
