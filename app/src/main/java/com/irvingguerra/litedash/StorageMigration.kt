package com.irvingguerra.litedash

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.irvingguerra.litedash.feature.car.CarFiles
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 1.4.47: todo lo que LiteDash guarda pasó de nombres en español a inglés. Corre una sola vez, al
 * arrancar la app y antes de leer cualquier ajuste: COPIA lo viejo a lo nuevo y deja lo viejo donde
 * estaba (salvo los autos, que se mueven porque pesan y se pueden volver a bajar). Una versión
 * futura borrará lo viejo (la lista de abajo).
 *
 * Los nombres viejos están escritos aquí a propósito: son historia y no deben seguir a las
 * constantes de hoy. Cada paso va por su lado: si uno falla, los demás se copian igual.
 *
 * Para borrar en la versión de diciembre de 2026:
 * - Archivos de ajustes de antes de la 1.4.47: `ajustes`, `estadisticas`, `clima`, `recordatorios`,
 *   `musica`, `autos`, `actualizaciones`, `tablero`.
 * - Archivos de antes de la 1.4.47 dentro de files/: `entrada.png`, `fondo.jpg`, `viajes.csv` y
 *   `logo.png` (la imagen de la pieza del auto hasta la 1.4.48; al borrarlo, quitar también
 *   [introImage], que lo lee).
 * - Claves viejas del archivo `obd` (se quedó con su nombre): `tipo`, `direccion`, `nombre`,
 *   `puerto`, `fabricante`, `producto`.
 * - En `settings`: `car_view` (lo lee [carTiles]) e `intro_image_migrated` (la bandera de [introImage]).
 */
internal object StorageMigration {
    private const val TAG = "LiteDash"
    private const val ATTEMPTS = "storage_attempts"
    private const val MAX_ATTEMPTS = 3

    fun run(context: Context) {
        val settings = prefs(context, PrefKeys.FILE)
        // Versión 1 (1.4.47): nombres en inglés. Versión 2 (1.4.49): la pieza del auto en tres piezas.
        // Cada una espera a que la anterior termine.
        if (settings.getInt(PrefKeys.STORAGE_VERSION, 0) < 1) {
            migrate(
                context, settings, 1,
                listOf(
                    "settings" to ::settings, "stats" to ::stats, "weather" to ::weather, "reminders" to ::reminders,
                    "music" to ::music, "obd" to ::obd, "updates" to ::updates, "dashboard" to ::dashboard,
                    "images" to ::images, "trips" to ::trips, "cars" to ::cars,
                ),
            )
        }
        if (settings.getInt(PrefKeys.STORAGE_VERSION, 0) == 1) migrate(context, settings, 2, listOf("car tiles" to ::carTiles))
        // Aparte de las versiones (es de antes): después de copiar los ajustes, que traen su bandera.
        introImage(context, settings)
    }

    /**
     * La animación de entrada usaba la imagen de la pieza del auto (files/logo.png); desde que tiene
     * la suya (files/intro.png), quien la tenía prendida se queda con la misma foto. Una sola vez:
     * la bandera se marca antes de copiar (como siempre), así un error no se repite en cada arranque.
     */
    private fun introImage(context: Context, settings: SharedPreferences) {
        if (settings.getBoolean(PrefKeys.INTRO_MIGRATED, false)) return
        settings.edit(commit = true) { putBoolean(PrefKeys.INTRO_MIGRATED, true) }
        val logo = File(context.filesDir, "logo.png")
        val intro = File(context.filesDir, "intro.png")
        if (settings.getBoolean(PrefKeys.LOGO_INTRO, false) && logo.exists() && !intro.exists()) runCatching { logo.copyTo(intro) }
    }

    private fun migrate(context: Context, settings: SharedPreferences, version: Int, steps: List<Pair<String, (Context) -> Unit>>) {
        val failed = steps.count { (name, step) -> runCatching { step(context) }.onFailure { Log.w(TAG, "No se pudo migrar $name", it) }.isFailure }
        // Si algo falló se reintenta al siguiente arranque (los pasos no pisan lo ya copiado), a lo más 3 veces.
        val attempts = settings.getInt(ATTEMPTS, 0) + 1
        settings.edit(commit = true) {
            if (failed == 0 || attempts >= MAX_ATTEMPTS) putInt(PrefKeys.STORAGE_VERSION, version).remove(ATTEMPTS) else putInt(ATTEMPTS, attempts)
        }
    }

