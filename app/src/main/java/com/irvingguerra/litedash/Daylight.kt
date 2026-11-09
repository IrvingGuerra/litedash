package com.irvingguerra.litedash

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Amanecer y atardecer de hoy con la ecuación de la NOAA (sin internet). Con la
 * última ubicación conocida si ya hay permiso; si no, la latitud media de México
 * y la longitud que corresponde a la zona horaria (de sobra para el tema).
 */
object Daylight {
    private const val DEFAULT_LATITUDE = 20.0

    /** Minutos del día (hora local) en que sale y se mete el sol. */
    fun sunTimes(day: Calendar, latitude: Double, longitude: Double): Pair<Double, Double> {
        val gamma = 2 * Math.PI / 365 * (day.get(Calendar.DAY_OF_YEAR) - 1)
        val equation = 229.18 * (0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma) -
            0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma))
        val declination = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) -
            0.006758 * cos(2 * gamma) + 0.000907 * sin(2 * gamma) -
            0.002697 * cos(3 * gamma) + 0.00148 * sin(3 * gamma)
        val lat = Math.toRadians(latitude)
        val cosHour = (cos(Math.toRadians(90.833)) / (cos(lat) * cos(declination)) - tan(lat) * tan(declination)).coerceIn(-1.0, 1.0)
        val hour = Math.toDegrees(acos(cosHour))
        val offset = day.timeZone.getOffset(day.timeInMillis) / 60_000.0
        val sunrise = 720 - 4 * (longitude + hour) - equation + offset
        val sunset = 720 - 4 * (longitude - hour) - equation + offset
        return sunrise to sunset
    }

    /** Con la última ubicación conocida; sin ella, la latitud media de México y la longitud de la zona horaria. */
    fun place(context: Context): Pair<Double, Double> {
        val granted = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            val manager = context.getSystemService(LocationManager::class.java)
            val last = manager?.newestLastKnown(runCatching { manager.getProviders(true) }.getOrDefault(emptyList()))
            if (last != null) return last.latitude to last.longitude
        }
        return DEFAULT_LATITUDE to TimeZone.getDefault().rawOffset / 3_600_000.0 * 15
    }

    /** Si es de noche ahora y en cuántos milisegundos cambia (amanecer o atardecer). */
    fun state(context: Context, now: Calendar = Calendar.getInstance()): Pair<Boolean, Long> {
        val (lat, lon) = place(context)
        val (sunrise, sunset) = sunTimes(now, lat, lon)
        val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE) + now.get(Calendar.SECOND) / 60.0
        val night = minute < sunrise || minute >= sunset
        val next = when {
            minute < sunrise -> sunrise - minute
            minute < sunset -> sunset - minute
            else -> 24 * 60 - minute + sunrise
        }
        return night to (next * 60_000).toLong().coerceAtLeast(60_000)
    }
}

/**
 * Si es de noche según [source]; sin sensor de luz (o uno que no sirve), por la hora del
 * sol. El sensor solo se escucha si algo usa la noche ([needed]: tema Día y noche o brillo).
 */
@Composable
fun rememberIsNight(prefs: Preferences, needed: Boolean): Boolean {
    // Sin uso, ni el sol se calcula (ni se lee la ubicación).
    if (!needed) return false
    val sun = rememberSunNight()
    return when (prefs.nightSource) {
        NightSource.SUN -> sun
        NightSource.SENSOR -> rememberSensorNight(sun) ?: sun
        NightSource.LIGHTS -> rememberLightsNight(prefs) ?: sun
    }
}

/** Si en este tiempo el sensor no dio ni un valor distinto, está de adorno (pasa en varias pantallas). */
private const val STUCK_SENSOR_MS = 10 * 60_000L

/**
 * Noche por el sensor de luz: cambia solo si la luz se mantiene un rato (al pasar bajo
 * un puente no). Arranca con [initial] (la hora del sol). null = la pantalla no tiene sensor.
 */
