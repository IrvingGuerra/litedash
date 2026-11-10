package com.irvingguerra.litedash

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.Colors
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.LocalPlainCard
import com.irvingguerra.litedash.core.ui.SwitchRow
import com.irvingguerra.litedash.core.ui.TilePadding
import com.irvingguerra.litedash.core.ui.Typography
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

fun iconOf(sky: Sky, day: Boolean) = when (sky) {
    Sky.CLEAR -> if (day) Icons.sun else Icons.moon
    Sky.PARTLY -> Icons.partlyCloudy
    Sky.CLOUDY -> Icons.cloud
    Sky.FOG -> Icons.fog
    Sky.RAIN -> Icons.rain
    Sky.STORM -> Icons.storm
    Sky.SNOW -> Icons.snow
}

fun textOf(sky: Sky) = when (sky) {
    Sky.CLEAR -> R.string.weather_clear
    Sky.PARTLY -> R.string.weather_partly
    Sky.CLOUDY -> R.string.weather_cloudy
    Sky.FOG -> R.string.weather_fog
    Sky.RAIN -> R.string.weather_rain
    Sky.STORM -> R.string.weather_storm
    Sky.SNOW -> R.string.weather_snow
}

/** Pide la ubicación aproximada y, si la dan, trae el clima al momento. */
@Composable
fun rememberEnableWeather(repo: WeatherRepository): () -> Unit {
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            repo.enable()
            scope.launch { repo.refreshIfStale(force = true) }
        }
    }
    return { launcher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }
}

/**
 * Mantiene el clima al día mientras el inicio se ve: al volver ([revision]) y cada
 * [WeatherRepository.STALE_MS] con LiteDash al frente. Apagado o en segundo plano, nada.
 */
@Composable
fun WeatherRefresh(repo: WeatherRepository, revision: Int) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val on = repo.enabled
    LaunchedEffect(repo, revision, on) {
        if (!on) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                repo.refreshIfStale()
                delay(WeatherRepository.STALE_MS)
            }
        }
    }
}

/** Ajustes → Inicio y apariencia: prender o apagar el clima (al prenderlo pide la ubicación aproximada). */
@Composable
fun WeatherSwitch(repo: WeatherRepository) {
    val enable = rememberEnableWeather(repo)
    SwitchRow(
        stringResource(R.string.weather_switch),
        repo.enabled,
        { on -> if (on) enable() else repo.disable() },
        stringResource(R.string.weather_switch_help),
    )
}

/** Ícono y temperatura, para la barra de arriba y el reloj. */
@Composable
fun WeatherChip(repo: WeatherRepository) {
    val w = repo.weather ?: return
    if (!repo.active) return
    val c = LocalColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(iconOf(skyOf(w.code), w.day), c.accent, 22.dp)
        Label("${w.temperature}°", Typography.subtitle, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
    }
}

/**
 * Pieza Clima: hoy en grande y, si cabe, los próximos días. Sin permiso es un
 * botón para activarlo (lo pide solo entonces).
 */
