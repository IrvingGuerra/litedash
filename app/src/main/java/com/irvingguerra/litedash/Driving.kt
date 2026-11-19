package com.irvingguerra.litedash

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Radius
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.core.vehicle.Signal
import com.irvingguerra.litedash.core.vehicle.watch
import com.irvingguerra.litedash.feature.car.snapshot
import com.irvingguerra.litedash.feature.map.Guidance
import com.irvingguerra.litedash.feature.map.GuidanceState
import com.irvingguerra.litedash.feature.map.Maneuver
import com.irvingguerra.litedash.feature.map.ManeuverType
import com.irvingguerra.litedash.feature.map.MapCanvas
import com.irvingguerra.litedash.feature.map.MapDetail
import com.irvingguerra.litedash.feature.map.MapLook
import com.irvingguerra.litedash.feature.map.MapOrientation
import com.irvingguerra.litedash.feature.map.MapPacks
import com.irvingguerra.litedash.feature.map.MapZoom
import com.irvingguerra.litedash.feature.map.Route
import com.irvingguerra.litedash.feature.map.RoutePlan
import com.irvingguerra.litedash.feature.map.ROUTE_BLUE
import com.irvingguerra.litedash.feature.map.SearchHit
import com.irvingguerra.litedash.feature.music.MusicTile
import com.irvingguerra.litedash.feature.map.rememberMapCamera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * La guía de LiteDash (M5): una pantalla aparte que tapa el inicio mientras manejas a [DriveSession.to]. Se entra con
 * «Ir» en la tarjeta de la ruta y se sale con «Terminar» (o atrás): regresas a donde estabas, sin tocar tu acomodo.
 */
internal object Driving {
    var session by mutableStateOf<DriveSession?>(null)
}

/** Un viaje guiado: a dónde, su ruta (cambia al recalcular), cómo vas y si se ve toda la ruta en el mapa. */
@Stable
internal class DriveSession(val to: SearchHit, route: Route) {
    var route by mutableStateOf(route)
        private set
    var guidance = Guidance(route)
        private set
    var state by mutableStateOf<GuidanceState?>(null)
    var recalculating by mutableStateOf(false)
    var overview by mutableStateOf(false)
    var lastRecalc = 0L

    /** Cuánto esperar al siguiente recálculo ([RECALC_GAPS_MS]): crece si salen la misma ruta o ninguna. */
    var recalcStep = 0

    /** La hora de la última lectura atendida (dos ventanas pueden recibir la misma). */
    var lastFix = 0L

    /** Lo recorrido en rutas anteriores de este viaje (al recalcular, la barra de avance no vuelve a cero). */
    var doneBefore = 0.0
        private set

    fun replace(next: Route) {
        doneBefore += state?.doneM ?: 0.0
        route = next
        guidance = Guidance(next)
        state = null
    }
}

/**
 * La pantalla de conducción (como Tesla): ancha, a la izquierda tu auto de espaldas sobre las calles en 3D con la ruta
 * azul y a la derecha el mapa con la próxima vuelta arriba, la llegada abajo y los botones redondos a la orilla, y debajo
 * del mapa la música; de pie, el auto arriba, el mapa y la música abajo. Fuera de la ruta recalcula en la pantalla; al
 * llegar dice «Llegaste». [revision] = la de la ventana (la música revisa sus permisos al volver).
 */
