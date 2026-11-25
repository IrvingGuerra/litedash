package com.irvingguerra.litedash.feature.map

import android.content.Context
import android.graphics.Bitmap
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

    /** Los nombres de las calles y su halo (ARGB); null = los grises de siempre. */
    val label: Int? get() = null
    val halo: Int? get() = null

    /** Pedir comprimido y guardarlo así (se descomprime al usarlo). */
    val gzip: Boolean

    /** Lo más cerca que se puede ver (las vectoriales se dibujan a cualquier zoom). */
    val maxZoom: Int get() = MAX_ZOOM

    /** La tesela que hay que bajar para mostrar [key] (las vectoriales llegan hasta el zoom 14). */
    fun origin(key: Long): Long = key

    /** Su dirección; null = por ahora no se puede (sin red para saber la versión de los datos). */
    fun url(origin: Long): String?

    /**
     * La imagen de [key] a partir de lo bajado de [origin]; null = lo bajado no sirve (o vino vacío y no hay qué dibujar).
     * [roads] = de dónde salen las calles cuando el mapa bajado no las trae (formato nuevo: el archivo de ruta).
     */
    fun decode(data: ByteArray, key: Long, origin: Long, roads: RoadGraph? = null): ImageBitmap?

    /** Dibuja [key] con lo ya leído de [origin], sin bytes; null = no lo tiene en memoria. */
    fun draw(key: Long, origin: Long): ImageBitmap? = null

    /**
     * Las calles con nombre de la tesela [origin] (ya leída para dibujarla), con su lado en unidades ([VectorTile.extent]);
     * null = no se sabe.
     */
    fun names(origin: Long): TileNames? = null

    /** Nadie ve el mapa: suelta lo que guarda en memoria para dibujar más rápido. */
    fun trim() {}

    /**
     * Cambiaron los datos de donde dibuja (llegó o se cambió un mapa bajado o sus calles): olvida lo leído de las teselas
     * origen que pasen [affected].
     */
    fun forget(affected: (Long) -> Boolean) = trim()
}

/** Las calles con nombre de una tesela y el lado de la tesela en sus unidades. */
class TileNames(val extent: Int, val roads: List<RoadName>)

/**
 * El mapa minimalista: los datos de OpenFreeMap (vectoriales, esquema OpenMapTiles, sin cuenta; para ver el mapa no
 * hay tope, para bajar zonas sí: ver OfflineMaps) dibujados por LiteDash con solo calles, agua y parques, en los colores de
 * [palette] (orillas abajo; rellenos encima, de las chicas a las grandes; puntas redondas).
 * Nítido a cualquier zoom: del 15 al [MAX_ZOOM] se dibuja la parte que toca de la tesela del 14.
 */
