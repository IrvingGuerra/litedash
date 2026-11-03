package com.irvingguerra.litedash

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.edit
import com.irvingguerra.litedash.feature.car.CarColors
import java.util.Locale
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Claves y valores ya guardados en el dispositivo: van en español porque así los
 * escribieron las versiones publicadas. Cambiarlos borraría los ajustes de quien
 * ya tiene LiteDash; los nombres en inglés son solo para el código.
 */
private object PrefKeys {
    const val FILE = "ajustes"
    const val THEME = "tema"
    const val NIGHT_SOURCE = "fuente_noche"
    const val SCALE = "escala"
    const val CAR_NAME = "nombre_auto"
    const val CAR_COLOR = "color_auto_argb"
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
    const val BRIGHTNESS_ORIGINAL = "brillo_original"
    /** Hasta la 1.4.35 la pieza era auto o logo (sí/no); se lee una vez para no perderlo. */
    const val SHOW_LOGO = "mostrar_logo"
    const val CAR_VIEW = "vista_auto"
    const val ENGINE_ALERTS = "alertas_motor"
    const val BAR_POSITION = "barra"
    const val LOGO_INTRO = "logo_entrada"
    const val INTRO_MIGRATED = "entrada_imagen_propia"
    const val LIGHTS_KEY = "luces_clave"
    const val LIGHTS_ON = "luces_encendido"
    const val SPLIT_SCREEN = "pantalla_dividida"
    const val SPLIT_CAR_LEFT = "dividida_auto_izquierda"
    const val TRIP_LOG = "registrar_viajes"
    const val BEST_ACCEL = "mejor_0_100"
    const val STATS = "estadisticas_anonimas"
    const val HIDE_STATUS_BAR = "ocultar_barra_android"
}

/** Dónde va la barra con la hora y tus apps. AUTO = a un lado si la pantalla está acostada. */
enum class BarPosition(val stored: String) {
    AUTO("auto"), SIDE("lateral"), BOTTOM("abajo");

    companion object {
        fun fromStored(value: String?) = entries.firstOrNull { it.stored == value } ?: AUTO
    }
}

/** Qué muestra la pieza del auto (y su vista en grande). */
enum class CarView(val stored: String) {
    /** El auto 3D que gira con el dedo. */
    CAR("auto"),
    /** Una imagen tuya (como el logo de tu marca) o, sin imagen, el nombre. Se guarda como "logo" desde la 1.4.15. */
    LOGO("logo"),
    /** El auto de espaldas en una carretera que avanza con la velocidad, con los km del viaje (como DuduAuto). */
    ROAD("carretera");

    companion object {
        fun fromStored(value: String?) = entries.firstOrNull { it.stored == value }
    }
}

/** [stored] es como se guarda cada tema (los nombres que tenía en la 1.3.4). */
enum class ThemeMode(val stored: String) {
    DARK("OSCURO"),
    LIGHT("CLARO"),
    SYSTEM("SISTEMA"),
    /** Claro de día y oscuro de noche (por el sol o el sensor de luz: [NightSource]). */
    SUN("SOL");

    companion object {
        fun fromStored(value: String?) = entries.firstOrNull { it.stored == value }
    }
}

/** Cómo se sabe si es de noche (para el tema "Día y noche" y el brillo). */
enum class NightSource(val stored: String) {
    /** Hora del sol: funciona en cualquier pantalla. */
    SUN("SOL"),
    /** Sensor de luz de la pantalla (si tiene): túneles, estacionamientos, nublado. */
    SENSOR("SENSOR"),
    /** Los faros del auto, por el ajuste de la pantalla que LiteDash aprende ([LightsKey]). */
    LIGHTS("LUCES");

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
    // Con la región del sistema si habla ese idioma (es-MX: "12.6 V"); si no, México, que
    // escribe los decimales con punto (el español genérico usa coma).
    val system = Resources.getSystem().configuration.locales[0]
    val locale = if (system.language == language) system else Locale.forLanguageTag(if (language == "es") "es-MX" else language)
    val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
    return base.createConfigurationContext(config)
}

/**
 * Un ajuste guardado: se lee una vez al abrir, es estado de Compose (redibuja lo que lo
 * usa) y cada cambio se escribe al momento.
 */
private class Stored<T>(initial: T, private val save: (T) -> Unit) : ReadWriteProperty<Any?, T> {
    private var value by mutableStateOf(initial)
    override fun getValue(thisRef: Any?, property: KProperty<*>) = value
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        this.value = value
        save(value)
    }
}

