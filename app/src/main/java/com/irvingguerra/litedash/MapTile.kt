package com.irvingguerra.litedash

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.StatFs
import android.os.storage.StorageManager
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
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
import com.irvingguerra.litedash.core.ui.TextInput
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.core.vehicle.Signal
import com.irvingguerra.litedash.core.vehicle.watch
import com.irvingguerra.litedash.feature.apps.App
import com.irvingguerra.litedash.feature.apps.normalize
import com.irvingguerra.litedash.feature.car.CarPackage
import com.irvingguerra.litedash.feature.car.snapshot
import com.irvingguerra.litedash.feature.dashboard.TileTypes
import com.irvingguerra.litedash.feature.map.MapCamera
import com.irvingguerra.litedash.feature.map.MapCanvas
import com.irvingguerra.litedash.feature.map.MapDetail
import com.irvingguerra.litedash.feature.map.MapLook
import com.irvingguerra.litedash.feature.map.MapOrientation
import com.irvingguerra.litedash.feature.map.MapPacks
import com.irvingguerra.litedash.feature.map.MapRegion
import com.irvingguerra.litedash.feature.map.MapStyle
import com.irvingguerra.litedash.feature.map.MapZoom
import com.irvingguerra.litedash.feature.map.MinimalPalette
import com.irvingguerra.litedash.feature.map.OfflineArea
import com.irvingguerra.litedash.feature.map.OfflineError
import com.irvingguerra.litedash.feature.map.OfflineMaps
import com.irvingguerra.litedash.feature.map.OsmTiles
import com.irvingguerra.litedash.feature.map.SearchHit
import com.irvingguerra.litedash.feature.map.TileStore
import com.irvingguerra.litedash.feature.map.VectorTiles
import com.irvingguerra.litedash.feature.map.rememberFreeMapCamera
import com.irvingguerra.litedash.feature.map.rememberMapCamera
import com.irvingguerra.litedash.feature.map.tilePx
import com.irvingguerra.litedash.feature.map.zoomToFit
import com.irvingguerra.litedash.feature.music.NotificationService
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Widget Mapa: tu posición del GPS en el mapa (minimalista de OpenFreeMap o completo de OpenStreetMap),
 * como diga su [data] ([MapLook]); navegando, la siguiente vuelta flota arriba a la izquierda y Casa y
 * Trabajo, abajo a la derecha (sobre los créditos). Un toque hace [onTap] (el mapa de LiteDash en grande); con una
 * app de mapas, el botón de la esquina la abre. En una fila, o sin permiso de ubicación, se ve
 * solo la vuelta, Casa y Trabajo ([NavigationTile]) y, sin permiso, un botón para pedirlo. GPS y descargas solo
 * mientras se ve. Al quitar el widget se sueltan su cámara y su almacén de teselas.
 */
@Composable
fun MapTile(
    d: Dependencies, tileId: String, data: String, app: App?, editing: Boolean, onPlace: (String) -> Unit, onNavigate: () -> Unit, onTap: () -> Unit,
    onSearch: () -> Unit,
) {
    val c = LocalColors.current
    val (granted, ask) = rememberGpsPermission(d)
    val look = remember(data) { MapLook.parse(data) }
    // Sale de la pantalla: si fue porque se quitó el widget, lo suyo ya no sirve (si no, se queda para cuando vuelva).
    DisposableEffect(tileId) {
        onDispose { if (d.dashboard.allTiles.none { it.id == tileId }) forgetMap(tileId) }
    }
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
        // Con una app de mapas, su botón en la esquina (un toque al mapa abre el de LiteDash).
        val external = app != null
        val turnWidth = maxWidth - if (external) 120.dp else 68.dp
        val withPlaces = maxHeight >= PLACES_HEIGHT
        Card(Modifier.fillMaxSize()) {
            LiveMap(d, look, Modifier.fillMaxSize(), tileId, onTap = onTap)
            // La vuelta no llega al botón de la app.
            NextTurn(d, Modifier.align(Alignment.TopStart).padding(8.dp).widthIn(max = turnWidth))
            if (withPlaces) {
                PlacePills(
                    d.prefs.homeAddress, d.prefs.workAddress, onPlace,
                    Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = ATTRIBUTION_CLEARANCE),
                )
            }
            // Tu app de mapas solo con su botón (un toque al mapa abre el de LiteDash en grande).
            // La lupa abre el mapa en grande con el buscador (al acomodar se esconde: estorba al botón de tamaño).
            Row(Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!editing) MapCompass(d, look, sharedMapCamera(tileId))
                if (!editing) IconButton(Icons.search, onSearch, size = 44.dp, background = c.surface.copy(alpha = 0.85f), color = c.accent, label = stringResource(R.string.search_place))
                if (external) IconButton(Icons.navigate, onNavigate, size = 44.dp, background = c.surface.copy(alpha = 0.85f), color = c.accent)
            }
        }
    }
}

