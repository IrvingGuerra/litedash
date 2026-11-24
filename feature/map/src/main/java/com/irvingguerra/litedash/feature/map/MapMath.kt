package com.irvingguerra.litedash.feature.map

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

// Cuentas del mapa sin Android (se prueban con JUnit). Basado en LiveMapView de StradaOS
// (GPL-3.0, https://github.com/stradaos): Web Mercator a mano; las teselas, vectoriales de OpenFreeMap.

/** Lo más cerca que llega el minimalista ([TileSource.maxZoom]) y lo más lejos que se aleja con los dedos. */
const val MAX_ZOOM = 20
const val MIN_ZOOM = 14

/** El zoom de los datos vectoriales: los nombres de las calles, las zonas sin conexión y las teselas del 15 en adelante salen de él. */
internal const val DATA_ZOOM = 14

/** El ecuador en metros (Web Mercator) y el radio medio de la Tierra en km (distancias sobre ella). */
internal const val EQUATOR_METERS = 40_075_016.686
internal const val EARTH_RADIUS_KM = 6371.0

/** Descargas a la vez (la política de uso de OSM y OpenFreeMap pide pocas): el mapa y las zonas sin conexión. */
internal const val MAX_DOWNLOADS = 2

/** Hasta dónde llega Web Mercator (más allá la latitud se va al infinito). */
private const val MAX_LAT = 85.05112878

/** Columna de tesela (con fracción) de una longitud en el zoom [z]. */
fun tileX(lon: Double, z: Int): Double = (lon + 180.0) / 360.0 * (1 shl z)

/** Fila de tesela (con fracción) de una latitud en el zoom [z]; 0 es el norte. */
fun tileY(lat: Double, z: Int): Double {
    val r = Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT))
    return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * (1 shl z)
}

/** Lo contrario de [tileX] y [tileY]: la longitud y la latitud de una coordenada de tesela. */
internal fun tileLon(x: Double, z: Int): Double = x / (1 shl z) * 360.0 - 180.0
internal fun tileLat(y: Double, z: Int): Double = Math.toDegrees(atan(sinh(PI * (1 - 2 * y / (1 shl z)))))

/** Cuánto hay que girar de [from] a [to] grados por el lado corto (−180 a 180). */
internal fun shortTurn(from: Float, to: Float): Float = ((to - from) % 360f + 540f) % 360f - 180f

/** Distancia en km sobre la Tierra (haversine). */
fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val h = sin(dLat / 2).let { it * it } + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
    return 2 * EARTH_RADIUS_KM * asin(sqrt(h.coerceAtMost(1.0)))
}

/**
 * Las teselas que se ven en un eje: [center] es la coordenada de tesela de tu posición y lo visible
 * va de [fromPx] a [toPx] px respecto a ella (en el plano del mapa), con teselas de [tilePx] px.
 */
fun tileSpan(center: Double, fromPx: Float, toPx: Float, tilePx: Float): IntRange =
    floor(center + fromPx / tilePx).toInt()..(ceil(center + toPx / tilePx).toInt() - 1)

/** Mínimo de la memoria de teselas: unas 36 de 256 px a 16 bits. */
const val MEMORY_MIN_BYTES = 36 * 256 * 256 * 2

/**
 * Bytes que deben caber en memoria: las [visible] teselas que se ven, de [tileBytes] cada una, más 8 de holgura; y el
 * doble (las del zoom anterior, de respaldo mientras llegan las nuevas) si no pasa de [roomy].
 */
fun memoryBytes(visible: Int, tileBytes: Int, roomy: Int = 0): Int {
    val needed = (visible + 8) * tileBytes
    return maxOf(MEMORY_MIN_BYTES, needed, minOf(needed * 2, roomy))
}

/**
 * El tamaño nuevo de la memoria de teselas, o null si se queda en [current]: crece si no cabe lo que se ve ([visible]
 * teselas de [tileBytes], con la holgura de [memoryBytes] hasta [roomy]) y se encoge si sobra más de la mitad, sin pasar
 * de [cap]. Sin nada visible no se toca (un mapa dejó de pedir antes de que el otro pida: tirarlas haría parpadear).
 */
internal fun memoryResize(current: Int, visible: Int, tileBytes: Int, roomy: Int, cap: Int): Int? {
    if (visible == 0) return null
    val needed = minOf(memoryBytes(visible, tileBytes, roomy), cap)
    return needed.takeIf { it > current || it < current / 2 }
}

/** Lo que pide cada mapa que ve un almacén (el widget y su mapa en grande lo comparten). */
internal class ViewRequests {
    private val views = HashMap<Any, Set<Long>>()