    /**
     * 1.4.49: la pieza del auto mostraba el auto 3D, la carretera o una imagen según "car_view"; ahora
     * son tres piezas. Cada acomodo cambia su pieza del auto por la de lo que mostraba, la imagen pasa
     * a images/logo.png (la de antes se queda), las plantillas de fábrica usan esa pieza y, con la
     * carretera, la pantalla dividida la sigue mostrando. "car_view" se queda guardado: se borra con lo demás viejo.
     */
    private fun carTiles(context: Context) {
        val settings = prefs(context, PrefKeys.FILE)
        val view = settings.getString("car_view", null) ?: return
        val dashboard = prefs(context, "dashboard")
        val layouts = dashboard.all.filter { (key, value) -> key.startsWith("layout_") && value is String }
        dashboard.edit(commit = true) {
            for ((key, value) in layouts) putString(key, LegacyNames.carTiles(value as String, view))
            // Las cuadrículas sin acomodo guardado salen de la plantilla: con la misma pieza grande.
            when (view) {
                "road" -> putString("hero_type", "road").putString("hero_data", "")
                "logo" -> putString("hero_type", "image").putString("hero_data", LegacyNames.LOGO_IMAGE)
            }
        }
        if (view == "road" && !settings.contains(PrefKeys.SPLIT_ROAD)) settings.edit(commit = true) { putBoolean(PrefKeys.SPLIT_ROAD, true) }
        // La foto al final y por su lado: si no se puede copiar, la pieza ya es de imagen y muestra el logo de LiteDash.
        if (view == "logo") runCatching {
            val source = File(context.filesDir, "logo.png")
            val target = File(context.filesDir, "images/${LegacyNames.LOGO_IMAGE}.png")
            if (source.isFile && !target.exists()) {
                target.parentFile?.mkdirs()
                writeSafely(target) { source.copyTo(it, overwrite = true) }
            }
        }.onFailure { Log.w(TAG, "No se pudo copiar la imagen de la pieza", it) }
    }

    private fun settings(context: Context) {
        val old = prefs(context, "ajustes").all
        copy(context, old, PrefKeys.FILE, { SETTINGS_KEYS[it] }) { key, value ->
            when (key) {
                "theme" -> LegacyNames.theme(value as String)
                "night_source" -> LegacyNames.nightSource(value as String)
                "bar_position" -> LegacyNames.barPosition(value as String)
                "car_view" -> LegacyNames.carView(value as String)
                "wheel_keys" -> LegacyNames.wheelKeys(value as String)
                else -> value
            }
        }
        // Hasta la 1.4.35 la pieza era auto o logo (sí/no): sin una vista que se entienda (nunca se eligió,
        // o era "fondo", que se quitó en la 1.4.37), se respeta el logo como antes.
        val view = old["vista_auto"] as? String
        if (old["mostrar_logo"] == true && (view == null || !LegacyNames.isCarView(view))) {
            prefs(context, PrefKeys.FILE).edit(commit = true) { putString("car_view", "logo") }
        }
    }

    private fun stats(context: Context) = copy(context, "estadisticas", "stats", mapOf("id" to "id", "alta" to "since", "ultimo" to "last_ping"))

    private fun weather(context: Context) {
        val old = prefs(context, "clima").all
        // Sin "activado" guardado, el clima está prendido si ya hay uno guardado: eso lo resuelve Weather al leer.
        copy(context, old, "weather", mapOf("ultimo" to "last", "activado" to "enabled")::get)
    }

    private fun reminders(context: Context) =
        copy(context, "recordatorios", "reminders", mapOf("lista" to "list")) { _, v -> LegacyNames.reminders(v as String) }

    private fun music(context: Context) = copy(context, "musica", "music", mapOf("ultima_app" to "last_app"))

    private fun obd(context: Context) = copy(
        context, "obd", "obd",
        mapOf("tipo" to "type", "direccion" to "address", "nombre" to "name", "puerto" to "port", "fabricante" to "vendor_id", "producto" to "product_id"),
    )

    private fun updates(context: Context) =
        copy(context, "actualizaciones", "updates", mapOf("ultima_revision" to "last_check", "encontrada" to "found", "omitida" to "dismissed"))

    private fun dashboard(context: Context) {
        val old = prefs(context, "tablero").all
        copy(context, old, "dashboard", { key -> key.takeIf { it.startsWith("acomodo_") }?.let { "layout_" + it.removePrefix("acomodo_") } }) { _, v ->
            LegacyNames.layout(v as String)
        }
    }

