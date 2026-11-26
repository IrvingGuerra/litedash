package com.irvingguerra.litedash

import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Radius
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.core.vehicle.Signal
import com.irvingguerra.litedash.core.vehicle.watch
import com.irvingguerra.litedash.feature.map.GuidanceState
import com.irvingguerra.litedash.feature.map.Maneuver
import com.irvingguerra.litedash.feature.map.ManeuverType
import com.irvingguerra.litedash.feature.map.MapCamera
import com.irvingguerra.litedash.feature.map.MapCanvas
import com.irvingguerra.litedash.feature.map.MapLook
import com.irvingguerra.litedash.feature.map.MapOrientation
import com.irvingguerra.litedash.feature.map.MapPacks
import com.irvingguerra.litedash.feature.map.MapZoom
import com.irvingguerra.litedash.feature.map.PackError
import com.irvingguerra.litedash.feature.map.ROUTE_BLUE
import com.irvingguerra.litedash.feature.map.Route
import com.irvingguerra.litedash.feature.map.SearchHit
import com.irvingguerra.litedash.feature.map.markerWidthDp
import com.irvingguerra.litedash.feature.map.rememberMapCamera
import kotlinx.coroutines.delay
import java.util.Date
import kotlin.math.cos
import kotlin.math.sin

/**
 * Qué tanto de la guía cabe en un mapa: en el widget Mapa según su tamaño ([guideSizeFor]) y [FULL] en el mapa en grande.
 */
internal enum class GuideSize {
    /** La flecha de la vuelta y a cuánto. */
    SMALL,

    /** La tarjeta de la vuelta (con la calle), la llegada, Terminar y, si cabe la vista Auto ([CAR_VIEW_MIN_SIDE]), Flecha / Auto. */
    MEDIUM,

    /** Además «Luego», el avance del viaje, Ver ruta y la brújula. */
    LARGE,

    /** Lo de [LARGE] en el mapa en grande. */
    FULL,
}

/**
 * El tamaño de la guía en un widget de [widthDp] × [heightDp]. El mediano angosto lleva la tarjeta a todo lo ancho y tu
 * flecha debajo ([guideStacked]): necesita más alto.
 */
internal fun guideSizeFor(widthDp: Float, heightDp: Float): GuideSize = when {
    widthDp >= LARGE_MIN_WIDTH && heightDp >= LARGE_MIN_HEIGHT -> GuideSize.LARGE
    widthDp >= MEDIUM_SIDE_WIDTH && heightDp >= MEDIUM_MIN_HEIGHT -> GuideSize.MEDIUM
    widthDp >= MEDIUM_MIN_WIDTH && heightDp >= MEDIUM_STACKED_HEIGHT -> GuideSize.MEDIUM
    else -> GuideSize.SMALL
}

/**
 * ¿La tarjeta de la vuelta va a todo lo ancho, arriba, con lo demás debajo? En el mediano si no cabe junto a tu flecha
 * (menos de [MEDIUM_SIDE_WIDTH]); en el grande si a su lado (los botones miden [sideDp]) quedaría más angosta que
 * [TURN_MIN_WIDTH] (un celular de pie).
 */
internal fun guideStacked(size: GuideSize, widthDp: Float, sideDp: Float, gapDp: Float): Boolean = when (size) {
    GuideSize.SMALL -> false
    GuideSize.MEDIUM -> widthDp < MEDIUM_SIDE_WIDTH
    GuideSize.LARGE, GuideSize.FULL -> widthDp - sideDp - gapDp * 3 < TURN_MIN_WIDTH.value
}

/** Desde qué tamaño (dp) el widget navegando es mediano (con la tarjeta al lado o, angosto y más alto, encima) o grande. */
private const val MEDIUM_MIN_WIDTH = 300f
private const val MEDIUM_SIDE_WIDTH = 460f
private const val MEDIUM_MIN_HEIGHT = 170f
private const val MEDIUM_STACKED_HEIGHT = 240f
private const val LARGE_MIN_WIDTH = 480f
private const val LARGE_MIN_HEIGHT = 330f

/** Más angosta que esto, la tarjeta de la vuelta no cabe junto a los botones: va encima de ellos. */
private val TURN_MIN_WIDTH = 280.dp

/**
 * Un mapa navegando (el widget Mapa [tileId] o, con [GuideSize.FULL], el mapa en grande): la vista Flecha (el mapa
 * inclinado a nivel de calle, con la cámara y las teselas del widget) o la vista Auto (tu auto de espaldas sobre las calles
 * a escala real), y encima lo que quepa en [size]. [controls] = false al acomodar el inicio (los botones estorban);
 * [onTap] = un toque al mapa (en el widget, verlo en grande); [extra] = botones de más arriba a la derecha (los del mapa en grande).
 * Solo un mapa anima la guía: con [active] = false se queda quieto (otro la enseña) y con [cards] = false es solo el mapa
 * con la ruta (un segundo widget Mapa en la página).
 */
