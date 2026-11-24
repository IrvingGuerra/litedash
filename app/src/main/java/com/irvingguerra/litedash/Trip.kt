package com.irvingguerra.litedash

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.location.LocationListenerCompat
import kotlin.concurrent.thread
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Divider
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.TilePadding
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.core.ui.fitFont
import com.irvingguerra.litedash.core.vehicle.Signal
import com.irvingguerra.litedash.core.vehicle.Source
import com.irvingguerra.litedash.core.vehicle.VehicleBus
import kotlinx.coroutines.delay

/** Un punto del GPS de hace menos que esto manda sobre la red. */
private const val GPS_FRESH_MS = 5_000L

/**
 * Un viaje termina tras este rato sin avanzar (estacionado, apagado o con la pantalla dormida): el
 * siguiente empieza en cero. Una parada a comer no lo corta; la noche estacionado, sí.
 */
internal const val TRIP_IDLE_MS = 3 * 3_600_000L

/** Más lento que esto (km/h) entre dos puntos es estar parado: ese rato no cuenta para el tiempo ni el promedio. */
private const val MOVING_KMH = 3.0

/**
 * El viaje en curso, sin Android: metros, tiempo en movimiento y los relojes de arranque del primer y
 * del último punto en movimiento ([startedAt] y [lastAt]; 0 = aún no empieza).
 */
internal data class TripLeg(val meters: Double = 0.0, val movingMs: Long = 0L, val startedAt: Long = 0L, val lastAt: Long = 0L) {
    /** Ya terminó: pasó [TRIP_IDLE_MS] sin avanzar. */
    fun over(now: Long) = lastAt != 0L && now - lastAt > TRIP_IDLE_MS

    /**
     * Un punto en movimiento a [now], a [step] metros del anterior. El rato desde el anterior solo cuenta si se
     * avanzó a más de [MOVING_KMH] (un semáforo o el auto estacionado con la app cerrada, no). Si el viaje no
     * había empezado o ya terminó, este punto empieza uno nuevo.
     */
    fun moved(now: Long, step: Double): TripLeg {
        if (lastAt == 0L || over(now)) return TripLeg(startedAt = now, lastAt = now)
        val dt = now - lastAt
        // m/ms × 3 600 = km/h.
        val moving = dt > 0 && step / dt * 3_600.0 > MOVING_KMH
        return copy(meters = meters + step, movingMs = movingMs + if (moving) dt else 0L, lastAt = now)
    }

    /** km/h de promedio en movimiento; null mientras hay poco que promediar (un minuto y 100 m). */
    fun averageKmh(): Double? = if (movingMs >= 60_000 && meters >= 100) meters / movingMs * 3_600.0 else null
}

/**
 * La última ubicación conocida más reciente de [providers] (null si ninguno tiene). Es una llamada al
 * sistema: fuera del hilo principal. Quien llama revisa antes el permiso.
 */
@SuppressLint("MissingPermission")
internal fun LocationManager.newestLastKnown(providers: List<String>): Location? =
    providers.mapNotNull { p ->
        try {
            getLastKnownLocation(p)
        } catch (e: IllegalArgumentException) {
            null // el proveedor no existe en esta pantalla
        } catch (e: SecurityException) {
            null // se quitó el permiso
        }
    }.maxByOrNull { it.time }

/**
 * Velocidad (al bus), rumbo y distancia del viaje con el GPS. Solo escucha mientras algo
 * lo pide ([acquire]/[release]) y la app está al frente: así no gasta batería
 * con la pantalla en otra app ni en segundo plano. Con solo la ubicación aproximada (o en pantallas
 * sin antena GPS) usa la red: mueve el punto del mapa, pero no da velocidad ni rumbo ni suma al viaje.
 */
class TripRepository(private val context: Context, private val bus: VehicleBus) {
    /** km/h del GPS; null = sin señal todavía. Las piezas leen la velocidad del bus (el OBD gana). */
    private var speedKmh: Float? = null
    var bearing by mutableStateOf<Float?>(null)
        private set
    /** La última posición (la lee el mapa); se queda aunque el GPS se apague. */
    var position by mutableStateOf<Location?>(null)
        private set
    /** El viaje tal como va (puede haber terminado: ver [current]). */
    internal var leg by mutableStateOf(TripLeg())
        private set

