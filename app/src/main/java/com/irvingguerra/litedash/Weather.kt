package com.irvingguerra.litedash

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.resume
import com.irvingguerra.litedash.core.ui.Https
import com.irvingguerra.litedash.core.ui.isRecent

/**
 * Clima tal como se ve: temperaturas redondeadas en la unidad elegida (códigos WMO de Open-Meteo).
 * [temperature] es la de ahora (null si la última descarga ya es vieja: no se presume como actual),
 * [today] lo pronosticado para hoy (por fecha) y [next] los días que siguen.
 */
data class Weather(val temperature: Int?, val code: Int, val day: Boolean, val today: WeatherDay?, val next: List<WeatherDay>)

data class WeatherDay(val date: String, val code: Int, val max: Int, val min: Int)

/**
 * Como llegó de Open-Meteo: °C con decimales, para que °F salga exacto (20.4 °C = 69 °F, no 68). [hours] es el
 * pronóstico por hora (para tener la temperatura de ahora sin red) y [lat]/[lon] dónde se pidió (null si es de
 * una versión que no lo guardaba).
 */
data class Forecast(
    val temperature: Double, val code: Int, val day: Boolean, val days: List<ForecastDay>, val fetchedAt: Long,
    val hours: ForecastHours? = null, val lat: Double? = null, val lon: Double? = null,
)

/** Pronóstico por hora: [list] seguidas a partir de [start] (ms, inicio de la primera hora). */
data class ForecastHours(val start: Long, val list: List<ForecastHour>)

data class ForecastHour(val temperature: Double, val code: Int, val day: Boolean)

/** Un día del pronóstico; [date] "aaaa-mm-dd" en la zona del lugar (como [Day.toString]). */
data class ForecastDay(val date: String, val code: Int, val max: Double, val min: Double)

/** Hasta esta edad la temperatura descargada se muestra como la de ahora; después, la de su hora o el pronóstico del día. */
const val CURRENT_MAX_AGE_MS = 3 * 3_600_000L

private const val HOUR_MS = 3_600_000L

/** Lo guardado sirve si se pidió a menos de esto de donde estás. */
private const val NEARBY_KM = 50.0

/** Índice de la hora de [now] en [Forecast.hours]; null si no hay o si [now] queda fuera de lo guardado. */
private fun Forecast.hourIndex(now: Long): Int? {
    val h = hours ?: return null
    if (now < h.start) return null
    val i = (now - h.start) / HOUR_MS
    return if (i < h.list.size) i.toInt() else null
}

/** El pronóstico de la hora de [now], si lo guardado la cubre. */
fun Forecast.hourAt(now: Long): ForecastHour? = hourIndex(now)?.let { hours!!.list[it] }

/** Si [lat]/[lon] quedan a menos de [NEARBY_KM] de donde se pidió [this] (sin dónde guardado, sí: como antes). */
fun Forecast.isNear(lat: Double, lon: Double): Boolean {
    val fromLat = this.lat ?: return true
    val fromLon = this.lon ?: return true
    // Aproximación plana: sobra a esta distancia.
    val dy = (lat - fromLat) * 111.2
    val dx = (lon - fromLon) * 111.2 * Math.cos(Math.toRadians((lat + fromLat) / 2))
    return dx * dx + dy * dy <= NEARBY_KM * NEARBY_KM
}

/** "aaaa-mm-ddThh:mm" en la hora local del lugar ([offsetSeconds] de UTC, como lo da Open-Meteo) a ms. */
fun localTimeToMillis(text: String, offsetSeconds: Int): Long = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
    clear()
    set(text.substring(0, 4).toInt(), text.substring(5, 7).toInt() - 1, text.substring(8, 10).toInt(), text.substring(11, 13).toInt(), text.substring(14, 16).toInt())
}.timeInMillis - offsetSeconds * 1000L

