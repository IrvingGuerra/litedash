package com.irvingguerra.litedash

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.edit
import com.irvingguerra.litedash.feature.car.CarColors

/**
 * Claves y valores ya guardados en el dispositivo: van en español porque así los
 * escribieron las versiones publicadas. Cambiarlos borraría los ajustes de quien
 * ya tiene LiteDash; los nombres en inglés son solo para el código.
 */
private object PrefKeys {
    const val FILE = "ajustes"
    const val THEME = "tema"
    const val SCALE = "escala"
    const val CAR_NAME = "nombre_auto"
    const val CAR_COLOR = "color_auto_argb"
    const val LEGACY_CAR_COLOR = "color_auto"
    const val CAR_MODEL = "modelo_auto"
    const val WELCOME_SEEN = "bienvenida_vista"
    const val WELCOME_STEP = "paso_bienvenida"
    const val DONT_ASK_HOME = "no_pedir_inicio"
    const val LANGUAGE = "idioma"
    const val HIDDEN = "ocultas"
    const val DOCK = "dock"
    const val MAP_APP = "app_mapa"
    const val MUSIC_APP = "app_musica"
    const val DRIVER_NAME = "nombre_conductor"
    const val HOME_ADDRESS = "direccion_casa"
    const val WORK_ADDRESS = "direccion_trabajo"
    const val PARKED_MODE = "modo_estacionado"
    const val WHEEL_KEYS = "mandos_volante"
    const val CAR_ANGLE = "angulo_auto"
    const val BRIGHTNESS_BY_SUN = "brillo_por_sol"
    const val DAY_BRIGHTNESS = "brillo_dia"
    const val NIGHT_BRIGHTNESS = "brillo_noche"
    const val BRIGHTNESS_ALL_APPS = "brillo_todas_apps"
}

/** [stored] es como se guarda cada tema (los nombres que tenía en la 1.3.4). */
enum class ThemeMode(val stored: String) {
    DARK("OSCURO"),
    LIGHT("CLARO"),
    SYSTEM("SISTEMA"),
    /** Claro de día y oscuro de noche, por la hora del sol. */
    SUN("SOL");

    companion object {
        fun fromStored(value: String?) = entries.firstOrNull { it.stored == value }
    }
}

/** Idioma de la app: "" = el del sistema. Se lee antes de crear la pantalla. */
object Language {
    fun read(context: Context) = context.getSharedPreferences(PrefKeys.FILE, Context.MODE_PRIVATE).getString(PrefKeys.LANGUAGE, "") ?: ""
}

/** [base] con el idioma elegido en Ajustes. */
fun contextWithLanguage(base: Context): Context {
    val language = Language.read(base)
    if (language.isBlank()) return base
    val config = android.content.res.Configuration(base.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag(language)) }
    return base.createConfigurationContext(config)
}

/** Ajustes del usuario: cada cambio se guarda al momento y redibuja lo que lo usa. */
class Preferences(context: Context) {
    private val p = context.getSharedPreferences(PrefKeys.FILE, Context.MODE_PRIVATE)

    private var _theme by mutableStateOf(ThemeMode.fromStored(p.getString(PrefKeys.THEME, null)) ?: ThemeMode.DARK)
    var theme: ThemeMode
        get() = _theme
        set(v) { _theme = v; p.edit { putString(PrefKeys.THEME, v.stored) } }

    private var _scale by mutableFloatStateOf(p.getFloat(PrefKeys.SCALE, 1f))
    var scale: Float
        get() = _scale
        set(v) { _scale = v; p.edit { putFloat(PrefKeys.SCALE, v) } }

    private var _carName by mutableStateOf(p.getString(PrefKeys.CAR_NAME, null))
    /** null = todavía no lo nombra (se muestra "Mi auto"). */
    var carName: String?
        get() = _carName
        set(v) { _carName = v; p.edit { putString(PrefKeys.CAR_NAME, v) } }

