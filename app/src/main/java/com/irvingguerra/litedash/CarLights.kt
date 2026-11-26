package com.irvingguerra.litedash

import android.content.Context
import android.database.ContentObserver
import android.hardware.Sensor
import android.hardware.SensorManager
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    // Desde Android 12 algunas claves de secure/global no se dejan leer (SecurityException): como si no existiera.
    fun read(context: Context): String? = try {
        val r = context.contentResolver
        when (table) {
            "global" -> Settings.Global.getString(r, name)
            "secure" -> Settings.Secure.getString(r, name)
            else -> Settings.System.getString(r, name)
        }
    } catch (_: SecurityException) {
        null
    }

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

/**
 * Ajustes que cambian solos y nunca son los faros: brillo (LiteDash mismo lo escribe), alarmas,
 * marcas de tiempo, contadores de wifi/bluetooth, el LED de avisos y el autollenado ("autofill" trae "ill_").
 */
private val NOISE_WORDS = Regex(
    "^screen_brightness|^next_alarm|timestamp|^wifi_|^bluetooth_|^ble_|notification_light_pulse|^autofill",
    RegexOption.IGNORE_CASE,
)

private fun isNoise(name: String) = NOISE_WORDS.containsMatchIn(name)

/**
 * El brillo de la pantalla. En varias pantallas de auto es la ÚNICA señal de los faros (la pantalla se atenúa sola al
 * prenderlos), así que sí se ofrece al aprender, pero nunca se guarda solo: se elige a mano, y solo si no lo mueve el
 * brillo automático de Android ni LiteDash mismo ([brightnessCandidate]).
 */
internal const val BRIGHTNESS_KEY = "system/screen_brightness"
private const val BRIGHTNESS_MODE_KEY = "system/screen_brightness_mode"

/**
 * El brillo como candidato a ser los faros: cambió entre las dos fotos ([before], [after]), no está en automático y
 * LiteDash no lo escribe ([ownBrightness]). Se guarda como un tope ("max:N" si con los faros baja, "min:N" si sube) a
 * media distancia entre los dos valores: el número exacto cambia en cuanto alguien mueve el brillo un poco.
 */
internal fun brightnessCandidate(before: Map<String, String>, after: Map<String, String>, ownBrightness: Boolean): Pair<String, String>? {
    if (ownBrightness || before[BRIGHTNESS_MODE_KEY] == "1" || after[BRIGHTNESS_MODE_KEY] == "1") return null
    val off = before[BRIGHTNESS_KEY]?.toIntOrNull() ?: return null
    val on = after[BRIGHTNESS_KEY]?.toIntOrNull() ?: return null
    // Un cambio chico no son los faros (alguien movió el brillo): la pantalla lo baja o lo sube de golpe.
    if (kotlin.math.abs(off - on) < BRIGHTNESS_STEP) return null
    return BRIGHTNESS_KEY to (if (on < off) "max:" else "min:") + (on + off) / 2
}

/** Lo menos que cambia el brillo (de 0 a 255) para tomarlo por los faros. */
private const val BRIGHTNESS_STEP = 10

/**
 * ¿Los faros se aprendieron por el brillo con tope ([key] y [on] guardados)? Entonces LiteDash no escribe el brillo del
 * sistema ([ApplyBrightness]): apagaría su propia señal. Lo aprendido antes con valor exacto no cuenta: sigue como estaba.
 */
internal fun brightnessIsTheSignal(key: String?, on: String?): Boolean =
    key == BRIGHTNESS_KEY && on != null && (on.startsWith("max:") || on.startsWith("min:"))

/** ¿El valor de ahora ([current]) es el de los faros prendidos ([on], lo guardado al aprender: exacto, "max:N" o "min:N")? */
internal fun lightsAreOn(on: String, current: String?): Boolean {
    val limit = on.substringAfter(':', "").toIntOrNull()
    val now = current?.toIntOrNull()
    return when {
        limit != null && on.startsWith("max:") -> now != null && now <= limit
        limit != null && on.startsWith("min:") -> now != null && now >= limit
        else -> current == on
    }
}

/** Si el ajuste [name] (sin la tabla) suena a los faros. */
internal fun soundsLikeLights(name: String) = LIGHT_WORDS.containsMatchIn(name) && !isNoise(name)

/**
 * Todos los ajustes de la pantalla (las tres tablas, "tabla/nombre" en ese orden) con su valor, para
 * comparar dos momentos. Cientos de filas por IPC: fuera del hilo principal.
 */
