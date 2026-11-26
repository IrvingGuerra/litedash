package com.irvingguerra.litedash

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.StatFs
import android.os.storage.StorageManager
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Https
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Radius
import com.irvingguerra.litedash.core.ui.Segmented
import com.irvingguerra.litedash.core.ui.SwitchRow
import com.irvingguerra.litedash.core.ui.TextInput
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.core.vehicle.Signal
import com.irvingguerra.litedash.core.vehicle.watch
import com.irvingguerra.litedash.feature.apps.App
import com.irvingguerra.litedash.feature.apps.normalize
import com.irvingguerra.litedash.feature.car.CarPackage
import com.irvingguerra.litedash.feature.car.snapshot
import com.irvingguerra.litedash.feature.dashboard.TileTypes
import com.irvingguerra.litedash.feature.map.IndexState
import com.irvingguerra.litedash.feature.map.MapCamera
import com.irvingguerra.litedash.feature.map.MapCanvas
import com.irvingguerra.litedash.feature.map.MapLook
import com.irvingguerra.litedash.feature.map.MapOrientation
import com.irvingguerra.litedash.feature.map.MapPalettes
import com.irvingguerra.litedash.feature.map.MapPacks
import com.irvingguerra.litedash.feature.map.MapRegion
import com.irvingguerra.litedash.feature.map.MapStyle
import com.irvingguerra.litedash.feature.map.MapZoom
import com.irvingguerra.litedash.feature.map.NamedRegion
import com.irvingguerra.litedash.feature.map.MapPalette
import com.irvingguerra.litedash.feature.map.OfflineArea
import com.irvingguerra.litedash.feature.map.OfflineError
import com.irvingguerra.litedash.feature.map.OfflineMaps
import com.irvingguerra.litedash.feature.map.PackError
import com.irvingguerra.litedash.feature.map.Route
import com.irvingguerra.litedash.feature.map.SearchHit
import com.irvingguerra.litedash.feature.map.StreetsGap
import com.irvingguerra.litedash.feature.map.TileStore
import com.irvingguerra.litedash.feature.map.VectorTiles
import com.irvingguerra.litedash.feature.map.rememberFreeMapCamera
import com.irvingguerra.litedash.feature.map.rememberMapCamera
import com.irvingguerra.litedash.feature.map.tilePx
import com.irvingguerra.litedash.feature.map.zoomToFit
import com.irvingguerra.litedash.feature.music.NotificationService
import java.text.SimpleDateFormat
import java.text.Collator
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Widget Mapa: tu posición del GPS en el mapa (de OpenFreeMap, con el aspecto de Ajustes → Apps → Navegación:
 * [rememberMapLook]; lo que un widget guardó aparte hasta la 1.5.15 ya no se lee); navegando, la siguiente vuelta flota
 * arriba a la izquierda y Casa y Trabajo, abajo a la derecha (sobre los créditos). Un toque hace [onTap]: el mapa de LiteDash en grande o, con
 * «Navegar con: App de mapas» ([app] no es null), esa app. En una fila, o sin permiso de ubicación, se ve
 * solo la vuelta, Casa y Trabajo ([NavigationTile]) y, sin permiso, un botón para pedirlo. Con la guía de LiteDash
 * navega ([GuideView], lo que quepa en su tamaño) y un toque la ve en grande ([onGuide]). GPS y descargas solo
 * mientras se ve. Al quitar el widget se sueltan su cámara y su almacén de teselas.
 */
@Composable
fun MapTile(
    d: Dependencies, tileId: String, app: App?, editing: Boolean, onPlace: (String) -> Unit, onTap: () -> Unit, onSearch: () -> Unit,
    onGuide: () -> Unit,
) {
    val c = LocalColors.current
    val (granted, ask) = rememberGpsPermission(d)
    val look = rememberMapLook(d)
    // Sale de la pantalla: si fue porque se quitó el widget, lo suyo ya no sirve (si no, se queda para cuando vuelva).
    DisposableEffect(tileId) {
        onDispose { if (d.dashboard.allTiles.none { it.id == tileId }) forgetMap(tileId) }
    }
    val session = Driving.session
    if (session != null && granted) {
        // Solo un mapa anima la guía: el primer widget Mapa de la página (los demás, solo el mapa con la ruta, quieto), y
        // mientras su mapa en grande se ve (también al crecer o encogerse), ese.
        val leads = d.dashboard.shown.firstOrNull { it.type == TileTypes.MAP }?.id == tileId
        val bigShown = expandedMap?.tileId == tileId
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val size = guideSizeFor(maxWidth.value, maxHeight.value)
            Card(Modifier.fillMaxSize()) {
                GuideView(
                    d, session, size, tileId, Modifier.fillMaxSize(), controls = !editing, onTap = onGuide,
                    active = leads && !bigShown, cards = leads,
                )
            }
        }
        return
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
        // Con tu app de mapas el toque la abre: sin lupa (el buscador es del mapa de LiteDash).
        val external = app != null
        val turnWidth = maxWidth - if (external) 16.dp else 68.dp
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
            // La lupa abre el mapa en grande con el buscador (al acomodar se esconde: estorba al botón de tamaño).
            // Sin brújula: esa es de la navegación (el mapa en grande y la guía).
            if (!editing && !external) IconButton(
                Icons.search, onSearch, Modifier.align(Alignment.TopEnd).padding(8.dp), size = 44.dp, background = c.surface.copy(alpha = 0.85f),
                color = c.accent, label = stringResource(R.string.search_place),
            )
        }
    }
}

/**
 * El mapa de LiteDash en grande (el toque al widget Mapa, y la barra y el volante si no abren tu app), con el aspecto de
 * Ajustes y la cámara del widget [tileId] si se abrió desde uno: arriba el buscador (su lista se cierra al tocar fuera o
 * con atrás), el mapa en vivo (con dos dedos o + y − se acerca o aleja; dejar presionado marca un punto), la siguiente
 * vuelta, Casa y Trabajo, y botones para cerrar y, con una app de mapas, para abrirla. Con la guía es la vista completa
 * de la navegación ([GuideView]); la lupa trae el buscador (para cambiar de destino) y su ✕ regresa a la guía. «Ir»
 * empieza la guía y avisa a [onGo] (el inicio decide si este mapa se cierra).
 */
