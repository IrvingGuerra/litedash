package com.irvingguerra.litedash.feature.map

import android.graphics.Matrix as AndroidMatrix
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.cos
import kotlin.math.roundToInt

/** A lo más tantos nombres de calles a la vez (más, es ruido). */
private const val MAX_LABELS = 14

/** Lo lejano no baja de este zoom (más allá, las calles ya ni se distinguen). */
private const val LOD_MIN_ZOOM = 12

/** Hasta cuántos zooms menos se busca una tesela de respaldo (al centrar se cruzan varios niveles de corrido). */
internal const val FALLBACK_LEVELS = 5

/**
 * Lo que necesita un mapa para dibujarse, armado una vez y reusado cuadro a cuadro: la proyección, las teselas, la
 * matriz, los nombres, el desvanecido y la flecha. Solo se rehace lo que cambió (moverte, el zoom o la escala, el
 * tamaño, una tesela con nombres nueva); al dibujar no se crea nada. Todo en el hilo principal.
 */
internal class MapFrame : ScreenPoint {
    val projection = MapProjection()
    val plan = TilePlan()
    private val matrix = AndroidMatrix()

    /** Lo que mide el mundo entero en px del plano del mapa (tesela × 2^zoom): un arrastre se vuelve movimiento. */
    var worldPx = 0.0
        private set

    /** Para convertir arrastres sin crear arreglos. */
    val dragFrom = FloatArray(3)
    val dragTo = FloatArray(3)

    /** Las teselas que hay que pedir (cambia solo cuando cambian): el mapa las pasa al almacén fuera del dibujo. */
    var wanted by mutableStateOf<Set<Long>>(emptySet())
        private set
    private var sorted = LongArray(0)
    private var requested = LongArray(0)
    private var requestedCount = -1

    // Lo que dio el último cálculo del plan (para no repetirlo si no cambió).
    private var fx = Double.NaN
    private var fy = Double.NaN
    private var zoom = -1
    private var ts = 0f
    private var atX = 0f
    private var atY = 0f
    private var width = -1f
    private var height = -1f

    /** Tu posición en pantalla (fuera de la vista si moviste el mapa lejos). */
    private val you = FloatArray(3)
    private var youShown = false
    private var arrowDegrees = 0f
    private var squash = 1f

    /** El círculo de la zona (px); 0 = sin círculo. */
    private var ring = 0f

    private var fade: Brush? = null
    private var fadeSky = Color.Unspecified
    private var fadeTilt = Float.NaN
    private var fadeEnd = Float.NaN

    private val labels = LabelCache()
    private val layout = LabelLayout(MAX_LABELS)
    private var labelRuns: List<LabelRun> = emptyList()
    private var labelPaint: android.graphics.Paint? = null

    /** La flecha apuntando al norte con su centro en (0, 0) y sus trazos, por densidad. */
    private var arrowFor = 0f
    private var arrow = Path()
    private var arrowStroke = Stroke(0f)
    private var ringStroke = Stroke(0f)
    private var unit = 1f

    /** El pincel de los nombres (se rehace solo si cambia el tamaño o un color). */
    fun textPaint(size: Float, color: Color, halo: Color) = labels.paint(size, color, halo)

    /** Ya hay un cuadro de este tamaño: tapado, se sigue dibujando ese sin leer nada. */
    fun ready(width: Float, height: Float) = width == this.width && height == this.height && zoom >= 0

