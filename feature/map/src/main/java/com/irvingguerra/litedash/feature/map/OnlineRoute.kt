package com.irvingguerra.litedash.feature.map

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import com.irvingguerra.litedash.core.ui.Https
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.math.roundToInt

/**
 * La ruta en línea cuando no hay mapa bajado: el servidor OSRM de FOSSGIS (routing.openstreetmap.de, gratis, de
 * voluntarios). Sus reglas: máximo un pedido por segundo para todo el servicio y nada de uso pesado. Por eso aquí: un
 * pedido solo cuando el usuario pide la ruta o al recalcular (quien llama espera ≥ [RECALC_GAP_MS] entre recálculos),
 * nunca dos en menos de [MIN_GAP_MS], la misma ruta pedida dos veces a la vez sale una sola vez, todo en [TOTAL_MS] (con
 * un solo reintento si queda tiempo) y las últimas rutas en memoria.
 * El índice de litedash-maps la puede apagar ("online": {"osrm": false}, [MapPacks.onlineRouting]).
 *
 * Ese servidor solo acepta TLS 1.3, que Android trae desde la 10: en pantallas más viejas (y en cualquiera donde la
 * conexión segura con él falle) el pedido va por el relevo de litedash.dev ([RELAY]), que lo repite tal cual.
 */
object OnlineRoute {
    private const val DIRECT = "https://routing.openstreetmap.de/routed-car/route/v1/driving/"
    private const val RELAY = "https://litedash.dev/route/v1/driving/"

    /** ¿Los pedidos van por [RELAY]? De entrada en Android ≤ 9; después, si la conexión segura directa falló. */
    @Volatile private var relayed = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
    private const val USER_AGENT = "LiteDash (+https://litedash.dev)"

    /** Entre dos pedidos de esta pantalla, al menos esto (el servicio pide ≤ 1 por segundo en total). */
    private const val MIN_GAP_MS = 2_000L

    /** Lo más que tarda un pedido completo (esperar turno, conectar, leer y el reintento). */
    private const val TOTAL_MS = 15_000L

    /** Lo más que se espera al servidor en un intento (conectar o cada lectura). */
    private const val TIMEOUT_MS = 8_000

    /** Antes del único reintento. */
    private const val RETRY_WAIT_MS = 1_000L

    /** Con menos tiempo que esto por delante no se reintenta. */
    private const val RETRY_MIN_MS = 3_000L

    /** Una respuesta más grande que esto (bytes) no se lee: un viaje así no cabe en la memoria de muchas pantallas. */
    internal const val MAX_BYTES = 4 * 1024 * 1024

    /** Entre dos recálculos en línea, al menos esto (lo respeta la guía). */
    const val RECALC_GAP_MS = 10_000L

    /** Las últimas rutas, pocas y con un tope de puntos entre todas (una ruta larga ocupa megas). */
    private const val CACHE_SIZE = 2
    private const val CACHE_POINTS = 60_000
    private const val CACHE_MS = 10 * 60_000L

    private class Entry(val atMs: Long, val route: Route)