/**
 * El mapa de LiteDash en grande (el toque al widget Mapa, y la barra y el volante si no abren tu app), como el widget
 * [data] si hay uno: el mapa en vivo (con dos dedos se acerca o aleja), la siguiente vuelta, Casa y Trabajo, y botones
 * para cerrar y, con una app de mapas, para abrirla.
 */
@Composable
fun MapExpanded(
    d: Dependencies, tileId: String?, data: String, app: App?, onPlace: (String) -> Unit, onNavigate: () -> Unit, onOffline: () -> Unit,
    close: () -> Unit, modifier: Modifier = Modifier,
) {
    val c = LocalColors.current
    val (granted, ask) = rememberGpsPermission(d)
    val look = remember(data) { MapLook.parse(data) }
    // Mientras se ve, su widget (que queda debajo) se rige por lo que tape a este: no suelta las teselas que comparten.
    val covered = LocalHomeCovered.current
    if (tileId != null) DisposableEffect(tileId, covered) {
        val shown = ExpandedMap(tileId, covered)
        expandedMap = shown
        onDispose { if (expandedMap === shown) expandedMap = null }
    }
    val context = LocalContext.current
    val camera = sharedMapCamera(tileId)
    val search = remember { PlaceSearch() }
    val focus = remember { FocusRequester() }
    // Al soltar el buscador el foco pasa al mapa (enfocable, sin nada visible). Con clearFocus() a secas, en Android ≤ 8
    // la ventana se lo regresa al primer enfocable, que es el mismo campo: el teclado se volvía a abrir y la tarjeta del
    // lugar no salía.
    val rest = remember { FocusRequester() }
    val release: () -> Unit = { rest.requestFocus() }
    val pick: (SearchHit) -> Unit = { hit ->
        release()
        search.query = hit.name
        search.selected = hit
        d.prefs.addRecent(hit)
        camera.show(hit.lat, hit.lon)
    }
    // Lo que llegó de otra app (geo:) o de la lupa del widget.
    LaunchedEffect(PlaceRequests.place, PlaceRequests.query, PlaceRequests.focus) {
        PlaceRequests.place?.let { pick(it) }
        PlaceRequests.query?.let {
            search.type(it.take(QUERY_MAX))
            focus.requestFocus()
        }
        if (PlaceRequests.focus) focus.requestFocus()
        PlaceRequests.place = null
        PlaceRequests.query = null
        PlaceRequests.focus = false
    }
    val selected = search.selected
    // La ruta al lugar elegido (al tocar «Ruta»); se suelta al cambiar de lugar.
    var routing by remember { mutableStateOf<RouteRequest?>(null) }
    if (routing != null && routing?.to != selected) routing = null
    routing?.let { RunRoute(d, it) }
    val route = routing?.route
    val unselect = {
        search.clear()
        camera.recenter()
    }
    // Al cerrar la ruta, el mapa regresa al lugar.
    val closeRoute = {
        routing = null
        selected?.let { camera.show(it.lat, it.lon) }
        Unit
    }
    BackHandler(routing != null && !search.focused, closeRoute)
    BackHandler(selected != null && routing == null && !search.focused, unselect)
    // Se soltó el lugar (otra búsqueda, borrar lo escrito) o se cierra el mapa en grande: el mapa (y el widget, que
    // comparte la cámara) regresa a ti.
    // (Lee el de ahora, no el de esta composición: al abrir con un lugar de otra app, ya se eligió.)
    LaunchedEffect(selected) { if (search.selected == null && camera.showingPlace) camera.recenter() }
    DisposableEffect(camera) {
        onDispose {
            if (camera.showingPlace) camera.recenter()
            // Los arreglos del ruteador (~10 MB en un estado grande) se sueltan al cerrar el mapa en grande.
            MapPacks.get(context).releaseRouter()
        }
    }
    Card(modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().focusRequester(rest).focusTarget()) {
            val boxWidth = maxWidth
            val boxHeight = maxHeight
            // El teclado tapa lo de abajo (la ventana no se encoge): la lista termina arriba de él.
            val keyboard = with(LocalDensity.current) { WindowInsets.ime.getBottom(this).toDp() }
            // Con la tarjeta de un lugar o de la ruta, el lugar va en lo que queda libre: a su derecha en pantallas anchas o
            // arriba en las angostas (si no, la tarjeta tapa el pin).
            val carded = selected != null && !search.focused
            val cardShare = ((minOf(CARD_MAX, boxWidth - 24.dp) + 12.dp) / boxWidth).coerceIn(0f, 1f)
            val clear = when {
                !carded -> null
                cardShare <= 0.6f -> Rect(cardShare, 0f, 1f, 1f)
                else -> Rect(0f, 0f, 1f, 0.5f)
            }
            // Con la ruta: el mapa al norte y con el zoom que la enseña toda (en ese hueco).
            val fitW = boxWidth.value * (clear?.width ?: 1f)
            val fitH = boxHeight.value * (clear?.height ?: 1f)
            val fitDensity = LocalDensity.current.density
            val fit = route?.let { remember(it, fitW, fitH, fitDensity) { routeZoom(it, fitW, fitH, fitDensity) } }
            // Solo al salir la ruta (no al cambiar el hueco: lo que movieras con el dedo se quedaría).
            LaunchedEffect(fit?.second) { fit?.second?.let { (la, lo) -> camera.show(la, lo) } }
            if (granted) {
                LiveMap(
                    d, if (route != null) look.copy(orientation = MapOrientation.NORTH) else look, Modifier.fillMaxSize(), tileId,
                    camera = camera, pin = selected?.let { it.lat to it.lon },
                    overlay = selected == null,
                    onLongPress = { lat, lon -> pick(pointHit(null, lat, lon)) },
                    route = route?.points, fixedZoom = fit?.first, focus = clear,
                )
            } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Button(stringResource(R.string.tap_to_enable), ask, primary = true) }
            val searching = search.focused || search.query.isNotEmpty()
            // Con la guía encima el buscador sale de la pantalla: en Android 6 un campo enfocado debajo vuelve a abrir el teclado.
            if (Driving.session == null) Column(Modifier.align(Alignment.TopStart).padding(12.dp).width(minOf(boxWidth - 88.dp, 480.dp))) {
                PlaceSearchBox(d, search, focus, release, pick, onPlace, maxListHeight = (boxHeight - 100.dp - keyboard).coerceAtLeast(MIN_LIST))
                if (!searching) NextTurn(d, Modifier.padding(top = 8.dp))
            }
            Row(Modifier.align(Alignment.TopEnd).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Tu app de mapas, con su botón (como en el widget).
                if (app != null && boxWidth >= WIDE_TOP) IconButton(Icons.navigate, onNavigate, size = 48.dp, background = c.surface.copy(alpha = 0.9f), color = c.accent)
                if (route == null && granted) MapCompass(d, look, camera, size = 48.dp)
                IconButton(Icons.close, close, size = 48.dp, background = c.surface.copy(alpha = 0.9f))
            }
            val cardModifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 12.dp, bottom = 12.dp).widthIn(max = CARD_MAX)
            val request = routing
            if (selected != null && !search.focused) {
                if (request != null) RouteCard(d, request, app?.name, onOpen = { openPlace(context, app, selected) }, onOffline = onOffline, back = closeRoute, modifier = cardModifier)
                else PlaceCard(
                    d, selected, app?.name, onRoute = { routing = RouteRequest(selected) }, onOpen = { openPlace(context, app, selected) },
                    close = unselect, modifier = cardModifier,
                )
            }
        }
    }
}