    fun set(view: Any, keys: Set<Long>) {
        views[view] = keys
    }

    /** false si [view] no pedía nada. */
    fun remove(view: Any): Boolean = views.remove(view) != null

    /** Lo que piden todos juntos. */
    fun union(): Set<Long> = views.values.singleOrNull() ?: views.values.flatMapTo(HashSet()) { it }
}

/** Qué hacer con la respuesta HTTP de una tesela. */
enum class TileResponse { OK, NOT_MODIFIED, EMPTY, SLOW_DOWN, BLOCKED, FAILED }

/**
 * 204/404 = ahí no hay nada (no se pregunta en un día); 429/503 = calma unos minutos; 403/418 = nos
 * bloquearon: la política de OSM pide dejar de pedir (una hora); lo demás, reintentar al rato.
 */
fun tileResponse(code: Int): TileResponse = when (code) {
    200 -> TileResponse.OK
    304 -> TileResponse.NOT_MODIFIED
    204, 404 -> TileResponse.EMPTY
    429, 503 -> TileResponse.SLOW_DOWN
    403, 418 -> TileResponse.BLOCKED
    else -> TileResponse.FAILED
}

/** Una tesela en un solo número (z hasta 24, x e y hasta 2^25). */
fun tileKey(z: Int, x: Int, y: Int): Long = (z.toLong() shl 50) or (x.toLong() shl 25) or y.toLong()

fun keyZ(key: Long) = (key ushr 50).toInt()
fun keyX(key: Long) = ((key ushr 25) and 0x1FFFFFF).toInt()
fun keyY(key: Long) = (key and 0x1FFFFFF).toInt()

/** Sin encabezados de caché, una semana (lo mínimo que pide la política de OSM; igual con OpenFreeMap). */
const val DEFAULT_TTL_MS = 7L * 24 * 3600 * 1000

/**
 * Hasta cuándo sirve una tesela según la respuesta: `Cache-Control: max-age` gana; si no,
 * `Expires` (corrido por el reloj del servidor, [date], por si el del equipo está mal);
 * `no-cache`/`no-store` o un `Expires` inválido = ya venció; sin nada, [DEFAULT_TTL_MS].
 */
fun expiresAt(now: Long, cacheControl: String?, expires: String?, date: String? = null): Long {
    if (cacheControl != null) {
        val parts = cacheControl.lowercase(Locale.US).split(',').map { it.trim() }
        if (parts.any { it == "no-cache" || it == "no-store" }) return now
        parts.firstOrNull { it.startsWith("max-age=") }?.substringAfter('=')?.trim('"')?.toLongOrNull()
            ?.let { return now + it.coerceAtLeast(0) * 1000 }
    }
    if (expires != null) {
        val at = httpDate(expires) ?: return now
        val serverNow = date?.let { httpDate(it) } ?: now
        return now + (at - serverNow)
    }
    return now + DEFAULT_TTL_MS
}

/** Fecha HTTP ("Wed, 04 Nov 2026 18:00:00 GMT") en milisegundos; null si no se entiende. */
fun httpDate(text: String): Long? = runCatching {
    SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }.parse(text.trim())?.time
}.getOrNull()

/**
 * Una tesela en disco: lo bajado tal cual ([body]: PNG o vectorial comprimida; vacío = ahí no hay
 * nada) y lo necesario para revalidarla con el servidor.
 */
class TileFile(val expires: Long, val etag: String?, val lastModified: String?, val body: ByteArray) {
    fun encode(): ByteArray = ByteArrayOutputStream(body.size + 64).also { bytes ->
        DataOutputStream(bytes).run {
            writeLong(expires)
            writeUTF(etag.orEmpty())
            writeUTF(lastModified.orEmpty())
            write(body)
        }
    }.toByteArray()

    companion object {
        fun decode(bytes: ByteArray): TileFile? = runCatching {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            val expires = input.readLong()
            val etag = input.readUTF().ifEmpty { null }
            val modified = input.readUTF().ifEmpty { null }
            TileFile(expires, etag, modified, input.readBytes())
        }.getOrNull()
    }
}

/** Un archivo de la caché en disco. */
class CachedTile(val name: String, val size: Long, val modified: Long)

/** Qué borrar si la caché pasa de [max] bytes: las más viejas hasta quedar en [target]. */
fun tilesToTrim(files: List<CachedTile>, max: Long, target: Long): List<String> {
    var total = files.sumOf { it.size }
    if (total <= max) return emptyList()
    val out = ArrayList<String>()
    for (f in files.sortedBy { it.modified }) {
        if (total <= target) break
        total -= f.size
        out += f.name
    }
    return out
}
