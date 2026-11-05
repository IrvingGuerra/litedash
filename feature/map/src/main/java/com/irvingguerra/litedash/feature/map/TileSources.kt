package com.irvingguerra.litedash.feature.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.irvingguerra.litedash.core.ui.Https
import java.io.File
import java.util.zip.GZIPInputStream
import org.json.JSONObject
import kotlin.math.pow

/** De dónde salen las teselas del mapa y cómo se vuelven imagen. */
interface TileSource {
    /** Carpeta de su caché en `filesDir`. */
    val folder: String

    /** Lo que se muestra en la esquina (lo piden las licencias) y la página que abre al tocarlo. */
    val attribution: String
    val attributionUrl: String

    /** Las imágenes vienen claras: en modo oscuro se les aplica el filtro. */
    val recolor: Boolean

    /** Pedir comprimido y guardarlo así (se descomprime al usarlo). */
    val gzip: Boolean

    /** La tesela que hay que bajar para mostrar [key] (las vectoriales llegan hasta el zoom 14). */
    fun origin(key: Long): Long = key

    /** Su dirección; null = por ahora no se puede (sin red para saber la versión de los datos). */
    fun url(origin: Long, userAgent: String): String?

    /** La imagen de [key] a partir de lo bajado de [origin]. */
    fun decode(data: ByteArray, key: Long, origin: Long): ImageBitmap?
}

/** Las teselas de OpenStreetMap ya dibujadas (con todo: calles, negocios, nombres). */
object OsmTiles : TileSource {
    override val folder = "map_tiles"
    override val attribution = "© OpenStreetMap"
    override val attributionUrl = "https://www.openstreetmap.org/copyright"
    override val recolor = true
    override val gzip = false

    override fun url(origin: Long, userAgent: String) = "https://tile.openstreetmap.org/${keyZ(origin)}/${keyX(origin)}/${keyY(origin)}.png"

    override fun decode(data: ByteArray, key: Long, origin: Long): ImageBitmap? {
        // Las teselas no traen transparencia: a 16 bits ocupan la mitad.
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        return BitmapFactory.decodeByteArray(data, 0, data.size, options)?.asImageBitmap()
    }
}

/**
 * El mapa minimalista: los datos de OpenFreeMap (vectoriales, esquema OpenMapTiles, sin cuenta ni
 * límites) dibujados por LiteDash con solo calles, agua y parques, en los colores de [palette].
 * Nítido a cualquier zoom: del 15 al 17 se dibuja la parte que toca de la tesela del 14.
 */
class VectorTiles(context: Context, private val palette: MinimalPalette, private val sizePx: Int, private val density: Float) : TileSource {
    private val app = context.applicationContext
    override val folder = "map_vector"
    override val attribution = "© OpenMapTiles © OpenStreetMap"
    override val attributionUrl = "https://openfreemap.org"
    override val recolor = false
    override val gzip = true

    /** Las teselas ya leídas (una del 14 sirve a varias del 15–17). */
    private val parsed = LruCache<Long, VectorTile>(4)

    override fun origin(key: Long): Long {
        val z = keyZ(key)
        if (z <= DATA_ZOOM) return key
        val shift = z - DATA_ZOOM
        return tileKey(DATA_ZOOM, keyX(key) shr shift, keyY(key) shr shift)
    }

    override fun url(origin: Long, userAgent: String): String? =
        template(userAgent)?.replace("{z}", "${keyZ(origin)}")?.replace("{x}", "${keyX(origin)}")?.replace("{y}", "${keyY(origin)}")

    override fun decode(data: ByteArray, key: Long, origin: Long): ImageBitmap? {
        val tile = synchronized(parsed) { parsed.get(origin) } ?: parseVectorTile(unzip(data)).also { synchronized(parsed) { parsed.put(origin, it) } }
        val z = keyZ(key)
        val sub = 1 shl (z - keyZ(origin))
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
        drawMinimal(Canvas(bitmap), tile, sub, keyX(key) - (keyX(origin) shl (z - keyZ(origin))), keyY(key) - (keyY(origin) shl (z - keyZ(origin))), z)
        return bitmap.asImageBitmap()
    }

