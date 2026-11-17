package com.irvingguerra.litedash.feature.map

import android.graphics.Matrix as AndroidMatrix
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
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

    /** La ruta en pantalla (se rehace en cada cuadro: pocos cientos de puntos). */
    private val routePath = Path()
    private val donePath = Path()
    private var routeShown = false
    private var doneShown = false
    private val routeAt = FloatArray(3)
    private var routeStroke = Stroke(0f)
    private var routeEdge = Stroke(0f)

    /** El ancho de la ruta en el plano del mapa (a escala real, con perspectiva); 0 = línea en pantalla. */
    private var routeWide = 0f
    private val planeAhead = Path()
    private val planeDone = Path()

    /** La ruta en el plano del mapa (se dibuja con la misma perspectiva que las calles): lo recorrido aparte. */
    private fun tracePlaneRoute(world: DoubleArray?, n: Double, done: Float) {
        planeAhead.reset()
        planeDone.reset()
        routeShown = false
        doneShown = false
        if (world == null || world.size < 4) return
        val count = world.size / 2
        val split = done.toInt().coerceIn(0, count - 1)
        val f = (done - split).toDouble().coerceIn(0.0, 1.0)
        fun px(i: Int) = atX + ((world[2 * i] * n - fx) * ts).toFloat()
        fun py(i: Int) = atY + ((world[2 * i + 1] * n - fy) * ts).toFloat()
        val next = (split + 1).coerceAtMost(count - 1)
        val hx = px(split) + (px(next) - px(split)) * f.toFloat()
        val hy = py(split) + (py(next) - py(split)) * f.toFloat()
        // Solo lo de cerca: una ruta de cientos de km en el plano mide millones de px y Android 6 no dibuja trazos así.
        val reach = PLANE_REACH * maxOf(width, height)
        fun near(x: Float, y: Float) = kotlin.math.abs(x - atX) < reach && kotlin.math.abs(y - atY) < reach
        val box = floatArrayOf(atX - reach, atY - reach, atX + reach, atY + reach)
        val seg = FloatArray(4)
        // Cada tramo recortado al cuadro de alcance (una recta larga sale de él y no debe perderse entera).
        fun add(path: Path, from: Int, to: Int, startX: Float?, startY: Float?): Boolean {
            var any = false
            var penX = Float.NaN
            var penY = Float.NaN
            var lastX = startX ?: px(from)
            var lastY = startY ?: py(from)
            var outside = 0
            for (i in (if (startX == null) from + 1 else from)..to) {
                val x = px(i)
                val y = py(i)
                if (clip(lastX, lastY, x, y, box, seg)) {
                    if (seg[0] != penX || seg[1] != penY) path.moveTo(seg[0], seg[1])
                    path.lineTo(seg[2], seg[3])
                    penX = seg[2]
                    penY = seg[3]
                    any = true
                    outside = 0
                } else if (any && ++outside > PLANE_OUT_POINTS) break
                lastX = x
                lastY = y
            }
            return any
        }
        if (done > 0f) {
            // Lo recorrido de atrás hacia adelante, terminando donde vas.
            val first = (split - PLANE_BACK_POINTS).coerceAtLeast(0)
            doneShown = add(planeDone, first, split, null, null)
            if (near(hx, hy) && doneShown) planeDone.lineTo(hx, hy)
        }
        routeShown = add(planeAhead, if (done > 0f) next else 1, count - 1, if (done > 0f) hx else px(0), if (done > 0f) hy else py(0))
    }

    /** Pasa la ruta (mundo en zoom 0) a la pantalla; lo que queda detrás de la cámara (inclinado) corta la línea. */
    private fun traceRoute(world: DoubleArray?, n: Double, done: Float) {
        routePath.reset()
        donePath.reset()
        routeShown = false
        doneShown = false
        if (world == null || world.size < 4) return
        // Lo recorrido (hasta el punto [done] y su fracción) va aparte, en gris; lo que falta empieza donde vas.
        val split = done.toInt().coerceIn(0, world.size / 2 - 1)
        val f = (done - split).toDouble().coerceIn(0.0, 1.0)
        if (done > 0f) {
            var penDone = false
            // Lo recorrido de hace mucho ya no se ve: solo los últimos puntos.
            for (k in (split - PLANE_BACK_POINTS).coerceAtLeast(0)..split) {
                if (toScreen(world[2 * k] * n, world[2 * k + 1] * n, routeAt)) {
                    if (penDone) donePath.lineTo(routeAt[0], routeAt[1]) else donePath.moveTo(routeAt[0], routeAt[1])
                    penDone = true
                    doneShown = true
                } else penDone = false
            }
        }
        val next = (split + 1).coerceAtMost(world.size / 2 - 1)
        val hx = world[2 * split] + (world[2 * next] - world[2 * split]) * f
        val hy = world[2 * split + 1] + (world[2 * next + 1] - world[2 * split + 1]) * f
        var pen = false
        if (done > 0f && toScreen(hx * n, hy * n, routeAt)) {
            if (doneShown) donePath.lineTo(routeAt[0], routeAt[1])
            routePath.moveTo(routeAt[0], routeAt[1])
            pen = true
        }
        var lastX = 0f
        var lastY = 0f
        val step = 2 * unit
        var i = if (done > 0f) 2 * next else 0
        while (i < world.size) {
            if (toScreen(world[i] * n, world[i + 1] * n, routeAt)) {
                val x = routeAt[0]
                val y = routeAt[1]
                // Lo que queda a menos de 2 dp del punto anterior no se ve: una ruta de cientos de km tiene miles.
                val last = i + 2 >= world.size
                if (!pen) routePath.moveTo(x, y) else if (last || abs(x - lastX) + abs(y - lastY) >= step) routePath.lineTo(x, y) else {
                    i += 2
                    continue
                }
                lastX = x
                lastY = y
                pen = true
                routeShown = true
            } else pen = false
            i += 2
        }
    }

    /** El pin del lugar en pantalla. */
    private val pinAt = FloatArray(3)
    private var pinShown = false
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
    private var pin = Path()
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
        pinX: Double = Double.NaN, pinY: Double = Double.NaN,
        routeWorld: DoubleArray? = null,
        routeDone: Float = 0f,
        routePlaneWidth: Float = 0f,
    ) {
        if (density != arrowFor) {
            arrowFor = density
            unit = density
            arrow = arrowPath(density)
            pin = pinPath(density)
            routeStroke = Stroke(6 * density, cap = StrokeCap.Round, join = StrokeJoin.Round)
            routeEdge = Stroke(10 * density, cap = StrokeCap.Round, join = StrokeJoin.Round)
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
        pinShown = !pinX.isNaN() && toScreen(pinX, pinY, pinAt)
        routeWide = routePlaneWidth
        if (routePlaneWidth > 0f) tracePlaneRoute(routeWorld, n, routeDone) else traceRoute(routeWorld, n, routeDone)
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

    fun draw(scope: DrawScope, store: TileStore, filter: ColorFilter?, accent: Color, marker: ImageBitmap? = null) = with(scope) {
        drawIntoCanvas { canvas ->
            canvas.save()
            canvas.nativeCanvas.concat(matrix)
            drawTiles(store, plan, filter)
            // A escala real la ruta va en el plano (con perspectiva, como una calle más), debajo de nombres y del auto.
            if (routeWide > 0f) {
                // Como Tesla: una franja sólida del ancho del auto por donde vas; lo recorrido, gris tenue.
                val cap = StrokeCap.Butt
                if (doneShown) drawPath(planeDone, Color.Gray.copy(alpha = 0.3f), style = Stroke(routeWide, cap = cap, join = StrokeJoin.Round))
                // Difuminada: tres pasadas cada vez más angostas, así las orillas quedan suaves y el centro más lleno.
                if (routeShown) for (k in 0 until 3) {
                    drawPath(planeAhead, accent.copy(alpha = 0.22f), style = Stroke(routeWide * (1f - 0.2f * k), cap = cap, join = StrokeJoin.Round))
                }
            }
            canvas.restore()
        }
        fade?.let { drawRect(it) }
        if (ring > 0f) {
            val center = Offset(atX, atY)
            drawCircle(accent.copy(alpha = 0.12f), ring, center)
            drawCircle(accent, ring, center, style = ringStroke)
        }
        // La ruta: de acento con un borde oscuro (se ve sobre calles claras y oscuras), debajo de los nombres.
        if (routeWide <= 0f && doneShown) drawPath(donePath, Color.Gray.copy(alpha = 0.7f), style = routeStroke)
        if (routeWide <= 0f && routeShown) {
            drawPath(routePath, Color.Black.copy(alpha = 0.45f), style = routeEdge)
            drawPath(routePath, accent, style = routeStroke)
        }
        labelPaint?.let { paint -> drawIntoCanvas { drawLabels(it.nativeCanvas, layout.placed, paint) } }
        if (pinShown) drawPin(accent)
        if (youShown) drawArrow(accent, marker)
    }

    /** El punto ([x], [y]) de la vista en el mapa (latitud, longitud); null si cae en el cielo o aún no hay cuadro. */
    fun toLatLon(x: Float, y: Float): Pair<Double, Double>? {
        if (zoom < 0 || ts <= 0f || !projection.toPlane(x, y, dragFrom)) return null
        val tx = fx + (dragFrom[0] - atX) / ts
        val ty = fy + (dragFrom[1] - atY) / ts
        return tileLat(ty, zoom) to tileLon(tx, zoom)
    }

    /** El pin del lugar: una gota de acento con la punta en el lugar y su sombra. */
    private fun DrawScope.drawPin(accent: Color) {
        val u = unit
        translate(pinAt[0], pinAt[1]) {
            drawOval(Color.Black.copy(alpha = 0.3f), Offset(-7 * u, -3 * u), Size(14 * u, 6 * u))
            drawPath(pin, accent)
            drawCircle(Color.White, 5 * u, Offset(0f, -27 * u))
        }
    }

    /**
     * Tu posición: la flecha con el rumbo (color de acento), un poco acostada si está inclinado; o, con [marker], tu
     * auto visto de atrás, siempre derecho (con él el mapa gira hacia donde vas: el auto no se gira).
     */
    private fun DrawScope.drawArrow(accent: Color, marker: ImageBitmap?) {
        translate(you[0], you[1]) {
            if (marker != null) {
                // Proporcional a la pantalla (como el auto de Tesla o Waze): se ve igual de grande en 7" que en 12".
                val w = (minOf(size.width, size.height) * MARKER_SHARE).coerceIn(MARKER_MIN_DP * unit, MARKER_MAX_DP * unit)
                val h = w * marker.height / marker.width
                // Su sombra en la calle (la foto va sin piso), y el auto encima.
                drawOval(Color.Black.copy(alpha = 0.35f), Offset(-w * 0.55f, h * 0.22f), Size(w * 1.1f, h * 0.36f))
                drawImage(marker, IntOffset.Zero, IntSize(marker.width, marker.height), IntOffset((-w / 2).toInt(), (-h * 0.6f).toInt()), IntSize(w.toInt(), h.toInt()), filterQuality = FilterQuality.High)
                return@translate
            }
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

/** Lo ancho de tu auto en el mapa: esta parte del lado corto del mapa, entre [MARKER_MIN_DP] y [MARKER_MAX_DP]. */
private const val MARKER_SHARE = 0.22f
private const val MARKER_MIN_DP = 140f
private const val MARKER_MAX_DP = 240f

/** Puntos seguidos fuera del alcance para dejar de revisar (la ruta ya se fue lejos). */
private const val PLANE_OUT_POINTS = 300

/**
 * El tramo ([x0], [y0])–([x1], [y1]) recortado al cuadro [box] (izq, arriba, der, abajo) en [out]; false si no lo toca
 * (Liang–Barsky).
 */
private fun clip(x0: Float, y0: Float, x1: Float, y1: Float, box: FloatArray, out: FloatArray): Boolean {
    val dx = x1 - x0
    val dy = y1 - y0
    var t0 = 0f
    var t1 = 1f
    val p = floatArrayOf(-dx, dx, -dy, dy)
    val q = floatArrayOf(x0 - box[0], box[2] - x0, y0 - box[1], box[3] - y0)
    for (k in 0 until 4) {
        if (p[k] == 0f) {
            if (q[k] < 0f) return false
        } else {
            val r = q[k] / p[k]
            if (p[k] < 0f) {
                if (r > t1) return false
                if (r > t0) t0 = r
            } else {
                if (r < t0) return false
                if (r < t1) t1 = r
            }
        }
    }
    out[0] = x0 + t0 * dx
    out[1] = y0 + t0 * dy
    out[2] = x0 + t1 * dx
    out[3] = y0 + t1 * dy
    return true
}

/** Hasta dónde se arma la ruta en el plano: estas veces el lado largo de la vista desde ti. */
private const val PLANE_REACH = 6f

/** Cuántos puntos de lo recorrido se revisan hacia atrás (lo demás ya no se ve). */
private const val PLANE_BACK_POINTS = 400

/** El pin con la punta en (0, 0): círculo de 12 dp arriba y punta abajo; [u] = 1 dp. */
private fun pinPath(u: Float) = Path().apply {
    moveTo(0f, 0f)
    cubicTo(-4 * u, -10 * u, -12 * u, -16 * u, -12 * u, -27 * u)
    cubicTo(-12 * u, -34 * u, -7 * u, -39 * u, 0f, -39 * u)
    cubicTo(7 * u, -39 * u, 12 * u, -34 * u, 12 * u, -27 * u)
    cubicTo(12 * u, -16 * u, 4 * u, -10 * u, 0f, 0f)
    close()
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
