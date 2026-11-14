package com.irvingguerra.litedash.feature.map

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

// Cuentas del mapa sin Android (se prueban con JUnit). Basado en LiveMapView de StradaOS
// (GPL-3.0, https://github.com/stradaos): Web Mercator a mano; las teselas, raster de OpenStreetMap o vectoriales de OpenFreeMap.

/** El zoom más cercano y el más lejano que se usan (el toque cicla entre ellos). */
const val MAX_ZOOM = 17
const val MIN_ZOOM = 14

/** Hasta dónde llega Web Mercator (más allá la latitud se va al infinito). */
private const val MAX_LAT = 85.05112878

/** Columna de tesela (con fracción) de una longitud en el zoom [z]. */
fun tileX(lon: Double, z: Int): Double = (lon + 180.0) / 360.0 * (1 shl z)

/** Fila de tesela (con fracción) de una latitud en el zoom [z]; 0 es el norte. */
fun tileY(lat: Double, z: Int): Double {
    val r = Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT))
    return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * (1 shl z)
}

/**
 * Las teselas que se ven en un eje: [center] es la coordenada de tesela de tu posición y lo visible
 * va de [fromPx] a [toPx] px respecto a ella (en el plano del mapa), con teselas de [tilePx] px.
 */
fun tileSpan(center: Double, fromPx: Float, toPx: Float, tilePx: Float): IntRange =
    floor(center + fromPx / tilePx).toInt()..(ceil(center + toPx / tilePx).toInt() - 1)

/**
 * Zoom según la velocidad: 17 en ciudad, 16 desde 35 km/h, 15 desde 70. Al frenar regresa
 * 5 km/h abajo del umbral para no brincar de zoom en zoom alrededor de él.
 */
fun autoZoom(kmh: Float?, current: Int): Int {
    val v = kmh ?: 0f
    val faster = if (v > 70f) 15 else if (v > 35f) 16 else 17
    val slower = if (v > 65f) 15 else if (v > 30f) 16 else 17
    return if (faster < current) faster else if (slower > current) slower else current
}

/** Mínimo de la memoria de teselas: unas 36 de OSM (256 px a 16 bits). */
const val MEMORY_MIN_BYTES = 36 * 256 * 256 * 2

/** Bytes que deben caber en memoria: las [visible] teselas que se ven, de [tileBytes] cada una, más 8 de holgura. */
fun memoryBytes(visible: Int, tileBytes: Int): Int = maxOf(MEMORY_MIN_BYTES, (visible + 8) * tileBytes)

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