/**
 * [this] a la hora [now] (ms) en el día [today], en °F ([fahrenheit]) o °C, redondeado al final. Lo
 * pronosticado se alinea por fecha (sin red, lo guardado de hace días no se toma por hoy) y lo vencido
 * no se muestra: null si ya no queda nada vigente (ni la temperatura ni el día de hoy).
 */
fun Forecast.at(now: Long, today: Day, fahrenheit: Boolean): Weather? {
    val shown: (Double) -> Int = { c -> Math.round(c.toFahrenheit(fahrenheit)).toInt() }
    fun ForecastDay.inUnits() = WeatherDay(date, code, shown(max), shown(min))
    val date = today.toString()
    // Con el reloj de la pantalla atrasado (sin pila, hasta que hay red) no se sabe su edad: se muestra lo guardado.
    val current = now - fetchedAt < CURRENT_MAX_AGE_MS
    // Vencida la de ahora (p. ej. al arrancar sin red), la pronosticada para esta hora.
    val hour = if (current) null else hourAt(now)
    val todays = days.firstOrNull { it.date == date }?.inUnits()
    if (!current && hour == null && todays == null) return null
    // Las fechas "aaaa-mm-dd" se ordenan igual como texto.
    val next = days.filter { it.date > date }.take(3).map { it.inUnits() }
    return when {
        current -> Weather(shown(temperature), code, day, todays, next)
        hour != null -> Weather(shown(hour.temperature), hour.code, hour.day, todays, next)
        else -> Weather(null, todays!!.code, true, todays, next)
    }
}

/** Cuándo (ms) puede cambiar lo que se ve de [this] después de [now]: al vencer la temperatura, al cambiar de hora o a medianoche. */
fun Forecast.nextChange(now: Long): Long {
    val expires = fetchedAt + CURRENT_MAX_AGE_MS
    if (expires > now) return minOf(expires, nextMidnight(now))
    val hour = hourIndex(now) ?: return nextMidnight(now)
    return minOf(hours!!.start + (hour + 1) * HOUR_MS, nextMidnight(now))
}

/** La próxima medianoche (ms) después de [now], en la zona del equipo. */
fun nextMidnight(now: Long): Long = GregorianCalendar().apply {
    timeInMillis = now
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
    add(Calendar.DAY_OF_MONTH, 1)
}.timeInMillis

/**
 * La respuesta de Open-Meteo ya leída, bajada a [fetchedAt]. Las horas se toman seguidas mientras traigan dato (una sin
 * dato las corta). Cualquier formato inesperado sale como [JSONException] (una respuesta mala, no un cierre de la app).
 */
internal fun parseForecast(json: JSONObject, lat: Double, lon: Double, fetchedAt: Long): Forecast = try {
    readForecast(json, lat, lon, fetchedAt)
} catch (e: RuntimeException) {
    throw JSONException(e.toString())
}

private fun readForecast(json: JSONObject, lat: Double, lon: Double, fetchedAt: Long): Forecast {
    val current = json.getJSONObject("current")
    val daily = json.getJSONObject("daily")
    val dates = daily.getJSONArray("time")
    val hourly = json.getJSONObject("hourly")
    val times = hourly.getJSONArray("time")
    val hourTemps = hourly.getJSONArray("temperature_2m")
    val hourCodes = hourly.getJSONArray("weather_code")
    val hourDays = hourly.getJSONArray("is_day")
    val hourList = (0 until times.length()).asSequence().map { i ->
        if (hourTemps.isNull(i) || hourCodes.isNull(i)) null
        else ForecastHour(hourTemps.getDouble(i), hourCodes.getInt(i), hourDays.optInt(i, 1) == 1)
    }.takeWhile { it != null }.filterNotNull().toList()
    return Forecast(
        temperature = current.getDouble("temperature_2m"),
        code = current.getInt("weather_code"),
        day = current.optInt("is_day", 1) == 1,
        days = (0 until dates.length()).map { i ->
            ForecastDay(
                date = dates.getString(i),
                code = daily.getJSONArray("weather_code").getInt(i),
                max = daily.getJSONArray("temperature_2m_max").getDouble(i),
                min = daily.getJSONArray("temperature_2m_min").getDouble(i),
            )
        },
        fetchedAt = fetchedAt,
        // Las horas llegan seguidas en la hora local del lugar; se guarda cuándo empieza la primera.
        hours = if (hourList.isEmpty()) null else ForecastHours(localTimeToMillis(times.getString(0), json.optInt("utc_offset_seconds")), hourList),
        lat = lat,
        lon = lon,
    )
}

