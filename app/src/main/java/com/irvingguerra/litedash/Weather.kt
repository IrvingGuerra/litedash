package com.irvingguerra.litedash

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.resume

/** Clima de ahora y de los próximos días (códigos WMO de Open-Meteo). */
data class Weather(val temperature: Int, val code: Int, val day: Boolean, val days: List<WeatherDay>, val fetchedAt: Long, val fahrenheit: Boolean = false)

data class WeatherDay(val date: String, val code: Int, val max: Int, val min: Int)

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
 * aproximada. Nada corre en segundo plano: se pide al volver al inicio si el
 * último tiene más de [STALE_MS], y se guarda para verlo al instante (y sin red).
 */
class WeatherRepository(private val context: Context, private val prefs: Preferences) {
    private val store = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    var weather by mutableStateOf(read())
        private set

    // Solo si el usuario lo activó: el permiso de ubicación también llega al activar
    // Viaje, y eso no debe encender el clima. Quien ya tenía clima guardado lo conserva.
    private var enabled by mutableStateOf(store.getBoolean(KEY_ON, store.contains(KEY)))

    /** En °F solo donde se usan (Estados Unidos y pocos más). */
    val fahrenheit get() = imperialUnits(prefs.language)

    /** Activado y con permiso de ubicación aproximada. */
    val active get() = enabled && context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun enable() {
        enabled = true
        store.edit { putBoolean(KEY_ON, true) }
    }

    suspend fun refreshIfStale(force: Boolean = false) {
        if (!active) return
        val last = weather
        if (!force && last != null && last.fahrenheit == fahrenheit && System.currentTimeMillis() - last.fetchedAt < STALE_MS) return
        val place = location() ?: return
        val fresh = runCatching { fetch(place.latitude, place.longitude) }.getOrNull() ?: return
        weather = fresh
        store.edit { putString(KEY, fresh.toJson().toString()) }
    }

    @SuppressLint("MissingPermission") // [active] revisa el permiso antes
    private suspend fun location(): Location? = withContext(Dispatchers.Main) {
        val manager = context.getSystemService(LocationManager::class.java)
        val last = manager.getProviders(true)
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (last != null || Build.VERSION.SDK_INT < 30) return@withContext last
        // "fused" existe desde Android 12.
        val fused = if (Build.VERSION.SDK_INT >= 31) listOf(LocationManager.FUSED_PROVIDER) else emptyList()
        val provider = (listOf(LocationManager.NETWORK_PROVIDER) + fused + LocationManager.GPS_PROVIDER)
            .firstOrNull { manager.isProviderEnabled(it) } ?: return@withContext null
        withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine { cont ->
                val cancel = CancellationSignal()
                cont.invokeOnCancellation { cancel.cancel() }
                manager.getCurrentLocation(provider, cancel, context.mainExecutor) { cont.resume(it) }
            }
        }
    }

    private suspend fun fetch(lat: Double, lon: Double): Weather = withContext(Dispatchers.IO) {
        val unit = if (fahrenheit) "&temperature_unit=fahrenheit" else ""
        val url = "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f".format(Locale.US, lat, lon) +
            "&current=temperature_2m,weather_code,is_day&daily=weather_code,temperature_2m_max,temperature_2m_min" +
            "&timezone=auto&forecast_days=4$unit"
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "LiteDash")
        }
        val json = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        val current = json.getJSONObject("current")
        val daily = json.getJSONObject("daily")
        val dates = daily.getJSONArray("time")
        Weather(
            temperature = Math.round(current.getDouble("temperature_2m")).toInt(),
            code = current.getInt("weather_code"),
            day = current.optInt("is_day", 1) == 1,
            days = (0 until dates.length()).map { i ->
                WeatherDay(
                    date = dates.getString(i),
                    code = daily.getJSONArray("weather_code").getInt(i),
                    max = Math.round(daily.getJSONArray("temperature_2m_max").getDouble(i)).toInt(),
                    min = Math.round(daily.getJSONArray("temperature_2m_min").getDouble(i)).toInt(),
                )
            },
            fetchedAt = System.currentTimeMillis(),
            fahrenheit = fahrenheit,
        )
    }

    private fun read(): Weather? = store.getString(KEY, null)?.let { text ->
        runCatching {
            val o = JSONObject(text)
            val days = o.getJSONArray("days")
            Weather(
                o.getInt("t"), o.getInt("c"), o.getBoolean("d"),
                (0 until days.length()).map { i ->
                    val x = days.getJSONObject(i)
                    WeatherDay(x.getString("date"), x.getInt("c"), x.getInt("max"), x.getInt("min"))
                },
                o.getLong("at"),
                o.optBoolean("f"),
            )
        }.getOrNull()
    }

    private fun Weather.toJson() = JSONObject()
        .put("t", temperature).put("c", code).put("d", day).put("at", fetchedAt).put("f", fahrenheit)
        .put("days", JSONArray(days.map { JSONObject().put("date", it.date).put("c", it.code).put("max", it.max).put("min", it.min) }))

    private companion object {
        const val STORE = "weather"
        const val KEY = "last"
        const val KEY_ON = "enabled"
        const val STALE_MS = 30 * 60_000L
    }
}
