package com.irvingguerra.litedash

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

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

    @android.annotation.SuppressLint("MissingPermission") // se revisa el permiso justo antes
    fun place(context: Context): Pair<Double, Double> {
        val granted = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            val manager = context.getSystemService(LocationManager::class.java)
            val last = runCatching {
                manager.getProviders(true).mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time }
            }.getOrNull()
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

/** Si es de noche; se vuelve a calcular justo al amanecer o al atardecer (no revisa cada rato). */
@Composable
fun rememberIsNight(): Boolean {
    val context = LocalContext.current
    val night by produceState(Daylight.state(context).first) {
        while (true) {
            val (now, wait) = Daylight.state(context)
            value = now
            delay(wait + 30_000)
        }
    }
    return night
}

/** "Buenos días/tardes/noches, [nombre]" según la hora; null sin nombre. */
@Composable
fun greeting(name: String?, hour: Int): String? {
    if (name.isNullOrBlank()) return null
    val id = when (hour) {
        in 5..11 -> R.string.greeting_morning
        in 12..18 -> R.string.greeting_afternoon
        else -> R.string.greeting_evening
    }
    return androidx.compose.ui.res.stringResource(id, name)
}
