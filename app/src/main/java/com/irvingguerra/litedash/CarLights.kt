package com.irvingguerra.litedash

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography

/**
 * Las luces del auto como señal de noche. Android no tiene un aviso estándar de faros:
 * cada pantalla (FYT, SYU, SRNUBI…) lo escribe en un ajuste del sistema con su propio
 * nombre y valor. LiteDash lo aprende: toma una foto de los ajustes con las luces
 * apagadas, otra con las luces prendidas, y el que cambió es el de los faros. Después
 * solo escucha ese ajuste (sin permisos: los ajustes del sistema se leen libremente).
 */
data class LightsKey(val table: String, val name: String) {
    val stored get() = "$table/$name"

    fun uri(): Uri = when (table) {
        "global" -> Settings.Global.getUriFor(name)
        "secure" -> Settings.Secure.getUriFor(name)
        else -> Settings.System.getUriFor(name)
    }

    fun read(context: Context): String? = runCatching {
        val r = context.contentResolver
        when (table) {
            "global" -> Settings.Global.getString(r, name)
            "secure" -> Settings.Secure.getString(r, name)
            else -> Settings.System.getString(r, name)
        }
    }.getOrNull()

    companion object {
        fun fromStored(v: String?): LightsKey? {
            val i = v?.indexOf('/') ?: return null
            if (i <= 0) return null
            return LightsKey(v.substring(0, i), v.substring(i + 1))
        }
    }
}

/** Nombres que suelen tener los ajustes de luces en las pantallas de auto. */
private val LIGHT_WORDS = Regex("illum|headl|lamp|light|dimm|night|backlight|ill_|acc_", RegexOption.IGNORE_CASE)

/** Todos los ajustes de la pantalla (las tres tablas) con su valor, para comparar dos momentos. */
private fun snapshotSettings(context: Context): Map<String, String> {
    val tables = listOf("system" to Settings.System.CONTENT_URI, "global" to Settings.Global.CONTENT_URI, "secure" to Settings.Secure.CONTENT_URI)
    val all = HashMap<String, String>()
    for ((table, uri) in tables) {
        runCatching {
            context.contentResolver.query(uri, arrayOf("name", "value"), null, null, null)?.use { c ->
                while (c.moveToNext()) c.getString(0)?.let { all["$table/$it"] = c.getString(1) ?: "" }
            }
        }
    }
    return all
}

/**
 * Si es de noche según las luces aprendidas; null si todavía no se aprendieron. Escucha
 * el ajuste solo mientras algo lo usa (el tema o el brillo) y sin revisar cada rato.
 */
@Composable
fun rememberLightsNight(prefs: Preferences): Boolean? {
    val context = LocalContext.current
    val key = remember(prefs.lightsKey) { LightsKey.fromStored(prefs.lightsKey) } ?: return null
    val on = prefs.lightsOnValue ?: return null
    var night by remember(key, on) { mutableStateOf(key.read(context) == on) }
    DisposableEffect(key, on) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                night = key.read(context) == on
            }
        }
        context.contentResolver.registerContentObserver(key.uri(), false, observer)
        night = key.read(context) == on
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return night
}

/** Pasos de "Aprender luces": apagadas → prendidas → elegir si hubo varios cambios. */
private sealed interface LearnStep {
    data object Idle : LearnStep
    data class LightsOff(val before: Map<String, String>) : LearnStep
    data class Choose(val candidates: List<Pair<String, String>>) : LearnStep
    data object Nothing : LearnStep
}

/** Ajustes → Inicio y apariencia → ¿Cuándo es de noche? → Luces del auto: aprender y ver el estado. */
@Composable
fun LightsSettings(prefs: Preferences) {
    val c = LocalColors.current
    val context = LocalContext.current
    var step by remember { mutableStateOf<LearnStep>(LearnStep.Idle) }
    val key = LightsKey.fromStored(prefs.lightsKey)
    val night = rememberLightsNight(prefs)
    fun save(stored: String, onValue: String) {
        prefs.lightsKey = stored
        prefs.lightsOnValue = onValue
        step = LearnStep.Idle
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (val s = step) {
            LearnStep.Idle -> {
                if (key != null) {
                    Label(
                        stringResource(if (night == true) R.string.lights_now_on else R.string.lights_now_off, key.stored),
                        Typography.body, if (night == true) c.accent else c.textMuted,
                    )
                } else {
                    Label(stringResource(R.string.lights_not_learned), Typography.small, c.textMuted)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(stringResource(R.string.lights_learn), { step = LearnStep.LightsOff(snapshotSettings(context)) }, primary = key == null)
                    if (key != null) Button(stringResource(R.string.lights_forget), { prefs.lightsKey = null; prefs.lightsOnValue = null })
                }
            }
            is LearnStep.LightsOff -> {
                Label(stringResource(R.string.lights_step_on), Typography.body, c.accent)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(stringResource(R.string.lights_done), {
                        val after = snapshotSettings(context)
                        // Lo que cambió entre una foto y otra; si varios, primero los que suenan a luces.
                        val changed = after.filter { (k, v) -> s.before[k] != v }.toList()
                            .sortedByDescending { LIGHT_WORDS.containsMatchIn(it.first) }
                        step = when {
                            changed.isEmpty() -> LearnStep.Nothing
                            changed.size == 1 || LIGHT_WORDS.containsMatchIn(changed[0].first) -> { save(changed[0].first, changed[0].second); LearnStep.Idle }
                            else -> LearnStep.Choose(changed.take(8))
                        }
                    }, primary = true)
                    Button(stringResource(R.string.cancel), { step = LearnStep.Idle })
                }
            }
            is LearnStep.Choose -> {
                Label(stringResource(R.string.lights_choose), Typography.body)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for ((k, v) in s.candidates) Button("$k = $v", { save(k, v) })
                    Button(stringResource(R.string.cancel), { step = LearnStep.Idle })
                }
            }
            LearnStep.Nothing -> {
                Label(stringResource(R.string.lights_nothing), Typography.body, c.danger)
                Button(stringResource(R.string.lights_retry), { step = LearnStep.LightsOff(snapshotSettings(context)) })
            }
        }
    }
}