@Composable
internal fun GuideView(
    d: Dependencies, session: DriveSession, size: GuideSize, tileId: String?, modifier: Modifier = Modifier,
    controls: Boolean = true, onTap: (() -> Unit)? = null, active: Boolean = true, cards: Boolean = true,
    extra: @Composable RowScope.() -> Unit = {},
) {
    val c = LocalColors.current
    val camera = sharedMapCamera(tileId)
    val big = size == GuideSize.LARGE || size == GuideSize.FULL
    // Al terminar la guía, la cámara (la comparte el widget) no se queda en «Ver ruta», suelta ni a medio zoom.
    DisposableEffect(session, camera) {
        onDispose { if (Driving.session !== session && (camera.showingPlace || !camera.following || camera.scaled)) camera.recenter() }
    }
    // Atrás cierra «Ver ruta» (nunca termina la guía).
    BackHandler(cards && session.overview) { session.overview = false }
    // Lo que tapan la vuelta (arriba, si va a todo lo ancho), la llegada (abajo) y los botones (arriba a la derecha), en px.
    var topH by remember { mutableIntStateOf(0) }
    var bottomH by remember { mutableIntStateOf(0) }
    var sideW by remember { mutableIntStateOf(0) }
    BoxWithConstraints(modifier.clipToBounds()) {
        val density = LocalDensity.current
        val moving = active && !LocalHomeCovered.current
        // Con poco alto (1024×600, widget mediano) las tarjetas van compactas.
        val compact = size == GuideSize.MEDIUM || maxHeight < COMPACT_CARDS_HEIGHT
        // Flecha / Auto es una sola elección para el widget y el mapa en grande; donde no cabe la vista Auto, la flecha.
        val carFits = size != GuideSize.SMALL && minOf(maxWidth, maxHeight) >= CAR_VIEW_MIN_SIDE
        val carView = cards && carFits && d.prefs.guideCarView
        val gap = if (compact) 8.dp else 12.dp
        val round = if (compact) ROUND_SMALL else ROUND_BUTTON
        val side = with(density) { sideW.toDp() }
        val stacked = cards && guideStacked(size, maxWidth.value, side.value, gap.value)
        val topPx = if (stacked) topH else 0
        val bottomPx = if (cards && size != GuideSize.SMALL) bottomH else 0
        if (carView) CarView(d, session, Modifier.fillMaxSize(), topPx = topH, bottomPx = bottomPx, active = moving, onTap = onTap)
        else ArrowView(
            d, session, tileId, camera, Modifier.fillMaxSize(), topPx = topPx, bottomPx = bottomPx, active = moving,
            interactive = cards && size != GuideSize.SMALL, onTap = onTap,
        )
        if (!cards) return@BoxWithConstraints
        if (size == GuideSize.SMALL) {
            TurnChip(d, session, Modifier.align(Alignment.TopStart).padding(gap))
            return@BoxWithConstraints
        }
        // En el mediano tu flecha queda a la altura de la tarjeta: la tarjeta no pasa de la mitad. Angosta (mediano chico,
        // celular de pie), va a todo lo ancho y tu flecha y los botones debajo.
        val turnMax = if (size == GuideSize.MEDIUM) maxWidth / 2 - ARROW_CLEARANCE - gap else maxWidth - side - gap * 3
        TurnCard(
            d, session, compact, then = big,
            modifier = Modifier.align(Alignment.TopStart).padding(gap)
                .then(if (stacked) Modifier.fillMaxWidth() else Modifier.widthIn(max = minOf(TURN_MAX_WIDTH, turnMax)))
                .onSizeChanged { topH = it.height + with(density) { gap.roundToPx() } },
        )
        // Arriba a la derecha (angosto, debajo de la vuelta): Flecha / Auto y los del mapa en grande; abajo de ellos,
        // redondos: seguirte (si lo soltaste), ver toda la ruta y la brújula.
        if (controls) Column(
            Modifier.align(Alignment.TopEnd).padding(top = if (stacked) with(density) { topH.toDp() } else 0.dp).padding(gap)
                .onSizeChanged { sideW = it.width },
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            if (carFits || big) Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
                if (carFits) ViewSwitch(carView, { d.prefs.guideCarView = it }, round)
                extra()
            }
            if (!carView && !camera.following && !session.overview) {
                IconButton(
                    Icons.recenter, camera::recenter, size = round, background = c.surface.copy(alpha = CARD_ALPHA), color = c.accent,
                    label = stringResource(R.string.guide_follow),
                )
            }
            if (big && session.state?.arrived != true) {
                IconButton(
                    if (session.overview) Icons.navigate else Icons.map, { session.overview = !session.overview },
                    size = round, background = c.surface.copy(alpha = CARD_ALPHA), color = c.text,
                    label = stringResource(if (session.overview) R.string.guide_follow else R.string.guide_overview),
                )
            }
            if (big && !carView && !session.overview) {
                val heading = !d.prefs.guideMapNorth
                val s = session.state
                CompassButton(
                    if (heading) -(((if (s != null && !s.offRoute) s.heading else null) ?: d.trip.bearing) ?: 0f) else 0f,
                    northUp = !heading, onClick = { d.prefs.guideMapNorth = !d.prefs.guideMapNorth }, size = round,
                    background = c.surface.copy(alpha = CARD_ALPHA),
                )
            }
        }
        ArrivalCard(
            d, session, compact, progress = big, onEnd = if (controls) endGuide else null,
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(gap)
                .onSizeChanged { bottomH = it.height + with(density) { gap.roundToPx() } },
        )
    }
}