@Composable
internal fun DrivingScreen(d: Dependencies, session: DriveSession, revision: Int) {
    val c = LocalColors.current
    // Se entra desde la tarjeta de la ruta con el buscador quizá abierto: que el teclado no se quede encima de la guía.
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(session) { keyboard?.hide() }
    val context = LocalContext.current
    val packs = remember(context) { MapPacks.get(context) }
    val scope = rememberCoroutineScope()
    KeepTripRunning(d.trip)
    val end = { Driving.session = null }
    BackHandler(onBack = end)
    val here = d.trip.position
    // Con cada lectura del GPS: dónde vas en la ruta y, si te saliste, una ruta nueva desde donde estás (en la pantalla).
    LaunchedEffect(session, here) {
        val p = here ?: return@LaunchedEffect
        // La misma lectura no cuenta dos veces (con dos ventanas de LiteDash las dos la reciben).
        if (p.time != 0L && p.time == session.lastFix) return@LaunchedEffect
        session.lastFix = p.time
        val s = session.guidance.update(p.latitude, p.longitude, if (p.hasAccuracy()) p.accuracy else null)
        session.state = s
        val now = System.currentTimeMillis()
        if (!s.offRoute) session.recalcStep = 0
        if (s.offRoute && !s.arrived && !session.recalculating && now - session.lastRecalc > RECALC_GAPS_MS[session.recalcStep]) {
            session.recalculating = true
            session.lastRecalc = now
            scope.launch {
                try {
                    val plan = withContext(Dispatchers.IO) { packs.planRoute(p.latitude, p.longitude, session.to.lat, session.to.lon) }
                    // Lejos de la calle (estacionamiento, GPS malo) sale la misma ruta: no se cambia (la tarjeta no parpadea).
                    val next = (plan as? RoutePlan.Found)?.route?.takeUnless { it.sameAs(session.route) }
                    if (next != null) {
                        session.replace(next)
                        session.recalcStep = 0
                    } else {
                        session.recalcStep = (session.recalcStep + 1).coerceAtMost(RECALC_GAPS_MS.lastIndex)
                    }
                } finally {
                    session.recalculating = false
                }
            }
        }
    }
    // Lo de abajo no se dibuja ni trabaja: esta pantalla lo tapa todo.
    CompositionLocalProvider(LocalHomeCovered provides false) {
        BoxWithConstraints(Modifier.fillMaxSize().background(c.background)) {
            val wide = maxWidth >= WIDE_MIN && maxWidth > maxHeight * 1.2f
            // La música, como Tesla, abajo del lado del mapa (más baja en pantallas bajas); el auto llega hasta abajo.
            val musicHeight = if (maxHeight < SHORT_SCREEN) MUSIC_SHORT else MUSIC_HEIGHT
            val music = @Composable {
                Card(Modifier.fillMaxWidth().height(musicHeight).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    MusicTile(d.music, revision, Modifier.fillMaxSize())
                }
            }
            if (wide) Row(Modifier.fillMaxSize()) {
                CarView(d, session, Modifier.weight(0.45f).fillMaxHeight())
                // Abajo la barra de Android (en pantallas con botones) no tapa la música.
                Column(Modifier.weight(0.55f).fillMaxHeight().navigationBarsPadding()) {
                    MapView(d, session, end, Modifier.weight(1f).fillMaxWidth(), turnOnMap = true)
                    music()
                }
            } else Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                Box(Modifier.weight(0.58f).fillMaxWidth()) {
                    CarView(d, session, Modifier.fillMaxSize())
                    TurnCard(d, session, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp).widthIn(max = 520.dp))
                }
                MapView(d, session, end, Modifier.weight(0.42f).fillMaxWidth(), turnOnMap = false)
                music()
            }
        }
    }
}

/** Tu auto de espaldas sobre las calles (o la flecha sin auto), siempre hacia donde vas, con la velocidad arriba. */
@Composable
private fun CarView(d: Dependencies, session: DriveSession, modifier: Modifier) {
    val c = LocalColors.current
    // Siempre el minimalista: el mapa completo (imágenes de OSM) no sabe dibujar calles a escala real.
    val look = remember(d.prefs.mapLook) {
        MapLook.parse(d.prefs.mapLook.orEmpty()).copy(zoom = MapZoom.AUTO, orientation = MapOrientation.HEADING, detail = MapDetail.MINIMAL)
    }
    val camera = rememberMapCamera()
    val car = d.cars.pkg(d.prefs.carModel)
    val paint = d.prefs.carPaint
    val marker by produceState<ImageBitmap?>(null, car?.id, paint) {
        value = car?.let { d.cars.snapshot(it, paint, MARKER_PX, turn = REAR_TURN, floor = false)?.solidPart()?.asImageBitmap() }
    }
    val position = d.trip.position
    val kmh = d.bus.watch(Signal.SPEED)
    val s = session.state
    BoxWithConstraints(modifier.clipToBounds()) {
        // Tu auto mide lo mismo que en el mapa de fondo (22 % del lado corto, 140–240 dp): las calles y la ruta, a su escala.
        val carDp = (minOf(maxWidth, maxHeight).value * 0.22f).coerceIn(140f, 240f)
        val store = rememberTileStore(look, c.dark, DRIVE_CAR_KEY, realRoadDp = carDp)
        if (position != null) {
            // Sobre la ruta, tu auto va en ella y mirando hacia donde sigue (como Waze); fuera, como diga el GPS.
            val onRoute = s != null && !s.offRoute
            MapCanvas(
                store, if (onRoute) s!!.lat else position.latitude, if (onRoute) s!!.lon else position.longitude,
                (if (onRoute) s!!.heading else null) ?: d.trip.bearing, kmh, look, Modifier.fillMaxSize(), camera,
                navigating = true, interactive = false, marker = marker,
                route = session.route.points, routeDone = s?.let { it.point + it.fraction } ?: 0f, routeWidth = carDp.dp,
            )
        }
        // La velocidad, grande, arriba a la izquierda (como la maqueta).
        Column(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(20.dp)) {
            val miles = d.prefs.units.miles
            val speed = ((kmh ?: 0f) * if (miles) 0.621371f else 1f).toInt()
            Label("$speed", Typography.clock.copy(fontSize = 52.sp, fontWeight = FontWeight.Bold), maxLines = 1)
            Label(stringResource(if (miles) R.string.unit_mph else R.string.unit_kmh), Typography.small, c.textMuted, maxLines = 1)
        }
    }
}