    /**
     * Arma el cuadro: vista de [width] × [height] a [density], tu posición en ([atX], [atY]) girada [turn] e inclinada
     * [tilt]; el centro del mapa en ([fx], [fy]) del nivel [zoom] con teselas de [ts] px; [panX]/[panY] = cuánto se movió
     * (fracción del mundo). [names] = la fuente si trae nombres de calles; [labelTs] = la tesela a su tamaño.
     */
    fun update(
        width: Float, height: Float, density: Float, atX: Float, atY: Float, turn: Float, tilt: Float,
        fx: Double, fy: Double, zoom: Int, ts: Float, panX: Double, panY: Double,
        names: TileSource?, labelTs: Float, text: android.graphics.Paint,
        sky: Color, arrowBearing: Float, ringKm: Float?, centerLat: Double,
    ) {
        if (density != arrowFor) {
            arrowFor = density
            unit = density
            arrow = arrowPath(density)
            arrowStroke = Stroke(3 * density)
            ringStroke = Stroke(2 * density)
        }
        val turned = projection.set(width, height, atX, atY, turn, tilt, density)
        if (turned) matrix.setValues(projection.values)
        val moved = turned || fx != this.fx || fy != this.fy || zoom != this.zoom || ts != this.ts
        if (moved) {
            this.fx = fx; this.fy = fy; this.zoom = zoom; this.ts = ts
            this.atX = atX; this.atY = atY; this.width = width; this.height = height
            plan.fill(projection, fx, fy, zoom, atX, atY, ts)
            if (keysChanged()) wanted = plan.orderedKeys()
        }
        val n = (1 shl zoom).toDouble()
        worldPx = ts * n
        youShown = projection.toScreen(atX - (panX * n * ts).toFloat(), atY - (panY * n * ts).toFloat(), you)
        arrowDegrees = arrowBearing
        squash = 1f - 0.3f * tilt
        // El círculo de la zona: metros por px del plano en el centro del mapa.
        ring = if (ringKm == null) 0f else (ringKm * 1000 / (EQUATOR_METERS * cos(Math.toRadians(centerLat)) / (n * ts))).toFloat()
        updateFade(sky, tilt, height)
        // Qué nombres caben se decide con la tesela a su tamaño (no cambia con cada cuadro del zoom).
        val runs = if (names != null) labels.runs(names, plan, zoom, labelTs, density, text) else emptyList()
        if (moved || runs !== labelRuns || text !== labelPaint) {
            labelRuns = runs
            labelPaint = text
            val top = projection.edge.coerceAtLeast(0f) + height * 0.12f * tilt
            layout.place(runs, width, height, top, text.textSize, density, this)
        }
    }

    /** Un punto en teselas del zoom del mapa, en la pantalla (para los nombres). */
    override fun toScreen(x: Double, y: Double, out: FloatArray) =
        projection.toScreen(atX + ((x - fx) * ts).toFloat(), atY + ((y - fy) * ts).toFloat(), out)

    /** Lo lejano se funde con el cielo: sólido hasta donde se acaba el mapa (en pantalla) y de ahí se desvanece. */
    private fun updateFade(sky: Color, tilt: Float, height: Float) {
        if (tilt <= 0f) {
            fade = null
            return
        }
        val end = (projection.edge / height).coerceIn(0f, 0.9f)
        if (fade != null && sky == fadeSky && tilt == fadeTilt && end == fadeEnd) return
        fadeSky = sky; fadeTilt = tilt; fadeEnd = end
        val solid = sky.copy(alpha = tilt)
        fade = Brush.verticalGradient(0f to solid, end to solid, (end + 0.15f).coerceAtMost(1f) to sky.copy(alpha = 0f))
    }

    /** ¿Cambió el conjunto de teselas desde lo último que se pidió? (sin importar el orden) */
    private fun keysChanged(): Boolean {
        val count = plan.count
        if (sorted.size < count) sorted = LongArray(count * 2)
        plan.keysInto(sorted)
        java.util.Arrays.sort(sorted, 0, count)
        var same = count == requestedCount
        if (same) for (i in 0 until count) if (sorted[i] != requested[i]) {
            same = false
            break
        }
        if (same) return false
        val t = requested
        requested = sorted
        sorted = t
        requestedCount = count
        return true
    }