/**
 * °F, mph y millas solo donde se usan (Estados Unidos y pocos más). Con la app en
 * español siempre métrico, aunque el sistema esté en inglés de EE. UU.
 */
fun imperialUnits(language: String) = language != "es" &&
    Locale.getDefault().country in setOf("US", "LR", "MM")

/** Qué cielo es cada código WMO, para el ícono y el texto. */
enum class Sky { CLEAR, PARTLY, CLOUDY, FOG, RAIN, STORM, SNOW }

fun skyOf(code: Int) = when (code) {
    0 -> Sky.CLEAR
    1, 2 -> Sky.PARTLY
    3 -> Sky.CLOUDY
    45, 48 -> Sky.FOG
    in 51..67, in 80..82 -> Sky.RAIN
    in 71..77, 85, 86 -> Sky.SNOW
    in 95..99 -> Sky.STORM
    else -> Sky.CLOUDY
}

/**
 * Clima con Open-Meteo: gratis, sin clave ni anuncios, y solo con la ubicación
 * aproximada. Nada corre en segundo plano: mientras se ve el inicio se pide si el
 * último tiene más de [STALE_MS] ([WeatherRefresh]), y se guarda para verlo al
 * instante (y sin red). Apagado (Ajustes → Inicio y apariencia) no pide ubicación ni va a la red.
 */
class WeatherRepository(private val context: Context, private val prefs: Preferences) {
    private val store = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    // Lo guardado (≈ 96 horas en JSON) se lee la primera vez que alguien lo pide: con el clima apagado, nunca. El estado
    // nace aquí (no dentro de un lazy que se abre en plena composición, cuya instantánea podría no aplicarse); lo
    // perezoso es solo la lectura del disco.
    private val saved = mutableStateOf<Lazy<Forecast?>>(lazy(::read))

    /** Lo último que llegó de Open-Meteo, en °C con decimales (puede ser viejo: se ve con [weatherAt]). */
    var forecast: Forecast?
        get() = saved.value.value
        private set(value) {
            saved.value = lazyOf(value)
        }

    /** La última falla al traerlo (para el reporte técnico); null si la última vez salió bien. */
    private var failure: String? = null

    /** En °F (de Ajustes): es estado, quien lo lee se vuelve a dibujar si cambia. */
    val fahrenheit get() = prefs.units.fahrenheit

    /** El clima vigente a la hora [now] en la unidad elegida; null si no hay nada vigente. */
    fun weatherAt(now: Long): Weather? = forecast?.at(now, Day.today(), fahrenheit)

    // Solo si el usuario lo activó: el permiso de ubicación también llega al activar
    // Viaje, y eso no debe encender el clima. Quien ya tenía clima guardado lo conserva.
    /** Prendido por el usuario (aunque aún falte el permiso). */
    var enabled by mutableStateOf(store.getBoolean(KEY_ON, store.contains(KEY)))
        private set

    /** Activado y con permiso de ubicación aproximada. */
    val active get() = enabled && context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun enable() = turn(true)

    /** Apagado: se esconde y ya no pide ubicación ni va a la red (lo guardado se queda). */
    fun disable() = turn(false)

    private fun turn(on: Boolean) {
        enabled = on
        store.edit { putBoolean(KEY_ON, on) }
    }