/**
 * El mapa con la ruta (de cerca o, con «Ver ruta», toda), la próxima vuelta arriba si [turnOnMap] (en la vista ancha),
 * la hora y la llegada abajo con la barra de avance, y Ver ruta / Terminar.
 */
@Composable
private fun MapView(d: Dependencies, session: DriveSession, end: () -> Unit, modifier: Modifier, turnOnMap: Boolean) {
    val c = LocalColors.current
    val context = LocalContext.current
    val heading = d.prefs.guideMapHeading && !session.overview
    val look = remember(d.prefs.mapLook, heading) {
        MapLook.parse(d.prefs.mapLook.orEmpty()).copy(orientation = if (heading) MapOrientation.HEADING else MapOrientation.NORTH)
    }
    val store = rememberTileStore(look, c.dark, DRIVE_MAP_KEY)
    val camera = rememberMapCamera()
    val position = d.trip.position
    val s = session.state
    BoxWithConstraints(modifier.clipToBounds()) {
        val density = LocalDensity.current.density
        val fit = if (session.overview) remember(session.route, maxWidth, maxHeight, density) { routeFit(session.route, maxWidth.value, maxHeight.value, density) } else null
        LaunchedEffect(fit?.lat, fit?.lon) {
            if (fit != null) camera.show(fit.lat, fit.lon) else camera.recenter()
        }
        if (position != null) {
            val onRoute = s != null && !s.offRoute
            MapCanvas(
                store, if (onRoute) s!!.lat else position.latitude, if (onRoute) s!!.lon else position.longitude,
                (if (onRoute) s!!.heading else null) ?: d.trip.bearing, null, look, Modifier.fillMaxSize(), camera,
                fixedZoom = fit?.whole ?: CLOSE_ZOOM, interactive = false,
                route = session.route.points, routeDone = s?.let { it.point + it.fraction } ?: 0f,
            )
        }
        // En la vista ancha el mapa llega hasta arriba: lo de arriba, bajo la barra de estado de Android.
        val top = if (turnOnMap) Modifier.statusBarsPadding() else Modifier
        if (turnOnMap) TurnCard(d, session, Modifier.align(Alignment.TopStart).then(top).padding(12.dp).widthIn(max = 440.dp))
        Clock(Modifier.align(Alignment.TopEnd).then(top).padding(16.dp))
        // A la orilla derecha, redondos: ver toda la ruta (o seguirte), la brújula y terminar.
        Column(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (s?.arrived != true) {
                IconButton(
                    if (session.overview) Icons.navigate else Icons.map, { session.overview = !session.overview },
                    size = ROUND_BUTTON, background = c.background.copy(alpha = 0.9f), color = c.text,
                    label = stringResource(if (session.overview) R.string.guide_follow else R.string.guide_overview),
                )
            }
            if (!session.overview) {
                CompassButton(
                    if (heading) -(((if (s != null && !s.offRoute) s.heading else null) ?: d.trip.bearing) ?: 0f) else 0f,
                    northUp = !heading, onClick = { d.prefs.guideMapHeading = !d.prefs.guideMapHeading }, size = ROUND_BUTTON,
                    background = c.background.copy(alpha = 0.9f),
                )
            }
            IconButton(Icons.close, end, size = ROUND_BUTTON, background = c.danger, color = Color.White, label = stringResource(R.string.guide_end))
        }
        // Abajo: llegada, lo que falta y la barra de avance (sin tapar los botones de la orilla).
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 12.dp, end = ROUND_BUTTON + 24.dp).fillMaxWidth()
                .clip(RoundedCornerShape(Radius.card)).background(c.surface.copy(alpha = 0.96f)).padding(16.dp),
        ) {
            val left = s?.leftS ?: session.route.seconds
            val leftM = s?.leftM ?: session.route.distanceM
            Row(verticalAlignment = Alignment.Bottom) {
                val arrive = DateFormat.getTimeFormat(context).format(Date(System.currentTimeMillis() + (left * 1000).toLong()))
                Label(arrive, Typography.title, maxLines = 1)
                val minutes = (left / 60).toInt().coerceAtLeast(if (s?.arrived == true) 0 else 1)
                val time = if (minutes < 60) stringResource(R.string.route_minutes, minutes) else stringResource(R.string.route_hours, minutes / 60, minutes % 60)
                Label(
                    "  $time · ${distanceLabel(leftM / 1000, d.prefs.units.miles)}", Typography.small, c.textMuted, maxLines = 1,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
            val before = session.doneBefore
            val progress = ((before + session.route.distanceM - leftM) / (before + session.route.distanceM).coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f)
            Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.surfaceHigh)) {
                Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(ROUTE_BLUE))
            }
        }
    }
}