@Composable
private fun rememberSensorNight(initial: Boolean): Boolean? {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(SensorManager::class.java) }
    val sensor = remember { manager?.getDefaultSensor(Sensor.TYPE_LIGHT) } ?: return null
    var night by remember { mutableStateOf(initial) }
    var stuck by remember { mutableStateOf(false) }
    DisposableEffect(sensor) {
        val light = LightHysteresis(night)
        val handler = Handler(Looper.getMainLooper())
        var lux = 0f
        // El sensor solo avisa cuando la luz cambia: si se queda igual, esta revisión
        // (una sola, al cumplirse el tiempo) confirma el cambio.
        val confirm = object : Runnable {
            override fun run() {
                night = light.update(lux, SystemClock.elapsedRealtime())
                light.waitMs(SystemClock.elapsedRealtime())?.let { handler.postDelayed(this, it) }
            }
        }
        var first: Float? = null
        var varies = false
        val stuckCheck = Runnable { if (!varies) stuck = true }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                lux = e.values[0]
                if (first == null) first = lux else if (lux != first) {
                    // Varió: sirve, aunque antes se haya dado por pegado.
                    varies = true
                    stuck = false
                }
                handler.removeCallbacks(confirm)
                confirm.run()
            }
            override fun onAccuracyChanged(s: Sensor, accuracy: Int) = Unit
        }
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        handler.postDelayed(stuckCheck, STUCK_SENSOR_MS)
        onDispose {
            manager.unregisterListener(listener)
            handler.removeCallbacks(confirm)
            handler.removeCallbacks(stuckCheck)
        }
    }
    return if (stuck) null else night
}

/**
 * Día o noche con la luz que mide el sensor: oscuro abajo de [DARK_LUX], claro arriba
 * de [BRIGHT_LUX] (entre los dos no cambia), y solo si dura [HOLD_MS].
 */
class LightHysteresis(private var night: Boolean) {
    private var since = 0L
    private var pending: Boolean? = null

    fun update(lux: Float, nowMs: Long): Boolean {
        val wants = when {
            lux < DARK_LUX -> true
            lux > BRIGHT_LUX -> false
            else -> night
        }
        if (wants == night) {
            pending = null
        } else if (pending != wants) {
            pending = wants
            since = nowMs
        } else if (nowMs - since >= HOLD_MS) {
            night = wants
            pending = null
        }
        return night
    }

    /** Cuánto falta para confirmar un cambio pendiente (null = no hay ninguno). */
    fun waitMs(nowMs: Long): Long? = pending?.let { (HOLD_MS - (nowMs - since)).coerceAtLeast(1) }

    companion object {
        const val DARK_LUX = 15f
        const val BRIGHT_LUX = 60f
        const val HOLD_MS = 15_000L
    }
}

/**
 * Si es de noche; se vuelve a calcular justo al amanecer o al atardecer (no revisa cada rato) y
 * al volver la app al frente (con la pantalla dormida la espera no avanza).
 */
@Composable
private fun rememberSunNight(): Boolean {
    val context = LocalContext.current
    // El valor inicial se calcula una vez aquí mismo (una lectura corta de la última ubicación): si
    // esperara a otro hilo, el tema podría brincar de claro a oscuro al abrir.
    val first = remember { Daylight.state(context) }
    val starts = rememberStarts()
    val night by produceState(first.first, starts) {
        // La primera vez ya se calculó; al volver al frente, otra vez (fuera del hilo principal: lee la ubicación).
        var (now, wait) = if (starts <= 1) first else withContext(Dispatchers.IO) { Daylight.state(context) }
        while (true) {
            value = now
            delay(wait + 30_000)
            val s = withContext(Dispatchers.IO) { Daylight.state(context) }
            now = s.first
            wait = s.second
        }
    }
    return night
}

/** "Buenos días/tardes/noches, [name]" según la hora; null sin nombre. */
@Composable
fun greeting(name: String?): String? {
    if (name.isNullOrBlank()) return null
    val id = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> R.string.greeting_morning
        in 12..18 -> R.string.greeting_afternoon
        else -> R.string.greeting_evening
    }
    return stringResource(id, name)
}
