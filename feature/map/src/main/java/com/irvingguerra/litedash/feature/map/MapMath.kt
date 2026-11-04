package com.irvingguerra.litedash.feature.map

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

// Cuentas del mapa sin Android (se prueban con JUnit). Basado en LiveMapView de StradaOS
// (GPL-3.0, https://github.com/stradaos): Web Mercator a mano, teselas raster de OpenStreetMap.

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

/** Lo inverso de [tileX]. */
fun lonOf(x: Double, z: Int): Double = x / (1 shl z) * 360.0 - 180.0

/** Lo inverso de [tileY]. */
fun latOf(y: Double, z: Int): Double = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * y / (1 shl z)))))

/**
 * Las teselas que se ven en un eje: [center] es la coordenada de tesela de tu posición, que
 * queda a [anchor] px del borde de una vista de [extent] px con teselas de [tilePx] px.
 */
fun tileRange(center: Double, anchor: Float, extent: Float, tilePx: Float): IntRange =
    floor(center - anchor / tilePx).toInt()..(ceil(center + (extent - anchor) / tilePx).toInt() - 1)

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

/** El toque: aleja un nivel; tras el más lejano vuelve al automático (null). */
fun nextZoom(shown: Int): Int? = if (shown <= MIN_ZOOM) null else shown - 1

/** Una tesela en un solo número (z hasta 24, x e y hasta 2^25). */
fun tileKey(z: Int, x: Int, y: Int): Long = (z.toLong() shl 50) or (x.toLong() shl 25) or y.toLong()

fun keyZ(key: Long) = (key ushr 50).toInt()
fun keyX(key: Long) = ((key ushr 25) and 0x1FFFFFF).toInt()
fun keyY(key: Long) = (key and 0x1FFFFFF).toInt()

/** Sin encabezados de caché, una semana (lo mínimo que pide la política de OSM). */
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

/** Una tesela en disco: su PNG tal cual y lo necesario para revalidarla con el servidor. */
class TileFile(val expires: Long, val etag: String?, val lastModified: String?, val png: ByteArray) {
    fun encode(): ByteArray = ByteArrayOutputStream(png.size + 64).also { bytes ->
        DataOutputStream(bytes).run {
            writeLong(expires)
            writeUTF(etag.orEmpty())
            writeUTF(lastModified.orEmpty())
            write(png)
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