    /**
     * Lo trae de nuevo si el último tiene más de [STALE_MS] (o con [force]). Devuelve false si lo intentó y
     * falló (sin ubicación o sin red): quien lo llama reintenta antes ([RETRY_MS]).
     */
    suspend fun refreshIfStale(force: Boolean = false, known: Location? = null): Boolean {
        if (!active) return true
        val last = forecast
        // Una edad negativa es el reloj atrasado: se trae de nuevo en cuanto se pueda.
        if (!force && last != null && isRecent(last.fetchedAt, System.currentTimeMillis(), STALE_MS)) return true
        val place = location(known)
        if (place == null) {
            failure = "sin ubicación"
            return false
        }
        // Lo guardado es de otro lugar: no se muestra (se queda en disco hasta que llegue el de aquí).
        if (last != null && !last.isNear(place.latitude, place.longitude)) forecast = null
        val fresh = try {
            fetch(place.latitude, place.longitude)
        } catch (e: IOException) {
            failure = "red: ${e.message}" // también un 4xx ("HTTP 404")
            return false
        } catch (e: JSONException) {
            failure = "respuesta: ${e.message}" // también un formato inesperado (ver [parse])
            return false
        }
        failure = null
        forecast = fresh
        withContext(Dispatchers.IO) { store.edit(commit = true) { putString(KEY, fresh.toJson().toString()) } }
        return true
    }

    /** Para el reporte técnico: si está prendido, de cuándo es lo guardado y la última falla. */
    fun describe(now: Long = System.currentTimeMillis()): String =
        "Clima: " + (if (!enabled) "apagado" else if (!active) "sin permiso" else forecast?.let { "de hace ${(now - it.fetchedAt) / 60_000} min" } ?: "sin datos") +
            (failure?.let { " · falló $it" } ?: "")

