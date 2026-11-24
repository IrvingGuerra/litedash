package com.irvingguerra.litedash

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleStartEffect
import com.irvingguerra.litedash.feature.dashboard.Grid
import com.irvingguerra.litedash.feature.dashboard.PageBackground
import com.irvingguerra.litedash.feature.dashboard.Tile
import com.irvingguerra.litedash.feature.dashboard.TileTypes
import com.irvingguerra.litedash.feature.dashboard.freeArea
import com.irvingguerra.litedash.feature.map.Guidance
import com.irvingguerra.litedash.feature.map.GuidanceState
import com.irvingguerra.litedash.feature.map.MapPacks
import com.irvingguerra.litedash.feature.map.OnlineMiss
import com.irvingguerra.litedash.feature.map.OnlineRoute
import com.irvingguerra.litedash.feature.map.Route
import com.irvingguerra.litedash.feature.map.RoutePlan
import com.irvingguerra.litedash.feature.map.SearchHit
import com.irvingguerra.litedash.feature.map.onlineMiss
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * La guía de LiteDash: un solo viaje guiado para toda la app ([session]), sin pantalla propia. «Ir» en la tarjeta de la
 * ruta lo empieza y lo ven los mapas que ya hay: el widget Mapa, el mapa de fondo y el mapa en grande ([GuideView]).
 * Termina con «Terminar» (atrás no: un toque sin querer manejando lo cortaba) o solo [ARRIVED_CLOSE_MS] después de llegar.
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
    /** El último recálculo no se pudo porque falta el mapa y no hay internet (la ruta en línea no se puede rehacer). */
    var noInternet by mutableStateOf(false)
    /** Cuándo se recalculó (`SystemClock.elapsedRealtime`). */
    var lastRecalc = 0L

    /** Cuánto esperar al siguiente recálculo ([RECALC_GAPS_MS]): crece si salen la misma ruta o ninguna. */
    var recalcStep = 0

    /** La hora de la última lectura atendida (dos ventanas pueden recibir la misma). */
    var lastFix = 0L

    /**
     * Cuándo se llegó (`SystemClock.elapsedRealtime`; 0 = aún no o se volvió a salir de la ruta). Vive aquí y no en el
     * inicio: al recrearse la ventana (girar, cambiar de tema) la cuenta del cierre sigue donde iba.
     */
    var arrivedAt = 0L
        private set

    /** Llegaste ahora ([now]): cuánto falta para que la guía termine sola (la primera vez empieza la cuenta). */
    fun closeInMs(now: Long): Long {
        if (arrivedAt == 0L) arrivedAt = now
        return (ARRIVED_CLOSE_MS - (now - arrivedAt)).coerceAtLeast(0L)
    }

    /** Te volviste a salir de la ruta tras llegar: ya no termina sola. */
    fun notArrived() {
        arrivedAt = 0L
    }

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
 * Lo que trabaja la guía, una sola vez para toda la app (no por cada mapa que la muestra): el GPS mientras LiteDash está
 * al frente (aunque ningún mapa se vea: otra página o Ajustes), dónde vas en la ruta con cada lectura, la ruta nueva si te
 * saliste y el cierre solo al llegar. Lo pone el inicio mientras hay [session].
 */
@Composable
internal fun GuideEngine(d: Dependencies, session: DriveSession) {
    val context = LocalContext.current
    val packs = remember(context) { MapPacks.get(context) }
    val scope = rememberCoroutineScope()
    LifecycleStartEffect(d.trip) {
        d.trip.acquire()
        onStopOrDispose { d.trip.release() }
    }
    // Al llegar termina sola un rato después (si no se terminó antes); si se vuelve a salir de la ruta, ya no. «Ver ruta»
    // se cierra al llegar (su botón ya no está).
    val arrived = session.state?.arrived == true
    LaunchedEffect(session, arrived) {
        if (!arrived) {
            session.notArrived()
            return@LaunchedEffect
        }
        session.overview = false
        delay(session.closeInMs(SystemClock.elapsedRealtime()))
        if (Driving.session === session) Driving.session = null
    }
    // Al terminar la guía, los almacenes de la vista Auto ya no sirven.
    DisposableEffect(session) {
        onDispose { if (Driving.session !== session) forgetCarViewStores() }
    }
    val here = d.trip.position
    // Con cada lectura del GPS: dónde vas en la ruta y, si te saliste, una ruta nueva desde donde estás (en la pantalla).
    LaunchedEffect(session, here) {
        val p = here ?: return@LaunchedEffect
        // La misma lectura no cuenta dos veces (con dos ventanas de LiteDash las dos la reciben).
        if (p.time != 0L && p.time == session.lastFix) return@LaunchedEffect
        session.lastFix = p.time
        // Velocidad, rumbo y hora de la lectura (reloj que no brinca): con ellos la guía descarta brincos imposibles del GPS
        // y cuenta el sentido contrario como salirse. El rumbo solo si es de ahora y vas andando (ver [reliableHeading]).
        val speed = if (p.hasSpeed()) p.speed else null
        val fixAgeMs = (SystemClock.elapsedRealtimeNanos() - p.elapsedRealtimeNanos) / 1_000_000
        val heading = reliableHeading(d.trip.bearing, speed, fixAgeMs)
        val accuracy = if (p.hasAccuracy()) p.accuracy else null
        val timeMs = p.elapsedRealtimeNanos / 1_000_000
        val s = session.guidance.update(p.latitude, p.longitude, accuracy, speedMps = speed, headingDeg = heading, timeMs = timeMs)
        session.state = s
        // Las esperas con el reloj que no brinca (en pantallas sin pila la hora se corrige al sincronizar).
        val now = SystemClock.elapsedRealtime()
        if (!s.offRoute) session.recalcStep = 0
        // Una ruta en línea se rehace en el servidor de voluntarios: no más de uno cada [OnlineRoute.RECALC_GAP_MS].
        val gap = maxOf(RECALC_GAPS_MS[session.recalcStep], if (session.route.online) OnlineRoute.RECALC_GAP_MS else 0L)
        if (s.offRoute && !s.arrived && !session.recalculating && now - session.lastRecalc > gap) {
            session.recalculating = true
            session.lastRecalc = now
            scope.launch {
                try {
                    // Andando, la ruta nueva no arranca contra tu rumbo; detenido o sin rumbo fiable, sin castigo (como en la 1.5.14).
                    val plan = withContext(Dispatchers.IO) { packs.planRoute(p.latitude, p.longitude, session.to.lat, session.to.lon, heading) }
                    // Lejos de la calle (estacionamiento, GPS malo) sale la misma ruta: no se cambia (la tarjeta no parpadea).
                    val next = (plan as? RoutePlan.Found)?.route?.takeUnless { it.sameAs(session.route) }
                    // Sin mapa de ahí y sin internet: se sigue la flecha y se vuelve a probar (al volver la red, recalcula).
                    session.noInternet = plan.onlineMiss == OnlineMiss.NO_NET
                    if (next != null) {
                        session.replace(next)
                        // La ruta nueva sale de donde estabas: se te pone en ella ya, sin esperar la siguiente lectura (la
                        // tarjeta no pasa por «Esperando tu ubicación…» un instante).
                        session.state = session.guidance.update(p.latitude, p.longitude, accuracy, speedMps = speed, headingDeg = heading, timeMs = timeMs)
                        session.recalcStep = 0
                    } else if (!session.noInternet) {
                        session.recalcStep = (session.recalcStep + 1).coerceAtMost(RECALC_GAPS_MS.lastIndex)
                    }
                } finally {
                    session.recalculating = false
                }
            }
        }
    }
}

