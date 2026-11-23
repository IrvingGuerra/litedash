package com.irvingguerra.litedash

import android.location.Location
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Radius
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.feature.map.FIT_MAX_ZOOM
import com.irvingguerra.litedash.feature.map.IndexState
import com.irvingguerra.litedash.feature.map.MapPacks
import com.irvingguerra.litedash.feature.map.Route
import com.irvingguerra.litedash.feature.map.RouteError
import com.irvingguerra.litedash.feature.map.RoutePlan
import com.irvingguerra.litedash.feature.map.SearchHit
import com.irvingguerra.litedash.feature.map.FIT_MIN_ZOOM
import com.irvingguerra.litedash.feature.map.tilePx
import com.irvingguerra.litedash.feature.map.tileX
import com.irvingguerra.litedash.feature.map.tileY
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * La ruta al lugar elegido (M4): se pide al tocar «Ruta» y se calcula en la pantalla con las calles del estado bajado.
 * [to] = a dónde; [plan] = lo que salió (null = buscando). Al moverte se calcula otra vez solo si te alejas más de
 * [RECHECK_M] de donde salió (seguirte vuelta por vuelta es la guía, M5).
 */
@Stable
internal class RouteRequest(val to: SearchHit) {
    var plan by mutableStateOf<RoutePlan?>(null)
    var waitingForPosition by mutableStateOf(false)
    val route: Route? get() = (plan as? RoutePlan.Found)?.route

    /** Desde dónde se calculó la última vez; [moved] = llegó una lectura lejos de ahí (se calcula otra vez, solo una). */
    var origin: Location? = null
    var moved by mutableStateOf(false)
    var redone = false
}

/** Calcula [request] desde donde estás (fuera del hilo principal); si faltaban las calles y ya llegaron, otra vez. */
@Composable
internal fun RunRoute(d: Dependencies, request: RouteRequest) {
    val context = LocalContext.current
    val packs = remember(context) { MapPacks.get(context) }
    val here = d.trip.position
    // Al terminar de bajar las calles de tu estado cambia su huella: se vuelve a intentar.
    val installed = packs.installed
    val routesReady = remember(installed) { installed.joinToString { it.routesSha } }
    // Sin la lista de estados (sin red al abrir) no se sabe de dónde bajar las calles: al llegar, otra vez.
    val regionsReady = packs.regions.isNotEmpty()
    // La primera ubicación puede ser la última conocida de hace horas: si una lectura nueva queda lejos y aún no tocas «Ir».
    LaunchedEffect(request, here) {
        val o = request.origin ?: return@LaunchedEffect
        if (here != null && !request.moved && Driving.session == null && o.distanceTo(here) > RECHECK_M) request.moved = true
    }
    LaunchedEffect(request, here == null, routesReady, regionsReady, request.moved) {
        val redo = request.moved && !request.redone
        // Ya encontrada, perder la señal un momento no la tapa.
        if (request.plan is RoutePlan.Found && !redo) return@LaunchedEffect
        val from = here ?: run {
            request.waitingForPosition = true
            return@LaunchedEffect
        }
        if (redo) request.redone = true
        request.origin = from
        request.waitingForPosition = false
        request.plan = null
        request.plan = withContext(Dispatchers.IO) { packs.planRoute(from.latitude, from.longitude, request.to.lat, request.to.lon) }
    }
}

/**
 * Cómo ver toda la ruta en una vista: el centro ([lat], [lon]) y el zoom justo ([exact], con fracción) para que quepa
 * con aire. El mapa en grande lo usa entero ([zoom] + [scale]); donde no hay escala, [whole] (el nivel de abajo).
 */
internal class RouteFit(val exact: Double, val lat: Double, val lon: Double) {
    /** El nivel de teselas más cercano y cuánto se agranda o achica para llegar a [exact] (como al pellizcar). */
    val zoom: Int get() = exact.roundToInt()
    val scale: Float get() = 2.0.pow(exact - zoom).toFloat()
    val whole: Int get() = floor(exact).toInt()
}

/**
 * El encuadre de la ruta en una vista de [widthDp] × [heightDp]: se ajusta a lo ancho y a lo alto por separado (una ruta
 * alargada llena su lado largo) y sin redondear al nivel de abajo, que la dejaba hasta a la mitad de tamaño. [density] =
 * la de la pantalla (el mapa dibuja cada tesela de [tilePx] píxeles).
 */
