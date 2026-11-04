package com.irvingguerra.litedash

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.location.LocationListenerCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.core.vehicle.Signal
import com.irvingguerra.litedash.core.vehicle.Source
import com.irvingguerra.litedash.core.vehicle.VehicleBus
import kotlinx.coroutines.delay

/**
 * Velocidad, rumbo y distancia del viaje con el GPS. Solo escucha mientras algo
 * lo pide ([acquire]/[release]) y la app está al frente: así no gasta batería
 * con la pantalla en otra app ni en segundo plano.
 */
class TripRepository(private val context: Context, private val bus: VehicleBus) {
    /** km/h; null = sin señal todavía. */
    var speedKmh by mutableStateOf<Float?>(null)
        private set
    var bearing by mutableStateOf<Float?>(null)
        private set
    var meters by mutableDoubleStateOf(0.0)
        private set
    /** Desde el primer punto en movimiento (0 = todavía no arranca): no cuenta lo que estuvo parado. */
    var startedAt by mutableLongStateOf(0L)
        private set

    /** En movimiento (más de 8 km/h): el modo estacionado espera a que se detenga. */
    val moving get() = (speedKmh ?: 0f) > 8f

    private var users = 0
    private var last: Location? = null
    private val manager by lazy { context.getSystemService(LocationManager::class.java) }
    // Compat: en Android 6–10 LocationListener tiene más métodos abstractos que el lambda no
    // implementa (AbstractMethodError al perder el GPS o apagar la ubicación).
    private val listener = LocationListenerCompat { update(it) }

    fun hasPermission() = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // se revisa con hasPermission()
    fun acquire() {
        users++
        if (users == 1 && hasPermission()) {
            runCatching { manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, listener, Looper.getMainLooper()) }
        }
    }

    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) {
            runCatching { manager.removeUpdates(listener) }
            speedKmh = null
            bus.publish(Signal.SPEED, null, Source.GPS)
        }
    }

    fun reset() {
        meters = 0.0
        startedAt = 0L
        last = null
    }

    private fun update(l: Location) {
        speedKmh = if (l.hasSpeed()) l.speed * 3.6f else null
        bus.publish(Signal.SPEED, speedKmh, Source.GPS)
        if (l.hasBearing() && (speedKmh ?: 0f) > 3f) bearing = l.bearing
        val previous = last
        // Solo puntos buenos y en movimiento: parado, el GPS "camina" solo unos metros.
        if (l.accuracy <= 30f && (speedKmh ?: 0f) > 3f) {
            if (startedAt == 0L) startedAt = System.currentTimeMillis()
            if (previous != null) meters += previous.distanceTo(l)
            last = l
        }
    }
}

/** Escucha el GPS mientras la pieza se ve y la app está al frente. */
@Composable
fun KeepTripRunning(trip: TripRepository) {
    LifecycleStartEffect(trip) {
        trip.acquire()
        onStopOrDispose { trip.release() }
    }
}

private val COMPASS = arrayOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
private val COMPASS_EN = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

/** Pieza Viaje: velocidad grande, rumbo, distancia y tiempo; sin permiso, el botón para activarla. */
@Composable
fun TripTile(trip: TripRepository, miles: Boolean) {
    val c = LocalColors.current
    var granted by remember { mutableStateOf(trip.hasPermission()) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    if (!granted) {
        return ShortcutTile(Icons.speed, stringResource(R.string.tile_trip), stringResource(R.string.tap_to_enable)) {
            ask.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    KeepTripRunning(trip)
    val speed = trip.speedKmh?.toMiles(miles)
    val distance = trip.meters.metersToDistance(miles)
    // El tiempo avanza solo, aunque el GPS no mande nada (una vez por minuto).
    val minutes by produceState(0, trip.startedAt) {
        while (true) {
            value = if (trip.startedAt == 0L) 0 else ((System.currentTimeMillis() - trip.startedAt) / 60_000).toInt()
            delay(60_000 - System.currentTimeMillis() % 60_000)
        }
    }
    val locale = currentLocale()
    val spanish = locale.language == "es"
    val heading = trip.bearing?.let { (if (spanish) COMPASS else COMPASS_EN)[((it + 22.5f) / 45f).toInt() % 8] }
    Card(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.CenterStart) {
            val big = maxHeight > 120.dp
            val wide = maxWidth > 340.dp
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (wide) 18.dp else 10.dp)) {
                Column {
                    Label(String.format(locale, "%.0f", speed ?: 0f), Typography.clock.copy(fontSize = if (big) 56.sp else 34.sp), maxLines = 1)
                    Label(stringResource(if (miles) R.string.unit_mph else R.string.unit_kmh), Typography.small, c.textMuted, maxLines = 1)
                }
                // Angosta y bajita: el rumbo deja su renglón para que quepan distancia y tiempo.
                val compact = !wide && !big
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (!compact) Label(heading ?: "", Typography.title, c.accent, maxLines = 1)
                    val km = String.format(locale, "%.1f %s", distance, if (miles) "mi" else "km")
                    val time = String.format(locale, "%d:%02d", minutes / 60, minutes % 60)
                    // En angosto no caben juntos en un renglón: la distancia se cortaba.
                    if (wide) {
                        Label("$km · $time", Typography.body, maxLines = 1)
                    } else {
                        Label(km, Typography.body, maxLines = 1)
                        Label(if (compact && heading != null) "$time · $heading" else time, Typography.small, c.textMuted, maxLines = 1)
                    }
                }
                if (big && wide) Button(stringResource(R.string.trip_reset), { trip.reset() })
            }
        }
    }
}