    private fun images(context: Context) {
        val dir = context.filesDir
        for ((from, to) in listOf("entrada.png" to "intro.png", "fondo.jpg" to "wallpaper.jpg")) {
            val source = File(dir, from)
            val target = File(dir, to)
            if (source.isFile && !target.exists()) writeSafely(target) { source.copyTo(it, overwrite = true) }
        }
    }

    /** A un temporal y luego en su lugar: si se apaga la pantalla a medias no queda un archivo cortado. */
    private fun writeSafely(target: File, write: (File) -> Unit) {
        val temp = File(target.path + ".tmp")
        write(temp)
        if (!temp.renameTo(target)) {
            temp.delete()
            error("No se pudo escribir ${target.name}")
        }
    }

    /** Los viajes se copian con el encabezado en inglés (el CSV se exporta tal cual). */
    private fun trips(context: Context) {
        val source = File(context.filesDir, "viajes.csv")
        val target = File(context.filesDir, "trips.csv")
        if (!source.isFile || target.exists()) return
        val lines = source.readLines()
        if (lines.isEmpty()) return
        writeSafely(target) { it.writeText((listOf(LegacyNames.TRIPS_HEADER) + lines.drop(1)).joinToString("\n", postfix = "\n")) }
    }

    /** Los autos se mueven (pueden pesar varios MB y se vuelven a bajar si algo falla). */
    private fun cars(context: Context) {
        File(context.cacheDir, "actualizacion.apk").delete()
        copy(context, prefs(context, "autos").all, CarFiles.ROOT, { key -> key.takeIf { it.startsWith("ultima_revision:") }?.let { "last_check:" + it.removePrefix("ultima_revision:") } })
        val old = File(context.filesDir, "autos")
        if (!old.isDirectory) return
        val root = File(context.filesDir, CarFiles.ROOT)
        if (!root.exists() && !old.renameTo(root)) root.mkdirs()
        // Si no se pudo mover de golpe (o ya había algo), auto por auto.
        old.listFiles()?.forEach { child ->
            val target = File(root, child.name)
            if (!target.exists()) child.renameTo(target)
        }
        if (old.exists() && old.list().isNullOrEmpty()) old.delete()
        File(root, ".miniaturas").let { thumbs -> if (thumbs.isDirectory && !File(root, CarFiles.THUMBNAILS).exists()) thumbs.renameTo(File(root, CarFiles.THUMBNAILS)) }
        root.listFiles()?.forEach { child ->
            when {
                // Temporales de una descarga a medias: se vuelven a bajar.
                child.name.startsWith(".bajando-") || child.name.endsWith(".nueva") -> child.deleteRecursively()
                child.isDirectory && !child.name.startsWith(".") -> {
                    File(child, ".paquete.sha256").let { if (it.isFile) it.renameTo(File(child, CarFiles.PACKAGE_HASH)) }
                    File(child, "giro.nueva").deleteRecursively()
                }
            }
        }
        File(root, CarFiles.THUMBNAILS).listFiles { f -> f.name.endsWith(".parcial") }?.forEach { it.delete() }
    }