@Composable
fun MapExpanded(
    d: Dependencies, tileId: String?, app: App?, onPlace: (String) -> Unit, onNavigate: () -> Unit, onOffline: () -> Unit,
    onGo: () -> Unit, close: () -> Unit, modifier: Modifier = Modifier,
) {
    val c = LocalColors.current
    val (granted, ask) = rememberGpsPermission(d)
    val look = rememberMapLook(d)
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
    // Con la guía: el buscador a la vista ([browsing], con la lupa o un lugar de otra app) o la navegación.
    val session = Driving.session
    var browsing by remember { mutableStateOf(false) }
    // Lo que llegó de otra app (geo:) o de la lupa del widget.
    LaunchedEffect(PlaceRequests.place, PlaceRequests.query, PlaceRequests.focus) {
        if (PlaceRequests.pending) browsing = true
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
    // De vuelta a la guía (✕ o atrás): el lugar y la ruta del buscador se sueltan (si no, seguían calculándose y atrás
    // movía el mapa de la guía) y el mapa vuelve a seguirte.
    val toGuide = {
        routing = null
        search.clear()
        browsing = false
        if (camera.showingPlace) camera.recenter()
    }
    // Lo del buscador solo mientras se ve (con la guía en pantalla, atrás no le toca).
    val searchShown = session == null || browsing
    // Atrás regresa a la guía (antes cierra el lugar o la ruta: esos van después y les toca primero).
    BackHandler(session != null && browsing, toGuide)
    BackHandler(searchShown && routing != null && !search.focused, closeRoute)
    BackHandler(searchShown && selected != null && routing == null && !search.focused, unselect)
    // «Ir»: la guía empieza aquí mismo (el lugar y la ruta se sueltan: la lupa abre el buscador limpio) y el mapa te sigue.
    val go: (Route) -> Unit = { r ->
        val to = routing?.to ?: selected
        if (to != null) {
            Driving.session = DriveSession(to, r)
            routing = null
            search.clear()
            browsing = false
            camera.recenter()
            onGo()
        }
    }
    // La lista del buscador (recientes o resultados) se cierra con el teclado al tocar fuera de ella: el mapa o cualquier
    // hueco (el toque sigue a lo que tocaste). Lo escrito se queda; tocar el campo la vuelve a abrir.
    var searchBounds by remember { mutableStateOf(Rect.Zero) }
    val listOpen by rememberUpdatedState(search.focused || (search.open && search.query.isNotEmpty() && selected == null))
    val bounds by rememberUpdatedState(searchBounds)
    val closeList: () -> Unit = {
        search.open = false
        release()
    }
    val outside by rememberUpdatedState(closeList)
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
    // La lupa de la guía abre el buscador listo para escribir.
    LaunchedEffect(browsing) { if (browsing && session != null && !search.focused && search.selected == null) focus.requestFocus() }
    if (session != null && !browsing) {
        Card(modifier.fillMaxSize()) {
            GuideView(d, session, GuideSize.FULL, tileId, Modifier.fillMaxSize()) {
                IconButton(
                    Icons.search, { browsing = true }, size = 48.dp, background = c.surface.copy(alpha = 0.96f), color = c.accent,
                    label = stringResource(R.string.search_place),
                )
                IconButton(Icons.close, close, size = 48.dp, background = c.surface.copy(alpha = 0.96f))
            }
        }
        return
    }
    Card(modifier.fillMaxSize()) {
        BoxWithConstraints(
            Modifier.fillMaxSize().focusRequester(rest).focusTarget().pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (listOpen && !bounds.contains(down.position)) outside()
                }
            },
        ) {
            val boxWidth = maxWidth
            val boxHeight = maxHeight
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
            val fit = route?.let { remember(it, fitW, fitH, fitDensity) { routeFit(it, fitW, fitH, fitDensity) } }
            // Solo al salir la ruta (no al cambiar el hueco: lo que movieras con el dedo se quedaría).
            LaunchedEffect(fit?.lat, fit?.lon) { fit?.let { camera.show(it.lat, it.lon, it.scale) } }
            if (granted) {
                LiveMap(
                    d, if (route != null) look.copy(orientation = MapOrientation.NORTH) else look, Modifier.fillMaxSize(), tileId,
                    camera = camera, pin = selected?.let { it.lat to it.lon },
                    overlay = selected == null,
                    onLongPress = { lat, lon -> pick(pointHit(null, lat, lon)) },
                    route = route?.points, fixedZoom = fit?.zoom, focus = clear,
                )
            } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Button(stringResource(R.string.tap_to_enable), ask, primary = true) }
            val searching = search.focused || search.query.isNotEmpty()
            Column(
                Modifier.align(Alignment.TopStart).padding(12.dp).width(minOf(boxWidth - 88.dp, 480.dp))
                    .onGloballyPositioned { searchBounds = it.boundsInParent() },
            ) {
                PlaceSearchBox(d, search, focus, closeList, pick, onPlace, listSpace = boxHeight - 100.dp)
                // Escribiendo no se ve, pero sigue compuesta: es la que pide los avisos de Waze/Maps (sin ella el mapa dejaría
                // de ir «de viaje» mientras escribes).
                NextTurn(d, Modifier.padding(top = 8.dp), shown = !searching)
            }
            Row(Modifier.align(Alignment.TopEnd).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Tu app de mapas, con su botón (como en el widget).
                if (app != null && boxWidth >= WIDE_TOP) IconButton(Icons.navigate, onNavigate, size = 48.dp, background = c.surface.copy(alpha = 0.9f), color = c.accent)
                // Con la guía, el ✕ cierra el buscador y regresa a ella.
                IconButton(Icons.close, if (session != null) toGuide else close, size = 48.dp, background = c.surface.copy(alpha = 0.9f))
            }
            // Acercar y alejar con un toque (en pantallas de auto el pellizco falla). Con la ruta el zoom es el que la enseña
            // toda (fijo): ahí no van.
            if (route == null && granted) Column(
                Modifier.align(Alignment.CenterEnd).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(
                    Icons.plus, { camera.zoomBy(1) }, size = 48.dp, background = c.surface.copy(alpha = 0.9f), color = c.text,
                    label = stringResource(R.string.map_zoom_near),
                )
                IconButton(
                    Icons.minus, { camera.zoomBy(-1) }, size = 48.dp, background = c.surface.copy(alpha = 0.9f), color = c.text,
                    label = stringResource(R.string.map_zoom_far),
                )
            }
            val cardModifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 12.dp, bottom = 12.dp).widthIn(max = CARD_MAX)
            val request = routing
            if (selected != null && !search.focused) {
                if (request != null) RouteCard(d, request, app?.name, onGo = go, onOpen = { openPlace(context, app, selected) }, onOffline = onOffline, back = closeRoute, modifier = cardModifier)
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

/** Más angosto, arriba solo cabe el buscador y cerrar. */
private val WIDE_TOP = 560.dp

/**
 * La siguiente vuelta que dice el aviso de Waze o Maps, en una tarjeta sobre el mapa (nada si no navegas). Mientras está
 * compuesta escucha los avisos; con [shown] = false no se dibuja pero sigue escuchando.
 */
@Composable
private fun NextTurn(d: Dependencies, modifier: Modifier, shown: Boolean = true) {
    val c = LocalColors.current
    val repo = d.navigation
    DisposableEffect(repo) {
        repo.use { NotificationService.active() }
        onDispose { repo.release() }
    }
    val instruction = repo.instruction?.takeIf { shown } ?: return
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
    val store = rememberTileStore(d, look, c.dark, tileId)
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
        val offline = MapPacks.get(context).covers(position.latitude, position.longitude) || OfflineMaps.get(context).covers(position.latitude, position.longitude)
        // Angosto, los créditos ocupan dos renglones a lo ancho: lo de la izquierda va arriba de ellos y sin el estado.
        val narrow = maxWidth < NARROW_MAP
        val tall = maxHeight >= STATUS_HEIGHT
        val showStatus = tall && !narrow
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = 8.dp, end = 8.dp, bottom = if (narrow) 44.dp else 8.dp).widthIn(max = if (narrow) maxWidth else maxWidth * 0.55f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (!centered) IconButton(Icons.recenter, camera::recenter, size = 44.dp, background = c.surface.copy(alpha = 0.9f), color = c.accent)
            camera.street?.let { MapPill(Icons.location, it, c.text) }
            // En línea con la fecha de la pantalla atrás no baja nada (HTTPS falla): se dice eso, aunque el mapa sea chico.
            val clockWrong = !offline && Https.clockLooksWrong()
            // Un mapa bajado que se ve sin sus calles (se usan las de las zonas o de internet): se dice y, si se puede, se bajan.
            val gap = MapPacks.get(context).streetsGapAt(position.latitude, position.longitude)
            if (clockWrong) MapPill(Icons.warning, stringResource(R.string.clock_wrong), c.warning, maxLines = 3)
            else if (gap != null && tall) StreetsGapNote(MapPacks.get(context), gap.first, gap.second, pill = true)
            else if (showStatus) {
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
 * pantalla), con la flecha. Con Waze/Maps navegando, su siguiente
 * vuelta va arriba de ese hueco. Con la guía de LiteDash, si es el mapa que navega en la página ([guide]), lleva la
 * cámara de la navegación (sus tarjetas van encima del tablero, [GuideCards]; la llegada tapa [cardsH] px abajo del
 * hueco); si navega un widget Mapa, solo la ruta, quieta (una sola vista anima). Sin permiso de ubicación o sin lectura
 * aún, no se dibuja (queda el fondo del tema). [covered] = algo en grande lo tapa: se queda quieto.
 */
@Composable
internal fun MapBackdrop(d: Dependencies, focus: Rect?, covered: Boolean, guide: Boolean, cardsH: Int) {
    val c = LocalColors.current
    // Con algo en grande encima el fondo no se dibuja: tampoco hace falta el GPS.
    if (!covered) KeepTripRunning(d.trip)
    val session = Driving.session
    val guiding = guide && session != null
    val heading = guiding && !d.prefs.guideMapNorth
    val raw = d.prefs.mapLook
    val look = remember(raw, session != null, heading, guiding) {
        MapLook.parse(raw.orEmpty()).let {
            when {
                heading -> it.copy(orientation = MapOrientation.HEADING)
                guiding -> it.copy(orientation = MapOrientation.NORTH_UP)
                // La ruta de la guía que lleva un widget: quieta aquí.
                session != null -> it.copy(flow = false)
                else -> it
            }
        }
    }
    val store = rememberTileStore(d, look, c.dark, BACKDROP_KEY)
    val camera = sharedMapCamera(BACKDROP_KEY)
    val position = d.trip.position ?: return
    val kmh = d.bus.watch(Signal.SPEED)
    val navigating = d.navigation.instruction != null
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val hole = focus ?: WHOLE_SCREEN
        val height = constraints.maxHeight.toFloat()
        val s = session?.state
        val onRoute = guiding && s != null && !s.offRoute
        val view = if (guiding && cardsH > 0 && height > 0f) {
            val bottom = (hole.bottom - cardsH / height).coerceAtLeast(hole.top + hole.height * 0.5f)
            Rect(hole.left, hole.top, hole.right, bottom)
        } else focus
        MapCanvas(
            store, if (onRoute) s.lat else position.latitude, if (onRoute) s.lon else position.longitude,
            if (guiding) youBearing(d, session, s) else d.trip.bearing, kmh, look, Modifier.fillMaxSize(), camera, navigating,
            active = !LocalHomeCovered.current && !covered, focus = view, interactive = false,
            navigation = guiding, turnAhead = onRoute && turnAhead(session.route, s),
            route = session?.route?.points, routeDone = s?.let { it.point + it.fraction } ?: 0f,
        )
        if (!guiding && focus != null) Box(Modifier.offset(maxWidth * hole.left, maxHeight * hole.top).size(maxWidth * hole.width, maxHeight * hole.height)) {
            NextTurn(d, Modifier.align(Alignment.TopStart).padding(12.dp).widthIn(max = 420.dp))
        }
    }
}

/** Sin hueco medido todavía: toda la pantalla. */
private val WHOLE_SCREEN = Rect(0f, 0f, 1f, 1f)

/** Tu auto de espaldas para ponerlo sobre el mapa ([car] null = sin auto: la flecha); se dibuja y recorta fuera del hilo principal. */
@Composable
internal fun rememberRearMarker(d: Dependencies, car: CarPackage?): ImageBitmap? {
    val paint = d.prefs.carPaint
    val marker by produceState<ImageBitmap?>(null, car?.id, paint) {
        value = car?.let { d.cars.snapshot(it, paint, MARKER_PX, turn = REAR_TURN, floor = false) }
            ?.let { withContext(Dispatchers.Default) { it.solidPart().asImageBitmap() } }
    }
    return marker
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
internal fun sharedMapCamera(key: String?): MapCamera =
    if (key == null) rememberMapCamera() else remember(key) { mapCameras.getOrPut(key) { MapCamera() } }

/** Una pastilla sobre el mapa: ícono y un texto corto (de [maxLines] renglones). */
@Composable
private fun MapPill(icon: ImageVector, text: String, color: Color, maxLines: Int = 1) {
    val c = LocalColors.current
    Row(
        Modifier.clip(RoundedCornerShape(Radius.small)).background(c.surface.copy(alpha = 0.88f)).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, c.accent, 14.dp)
        Label(text, Typography.small, color, maxLines = maxLines)
    }
}

/** Más bajo, el mapa no lleva la pastilla de en línea o sin conexión (estorba). */
private val STATUS_HEIGHT = 220.dp

/** Más angosto, los créditos del mapa van en dos renglones (como en `MapCanvas`). */
private val NARROW_MAP = 320.dp

/** Como se presenta LiteDash ante los servidores de mapas (lo piden sus políticas de uso). */
private val MAP_USER_AGENT = "LiteDash/${BuildConfig.VERSION_NAME} (+https://litedash.dev)"

/**
 * Las teselas de [look], bajando solo mientras se ve y nada lo tapa: se dibujan ya en los colores de su paleta (día o
 * noche, de fábrica, como tu interfaz o de tu color). Con [shareKey] (el id del widget), el widget y su mapa en grande
 * usan el mismo almacén: al abrirlo o cerrarlo las teselas ya están. A escala real ([realRoadDp], la vista de tu auto)
 * los colores no cambian con la paleta. [d] da el color de tu interfaz (sin él, el de fábrica).
 */
@Composable
internal fun rememberTileStore(
    d: Dependencies?, look: MapLook, themeDark: Boolean, shareKey: String? = null, realRoadDp: Float = 0f,
): TileStore {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val darkTiles = look.dark(themeDark)
    // El color de la interfaz: el de las tarjetas si elegiste uno (lleva el tono), si no el realce.
    val ui = d?.let { (it.prefs.cardColor ?: it.prefs.accentColor)?.toArgb()?.and(0xFFFFFF) }
    val palette = if (realRoadDp > 0f) (if (darkTiles) MapPalette.CAR_NIGHT else MapPalette.CAR_DAY) else look.colors(themeDark, ui)
    // La llave lleva los colores: teselas de otra paleta no se mezclan.
    val kind = "$density/$realRoadDp/${palette.land}/${palette.roads.contentToString()}/${palette.casings.contentToString()}"
    val store = remember(kind, shareKey) {
        if (shareKey == null) return@remember newTileStore(context, palette, density)
        // Uno por widget: al cambiar de colores o de tema, el anterior se suelta.
        tileStores[shareKey]?.takeIf { it.first == kind }?.second
            ?: newTileStore(context, palette, density, realRoadDp).also { tileStores[shareKey] = kind to it }
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

/** El almacén de cada widget Mapa (por su id) y de qué colores es. */
private val tileStores = HashMap<String, Pair<String, TileStore>>()

/** El almacén de la vista Auto de la guía para un auto de [carDp] de ancho (las calles van a su escala). */
internal fun carViewKey(carDp: Float) = "$CAR_VIEW_KEY/$carDp"

/** Terminó la guía: los almacenes de la vista Auto ya no sirven. */
internal fun forgetCarViewStores() {
    tileStores.keys.removeAll { it.startsWith(CAR_VIEW_KEY) }
}

private const val CAR_VIEW_KEY = "drive-car"

private fun newTileStore(context: Context, palette: MapPalette, density: Float, realRoadDp: Float = 0f): TileStore =
    TileStore(context, MAP_USER_AGENT, minimalTiles(context, palette, density, realRoadDp))

/** Las teselas del minimalista en los colores de [palette], a la medida de la pantalla. */
private fun minimalTiles(context: Context, palette: MapPalette, density: Float, realRoadDp: Float = 0f) =
    VectorTiles(context, palette, tilePx(density).toInt(), density, MAP_USER_AGENT, realRoadDp)

/**
 * El aspecto del mapa de LiteDash (Ajustes → Apps → Navegación): uno solo para todos los widgets Mapa, el mapa en grande
 * y el de fondo.
 */
@Composable
private fun rememberMapLook(d: Dependencies): MapLook {
    val raw = d.prefs.mapLook
    return remember(raw) { MapLook.parse(raw.orEmpty()) }
}

/**
 * Personalizar mapa (el engrane del widget): el mapa en vivo y atajos a lo que vale para todos los mapas, en Ajustes →
 * Apps → Navegación: el aspecto ([openLook]) y Sin conexión ([openOffline]).
 */
@Composable
fun CustomizeMapPanel(d: Dependencies, tileId: String, close: () -> Unit, openLook: () -> Unit, openOffline: () -> Unit) {
    if (d.dashboard.tile(tileId) == null) {
        // Cerrar escribe estado: fuera de la composición.
        LaunchedEffect(Unit) { close() }
        return
    }
    val c = LocalColors.current
    val look = rememberMapLook(d)
    val (granted, _) = rememberGpsPermission(d)
    PanelScaffold(stringResource(R.string.map_customize), close) {
        WidgetPanelLayout(
            // Las mismas teselas que el widget (no se bajan dos veces), con su propia cámara: moverla no mueve el widget.
            preview = { m -> if (granted) Card(m) { LiveMap(d, look, Modifier.fillMaxSize(), tileId, ownCamera = true) } },
            options = { m ->
                Column(m, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Label(stringResource(R.string.map_customize_help), Typography.small, c.textMuted)
                    Button(stringResource(R.string.map_tab_style), openLook, Modifier.fillMaxWidth())
                    Button(stringResource(R.string.map_offline), openOffline, Modifier.fillMaxWidth())
                }
            },
        )
    }
}

/**
 * Ajustes → Apps → Navegación → Aspecto del mapa: el único lugar del aspecto, para todos los widgets Mapa y el mapa en
 * grande ([applyMapLook]), con el mapa en vivo como vista previa.
 */
@Composable
internal fun MapLookSettings(d: Dependencies) {
    val look = rememberMapLook(d)
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
 * Ajustes → Apps → Navegación → Sin conexión. Abajo, siempre (también a quien viaja), pedir el mapa de otro país:
 * [requestMap] abre el reporte ya elegido. Mientras llega la primera lista de mapas ([OfflineSection] la pide), no se
 * puede pedir país.
 */
@Composable
internal fun MapOfflineSettings(d: Dependencies, requestMap: () -> Unit) {
    val context = LocalContext.current
    val packs = remember { MapPacks.get(context) }
    OfflineSection(d)
    val listLoading = packs.catalog.isEmpty() && packs.indexState == IndexState.LOADING
    Button(stringResource(R.string.map_request_country), requestMap, enabled = !listLoading)
}

/** Guarda el aspecto del mapa: todos los mapas de LiteDash lo leen de aquí ([rememberMapLook]). */
private fun applyMapLook(d: Dependencies, look: MapLook) {
    d.prefs.mapLook = look.encode().ifEmpty { null }
}

/** Ancho desde el que la vista previa de Ajustes va a un lado de las opciones. */
private val SETTINGS_SIDE_BY_SIDE = 640.dp
private val SETTINGS_PREVIEW_HEIGHT = 340.dp

/** Las opciones del aspecto. */
@Composable
private fun MapLookOptions(look: MapLook, set: (MapLook) -> Unit) {
    val c = LocalColors.current
    Label(stringResource(R.string.map_orientation), Typography.label, c.textMuted)
    Segmented(
        listOf(
            MapOrientation.NORTH to stringResource(R.string.map_north), MapOrientation.HEADING to stringResource(R.string.map_heading),
            MapOrientation.NORTH_UP to stringResource(R.string.map_north_up),
        ),
        look.orientation, { set(look.copy(orientation = it)) },
    )
    if (look.orientation == MapOrientation.NORTH) Label(stringResource(R.string.map_orientation_help), Typography.small, c.textMuted)
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
    // La paleta va encima de «Colores» (que decide día o noche): sus tonos en las dos versiones.
    Label(stringResource(R.string.map_palette), Typography.label, c.textMuted)
    Segmented(
        listOf(
            MapPalettes.FACTORY to stringResource(R.string.factory_look), MapPalettes.INTERFACE to stringResource(R.string.map_palette_interface),
            MapPalettes.CUSTOM to stringResource(R.string.map_palette_custom),
        ),
        look.palette,
        // Al elegir «Color propio» por primera vez, arranca con el primero de los rápidos.
        { p -> set(look.copy(palette = p, custom = look.custom ?: if (p == MapPalettes.CUSTOM) CARD_COLORS[1].rgb() else null)) },
    )
    // El color se aplica al soltar (redibujar el mapa en cada movimiento del dedo pesaría).
    if (look.palette == MapPalettes.CUSTOM) {
        ColorPicker(look.custom?.let { Color(0xFF000000 or it.toLong()) } ?: Color.Unspecified, {}, CARD_COLORS) { set(look.copy(custom = it.rgb())) }
    }
    SwitchRow(stringResource(R.string.map_route_flow), look.flow, { set(look.copy(flow = it)) }, stringResource(R.string.map_route_flow_help))
}

/** El color sin alfa (RGB), como lo guarda [MapLook.custom]. */
private fun Color.rgb() = toArgb() and 0xFFFFFF

/**
 * Sin conexión: por estado (el mapa, la ruta y la búsqueda) o por zona (solo el mapa): las zonas guardadas
 * (tocar una la muestra en el mapa, con Volver a bajar y Borrar); el radio y un mapa con su círculo para elegir la zona
 * (se arrastra; «Mi ubicación» regresa), su tamaño aproximado y lo libre; mientras baja, el avance y Cancelar. Al abrir,
 * la lista de mapas se pide si tiene más de una hora (para ver los países recién publicados).
 */
@Composable
private fun OfflineSection(d: Dependencies) {
    val c = LocalColors.current
    Label(stringResource(R.string.map_offline), Typography.label, c.textMuted)
    KeepTripRunning(d.trip)
    val context = LocalContext.current
    val offline = remember { OfflineMaps.get(context) }
    val packs = remember { MapPacks.get(context) }
    LaunchedEffect(Unit) { packs.refresh(maxAgeMs = MapPacks.INDEX_SCREEN_MAX_AGE_MS) }
    // Aquí hace falta saber dónde estás (tu estado arriba, tu zona en el mapa) aunque ningún widget use el GPS.
    LifecycleStartEffect(d.trip) {
        d.trip.acquire()
        onStopOrDispose { d.trip.release() }
    }
    val position = d.trip.position
    // Por estado (todo tu estado, de nuestro repo) o por zona (un círculo, en cualquier parte del mundo). De entrada,
    // por estado; por zona solo si ya se sabe que donde estás no hay mapa del estado (y no has bajado ninguno): sin
    // ubicación se quedaba en «Por zona» y los estados ni se veían.
    val spot = position?.let { Math.round(it.latitude * 100) / 100.0 to Math.round(it.longitude * 100) / 100.0 }
    // Con el índice por país, solo cuando ya llegaron los países de ahí (si no, aún no se sabe).
    val outside = remember(packs.catalog, packs.regions, packs.countryState, spot) { spot != null && packs.knownOutside(spot.first, spot.second) }
    val noState = packs.installed.isEmpty() && outside
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
        Label(downloadError(text), Typography.small, c.warning)
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
 * que ya bajaste, el buscador (siempre a la vista: busca en todos los países, por el nombre del estado en varios idiomas o
 * por el del país; si es un país, primero el país con «Descargar completo» y sus estados) y unos cuantos países (con «Ver
 * todos» para el resto) con el país completo y sus estados al tocar uno. Sin lista, cómo conseguirla (y Reintentar).
 */
@Composable
private fun StatePackSection(d: Dependencies, packs: MapPacks) {
    val c = LocalColors.current
    val context = LocalContext.current
    val position = d.trip.position
    val locale = currentLocale()
    val language = locale.language
    Label(stringResource(R.string.map_pack_help), Typography.small, c.textMuted)
    if (packs.catalog.isEmpty()) {
        when (packs.indexState) {
            IndexState.LOADING -> Label(stringResource(R.string.map_list_loading), Typography.small, c.textMuted)
            IndexState.FAILED -> {
                Label(downloadError(R.string.map_list_failed), Typography.small, c.warning)
                Button(stringResource(R.string.retry), { packs.refresh(force = true) }, primary = true)
            }
            // Sin lista y sin error (nunca hubo internet, o la guardada no se pudo leer): también se puede reintentar.
            IndexState.IDLE -> {
                Label(stringResource(R.string.map_pack_no_index), Typography.small, c.textMuted)
                Button(stringResource(R.string.retry), { packs.refresh(force = true) }, primary = true)
            }
        }
        return
    }
    // Lo libre (para no empezar una descarga que no cabe); se vuelve a leer al bajar o borrar un estado.
    val free by produceState<Long?>(null, packs.installed) { value = withContext(Dispatchers.IO) { freeBytes(context) } }
    // Con la posición redondeada (~1 km): no se prueban contornos con cada lectura del GPS.
    val spot = position?.let { Math.round(it.latitude * 100) / 100.0 to Math.round(it.longitude * 100) / 100.0 }
    val here = remember(packs.regions, spot) { spot?.let { packs.regionAt(it.first, it.second) } }
    val mine = packs.installed.mapNotNull { p -> packs.regions.firstOrNull { it.id == p.id } }
    val shown = (listOfNotNull(here) + mine).distinctBy { it.id }
    for (r in shown) StatePackRow(packs, r, language, free)
    // Escribiendo, de cualquier país (también por el nombre en otro idioma o por el del país).
    var query by rememberSaveable { mutableStateOf("") }
    TextInput(query, { query = it }, stringResource(R.string.map_pack_search), Modifier.fillMaxWidth(), icon = true)
    val q = normalize(query.trim())
    val shownIds = shown.map { it.id }.toSet()
    val allCountries = remember(packs.catalog) { packs.catalog.map { it.country }.distinct() }
    if (q.isNotEmpty()) {
        // Si es el nombre de un país: primero el país (con «Descargar completo») y todos sus estados.
        val countryHits = remember(allCountries, q, locale) { countriesMatching(q, allCountries, locale) }
        for (code in countryHits) key(code) {
            LaunchedEffect(code) { packs.need(code, retry = false) }
            CountryPackCard(packs, code, locale, free)
            CountryLoading(packs, code)
            val states = remember(packs.regions, code, shownIds, language) {
                packs.regions.filter { it.country == code && it.id !in shownIds }.sortedBy { it.name(language) }
            }
            for (r in states) StatePackRow(packs, r, language, free)
        }
        // Se busca en los nombres de todos los países; los datos de cada estado vienen con su país (índice por país): se
        // piden los países de lo encontrado, si no son demasiados (escribiendo más se acota).
        val keys = remember(packs.catalog, locale) { packs.catalog.associate { it.id to regionSearchKeys(it, locale) } }
        val matches = remember(packs.catalog, q, countryHits) {
            packs.catalog.filter { it.country !in countryHits && keys[it.id].orEmpty().any { k -> q in k } }
        }
        LaunchedEffect(matches) {
            if (matches.size <= SEARCH_LOAD_MAX) matches.map { it.country }.distinct().forEach { packs.need(it, retry = false) }
        }
        val found = remember(packs.regions, matches, shownIds, language) {
            val ids = matches.map { it.id }.toSet()
            packs.regions.filter { it.id in ids && it.id !in shownIds }.sortedBy { it.name(language) }
        }
        for (r in found) StatePackRow(packs, r, language, free)
        return
    }
    // Los países, siempre a la vista (escondidos, la gente pedía países que ya estaban): unos cuantos (el tuyo, los que
    // ya tienen algo bajado y los más pedidos) y «Ver todos» despliega el resto, en orden alfabético. Al tocar uno, sus
    // estados; sin nada arriba (no se sabe dónde estás ni has bajado nada) ya va abierto el tuyo, por la región del sistema.
    val countries = remember(allCountries, locale) {
        val collator = Collator.getInstance(locale)
        allCountries.sortedWith { a, b -> collator.compare(countryName(a, locale), countryName(b, locale)) }
    }
    var pickedCountry by rememberSaveable { mutableStateOf<String?>(null) }
    val system = systemRegion()
    val country = pickedCountry
        ?: if (shown.isEmpty()) system.takeIf { it in countries } ?: countries.first() else null
    val mineCountries = (listOfNotNull(here?.country, system) + mine.map { it.country } + listOfNotNull(country)).distinct()
    val featured = remember(countries, mineCountries) { featuredCountries(countries, mineCountries) }
    var allShown by rememberSaveable { mutableStateOf(false) }
    Label(stringResource(R.string.map_pack_others), Typography.label, c.textMuted)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (code in if (allShown) countries else featured) Button(countryName(code, locale), { pickedCountry = code }, primary = code == country)
        if (featured.size < countries.size) {
            if (allShown) Button(stringResource(R.string.map_pack_fewer_countries), { allShown = false })
            else Button(stringResource(R.string.map_pack_all_countries, countries.size), { allShown = true })
        }
    }
    if (country == null) return
    // Con el índice por país, los estados del país llegan al tocarlo (bajando, falló con Reintentar).
    LaunchedEffect(country) { packs.need(country, retry = false) }
    CountryPackCard(packs, country, locale, free)
    CountryLoading(packs, country)
    val others = remember(packs.regions, country, shownIds, language) {
        packs.regions.filter { it.country == country && it.id !in shownIds }.sortedBy { it.name(language) }
    }
    for (r in others) StatePackRow(packs, r, language, free)
}

/** Mientras llegan los estados del país [code] (índice por país): bajando o, si falló, Reintentar. */
@Composable
private fun CountryLoading(packs: MapPacks, code: String) {
    val c = LocalColors.current
    when (packs.countryState[code]) {
        IndexState.LOADING -> Label(stringResource(R.string.map_list_loading), Typography.small, c.textMuted)
        IndexState.FAILED -> if (packs.regions.none { it.country == code }) {
            Label(downloadError(R.string.map_list_failed), Typography.small, c.warning)
            Button(stringResource(R.string.retry), { packs.need(code) }, primary = true)
        }
        else -> Unit
    }
}

/**
 * El país [code] completo: su nombre, cuántos estados y cuánto pesa todo; «Descargar completo» baja los que falten uno
 * tras otro (con su confirmación: tamaño y lo libre). Bajando, «Bajando 3 de 32 · Jalisco», su avance y Cancelar; si se
 * detuvo, por qué y Reintentar (sigue desde ese estado). Con todo bajado, no sale el botón.
 */
@Composable
private fun CountryPackCard(packs: MapPacks, code: String, locale: Locale, free: Long?) {
    val c = LocalColors.current
    val context = LocalContext.current
    val name = countryName(code, locale)
    val count = packs.countryCount(code)
    val batch = packs.batch?.takeIf { it.code == code }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.inner)).background(c.surfaceHigh).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Label(name, Typography.label, c.text)
        Label(
            pluralStringResource(R.plurals.map_pack_country_states, count, count) + " · " +
                Formatter.formatShortFileSize(context, packs.countryTotal(code)),
            Typography.small, c.textMuted,
        )
        if (batch != null && batch.error == null) {
            val regionName = packs.regions.firstOrNull { it.id == batch.regionId }?.name(locale.language) ?: batch.regionId
            Label(stringResource(R.string.map_pack_batch_progress, batch.at, batch.count, regionName), Typography.small, c.text)
            val current = packs.job?.takeIf { it.id == batch.regionId && !it.failed }?.progress ?: 0f
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.surface)) {
                Box(Modifier.fillMaxWidth(((batch.at - 1 + current) / batch.count).coerceIn(0f, 1f)).fillMaxHeight().background(c.accent))
            }
            Button(stringResource(R.string.cancel), { packs.cancel() })
            return@Column
        }
        if (batch?.error != null) {
            Label(
                if (batch.error == PackError.SPACE) stringResource(R.string.map_pack_no_room) else downloadError(R.string.map_pack_failed),
                Typography.small, c.warning,
            )
            Button(stringResource(R.string.retry), { packs.downloadCountry(code) }, primary = true, enabled = !packs.busy)
            return@Column
        }
        // Lo que falta (necesita los estados del país ya cargados para saber cuáles).
        if (!packs.countryReady(code)) return@Column
        val missing = packs.missingIn(code)
        if (missing.isEmpty()) return@Column
        val size = Formatter.formatShortFileSize(context, missing.sumOf { it.totalSize })
        val noRoom = free != null && !packs.hasRoomForAll(missing, free)
        Confirmed(
            stringResource(R.string.map_pack_download_all, size), stringResource(R.string.map_pack_download_all_question, name),
            stringResource(R.string.map_pack_download),
            message = free?.let { stringResource(R.string.map_offline_size_free, size, Formatter.formatShortFileSize(context, it)) },
            primary = true, destructive = false, enabled = !packs.busy && !noRoom,
        ) { packs.downloadCountry(code) }
        if (noRoom) Label(stringResource(R.string.map_pack_no_room), Typography.small, c.warning)
    }
}