/** Ajustes del usuario: cada cambio se guarda al momento y redibuja lo que lo usa. */
class Preferences(context: Context) {
    private val p = context.getSharedPreferences(PrefKeys.FILE, Context.MODE_PRIVATE)

    private fun bool(key: String, default: Boolean) = Stored(p.getBoolean(key, default)) { p.edit { putBoolean(key, it) } }
    private fun int(key: String, default: Int) = Stored(p.getInt(key, default)) { p.edit { putInt(key, it) } }
    private fun float(key: String, default: Float) = Stored(p.getFloat(key, default)) { p.edit { putFloat(key, it) } }
    private fun string(key: String) = Stored(p.getString(key, null)) { p.edit { putString(key, it) } }
    private fun <T> mapped(key: String, read: (String?) -> T, write: (T) -> String?) =
        Stored(read(p.getString(key, null))) { p.edit { putString(key, write(it)) } }

    var theme by mapped(PrefKeys.THEME, { ThemeMode.fromStored(it) ?: ThemeMode.DARK }, { it.stored })

    var nightSource by mapped(PrefKeys.NIGHT_SOURCE, { NightSource.fromStored(it) ?: NightSource.SUN }, { it.stored })

    var scale by float(PrefKeys.SCALE, 1f)

    /** null = todavía no lo nombra (se muestra "Mi auto"). */
    var carName by string(PrefKeys.CAR_NAME)

    private var _carColor by mutableIntStateOf(p.getInt(PrefKeys.CAR_COLOR, CarColors[0].toArgb()))
    var carColor: Color
        get() = Color(_carColor)
        set(v) { _carColor = v.toArgb(); p.edit { putInt(PrefKeys.CAR_COLOR, _carColor) } }

    /** Mientras se arrastra el selector: el auto cambia en vivo y se guarda al soltar ([carColor]). */
    fun previewCarColor(v: Color) {
        _carColor = v.toArgb()
    }

    /** Id del auto del catálogo que se muestra; null = el sedán genérico. */
    var carModel by string(PrefKeys.CAR_MODEL)

    var welcomeSeen by bool(PrefKeys.WELCOME_SEEN, false)

    /** Se guarda porque Android reinicia la app al volverla pantalla de inicio. */
    var welcomeStep by int(PrefKeys.WELCOME_STEP, 0)

    /** El usuario pidió no volver a ver el aviso de "hazlo tu pantalla de inicio". */
    var dontAskHome by bool(PrefKeys.DONT_ASK_HOME, false)

    /** "" (sistema), "es" o "en". Cambiarlo recrea la actividad: se escribe al instante (commit). */
    var language by Stored(p.getString(PrefKeys.LANGUAGE, "") ?: "") { p.edit(commit = true) { putString(PrefKeys.LANGUAGE, it) } }

    /** Apps que no salen en el cajón ([com.irvingguerra.litedash.feature.apps.App.key]). */
    var hidden by Stored(p.getStringSet(PrefKeys.HIDDEN, emptySet())!!.toSet()) { p.edit { putStringSet(PrefKeys.HIDDEN, it) } }

    /** Apps fijas en el dock ([com.irvingguerra.litedash.feature.apps.App.key]); null = las sugeridas. */
    var dock by mapped(PrefKeys.DOCK, { v -> v?.split('\n')?.filter { it.isNotBlank() } }, { it?.joinToString("\n") })

    /** App de navegación elegida ([com.irvingguerra.litedash.feature.apps.App.key]); null = la primera instalada. */
    var mapApp by string(PrefKeys.MAP_APP)

    /** Paquete de la app de música elegida; null = "Sistema" (lo que suene: radio, USB, Bluetooth…). */
    var musicApp by string(PrefKeys.MUSIC_APP)

    /** Nombre de quien maneja, para saludarlo; null = sin saludo. */
    var driverName by string(PrefKeys.DRIVER_NAME)

    /** Dirección de Casa para el atajo de Navegar; null = sin guardar. */
    var homeAddress by string(PrefKeys.HOME_ADDRESS)

    /** Dirección de Trabajo para el atajo de Navegar; null = sin guardar. */
    var workAddress by string(PrefKeys.WORK_ADDRESS)

    /** Reloj grande tras unos minutos sin tocar el inicio. */
    var parkedMode by bool(PrefKeys.PARKED_MODE, true)

    /** Dónde van la hora y tus apps: a un lado, abajo o según la pantalla. */
    var barPosition by mapped(PrefKeys.BAR_POSITION, BarPosition::fromStored) { it.stored }

    /** Qué muestra la pieza del auto; quien tenía el logo prendido lo conserva. */
    var carView by mapped(
        PrefKeys.CAR_VIEW,
        { CarView.fromStored(it) ?: if (p.getBoolean(PrefKeys.SHOW_LOGO, false)) CarView.LOGO else CarView.CAR },
        { it.stored },
    )