    /**
     * Dónde pedir el clima: [known] (la del GPS de LiteDash, si un widget lo tiene prendido) o la última conocida del
     * sistema si son recientes ([LOCATION_MAX_AGE_MS]); si no, UNA lectura al sistema por el proveedor más barato
     * prendido (red antes que GPS), con plazo y soltando el escucha; si no llega, la última conocida aunque sea vieja.
     * Sin esto, en Android 6–10 sin última conocida (nadie más pidió ubicación) el clima no llegaba nunca.
     */
    @SuppressLint("MissingPermission") // [active] revisa el permiso antes
    private suspend fun location(known: Location?): Location? {
        if (known != null && known.isRecent()) return known
        val manager = context.getSystemService(LocationManager::class.java) ?: return known
        // La última conocida y qué proveedores están prendidos se leen del sistema (binder): fuera del hilo principal.
        val (last, providers) = withContext(Dispatchers.IO) {
            val last = manager.newestLastKnown(manager.getProviders(true))
            // "fused" existe desde Android 12; el GPS solo con la ubicación precisa (con la aproximada no se puede).
            val fused = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) listOf(LocationManager.FUSED_PROVIDER) else emptyList()
            val gps = if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) listOf(LocationManager.GPS_PROVIDER) else emptyList()
            last to (listOf(LocationManager.NETWORK_PROVIDER) + fused + gps).filter {
                try {
                    manager.isProviderEnabled(it)
                } catch (e: IllegalArgumentException) {
                    false
                }
            }
        }
        if (last != null && last.isRecent()) return last
        // Uno tras otro hasta que alguno conteste (normalmente la red, al primer intento).
        for (provider in providers) currentLocation(manager, provider)?.let { return it }
        return last ?: known
    }

    /** Una sola lectura de [provider] (o null si no llega en [LOCATION_TIMEOUT_MS]); el escucha se suelta siempre. */
    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(manager: LocationManager, provider: String): Location? = withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            val cancel = CancellationSignal()
            cont.invokeOnCancellation { cancel.cancel() }
            try {
                // En Android 11+ es getCurrentLocation; antes, requestSingleUpdate con su propio plazo.
                LocationManagerCompat.getCurrentLocation(manager, provider, cancel, ContextCompat.getMainExecutor(context)) { l ->
                    if (cont.isActive) cont.resume(l?.takeUnless { it.latitude == 0.0 && it.longitude == 0.0 })
                }
            } catch (e: RuntimeException) {
                // Proveedor que no existe o permiso quitado entre medio: como si no contestara.
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    private suspend fun fetch(lat: Double, lon: Double): Forecast = withContext(Dispatchers.IO) {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f".format(Locale.US, lat, lon) +
            "&current=temperature_2m,weather_code,is_day&daily=weather_code,temperature_2m_max,temperature_2m_min" +
            "&hourly=temperature_2m,weather_code,is_day" +
            "&timezone=auto&forecast_days=4"
        // Https.get revisa el código (un 5xx se reintenta solo) y aquí se cierra la conexión al terminar.
        val connection = Https.get(context, url)
        val json = try {
            connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        } finally {
            connection.disconnect()
        }
        parseForecast(json, lat, lon, System.currentTimeMillis())
    }

    /** Lo guardado (en °C); si no se puede leer, como si no hubiera nada (se vuelve a pedir). */
    private fun read(): Forecast? = store.getString(KEY, null)?.let { text ->
        try {
            val o = JSONObject(text)
            val days = o.getJSONArray("days")
            Forecast(
                o.getDouble("t"), o.getInt("c"), o.getBoolean("d"),
                (0 until days.length()).map { i ->
                    val x = days.getJSONObject(i)
                    ForecastDay(x.getString("date"), x.getInt("c"), x.getDouble("max"), x.getDouble("min"))
                },
                o.getLong("at"),
                // De versiones anteriores no trae horas ni lugar.
                o.optJSONArray("ht")?.let { t ->
                    val c = o.getJSONArray("hc")
                    val d = o.getJSONArray("hd")
                    ForecastHours(o.getLong("hs"), (0 until t.length()).map { i -> ForecastHour(t.getDouble(i), c.getInt(i), d.getBoolean(i)) })
                },
                if (o.has("lat")) o.getDouble("lat") else null,
                if (o.has("lon")) o.getDouble("lon") else null,
            )
        } catch (e: JSONException) {
            null
        }
    }

    private fun Forecast.toJson() = JSONObject()
        .put("t", temperature).put("c", code).put("d", day).put("at", fetchedAt)
        .put("days", JSONArray(days.map { JSONObject().put("date", it.date).put("c", it.code).put("max", it.max).put("min", it.min) }))
        .putOpt("lat", lat).putOpt("lon", lon)
        .apply {
            hours?.let { h ->
                put("hs", h.start)
                put("ht", JSONArray(h.list.map { it.temperature }))
                put("hc", JSONArray(h.list.map { it.code }))
                put("hd", JSONArray(h.list.map { it.day }))
            }
        }

    companion object {
        private const val STORE = "weather"
        private const val KEY = "last"
        private const val KEY_ON = "enabled"

        /** Cada cuánto se renueva mientras se ve el inicio. */
        const val STALE_MS = 30 * 60_000L

        /** Tras una falla (sin red o sin ubicación), cuándo se vuelve a intentar. */
        const val RETRY_MS = 5 * 60_000L

        /** Una ubicación ya conocida sirve si tiene menos de esto; si no, se pide una al sistema (pudiste viajar). */
        private const val LOCATION_MAX_AGE_MS = 3 * 3_600_000L

        /** Cuánto se espera una lectura de un proveedor antes de probar el siguiente. */
        private const val LOCATION_TIMEOUT_MS = 15_000L

        /** Edad por el reloj de arranque (no el de la pantalla, que puede estar en 1970 o desfasado). */
        private fun Location.isRecent(): Boolean {
            val age = SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos
            return age in 0..LOCATION_MAX_AGE_MS * 1_000_000
        }
    }
}