/**
 * Los países cuyo nombre empieza con lo escrito [q] (ya sin acentos ni mayúsculas), en el idioma de la app, en español
 * o en inglés (o una palabra de su nombre: «unidos»), o su código («mx»). Desde 3 letras (o el código exacto) para no
 * llenar la lista con cada letra. El que coincide completo va primero.
 */
internal fun countriesMatching(q: String, codes: Collection<String>, locale: Locale): List<String> {
    if (q.isEmpty()) return emptyList()
    val names = codes.associateWith { code ->
        listOf(locale, SPANISH, Locale.ENGLISH).map { normalize(countryName(code, it)) }.distinct()
    }
    fun exact(code: String) = q == code.lowercase(Locale.ROOT) || names[code].orEmpty().any { it == q }
    fun hit(code: String) = exact(code) ||
        q.length >= COUNTRY_MATCH_MIN && names[code].orEmpty().any { n -> n.split(' ', '-').any { it.startsWith(q) } || n.startsWith(q) }
    return codes.filter(::hit).sortedBy { if (exact(it)) 0 else 1 }
}

/**
 * Los países que se ven sin «Ver todos»: primero [mine] (donde estás, la región del sistema, los que tienen algo bajado
 * y el elegido), luego los más pedidos hasta [limit]; solo los que hay en [all] y sin repetir.
 */