    // Antes se guardaba el número de un color fijo (PrefKeys.LEGACY_CAR_COLOR); ahora el color libre.
    private var _carColor by mutableIntStateOf(
        if (p.contains(PrefKeys.CAR_COLOR)) p.getInt(PrefKeys.CAR_COLOR, 0)
        else CarColors[p.getInt(PrefKeys.LEGACY_CAR_COLOR, 0).coerceIn(CarColors.indices)].toArgb(),
    )
    var carColor: Color
        get() = Color(_carColor)
        set(v) { _carColor = v.toArgb(); p.edit { putInt(PrefKeys.CAR_COLOR, _carColor) } }

    private var _carModel by mutableStateOf(p.getString(PrefKeys.CAR_MODEL, null))
    /** Id del auto del catálogo que se muestra; null = el sedán genérico. */
    var carModel: String?
        get() = _carModel
        set(v) { _carModel = v; p.edit { putString(PrefKeys.CAR_MODEL, v) } }

    private var _welcomeSeen by mutableStateOf(p.getBoolean(PrefKeys.WELCOME_SEEN, false))
    var welcomeSeen: Boolean
        get() = _welcomeSeen
        set(v) { _welcomeSeen = v; p.edit { putBoolean(PrefKeys.WELCOME_SEEN, v) } }

    private var _welcomeStep by mutableIntStateOf(p.getInt(PrefKeys.WELCOME_STEP, 0))
    /** Se guarda porque Android reinicia la app al volverla pantalla de inicio. */
    var welcomeStep: Int
        get() = _welcomeStep
        set(v) { _welcomeStep = v; p.edit { putInt(PrefKeys.WELCOME_STEP, v) } }

    private var _dontAskHome by mutableStateOf(p.getBoolean(PrefKeys.DONT_ASK_HOME, false))
    /** El usuario pidió no volver a ver el aviso de "hazlo tu pantalla de inicio". */
    var dontAskHome: Boolean
        get() = _dontAskHome
        set(v) { _dontAskHome = v; p.edit { putBoolean(PrefKeys.DONT_ASK_HOME, v) } }

    private var _language by mutableStateOf(p.getString(PrefKeys.LANGUAGE, "") ?: "")
    /** "" (sistema), "es" o "en". Cambiarlo recrea la actividad. */
    var language: String
        get() = _language
        set(v) { _language = v; p.edit(commit = true) { putString(PrefKeys.LANGUAGE, v) } }

    private var _hidden by mutableStateOf(p.getStringSet(PrefKeys.HIDDEN, emptySet())!!.toSet())
    /** Apps que no salen en el cajón ([com.irvingguerra.litedash.feature.apps.App.key]). */
    var hidden: Set<String>
        get() = _hidden
        set(v) { _hidden = v; p.edit { putStringSet(PrefKeys.HIDDEN, v) } }

    private var _dock by mutableStateOf(p.getString(PrefKeys.DOCK, null)?.split('\n')?.filter { it.isNotBlank() })
    /** Apps fijas en el dock ([com.irvingguerra.litedash.feature.apps.App.key]); null = las sugeridas. */
    var dock: List<String>?
        get() = _dock
        set(v) { _dock = v; p.edit { putString(PrefKeys.DOCK, v?.joinToString("\n")) } }

    private var _mapApp by mutableStateOf(p.getString(PrefKeys.MAP_APP, null))
    /** App de navegación elegida ([com.irvingguerra.litedash.feature.apps.App.key]); null = la primera instalada. */
    var mapApp: String?
        get() = _mapApp
        set(v) { _mapApp = v; p.edit { putString(PrefKeys.MAP_APP, v) } }

    private var _musicApp by mutableStateOf(p.getString(PrefKeys.MUSIC_APP, null))
    /** Paquete de la app de música elegida; null = "Sistema" (lo que suene: radio, USB, Bluetooth…). */
    var musicApp: String?
        get() = _musicApp
        set(v) { _musicApp = v; p.edit { putString(PrefKeys.MUSIC_APP, v) } }

