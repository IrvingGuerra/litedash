package com.irvingguerra.litedash.feature.map

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.irvingguerra.litedash.core.ui.Https
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import kotlin.math.roundToInt

/**
 * La ruta en línea cuando no hay mapa bajado: el servidor OSRM de FOSSGIS (routing.openstreetmap.de, gratis, de
 * voluntarios). Sus reglas: máximo un pedido por segundo para todo el servicio y nada de uso pesado. Por eso aquí: un
 * pedido solo cuando el usuario pide la ruta o al recalcular (quien llama espera ≥ [RECALC_GAP_MS] entre recálculos),
 * nunca dos en menos de [MIN_GAP_MS], tiempo corto ([TIMEOUT_MS]), un solo reintento y las últimas rutas en memoria.
 * El índice de litedash-maps la puede apagar ("online": {"osrm": false}, [MapPacks.onlineRouting]).
 */
object OnlineRoute {
    private const val ENDPOINT = "https://routing.openstreetmap.de/routed-car/route/v1/driving/"
    private const val USER_AGENT = "LiteDash (+https://litedash.dev)"

    /** Entre dos pedidos de esta pantalla, al menos esto (el servicio pide ≤ 1 por segundo en total). */
    private const val MIN_GAP_MS = 2_000L

    /** Lo más que se espera al servidor (conectar y leer). */
    private const val TIMEOUT_MS = 10_000

    /** Antes del único reintento. */
    private const val RETRY_WAIT_MS = 2_000L

    /** Entre dos recálculos en línea, al menos esto (lo respeta la guía). */
    const val RECALC_GAP_MS = 10_000L

    private const val CACHE_SIZE = 8
    private const val CACHE_MS = 10 * 60_000L

    private class Entry(val atMs: Long, val route: Route)

    private val cache = object : LinkedHashMap<String, Entry>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) = size > CACHE_SIZE
    }

    /** Cuándo salió el último pedido (`SystemClock.elapsedRealtime`); con [gapLock] se esperan en fila. */
    private var lastAt = 0L
    private val gapLock = Any()

    /**
     * ¿Hay una red con internet? Barato (no pide nada); sin red no se intenta el servidor (sin esperar al tiempo de
     * espera). Si no se puede saber, se intenta.
     */
    fun hasInternet(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(true)

    /**
     * La ruta de ([fromLat], [fromLon]) a ([toLat], [toLon]) por internet, sin curvas suavizadas aún (eso lo hace quien
     * llama). Con [headingDeg] (al recalcular andando) no arranca contra tu rumbo. null = sin red, el servidor falló o no
     * hay camino. Bloqueante: fuera del hilo principal.
     */
    fun route(context: Context, fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, headingDeg: Float?): Route? {
        if (!hasInternet(context)) return null
        // Desde ~11 m y hasta ~1 m del destino, con el rumbo por octantes: la misma ruta pedida otra vez no sale a la red.
        val key = String.format(Locale.ROOT, "%.4f,%.4f;%.5f,%.5f;%s", fromLat, fromLon, toLat, toLon, headingDeg?.let { (it / 45f).roundToInt() % 8 })
        val now = SystemClock.elapsedRealtime()
        synchronized(cache) { cache[key]?.let { if (now - it.atMs in 0 until CACHE_MS) return it.route } }
        val url = buildString {
            append(ENDPOINT)
            append(String.format(Locale.ROOT, "%.6f,%.6f;%.6f,%.6f", fromLon, fromLat, toLon, toLat))
            append("?overview=full&geometries=polyline6&steps=true")
            headingDeg?.let { append("&bearings=").append(((it % 360f + 360f) % 360f).roundToInt() % 360).append(",45;") }
        }
        val body = fetch(context, url) ?: return null
        val route = parseOsrm(body) ?: return null
        synchronized(cache) { cache[key] = Entry(SystemClock.elapsedRealtime(), route) }
        return route
    }

    /** El cuerpo de la respuesta; un reintento solo si se cortó la red o el servidor falló (5xx). null = no se pudo. */
    private fun fetch(context: Context, url: String): String? {
        repeat(2) { attempt ->
            if (attempt > 0) Thread.sleep(RETRY_WAIT_MS)
            waitTurn()
            try {
                val c = Https.open(context, url)
                try {
                    c.connectTimeout = TIMEOUT_MS
                    c.readTimeout = TIMEOUT_MS
                    c.setRequestProperty("User-Agent", USER_AGENT)
                    c.setRequestProperty("Accept", "application/json")
                    val code = c.responseCode
                    // 4xx (sin camino, muchos pedidos): no se reintenta.
                    if (code in 400..499) return null
                    if (code in 200..299) return c.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    c.disconnect()
                }
            } catch (_: IOException) {
                // Red cortada: el reintento.
            }
        }
        return null
    }

    /** Espera lo que falte para [MIN_GAP_MS] desde el último pedido y apunta este. */
    private fun waitTurn() = synchronized(gapLock) {
        val wait = lastAt + MIN_GAP_MS - SystemClock.elapsedRealtime()
        if (lastAt != 0L && wait > 0) Thread.sleep(wait)
        lastAt = SystemClock.elapsedRealtime()
    }
}