internal fun featuredCountries(all: List<String>, mine: List<String>, limit: Int = FEATURED_COUNTRIES): List<String> {
    val have = all.toSet()
    val first = mine.filter { it in have }.distinct()
    val rest = (POPULAR_COUNTRIES + all).filter { it in have && it !in first }.distinct()
    return first + rest.take((limit - first.size).coerceAtLeast(0))
}

/** Cuántos países se ven sin «Ver todos». */
private const val FEATURED_COUNTRIES = 6

/** Los países por pedidos y pantallas (reportes `want_map` y estadísticas), para completar la lista corta. */
private val POPULAR_COUNTRIES = listOf("MX", "US", "CO", "BR", "AR", "ES", "CL", "PE", "EC", "CA", "IT", "VE")

/** Desde cuántas letras se busca un país por su nombre. */
private const val COUNTRY_MATCH_MIN = 3

private val SPANISH = Locale("es")

/** La región del sistema (no la del idioma de la app): con ella se elige el país de entrada. */
private fun systemRegion(): String = Locale.getDefault().country

/** El nombre del país [code] (ISO de dos letras) en [locale]; si Android no lo sabe, el código. */
private fun countryName(code: String, locale: Locale): String = Locale("", code).getDisplayCountry(locale).ifEmpty { code }