/** Termina la guía («Terminar»: no pregunta nada). */
internal val endGuide: () -> Unit = { Driving.session = null }

/**
 * La vista Flecha: el mapa con la ruta, inclinado y muy cerca, hacia donde vas, alejándose con la velocidad; con un dedo
 * se suelta (al alejarlo se aplana) y «Seguirme» lo regresa. La brújula lo pasa a «siempre al norte» (plano) y de vuelta;
 * «Ver ruta» la enseña toda, plana. Tu flecha y los créditos van entre la tarjeta de la vuelta, si va a todo lo ancho
 * ([topPx]), y la de la llegada ([bottomPx]). Con [active] = false se queda quieto (otro mapa anima la guía).
 */
@Composable
private fun ArrowView(
    d: Dependencies, session: DriveSession, tileId: String?, camera: MapCamera, modifier: Modifier, topPx: Int, bottomPx: Int,
    active: Boolean, interactive: Boolean, onTap: (() -> Unit)?,
) {
    val c = LocalColors.current
    val heading = !d.prefs.guideMapNorth && !session.overview
    val look = remember(d.prefs.mapLook, heading) {
        MapLook.parse(d.prefs.mapLook.orEmpty()).copy(orientation = if (heading) MapOrientation.HEADING else MapOrientation.NORTH_UP)
    }
    // Las mismas teselas que el widget plano (los colores no dependen de hacia dónde mira).
    val store = rememberTileStore(d, look, c.dark, tileId)
    val position = d.trip.position
    val kmh = d.bus.watch(Signal.SPEED)
    val s = session.state
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current.density
        val view = guideFocus(topPx, bottomPx, constraints.maxHeight)
        // «Ver ruta» la pone solo el mapa que anima la guía (el quieto comparte la cámara o no se ve).
        val fit = if (session.overview && active) remember(session.route, maxWidth, maxHeight, density) { routeFit(session.route, maxWidth.value, maxHeight.value, density) } else null
        LaunchedEffect(fit?.lat, fit?.lon, active) {
            if (fit != null) camera.show(fit.lat, fit.lon) else if (active && camera.showingPlace) camera.recenter()
        }
        if (position == null) {
            Label(stringResource(R.string.map_waiting), Typography.body, c.textMuted, maxLines = 2, modifier = Modifier.align(Alignment.Center).padding(16.dp))
            return@BoxWithConstraints
        }
        val onRoute = s != null && !s.offRoute
        MapCanvas(
            store, if (onRoute) s.lat else position.latitude, if (onRoute) s.lon else position.longitude,
            youBearing(d, session, s), kmh, look, Modifier.fillMaxSize(), camera,
            fixedZoom = fit?.whole, interactive = interactive && !session.overview, navigation = true,
            active = active, onTap = onTap,
            turnAhead = onRoute && turnAhead(session.route, s),
            route = session.route.points, routeDone = s?.let { it.point + it.fraction } ?: 0f,
            focus = view,
        )
    }
}

/**
 * Dónde vas tú en un mapa de [heightPx] con [topPx] tapados arriba y [bottomPx] abajo, en fracciones (null = todo el
 * mapa). Lo que queda a la vista nunca baja de la mitad del alto ni, con algo arriba, de un tercio.
 */
internal fun guideFocus(topPx: Int, bottomPx: Int, heightPx: Int): Rect? {
    if (heightPx <= 0 || (topPx <= 0 && bottomPx <= 0)) return null
    val top = (topPx.coerceAtLeast(0).toFloat() / heightPx).coerceAtMost(MAX_TOP_SHARE)
    val bottom = ((heightPx - bottomPx.coerceAtLeast(0)).toFloat() / heightPx)
        .coerceAtLeast(maxOf(0.5f, top + MIN_VIEW_SHARE)).coerceAtMost(1f)
    return Rect(0f, top, 1f, bottom)
}

private const val MAX_TOP_SHARE = 0.6f
private const val MIN_VIEW_SHARE = 0.35f

/** Hacia dónde vas: sobre la ruta, hacia donde sigue (como Waze); fuera, el del GPS; al salir, el de la ruta. */
internal fun youBearing(d: Dependencies, session: DriveSession, s: GuidanceState?): Float? =
    (if (s != null && !s.offRoute) s.heading else null) ?: d.trip.bearing ?: session.route.startBearing.takeIf { s == null || s.point == 0 }

/**
 * La vista Auto: tu auto de espaldas sobre las calles (o la flecha sin auto), siempre hacia donde vas, con la velocidad.
 * Las calles a escala real salen del mapa bajado: donde no lo hay (ruta en línea), en su lugar [GetMapCard] (bajarlo sin
 * dejar de navegar; al terminar, el auto aparece solo), entre la tarjeta de la vuelta ([topPx]) y la de la llegada
 * ([bottomPx]) y a la derecha de la velocidad. Con [active] = false se queda quieta.
 */