    /** El viaje a la hora [now] (reloj de arranque): vacío si ya terminó por llevar mucho sin avanzar. */
    internal fun current(now: Long = SystemClock.elapsedRealtime()) = leg.takeUnless { it.over(now) } ?: TripLeg()

    val meters get() = current().meters

    /** Reloj de arranque del primer punto en movimiento del viaje (0 = todavía no arranca). */
    val startedAt get() = current().startedAt

    /**
     * Si hay permiso de ubicación. Es estado: al darlo desde un widget, todos los que lo piden lo ven a
     * la vez. Se pone al día con [refreshPermission] (al responder el diálogo y al volver a la app).
     */
    var permitted by mutableStateOf(hasPermission())
        private set

    /** Con la ubicación precisa (con solo la aproximada no hay velocidad ni viaje). Estado, como [permitted]. */
    var precise by mutableStateOf(granted(Manifest.permission.ACCESS_FINE_LOCATION))
        private set

    private var users = 0
    private var listening = false
    /** Los proveedores que sí quedaron escuchando y la última falla al registrarlos (para el reporte). */
    private var registered = emptyList<String>()
    private var failure: String? = null
    private var last: Location? = null
    /** Reloj de arranque de cuando dejó de escuchar (0 = nunca). */
    private var releasedAt = 0L
    private val manager by lazy { context.getSystemService(LocationManager::class.java) }
    // Compat: en Android 6–10 LocationListener tiene más métodos abstractos que el lambda no
    // implementa (AbstractMethodError al perder el GPS o apagar la ubicación).
    private val listener = LocationListenerCompat { update(it) }

    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Ubicación precisa o aproximada (en Android 12+ el usuario puede dar solo la aproximada). */
    private fun hasPermission() = granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    fun refreshPermission() {
        permitted = hasPermission()
        precise = granted(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    /**
     * Con la precisa, el GPS y también la red: muchas pantallas no traen antena GPS (o no está
     * conectada) y Google Maps las ubica por Wi-Fi; sin la red, el mapa se quedaba buscando para
     * siempre. Con la aproximada, solo la red.
     */
    private fun providers(): List<String> {
        val wanted = if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            else listOf(LocationManager.NETWORK_PROVIDER)
        val existing = manager.allProviders
        return wanted.filter { it in existing }
    }

    /**
     * Alguien quiere el GPS. Empieza a escuchar si aún no lo hace y ya hay permiso, aunque otros lo
     * hayan pedido antes sin permiso (el que lo pidió primero no lo deja apagado para siempre).
     */
    fun acquire() {
        users++
        // Tras un buen rato sin escuchar (estacionado, en otra app) el último rumbo ya no es de ahora: no se sigue
        // mostrando. Un cambio de pantalla (soltar y volver a pedir enseguida) lo conserva.
        if (users == 1 && releasedAt != 0L && SystemClock.elapsedRealtime() - releasedAt > BEARING_STALE_MS) bearing = null
        refreshPermission()
        if (!listening && permitted) listen()
    }

    @SuppressLint("MissingPermission") // se revisa con permitted
    private fun listen() {
        listening = true
        // La falla de antes no se queda en el reporte si esta vez sale bien.
        failure = null
        val providers = providers()
        registered = providers.filter { provider ->
            try {
                manager.requestLocationUpdates(provider, 1_000L, 0f, listener, Looper.getMainLooper())
                true
            } catch (e: IllegalArgumentException) {
                failure = "$provider: ${e.message}"
                false
            } catch (e: SecurityException) {
                failure = "$provider: sin permiso"
                false
            }
        }
        // Mientras llega el primer punto, la última conocida (el mapa no espera en blanco). Es una
        // llamada al sistema: fuera del hilo principal (el estado se puede escribir desde ahí).
        if (position == null) thread(name = "last-location") {
            val last = manager.newestLastKnown(providers)
            // En el principal: si mientras tanto llegó un punto, no se pisa con uno más viejo.
            if (last != null && (last.latitude != 0.0 || last.longitude != 0.0)) main.post { if (position == null) position = last }
        }
    }

    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users > 0) return
        if (listening) {
            manager.removeUpdates(listener)
            listening = false
            registered = emptyList()
        }
        speedKmh = null
        releasedAt = SystemClock.elapsedRealtime()
        // Al volver (quizá horas después y en otro lugar), el rumbo no se calcula desde el punto de antes.
        courseFrom = null
        bus.publish(Signal.SPEED, null, Source.GPS)
    }