/** Decodifica una línea en formato polyline con 6 decimales (la de OSRM): pares lat, lon en millonésimas de grado. */
internal fun decodePolyline6(text: String): IntArray {
    val out = IntList((text.length / 2).coerceAtLeast(16))
    var i = 0
    var lat = 0
    var lon = 0
    fun next(): Int? {
        var result = 0
        var shift = 0
        while (true) {
            if (i >= text.length) return null
            val b = text[i++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
            if (b < 0x20) break
        }
        return if (result and 1 != 0) (result shr 1).inv() else result shr 1
    }
    while (i < text.length) {
        lat += next() ?: break
        lon += next() ?: break
        out.add(lat)
        out.add(lon)
    }
    return IntArray(out.size) { out[it] }
}

/**
 * Convierte la respuesta de OSRM (`/route/v1`, `polyline6` y `steps=true`) en una [Route] marcada [Route.online]: los
 * puntos, metros y segundos por punto (los segundos de cada paso repartidos por lo que mide) y las vueltas con el tipo
 * más parecido ([osrmType]); una glorieta guarda su número de salida. null si no es la respuesta esperada o no hay ruta.
 */
internal fun parseOsrm(json: String): Route? {
    val o = try {
        JSONObject(json)
    } catch (_: JSONException) {
        return null
    }
    if (o.optString("code") != "Ok") return null
    val r = o.optJSONArray("routes")?.optJSONObject(0) ?: return null
    val points = decodePolyline6(r.optString("geometry"))
    val n = points.size / 2
    val distance = r.optDouble("distance")
    val duration = r.optDouble("duration")
    val steps = r.optJSONArray("legs")?.optJSONObject(0)?.optJSONArray("steps") ?: return null
    if (n < 2 || distance.isNaN() || duration.isNaN() || steps.length() < 2) return null
    val atM = DoubleArray(n)
    for (i in 1 until n) atM[i] = atM[i - 1] + flatMeters(points, i - 1, i)
    if (atM[n - 1] > 0) for (i in 0 until n) atM[i] *= distance / atM[n - 1]
    // Dónde empieza cada paso en la línea (su punto de maniobra es uno de sus puntos), siempre hacia adelante.
    val starts = IntArray(steps.length())
    var cursor = 0
    for (k in 0 until steps.length()) {
        val loc = steps.optJSONObject(k)?.optJSONObject("maneuver")?.optJSONArray("location") ?: return null
        starts[k] = when (k) {
            0 -> 0
            steps.length() - 1 -> n - 1
            else -> nearestAhead(points, cursor, Math.round(loc.optDouble(1) * 1e6).toInt(), Math.round(loc.optDouble(0) * 1e6).toInt())
        }
        cursor = starts[k]
    }
    // Los segundos: cada paso a su paso (calles lentas y rápidas), y el total el del servidor.
    val atS = DoubleArray(n)
    for (k in 0 until steps.length()) {
        val from = starts[k]
        val to = if (k + 1 < steps.length()) starts[k + 1] else n - 1
        val stepM = atM[to] - atM[from]
        val seconds = steps.optJSONObject(k)?.optDouble("duration")?.takeUnless { it.isNaN() } ?: 0.0
        for (i in from + 1..to) atS[i] = atS[i - 1] + if (stepM > 0) seconds * (atM[i] - atM[i - 1]) / stepM else 0.0
    }
    if (atS[n - 1] > 0) for (i in 0 until n) atS[i] *= duration / atS[n - 1]
    val maneuvers = ArrayList<Maneuver>()
    val byName = HashMap<String, Double>()
    for (k in 0 until steps.length()) {
        val step = steps.getJSONObject(k)
        val m = step.optJSONObject("maneuver") ?: return null
        val kind = m.optString("type")
        val modifier = m.optString("modifier").takeIf { it.isNotEmpty() }
        val name = roadOf(step)
        if (name.isNotEmpty()) byName[name] = (byName[name] ?: 0.0) + step.optDouble("distance", 0.0)
        val type = when (k) {
            0 -> ManeuverType.DEPART
            steps.length() - 1 -> ManeuverType.ARRIVE
            else -> osrmType(kind, modifier) ?: continue
        }
        val point = starts[k]
        // Si dos vueltas caen en el mismo punto, se queda la primera (la que dice qué hacer ahí).
        if (maneuvers.isNotEmpty() && maneuvers.last().point == point && type != ManeuverType.ARRIVE) continue
        val angle: Float
        val road: String
        val exit: Int
        if (type == ManeuverType.ROUNDABOUT) {
            // Al salir de la glorieta: su paso «exit roundabout» trae hacia dónde sales y la calle (si no, el siguiente).
            val out = steps.optJSONObject(k + 1)
            val leaving = out?.optJSONObject("maneuver")?.optString("type")?.startsWith("exit ") == true
            angle = roundaboutTurn(m, if (leaving) out?.optJSONObject("maneuver") else null, modifier)
            road = (if (leaving) out?.let(::roadOf) else null)?.takeIf { it.isNotEmpty() } ?: name
            exit = m.optInt("exit", 0).coerceAtLeast(0)
        } else {
            // Los rumbos de OSRM son los del mero cruce (una vuelta a la izquierda a una lateral sale «109° → 108°»):
            // la flecha sale del tipo de vuelta, que sí es fiable.
            angle = if (type == ManeuverType.DEPART || type == ManeuverType.ARRIVE) 0f else MODIFIER_ANGLES[modifier] ?: 0f
            road = if (type == ManeuverType.ARRIVE) "" else name
            exit = 0
        }
        maneuvers += Maneuver(type, angle, road, point, atM[point], exit)
    }
    val main = byName.maxByOrNull { it.value }?.key.orEmpty()
    return Route(points, distance, duration, main, maneuvers, atM, atS, online = true)
}

/**
 * El tipo de vuelta para un paso de OSRM ([kind] = `maneuver.type`, [modifier] = `maneuver.modifier`); null = no se dice
 * nada ahí: salir de la glorieta (ya va en la glorieta), avisos, carriles, o la misma calle que solo cambia de nombre o
 * sigue derecho.
 */
internal fun osrmType(kind: String, modifier: String?): ManeuverType? {
    val turn = when (modifier) {
        "uturn" -> ManeuverType.UTURN
        "sharp right" -> ManeuverType.SHARP_RIGHT
        "right" -> ManeuverType.RIGHT
        "slight right" -> ManeuverType.SLIGHT_RIGHT
        "slight left" -> ManeuverType.SLIGHT_LEFT
        "left" -> ManeuverType.LEFT
        "sharp left" -> ManeuverType.SHARP_LEFT
        else -> ManeuverType.STRAIGHT
    }
    return when (kind) {
        "depart" -> ManeuverType.DEPART
        "arrive" -> ManeuverType.ARRIVE
        "roundabout", "rotary" -> ManeuverType.ROUNDABOUT
        "exit roundabout", "exit rotary", "notification", "use lane" -> null
        // Cambia de nombre sin dar vuelta, o sigue por la misma: como el ruteador propio, no es vuelta.
        "new name", "continue" -> turn.takeUnless { it == ManeuverType.STRAIGHT || it == ManeuverType.SLIGHT_LEFT || it == ManeuverType.SLIGHT_RIGHT }
        // turn, end of road, fork, merge, on ramp, off ramp, roundabout turn y los que se agreguen.
        else -> turn
    }
}

/** La calle de un paso: su nombre o, sin nombre, su número de carretera (`ref`); "" sin ninguno. */
private fun roadOf(step: JSONObject): String =
    step.optString("name").trim().ifEmpty { step.optString("ref").trim() }

/**
 * Cuánto se gira en una glorieta (grados, + a la derecha): del rumbo al entrar ([m] `bearing_before`) al rumbo al salir
 * (`bearing_after` de su paso de salida, [leaving]); sin ese paso, lo que diga el [modifier].
 */
private fun roundaboutTurn(m: JSONObject, leaving: JSONObject?, modifier: String?): Float {
    val before = m.optDouble("bearing_before")
    val after = leaving?.optDouble("bearing_after") ?: Double.NaN
    if (before.isNaN() || after.isNaN()) return MODIFIER_ANGLES[modifier] ?: 0f
    return (((after - before) % 360.0 + 540.0) % 360.0 - 180.0).toFloat()
}

private val MODIFIER_ANGLES = mapOf(
    "uturn" to 180f, "sharp right" to 135f, "right" to 90f, "slight right" to 35f,
    "slight left" to -35f, "left" to -90f, "sharp left" to -135f,
)

/** El punto de [points] desde [from] que es ([lat], [lon]); si ninguno es exacto, el más cercano. */
private fun nearestAhead(points: IntArray, from: Int, lat: Int, lon: Int): Int {
    var best = from
    var bestD = Long.MAX_VALUE
    for (i in from until points.size / 2) {
        val dy = (points[2 * i] - lat).toLong()
        val dx = (points[2 * i + 1] - lon).toLong()
        val d = dy * dy + dx * dx
        if (d == 0L) return i
        if (d < bestD) {
            bestD = d
            best = i
        }
    }
    return best
}