@Composable
private fun CarView(d: Dependencies, session: DriveSession, modifier: Modifier, topPx: Int, bottomPx: Int, active: Boolean, onTap: (() -> Unit)?) {
    val c = LocalColors.current
    val context = LocalContext.current
    val packs = remember(context) { MapPacks.get(context) }
    val look = remember(d.prefs.mapLook) {
        MapLook.parse(d.prefs.mapLook.orEmpty()).copy(zoom = MapZoom.AUTO, orientation = MapOrientation.HEADING)
    }
    val camera = rememberMapCamera()
    val marker = rememberRearMarker(d, d.cars.pkg(d.prefs.carModel))
    val position = d.trip.position
    val kmh = d.bus.watch(Signal.SPEED)
    val s = session.state
    BoxWithConstraints(modifier.clipToBounds().then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier)) {
        // Tu auto mide lo mismo que en el mapa de fondo ([markerWidthDp]): las calles y la ruta, a su escala.
        val carDp = markerWidthDp(minOf(maxWidth, maxHeight).value)
        // Un almacén por tamaño (el ancho de las calles cambia con él); al terminar la guía se sueltan todos.
        val store = rememberTileStore(d, look, c.dark, carViewKey(carDp), realRoadDp = carDp)
        val density = LocalDensity.current
        val topDp = with(density) { topPx.toDp() }
        val bottomDp = with(density) { bottomPx.toDp() }
        // ¿Hay mapa bajado donde vas? (Por cuadras de ~100 m: no se revisa el contorno con cada lectura.)
        val installed = packs.installed
        val cellLat = position?.let { Math.round(it.latitude * 1000) }
        val cellLon = position?.let { Math.round(it.longitude * 1000) }
        val covered = remember(installed, cellLat, cellLon) {
            position == null || installed.any { it.contains(position.latitude, position.longitude) }
        }
        if (!covered) {
            val freeHeight = maxHeight - topDp - bottomDp
            Box(
                Modifier.fillMaxSize().padding(start = SPEED_CLEARANCE, end = 12.dp, top = topDp, bottom = bottomDp),
                contentAlignment = Alignment.Center,
            ) {
                GetMapCard(
                    packs, position!!.latitude, position.longitude, cellLat, cellLon,
                    compact = freeHeight < GET_MAP_FULL_HEIGHT, modifier = Modifier.widthIn(max = 420.dp),
                )
            }
        } else if (position != null) {
            val onRoute = s != null && !s.offRoute
            MapCanvas(
                store, if (onRoute) s.lat else position.latitude, if (onRoute) s.lon else position.longitude,
                youBearing(d, session, s), kmh, look, Modifier.fillMaxSize(), camera,
                navigating = true, interactive = false, marker = marker, active = active,
                route = session.route.points, routeDone = s?.let { it.point + it.fraction } ?: 0f, routeWidth = carDp.dp,
                focus = guideFocus(0, bottomPx, constraints.maxHeight),
            )
        }
        // La velocidad, grande, a la izquierda debajo de la vuelta; sin dato, una raya (no «0»: parecería que vas parado).
        Column(Modifier.align(Alignment.TopStart).padding(start = 20.dp, top = topDp + 8.dp)) {
            val miles = d.prefs.units.miles
            val speed = kmh?.let { "${(it * if (miles) 0.621371f else 1f).toInt()}" } ?: NO_SPEED
            Label(speed, Typography.clock.copy(fontSize = 52.sp, fontWeight = FontWeight.Bold), maxLines = 1)
            Label(stringResource(if (miles) R.string.unit_mph else R.string.unit_kmh), Typography.small, c.textMuted, maxLines = 1)
        }
    }
}

/**
 * En lugar de tu auto, sin mapa bajado donde vas: «Para ver tu auto y los carriles, baja el mapa de <estado> (<tamaño>)»
 * con Bajar y su avance (la guía sigue mientras baja). Sin mapa de esa zona en la lista, solo el texto. El estado se
 * busca por cuadras de ~100 m ([cellLat], [cellLon]), no con cada lectura del GPS.
 */
@Composable
private fun GetMapCard(packs: MapPacks, lat: Double, lon: Double, cellLat: Long?, cellLon: Long?, compact: Boolean, modifier: Modifier) {
    val c = LocalColors.current
    val context = LocalContext.current
    val regions = packs.regions
    val region = remember(regions, cellLat, cellLon) { regions.firstOrNull { it.contains(lat, lon) } }
    // Si tu país aún no se carga, se pide (fuera del dibujo); al llegar cambia [MapPacks.regions].
    LaunchedEffect(cellLat, cellLon, region == null) { if (region == null) packs.regionAt(lat, lon) }
    // Con poco alto (entre la vuelta y la llegada) va más junta y sin ícono; si aun así no cabe, se desplaza.
    Column(
        modifier.clip(RoundedCornerShape(Radius.card)).background(c.surface.copy(alpha = CARD_ALPHA))
            .verticalScroll(rememberScrollState()).padding(if (compact) 12.dp else 20.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 14.dp),
    ) {
        if (!compact) Icon(Icons.offline, c.textMuted, 28.dp)
        val lines = if (compact) 3 else 4
        if (region == null) {
            Label(stringResource(R.string.guide_car_no_map), Typography.body, maxLines = lines)
            return@Column
        }
        val size = Formatter.formatShortFileSize(context, region.totalSize)
        Label(stringResource(R.string.guide_car_need_map, region.name(currentLocale().language), size), Typography.body, maxLines = lines)
        val job = packs.job?.takeIf { it.id == region.id }
        if (job != null && !job.failed) {
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.surfaceHigh)) {
                Box(Modifier.fillMaxWidth(job.progress).fillMaxHeight().background(c.accent))
            }
        } else {
            if (job?.error == PackError.SPACE) Label(stringResource(R.string.map_pack_no_room), Typography.small, c.warning)
            else if (job?.failed == true) Label(downloadError(R.string.map_pack_failed), Typography.small, c.warning)
            // Uno a la vez: si baja otro mapa, este espera.
            Button(stringResource(R.string.route_get_map), { packs.download(region) }, primary = true, enabled = !packs.busy)
        }
    }
}