    fun reset() {
        leg = TripLeg()
        last = null
    }

    /** Para el reporte técnico: qué proveedores escuchan y la última falla al registrarlos. */
    fun describe(): String =
        "GPS: " + (if (listening) "escucha ${registered.ifEmpty { listOf("ninguno") }.joinToString()}" else "sin escuchar") +
            (failure?.let { " · falló $it" } ?: "")

    /** Reloj de arranque del último punto del GPS: mientras llega, la red (menos precisa) no lo pisa. */
    private var lastGpsAt = 0L
    private val main = Handler(Looper.getMainLooper())

    /** Donde se midió el último rumbo calculado con el avance (para cuando el GPS no da uno confiable). */
    private var courseFrom: Location? = null

    /**
     * Hacia dónde vas: el rumbo del GPS si es confiable; si no lo trae o viene impreciso (algunas pantallas mandan 0°),
     * el del avance entre lecturas (a partir de [COURSE_METERS]). Así el mapa siempre mira hacia adelante, como Waze.
     */
    private fun heading(l: Location): Float? {
        val from = courseFrom
        val moved = if (from != null && from.distanceTo(l) >= COURSE_METERS) ((from.bearingTo(l) + 360f) % 360f).also { courseFrom = l } else null
        if (from == null) courseFrom = l
        val trusted = l.hasBearing() && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !l.hasBearingAccuracy() || l.bearingAccuracyDegrees <= MAX_BEARING_ERROR)
        return if (trusted) l.bearing else moved
    }

    private fun update(l: Location) {
        // Sin señal, algunas pantallas mandan 0,0 como si fuera un punto (el mapa se iba al mar): se ignora.
        if (l.latitude == 0.0 && l.longitude == 0.0) return
        val now = SystemClock.elapsedRealtime()
        if (l.provider != LocationManager.GPS_PROVIDER) {
            // La red solo mueve el punto del mapa: no trae velocidad ni rumbo y no cuenta para el viaje
            // (sin GPS, el velocímetro se queda con lo último en vez de apagarse en un túnel).
            if (now - lastGpsAt >= GPS_FRESH_MS) position = l
            return
        }
        lastGpsAt = now
        position = l
        speedKmh = if (l.hasSpeed()) l.speed * 3.6f else null
        bus.publish(Signal.SPEED, speedKmh, Source.GPS)
        if ((speedKmh ?: 0f) > 3f) heading(l)?.let { bearing = it }
        // Solo puntos buenos y en movimiento: parado, el GPS "camina" solo unos metros.
        if (l.accuracy <= 30f && (speedKmh ?: 0f) > 3f) {
            leg = leg.moved(now, last?.distanceTo(l)?.toDouble() ?: 0.0)
            last = l
        }
    }
}

/**
 * El inicio está tapado del todo por algo opaco (estacionado, el auto o Llantas ya en grande; el tablero también
 * con la música o el mapa en grande): no se dibuja y lo que gasta (GPS, mapa, avisos del sistema) descansa.
 */
val LocalHomeCovered = compositionLocalOf { false }

/** Escucha el GPS mientras la pieza se ve (sin nada encima) y la app está al frente. */
@Composable
fun KeepTripRunning(trip: TripRepository) {
    // Tapado (el auto en grande, Llantas o estacionado encima), el GPS descansa.
    if (LocalHomeCovered.current) return
    LifecycleStartEffect(trip) {
        trip.acquire()
        onStopOrDispose { trip.release() }
    }
}

/** Cuál de los ocho puntos cardinales ([R.array.compass_points], desde el norte) es el rumbo [bearing] en grados. */
internal fun compassIndex(bearing: Float): Int = (((bearing % 360f + 360f) % 360f + 22.5f) / 45f).toInt() % 8