    /** Abrir las apps de mapa y música en pantalla dividida, con el auto al lado (solo acostada). */
    var splitScreen by bool(PrefKeys.SPLIT_SCREEN, false)

    /** En pantalla dividida, el auto va a la izquierda (si no, a la derecha). */
    var splitCarLeft by bool(PrefKeys.SPLIT_CAR_LEFT, true)

    /** Guardar un resumen de cada viaje con el OBD conectado (apagado de fábrica). */
    var tripLog by bool(PrefKeys.TRIP_LOG, false)

    /** Ocultar la barra de Android de arriba (se asoma deslizando desde arriba); apagado de fábrica. */
    var hideStatusBar by bool(PrefKeys.HIDE_STATUS_BAR, false)

    /** Contar esta pantalla en las estadísticas anónimas (ver [Stats]); prendido de fábrica. */
    var stats by bool(PrefKeys.STATS, true)

    /** El mejor 0 a 100 (segundos); 0 = todavía no hay. */
    var bestAccel by float(PrefKeys.BEST_ACCEL, 0f)

    /** Con el OBD conectado: avisar de motor caliente o batería que no carga. */
    var engineAlerts by bool(PrefKeys.ENGINE_ALERTS, true)

    /** Al abrir LiteDash, tu imagen (o el logo de LiteDash) aparece unos instantes a pantalla completa. */
    var logoIntro by bool(PrefKeys.LOGO_INTRO, false)

    /** Ya se le dio su propia imagen a la animación de entrada ([migrateIntroImage]). */
    var introImageMigrated by bool(PrefKeys.INTRO_MIGRATED, false)

    /** Ajuste de la pantalla que dice si los faros están prendidos ("tabla/nombre"); null = sin aprender. */
    var lightsKey by string(PrefKeys.LIGHTS_KEY)

    /** El valor de ese ajuste con los faros prendidos. */
    var lightsOnValue by string(PrefKeys.LIGHTS_ON)

    /** Cambia cada vez que se elige o quita la imagen del logo (para volver a leerla); solo en memoria. */
    var logoRevision by mutableIntStateOf(0)

    /** Lo mismo para la imagen de la animación de entrada. */
    var introRevision by mutableIntStateOf(0)

    /** Lo mismo para la imagen de fondo del inicio. */
    var wallpaperRevision by mutableIntStateOf(0)

    /** Hacia dónde dejó girado el auto en el inicio (fracción de vuelta); null = tres cuartos. */
    var carAngle by Stored(p.getFloat(PrefKeys.CAR_ANGLE, -1f).takeIf { it >= 0f }) { p.edit { putFloat(PrefKeys.CAR_ANGLE, it ?: -1f) } }

    /** Botón del volante (código de tecla de Android) → acción guardada ([WheelAction.stored]). */
    var wheelKeys by mapped(
        PrefKeys.WHEEL_KEYS,
        { v ->
            (v ?: "").lines().mapNotNull { line ->
                val (code, action) = line.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                code.toIntOrNull()?.let { it to action }
            }.toMap()
        },
        { m -> m.entries.joinToString("\n") { "${it.key}=${it.value}" } },
    )

    /** Brillo de día y de noche según el sol (apagado = el brillo del sistema, sin tocarlo). */
    var brightnessBySun by bool(PrefKeys.BRIGHTNESS_BY_SUN, false)

    /** Por ciento, de 10 a 100. */
    var dayBrightness by int(PrefKeys.DAY_BRIGHTNESS, 100)

    /** Por ciento, de 10 a 100. */
    var nightBrightness by int(PrefKeys.NIGHT_BRIGHTNESS, 40)

    /** También en las demás apps (cambia el brillo del sistema; pide "modificar ajustes del sistema"). */
    var brightnessAllApps by bool(PrefKeys.BRIGHTNESS_ALL_APPS, false)

    /**
     * El brillo del sistema como estaba antes de que LiteDash lo cambiara ("modo,nivel"):
     * se regresa al apagar el brillo en todas las apps. null = LiteDash no lo ha tocado.
     */
    var brightnessOriginal: Pair<Int, Int>?
        get() = p.getString(PrefKeys.BRIGHTNESS_ORIGINAL, null)?.split(',')?.mapNotNull { it.toIntOrNull() }
            ?.takeIf { it.size == 2 }?.let { it[0] to it[1] }
        set(v) = p.edit { putString(PrefKeys.BRIGHTNESS_ORIGINAL, v?.let { "${it.first},${it.second}" }) }
}