/** Flecha / Auto: dos íconos en una pastilla; la vista elegida se recuerda. */
@Composable
private fun ViewSwitch(car: Boolean, onChange: (Boolean) -> Unit, height: Dp) {
    val c = LocalColors.current
    Row(Modifier.clip(RoundedCornerShape(50)).background(c.surface.copy(alpha = CARD_ALPHA))) {
        ViewOption(Icons.navigate, R.string.guide_view_arrow, !car, height) { onChange(false) }
        ViewOption(Icons.car, R.string.guide_view_car, car, height) { onChange(true) }
    }
}

@Composable
private fun ViewOption(icon: ImageVector, @StringRes label: Int, chosen: Boolean, height: Dp, onClick: () -> Unit) {
    val c = LocalColors.current
    val text = stringResource(label)
    // Se toca en todo el alto de la pastilla (≥ 44 dp: se usa manejando); la elegida se marca 4 dp adentro.
    Box(
        Modifier.size(height * 1.25f, height).clickable(role = Role.Tab, onClick = onClick).semantics {
            contentDescription = text
            selected = chosen
        }.padding(4.dp).clip(RoundedCornerShape(50)).background(if (chosen) c.surfaceHigh else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, if (chosen) c.accent else c.textMuted, (height - 8.dp) * 0.55f) }
}

/** Lo que dice la guía cuando no hay vuelta que enseñar (recalculando, sin ubicación, fuera de la ruta); null = la vuelta. */
@StringRes
private fun guideStatus(session: DriveSession): Int? {
    val s = session.state
    return when {
        session.recalculating -> R.string.guide_recalculating
        s == null -> R.string.route_waiting
        // Fuera de la ruta y sin una nueva todavía: la vuelta de la ruta vieja ya no sirve.
        s.offRoute && !s.arrived -> if (session.noInternet) R.string.guide_no_internet else R.string.guide_off_route
        else -> null
    }
}

/** La vuelta que toca: la próxima o, al llegar, la llegada. Solo con [guideStatus] null. */
private fun nextManeuver(session: DriveSession, s: GuidanceState): Maneuver =
    if (s.arrived) session.route.maneuvers.last() else session.route.maneuvers[s.next]

/** «Llegaste» o «Llegaste a <lugar>». */
@Composable
private fun arrivedText(to: SearchHit): String =
    to.name.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.guide_arrived_to, it) } ?: stringResource(R.string.guide_arrived)

/**
 * La próxima vuelta: flecha, a cuánto y qué hacer; con [then], abajo «Luego» con la siguiente. Al llegar, «Llegaste».
 * [compact] = más chica (poco alto).
 */