/** La próxima vuelta: flecha, a cuánto y qué hacer; abajo «Luego» con la siguiente. Al llegar, «Llegaste». */
@Composable
private fun TurnCard(d: Dependencies, session: DriveSession, modifier: Modifier) {
    val c = LocalColors.current
    val s = session.state
    val route = session.route
    Row(modifier.clip(RoundedCornerShape(Radius.card)).background(c.surface.copy(alpha = 0.97f)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            session.recalculating -> Label(stringResource(R.string.guide_recalculating), Typography.title, maxLines = 1)
            s == null -> Label(stringResource(R.string.route_waiting), Typography.title, maxLines = 1)
            s.arrived -> {
                ArrowBox(route.maneuvers.last(), 64.dp)
                Label(
                    session.to.name.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.guide_arrived_to, it) } ?: stringResource(R.string.guide_arrived),
                    Typography.title, maxLines = 2, modifier = Modifier.padding(start = 14.dp),
                )
            }
            else -> {
                val m = route.maneuvers[s.next]
                ArrowBox(m, 64.dp)
                Column(Modifier.padding(start = 14.dp)) {
                    Label(distanceLabel(s.toNextM / 1000, d.prefs.units.miles), Typography.clock.copy(fontSize = 30.sp), maxLines = 1)
                    Label(maneuverText(m, session.to), Typography.body, maxLines = 2)
                    val then = s.then?.let { route.maneuvers[it] }
                    if (then != null && then.type != ManeuverType.ARRIVE) {
                        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Label(stringResource(R.string.guide_then), Typography.small, c.textMuted, maxLines = 1)
                            ManeuverArrow(then, 16.dp, c.textMuted, Modifier.padding(horizontal = 6.dp))
                            Label(then.road.ifEmpty { maneuverText(then, session.to) }, Typography.small, c.textMuted, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArrowBox(m: Maneuver, side: Dp) {
    val c = LocalColors.current
    Box(Modifier.size(side).clip(RoundedCornerShape(Radius.inner)).background(c.accent), contentAlignment = Alignment.Center) {
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
        if (m.type == ManeuverType.UTURN) {
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

/** La hora, al minuto. */
@Composable
private fun Clock(modifier: Modifier) {
    val context = LocalContext.current
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(CLOCK_TICK_MS - value % CLOCK_TICK_MS)
        }
    }
    Label(DateFormat.getTimeFormat(context).format(Date(now)), Typography.subtitle, maxLines = 1, modifier = modifier)
}

/** Los botones redondos de la orilla del mapa. */
private val ROUND_BUTTON = 52.dp

/** La franja de la música abajo; más baja si la pantalla es baja (las de 1280×480). */
private val MUSIC_HEIGHT = 96.dp
private val MUSIC_SHORT = 80.dp
private val SHORT_SCREEN = 420.dp

/** Desde qué ancho se ponen el auto y el mapa lado a lado. */
private val WIDE_MIN = 720.dp

/** El zoom del mapa de la derecha mientras manejas (calles alrededor). */
private const val CLOSE_ZOOM = 15

/**
 * Entre un recálculo y otro, al menos esto (un GPS que brinca no debe recalcular sin parar); si sale la misma ruta o
 * ninguna, el siguiente espera más. Regresa al primero al aplicar una ruta nueva o al volver a la ruta.
 */
private val RECALC_GAPS_MS = longArrayOf(5_000L, 15_000L, 30_000L)

/** ¿La misma ruta? (Mismos puntos, o la misma cantidad con la misma salida, llegada y distancia.) */
private fun Route.sameAs(o: Route): Boolean {
    if (points.contentEquals(o.points)) return true
    val n = points.size
    return n == o.points.size && n >= 2 && points[0] == o.points[0] && points[1] == o.points[1] &&
        points[n - 2] == o.points[n - 2] && points[n - 1] == o.points[n - 1] && abs(distanceM - o.distanceM) < 1.0
}

private const val CLOCK_TICK_MS = 60_000L

/** El almacén de teselas de cada vista de la guía (no se mezcla con los widgets). */
private const val DRIVE_CAR_KEY = "drive-car"
private const val DRIVE_MAP_KEY = "drive-map"
