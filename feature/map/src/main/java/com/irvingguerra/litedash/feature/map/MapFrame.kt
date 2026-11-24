package com.irvingguerra.litedash.feature.map

import android.graphics.Matrix as AndroidMatrix
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt

/** El azul de la ruta a escala real (la franja bajo tu auto, como Tesla) y de la barra de avance de la guía. */
val ROUTE_BLUE = Color(0xFF3D8BFF)
private const val ROUTE_ARGB = 0xFF3D8BFF.toInt()

/**
 * La ruta «río» de los mapas planos (valores de Horizon Maps, GPL-3.0): la línea y su orilla clara, de noche y de día;
 * lo ya recorrido en gris. El resplandor es la misma línea, ancha y translúcida.
 */
private const val RIVER_NIGHT = 0xFF4CC9FF.toInt()
private const val RIVER_NIGHT_EDGE = 0xFFEAF2FF.toInt()
private const val RIVER_DAY = 0xFF2A7BE4.toInt()
private const val RIVER_DAY_EDGE = 0xFF8AB6F0.toInt()
private const val RIVER_DONE = 0xFF8C92AC.toInt()

/** Las flechitas que avanzan sobre la ruta: blancas, casi opacas, una cada [FLOW_GAP_DP]. */
private const val FLOW_ARGB = 0xE6FFFFFF.toInt()
private const val FLOW_GAP_DP = 24f

/** Tu flecha (como en Horizon Maps): gota blanca sobre un círculo azul con orilla blanca y un resplandor azul suave. */
private val ARROW_BODY = Color(0xFFF2F2F6)
private val ARROW_DISC = Color(0xFF4C9BE6)

/** La franja a escala real mide esto del ancho de tu auto. */
private const val RIBBON = 0.9f

/** Lo más cerca de la cámara que se traza la ruta (1 = donde vas tú, 0 = la cámara): más acá no se puede proyectar. */
private const val ROUTE_NEAR = 0.3f