    private var _driverName by mutableStateOf(p.getString(PrefKeys.DRIVER_NAME, null))
    /** Nombre de quien maneja, para saludarlo; null = sin saludo. */
    var driverName: String?
        get() = _driverName
        set(v) { _driverName = v; p.edit { putString(PrefKeys.DRIVER_NAME, v) } }

    private var _homeAddress by mutableStateOf(p.getString(PrefKeys.HOME_ADDRESS, null))
    /** Dirección de Casa para el atajo de Navegar; null = sin guardar. */
    var homeAddress: String?
        get() = _homeAddress
        set(v) { _homeAddress = v; p.edit { putString(PrefKeys.HOME_ADDRESS, v) } }

    private var _workAddress by mutableStateOf(p.getString(PrefKeys.WORK_ADDRESS, null))
    /** Dirección de Trabajo para el atajo de Navegar; null = sin guardar. */
    var workAddress: String?
        get() = _workAddress
        set(v) { _workAddress = v; p.edit { putString(PrefKeys.WORK_ADDRESS, v) } }

    private var _parkedMode by mutableStateOf(p.getBoolean(PrefKeys.PARKED_MODE, true))
    /** Reloj grande tras unos minutos sin tocar el inicio. */
    var parkedMode: Boolean
        get() = _parkedMode
        set(v) { _parkedMode = v; p.edit { putBoolean(PrefKeys.PARKED_MODE, v) } }

    private var _carAngle by mutableStateOf(p.getFloat(PrefKeys.CAR_ANGLE, -1f).takeIf { it >= 0f })
    /** Hacia dónde dejó girado el auto en el inicio (fracción de vuelta); null = tres cuartos. */
    var carAngle: Float?
        get() = _carAngle
        set(v) { _carAngle = v; p.edit { putFloat(PrefKeys.CAR_ANGLE, v ?: -1f) } }

    private var _wheelKeys by mutableStateOf(
        p.getString(PrefKeys.WHEEL_KEYS, "")!!.lines().mapNotNull { line ->
            val (code, action) = line.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            code.toIntOrNull()?.let { it to action }
        }.toMap(),
    )
    /** Botón del volante (código de tecla de Android) → acción guardada ([WheelAction.stored]). */
    var wheelKeys: Map<Int, String>
        get() = _wheelKeys
        set(v) { _wheelKeys = v; p.edit { putString(PrefKeys.WHEEL_KEYS, v.entries.joinToString("\n") { "${it.key}=${it.value}" }) } }

    private var _brightnessBySun by mutableStateOf(p.getBoolean(PrefKeys.BRIGHTNESS_BY_SUN, false))
    /** Brillo de día y de noche según el sol (apagado = el brillo del sistema, sin tocarlo). */
    var brightnessBySun: Boolean
        get() = _brightnessBySun
        set(v) { _brightnessBySun = v; p.edit { putBoolean(PrefKeys.BRIGHTNESS_BY_SUN, v) } }

    private var _dayBrightness by mutableIntStateOf(p.getInt(PrefKeys.DAY_BRIGHTNESS, 100))
    /** Por ciento, de 10 a 100. */
    var dayBrightness: Int
        get() = _dayBrightness
        set(v) { _dayBrightness = v; p.edit { putInt(PrefKeys.DAY_BRIGHTNESS, v) } }

    private var _nightBrightness by mutableIntStateOf(p.getInt(PrefKeys.NIGHT_BRIGHTNESS, 40))
    /** Por ciento, de 10 a 100. */
    var nightBrightness: Int
        get() = _nightBrightness
        set(v) { _nightBrightness = v; p.edit { putInt(PrefKeys.NIGHT_BRIGHTNESS, v) } }

    private var _brightnessAllApps by mutableStateOf(p.getBoolean(PrefKeys.BRIGHTNESS_ALL_APPS, false))
    /** También en las demás apps (cambia el brillo del sistema; pide "modificar ajustes del sistema"). */
    var brightnessAllApps: Boolean
        get() = _brightnessAllApps
        set(v) { _brightnessAllApps = v; p.edit { putBoolean(PrefKeys.BRIGHTNESS_ALL_APPS, v) } }

}
