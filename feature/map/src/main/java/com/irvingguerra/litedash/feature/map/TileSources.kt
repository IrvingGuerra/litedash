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

    /** Lo más cerca que se puede ver (las de OSM llegan al 19; las vectoriales se dibujan a cualquier zoom). */
    val maxZoom: Int get() = MAX_ZOOM

    /** La tesela que hay que bajar para mostrar [key] (las vectoriales llegan hasta el zoom 14). */
    fun origin(key: Long): Long = key

    /** Su dirección; null = por ahora no se puede (sin red para saber la versión de los datos). */
    fun url(origin: Long): String?

    /** La imagen de [key] a partir de lo bajado de [origin]; null = lo bajado no sirve (o vino vacío y no hay qué dibujar). */
    fun decode(data: ByteArray, key: Long, origin: Long): ImageBitmap?

    /**
     * Las calles con nombre de la tesela [origin] (ya leída para dibujarla), con su lado en unidades ([VectorTile.extent]);
     * null = no se sabe (las imágenes de OSM ya traen los nombres dibujados).
     */
    fun names(origin: Long): TileNames? = null
}

/** Las calles con nombre de una tesela y el lado de la tesela en sus unidades. */
class TileNames(val extent: Int, val roads: List<RoadName>)

/** Las teselas de OpenStreetMap ya dibujadas (con todo: calles, negocios, nombres). */
object OsmTiles : TileSource {
    override val folder = "map_tiles"
    override val attribution = "© OpenStreetMap"
    override val recolor = true
    override val gzip = false
    override val maxZoom = 19

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
     * candado del archivo: la misma tesela no se lee dos veces a la vez. Ocho porque el mapa inclinado
     * mezcla orígenes del 12 al 14 (lo lejano con menos detalle) y con cuatro se volvían a leer.
     */
    private val parsed = LruCache<Long, VectorTile>(8)

    /** Los nombres de las calles por tesela origen: pesan poco y se guardan más que las teselas leídas. */
    private val names = LruCache<Long, TileNames>(NAMES_KEPT)

    override fun names(origin: Long): TileNames? = names.get(origin)

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
        // Edificios y nombres solo de las teselas del 14 (cerca); las del 12–13 son lo lejano del mapa inclinado.
        val near = keyZ(origin) == DATA_ZOOM
        val tile = parsed.get(origin) ?: (parseVectorTile(unzip(data), withBuildings = near) ?: return null).also {
            parsed.put(origin, it)
            if (near) names.put(origin, TileNames(it.extent, it.names))
        }
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
        val zoomFactor = 2.0.pow(0.7 * (z - 16)).toFloat().coerceIn(0.35f, 4.5f)
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
        // Las casas solo de cerca: más lejos son puntitos que ensucian.
        if (z >= BUILDINGS_ZOOM) area(t.buildings, palette.building)
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
        const val BUILDINGS_ZOOM = 16
        const val NAMES_KEPT = 48
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
class MinimalPalette(val background: Int, val water: Int, val park: Int, val building: Int, val casing: Int, val roads: IntArray) {
    companion object {
        // Autopistas en ámbar y avenidas un poco más claras: se ve por dónde se cruza la ciudad.
        val LIGHT = MinimalPalette(
            background = 0xFFE9EBEF.toInt(), water = 0xFFC2D3E6.toInt(), park = 0xFFD5E4CF.toInt(), building = 0xFFDCDEE3.toInt(),
            casing = 0xFFCFD3DA.toInt(),
            roads = intArrayOf(0xFFF7CF86.toInt(), 0xFFFCE9C4.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFF7F8FA.toInt()),
        )
        val DARK = MinimalPalette(
            background = 0xFF1B1C20.toInt(), water = 0xFF1C2A3B.toInt(), park = 0xFF1D2A20.toInt(), building = 0xFF24262B.toInt(),
            casing = 0xFF141518.toInt(),
            roads = intArrayOf(0xFF8A6A33.toInt(), 0xFF5E5442.toInt(), 0xFF45474E.toInt(), 0xFF383A40.toInt(), 0xFF2E3035.toInt()),
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