internal fun routeFit(route: Route, widthDp: Float, heightDp: Float, density: Float): RouteFit {
    var minLat = Int.MAX_VALUE
    var maxLat = Int.MIN_VALUE
    var minLon = Int.MAX_VALUE
    var maxLon = Int.MIN_VALUE
    for (i in 0 until route.pointCount) {
        val la = route.points[2 * i]
        val lo = route.points[2 * i + 1]
        if (la < minLat) minLat = la
        if (la > maxLat) maxLat = la
        if (lo < minLon) minLon = lo
        if (lo > maxLon) maxLon = lo
    }
    val lat = (minLat + maxLat) / 2e6
    val lon = (minLon + maxLon) / 2e6
    // Lo que abarca, en fracción del mundo (la proyección del mapa).
    val spanX = tileX(maxLon / 1e6, 0) - tileX(minLon / 1e6, 0)
    val spanY = tileY(minLat / 1e6, 0) - tileY(maxLat / 1e6, 0)
    val tileDp = tilePx(density) / density
    // El centro del mapa queda al 60 % del alto (no a la mitad): hacia abajo cabe menos, por eso el alto cuenta un 80 %.
    val tiles = minOf(widthDp * FIT_SHARE / (spanX.coerceAtLeast(MIN_SPAN) * tileDp), heightDp * 0.8f * FIT_SHARE / (spanY.coerceAtLeast(MIN_SPAN) * tileDp))
    val exact = if (tiles > 0.0) log2(tiles).coerceIn(FIT_MIN_ZOOM.toDouble(), FIT_MAX_ZOOM.toDouble()) else FIT_MAX_ZOOM.toDouble()
    return RouteFit(exact, lat, lon)
}

/** Cuánto de la vista ocupa la ruta (el resto es aire a los lados). */
private const val FIT_SHARE = 0.8f

/** Lo menos que abarca una ruta para el encuadre (fracción del mundo, ~40 m): una de unos metros no pide zoom infinito. */
private const val MIN_SPAN = 1e-6

/** Lejos de donde se calculó la ruta (metros), se calcula otra vez. */
private const val RECHECK_M = 500f

/**
 * La tarjeta de la ruta: lo que mide, lo que se tarda (sin tráfico), a qué hora llegas y por dónde va; o, si no salió,
 * por qué y qué hacer (bajar el mapa o las calles de tu estado, o abrir el lugar en tu app de mapas). [onOpen] = tu app
 * de mapas; [onOffline] = Ajustes → Apps → Navegación → Sin conexión.
 */