/** Lo más ancho de la tarjeta del lugar o de la ruta. */
private val CARD_MAX = 460.dp

/** Lo menos que mide la lista de resultados (con el teclado arriba en pantallas bajas). */
private val MIN_LIST = 120.dp

/** Más angosto, arriba solo cabe el buscador y cerrar. */
private val WIDE_TOP = 560.dp

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
 * y nada lo tapa ([LocalHomeCovered]). Con [tileId], las teselas son las de ese widget y, salvo con [ownCamera]
 * (la vista previa del panel), también su cámara. [onTap] = qué hace un toque (sin él, nada); con un dedo se mueve
 * y abajo a la izquierda van el botón para regresar a ti, la calle donde vas y si el mapa es en línea o bajado.
 */
@Composable
private fun LiveMap(
    d: Dependencies, look: MapLook, modifier: Modifier, tileId: String? = null, ownCamera: Boolean = false, onTap: (() -> Unit)? = null,
    camera: MapCamera = sharedMapCamera(tileId?.takeUnless { ownCamera }),
    pin: Pair<Double, Double>? = null,
    overlay: Boolean = true,
    onLongPress: ((Double, Double) -> Unit)? = null,
    route: IntArray? = null,
    fixedZoom: Int? = null,
    focus: Rect? = null,
) {
    val c = LocalColors.current
    KeepTripRunning(d.trip)
    val context = LocalContext.current
    val store = rememberTileStore(look, c.dark, tileId)
    // Tapado (por el mapa en grande o un panel) se queda como está: no pide teselas ni anima.
    val active = !LocalHomeCovered.current
    val position = d.trip.position
    // Del bus (la del OBD si hay escáner, si no la del GPS): andando (o con Waze/Maps navegando) va de viaje.
    val kmh = d.bus.watch(Signal.SPEED)
    val navigating = d.navigation.instruction != null
    // Solo cambia al mover o centrar el mapa (no con cada movimiento del dedo).
    val centered by remember(camera) { derivedStateOf { camera.centered } }
    BoxWithConstraints(modifier) {
        if (position == null) {
            Label(stringResource(R.string.map_waiting), Typography.body, c.textMuted, maxLines = 2, modifier = Modifier.align(Alignment.Center).padding(16.dp))
            return@BoxWithConstraints
        }
        MapCanvas(
            store, position.latitude, position.longitude, d.trip.bearing, kmh, look, Modifier.fillMaxSize(), camera, navigating,
            active = active, onTap = onTap, pin = pin, onLongPress = onLongPress, route = route, fixedZoom = fixedZoom, focus = focus,
        )
        // Con la tarjeta de un lugar abajo a la izquierda, lo de aquí estorba (al cerrarla vuelve a ti).
        if (!overlay) return@BoxWithConstraints
        // Abajo a la izquierda (a la derecha van los créditos): centrar si lo moviste, la calle y si es en línea o bajado.
        val offline = look.detail == MapDetail.MINIMAL &&
            (MapPacks.get(context).covers(position.latitude, position.longitude) || OfflineMaps.get(context).covers(position.latitude, position.longitude))
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
 * El mapa vivo detrás de los widgets de una página (#18). Te sigue sin gestos (los de ahí son del inicio: deslizar de
 * página, dejar presionado para acomodar) y vas en [focus], el hueco que dejan los widgets (en fracciones de la
 * pantalla), con la flecha o tu auto visto de atrás ([car], sin él la flecha). Con Waze/Maps navegando, su siguiente
 * vuelta va arriba de ese hueco. Sin permiso de ubicación o sin lectura aún, no se dibuja (queda el fondo del tema).
 * [covered] = algo en grande lo tapa: se queda quieto.
 */
@Composable
internal fun MapBackdrop(d: Dependencies, focus: Rect?, car: CarPackage?, covered: Boolean) {
    val c = LocalColors.current
    KeepTripRunning(d.trip)
    val raw = d.prefs.mapLook
    val look = remember(raw) { MapLook.parse(raw.orEmpty()) }
    val store = rememberTileStore(look, c.dark, BACKDROP_KEY)
    val camera = sharedMapCamera(BACKDROP_KEY)
    val position = d.trip.position ?: return
    val kmh = d.bus.watch(Signal.SPEED)
    val navigating = d.navigation.instruction != null
    val wantsCar = d.prefs.mapBackdropCar && car != null
    val paint = d.prefs.carPaint
    val marker by produceState<ImageBitmap?>(null, wantsCar, car?.id, paint) {
        value = if (wantsCar) d.cars.snapshot(car, paint, MARKER_PX, turn = REAR_TURN, floor = false)?.solidPart()?.asImageBitmap() else null
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        MapCanvas(
            store, position.latitude, position.longitude, d.trip.bearing, kmh, look, Modifier.fillMaxSize(), camera, navigating,
            active = !LocalHomeCovered.current && !covered, focus = focus, interactive = false, marker = marker,
        )
        if (focus != null) Box(Modifier.offset(maxWidth * focus.left, maxHeight * focus.top).size(maxWidth * focus.width, maxHeight * focus.height)) {
            NextTurn(d, Modifier.align(Alignment.TopStart).padding(12.dp).widthIn(max = 420.dp))
        }
    }
}

/**
 * Solo el auto, sin el aire ni la sombra de los lados: el cuadro del giro mide lo ancho del auto visto de lado y, de
 * espaldas, el auto ocupa una parte (se vería en miniatura). Corta a lo que es casi opaco ([SOLID_ALPHA]).
 */
internal fun Bitmap.solidPart(): Bitmap {
    val px = IntArray(width * height)
    getPixels(px, 0, width, 0, 0, width, height)
    var left = width; var right = -1; var top = height; var bottom = -1
    for (y in 0 until height) for (x in 0 until width) {
        if ((px[y * width + x] ushr 24) >= SOLID_ALPHA) {
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            if (y > bottom) bottom = y
        }
    }
    if (right < left || bottom < top) return this
    return Bitmap.createBitmap(this, left, top, right - left + 1, bottom - top + 1)
}

/** Desde qué tan opaco cuenta como auto (la sombra es más transparente). */
private const val SOLID_ALPHA = 200

/** El almacén y la cámara del mapa de fondo (uno para todas las páginas que lo usen). */
private const val BACKDROP_KEY = "home-backdrop"

/** Tu auto de espaldas: en los paquetes, el cuadro a 3/4 de vuelta del primero. */
internal const val REAR_TURN = 0.75f

/** Lo ancho de la foto de tu auto en el mapa (se dibuja hasta a 240 dp: así se ve nítido en pantallas densas). */
internal const val MARKER_PX = 640

/**
 * Una cámara por widget Mapa ([key] = su id), la misma en el widget y en grande: lo que muevas o acerques se conserva
 * al abrirlo o cerrarlo (solo cambia el tamaño). Sin [key] (vistas previas), una propia.
 */
private val mapCameras = HashMap<String, MapCamera>()

/** Se quitó el widget Mapa [tileId]: su cámara y su almacén ya no sirven (el almacén ya soltó todo al dejar de verse). */
private fun forgetMap(tileId: String) {
    mapCameras.remove(tileId)
    tileStores.remove(tileId)
}

/** El mapa en grande que se ve: de qué widget es y si algo lo tapa. */
private class ExpandedMap(val tileId: String, val covered: Boolean)

/** El mapa en grande a la vista (null = ninguno); lo pone [MapExpanded]. */
private var expandedMap by mutableStateOf<ExpandedMap?>(null)

@Composable
private fun sharedMapCamera(key: String?): MapCamera =
    if (key == null) rememberMapCamera() else remember(key) { mapCameras.getOrPut(key) { MapCamera() } }

/**
 * La brújula del mapa de LiteDash: apunta al norte como esté girado el mapa ahora (con Norte de fábrica, gira de viaje)
 * y un toque cambia todos los mapas entre «siempre al norte» y «hacia donde vas».
 */
@Composable
private fun MapCompass(d: Dependencies, look: MapLook, camera: MapCamera, size: Dp = 44.dp) {
    // Como esté girado el mapa ahora (con Automático gira al inclinarse en lo más cerca, aunque estés detenido).
    val heading = camera.turning
    CompassButton(
        camera.turnDegrees, northUp = !heading,
        onClick = { applyMapLook(d, look.copy(orientation = if (heading) MapOrientation.NORTH_UP else MapOrientation.HEADING)) },
        size = size,
    )
}

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
 * dibuja ya en sus colores), por claro u oscuro; el completo usa un filtro. Con [shareKey] (el id del widget), el
 * widget y su mapa en grande usan el mismo almacén: al abrirlo o cerrarlo las teselas ya están.
 */
@Composable
internal fun rememberTileStore(look: MapLook, themeDark: Boolean, shareKey: String? = null, realRoadDp: Float = 0f): TileStore {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val darkTiles = look.detail == MapDetail.MINIMAL && look.dark(themeDark)
    val store = remember(look.detail, darkTiles, shareKey, realRoadDp) {
        if (shareKey == null) return@remember newTileStore(context, look.detail, darkTiles, density)
        val kind = "${look.detail}/$darkTiles/$density/$realRoadDp"
        // Uno por widget: al cambiar de estilo o de tema, el anterior se suelta.
        tileStores[shareKey]?.takeIf { it.first == kind }?.second
            ?: newTileStore(context, look.detail, darkTiles, density, realRoadDp).also { tileStores[shareKey] = kind to it }
    }
    // Tapado por su propio mapa en grande no cuenta como tapado: se rige por lo que tape a ese (comparten almacén).
    val covered = expandedMap?.takeIf { shareKey != null && it.tileId == shareKey }?.covered ?: LocalHomeCovered.current
    LifecycleStartEffect(store, covered) {
        // El almacén cuenta quién lo ve: solo lo suelta quien lo tomó.
        if (!covered) store.resume()
        onStopOrDispose { if (!covered) store.pause() }
    }
    return store
}

/** El almacén de cada widget Mapa (por su id) y de qué estilo es. */
private val tileStores = HashMap<String, Pair<String, TileStore>>()

private fun newTileStore(context: Context, detail: MapDetail, dark: Boolean, density: Float, realRoadDp: Float = 0f): TileStore {
    val source = when (detail) {
        MapDetail.FULL -> OsmTiles
        MapDetail.MINIMAL -> minimalTiles(context, dark, density, realRoadDp)
    }
    return TileStore(context, MAP_USER_AGENT, source)
}

/** Las teselas del minimalista (claras u oscuras), a la medida de la pantalla. */
private fun minimalTiles(context: Context, dark: Boolean, density: Float, realRoadDp: Float = 0f) =
    VectorTiles(context, if (dark) MinimalPalette.DARK else MinimalPalette.LIGHT, tilePx(density).toInt(), density, MAP_USER_AGENT, realRoadDp)

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
            // Las mismas teselas que el widget (no se bajan dos veces), con su propia cámara: moverla no mueve el widget.
            val preview = @Composable { m: Modifier -> if (granted) Card(m) { LiveMap(d, look, Modifier.fillMaxSize(), tileId, ownCamera = true) } }
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

/**
 * Ajustes → Apps → Navegación → Sin conexión (con el estilo que tenga el mapa de LiteDash). Abajo, siempre (también a
 * quien viaja), pedir el mapa de otro país: [requestMap] abre el reporte ya elegido.
 */
@Composable
internal fun MapOfflineSettings(d: Dependencies, requestMap: () -> Unit) {
    OfflineSection(d, MapLook.parse(d.prefs.mapLook.orEmpty()).detail)
    Button(stringResource(R.string.map_request_country), requestMap)
}

/** El aspecto de LiteDash: se guarda y se pone en todos los widgets Mapa del inicio (de todas las páginas). */
private fun applyMapLook(d: Dependencies, look: MapLook) {
    val data = look.encode()
    d.prefs.mapLook = data.ifEmpty { null }
    d.dashboard.changeDataEverywhere { if (it.type == TileTypes.MAP) data else null }
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
        listOf(
            MapOrientation.NORTH to stringResource(R.string.map_north), MapOrientation.HEADING to stringResource(R.string.map_heading),
            MapOrientation.NORTH_UP to stringResource(R.string.map_north_up),
        ),
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
    val packs = remember { MapPacks.get(context) }
    LaunchedEffect(Unit) { packs.refresh() }
    // Aquí hace falta saber dónde estás (tu estado arriba, tu zona en el mapa) aunque ningún widget use el GPS.
    LifecycleStartEffect(d.trip) {
        d.trip.acquire()
        onStopOrDispose { d.trip.release() }
    }
    val position = d.trip.position
    // Por estado (todo tu estado, de nuestro repo) o por zona (un círculo, en cualquier parte del mundo). De entrada,
    // por estado; por zona solo si ya se sabe que donde estás no hay mapa del estado (y no has bajado ninguno): sin
    // ubicación se quedaba en «Por zona» y los estados ni se veían.
    val noState = packs.installed.isEmpty() && packs.regions.isNotEmpty() && position?.let { packs.regionAt(it.latitude, it.longitude) == null } == true
    var picked by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val byZone = picked ?: noState
    Segmented(
        listOf(false to stringResource(R.string.map_offline_by_state), true to stringResource(R.string.map_offline_by_zone)),
        byZone,
        { picked = it },
    )
    if (!byZone) {
        StatePackSection(d, packs)
        return
    }
    Label(stringResource(R.string.map_offline_help), Typography.small, c.textMuted)
    val locale = currentLocale()
    val dateFormat = remember(locale) { SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "dMMM"), locale) }
    // La zona guardada que se está viendo (null = elegir una nueva); si se borra, se vuelve a elegir.
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = offline.areas.firstOrNull { it.id == selectedId }
    val miles = d.prefs.units.miles
    for (area in offline.areas) {
        val here = position != null && area.contains(position.latitude, position.longitude)
        val chosen = area.id == selectedId
        Label(
            stringResource(
                R.string.map_offline_area, stringResource(if (here) R.string.map_offline_here else R.string.map_offline_other),
                radiusLabel(area.km, miles), Formatter.formatShortFileSize(context, area.bytes), dateFormat.format(Date(area.savedAt)),
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
        // Sin total todavía se están contando las teselas (un instante).
        if (job.total > 0) Label(stringResource(R.string.map_offline_progress, job.done, job.total, Formatter.formatShortFileSize(context, job.bytes)), Typography.small, c.textMuted)
        if (job.waiting) Label(stringResource(R.string.map_offline_waiting_server), Typography.small, c.warning)
        Button(stringResource(R.string.cancel), { offline.cancel() })
        return
    }
    job?.error?.let { error ->
        val text = when (error) {
            OfflineError.BLOCKED -> R.string.map_offline_blocked
            OfflineError.TOO_BIG -> R.string.map_offline_too_big
            OfflineError.NETWORK -> R.string.map_offline_failed
        }
        Label(stringResource(text), Typography.small, c.warning)
    }
    var km by rememberSaveable { mutableIntStateOf(OFFLINE_RADII[1]) }
    // Con una zona elegida el radio es el suyo. En millas solo cambia la etiqueta: se baja lo mismo.
    if (selected == null) Segmented(
        OFFLINE_RADII.map { it to radiusLabel(it, miles) },
        km, { km = it },
    )
    if (position == null) {
        Button(stringResource(R.string.map_offline_download), {}, primary = true, enabled = false)
        Label(stringResource(R.string.map_offline_waiting), Typography.small, c.textMuted)
    } else {
        OfflinePicker(offline, position.latitude, position.longitude, km, selected) { selectedId = null }
    }
}

/**
 * El mapa de tu estado completo (M2): el de donde estás arriba (bajarlo, su avance, actualizarlo o borrarlo), luego los
 * que ya bajaste y los demás por país (los países que hay, siempre a la vista). Sin índice (nunca hubo internet), cómo
 * conseguirlo.
 */
@Composable
private fun StatePackSection(d: Dependencies, packs: MapPacks) {
    val c = LocalColors.current
    val position = d.trip.position
    val language = currentLocale().language
    Label(stringResource(R.string.map_pack_help), Typography.small, c.textMuted)
    // Siempre a la vista: donde aún no hay mapa del estado, las zonas sirven en cualquier parte.
    Label(stringResource(R.string.map_pack_tip), Typography.small, c.accent)
    if (packs.regions.isEmpty()) {
        Label(stringResource(R.string.map_pack_no_index), Typography.small, c.textMuted)
        return
    }
    val here = position?.let { packs.regionAt(it.latitude, it.longitude) }
    val mine = packs.installed.mapNotNull { p -> packs.regions.firstOrNull { it.id == p.id } }
    val shown = (listOfNotNull(here) + mine).distinctBy { it.id }
    for (r in shown) StatePackRow(packs, r, language)
    // Los países que hay, siempre a la vista (escondidos, la gente pedía países que ya estaban). Al tocar uno, sus
    // estados; sin nada arriba (no se sabe dónde estás ni has bajado nada) ya va abierto el tuyo, por la región del sistema.
    val countries = remember(packs.regions) { packs.regions.map { it.country }.distinct() }
    val locale = currentLocale()
    var pickedCountry by rememberSaveable { mutableStateOf<String?>(null) }
    val country = pickedCountry
        ?: if (shown.isEmpty()) java.util.Locale.getDefault().country.takeIf { it in countries } ?: countries.first() else null
    Label(stringResource(R.string.map_pack_others), Typography.label, c.textMuted)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (code in countries) Button(java.util.Locale("", code).getDisplayCountry(locale).ifEmpty { code }, { pickedCountry = code }, primary = code == country)
    }
    if (country == null) return
    // Sin escribir, los del país elegido; escribiendo, de cualquier país.
    var query by rememberSaveable { mutableStateOf("") }
    TextInput(query, { query = it }, stringResource(R.string.map_pack_search), Modifier.fillMaxWidth(), icon = true)
    val q = normalize(query.trim())
    val names = remember(packs.regions, language) { packs.regions.associate { it.id to normalize(it.name(language)) } }
    val others = packs.regions
        .filter { shown.none { s -> s.id == it.id } && if (q.isEmpty()) it.country == country else q in names[it.id].orEmpty() }
        .sortedBy { it.name(language) }
    for (r in others) StatePackRow(packs, r, language)
}