/**
 * Con qué se encuentra un estado en el buscador (sin acentos ni mayúsculas): su nombre en tu idioma y en los de la lista
 * (en inglés, «Mexico City»; en español, «Ciudad de México») y el de su país en tu idioma y en inglés.
 */
internal fun regionSearchKeys(region: NamedRegion, locale: Locale): Set<String> =
    ((REGION_NAME_LANGUAGES + locale.language).map { region.name(it) } +
        countryName(region.country, locale) + countryName(region.country, Locale.ENGLISH))
        .map { normalize(it) }.toSet()

/** Hasta cuántos estados encontrados se piden sus países (índice por país): «a» encontraría casi todos. */
private const val SEARCH_LOAD_MAX = 60

/** Los idiomas en que se prueba el nombre de un estado (los que trae la lista de mapas; si falta uno, sale el inglés). */
private val REGION_NAME_LANGUAGES = listOf("en", "es", "pt", "fr", "it", "de", "tr")

/**
 * Un estado en un renglón: su nombre y tamaño a la izquierda y lo que se puede hacer a la derecha (bajar, actualizar o
 * borrar); bajando, su avance debajo y Cancelar (que también suelta un mapa a medias). Si no cabe en lo [free] (con la
 * regla de [MapPacks.hasRoom]), no se deja bajar y se dice.
 */