@Composable
internal fun RouteCard(
    d: Dependencies, request: RouteRequest, appName: String?, onOpen: () -> Unit, onOffline: () -> Unit, back: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalColors.current
    val context = LocalContext.current
    val packs = remember(context) { MapPacks.get(context) }
    val openLabel = if (appName != null) stringResource(R.string.search_open_in, appName) else stringResource(R.string.search_open)
    // Falta un estado pero aún no llega la lista de mapas (se está bajando): no se sabe cuál; al llegar se replanea.
    val listLoading = request.plan.let { it is RoutePlan.NoState && it.region == null || it is RoutePlan.OtherState && it.region == null } &&
        packs.regions.isEmpty() && packs.indexState == IndexState.LOADING
    Column(modifier.clip(RoundedCornerShape(Radius.card)).background(c.surface.copy(alpha = 0.97f)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                val plan = request.plan
                when {
                    request.waitingForPosition -> Message(stringResource(R.string.route_waiting))
                    plan == null -> Message(stringResource(R.string.route_finding))
                    listLoading -> Message(stringResource(R.string.map_list_loading))
                    plan is RoutePlan.Found -> Summary(d, plan.route)
                    plan is RoutePlan.NeedsRoutes -> {
                        val job = packs.routesJob?.takeIf { it.id == plan.region.id }
                        val size = Formatter.formatShortFileSize(context, plan.region.routes?.size ?: 0)
                        Message(stringResource(R.string.route_need_routes, plan.region.name(currentLocale().language), size))
                        when {
                            job == null -> Unit
                            job.failed -> Label(stringResource(R.string.route_download_failed), Typography.small, c.warning, modifier = Modifier.padding(top = 6.dp))
                            else -> Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.surfaceHigh)) {
                                Box(Modifier.fillMaxWidth(job.progress).fillMaxHeight().background(c.accent))
                            }
                        }
                    }
                    plan is RoutePlan.OtherState -> Message(
                        plan.region?.let { stringResource(R.string.route_other_state, it.name(currentLocale().language)) }
                            ?: stringResource(R.string.route_outside),
                    )
                    plan is RoutePlan.NoState -> Message(
                        when {
                            plan.region != null -> stringResource(R.string.route_no_state_named, plan.region!!.name(currentLocale().language))
                            // Sin la lista de estados (sin red al abrir) no se sabe si hay mapa de ahí.
                            packs.regions.isEmpty() -> stringResource(R.string.route_no_state)
                            else -> stringResource(R.string.route_no_map)
                        },
                    )
                    plan is RoutePlan.TwoStates -> Message(stringResource(R.string.route_two_states))
                    plan is RoutePlan.Failed -> Message(
                        stringResource(
                            when (plan.error) {
                                RouteError.FROM_OFF_ROAD -> R.string.route_from_off
                                RouteError.TO_OFF_ROAD -> R.string.route_to_off
                                RouteError.NO_PATH -> R.string.route_no_path
                                RouteError.TOO_BIG -> R.string.route_too_big
                            },
                        ),
                    )
                }
            }
            IconButton(Icons.close, back, size = 36.dp, color = c.textMuted, label = stringResource(R.string.route_back))
        }
        FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val plan = request.plan
            if (plan is RoutePlan.NeedsRoutes) {
                val job = packs.routesJob?.takeIf { it.id == plan.region.id }
                if (job == null || job.failed) Button(stringResource(R.string.route_download), { packs.downloadRoutes(plan.region) }, primary = true)
            }
            if (plan is RoutePlan.Found) Button(stringResource(R.string.guide_go), { Driving.session = DriveSession(request.to, plan.route) }, primary = true)
            // Falta un estado que sí existe (o no se sabe): un atajo a donde se baja.
            val getMap = when {
                listLoading -> false
                plan is RoutePlan.NoState -> plan.region != null || packs.regions.isEmpty()
                plan is RoutePlan.OtherState -> plan.region != null
                else -> false
            }
            if (getMap) Button(stringResource(R.string.route_get_map), onOffline, primary = true)
            // Sin mapa de ahí todavía: a la lista de los que hay (ahí mismo se pide el de tu país).
            val noMap = plan is RoutePlan.NoState && !getMap && !listLoading
            if (noMap) Button(stringResource(R.string.route_see_maps), onOffline, primary = true)
            // Sin «Navegar con: App de mapas» ([appName] null), otra app solo como salida cuando LiteDash no pudo trazar la ruta.
            if (appName != null || (plan != null && plan !is RoutePlan.Found)) {
                Button(openLabel, onOpen, primary = plan !is RoutePlan.NeedsRoutes && plan !is RoutePlan.Found && !getMap && !noMap)
            }
        }
    }
}

@Composable
private fun Message(text: String) = Label(text, Typography.body, LocalColors.current.text, maxLines = 4)

/** "26 km · 30 min" grande; abajo "Llegas a las 14:35 · por Paseo Tollocan · sin contar el tráfico". */
@Composable
private fun Summary(d: Dependencies, route: Route) {
    val c = LocalColors.current
    val context = LocalContext.current
    val minutes = (route.seconds / 60).toInt().coerceAtLeast(1)
    val time = if (minutes < 60) stringResource(R.string.route_minutes, minutes) else stringResource(R.string.route_hours, minutes / 60, minutes % 60)
    Label("${distanceLabel(route.distanceM / 1000, d.prefs.units.miles)} · $time", Typography.title, maxLines = 1)
    val arrive = DateFormat.getTimeFormat(context).format(Date(System.currentTimeMillis() + (route.seconds * 1000).toLong()))
    val details = listOfNotNull(
        stringResource(R.string.route_arrive, arrive),
        route.mainRoad.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.route_via, it) },
        stringResource(R.string.route_no_traffic),
    ).joinToString(" · ")
    Label(details, Typography.small, c.textMuted, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
}
