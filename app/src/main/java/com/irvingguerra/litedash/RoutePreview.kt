package com.irvingguerra.litedash

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
import com.irvingguerra.litedash.feature.map.MapPacks
import com.irvingguerra.litedash.feature.map.Route
import com.irvingguerra.litedash.feature.map.RouteError
import com.irvingguerra.litedash.feature.map.RoutePlan
import com.irvingguerra.litedash.feature.map.SearchHit
import com.irvingguerra.litedash.feature.map.zoomToFit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max

/**
 * La ruta al lugar elegido (M4): se pide al tocar «Ruta» y se calcula en la pantalla con las calles del estado bajado.
 * [to] = a dónde; [plan] = lo que salió (null = buscando). Al moverte no se recalcula (eso es la guía, M5).
 */
@Stable
internal class RouteRequest(val to: SearchHit) {
    var plan by mutableStateOf<RoutePlan?>(null)
    var waitingForPosition by mutableStateOf(false)
    val route: Route? get() = (plan as? RoutePlan.Found)?.route
}

/** Calcula [request] desde donde estás (fuera del hilo principal); si faltaban las calles y ya llegaron, otra vez. */
@Composable
internal fun RunRoute(d: Dependencies, request: RouteRequest) {
    val context = LocalContext.current
    val packs = remember(context) { MapPacks.get(context) }
    val here = d.trip.position
    // Al terminar de bajar las calles de tu estado cambia su huella: se vuelve a intentar.
    val routesReady = packs.installed.joinToString { it.routesSha }
    LaunchedEffect(request, here == null, routesReady) {
        // Ya encontrada, perder la señal un momento no la tapa.
        if (request.plan is RoutePlan.Found) return@LaunchedEffect
        val from = here ?: run {
            request.waitingForPosition = true
            return@LaunchedEffect
        }
        request.waitingForPosition = false
        request.plan = null
        request.plan = withContext(Dispatchers.IO) { packs.planRoute(from.latitude, from.longitude, request.to.lat, request.to.lon) }
    }
}

/** El zoom para ver toda la ruta en una vista de [widthDp] × [heightDp] (el lado corto, con aire). */
internal fun routeZoom(route: Route, widthDp: Float, heightDp: Float): Pair<Int, Pair<Double, Double>> {
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
    val kmLat = (maxLat - minLat) / 1e6 * 111.195
    val kmLon = (maxLon - minLon) / 1e6 * 111.195 * cos(Math.toRadians(lat))
    val radius = (max(abs(kmLat), abs(kmLon)) / 2).toFloat().coerceAtLeast(0.2f)
    // El centro del mapa queda al 60 % del alto (no a la mitad): hacia abajo cabe menos, por eso el alto cuenta un 80 %.
    return zoomToFit(radius, lat, minOf(widthDp, heightDp * 0.8f), ROUTE_TILE_DP).coerceAtMost(FIT_MAX_ZOOM) to (lat to lon)
}

/** Lo que mide una tesela en dp (como la dibuja el mapa en pantallas normales). */
private const val ROUTE_TILE_DP = 256f

/**
 * La tarjeta de la ruta: lo que mide, lo que se tarda (sin tráfico), a qué hora llegas y por dónde va; o, si no salió,
 * por qué y qué hacer (bajar las calles de tu estado, o abrir el lugar en tu app de mapas). [onOpen] = tu app de mapas.
 */
@Composable
internal fun RouteCard(d: Dependencies, request: RouteRequest, appName: String?, onOpen: () -> Unit, back: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    val context = LocalContext.current
    val packs = remember(context) { MapPacks.get(context) }
    val openLabel = if (appName != null) stringResource(R.string.search_open_in, appName) else stringResource(R.string.search_open)
    Column(modifier.clip(RoundedCornerShape(Radius.card)).background(c.surface.copy(alpha = 0.97f)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                val plan = request.plan
                when {
                    request.waitingForPosition -> Message(stringResource(R.string.route_waiting))
                    plan == null -> Message(stringResource(R.string.route_finding))
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
                    plan is RoutePlan.NoState -> Message(stringResource(R.string.route_no_state))
                    plan is RoutePlan.TwoStates -> Message(stringResource(R.string.route_two_states))
                    plan is RoutePlan.Failed -> Message(
                        stringResource(
                            when (plan.error) {
                                RouteError.FROM_OFF_ROAD -> R.string.route_from_off
                                RouteError.TO_OFF_ROAD -> R.string.route_to_off
                                RouteError.NO_PATH -> R.string.route_no_path
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
            Button(openLabel, onOpen, primary = plan !is RoutePlan.NeedsRoutes && plan !is RoutePlan.Found)
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
