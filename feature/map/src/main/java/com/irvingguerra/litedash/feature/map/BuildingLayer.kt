package com.irvingguerra.litedash.feature.map

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.cos
import kotlin.math.sqrt

/** Hasta dónde se levantan edificios (m): más lejos ya se pierden en la niebla y solo costarían. */
private const val REACH_M = 260.0

/** A lo más tantos edificios a la vez (los más cercanos): en pantallas de 1 GB debe seguir fluido. */
private const val MAX_BUILDINGS = 150

/** Ningún edificio más alto que esto (m): una torre de 300 m taparía media vista. */
private const val MAX_HEIGHT_M = 120f

/** Desde aquí (fracción de [REACH_M]) se van desvaneciendo hasta desaparecer en la orilla. */
private const val FADE_FROM = 0.6f

/**
 * Los edificios levantados en 3D sobre la vista de tu auto (como Tesla): con su altura real (`render_height`), muros
 * sombreados según hacia dónde dan y la azotea más clara. Solo los cercanos; se arma en [trace] (cada cuadro, sin crear
 * nada) y se dibuja en [draw], cada edificio de atrás hacia adelante (y sus muros también), así los de enfrente tapan.
 */
internal class BuildingLayer {
    // Por edificio: dónde empiezan sus puntos en [ground]/[roof], cuántos tiene, su profundidad y su desvanecido.
    private var start = IntArray(MAX_BUILDINGS)
    private var size = IntArray(MAX_BUILDINGS)
    private var fadeOf = FloatArray(MAX_BUILDINGS)
    private var count = 0
    private var order = LongArray(MAX_BUILDINGS)

    // Los puntos en pantalla: suelo y azotea (x, y) y la profundidad de cada uno; y el tono de cada muro (su orilla).
    private var ground = FloatArray(4096)
    private var roof = FloatArray(4096)
    private var depth = FloatArray(2048)
    private var shade = FloatArray(2048)
    private var used = 0

    // Los candidatos (antes de quedarse con los más cercanos): tesela, edificio y distancia.
    private var candTile = arrayOfNulls<VectorTile>(256)
    private var candOrigin = LongArray(256)
    private var candIndex = IntArray(256)
    private var candDist = LongArray(256)
    private var candCount = 0
    private var visited = LongArray(16)