internal fun snapshotSettings(context: Context): Map<String, String> {
    val tables = listOf("system" to Settings.System.CONTENT_URI, "global" to Settings.Global.CONTENT_URI, "secure" to Settings.Secure.CONTENT_URI)
    val all = LinkedHashMap<String, String>()
    for ((table, uri) in tables) {
        // Una tabla que Android no deja leer se salta (el reporte cuenta cuántas filas dio cada una).
        try {
            context.contentResolver.query(uri, arrayOf("name", "value"), null, null, null)?.use { c ->
                while (c.moveToNext()) c.getString(0)?.let { all["$table/$it"] = c.getString(1) ?: "" }
            }
        } catch (_: SecurityException) {
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
    var night by remember(key, on) { mutableStateOf(lightsAreOn(on, key.read(context))) }
    DisposableEffect(key, on) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                night = lightsAreOn(on, key.read(context))
            }
        }
        context.contentResolver.registerContentObserver(key.uri(), false, observer)
        night = lightsAreOn(on, key.read(context))
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    // Algunas ROM escriben el ajuste sin avisar al observador: se vuelve a leer al regresar al frente.
    LifecycleStartEffect(key, on) {
        night = lightsAreOn(on, key.read(context))
        onStopOrDispose { }
    }
    return night
}

/** Pasos de "Aprender luces": apagadas → prendidas → elegir si hubo varios cambios. */
internal sealed interface LearnStep {
    data object Idle : LearnStep
    data class LightsOff(val before: Map<String, String>) : LearnStep
    data class Choose(val candidates: List<Pair<String, String>>) : LearnStep
    data object Nothing : LearnStep
}

/**
 * El paso como lista de textos (lo que cabe en el estado guardado): el tipo y luego clave, valor, clave, valor…
 * Así sobrevive si la pantalla recrea la actividad al prender los faros (algunas cambian de tema o de modo).
 */
internal fun LearnStep.toSaved(): ArrayList<String> {
    val pairs = when (this) {
        is LearnStep.LightsOff -> before.toList()
        is LearnStep.Choose -> candidates
        else -> emptyList()
    }
    val kind = when (this) {
        LearnStep.Idle -> "idle"
        is LearnStep.LightsOff -> "off"
        is LearnStep.Choose -> "choose"
        LearnStep.Nothing -> "nothing"
    }
    return ArrayList<String>(1 + pairs.size * 2).apply {
        add(kind)
        for ((k, v) in pairs) {
            add(k)
            add(v)
        }
    }
}

/** Lo guardado por [toSaved]; algo que no se entiende regresa al inicio. */
internal fun learnStepFrom(saved: List<String>): LearnStep {
    val pairs = saved.drop(1).chunked(2).filter { it.size == 2 }.map { it[0] to it[1] }
    return when (saved.firstOrNull()) {
        "off" -> LearnStep.LightsOff(pairs.toMap())
        "choose" -> LearnStep.Choose(pairs)
        "nothing" -> LearnStep.Nothing
        else -> LearnStep.Idle
    }
}

private val LearnStepSaver = Saver<LearnStep, ArrayList<String>>({ it.toSaved() }, { learnStepFrom(it) })

/**
 * Cómo se ve cada candidato en el paso de elegir: solo el nombre del ajuste, salvo que otro de otra tabla se llame igual
 * (ahí "tabla/nombre" para distinguirlos).
 */
internal fun candidateLabels(candidates: List<Pair<String, String>>): List<String> {
    val tables = candidates.groupBy({ it.first.substringAfter('/') }, { it.first.substringBefore('/') })
    return candidates.map { (k, v) ->
        val name = k.substringAfter('/')
        "${if (tables.getValue(name).toSet().size > 1) k else name} = $v"
    }
}

/** El último intento de aprender las luces (para el reporte, que lo lee en IO); solo en memoria del proceso. */
@Volatile
private var lastLearn: String? = null