class VectorTiles(
    context: Context,
    private val palette: MapPalette,
    private val sizePx: Int,
    private val density: Float,
    private val userAgent: String,
    /**
     * Calles a escala real (la vista de tu auto en la guía): una calle normal mide esto (dp) en el zoom [realZoom] y las
     * demás en proporción; al alejarse se achican como en la vida real. 0 = grosores de mapa (fijos en pantalla).
     */
    private val realRoadDp: Float = 0f,
    private val realZoom: Int = 20,
) : TileSource {
    private val app = context.applicationContext
    override val folder = "map_vector"
    override val attribution = "OpenFreeMap © OpenMapTiles © OpenStreetMap"
    override val attributionShort = "© OpenMapTiles © OpenStreetMap"
    override val sky = palette.land
    override val label = palette.label
    override val halo = palette.halo
    override val gzip = true

    /**
     * Las teselas ya leídas (una del 14 sirve a todas las suyas del 15 en adelante). [TileStore] llama a [draw] y [decode] con
     * el candado del archivo: la misma tesela no se lee dos veces a la vez. Ocho porque el mapa inclinado
     * mezcla orígenes del 12 al 14 (lo lejano con menos detalle) y con cuatro se volvían a leer.
     */
    private val parsed = LruCache<Long, VectorTile>(8)

    /** Los nombres de las calles por tesela origen: pesan poco y se guardan más que las teselas leídas. */
    private val names = LruCache<Long, TileNames>(NAMES_KEPT)

    override fun names(origin: Long): TileNames? = names.get(origin)

    /** Las leídas pesan (miles de formas); los nombres, poco: esos se quedan. */
    override fun trim() = parsed.evictAll()

    override fun forget(affected: (Long) -> Boolean) {
        for (origin in parsed.snapshot().keys) if (affected(origin)) parsed.remove(origin)
        for (origin in names.snapshot().keys) if (affected(origin)) names.remove(origin)
    }

    /** La dirección de los datos (de [template]) en memoria, cuándo se supo y el último intento fallido. */
    private var template: String? = null
    private var templateRead = false
    private var templateAt = 0L
    private var templateFailedAt = 0L
    /** Un hilo está preguntando por la dirección (los demás no esperan). */
    private var templateFetching = false

    override fun origin(key: Long): Long = dataOrigin(key)

    override fun url(origin: Long): String? =
        template()?.replace("{z}", "${keyZ(origin)}")?.replace("{x}", "${keyX(origin)}")?.replace("{y}", "${keyY(origin)}")

    override fun decode(data: ByteArray, key: Long, origin: Long, roads: RoadGraph?): ImageBitmap? {
        // Nombres solo de las teselas del 14 (cerca); las del 12–13 son lo lejano del mapa inclinado.
        val near = keyZ(origin) == DATA_ZOOM
        val tile = parsed.get(origin) ?: (parseVectorTile(unzip(data)) ?: return null).let {
            if (roads == null) it else it.withRoads(GraphTiles.roads(roads, origin, it.extent))
        }.also {
            parsed.put(origin, it)
            if (near) names.put(origin, TileNames(it.extent, it.names))
        }
        return render(tile, key, origin)
    }

    override fun draw(key: Long, origin: Long): ImageBitmap? = parsed.get(origin)?.let { render(it, key, origin) }

    /** El pedazo [key] de la tesela [origin] ya leída. */
    private fun render(tile: VectorTile, key: Long, origin: Long): ImageBitmap {
        val z = keyZ(key)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
        drawMinimal(Canvas(bitmap), tile, 1 shl (z - keyZ(origin)), pieceX(key, origin), pieceY(key, origin), z)
        return bitmap.asImageBitmap()
    }

    /** La parte ([sx], [sy]) de 1/[sub] de la tesela, con grosores de calle según el zoom [z]. */
    private fun drawMinimal(canvas: Canvas, t: VectorTile, sub: Int, sx: Int, sy: Int, z: Int) {
        canvas.drawColor(palette.land)
        val scale = sizePx.toFloat() * sub / t.extent
        canvas.translate(-sx * sizePx.toFloat(), -sy * sizePx.toFloat())
        canvas.scale(scale, scale)
        // Grosor en px de pantalla → en unidades de la tesela; un poco más delgado al alejarse.
        val zoomFactor = 2.0.pow(0.7 * (z - 16)).toFloat().coerceIn(0.35f, 4.5f)
        // Solo lo que toca este pedazo (más el grosor de la calle más ancha): del 17 son 64 pedazos por tesela.
        val window = t.extent.toFloat() / sub
        // A escala real: el grosor de la calle normal en este zoom (px de pantalla), del mismo tamaño que tu auto.
        val real = if (realRoadDp > 0f) realRoadDp * density * 2.0.pow(z - realZoom).toFloat() else 0f
        val margin = (if (real > 0f) real * REAL_RATIO[0] * 1.2f else (ROAD_WIDTH[0] + CASING) * density * zoomFactor) / scale
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
        // A escala real (la vista de tu auto) solo calles: sin manzanas, parques ni agua.
        if (realRoadDp <= 0f) {
            area(t.parks, palette.park)
            area(t.water, palette.water)
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        fun lines(shapes: List<Shape>, widthDp: Float, color: Int, realPx: Float = 0f) {
            if (shapes.isEmpty()) return
            stroke.color = color
            stroke.strokeWidth = if (realPx > 0f) realPx / scale else widthDp * density * zoomFactor / scale
            for (s in shapes) if (s.touches(l, tp, r, b)) canvas.drawPath(s.toPath(path, close = false), stroke)
        }
        if (realRoadDp <= 0f) lines(t.rivers, 2.4f, palette.water)
        // De las chicas a las grandes (las grandes encima); primero el borde de todas, luego el relleno.
        val tiers = if (z >= SERVICE_ZOOM) TIERS_ALL else TIERS_NO_SERVICE
        if (real > 0f) {
            drawRealRoads(canvas, t, tiers, real, z, scale, path, stroke, l, tp, r, b)
            return
        }
        for (tier in tiers) lines(t.roads[tier.ordinal], ROAD_WIDTH[tier.ordinal] + CASING, palette.casings[tier.ordinal])
        for (tier in tiers) lines(t.roads[tier.ordinal], ROAD_WIDTH[tier.ordinal], palette.roads[tier.ordinal])
    }

    /**
     * Las calles a escala real (la vista de tu auto, como Tesla): banqueta, orilla clara, asfalto y, encima, la raya del
     * centro (una amarilla delgada en las de doble sentido, blanca punteada en las anchas de un solo sentido). Por tipo, de las
     * chicas a las grandes: el asfalto de una grande tapa las rayas de la chica en el cruce. [real] = ancho en px de
     * pantalla de una calle con [REAL_RATIO] 1.
     */
    private fun drawRealRoads(
        canvas: Canvas, t: VectorTile, tiers: List<RoadTier>, real: Float, z: Int, scale: Float,
        path: Path, stroke: Paint, l: Float, tp: Float, r: Float, b: Float,
    ) {
        val dark = luminance(palette.land) < 0.2
        val look = if (dark) REAL_DARK else REAL_LIGHT
        // Las rayas y orillas se adelgazan al alejarse, pero nunca menos de un px.
        val line = (1.6f * density * 2.0.pow((z - realZoom).coerceAtMost(0)).toFloat()).coerceAtLeast(1f)
        fun draw(shapes: List<Shape>, widthPx: Float, color: Int, only: BooleanArray? = null, want: Boolean = true) {
            if (shapes.isEmpty() || widthPx <= 0f) return
            stroke.color = color
            stroke.strokeWidth = widthPx / scale
            for ((i, s) in shapes.withIndex()) {
                if (only != null && only[i] != want) continue
                if (s.touches(l, tp, r, b)) canvas.drawPath(s.toPath(path, close = false), stroke)
            }
        }
        // Las banquetas de todas primero: así en los cruces no quedan bordes atravesados.
        for (tier in tiers) draw(t.roads[tier.ordinal], real * REAL_RATIO[tier.ordinal] * (1f + SIDEWALK), look[0])
        for (tier in tiers) {
            val shapes = t.roads[tier.ordinal]
            val w = real * REAL_RATIO[tier.ordinal]
            draw(shapes, w, look[1])
            draw(shapes, w - 2 * line, look[2])
            // Las rayas solo donde la calle es bastante ancha para verse (lejos serían ruido).
            if (tier == RoadTier.SERVICE || w < 14f * density) continue
            val oneway = t.oneway[tier.ordinal]
            // Doble sentido: una sola raya amarilla, delgada.
            draw(shapes, line * 1.1f, look[3], oneway, want = false)
            // Un solo sentido y ancha (dos carriles o más): una raya blanca punteada.
            if (tier.ordinal <= RoadTier.SECONDARY.ordinal) {
                val dash = w * 0.9f / scale
                stroke.pathEffect = android.graphics.DashPathEffect(floatArrayOf(dash, dash * 1.6f), 0f)
                draw(shapes, line, look[4], oneway, want = true)
                stroke.pathEffect = null
            }
        }
    }

    /**
     * La dirección de las teselas: OpenFreeMap publica los datos por versión (su TileJSON dice
     * cuál es la actual). Se guarda una semana; sin red, la última conocida, y no se vuelve a
     * preguntar antes de [TEMPLATE_RETRY_MS]. La red va fuera del candado: mientras un hilo pregunta, los demás
     * se llevan la guardada (o null) sin esperar.
     */
    private fun template(): String? {
        val now = System.currentTimeMillis()
        val file = File(File(app.filesDir, folder), "tilejson")
        synchronized(this) {
            if (!templateRead) {
                templateRead = true
                template = file.takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }
                templateAt = file.lastModified()
            }
            val saved = template
            if (saved != null && now - templateAt < TEMPLATE_MS) return saved
            if (templateFetching || now - templateFailedAt < TEMPLATE_RETRY_MS) return saved
            templateFetching = true
        }
        try {
            val fresh = runCatching {
                httpGet(app, TILEJSON, userAgent) { c, code ->
                    if (code == 200) JSONObject(c.inputStream.bufferedReader().readText()).getJSONArray("tiles").getString(0) else null
                }
            }.getOrNull()
            synchronized(this) {
                if (fresh == null) {
                    templateFailedAt = now
                    return template
                }
                file.parentFile?.mkdirs()
                file.writeText(fresh)
                template = fresh
                templateAt = now
                return fresh
            }
        } finally {
            synchronized(this) { templateFetching = false }
        }
    }

    private companion object {
        /** Las calles de servicio (estacionamientos, privadas) desde este zoom. */
        const val SERVICE_ZOOM = 15
        /** Los tipos de calle en el orden de dibujo (de las chicas a las grandes), con y sin las de servicio. */
        val TIERS_ALL = RoadTier.entries.reversed()
        val TIERS_NO_SERVICE = TIERS_ALL.filter { it != RoadTier.SERVICE }
        const val NAMES_KEPT = 48
        const val TEMPLATE_MS = 7 * 86_400_000L
        const val TEMPLATE_RETRY_MS = 10 * 60_000L
        const val TILEJSON = "https://tiles.openfreemap.org/planet"
        /** Grosor de cada tipo de calle en el zoom 16 (dp), en el orden de [RoadTier]. */
        val ROAD_WIDTH = floatArrayOf(7f, 5.5f, 4.5f, 2.8f, 1.6f)
        /** A escala real, cada tipo de calle contra el ancho que se pide (tu auto): una normal es más ancha que el auto. */
        val REAL_RATIO = floatArrayOf(3.2f, 2.6f, 2.1f, 1.7f, 1.2f)
        /** El borde que separa la calle del fondo (dp de más a cada calle). */
        const val CASING = 1.5f
        /** La banqueta a escala real: esta fracción del ancho de la calle, repartida a los dos lados. */
        const val SIDEWALK = 0.35f
        /** A escala real (ARGB): banqueta, orilla, asfalto, raya amarilla y raya blanca; de noche y de día. */
        val REAL_DARK = intArrayOf(0xFF20252D.toInt(), 0xFF8A919C.toInt(), 0xFF101318.toInt(), 0xFFE9B93C.toInt(), 0xFFB9C0CA.toInt())
        val REAL_LIGHT = intArrayOf(0xFFD9DDE3.toInt(), 0xFF8E96A3.toInt(), 0xFF6B717B.toInt(), 0xFFF2C230.toInt(), 0xFFF4F6F8.toInt())
    }
}

/** La tesela que hay que leer para dibujar [key]: la misma hasta el zoom de los datos; más cerca, la del 14 que la contiene. */
internal fun dataOrigin(key: Long): Long {
    val z = keyZ(key)
    if (z <= DATA_ZOOM) return key
    val shift = z - DATA_ZOOM
    return tileKey(DATA_ZOOM, keyX(key) shr shift, keyY(key) shr shift)
}

/** Qué pedazo de [origin] es [key]: columna y fila dentro de ella (de 0 a 2^(zoom − zoom de origin) − 1). */
internal fun pieceX(key: Long, origin: Long) = keyX(key) - (keyX(origin) shl (keyZ(key) - keyZ(origin)))
internal fun pieceY(key: Long, origin: Long) = keyY(key) - (keyY(origin) shl (keyZ(key) - keyZ(origin)))

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