    /**
     * Las teselas del 14 ya leídas que se han usado (las últimas [KEPT_TILES]): la caché de la fuente es chica y, si suelta
     * una cuyo dibujo sigue en memoria, nadie la vuelve a leer y sus edificios desaparecerían.
     */
    private val kept = object : LinkedHashMap<Long, VectorTile>(KEPT_TILES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, VectorTile>?) = size > KEPT_TILES
    }

    private val point = FloatArray(3)
    private val path = Path()
    private val wallOrder = LongArray(512)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var night = true

    /**
     * Arma los edificios cercanos a tu posición ([atX], [atY]) del cuadro: centro del mapa en ([fx], [fy]) del nivel [zoom]
     * con teselas de [ts] px, las teselas de [plan] ya leídas en [source] y la proyección [projection]. [night] = colores
     * oscuros.
     */
    fun trace(
        source: TileSource, plan: TilePlan, projection: MapProjection, fx: Double, fy: Double, zoom: Int, ts: Float,
        atX: Float, atY: Float, density: Float, night: Boolean,
    ) {
        this.night = night
        count = 0
        used = 0
        candCount = 0
        if (zoom < DATA_ZOOM) return
        val n = (1 shl zoom).toDouble()
        val pxPerM = (n * ts / (EQUATOR_METERS * cos(Math.toRadians(tileLat(fy, zoom))))).toFloat()
        val reach = (REACH_M * pxPerM).toFloat()
        val shift = 1 shl (zoom - DATA_ZOOM)
        // Las teselas del 14 que se ven (varias claves del plan salen de la misma).
        var visitedCount = 0
        for (i in 0 until plan.count) {
            val origin = dataOrigin(plan.key(i))
            if (keyZ(origin) != DATA_ZOOM || seen(origin, visitedCount)) continue
            if (visitedCount == visited.size) visited = visited.copyOf(visitedCount * 2)
            visited[visitedCount++] = origin
            val ox = keyX(origin).toDouble()
            val oy = keyY(origin).toDouble()
            val baseX = atX + ((ox * shift - fx) * ts).toFloat()
            val baseY = atY + ((oy * shift - fy) * ts).toFloat()
            // Una tesela entera más lejos que el alcance ni se recorre (en el centro de una ciudad trae miles).
            val side = ts * shift
            val nearX = (atX - baseX).coerceIn(0f, side) + baseX
            val nearY = (atY - baseY).coerceIn(0f, side) + baseY
            if ((nearX - atX) * (nearX - atX) + (nearY - atY) * (nearY - atY) > reach * reach) continue
            val tile = source.parsed(origin)?.also { kept[origin] = it } ?: kept[origin] ?: continue
            val k = side / tile.extent
            val buildings = tile.buildings
            for (b in buildings.indices) {
                val s = buildings[b]
                if (s.points.size < 6) continue
                val cx = baseX + (s.left + s.right) * 0.5f * k
                val cy = baseY + (s.top + s.bottom) * 0.5f * k
                val d2 = (cx - atX) * (cx - atX) + (cy - atY) * (cy - atY)
                if (d2 > reach * reach) continue
                addCandidate(tile, origin, b, sqrt(d2))
            }
        }
        if (candCount == 0) return
        // Los más cercanos primero; de ahí hasta [MAX_BUILDINGS].
        java.util.Arrays.sort(candDist, 0, candCount)
        for (c in 0 until minOf(candCount, MAX_BUILDINGS)) {
            val packed = candDist[c]
            val slot = (packed and 0xFFFFFFFFL).toInt()
            val dist = java.lang.Float.intBitsToFloat((packed ushr 32).toInt())
            val tile = candTile[slot] ?: continue
            val origin = candOrigin[slot]
            val s = tile.buildings[candIndex[slot]]
            val k = ts * shift / tile.extent
            val baseX = atX + ((keyX(origin).toDouble() * shift - fx) * ts).toFloat()
            val baseY = atY + ((keyY(origin).toDouble() * shift - fy) * ts).toFloat()
            val h = minOf(tile.buildingHeights[candIndex[slot]], MAX_HEIGHT_M) * pxPerM
            traceBuilding(s, k, baseX, baseY, h, projection, (1f - ((dist / reach) - FADE_FROM) / (1f - FADE_FROM)).coerceIn(0f, 1f))
        }
        // Los lejanos primero (se dibujan y los cercanos los tapan).
        for (b in 0 until count) {
            var far = 0f
            for (p in start[b] until start[b] + size[b]) far = maxOf(far, depth[p])
            order[b] = (java.lang.Float.floatToIntBits(far).toLong() shl 32) or b.toLong()
        }
        java.util.Arrays.sort(order, 0, count)
        edge.strokeWidth = density
        // No retener teselas de más (las guarda [kept]).
        java.util.Arrays.fill(candTile, 0, candCount, null)
    }

    private fun seen(origin: Long, visitedCount: Int): Boolean {
        for (c in 0 until visitedCount) if (visited[c] == origin) return true
        return false
    }

    private fun addCandidate(tile: VectorTile, origin: Long, index: Int, dist: Float) {
        if (candCount == candDist.size) {
            val grown = candCount * 2
            candTile = candTile.copyOf(grown)
            candOrigin = candOrigin.copyOf(grown)
            candIndex = candIndex.copyOf(grown)
            candDist = candDist.copyOf(grown)
        }
        candTile[candCount] = tile
        candOrigin[candCount] = origin
        candIndex[candCount] = index
        // Distancia (positiva: sus bits ordenan igual que el número) y el lugar, para ordenar sin crear nada.
        candDist[candCount] = (java.lang.Float.floatToIntBits(dist).toLong() shl 32) or candCount.toLong()
        candCount++
    }

    /** El contorno de afuera de [s] (el primer anillo) levantado [h] px; si algún punto queda detrás de la cámara, nada. */
    private fun traceBuilding(s: Shape, k: Float, baseX: Float, baseY: Float, h: Float, projection: MapProjection, fade: Float) {
        if (fade <= 0f || count == MAX_BUILDINGS) return
        val end = if (s.parts.size > 1) s.parts[1] else s.points.size / 2
        var m = end
        // Los anillos cerrados repiten el primer punto al final: ese no cuenta.
        if (m > 1 && s.points[0] == s.points[2 * (m - 1)] && s.points[1] == s.points[2 * (m - 1) + 1]) m--
        if (m < 3) return
        ensure(used + m)
        // El sentido del anillo (área con signo): dice hacia qué lado queda afuera de cada muro.
        var area = 0f
        for (i in 0 until m) {
            val j = (i + 1) % m
            area += s.points[2 * i] * s.points[2 * j + 1] - s.points[2 * j] * s.points[2 * i + 1]
        }
        val sense = if (area >= 0f) 1f else -1f
        for (i in 0 until m) {
            val x = baseX + s.points[2 * i] * k
            val y = baseY + s.points[2 * i + 1] * k
            if (!projection.toScreen3(x, y, 0f, point)) return
            ground[2 * (used + i)] = point[0]
            ground[2 * (used + i) + 1] = point[1]
            depth[used + i] = point[2]
            if (!projection.toScreen3(x, y, h, point)) return
            roof[2 * (used + i)] = point[0]
            roof[2 * (used + i) + 1] = point[1]
            // El tono del muro que sale de este punto: según cuánto da hacia la luz (del noroeste del mundo).
            val j = (i + 1) % m
            val dx = s.points[2 * j] - s.points[2 * i]
            val dy = s.points[2 * j + 1] - s.points[2 * i + 1]
            val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-3f)
            // Hacia afuera: (dy, −dx) en un anillo en sentido horario (y hacia abajo); al revés en el otro sentido.
            val facing = (sense * dy * LIGHT_X - sense * dx * LIGHT_Y) / len
            shade[used + i] = 0.5f + 0.5f * facing
        }
        start[count] = used
        size[count] = m
        fadeOf[count] = fade
        count++
        used += m
    }

    private fun ensure(points: Int) {
        if (points <= depth.size) return
        val grown = maxOf(points, depth.size * 2)
        ground = ground.copyOf(grown * 2)
        roof = roof.copyOf(grown * 2)
        depth = depth.copyOf(grown)
        shade = shade.copyOf(grown)
    }

    fun draw(canvas: Canvas) {
        if (count == 0) return
        val colors = if (night) NIGHT else DAY
        // Android ≤ 8.1 no dibuja una forma más grande que la textura de la GPU (como pasaba con la ruta): un muro pegado
        // a la cámara se recorta a la vista (con un margen) antes de dibujarlo.
        clip = if (android.os.Build.VERSION.SDK_INT < 28) {
            val m = CLIP_MARGIN_PX
            clipBox.apply { set(-m, -m, canvas.width + m, canvas.height + m) }
        } else null
        for (o in count - 1 downTo 0) {
            val b = (order[o] and 0xFFFFFFFFL).toInt()
            val first = start[b]
            val m = size[b]
            val alpha = (fadeOf[b] * 255).toInt()
            // Sus muros de atrás hacia adelante (por la profundidad de su orilla de abajo).
            val walls = minOf(m, wallOrder.size)
            for (i in 0 until walls) {
                val j = (i + 1) % m
                val far = maxOf(depth[first + i], depth[first + j])
                wallOrder[i] = (java.lang.Float.floatToIntBits(far).toLong() shl 32) or i.toLong()
            }
            java.util.Arrays.sort(wallOrder, 0, walls)
            // Los muros de atrás no se dibujan (los tapa la azotea): de frente, el muro gira igual que el contorno en pantalla.
            var ring = 0f
            for (i in 0 until m) {
                val p = first + i
                val q = first + (i + 1) % m
                ring += ground[2 * p] * ground[2 * q + 1] - ground[2 * q] * ground[2 * p + 1]
            }
            for (w in walls - 1 downTo 0) {
                val i = (wallOrder[w] and 0xFFFFFFFFL).toInt()
                val a = first + i
                val c = first + (i + 1) % m
                if (wallTurn(a, c) * ring <= 0f) continue
                poly[0] = ground[2 * a]; poly[1] = ground[2 * a + 1]
                poly[2] = ground[2 * c]; poly[3] = ground[2 * c + 1]
                poly[4] = roof[2 * c]; poly[5] = roof[2 * c + 1]
                poly[6] = roof[2 * a]; poly[7] = roof[2 * a + 1]
                if (!shape(4)) continue
                fill.color = mix(colors[0], colors[1], shade[a])
                fill.alpha = alpha
                canvas.drawPath(path, fill)
            }
            if (poly.size < 2 * m) poly = FloatArray(4 * m)
            for (i in 0 until m) {
                poly[2 * i] = roof[2 * (first + i)]
                poly[2 * i + 1] = roof[2 * (first + i) + 1]
            }
            if (!shape(m)) continue
            fill.color = colors[2]
            fill.alpha = alpha
            canvas.drawPath(path, fill)
            edge.color = colors[3]
            edge.alpha = (alpha * (colors[3] ushr 24) / 255)
            canvas.drawPath(path, edge)
        }
    }

    /** La vista con margen donde se recortan las formas (solo Android ≤ 8.1; null = sin recorte). */
    private var clip: android.graphics.RectF? = null
    private val clipBox = android.graphics.RectF()
    /** Los puntos de la forma que se va a dibujar (x, y…) y el resultado de recortarla. */
    private var poly = FloatArray(64)
    private var clipped = FloatArray(64)
    private var clipTemp = FloatArray(64)

    /** Arma [path] con los [n] puntos de [poly] (recortados a [clip] si hay); false si no queda nada a la vista. */
    private fun shape(n: Int): Boolean {
        val box = clip
        var pts = poly
        var count = n
        if (box != null) {
            if (clipped.size < 2 * n + 16) {
                clipped = FloatArray(4 * n + 16)
                clipTemp = FloatArray(4 * n + 16)
            }
            count = clipPolygon(poly, n, box.left, box.top, box.right, box.bottom, clipped, clipTemp)
            pts = clipped
            if (count < 3) return false
        }
        path.rewind()
        path.moveTo(pts[0], pts[1])
        for (i in 1 until count) path.lineTo(pts[2 * i], pts[2 * i + 1])
        path.close()
        return true
    }

    /** El área con signo del muro de [a] a [c] en pantalla (suelo y azotea): su signo dice hacia dónde gira. */
    private fun wallTurn(a: Int, c: Int): Float {
        val x0 = ground[2 * a]; val y0 = ground[2 * a + 1]
        val x1 = ground[2 * c]; val y1 = ground[2 * c + 1]
        val x2 = roof[2 * c]; val y2 = roof[2 * c + 1]
        val x3 = roof[2 * a]; val y3 = roof[2 * a + 1]
        return (x0 * y1 - x1 * y0) + (x1 * y2 - x2 * y1) + (x2 * y3 - x3 * y2) + (x3 * y0 - x0 * y3)
    }

    private companion object {
        /** Hacia dónde está la luz (vector unitario en el plano del mundo, y hacia abajo = sur): el noroeste. */
        const val LIGHT_X = -0.7071f
        const val LIGHT_Y = -0.7071f

        /** Cuántas teselas leídas se guardan para los edificios. */
        const val KEPT_TILES = 9

        /** Muro en sombra, muro iluminado, azotea y orilla de la azotea (ARGB); de noche y de día. */
        val NIGHT = intArrayOf(0xFF242931.toInt(), 0xFF464E5C.toInt(), 0xFF5B6372.toInt(), 0x40C8D4E6)
        val DAY = intArrayOf(0xFFAFB6C1.toInt(), 0xFFD3D8DF.toInt(), 0xFFEEF0F3.toInt(), 0x30000000)

        /** Entre [a] y [b] (ARGB opacos) a [t] (0–1). */
        fun mix(a: Int, b: Int, t: Float): Int {
            fun ch(shift: Int) = ((a shr shift and 0xFF) + ((b shr shift and 0xFF) - (a shr shift and 0xFF)) * t).toInt()
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}

/** Margen (px) de la vista al recortar formas en Android ≤ 8.1: la orilla del recorte queda fuera de la pantalla. */
private const val CLIP_MARGIN_PX = 64f

/**
 * Recorta el polígono de [n] puntos de [src] (x, y…) al rectángulo dado (Sutherland–Hodgman, sin crear arreglos: usa
 * [out] y [temp], cada uno con lugar para n + 8 puntos) y lo deja en [out]; devuelve cuántos puntos quedaron.
 */
internal fun clipPolygon(
    src: FloatArray, n: Int, left: Float, top: Float, right: Float, bottom: Float, out: FloatArray, temp: FloatArray,
): Int {
    // Cuatro pasadas alternando buffers: src → temp → out → temp → out.
    var from = src
    var count = n
    for (edge in 0 until 4) {
        val to = if (edge % 2 == 0) temp else out
        var k = 0
        for (i in 0 until count) {
            val j = (i + count - 1) % count
            val cx = from[2 * i]; val cy = from[2 * i + 1]
            val px = from[2 * j]; val py = from[2 * j + 1]
            val cIn = inside(edge, cx, cy, left, top, right, bottom)
            if (cIn != inside(edge, px, py, left, top, right, bottom)) {
                val t = cross(edge, px, py, cx, cy, left, top, right, bottom)
                to[2 * k] = px + (cx - px) * t
                to[2 * k + 1] = py + (cy - py) * t
                k++
            }
            if (cIn) {
                to[2 * k] = cx
                to[2 * k + 1] = cy
                k++
            }
        }
        from = to
        count = k
        if (count == 0) break
    }
    if (from !== out) System.arraycopy(from, 0, out, 0, 2 * count)
    return count
}

private fun inside(edge: Int, x: Float, y: Float, l: Float, t: Float, r: Float, b: Float) = when (edge) {
    0 -> x >= l
    1 -> x <= r
    2 -> y >= t
    else -> y <= b
}

/** Dónde (0–1) cruza el tramo de (x0, y0) a (x1, y1) la orilla [edge]. */
private fun cross(edge: Int, x0: Float, y0: Float, x1: Float, y1: Float, l: Float, t: Float, r: Float, b: Float): Float = when (edge) {
    0 -> (l - x0) / (x1 - x0)
    1 -> (r - x0) / (x1 - x0)
    2 -> (t - y0) / (y1 - y0)
    else -> (b - y0) / (y1 - y0)
}