/**
 * Un estado en un renglón: su nombre y tamaño a la izquierda y lo que se puede hacer a la derecha (bajar, actualizar o
 * borrar); bajando, su avance y Cancelar debajo.
 */
@Composable
private fun StatePackRow(packs: MapPacks, region: MapRegion, language: String) {
    val c = LocalColors.current
    val context = LocalContext.current
    val name = region.name(language)
    val saved = packs.installed.firstOrNull { it.id == region.id }
    val job = packs.job?.takeIf { it.id == region.id }
    val downloading = job != null && !job.failed
    // Uno a la vez: mientras baja otro, este espera.
    val busy = packs.job?.let { !it.failed } == true
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Todo el paquete: mapa, búsqueda y calles para la ruta.
            val size = Formatter.formatShortFileSize(context, region.totalSize)
            Label(
                if (saved != null) stringResource(R.string.map_pack_saved, name, size) else "$name · $size",
                Typography.body, if (saved != null) c.accent else c.text, maxLines = 2, modifier = Modifier.weight(1f),
            )
            when {
                downloading -> Button(stringResource(R.string.cancel), { packs.cancel() })
                saved == null -> Button(stringResource(R.string.map_pack_download), { packs.download(region) }, primary = true, enabled = !busy)
                else -> {
                    if (packs.outdated(region)) Button(stringResource(R.string.map_pack_update), { packs.download(region) }, primary = true, enabled = !busy)
                    Confirmed(
                        stringResource(R.string.map_offline_delete), stringResource(R.string.map_pack_delete_question, name),
                        stringResource(R.string.confirm_delete), message = stringResource(R.string.map_pack_delete_message),
                    ) { packs.delete(region.id) }
                }
            }
        }
        if (downloading) {
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.surfaceHigh)) {
                Box(Modifier.fillMaxWidth(job!!.progress).fillMaxHeight().background(c.accent))
            }
        }
        if (job?.failed == true) Label(stringResource(R.string.map_pack_failed), Typography.small, c.warning)
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
    // Lo que se baja va siempre en claro (el oscuro se pinta al dibujar); una sola fuente para las dos descargas.
    val download = remember(density) { minimalTiles(context, dark = false, density) }
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
                offline.download(selected.lat, selected.lon, selected.km, download, MAP_USER_AGENT)
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
    // Lejos del ecuador las teselas son más chicas y caben menos km en el tope de OpenFreeMap.
    val allowed = tiles?.let { OfflineMaps.withinLimits(it) } ?: false
    if (bytes != null) {
        Label(
            free?.let { stringResource(R.string.map_offline_size_free, Formatter.formatShortFileSize(context, bytes), Formatter.formatShortFileSize(context, it)) }
                ?: stringResource(R.string.map_offline_size, Formatter.formatShortFileSize(context, bytes)),
            Typography.small, c.textMuted,
        )
        if (!allowed) Label(stringResource(R.string.map_offline_too_big), Typography.small, c.warning)
        else if (free != null && !fits) Label(stringResource(R.string.map_offline_no_room), Typography.small, c.warning)
    }
    Button(stringResource(R.string.map_offline_download), {
        val (centerLat, centerLon) = camera.center(lat, lon)
        offline.download(centerLat, centerLon, km, download, MAP_USER_AGENT)
    }, primary = true, enabled = fits && allowed)
}

/**
 * Radios que se pueden bajar (km); 20, de fábrica. Hasta 30: cabe en el tope de OpenFreeMap por descarga cerca del
 * ecuador (México); más al norte o al sur, el de 30 puede no caber y se avisa. Lo grande llegará por estado.
 */
private val OFFLINE_RADII = listOf(10, 20, 30)

/** El radio de una zona como lo pide Ajustes: "25 km" o, en millas, "16 mi" (se baja lo mismo; solo cambia la etiqueta). */
@Composable
private fun radiusLabel(km: Int, miles: Boolean): String =
    if (miles) stringResource(R.string.radio_mi, roundMiles(km)) else stringResource(R.string.map_offline_radius, km)

/** Alto del mapa para elegir la zona (y de la vista previa de Ajustes en pantallas angostas). */
private val OFFLINE_PREVIEW_HEIGHT = 240.dp

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