@Composable
private fun TurnCard(d: Dependencies, session: DriveSession, compact: Boolean, then: Boolean, modifier: Modifier) {
    val c = LocalColors.current
    val s = session.state
    val status = guideStatus(session)
    val arrow = if (compact) 52.dp else 64.dp
    Row(
        modifier.clip(RoundedCornerShape(Radius.card)).background(c.surface.copy(alpha = CARD_ALPHA)).padding(if (compact) 10.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            status != null -> Label(stringResource(status), Typography.title, maxLines = 2)
            s!!.arrived -> {
                ArrowBox(nextManeuver(session, s), arrow)
                Label(arrivedText(session.to), Typography.title, maxLines = 2, modifier = Modifier.padding(start = 14.dp))
            }
            else -> {
                val m = nextManeuver(session, s)
                ArrowBox(m, arrow)
                Column(Modifier.padding(start = if (compact) 12.dp else 14.dp)) {
                    Label(distanceLabel(s.toNextM / 1000, d.prefs.units.miles), Typography.clock.copy(fontSize = if (compact) 26.sp else 30.sp), maxLines = 1)
                    Label(maneuverText(m, session.to), Typography.body, maxLines = if (compact) 1 else 2)
                    val next = s.then?.let { session.route.maneuvers[it] }
                    if (then && next != null && next.type != ManeuverType.ARRIVE) {
                        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Label(stringResource(R.string.guide_then), Typography.small, c.textMuted, maxLines = 1)
                            ManeuverArrow(next, 16.dp, c.textMuted, Modifier.padding(horizontal = 6.dp))
                            Label(next.road.ifEmpty { maneuverText(next, session.to) }, Typography.small, c.textMuted, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/** La vuelta en el widget chico: la flecha y a cuánto (o, sin vuelta, lo que pasa, corto). */
@Composable
private fun TurnChip(d: Dependencies, session: DriveSession, modifier: Modifier) {
    val c = LocalColors.current
    val s = session.state
    val status = guideStatus(session)
    Row(
        modifier.clip(RoundedCornerShape(Radius.card)).background(c.surface.copy(alpha = CARD_ALPHA)).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (status != null) {
            Label(stringResource(status), Typography.subtitle, maxLines = 2, modifier = Modifier.padding(4.dp))
            return@Row
        }
        ArrowBox(nextManeuver(session, s!!), 44.dp)
        val text = if (s.arrived) stringResource(R.string.guide_arrived) else distanceLabel(s.toNextM / 1000, d.prefs.units.miles)
        Label(text, Typography.clock.copy(fontSize = 24.sp), maxLines = 1, modifier = Modifier.padding(start = 10.dp, end = 4.dp))
    }
}

/**
 * Abajo: la hora de llegada, lo que falta y, con [progress], la barra de avance del viaje; a la derecha Terminar ([onEnd];
 * null = sin botón).
 */
@Composable
private fun ArrivalCard(d: Dependencies, session: DriveSession, compact: Boolean, progress: Boolean, onEnd: (() -> Unit)?, modifier: Modifier) {
    val c = LocalColors.current
    val context = LocalContext.current
    val s = session.state
    // La hora de llegada se mueve con el reloj aunque no llegue otra lectura del GPS.
    rememberMinute()
    Row(
        modifier.clip(RoundedCornerShape(Radius.card)).background(c.surface.copy(alpha = CARD_ALPHA))
            .padding(start = if (compact) 12.dp else 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = if (compact) 2.dp else 6.dp)) {
            val left = s?.leftS ?: session.route.seconds
            val leftM = s?.leftM ?: session.route.distanceM
            Row(verticalAlignment = Alignment.Bottom) {
                val arrive = DateFormat.getTimeFormat(context).format(Date(System.currentTimeMillis() + (left * 1000).toLong()))
                Label(arrive, if (compact) Typography.subtitle.copy(fontWeight = FontWeight.SemiBold) else Typography.title, maxLines = 1)
                val minutes = (left / 60).toInt().coerceAtLeast(if (s?.arrived == true) 0 else 1)
                val time = if (minutes < 60) stringResource(R.string.route_minutes, minutes) else stringResource(R.string.route_hours, minutes / 60, minutes % 60)
                Label(
                    "  $time · ${distanceLabel(leftM / 1000, d.prefs.units.miles)}", Typography.small, c.textMuted, maxLines = 1,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
            if (progress) {
                val before = session.doneBefore
                val done = ((before + session.route.distanceM - leftM) / (before + session.route.distanceM).coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f)
                Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.surfaceHigh)) {
                    Box(Modifier.fillMaxWidth(done).fillMaxHeight().background(ROUTE_BLUE))
                }
            }
        }
        if (onEnd != null) IconButton(
            Icons.close, onEnd, Modifier.padding(start = 12.dp), size = if (compact) ROUND_SMALL - 4.dp else ROUND_SMALL,
            background = c.danger, color = Color.White, label = stringResource(R.string.guide_end),
        )
    }
}

/**
 * La guía en el mapa de fondo de una página: la vuelta arriba y la llegada abajo del hueco que dejan los widgets (va
 * encima del tablero, colocada en ese hueco: así se tocan). En un hueco bajo, solo la vuelta compacta y Terminar. Avisa
 * lo que tapa la llegada ([onBottom], px) para que tu auto o la flecha vayan arriba de ella.
 */
@Composable
internal fun GuideCards(d: Dependencies, session: DriveSession, modifier: Modifier, onBottom: (Int) -> Unit) {
    BoxWithConstraints(modifier) {
        if (maxHeight < LOW_HOLE_HEIGHT) {
            val c = LocalColors.current
            LaunchedEffect(Unit) { onBottom(0) }
            Row(
                Modifier.align(Alignment.TopStart).padding(8.dp).widthIn(max = TURN_MAX_WIDTH),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TurnCard(d, session, compact = true, then = false, modifier = Modifier.weight(1f, fill = false))
                IconButton(Icons.close, endGuide, size = ROUND_SMALL, background = c.danger, color = Color.White, label = stringResource(R.string.guide_end))
            }
            return@BoxWithConstraints
        }
        val compact = maxHeight < COMPACT_CARDS_HEIGHT
        val gap = if (compact) 8.dp else 12.dp
        val density = LocalDensity.current
        TurnCard(d, session, compact, then = !compact, modifier = Modifier.align(Alignment.TopStart).padding(gap).widthIn(max = TURN_MAX_WIDTH))
        ArrivalCard(
            d, session, compact, progress = !compact, onEnd = endGuide,
            modifier = Modifier.align(Alignment.BottomStart).padding(gap).widthIn(max = TURN_MAX_WIDTH).fillMaxWidth()
                .onSizeChanged { onBottom(it.height + with(density) { gap.roundToPx() }) },
        )
    }
}

/**
 * La pastilla de la guía cuando no se ve ningún mapa navegando (otra página, la música o un panel): la flecha de la
 * próxima vuelta, a cuánto, la calle y la hora de llegada. Un toque lleva al mapa ([onClick]).
 */
@Composable
internal fun GuidePill(d: Dependencies, session: DriveSession, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    val context = LocalContext.current
    val s = session.state
    val status = guideStatus(session)
    rememberMinute()
    Row(
        modifier.widthIn(max = PILL_MAX_WIDTH).clip(RoundedCornerShape(50)).background(c.surface.copy(alpha = CARD_ALPHA))
            .clickable(role = Role.Button, onClick = onClick).padding(start = 5.dp, end = 16.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (status != null) {
            Box(Modifier.size(PILL_ARROW).clip(RoundedCornerShape(50)).background(c.accent), contentAlignment = Alignment.Center) {
                Icon(Icons.navigate, c.onPrimary, PILL_ARROW * 0.6f)
            }
            Label(stringResource(status), Typography.body, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
        } else {
            val m = nextManeuver(session, s!!)
            ArrowBox(m, PILL_ARROW, Radius.small)
            if (s.arrived) Label(arrivedText(session.to), Typography.body, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            else {
                Label(distanceLabel(s.toNextM / 1000, d.prefs.units.miles), Typography.subtitle.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
                Label(m.road.ifEmpty { maneuverText(m, session.to) }, Typography.body, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            }
        }
        val left = s?.leftS ?: session.route.seconds
        Label(DateFormat.getTimeFormat(context).format(Date(System.currentTimeMillis() + (left * 1000).toLong())), Typography.body, c.textMuted, maxLines = 1)
    }
}

/** La pastilla de la guía para el encabezado de los paneles (null = no navegas o ya se ve un mapa navegando). */
internal val LocalGuidePill = compositionLocalOf<(@Composable () -> Unit)?> { null }

/** La flecha de una vuelta en su cuadro del color de realce (en la llegada, el pin). */
@Composable
private fun ArrowBox(m: Maneuver, side: Dp, radius: Dp = Radius.inner) {
    val c = LocalColors.current
    Box(Modifier.size(side).clip(RoundedCornerShape(radius)).background(c.accent), contentAlignment = Alignment.Center) {
        if (m.type == ManeuverType.ARRIVE) Icon(Icons.location, c.onPrimary, side * 0.55f)
        else ManeuverArrow(m, side * 0.7f, c.onPrimary)
    }
}

/** La flecha de una vuelta: sube derecho y dobla hacia donde se gira (en U, regresa). */
@Composable
internal fun ManeuverArrow(m: Maneuver, side: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(side)) {
        val w = size.minDimension
        val stroke = Stroke(w * 0.13f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val head = w * 0.2f
        val path = Path()
        val tip: Offset
        val dir: Float
        if (m.type == ManeuverType.ROUNDABOUT) {
            // La glorieta: entras por abajo, la rodeas y sales hacia donde va la salida.
            val center = Offset(w * 0.5f, w * 0.45f)
            val r = w * 0.17f
            drawCircle(color, r, center, style = Stroke(w * 0.08f))
            path.moveTo(w * 0.5f, w * 0.95f)
            path.lineTo(w * 0.5f, center.y + r)
            val a = Math.toRadians(m.angle.coerceIn(-150f, 150f).toDouble())
            val out = Offset((sin(a)).toFloat(), (-cos(a)).toFloat())
            path.moveTo(center.x + out.x * r, center.y + out.y * r)
            tip = Offset(center.x + out.x * (r + w * 0.22f), center.y + out.y * (r + w * 0.22f))
            path.lineTo(tip.x, tip.y)
            dir = m.angle.coerceIn(-150f, 150f)
        } else if (m.type == ManeuverType.UTURN) {
            // Sube, da la vuelta y baja del otro lado (a la izquierda en México; al revés si gira a la derecha).
            val side = if (m.angle >= 0) 1f else -1f
            path.moveTo(w * 0.5f + side * w * 0.15f, w * 0.92f)
            path.lineTo(w * 0.5f + side * w * 0.15f, w * 0.4f)
            path.cubicTo(w * 0.5f + side * w * 0.15f, w * 0.1f, w * 0.5f - side * w * 0.25f, w * 0.1f, w * 0.5f - side * w * 0.25f, w * 0.4f)
            path.lineTo(w * 0.5f - side * w * 0.25f, w * 0.62f)
            tip = Offset(w * 0.5f - side * w * 0.25f, w * 0.7f)
            dir = 180f
        } else {
            val a = if (m.type == ManeuverType.DEPART || m.type == ManeuverType.STRAIGHT) 0f else m.angle.coerceIn(-150f, 150f)
            val r = Math.toRadians(a.toDouble())
            val mid = Offset(w * 0.5f, w * 0.55f)
            val len = w * 0.3f
            tip = Offset(mid.x + (len * sin(r)).toFloat(), mid.y - (len * cos(r)).toFloat())
            path.moveTo(w * 0.5f, w * 0.95f)
            path.lineTo(mid.x, mid.y)
            path.lineTo(tip.x, tip.y)
            dir = a
        }
        drawPath(path, color, style = stroke)
        // La punta, girada hacia donde va.
        rotate(dir, tip) {
            val p = Path().apply {
                moveTo(tip.x, tip.y - head * 0.9f)
                lineTo(tip.x + head * 0.8f, tip.y + head * 0.3f)
                lineTo(tip.x - head * 0.8f, tip.y + head * 0.3f)
                close()
            }
            drawPath(p, color)
        }
    }
}

/** Qué hacer en una vuelta, en palabras ("Gira a la izquierda hacia Av. Insurgentes"). */
@Composable
internal fun maneuverText(m: Maneuver, to: SearchHit): String {
    val road = m.road
    val plainId: Int
    val ontoId: Int
    when (m.type) {
        ManeuverType.UTURN -> return stringResource(R.string.guide_uturn)
        ManeuverType.ROUNDABOUT -> if (m.exit > 0) {
            return if (road.isEmpty()) stringResource(R.string.guide_roundabout, m.exit) else stringResource(R.string.guide_roundabout_onto, m.exit, road)
        } else {
            // Sin el número de salida: lo que se gira al salir.
            val ids = TURN_TEXTS.getValue(
                when {
                    m.angle >= 50f -> ManeuverType.RIGHT
                    m.angle <= -50f -> ManeuverType.LEFT
                    else -> ManeuverType.STRAIGHT
                },
            )
            plainId = ids.first
            ontoId = ids.second
        }
        ManeuverType.ARRIVE -> return to.name.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.guide_arrive_at, it) } ?: stringResource(R.string.guide_arrive)
        else -> {
            val ids = TURN_TEXTS.getValue(m.type)
            plainId = ids.first
            ontoId = ids.second
        }
    }
    return if (road.isEmpty()) stringResource(plainId) else stringResource(ontoId, road)
}

/** El texto de cada vuelta: sin calle y con calle. */
private val TURN_TEXTS = mapOf(
    ManeuverType.DEPART to (R.string.guide_depart to R.string.guide_depart_onto),
    ManeuverType.STRAIGHT to (R.string.guide_straight to R.string.guide_straight_onto),
    ManeuverType.SLIGHT_LEFT to (R.string.guide_slight_left to R.string.guide_slight_left_onto),
    ManeuverType.LEFT to (R.string.guide_left to R.string.guide_left_onto),
    ManeuverType.SHARP_LEFT to (R.string.guide_sharp_left to R.string.guide_sharp_left_onto),
    ManeuverType.SLIGHT_RIGHT to (R.string.guide_slight_right to R.string.guide_slight_right_onto),
    ManeuverType.RIGHT to (R.string.guide_right to R.string.guide_right_onto),
    ManeuverType.SHARP_RIGHT to (R.string.guide_sharp_right to R.string.guide_sharp_right_onto),
)

/** La hora de ahora (ms), que cambia al empezar cada minuto. */
@Composable
internal fun rememberMinute(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(CLOCK_TICK_MS - value % CLOCK_TICK_MS)
        }
    }
    return now
}

private const val CLOCK_TICK_MS = 60_000L

/**
 * ¿Ya viene la siguiente vuelta? (a [TURN_AHEAD_M] o menos, sin contar «sigue derecho»): el mapa se acerca un poco y se
 * aplana algo para que se vea la esquina.
 */
internal fun turnAhead(route: Route, s: GuidanceState): Boolean {
    if (s.arrived || s.toNextM > TURN_AHEAD_M) return false
    val type = route.maneuvers.getOrNull(s.next)?.type ?: return false
    return type != ManeuverType.STRAIGHT && type != ManeuverType.DEPART
}

private const val TURN_AHEAD_M = 100.0

/** Las tarjetas de la guía, casi opacas sobre el mapa. */
private const val CARD_ALPHA = 0.96f

/** Lo más ancho de la tarjeta de la vuelta (y de la llegada en el mapa de fondo). */
private val TURN_MAX_WIDTH = 460.dp

/** Lo que ocupa tu flecha a cada lado del centro del mapa. */
private val ARROW_CLEARANCE = 32.dp

/** En un hueco del mapa de fondo más bajo que esto no cabe la llegada: solo la vuelta y Terminar. */
private val LOW_HOLE_HEIGHT = 150.dp

/** Más bajo que esto, las tarjetas van compactas. */
private val COMPACT_CARDS_HEIGHT = 420.dp

/** Los botones redondos sobre el mapa (más chicos con poco alto). */
private val ROUND_BUTTON = 52.dp
private val ROUND_SMALL = 44.dp

/** La pastilla de arriba: lo más ancho y su cuadro de la flecha. */
private val PILL_MAX_WIDTH = 520.dp
private val PILL_ARROW = 30.dp

/**
 * Lo mínimo del lado corto de un mapa navegando para la vista Auto y su cambio Flecha / Auto. Con 240 dp caben, sin
 * encimarse, la vuelta compacta (~80 dp), la velocidad debajo (~85 dp) y la llegada (~65 dp), y tu auto mide sus 96 dp;
 * más chico (widget mediano bajo, 170–240 dp) solo cabe la flecha.
 */
private val CAR_VIEW_MIN_SIDE = 240.dp

/** La velocidad cuando no hay dato. */
private const val NO_SPEED = "—"

/** Lo que ocupa la velocidad de la vista Auto a la izquierda (la tarjeta para bajar el mapa no se le encima). */
private val SPEED_CLEARANCE = 132.dp

/** Con menos alto libre, la tarjeta para bajar el mapa va compacta. */
private val GET_MAP_FULL_HEIGHT = 220.dp