    fun draw(scope: DrawScope, store: TileStore, filter: ColorFilter?, accent: Color) = with(scope) {
        drawIntoCanvas { canvas ->
            canvas.save()
            canvas.nativeCanvas.concat(matrix)
            drawTiles(store, plan, filter)
            canvas.restore()
        }
        fade?.let { drawRect(it) }
        if (ring > 0f) {
            val center = Offset(atX, atY)
            drawCircle(accent.copy(alpha = 0.12f), ring, center)
            drawCircle(accent, ring, center, style = ringStroke)
        }
        labelPaint?.let { paint -> drawIntoCanvas { drawLabels(it.nativeCanvas, layout.placed, paint) } }
        if (youShown) drawArrow(accent)
    }

    /** Tu posición: la flecha con el rumbo (color de acento). Inclinado, un poco acostada. */
    private fun DrawScope.drawArrow(accent: Color) {
        translate(you[0], you[1]) {
            scale(1f, squash, Offset.Zero) {
                drawCircle(accent.copy(alpha = 0.22f), 34 * unit, Offset.Zero)
                rotate(arrowDegrees, Offset.Zero) {
                    drawPath(arrow, accent)
                    drawPath(arrow, Color.White, style = arrowStroke)
                }
            }
        }
    }
}

/** La flecha apuntando al norte con su centro en (0, 0); [u] = 1 dp. */
private fun arrowPath(u: Float) = Path().apply {
    moveTo(0f, -22 * u)
    lineTo(16 * u, 17 * u)
    lineTo(0f, 8 * u)
    lineTo(-16 * u, 17 * u)
    close()
}

/**
 * Las teselas a dibujar alrededor del centro, de lo lejano a lo cercano (lo cercano encima): su zoom, columna, fila y
 * dónde van en el plano del mapa (en px, ya redondeado), las primeras [count] de cada arreglo. Inclinado, lo lejano va
 * con menos detalle ([detailDrop]). Se rellena cada vez sin crear nada (los arreglos solo crecen).
 */
internal class TilePlan {
    var count = 0
        private set
    var z = IntArray(0); private set
    var x = IntArray(0); private set
    var y = IntArray(0); private set
    var left = IntArray(0); private set
    var top = IntArray(0); private set
    var right = IntArray(0); private set
    var bottom = IntArray(0); private set
    private var keys = LongArray(0)

    // Las candidatas, una vez cada una, antes de ordenarlas.
    private val span = IntList(256)
    private var candidates = 0
    private var cz = IntArray(0)
    private var cx = IntArray(0)
    private var cy = IntArray(0)
    private var ck = LongArray(0)

    fun key(i: Int) = keys[i]

    fun keysInto(out: LongArray) = keys.copyInto(out, 0, 0, count)

    /** Las claves en el orden de dibujo: lo cercano al final (el almacén descarga primero lo último que se pidió). */
    fun orderedKeys(): Set<Long> = LinkedHashSet<Long>(count * 2).also { for (i in 0 until count) it += keys[i] }