/** La distancia en la unidad del usuario, con un decimal ("12.3"); la unidad con [distanceUnit]. */
@Composable
fun distanceText(meters: Double, miles: Boolean): String = String.format(currentLocale(), "%.1f", meters.metersToDistance(miles))

@Composable
private fun distanceUnit(miles: Boolean) = stringResource(if (miles) R.string.unit_mi else R.string.unit_km)

/** La velocidad sin decimales en la unidad del usuario (sin dato, 0); la unidad con [speedUnit]. */
@Composable
fun speedText(kmh: Float?, miles: Boolean): String = String.format(currentLocale(), "%.0f", (kmh ?: 0f).toMiles(miles))

@Composable
fun speedUnit(miles: Boolean) = stringResource(if (miles) R.string.unit_mph else R.string.unit_kmh)

/** Lo del viaje ya en texto, para la pieza Viaje y la carretera. [distanceWithUnit] = las dos juntas en el orden del idioma. */
class TripStats(val distance: String, val unit: String, val distanceWithUnit: String, val time: String, val average: String?, val heading: String?)

/**
 * Los datos del viaje en la unidad del usuario. El tiempo es el que llevas en movimiento y el promedio no cuenta
 * lo parado; un viaje que ya terminó ([TRIP_IDLE_MS] sin avanzar) se ve en cero aunque no llegue ningún punto.
 */
@Composable
fun rememberTripStats(trip: TripRepository, miles: Boolean): TripStats {
    val leg = trip.leg
    // Con la pantalla dormida la espera no avanza: al volver a la app se revisa otra vez.
    val starts = rememberStarts()
    val over by produceState(leg.over(SystemClock.elapsedRealtime()), leg, starts) {
        while (true) {
            val now = SystemClock.elapsedRealtime()
            value = leg.over(now)
            if (value || leg.lastAt == 0L) break
            delay(leg.lastAt + TRIP_IDLE_MS - now + 1)
        }
    }
    val shown = if (over) TripLeg() else leg
    val minutes = (shown.movingMs / 60_000).toInt()
    val locale = currentLocale()
    val points = stringArrayResource(R.array.compass_points)
    // Un viaje que ya terminó no tiene rumbo (el último se quedaba aunque ya no avanzaras).
    val heading = trip.bearing?.takeUnless { over }?.let { points[compassIndex(it)] }
    val average = shown.averageKmh()?.let { stringResource(R.string.value_with_unit, speedText(it.toFloat(), miles), speedUnit(miles)) }
    val distance = distanceText(shown.meters, miles)
    val unit = distanceUnit(miles)
    return TripStats(
        distance = distance,
        unit = unit,
        distanceWithUnit = stringResource(R.string.value_with_unit, distance, unit),
        time = String.format(locale, "%d:%02d", minutes / 60, minutes % 60),
        average = average,
        heading = heading,
    )
}

/**
 * Pieza Viaje: solo lo del viaje (la velocidad ya está en el velocímetro y la carretera): la
 * distancia en grande, el tiempo, la velocidad promedio y el rumbo. Sin permiso, el botón para activarla.
 */
