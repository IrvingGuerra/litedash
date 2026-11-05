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
import java.io.File
import java.util.zip.GZIPInputStream
import org.json.JSONObject
import kotlin.math.pow

/** De dónde salen las teselas del mapa y cómo se vuelven imagen. */
interface TileSource {
    /** Carpeta de su caché en `filesDir`. */
    val folder: String

    /** Los créditos que se muestran en la esquina (lo piden las licencias) y su versión corta para vistas angostas. */
    val attribution: String
    val attributionShort: String get() = attribution

    /** El cielo del mapa inclinado (ARGB); null = el gris claro u oscuro de siempre. */
    val sky: Int? get() = null

    /** Las imágenes vienen claras: en modo oscuro se les aplica el filtro. */
    val recolor: Boolean

    /** Pedir comprimido y guardarlo así (se descomprime al usarlo). */
    val gzip: Boolean

    /** La tesela que hay que bajar para mostrar [key] (las vectoriales llegan hasta el zoom 14). */
    fun origin(key: Long): Long = key

    /** Su dirección; null = por ahora no se puede (sin red para saber la versión de los datos). */
    fun url(origin: Long): String?

    /** La imagen de [key] a partir de lo bajado de [origin]; null = lo bajado no sirve (o vino vacío y no hay qué dibujar). */
    fun decode(data: ByteArray, key: Long, origin: Long): ImageBitmap?
}

/** Las teselas de OpenStreetMap ya dibujadas (con todo: calles, negocios, nombres). */
object OsmTiles : TileSource {
    override val folder = "map_tiles"
    override val attribution = "© OpenStreetMap"
    override val recolor = true
    override val gzip = false

    override fun url(origin: Long) = "https://tile.openstreetmap.org/${keyZ(origin)}/${keyX(origin)}/${keyY(origin)}.png"

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
class VectorTiles(
    context: Context,
    private val palette: MinimalPalette,
    private val sizePx: Int,
    private val density: Float,
    private val userAgent: String,
) : TileSource {
    private val app = context.applicationContext
    override val folder = "map_vector"
    override val attribution = "OpenFreeMap © OpenMapTiles © OpenStreetMap"
    override val attributionShort = "© OpenMapTiles © OpenStreetMap"
    override val sky = palette.background
    override val recolor = false
    override val gzip = true

    /**
     * Las teselas ya leídas (una del 14 sirve a varias del 15–17). [TileStore] llama a [decode] con el
     * candado del archivo: la misma tesela no se lee dos veces a la vez.
     */
    private val parsed = LruCache<Long, VectorTile>(4)

    /** La dirección de los datos (de [template]) en memoria, cuándo se supo y el último intento fallido. */
    private var template: String? = null
    private var templateRead = false
    private var templateAt = 0L
    private var templateFailedAt = 0L

    override fun origin(key: Long): Long {
        val z = keyZ(key)
        if (z <= DATA_ZOOM) return key
        val shift = z - DATA_ZOOM
        return tileKey(DATA_ZOOM, keyX(key) shr shift, keyY(key) shr shift)
    }

    override fun url(origin: Long): String? =
        template()?.replace("{z}", "${keyZ(origin)}")?.replace("{x}", "${keyX(origin)}")?.replace("{y}", "${keyY(origin)}")

    override fun decode(data: ByteArray, key: Long, origin: Long): ImageBitmap? {
        val tile = parsed.get(origin) ?: (parseVectorTile(unzip(data)) ?: return null).also { parsed.put(origin, it) }
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
        // Grosor en px de pantalla → en unidades de la tesela; un poco más delgado al alejarse.
        val zoomFactor = 2.0.pow(0.7 * (z - 16)).toFloat().coerceIn(0.35f, 1.6f)
        // Solo lo que toca este pedazo (más el grosor de la calle más ancha): del 17 son 64 pedazos por tesela.
        val window = t.extent.toFloat() / sub
        val margin = (ROAD_WIDTH[0] + CASING) * density * zoomFactor / scale
        val l = sx * window - margin
        val tp = sy * window - margin
        val r = (sx + 1) * window + margin
        val b = (sy + 1) * window + margin
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val path = Path()
        fun area(shapes: List<Shape>, color: Int) {
            fill.color = color
            for (s in shapes) if (s.touches(l, tp, r, b)) canvas.drawPath(s.toPath(path, close = true), fill)
        }
        area(t.parks, palette.park)
        area(t.water, palette.water)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        fun lines(shapes: List<Shape>, widthDp: Float, color: Int) {
            if (shapes.isEmpty()) return
            stroke.color = color
            stroke.strokeWidth = widthDp * density * zoomFactor / scale
            for (s in shapes) if (s.touches(l, tp, r, b)) canvas.drawPath(s.toPath(path, close = false), stroke)
        }
        lines(t.rivers, 2.4f, palette.water)
        // De las chicas a las grandes (las grandes encima); primero el borde de todas, luego el relleno.
        val tiers = RoadTier.entries.reversed().filter { it != RoadTier.SERVICE || z >= 15 }
        for (tier in tiers) lines(t.roads[tier.ordinal], ROAD_WIDTH[tier.ordinal] + CASING, palette.casing)
        for (tier in tiers) lines(t.roads[tier.ordinal], ROAD_WIDTH[tier.ordinal], palette.roads[tier.ordinal])
    }

    /**
     * La dirección de las teselas: OpenFreeMap publica los datos por versión (su TileJSON dice
     * cuál es la actual). Se guarda una semana; sin red, la última conocida, y no se vuelve a
     * preguntar antes de [TEMPLATE_RETRY_MS].
     */
    @Synchronized
    private fun template(): String? {
        val now = System.currentTimeMillis()
        val file = File(File(app.filesDir, folder), "tilejson")
        if (!templateRead) {
            templateRead = true
            template = file.takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }
            templateAt = file.lastModified()
        }
        val saved = template
        if (saved != null && now - templateAt < TEMPLATE_MS) return saved
        if (now - templateFailedAt < TEMPLATE_RETRY_MS) return saved
        val fresh = runCatching {
            httpGet(app, TILEJSON, userAgent) { c, code ->
                if (code == 200) JSONObject(c.inputStream.bufferedReader().readText()).getJSONArray("tiles").getString(0) else null
            }
        }.getOrNull()
        if (fresh == null) {
            templateFailedAt = now
            return saved
        }
        file.parentFile?.mkdirs()
        file.writeText(fresh)
        template = fresh
        templateAt = now
        return fresh
    }

    private companion object {
        const val DATA_ZOOM = 14
        const val TEMPLATE_MS = 7 * 86_400_000L
        const val TEMPLATE_RETRY_MS = 10 * 60_000L
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