    /** Las teselas de [projection] con el centro en ([fx], [fy]) del zoom [zoom], tu posición en ([atX], [atY]) y teselas de [ts] px. */
    fun fill(projection: MapProjection, fx: Double, fy: Double, zoom: Int, atX: Float, atY: Float, ts: Float) {
        projection.visibleTiles(fx, fy, ts, span)
        // Cada tesela visible, o la de menos detalle que la cubre (una sola vez aunque cubra varias).
        candidates = 0
        for (i in 0 until span.size step 2) {
            val tx = span[i]
            val ty = span[i + 1]
            val drop = detailDrop(projection.scaleOf(fx, fy, ts, tx, ty)).coerceAtMost(zoom - LOD_MIN_ZOOM).coerceAtLeast(0)
            val dz = zoom - drop
            val ax = Math.floorDiv(tx, 1 shl drop)
            val ay = Math.floorDiv(ty, 1 shl drop)
            val n = 1 shl dz
            if (ay < 0 || ay >= n) continue
            val k = tileKey(dz, Math.floorMod(ax, n), ay)
            if (picked(k)) continue
            if (candidates == cz.size) growCandidates()
            cz[candidates] = dz
            cx[candidates] = ax
            cy[candidates] = ay
            ck[candidates] = k
            candidates++
        }
        if (z.size < candidates) grow(candidates)
        // Lo lejano (menos zoom) primero: lo cercano se dibuja encima y tapa las orillas.
        count = 0
        for (drop in 2 downTo 0) {
            val dz = zoom - drop
            for (i in 0 until candidates) {
                if (cz[i] != dz) continue
                val ax = cx[i]
                val ay = cy[i]
                val scale = (1 shl drop).toDouble()
                val size = ts * scale
                z[count] = dz
                x[count] = Math.floorMod(ax, 1 shl dz)
                y[count] = ay
                // Bordes redondeados a píxel con el de la siguiente: sin rayas entre teselas.
                left[count] = (atX + (ax * scale - fx) * ts).roundToInt()
                top[count] = (atY + (ay * scale - fy) * ts).roundToInt()
                right[count] = (atX + (ax * scale - fx) * ts + size).roundToInt()
                bottom[count] = (atY + (ay * scale - fy) * ts + size).roundToInt()
                keys[count] = ck[i]
                count++
            }
        }
    }

    /** ¿Ya está [k] entre las candidatas? Son a lo más ~120: buscar de corrido cuesta menos que una tabla. */
    private fun picked(k: Long): Boolean {
        for (i in 0 until candidates) if (ck[i] == k) return true
        return false
    }

    private fun growCandidates() {
        val size = maxOf(64, cz.size * 2)
        cz = cz.copyOf(size); cx = cx.copyOf(size); cy = cy.copyOf(size); ck = ck.copyOf(size)
    }

    private fun grow(size: Int) {
        z = IntArray(size); x = IntArray(size); y = IntArray(size)
        left = IntArray(size); top = IntArray(size); right = IntArray(size); bottom = IntArray(size)
        keys = LongArray(size)
    }
}

private fun DrawScope.drawTiles(store: TileStore, plan: TilePlan, filter: ColorFilter?) {
    for (i in 0 until plan.count) {
        val offset = IntOffset(plan.left[i], plan.top[i])
        val dst = IntSize(plan.right[i] - plan.left[i], plan.bottom[i] - plan.top[i])
        val image = store.tile(plan.key(i))
        if (image != null) {
            drawImage(image, dstOffset = offset, dstSize = dst, colorFilter = filter, filterQuality = FilterQuality.Low)
            continue
        }
        val z = plan.z[i]
        val x = plan.x[i]
        val y = plan.y[i]
        // Mientras llega (al cambiar de zoom no se ve vacío): su pedazo de la tesela más cercana de un zoom menor…
        val up = parentFallback(z, x, y) { store.tile(it) != null }
        if (up > 0) {
            val parent = store.tile(tileKey(z - up, x shr up, y shr up)) ?: continue
            val part = parent.width shr up
            if (part >= 1) {
                val mask = (1 shl up) - 1
                drawImage(
                    parent, IntOffset((x and mask) * part, (y and mask) * part), IntSize(part, part),
                    offset, dst, colorFilter = filter, filterQuality = FilterQuality.Low,
                )
                continue
            }
        }
        // …o, al alejar, las del zoom siguiente que ya estaban (cada una, su cuarto).
        val halfW = dst.width / 2
        val halfH = dst.height / 2
        for (quarter in 0 until 4) {
            val cx = quarter and 1
            val cy = quarter shr 1
            val child = store.tile(tileKey(z + 1, x * 2 + cx, y * 2 + cy)) ?: continue
            drawImage(
                child, dstOffset = IntOffset(offset.x + cx * halfW, offset.y + cy * halfH),
                dstSize = IntSize(if (cx == 0) halfW else dst.width - halfW, if (cy == 0) halfH else dst.height - halfH),
                colorFilter = filter, filterQuality = FilterQuality.Low,
            )
        }
    }
}