@Composable
private fun StatePackRow(packs: MapPacks, region: MapRegion, language: String, free: Long?) {
    val c = LocalColors.current
    val context = LocalContext.current
    val name = region.name(language)
    val saved = packs.installed.firstOrNull { it.id == region.id }
    val job = packs.job?.takeIf { it.id == region.id }
    val downloading = job != null && !job.failed
    // Uno a la vez: mientras baja otro (o un país completo), este espera.
    val busy = packs.busy
    val update = saved != null && packs.outdated(region)
    // La misma regla de espacio que la descarga: descuenta lo ya bajado del mapa a medias (si no, al reanudar no cabría).
    val noRoom = (saved == null || update) && !downloading && free != null && !packs.hasRoom(region, free)
    // Quedó a medias (se cortó la red o se cerró la app): se puede seguir o soltarlo.
    val paused = !downloading && packs.paused?.id == region.id
    // Guardado, lo que ocupa en la pantalla (no lo que pesa la versión nueva): se lee del disco aparte.
    val savedBytes by produceState<Long?>(null, saved) { value = saved?.let { withContext(Dispatchers.IO) { packs.savedBytes(it.id) } } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Label(
                if (saved != null) stringResource(R.string.map_pack_saved, name, savedBytes?.let { Formatter.formatShortFileSize(context, it) } ?: "…")
                // Todo el paquete: mapa, búsqueda y calles para la ruta.
                else "$name · ${Formatter.formatShortFileSize(context, region.totalSize)}",
                Typography.body, if (saved != null) c.accent else c.text, maxLines = 2, modifier = Modifier.weight(1f),
            )
            when {
                downloading -> Button(stringResource(R.string.cancel), { packs.cancel() })
                saved == null -> {
                    if (paused) Button(stringResource(R.string.cancel), { packs.cancel() })
                    Button(stringResource(R.string.map_pack_download), { packs.download(region) }, primary = true, enabled = !busy && !noRoom)
                }
                else -> {
                    // Una actualización a medias también se suelta.
                    if (paused) Button(stringResource(R.string.cancel), { packs.cancel() })
                    if (update) Button(stringResource(R.string.map_pack_update), { packs.download(region) }, primary = true, enabled = !busy && !noRoom)
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
        // Sin espacio (antes de empezar o a media descarga) no es culpa del internet.
        if (noRoom || job?.error == PackError.SPACE) Label(stringResource(R.string.map_pack_no_room), Typography.small, c.warning)
        else if (job?.failed == true) Label(downloadError(R.string.map_pack_failed), Typography.small, c.warning)
        else if (saved != null && !downloading) packs.streetsGaps[region.id]?.let { StreetsGapNote(packs, region.id, it, pill = false) }
    }
}

/**
 * Por qué el mapa bajado [id] se ve sin sus calles ([gap]) y, si faltan, «Bajar calles» (con su avance). [pill] = sobre
 * el mapa; si no, en el renglón del estado.
 */
@Composable
private fun StreetsGapNote(packs: MapPacks, id: String, gap: StreetsGap, pill: Boolean) {
    val c = LocalColors.current
    val text = stringResource(
        when (gap) {
            StreetsGap.OFFER -> R.string.map_streets_missing
            StreetsGap.NO_ROOM -> R.string.map_pack_no_room
            StreetsGap.MEMORY -> R.string.map_streets_memory
        },
    )
    val job = packs.routesJob?.takeIf { it.id == id }
    val loading = job != null && !job.failed
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (pill) MapPill(Icons.warning, text, c.warning, maxLines = 3) else Label(text, Typography.small, c.warning)
        when {
            loading -> Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.surfaceHigh)) {
                Box(Modifier.fillMaxWidth(job.progress).fillMaxHeight().background(c.accent))
            }
            gap == StreetsGap.OFFER -> Button(stringResource(R.string.route_download), { packs.downloadStreets(id) }, primary = !pill)
        }
        if (job?.failed == true) Label(stringResource(R.string.route_download_failed), Typography.small, c.warning)
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
    val store = rememberTileStore(null, look, c.dark)
    // Lo que se baja va siempre en claro (el oscuro se pinta al dibujar); una sola fuente para las dos descargas.
    val download = remember(density) { minimalTiles(context, MapPalette.DAY, density) }
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