    private val cache = object : LinkedHashMap<String, Entry>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) = size > CACHE_SIZE
    }

    /** Los pedidos en curso, por llave. */
    private val inFlight = SharedCalls<Route>()

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

    /** Suelta las rutas guardadas (Android pide memoria o una respuesta no cupo). */
    fun clearCache() = synchronized(cache) { cache.clear() }

    /**
     * La ruta de ([fromLat], [fromLon]) a ([toLat], [toLon]) por internet, sin curvas suavizadas aún (eso lo hace quien
     * llama). Con [headingDeg] (al recalcular andando) no arranca contra tu rumbo. null = sin red, el servidor falló, no
     * hay camino o la respuesta es demasiado grande. Bloqueante: fuera del hilo principal.
     */
    fun route(context: Context, fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, headingDeg: Float?): Route? {
        if (!hasInternet(context)) return null
        val key = routeKey(fromLat, fromLon, toLat, toLon, headingDeg)
        val now = SystemClock.elapsedRealtime()
        synchronized(cache) { cache[key]?.let { if (now - it.atMs in 0 until CACHE_MS) return it.route } }
        // Si ya se está pidiendo lo mismo (la tarjeta se vuelve a planear al llegar la lista o tu país), se espera ese.
        return inFlight.run(key, TOTAL_MS + RETRY_WAIT_MS) {
            val query = buildString {
                append(String.format(Locale.ROOT, "%.6f,%.6f;%.6f,%.6f", fromLon, fromLat, toLon, toLat))
                append("?overview=full&geometries=polyline6&steps=true")
                headingDeg?.let { append("&bearings=").append(normalized(it).roundToInt() % 360).append(",45;") }
            }
            fetch(context, query)?.let(::parseOsrm)?.also { keep(key, it) }
        }
    }

    /** Guarda [route]; si entre todas pasan de [CACHE_POINTS] puntos se sueltan las más viejas (una enorme no se guarda). */
    private fun keep(key: String, route: Route) = synchronized(cache) {
        if (route.pointCount > CACHE_POINTS) return@synchronized
        cache[key] = Entry(SystemClock.elapsedRealtime(), route)
        var total = cache.values.sumOf { it.route.pointCount }
        val it = cache.values.iterator()
        while (total > CACHE_POINTS && it.hasNext()) {
            val e = it.next()
            if (e.route === route) continue
            total -= e.route.pointCount
            it.remove()
        }
    }

    /**
     * El cuerpo de la respuesta a [query] (los puntos y las opciones, lo que va después del servidor); un reintento solo
     * si se cortó la red, el servidor falló (5xx) o no hubo conexión segura (ese va por el relevo) y queda tiempo.
     * null = no se pudo.
     */
    private fun fetch(context: Context, query: String): String? {
        val deadline = SystemClock.elapsedRealtime() + TOTAL_MS
        for (attempt in 0..1) {
            if (attempt > 0) {
                if (deadline - SystemClock.elapsedRealtime() < RETRY_WAIT_MS + RETRY_MIN_MS) return null
                Thread.sleep(RETRY_WAIT_MS)
            }
            if (!waitTurn(deadline)) return null
            val left = (deadline - SystemClock.elapsedRealtime()).coerceIn(1L, TIMEOUT_MS.toLong()).toInt()
            try {
                val c = Https.open(context, (if (relayed) RELAY else DIRECT) + query)
                try {
                    c.connectTimeout = left
                    c.readTimeout = left
                    c.setRequestProperty("User-Agent", USER_AGENT)
                    c.setRequestProperty("Accept", "application/json")
                    val code = c.responseCode
                    // 4xx (sin camino, muchos pedidos): no se reintenta.
                    if (code in 400..499) return null
                    if (code in 200..299) {
                        // Más grande de lo que cabe (lo dice el servidor o se ve al leer): sin reintento.
                        if (c.contentLength > MAX_BYTES) return null
                        return c.inputStream.use { readCapped(it, MAX_BYTES, deadline) }
                    }
                } finally {
                    c.disconnect()
                }
            } catch (_: SSLException) {
                // Sin conexión segura con el servidor (TLS 1.3 que la pantalla no trae): el reintento, por el relevo.
                relayed = true
            } catch (_: IOException) {
                // Red cortada: el reintento.
            }
        }
        return null
    }

    /** Espera lo que falte para [MIN_GAP_MS] desde el último pedido y apunta este; false si eso pasa de [deadline]. */
    private fun waitTurn(deadline: Long): Boolean = synchronized(gapLock) {
        val wait = lastAt + MIN_GAP_MS - SystemClock.elapsedRealtime()
        if (lastAt != 0L && wait > 0) {
            if (SystemClock.elapsedRealtime() + wait >= deadline) return false
            Thread.sleep(wait)
        }
        lastAt = SystemClock.elapsedRealtime()
        true
    }
}

/**
 * Llamadas bloqueantes compartidas por llave: si llega otra con la misma llave mientras corre una, espera (hasta
 * `waitMs`) y se queda con lo que dio la primera, sin volver a llamar.
 */
internal class SharedCalls<T : Any> {
    private class Pending<T> {
        val done = CountDownLatch(1)
        @Volatile var value: T? = null
    }

    private val running = HashMap<String, Pending<T>>()