/**
 * Cuántos zooms arriba está la tesela de respaldo más cercana de ([z], [x], [y]) que ya está ([has]), hasta
 * [FALLBACK_LEVELS] (y no más arriba del zoom 0); 0 = ninguna.
 */
internal inline fun parentFallback(z: Int, x: Int, y: Int, has: (Long) -> Boolean): Int {
    for (up in 1..minOf(FALLBACK_LEVELS, z)) if (has(tileKey(z - up, x shr up, y shr up))) return up
    return 0
}

/** Los nombres ya acomodados, derechos y centrados en su tramo. */
private fun drawLabels(canvas: android.graphics.Canvas, labels: List<PlacedLabel>, paint: android.graphics.Paint) {
    val baseline = paint.textSize * 0.35f
    for (i in labels.indices) {
        val l = labels[i]
        canvas.save()
        canvas.translate(l.x, l.y)
        canvas.rotate(l.degrees)
        canvas.drawText(l.name, -l.width / 2, baseline, paint)
        canvas.restore()
    }
}

/**
 * Los tramos con nombre de las teselas que se ven, con su pincel: se rehacen solo al cambiar de teselas o de zoom, o
 * al llegar los nombres de una (no con cada tesela que llega ni con cada cuadro).
 */
internal class LabelCache {
    private var origins = LongArray(32)
    private var count = 0
    private var last = LongArray(32)
    private var lastCount = -1
    private var lastZ = -1
    private var lastNamed = -1
    private var lastPaint: android.graphics.Paint? = null
    private var cached: List<LabelRun> = emptyList()
    private var fill: android.graphics.Paint? = null
    private var halo = 0

    /** Lo que mide cada nombre con el pincel (medir texto cuesta). */
    private val widths = HashMap<String, Float>()

    /** El pincel de los nombres: se rehace (y se vuelven a medir) solo si cambia el tamaño o un color. */
    fun paint(size: Float, color: Color, halo: Color): android.graphics.Paint {
        val p = fill
        if (p != null && p.textSize == size && p.color == color.toArgb() && this.halo == halo.toArgb()) return p
        this.halo = halo.toArgb()
        widths.clear()
        return android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color.toArgb()
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            // El halo del color del fondo: se lee sobre las calles.
            setShadowLayer(size * 0.18f, 0f, 0f, halo.toArgb())
        }.also { fill = it }
    }

    fun runs(source: TileSource, plan: TilePlan, z: Int, ts: Float, dp: Float, measure: android.graphics.Paint): List<LabelRun> {
        // Solo lo cercano (con todo el detalle): lejos, en el mapa inclinado, los nombres ni se leerían.
        count = 0
        var named = 0
        for (i in 0 until plan.count) {
            if (plan.z[i] != z) continue
            val o = source.origin(plan.key(i))
            if (has(o)) continue
            if (count == origins.size) origins = origins.copyOf(count * 2)
            origins[count++] = o
            if (source.names(o) != null) named++
        }
        java.util.Arrays.sort(origins, 0, count)
        if (z == lastZ && named == lastNamed && measure === lastPaint && sameOrigins()) return cached
        val t = last
        last = origins
        origins = if (t.size >= last.size) t else LongArray(last.size)
        lastCount = count
        lastZ = z
        lastNamed = named
        lastPaint = measure
        val names = HashMap<Long, TileNames>()
        for (i in 0 until lastCount) source.names(last[i])?.let { names[last[i]] = it }
        cached = labelRuns(names, z, ts, dp) { name -> widths.getOrPut(name) { measure.measureText(name) } }
        return cached
    }

    private fun has(o: Long): Boolean {
        for (i in 0 until count) if (origins[i] == o) return true
        return false
    }

    private fun sameOrigins(): Boolean {
        if (count != lastCount) return false
        for (i in 0 until count) if (origins[i] != last[i]) return false
        return true
    }
}