    private fun prefs(context: Context, name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun copy(context: Context, from: String, to: String, keys: Map<String, String>, value: (String, Any) -> Any? = { _, v -> v }) =
        copy(context, prefs(context, from).all, to, keys::get, value)

    /** Copia cada clave vieja que tenga nombre nuevo, sin pisar lo que ya exista con el nombre nuevo. */
    private fun copy(context: Context, old: Map<String, *>, to: String, key: (String) -> String?, value: (String, Any) -> Any? = { _, v -> v }) {
        if (old.isEmpty()) return
        val target = prefs(context, to)
        // commit y no apply: el idioma se lee justo después, al crear la pantalla.
        target.edit(commit = true) {
            for ((oldKey, oldValue) in old) {
                val newKey = key(oldKey) ?: continue
                if (oldValue == null || target.contains(newKey)) continue
                put(newKey, value(newKey, oldValue) ?: continue)
            }
        }
    }

    private fun SharedPreferences.Editor.put(key: String, value: Any) {
        when (value) {
            is String -> putString(key, value)
            is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
        }
    }

    private val SETTINGS_KEYS = mapOf(
        "tema" to "theme", "fuente_noche" to "night_source", "escala" to "scale", "nombre_auto" to "car_name",
        "color_auto_argb" to "car_color_argb", "modelo_auto" to "car_model", "bienvenida_vista" to "welcome_seen",
        "paso_bienvenida" to "welcome_step", "no_pedir_inicio" to "dont_ask_home", "idioma" to "language",
        "ocultas" to "hidden_apps", "dock" to "dock", "app_mapa" to "map_app", "app_musica" to "music_app",
        "nombre_conductor" to "driver_name", "direccion_casa" to "home_address", "direccion_trabajo" to "work_address",
        "modo_estacionado" to "parked_mode", "mandos_volante" to "wheel_keys", "angulo_auto" to "car_angle",
        "brillo_por_sol" to "brightness_by_sun", "brillo_dia" to "day_brightness", "brillo_noche" to "night_brightness",
        "brillo_todas_apps" to "brightness_all_apps", "brillo_original" to "brightness_original", "vista_auto" to "car_view",
        "alertas_motor" to "engine_alerts", "barra" to "bar_position", "logo_entrada" to "logo_intro",
        "entrada_imagen_propia" to "intro_image_migrated", "luces_clave" to "lights_key", "luces_encendido" to "lights_on_value",
        "pantalla_dividida" to "split_screen", "dividida_auto_izquierda" to "split_car_left", "registrar_viajes" to "trip_log",
        "mejor_0_100" to "best_0_100", "estadisticas_anonimas" to "anonymous_stats", "ocultar_barra_android" to "hide_status_bar",
    )
}

/** Traducción de los valores guardados antes de la 1.4.47 (funciones puras, con pruebas). */
internal object LegacyNames {
    /** Nombre de la foto de la pieza de imagen que sale de la imagen de la pieza del auto. */
    const val LOGO_IMAGE = "logo"
    const val TRIPS_HEADER = "start_ms,seconds,km,max_kmh,avg_kmh,max_rpm,max_coolant_c,liters"

    private val THEME = mapOf("OSCURO" to "dark", "CLARO" to "light", "SISTEMA" to "system", "SOL" to "sun")
    private val NIGHT = mapOf("SOL" to "sun", "SENSOR" to "sensor", "LUCES" to "lights")
    private val BAR = mapOf("auto" to "auto", "lateral" to "side", "abajo" to "bottom")
    private val CAR_VIEW = mapOf("auto" to "car", "logo" to "logo", "carretera" to "road")
    private val WHEEL = mapOf(
        "normal" to "normal", "play" to "play", "siguiente" to "next", "anterior" to "previous",
        "subir_volumen" to "volume_up", "bajar_volumen" to "volume_down", "silencio" to "mute", "navegar" to "navigate",
        "musica" to "music", "voz" to "voice", "inicio" to "home", "nada" to "nothing",
    )
    private val REMINDER = mapOf(
        "servicio" to "service", "verificacion" to "inspection", "tenencia" to "tax", "seguro" to "insurance",
        "placas" to "plates", "otro" to "other",
    )
    private val TILE = mapOf(
        "auto" to "car", "musica" to "music", "navegar" to "navigate", "mapa" to "navigate", "reloj" to "clock",
        "telefono" to "phone", "clima" to "weather", "viaje" to "trip", "velocimetro" to "speedometer",
        "medidor" to "gauge", "taller" to "workshop", "proyeccion" to "projection", "app" to "app",
    )
    private val GAUGE_KEY = mapOf("estilo" to "style", "color" to "color", "max" to "max", "marcas" to "ticks", "numeros" to "numbers", "roja" to "redline")
    private val GAUGE_STYLE = mapOf(
        "arco" to "arc", "aguja" to "needle", "digital" to "digital", "anillo" to "ring", "barra" to "bar",
        "degradado" to "gradient", "neon" to "neon", "cuadros" to "blocks",
    )
    private val GAUGE_COLOR = mapOf(
        "ambar" to "amber", "auto" to "car", "dinamico" to "dynamic", "verde" to "green", "azul" to "blue",
        "rojo" to "red", "blanco" to "white", "morado" to "purple", "cian" to "cyan",
    )
    private val SIGNAL = mapOf(
        "motor_temp" to "coolant_temp", "gasolina" to "fuel", "voltaje" to "voltage", "consumo" to "consumption",
        "turbo" to "boost", "acelerador" to "throttle", "aceite_temp" to "oil_temp", "gasolina_lh" to "fuel_rate",
        "velocidad" to "speed", "admision_temp" to "intake_temp", "carga" to "load", "presion_admision" to "intake_pressure",
        "presion_aire" to "barometric_pressure", "aire_temp" to "ambient_temp", "ajuste_corto" to "fuel_trim_short",
        "ajuste_largo" to "fuel_trim_long", "presion_gasolina" to "fuel_pressure", "avance_encendido" to "timing_advance",
        "tiempo_motor" to "run_time", "km_con_falla" to "distance_mil",
    )