    fun run(key: String, waitMs: Long, block: () -> T?): T? {
        var mine = false
        val pending = synchronized(running) { running.getOrPut(key) { Pending<T>().also { mine = true } } }
        if (!mine) {
            pending.done.await(waitMs, TimeUnit.MILLISECONDS)
            return pending.value
        }
        try {
            return block().also { pending.value = it }
        } finally {
            synchronized(running) { running.remove(key) }
            pending.done.countDown()
        }
    }
}

/**
 * La llave de una ruta en la caché: desde ~11 m y hasta ~1 m del destino, con el rumbo por octantes (0–7, de cualquier
 * rumbo, también negativo o de más de una vuelta).
 */
internal fun routeKey(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, headingDeg: Float?): String =
    String.format(Locale.ROOT, "%.4f,%.4f;%.5f,%.5f;%s", fromLat, fromLon, toLat, toLon, headingDeg?.let { (normalized(it) / 45f).roundToInt() % 8 })

/** El rumbo entre 0 y 360. */
private fun normalized(degrees: Float): Float = (degrees % 360f + 360f) % 360f

/** Lee [input] completo como texto; null si pasa de [maxBytes] o de [deadline] (`SystemClock.elapsedRealtime`). */
internal fun readCapped(input: InputStream, maxBytes: Int, deadline: Long = Long.MAX_VALUE): String? {
    val out = ByteArrayOutputStream(64 * 1024)
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (out.size() + read > maxBytes) return null
        if (deadline != Long.MAX_VALUE && SystemClock.elapsedRealtime() > deadline) return null
        out.write(buffer, 0, read)
    }
    return out.toString("UTF-8")
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
    val line = decodePolyline6(r.optString("geometry"))
    val distance = r.optDouble("distance")
    val duration = r.optDouble("duration")
    val steps = r.optJSONArray("legs")?.optJSONObject(0)?.optJSONArray("steps") ?: return null
    if (line.size < 4 || distance.isNaN() || duration.isNaN() || steps.length() < 2) return null
    // Dónde empieza cada paso en la línea (su punto de maniobra es uno de sus puntos), siempre hacia adelante.
    val starts = IntArray(steps.length())
    var cursor = 0
    for (k in 0 until steps.length()) {
        val loc = steps.optJSONObject(k)?.optJSONObject("maneuver")?.optJSONArray("location") ?: return null
        starts[k] = when (k) {
            0 -> 0
            steps.length() - 1 -> line.size / 2 - 1
            else -> nearestAhead(line, cursor, Math.round(loc.optDouble(1) * 1e6).toInt(), Math.round(loc.optDouble(0) * 1e6).toInt())
        }
        cursor = starts[k]
    }
    // Sin los puntos que casi no se mueven (una carretera larga trae decenas de miles): menos memoria aquí, al suavizar y al dibujar.
    val points = thinned(line, starts, THIN_METERS)
    val n = points.size / 2
    if (n < 2) return null
    val atM = DoubleArray(n)
    for (i in 1 until n) atM[i] = atM[i - 1] + flatMeters(points, i - 1, i)
    if (atM[n - 1] > 0) for (i in 0 until n) atM[i] *= distance / atM[n - 1]
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

/** Entre dos puntos seguidos de la ruta en línea, al menos esto (m); los de las vueltas se quedan siempre. */
private const val THIN_METERS = 3.0

/**
 * [points] (pares lat, lon en millonésimas) sin los que quedan a menos de [minMeters] del último que se quedó, salvo los
 * de [keep] (índices en orden, p. ej. donde empieza cada vuelta) y el último. Los índices de [keep] se cambian ahí mismo
 * a los de la línea nueva.
 */
internal fun thinned(points: IntArray, keep: IntArray, minMeters: Double): IntArray {
    val n = points.size / 2
    if (n < 3) return points
    val out = IntList(points.size)
    var k = 0
    var last = 0
    for (i in 0 until n) {
        val must = i == 0 || i == n - 1 || (k < keep.size && keep[k] == i)
        if (!must && flatMeters(points, last, i) < minMeters) continue
        while (k < keep.size && keep[k] == i) keep[k++] = out.size / 2
        out.add(points[2 * i])
        out.add(points[2 * i + 1])
        last = i
    }
    return IntArray(out.size) { out[it] }
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