    /** La parte ([sx], [sy]) de 1/[sub] de la tesela, con grosores de calle según el zoom [z]. */
    private fun drawMinimal(canvas: Canvas, t: VectorTile, sub: Int, sx: Int, sy: Int, z: Int) {
        canvas.drawColor(palette.background)
        val scale = sizePx.toFloat() * sub / t.extent
        canvas.translate(-sx * sizePx.toFloat(), -sy * sizePx.toFloat())
        canvas.scale(scale, scale)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val path = Path()
        fun area(shapes: List<Shape>, color: Int) {
            fill.color = color
            for (s in shapes) canvas.drawPath(s.toPath(path, close = true), fill)
        }
        area(t.parks, palette.park)
        area(t.water, palette.water)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        // Grosor en px de pantalla → en unidades de la tesela; un poco más delgado al alejarse.
        val zoomFactor = 2.0.pow(0.7 * (z - 16)).toFloat().coerceIn(0.35f, 1.6f)
        fun lines(shapes: List<Shape>, widthDp: Float, color: Int) {
            if (shapes.isEmpty()) return
            stroke.color = color
            stroke.strokeWidth = widthDp * density * zoomFactor / scale
            for (s in shapes) canvas.drawPath(s.toPath(path, close = false), stroke)
        }
        lines(t.rivers, 2.4f, palette.water)
        // De las chicas a las grandes (las grandes encima); primero el borde de todas, luego el relleno.
        val tiers = RoadTier.entries.reversed().filter { it != RoadTier.SERVICE || z >= 15 }
        for (tier in tiers) lines(t.roads[tier.ordinal], ROAD_WIDTH[tier.ordinal] + CASING, palette.casing)
        for (tier in tiers) lines(t.roads[tier.ordinal], ROAD_WIDTH[tier.ordinal], palette.roads[tier.ordinal])
    }

    /**
     * La dirección de las teselas: OpenFreeMap publica los datos por versión (su TileJSON dice
     * cuál es la actual). Se guarda una semana; sin red, la última conocida.
     */
    @Synchronized
    private fun template(userAgent: String): String? {
        val file = File(File(app.filesDir, folder), "tilejson")
        val now = System.currentTimeMillis()
        val saved = file.takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }
        if (saved != null && now - file.lastModified() < TEMPLATE_DAYS * 86_400_000L) return saved
        val fresh = runCatching {
            val c = Https.open(app, TILEJSON)
            try {
                c.connectTimeout = 8_000
                c.readTimeout = 10_000
                c.setRequestProperty("User-Agent", userAgent)
                if (c.responseCode != 200) return@runCatching null
                JSONObject(c.inputStream.bufferedReader().readText()).getJSONArray("tiles").getString(0)
            } finally {
                c.disconnect()
            }
        }.getOrNull()
        if (fresh != null) {
            file.parentFile?.mkdirs()
            file.writeText(fresh)
        }
        return fresh ?: saved
    }

    private companion object {
        const val DATA_ZOOM = 14
        const val TEMPLATE_DAYS = 7
        const val TILEJSON = "https://tiles.openfreemap.org/planet"
        /** Grosor de cada tipo de calle en el zoom 16 (dp), en el orden de [RoadTier]. */
        val ROAD_WIDTH = floatArrayOf(7f, 5.5f, 4.5f, 2.8f, 1.6f)
        /** El borde que separa la calle del fondo (dp de más a cada calle). */
        const val CASING = 1.5f
    }
}

/** Los colores del mapa minimalista (ARGB). [roads] en el orden de [RoadTier]. */
class MinimalPalette(val background: Int, val water: Int, val park: Int, val casing: Int, val roads: IntArray) {
    companion object {
        val LIGHT = MinimalPalette(
            background = 0xFFE9EBEF.toInt(), water = 0xFFC6D4E4.toInt(), park = 0xFFDCE6D7.toInt(), casing = 0xFFD3D6DC.toInt(),
            roads = intArrayOf(0xFFF6D9A6.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFF7F8FA.toInt()),
        )
        val DARK = MinimalPalette(
            background = 0xFF1B1C20.toInt(), water = 0xFF1D2836.toInt(), park = 0xFF1E2620.toInt(), casing = 0xFF16171A.toInt(),
            roads = intArrayOf(0xFF6E5A35.toInt(), 0xFF4A4C53.toInt(), 0xFF3F4148.toInt(), 0xFF34363C.toInt(), 0xFF2C2E33.toInt()),
        )
    }
}

private fun Shape.toPath(path: Path, close: Boolean): Path {
    path.rewind()
    for (p in parts.indices) {
        val start = parts[p]
        val end = if (p + 1 < parts.size) parts[p + 1] else points.size / 2
        path.moveTo(points[start * 2], points[start * 2 + 1])
        for (i in start + 1 until end) path.lineTo(points[i * 2], points[i * 2 + 1])
        if (close) path.close()
    }
    return path
}

/** Lo bajado comprimido (gzip) a los datos; si no venía comprimido, tal cual. */
private fun unzip(data: ByteArray): ByteArray =
    if (data.size > 2 && data[0] == 0x1F.toByte() && data[1] == 0x8B.toByte()) GZIPInputStream(data.inputStream()).use { it.readBytes() } else data