/** Ajustes → Inicio y apariencia → ¿Cuándo es de noche? → Luces del auto: aprender y ver el estado. */
@Composable
fun LightsSettings(prefs: Preferences) {
    val c = LocalColors.current
    val context = LocalContext.current
    var step by rememberSaveable(stateSaver = LearnStepSaver) { mutableStateOf<LearnStep>(LearnStep.Idle) }
    val scope = rememberCoroutineScope()
    val key = LightsKey.fromStored(prefs.lightsKey)
    // La foto de los ajustes, fuera del hilo principal; si mientras tanto se cambió de paso (Cancelar), se ignora.
    fun withSnapshot(next: (Map<String, String>) -> LearnStep) {
        val from = step
        scope.launch {
            val snapshot = withContext(Dispatchers.IO) { snapshotSettings(context) }
            if (step === from) step = next(snapshot)
        }
    }
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
                        stringResource(if (night == true) R.string.lights_now_on else R.string.lights_now_off),
                        Typography.body, if (night == true) c.accent else c.textMuted,
                    )
                } else {
                    Label(stringResource(R.string.lights_not_learned), Typography.small, c.textMuted)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(stringResource(R.string.lights_learn), { withSnapshot { LearnStep.LightsOff(it) } }, primary = key == null)
                    if (key != null) Button(stringResource(R.string.lights_forget), { prefs.lightsKey = null; prefs.lightsOnValue = null })
                    else UseSunButton(prefs)
                }
            }
            is LearnStep.LightsOff -> {
                Label(stringResource(R.string.lights_step_on), Typography.body, c.accent)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(stringResource(R.string.lights_done), {
                        withSnapshot { after ->
                            // Lo que cambió entre una foto y otra (sin el ruido), primero lo que suena a luces;
                            // solo se guarda sin preguntar si suena a luces.
                            val changed = after.filter { (k, v) -> s.before[k] != v && !isNoise(k.substringAfter('/')) }.toList()
                                .sortedByDescending { soundsLikeLights(it.first.substringAfter('/')) }
                            // El brillo, al final y solo para elegirlo a mano: en muchas pantallas es lo único que cambia.
                            val own = prefs.brightnessBySun && prefs.brightnessAllApps && Settings.System.canWrite(context)
                            val brightness = brightnessCandidate(s.before, after, own)
                            when {
                                changed.isEmpty() && brightness != null -> {
                                    lastLearn = "solo el brillo"
                                    LearnStep.Choose(listOf(brightness))
                                }
                                changed.isEmpty() -> {
                                    lastLearn = "nada cambió"
                                    LearnStep.Nothing
                                }
                                soundsLikeLights(changed[0].first.substringAfter('/')) -> {
                                    lastLearn = "aprendido ${changed[0].first}"
                                    save(changed[0].first, changed[0].second)
                                    LearnStep.Idle
                                }
                                else -> {
                                    lastLearn = "${changed.size} candidatos"
                                    LearnStep.Choose(changed.take(7) + listOfNotNull(brightness))
                                }
                            }
                        }
                    }, primary = true)
                    Button(stringResource(R.string.cancel), { step = LearnStep.Idle })
                }
            }
            is LearnStep.Choose -> {
                Label(stringResource(R.string.lights_choose), Typography.body)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val brightness = stringResource(R.string.brightness)
                    for ((candidate, label) in s.candidates.zip(candidateLabels(s.candidates))) {
                        // El brillo con su nombre de siempre (su valor guardado es un tope, no algo que leer).
                        Button(if (candidate.first == BRIGHTNESS_KEY) brightness else label, {
                            lastLearn = "aprendido ${candidate.first}"
                            save(candidate.first, candidate.second)
                        })
                    }
                    Button(stringResource(R.string.cancel), { step = LearnStep.Idle })
                }
            }
            LearnStep.Nothing -> {
                Label(stringResource(R.string.lights_nothing), Typography.body, c.danger)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(stringResource(R.string.lights_retry), { withSnapshot { LearnStep.LightsOff(it) } })
                    UseSunButton(prefs)
                    Button(stringResource(R.string.lights_report), { openLightsReport() })
                }
            }
        }
    }
}

/** Salida para pantallas que no avisan de los faros: la noche por la hora del sol (lo que ya se usa mientras). */
@Composable
private fun UseSunButton(prefs: Preferences) {
    Button(stringResource(R.string.lights_use_sun), { prefs.nightSource = NightSource.SUN })
}

/**
 * Para el reporte: si hay sensor de luz y qué ajustes de la pantalla suenan a luces (con el
 * mismo criterio que al aprender). Cientos de filas por IPC: fuera del hilo principal.
 */
internal fun lightsReport(context: Context, learned: String?, theme: ThemeMode, night: NightSource): String {
    val sensor = context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_LIGHT)
    val all = snapshotSettings(context)
    // Valores cortos: las luces son números.
    val found = all.mapNotNull { (key, value) ->
        "$key=${value.take(24)}".takeIf { soundsLikeLights(key.substringAfter('/')) }
    }
    // Cuántos ajustes deja leer Android por tabla (0 = negada o vacía): si son pocos, "Aprender luces" no ve los faros.
    val rows = listOf("system", "global", "secure").joinToString { t -> "$t ${all.keys.count { it.startsWith("$t/") }}" }
    // El ajuste aprendido va aquí y no en pantalla (es una clave interna de la pantalla).
    return "Sensor de luz: ${sensor?.name ?: "no"}\nLuces: ${found.joinToString().ifEmpty { "—" }}\n" +
        "Luces aprendidas: ${learned ?: "no"}\nAprender: ${lastLearn ?: "sin intentar"}\n" +
        "Tema: ${theme.stored}, noche por: ${night.stored}\nAjustes leídos: $rows"
}