/** Qué mapa de una página enseña la guía. */
internal enum class GuideHome {
    /** El (primer) widget Mapa de la página. */
    WIDGET,

    /** El mapa de fondo de la página (con un hueco libre entre los widgets). */
    BACKDROP,

    /** Ninguno: la página no tiene mapa donde se vea. */
    EXPANDED,
}

/**
 * Qué mapa de una página enseña la guía: el widget Mapa primero, luego el mapa de fondo si los widgets le dejan un hueco
 * ([backdropHole]; sin hueco la guía quedaría tapada: cuenta como sin mapa).
 */
internal fun guideHome(mapWidget: Boolean, backdropHole: Boolean): GuideHome = when {
    mapWidget -> GuideHome.WIDGET
    backdropHole -> GuideHome.BACKDROP
    else -> GuideHome.EXPANDED
}

/**
 * La única regla de dónde se ve la guía («Ir», la pastilla de arriba y «Navegar» de la barra y del volante): la página que
 * se ve si tiene un mapa que la enseñe ([current]); si no, la primera que sí ([firstWithMap]); null = ninguna: el mapa en grande.
 */
internal fun guidePage(current: Int, currentHome: GuideHome, firstWithMap: () -> Int?): Int? =
    if (currentHome != GuideHome.EXPANDED) current else firstWithMap()

/** Qué mapa enseña la guía en una página con fondo [background] y piezas [tiles] (las que se ven) en [grid]. */
internal fun pageGuideHome(background: PageBackground, tiles: List<Tile>, grid: Grid?): GuideHome =
    guideHome(
        mapWidget = tiles.any { it.type == TileTypes.MAP },
        backdropHole = background == PageBackground.MAP && grid != null && tiles.freeArea(grid) != null,
    )

/** Cuánto sigue la guía después de «Llegaste». */
internal const val ARRIVED_CLOSE_MS = 30_000L

/**
 * Entre un recálculo y otro, al menos esto (un GPS que brinca no debe recalcular sin parar); si sale la misma ruta o
 * ninguna, el siguiente espera más. Regresa al primero al aplicar una ruta nueva o al volver a la ruta.
 */
private val RECALC_GAPS_MS = longArrayOf(5_000L, 15_000L, 30_000L)

/**
 * El rumbo [heading] solo cuenta (para seguirte y para planear) si vas a [speedMps] ≥ [HEADING_MIN_MPS] (~11 km/h) y la
 * lectura es de hace no más de [HEADING_MAX_AGE_MS] ([fixAgeMs]); detenido el rumbo guardado es viejo (al empezar la guía
 * parado salía «Da vuelta en U» porque apuntaba contra la ruta). null = sin rumbo fiable.
 */
internal fun reliableHeading(heading: Float?, speedMps: Float?, fixAgeMs: Long): Float? =
    heading?.takeIf { speedMps != null && speedMps >= HEADING_MIN_MPS && fixAgeMs in 0..HEADING_MAX_AGE_MS }

private const val HEADING_MIN_MPS = 3f
private const val HEADING_MAX_AGE_MS = 5_000L

/** ¿La misma ruta? (Mismos puntos, o la misma cantidad con la misma salida, llegada y distancia.) */
private fun Route.sameAs(o: Route): Boolean {
    if (points.contentEquals(o.points)) return true
    val n = points.size
    return n == o.points.size && n >= 2 && points[0] == o.points[0] && points[1] == o.points[1] &&
        points[n - 2] == o.points[n - 2] && points[n - 1] == o.points[n - 1] && abs(distanceM - o.distanceM) < 1.0
}