/** La franja de la ruta empieza este múltiplo del ancho del auto atrás de donde vas: queda debajo de todo el auto. */
private const val UNDER_CAR = 3.6f

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

    /**
     * La ruta lista para dibujar, ya en pantalla y recortada a la vista: en los mapas planos, tramos sueltos (lo que falta
     * y lo recién recorrido); a escala real, el contorno de la franja en perspectiva. Nunca mide más que la vista: Android
     * 8.1 y anteriores vuelven imagen cada trazo y, si mide más de lo que acepta la GPU (4096 px en muchas pantallas),
     * no lo dibujan.
     */
    private val routeAhead = Segments()
    private val routeBehind = Segments()
    private val ribbonLine = android.graphics.Path()
    private val ribbon = android.graphics.Path()
    private var ribbonX = Float.NaN
    private var ribbonY = Float.NaN
    private val routeAt = FloatArray(3)
    private val routeSeg = FloatArray(4)
    private var routeMargin = 0f
    private val routePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }

    /** Lo que falta de la ruta como un solo trazo (para que las flechitas sigan de corrido), ya recortado a la vista. */
    private val flowLine = android.graphics.Path()
    private val flowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.FILL
        color = FLOW_ARGB
    }
    private var chevron = android.graphics.Path()

    /** Las flechitas de la ruta: una por cada paso de su avance (0–1), hechas una vez. */
    private val flowEffects = arrayOfNulls<android.graphics.PathEffect>(FLOW_STEPS)

    /** El ancho de la ruta en el plano del mapa (a escala real, con perspectiva); 0 = línea en pantalla. */
    private var routeWide = 0f

    /**
     * Arma la ruta ([world] = sus puntos en el mundo, zoom 0) desde donde vas ([done] = punto + fracción al siguiente).
     * A escala real ([routeWide] > 0) queda en el plano del mapa y empieza debajo de tu auto; si no, en pantalla, con lo
     * recién recorrido aparte. Solo lo de cerca, recortado tramo por tramo.
     */
    private fun traceRoute(world: DoubleArray?, n: Double, done: Float) {
        routeAhead.clear()
        routeBehind.clear()
        ribbonLine.rewind()
        ribbon.rewind()
        ribbonX = Float.NaN
        if (world == null || world.size < 4) return
        val count = world.size / 2
        // Lo que se sale de la vista por este margen (el grosor de la línea o de la franja) ya no se traza.
        routeMargin = if (routeWide > 0f) routeWide else 12 * unit
        if (done <= 0f) {
            traceRun(world, n, 1, count - 1, planeX(world, n, 0), planeY(world, n, 0), routeAhead)
            outlineRibbon()
            return
        }
        val split = done.toInt().coerceIn(0, count - 1)
        val next = (split + 1).coerceAtMost(count - 1)
        val f = (done - split).coerceIn(0f, 1f)
        val hx = planeX(world, n, split) + (planeX(world, n, next) - planeX(world, n, split)) * f
        val hy = planeY(world, n, split) + (planeY(world, n, next) - planeY(world, n, split)) * f
        if (routeWide <= 0f) {
            // Lo recorrido de hace mucho ya no se ve: solo los últimos puntos, hasta donde vas.
            val from = (split - ROUTE_BACK_POINTS).coerceAtLeast(0)
            traceRun(world, n, from + 1, split, planeX(world, n, from), planeY(world, n, from), routeBehind, hx, hy)
            traceRun(world, n, next, count - 1, hx, hy, routeAhead)
            return
        }
        // Como Tesla, la franja pasa por debajo de todo tu auto: empieza un largo de auto atrás de donde vas.
        var left = routeWide * UNDER_CAR
        var fromX = hx
        var fromY = hy
        var i = split
        while (i >= 0) {
            val x = planeX(world, n, i)
            val y = planeY(world, n, i)
            val d = kotlin.math.hypot(x - fromX, y - fromY)
            if (d >= left) {
                val t = left / d
                fromX += (x - fromX) * t
                fromY += (y - fromY) * t
                break
            }
            left -= d
            fromX = x
            fromY = y
            i--
        }
        traceRun(world, n, (i + 1).coerceIn(1, count - 1), count - 1, fromX, fromY, routeAhead)
        outlineRibbon()
    }

    /** Une los tramos de lo que falta en un trazo: los que siguen al anterior van de corrido. */
    private fun joinFlow() {
        flowLine.rewind()
        val p = routeAhead.points
        var lastX = Float.NaN
        var lastY = Float.NaN
        var i = 0
        while (i < routeAhead.size) {
            if (p[i] != lastX || p[i + 1] != lastY) flowLine.moveTo(p[i], p[i + 1])
            flowLine.lineTo(p[i + 2], p[i + 3])
            lastX = p[i + 2]
            lastY = p[i + 3]
            i += 4
        }
    }

    /** A escala real: el contorno de la franja (con vueltas redondas) se arma en el plano y se pasa a la pantalla. */
    private fun outlineRibbon() {
        if (routeWide <= 0f || ribbonX.isNaN()) return
        routePaint.style = android.graphics.Paint.Style.STROKE
        routePaint.strokeWidth = routeWide * RIBBON
        routePaint.getFillPath(ribbonLine, ribbon)
        ribbon.transform(matrix)
    }

    /** Un punto de la ruta en el plano del mapa (px). */
    private fun planeX(world: DoubleArray, n: Double, i: Int) = atX + ((world[2 * i] * n - fx) * ts).toFloat()
    private fun planeY(world: DoubleArray, n: Double, i: Int) = atY + ((world[2 * i + 1] * n - fy) * ts).toFloat()

    /**
     * Los tramos desde ([startX], [startY]) por los puntos [from]..[to] de la ruta, y al final hasta ([endX], [endY]) si
     * se da, en [out]. Los puntos a menos de 2 dp del anterior se saltan (una ruta larga trae miles).
     */
    private fun traceRun(
        world: DoubleArray, n: Double, from: Int, to: Int, startX: Float, startY: Float, out: Segments,
        endX: Float = Float.NaN, endY: Float = Float.NaN,
    ) {
        var lastX = startX
        var lastY = startY
        var outside = 0
        val step = 2 * unit
        for (i in from..to) {
            val x = planeX(world, n, i)
            val y = planeY(world, n, i)
            if (i < to && abs(x - lastX) + abs(y - lastY) < step) continue
            if (traceSegment(lastX, lastY, x, y, out)) outside = 0
            // Ya se fue lejos: lo que sigue de la ruta no se revisa (si regresa, se verá al acercarte).
            else if ((out.size > 0 || !ribbonX.isNaN()) && ++outside > ROUTE_OUT_POINTS) return
            lastX = x
            lastY = y
        }
        if (!endX.isNaN()) traceSegment(lastX, lastY, endX, endY, out)
    }

    /**
     * El tramo del plano recortado a lo que se ve (con [routeMargin] de más): a escala real se suma a la franja, en el
     * plano; si no, a [out], en pantalla. false si no se ve nada de él.
     */
    private fun traceSegment(x0: Float, y0: Float, x1: Float, y1: Float, out: Segments): Boolean {
        val seg = routeSeg
        if (!clipToView(x0, y0, x1, y1, seg)) return false
        if (routeWide > 0f) {
            if (seg[0] != ribbonX || seg[1] != ribbonY) ribbonLine.moveTo(seg[0], seg[1])
            ribbonLine.lineTo(seg[2], seg[3])
            ribbonX = seg[2]
            ribbonY = seg[3]
            return true
        }
        projection.toScreen(seg[0], seg[1], routeAt)
        val ax = routeAt[0]
        val ay = routeAt[1]
        projection.toScreen(seg[2], seg[3], routeAt)
        out.add(ax, ay, routeAt[0], routeAt[1])
        return true
    }

    /**
     * El tramo ([x0], [y0])–([x1], [y1]) del plano recortado a lo que cae dentro de la vista (más [routeMargin]) y delante
     * de la cámara, en [out] (sigue en el plano); false si nada. Se recorta antes de dividir por la perspectiva: cada
     * orilla de la vista es una recta también en el plano.
     */
    private fun clipToView(x0: Float, y0: Float, x1: Float, y1: Float, out: FloatArray): Boolean {
        val m = projection.values
        val ax = m[0] * x0 + m[1] * y0 + m[2]
        val ay = m[3] * x0 + m[4] * y0 + m[5]
        val aw = m[6] * x0 + m[7] * y0 + m[8]
        val bx = m[0] * x1 + m[1] * y1 + m[2]
        val by = m[3] * x1 + m[4] * y1 + m[5]
        val bw = m[6] * x1 + m[7] * y1 + m[8]
        var t0 = 0f
        var t1 = 1f
        for (k in 0 until 5) {
            // Cuánto queda adentro cada punta (≥ 0): de la cámara, y de la orilla izquierda, derecha, de arriba y de abajo.
            val a = when (k) { 0 -> aw - ROUTE_NEAR; 1 -> ax + routeMargin * aw; 2 -> (width + routeMargin) * aw - ax; 3 -> ay + routeMargin * aw; else -> (height + routeMargin) * aw - ay }
            val b = when (k) { 0 -> bw - ROUTE_NEAR; 1 -> bx + routeMargin * bw; 2 -> (width + routeMargin) * bw - bx; 3 -> by + routeMargin * bw; else -> (height + routeMargin) * bw - by }
            if (a < 0f && b < 0f) return false
            if (a < 0f) t0 = maxOf(t0, a / (a - b)) else if (b < 0f) t1 = minOf(t1, a / (a - b))
        }
        if (t1 - t0 < 1e-6f) return false
        out[0] = x0 + (x1 - x0) * t0
        out[1] = y0 + (y1 - y0) * t0
        out[2] = x0 + (x1 - x0) * t1
        out[3] = y0 + (y1 - y0) * t1
        return true
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
    private var glow: Brush? = null
    private var ringStroke = Stroke(0f)
    private var unit = 1f

    /** Más cerca que lo más cerca de la fuente (la navegación detenida), las calles se agrandan: la ruta plana también. */
    private var river = 1f

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
        routeArrows: Boolean = false,
    ) {
        if (density != arrowFor) {
            arrowFor = density
            unit = density
            arrow = arrowPath(density)
            pin = pinPath(density)
            chevron = chevronPath(density)
            flowEffects.fill(null)
            glow = Brush.radialGradient(
                0f to ARROW_DISC.copy(alpha = 0.55f), 0.6f to ARROW_DISC.copy(alpha = 0.22f), 1f to ARROW_DISC.copy(alpha = 0f),
                center = Offset.Zero, radius = ARROW_GLOW * density,
            )
            arrowStroke = Stroke(2 * density)
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
        river = if (names != null && zoom >= names.maxZoom) (ts / labelTs).coerceIn(1f, 2f) else 1f
        traceRoute(routeWorld, n, routeDone)
        // Las flechitas solo donde la pantalla da para ellas; si no, la ruta va sola en sus tres capas.
        if (routeWide <= 0f && routeArrows) joinFlow() else flowLine.rewind()
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

    /**
     * [focus] = el hueco libre en fracciones del mapa (de fondo del inicio): tu auto se mide contra él. [dark] = la ruta en
     * sus colores de noche; [flow] = cuánto han avanzado sus flechitas (0–1, una flechita completa).
     */
    fun draw(
        scope: DrawScope, store: TileStore, accent: Color, marker: ImageBitmap? = null, focus: Rect? = null,
        dark: Boolean = true, flow: Float = 0f,
    ) = with(scope) {
        drawIntoCanvas { canvas ->
            canvas.save()
            canvas.nativeCanvas.concat(matrix)
            drawTiles(store, plan)
            canvas.restore()
            // A escala real la ruta va acostada como una calle más: una franja azul lisa, como Tesla.
            if (routeWide > 0f && !ribbonX.isNaN()) {
                routePaint.style = android.graphics.Paint.Style.FILL
                routePaint.color = ROUTE_ARGB
                canvas.nativeCanvas.drawPath(ribbon, routePaint)
            }
        }
        fade?.let { drawRect(it) }
        if (ring > 0f) {
            val center = Offset(atX, atY)
            drawCircle(accent.copy(alpha = 0.12f), ring, center)
            drawCircle(accent, ring, center, style = ringStroke)
        }
        // En los mapas planos, la ruta «río» debajo de los nombres: resplandor, orilla clara, la línea y, encima, las
        // flechitas que avanzan hacia donde vas. Lo recorrido, en gris.
        if (routeWide <= 0f && routeAhead.size + routeBehind.size > 0) drawIntoCanvas {
            val canvas = it.nativeCanvas
            val line = if (dark) RIVER_NIGHT else RIVER_DAY
            val u = unit * river
            drawRoute(canvas, routeBehind, RIVER_DONE, 6 * u)
            drawRoute(canvas, routeAhead, line and 0x00FFFFFF or (0x40 shl 24), 15 * u)
            drawRoute(canvas, routeAhead, if (dark) RIVER_NIGHT_EDGE else RIVER_DAY_EDGE, 10 * u)
            drawRoute(canvas, routeAhead, line, 7 * u)
            if (!flowLine.isEmpty) {
                val step = (flow.coerceIn(0f, 1f) * FLOW_STEPS).toInt() % FLOW_STEPS
                // La fase baja para que las flechitas vayan hacia adelante (hacia el final del trazo).
                flowPaint.pathEffect = flowEffects[step] ?: android.graphics.PathDashPathEffect(
                    chevron, FLOW_GAP_DP * unit, FLOW_GAP_DP * unit * (1f - step.toFloat() / FLOW_STEPS),
                    android.graphics.PathDashPathEffect.Style.ROTATE,
                ).also { e -> flowEffects[step] = e }
                canvas.drawPath(flowLine, flowPaint)
            }
        }
        labelPaint?.let { paint -> drawIntoCanvas { drawLabels(it.nativeCanvas, layout.placed, paint) } }
        if (pinShown) drawPin(accent)
        if (youShown) drawArrow(marker, focus)
    }

    /** Los tramos de [segments] de [width] px, con las puntas redondas (así se unen sin huecos en las vueltas). */
    private fun drawRoute(canvas: android.graphics.Canvas, segments: Segments, argb: Int, width: Float) {
        if (segments.size == 0) return
        routePaint.style = android.graphics.Paint.Style.STROKE
        routePaint.color = argb
        routePaint.strokeWidth = width
        canvas.drawLines(segments.points, 0, segments.size, routePaint)
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
     * Tu posición: la flecha redondeada con el rumbo, un poco acostada si está inclinado; o, con [marker], tu
     * auto visto de atrás, siempre derecho (con él el mapa gira hacia donde vas: el auto no se gira).
     */
    private fun DrawScope.drawArrow(marker: ImageBitmap?, focus: Rect?) {
        translate(you[0], you[1]) {
            if (marker != null) {
                // Proporcional al hueco donde va (como el auto de Tesla o Waze): se ve igual de grande en 7" que en 12".
                val side = if (focus == null) minOf(size.width, size.height) else minOf(size.width * focus.width, size.height * focus.height)
                val w = markerWidthDp(side / unit) * unit
                val h = w * marker.height / marker.width
                // Su sombra en la calle (la foto va sin piso), y el auto encima.
                drawOval(Color.Black.copy(alpha = 0.35f), Offset(-w * 0.55f, h * 0.22f), Size(w * 1.1f, h * 0.36f))
                drawImage(marker, IntOffset.Zero, IntSize(marker.width, marker.height), IntOffset((-w / 2).toInt(), (-h * 0.6f).toInt()), IntSize(w.toInt(), h.toInt()), filterQuality = FilterQuality.High)
                return@translate
            }
            scale(1f, squash, Offset.Zero) {
                glow?.let { drawCircle(it, ARROW_GLOW * unit, Offset.Zero) }
                drawCircle(ARROW_DISC.copy(alpha = 0.92f), ARROW_DISC_DP * unit, Offset.Zero)
                drawCircle(Color.White.copy(alpha = 0.55f), ARROW_DISC_DP * unit, Offset.Zero, style = arrowStroke)
                rotate(arrowDegrees, Offset.Zero) { drawPath(arrow, ARROW_BODY) }
            }
        }
    }
}

/**
 * Lo ancho de tu auto (dp) en un mapa cuyo lado corto libre mide [shortSideDp]: el 22 %, entre 96 y 240 dp y nunca más
 * del 40 % de ese lado (en un hueco chico no lo tapa todo). La guía mide con esto sus calles a escala real.
 */
fun markerWidthDp(shortSideDp: Float): Float =
    (shortSideDp * MARKER_SHARE).coerceIn(MARKER_MIN_DP, MARKER_MAX_DP).coerceAtMost(shortSideDp * MARKER_MAX_SHARE)

private const val MARKER_SHARE = 0.22f
private const val MARKER_MAX_SHARE = 0.4f
private const val MARKER_MIN_DP = 96f
private const val MARKER_MAX_DP = 240f

/** Puntos seguidos fuera del alcance para dejar de revisar (la ruta ya se fue lejos). */
private const val ROUTE_OUT_POINTS = 1500

/** Tramos sueltos (x0, y0, x1, y1 cada uno) que se reusan cuadro a cuadro; [size] = cuántos números hay. */
private class Segments {
    var points = FloatArray(1024)
        private set
    var size = 0
        private set

    fun clear() {
        size = 0
    }

    fun add(x0: Float, y0: Float, x1: Float, y1: Float) {
        if (size + 4 > points.size) points = points.copyOf(points.size * 2)
        points[size] = x0; points[size + 1] = y0; points[size + 2] = x1; points[size + 3] = y1
        size += 4
    }
}

/** Cuántos puntos de lo recorrido se revisan hacia atrás (lo demás ya no se ve). */
private const val ROUTE_BACK_POINTS = 400

/** El pin con la punta en (0, 0): círculo de 12 dp arriba y punta abajo; [u] = 1 dp. */
private fun pinPath(u: Float) = Path().apply {
    moveTo(0f, 0f)
    cubicTo(-4 * u, -10 * u, -12 * u, -16 * u, -12 * u, -27 * u)
    cubicTo(-12 * u, -34 * u, -7 * u, -39 * u, 0f, -39 * u)
    cubicTo(7 * u, -39 * u, 12 * u, -34 * u, 12 * u, -27 * u)
    cubicTo(12 * u, -16 * u, 4 * u, -10 * u, 0f, 0f)
    close()
}

/** Lo que alcanza el resplandor de tu flecha y el radio de su círculo azul (dp). */
internal const val ARROW_GLOW = 34f
private const val ARROW_DISC_DP = 22f

/**
 * La flecha apuntando al norte con su centro en (0, 0): una gota redondeada (punta suave, esquinas de atrás redondas y
 * una muesca), como la de Horizon Maps; [u] = 1 dp. Cabe en el círculo de [ARROW_DISC_DP].
 */
private fun arrowPath(u: Float) = Path().apply {
    val k = 0.68f * u
    moveTo(0f, -20 * k)
    cubicTo(4 * k, -14 * k, 15 * k, 6 * k, 16 * k, 12 * k)
    cubicTo(17 * k, 17 * k, 12 * k, 19 * k, 8 * k, 17 * k)
    lineTo(0f, 12 * k)
    lineTo(-8 * k, 17 * k)
    cubicTo(-12 * k, 19 * k, -17 * k, 17 * k, -16 * k, 12 * k)
    cubicTo(-15 * k, 6 * k, -4 * k, -14 * k, 0f, -20 * k)
    close()
}

/** Cuántos pasos tiene el avance de las flechitas de la ruta (cada uno con su efecto ya hecho). */
private const val FLOW_STEPS = 48

/** Una flechita «›» apuntando hacia +x (el sentido del trazo) con su centro en (0, 0); [u] = 1 dp. */
private fun chevronPath(u: Float) = android.graphics.Path().apply {
    moveTo(-3.5f * u, -4.5f * u)
    lineTo(-0.5f * u, -4.5f * u)
    lineTo(3.5f * u, 0f)
    lineTo(-0.5f * u, 4.5f * u)
    lineTo(-3.5f * u, 4.5f * u)
    lineTo(0.5f * u, 0f)
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

private fun DrawScope.drawTiles(store: TileStore, plan: TilePlan) {
    for (i in 0 until plan.count) {
        val offset = IntOffset(plan.left[i], plan.top[i])
        val dst = IntSize(plan.right[i] - plan.left[i], plan.bottom[i] - plan.top[i])
        val image = store.tile(plan.key(i))
        if (image != null) {
            drawImage(image, dstOffset = offset, dstSize = dst, filterQuality = FilterQuality.Low)
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
                    offset, dst, filterQuality = FilterQuality.Low,
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
                filterQuality = FilterQuality.Low,
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