@Composable
fun WeatherTile(repo: WeatherRepository, days: Boolean = true, range: Boolean = true) {
    val c = LocalColors.current
    val enable = rememberEnableWeather(repo)
    val w = repo.weather
    if (!repo.active || w == null) {
        return ShortcutTile(Icons.sun, stringResource(R.string.tile_weather), stringResource(if (repo.active) R.string.weather_loading else R.string.tap_to_enable), enable)
    }
    val sky = skyOf(w.code)
    val today = w.days.firstOrNull()?.takeIf { range }
    val detail = stringResource(textOf(sky)) + (today?.let { " · ${it.max}° / ${it.min}°" } ?: "")
    val locale = currentLocale()
    // Los formatos se crean una vez por idioma, no en cada recomposición.
    val parse = remember(locale) { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    val weekday = remember(locale) { SimpleDateFormat("EEE", locale) }
    fun dayName(date: String) = runCatching { weekday.format(parse.parse(date)!!) }.getOrDefault("").replaceFirstChar { it.uppercase() }
    Card(Modifier.fillMaxSize()) {
        // Un tinte suave del color del cielo desde la esquina (como el clima de iOS); nada se anima.
        // (Sin tarjeta no: sería un rectángulo de color sobre el fondo.) Hasta la esquina contraria, en cualquier pantalla.
        if (!LocalPlainCard.current) Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(skyTint(sky, w.day, c), Color.Transparent), end = Offset.Infinite)))
        BoxWithConstraints(Modifier.fillMaxSize().padding(TilePadding)) {
            // El tamaño se lee aquí: dentro de las filas y columnas no se alcanza.
            val width = maxWidth
            val height = maxHeight
            val tall = height > 120.dp
            val nextDays = if (days) w.days.drop(1).take(3) else emptyList()
            if (!tall) {
                // Una fila: ícono, temperatura y cómo está; si es ancha, los próximos días.
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(iconOf(sky, w.day), c.accent, 34.dp)
                    Column(Modifier.weight(1f)) {
                        Label("${w.temperature}°", Typography.clock.copy(fontSize = fitFont("${w.temperature}°", width / 3, height, 0.5f, max = 40f).sp), maxLines = 1)
                        Label(detail, Typography.small, c.textMuted, maxLines = 1)
                    }
                    // Los días solo si caben a lo alto (si no, salían cortados).
                    if (width > 340.dp && height > 64.dp) for (d in nextDays) DayColumn(dayName(d.date), iconOf(skyOf(d.code), true), "${d.max}°")
                }
                return@BoxWithConstraints
            }
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Label(stringResource(R.string.tile_weather).uppercase(locale), Typography.label, c.textMuted, maxLines = 1)
                        val temp = "${w.temperature}°"
                        Label(temp, Typography.clock.copy(fontSize = fitFont(temp, width * 0.6f, height, 0.32f, min = 28f, max = 72f).sp), maxLines = 1)
                    }
                    Icon(iconOf(sky, w.day), c.accent, (height * 0.22f).coerceIn(32.dp, 56.dp))
                }
                Label(detail, Typography.small, c.textMuted, maxLines = 1)
                // Lo de hoy junto arriba; los próximos días, abajo.
                Spacer(Modifier.weight(1f))
                // Los próximos días en renglón, si hay lugar (a lo ancho cabe uno por cada ~80 dp).
                val fit = (width / 80.dp).toInt().coerceAtMost(nextDays.size)
                if (fit > 0 && height > 150.dp) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    for (d in nextDays.take(fit)) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Label(dayName(d.date), Typography.small, c.textMuted, maxLines = 1)
                        Icon(iconOf(skyOf(d.code), true), c.text, 18.dp)
                        Label("${d.max}°", Typography.small, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Un día del pronóstico en una fila: nombre, ícono y máxima, uno sobre otro. */
@Composable
private fun DayColumn(name: String, icon: ImageVector, max: String) {
    val c = LocalColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Label(name, Typography.small, c.textMuted, maxLines = 1)
        Icon(icon, c.text, 22.dp)
        Label(max, Typography.small, maxLines = 1)
    }
}

/** El tinte del cielo: ámbar con sol, azul con nubes, lluvia o noche despejada, gris con niebla; de noche, más tenue. */
private fun skyTint(sky: Sky, day: Boolean, c: Colors): Color {
    val base = when (sky) {
        Sky.CLEAR -> if (day) c.accent else SkyBlue
        Sky.PARTLY, Sky.CLOUDY, Sky.RAIN, Sky.STORM -> SkyBlue
        Sky.FOG, Sky.SNOW -> c.textMuted
    }
    return base.copy(alpha = if (day) 0.16f else 0.1f)
}

private val SkyBlue = Color(0xFF5B8CFF)