    // Un valor que no se reconoce se deja igual: el lector nuevo usa su valor de fábrica, como antes.
    fun theme(v: String) = THEME[v] ?: v
    fun nightSource(v: String) = NIGHT[v] ?: v
    fun barPosition(v: String) = BAR[v] ?: v
    fun carView(v: String) = CAR_VIEW[v] ?: v
    fun isCarView(v: String) = v in CAR_VIEW

    /** Renglones "código=acción". */
    fun wheelKeys(text: String) = text.lines().joinToString("\n") { line ->
        val parts = line.split('=', limit = 2)
        if (parts.size == 2) "${parts[0]}=${WHEEL[parts[1]] ?: parts[1]}" else line
    }

    /** [{"id","tipo","fecha"}] → [{"id","type","date"}]. Si no se puede leer, se deja igual. */
    fun reminders(text: String): String = runCatching {
        val old = JSONArray(text)
        val list = JSONArray()
        for (i in 0 until old.length()) {
            val o = old.getJSONObject(i)
            val type = o.optString("tipo")
            list.put(
                JSONObject()
                    .put("id", o.getLong("id"))
                    .put("type", REMINDER[type] ?: type)
                    .put("date", o.getString("fecha")),
            )
        }
        list.toString()
    }.getOrDefault(text)

    /**
     * Un acomodo: una pieza por renglón, campos separados por tabulador
     * (id, tipo, columna, fila, ancho, alto, datos, sin tarjeta).
     */
    fun layout(text: String): String {
        val ids = mutableSetOf<String>()
        return text.lines().joinToString("\n") { line ->
            val f = line.split('\t').toMutableList()
            if (f.size < 2) return@joinToString line
            val oldType = f[1]
            val type = TILE[oldType] ?: oldType
            f[1] = type
            // Los ids solo deben ser únicos: se traduce su prefijo si no choca (p. ej. "mapa-0" y "navegar-0").
            val id = if (f[0].startsWith("$oldType-")) type + f[0].removePrefix(oldType) else f[0]
            if (id !in ids) f[0] = id
            ids += f[0]
            if (f.size > 6 && (type == "gauge" || type == "speedometer")) f[6] = gauge(f[6])
            f.joinToString("\t")
        }
    }

    /**
     * 1.4.49: la pieza del auto ("car") pasa a la de lo que mostraba ([view]: "road" → carretera,
     * "logo" → imagen con la foto [LOGO_IMAGE]); con el auto 3D no cambia nada. Ids como en [layout].
     */
    fun carTiles(text: String, view: String): String {
        val type = when (view) {
            "road" -> "road"
            "logo" -> "image"
            else -> return text
        }
        val ids = text.lines().mapTo(mutableSetOf()) { it.substringBefore('\t') }
        return text.lines().joinToString("\n") { line ->
            val f = line.split('\t').toMutableList()
            if (f.size < 6 || f[1] != "car") return@joinToString line
            f[1] = type
            if (f[0].startsWith("car-")) {
                val id = type + f[0].removePrefix("car")
                if (id !in ids) { ids -= f[0]; ids += id; f[0] = id }
            }
            if (type == "image") {
                if (f.size < 7) f += ""
                f[6] = LOGO_IMAGE
            }
            f.joinToString("\t")
        }
    }

    /** Datos de un medidor: "señal;clave=valor;…" (la señal es opcional: el velocímetro no lleva). */
    fun gauge(data: String): String {
        if (data.isBlank()) return data
        return data.split(';').joinToString(";") { part ->
            val pair = part.split('=', limit = 2)
            if (pair.size == 1) return@joinToString SIGNAL[part] ?: part
            val key = GAUGE_KEY[pair[0]] ?: pair[0]
            val value = when (key) {
                "style" -> GAUGE_STYLE[pair[1]] ?: pair[1]
                "color" -> GAUGE_COLOR[pair[1]] ?: pair[1]
                else -> pair[1]
            }
            "$key=$value"
        }
    }
}