@Composable
fun TripTile(d: Dependencies, time: Boolean = true, average: Boolean = true, heading: Boolean = true) {
    val c = LocalColors.current
    val trip = d.trip
    val miles = d.prefs.units.miles
    val (granted, ask) = rememberGpsPermission(d)
    if (!granted) return ShortcutTile(Icons.location, stringResource(R.string.tile_trip), stringResource(R.string.tap_to_enable), ask)
    KeepTripRunning(trip)
    val stats = rememberTripStats(trip, miles)
    // Cada dato es una opción del widget (la distancia siempre va).
    val shownTime = stats.time.takeIf { time }
    val shownAverage = stats.average?.takeIf { average }
    val shownHeading = stats.heading?.takeIf { heading }
    val locale = currentLocale()
    Card(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(TilePadding)) {
            val width = maxWidth
            val height = maxHeight
            val tall = height > 120.dp
            // La distancia del tamaño que quepa (en un widget chico no se corta; en uno grande luce).
            val distanceSize = fitFont(stats.distance, width * 0.75f, height, if (tall) 0.3f else 0.5f, min = 24f, max = if (tall) 72f else 40f, extraChars = 1.5f)
            if (!tall) {
                // Una fila: la distancia y, a un lado, tiempo, promedio y rumbo en chico.
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    BigValue(stats.distance, stats.unit, distanceSize.toInt())
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val line = listOfNotNull(shownTime, shownHeading).joinToString(" · ")
                        if (line.isNotEmpty()) Label(line, Typography.body, maxLines = 1)
                        if (shownAverage != null) Label(shownAverage, Typography.small, c.textMuted, maxLines = 1)
                    }
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Label(stringResource(R.string.tile_trip).uppercase(locale), Typography.label, c.textMuted, maxLines = 1, modifier = Modifier.weight(1f))
                        if (shownHeading != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.navigate, c.accent, 14.dp)
                            Label(shownHeading, Typography.subtitle, c.accent, maxLines = 1)
                        }
                    }
                    BigValue(stats.distance, stats.unit, distanceSize.toInt())
                    // Tiempo y promedio bajo una línea fina, cada uno con su nombre arriba.
                    val stat = listOfNotNull(shownTime?.let { R.string.trip_time to it }, shownAverage?.let { R.string.trip_average to it })
                    if (stat.isNotEmpty() || width > 340.dp) Column {
                        Divider()
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(if (width > 340.dp) 28.dp else 16.dp)) {
                            for ((name, value) in stat) TripStat(stringResource(name), value)
                            Spacer(Modifier.weight(1f))
                            if (width > 340.dp) ResetTripButton(trip)
                        }
                    }
                }
            }
        }
    }
}

/**
 * En las opciones de Velocímetro y Viaje: sin la ubicación precisa (Android 12+ deja dar solo la aproximada) la
 * velocidad y el viaje se quedan en 0. Nota con «Permitir» y, ya negada, «Permisos» (los ajustes de LiteDash).
 */
@Composable
internal fun PreciseLocationNote(trip: TripRepository) {
    val context = LocalContext.current
    var denied by rememberSaveable { mutableStateOf(false) }
    // Se pide junto con la aproximada: en Android 12+ pedir solo la precisa se ignora.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        trip.refreshPermission()
        denied = !trip.precise
    }
    // Al volver de los ajustes de Android ya puede estar dada.
    LifecycleResumeEffect(trip) {
        trip.refreshPermission()
        onPauseOrDispose { }
    }
    if (trip.precise) return
    Label(stringResource(R.string.precise_location_missing), Typography.small, LocalColors.current.warning)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(stringResource(R.string.local_network_allow), { launcher.launch(LOCATION_PERMISSIONS) }, primary = true)
        if (denied) Button(stringResource(R.string.local_network_settings), { openAppSettings(context) })
    }
}

/** «Reiniciar» el viaje (en el widget ancho y en las opciones de Viaje y Carretera): no se deshace, se confirma. */
@Composable
internal fun ResetTripButton(trip: TripRepository) {
    Confirmed(
        stringResource(R.string.trip_reset), stringResource(R.string.trip_reset_question), stringResource(R.string.trip_reset),
        message = stringResource(R.string.trip_reset_message), action = trip::reset,
    )
}

/** Un número grande con su unidad en chico a un lado (como el reloj). */
@Composable
private fun BigValue(value: String, unit: String, size: Int) {
    Row(verticalAlignment = Alignment.Bottom) {
        Label(value, Typography.clock.copy(fontSize = size.sp), maxLines = 1)
        Label(unit, Typography.subtitle, LocalColors.current.textMuted, maxLines = 1, modifier = Modifier.padding(start = 6.dp, bottom = (size / 7).dp))
    }
}

/** Un dato del viaje: su nombre en chico arriba y el valor. */
@Composable
private fun TripStat(name: String, value: String) {
    Column {
        Label(name, Typography.small, LocalColors.current.textMuted, maxLines = 1)
        Label(value, Typography.subtitle, maxLines = 1)
    }
}

/** Sin escuchar más que esto, el último rumbo se olvida. */
private const val BEARING_STALE_MS = 5 * 60_000L

/** Avance mínimo para calcular el rumbo con dos lecturas (menos, el error del GPS lo voltea). */
private const val COURSE_METERS = 8f

/** Más imprecisión que esto (grados) y el rumbo del GPS no se usa. */
private const val MAX_BEARING_ERROR = 20f
