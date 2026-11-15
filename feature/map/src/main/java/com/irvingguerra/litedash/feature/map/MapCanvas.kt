package com.irvingguerra.litedash.feature.map

import android.graphics.Matrix as AndroidMatrix
import androidx.compose.foundation.background
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.positionChange
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Radius
import com.irvingguerra.litedash.core.ui.Typography
import kotlin.math.roundToInt

private const val TILE = 256

/** Las teselas que traen los nombres de las calles (las del minimalista llegan hasta el 14). */
private const val DATA_ZOOM = 14

/** El zoom de siempre (calles de tu colonia); el de viaje es lo más cerca de la fuente (un par de calles, inclinado). */
private const val CITY_ZOOM = 17

/** Desde esta velocidad vas de viaje; detenido más de [STOP_MS], ya no. */
private const val DRIVE_KMH = 12f
private const val STOP_MS = 25_000L

/** Para elegir una zona el mapa se mueve hasta ~2 000 km de ti. */
private const val FREE_PAN = 0.05

/** El ecuador en metros. */
private const val EARTH_METERS = 40_075_016.686

/** Movido con el dedo, regresa a ti tras este rato sin tocarlo. */
private const val RECENTER_MS = 20_000L

/** Cuánto tarda en deslizarse hasta ti al centrar, y cada paso de zoom al acercarse después. */
private const val RECENTER_GLIDE_MS = 900f
private const val ZOOM_STEP_MS = 220f

/** Arranca y llega suave. */
private fun easeInOut(t: Float): Float = if (t < 0.5f) 2 * t * t else 1 - (-2 * t + 2).let { it * it } / 2

/** Lo más que se aleja el mapa de ti con el dedo (fracción del mundo, ~80 km). */
private const val MAX_PAN = 0.002

/** En el plano, un movimiento del dedo no avanza más de tantas veces lo que se movió en pantalla. */
private const val MAX_PAN_STRETCH = 4.0

/** A lo más tantos nombres de calles a la vez (más, es ruido). */
private const val MAX_LABELS = 14

/** Lo que tarda tu flecha en deslizarse a la lectura nueva del GPS (llegan una por segundo). */
private const val GLIDE_MS = 950

/** Cada cuánto avanza el deslizamiento (~30 cuadros por segundo). */
private const val GLIDE_STEP_MS = 33L

/** Más que esto de una lectura a otra (o casi nada, quieto) va directo, sin deslizar. */
private const val SNAP_METERS = 150.0
private const val STILL_METERS = 3.0
private const val STILL_DEGREES = 3f

/** El lado de una tesela en pantalla: 256 px a densidad 1, hasta el doble (más se vería borrosa). */
fun tilePx(density: Float) = TILE * density.coerceIn(1f, 2f)

/** Abajo de este ancho los créditos van en su versión corta. */
private val SHORT_ATTRIBUTION = 320.dp

/** Los créditos llevan aquí (lo pide la licencia de los datos de OpenStreetMap, que usan las dos fuentes). */
private const val ATTRIBUTION_URL = "https://www.openstreetmap.org/copyright"

/**
 * Lo que la app ve del mapa y le puede pedir: si lo moviste con el dedo ([centered] = false; de viaje vuelve solo tras
 * [RECENTER_MS] sin tocarlo; [recenter] te regresa al zoom de manejo en 3D), mirar otro lugar ([lookAt]) y la calle donde
 * estás ([street], solo con el minimalista).
 */
@Stable
class MapCamera(
    /** Lo más que se aleja el centro de ti (fracción del mundo). */
    internal val maxPan: Double = MAX_PAN,
    /** Regresa solo a ti tras un rato sin tocarlo (en la vista previa de la descarga, no). */
    internal val returns: Boolean = true,
) {
    /** Cuánto se movió el mapa con el dedo, en fracciones del mundo (0 = tú en tu lugar). */
    internal var panX by mutableDoubleStateOf(0.0)
    internal var panY by mutableDoubleStateOf(0.0)

    val centered: Boolean get() = panX == 0.0 && panY == 0.0

    /** Cuántas veces se pidió centrar (el mapa vuelve a lo más cerca, inclinado). */
    internal var recenters by mutableIntStateOf(0)

    /** Regresa a ti deslizándose (lo anima `MapCanvas`) y acerca hasta lo más cerca, inclinado. */
    fun recenter() {
        recenters++
    }

    /** El zoom que pusiste con dos dedos o al centrar (null = el de fábrica): igual en el widget y en grande. */
    internal var zoom by mutableStateOf<Int?>(null)

    /** De viaje (andando o navegando); se guarda aquí para que el widget y el mapa en grande coincidan. */
    internal var driving by mutableStateOf(false)

    /** Con qué [driving] se puso [zoom]: al empezar o terminar el viaje se olvida. */
    internal var zoomFor = false

    /** Cuántas veces se movió con el dedo: si lo tocas mientras se desliza a ti, se queda donde lo dejaste. */
    internal var touches = 0

    /** Mueve el centro del mapa a ([lat], [lon]) estando tú en ([fromLat], [fromLon]) (hasta donde deja [maxPan]). */
    fun lookAt(lat: Double, lon: Double, fromLat: Double, fromLon: Double) {
        panX = (tileX(lon, 0) - tileX(fromLon, 0)).coerceIn(-maxPan, maxPan)
        panY = (tileY(lat, 0) - tileY(fromLat, 0)).coerceIn(-maxPan, maxPan)
    }

    /** El centro del mapa (latitud, longitud) estando tú en [lat], [lon]: tú mismo, o a donde lo moviste. */
    fun center(lat: Double, lon: Double): Pair<Double, Double> {
        val x = tileX(lon, 0) + panX
        val y = tileY(lat, 0) + panY
        return Math.toDegrees(kotlin.math.atan(kotlin.math.sinh(Math.PI * (1 - 2 * y)))) to x * 360.0 - 180.0
    }

    /** La calle donde estás; null = no se sabe (lejos de una calle con nombre o con el mapa completo). */
    var street by mutableStateOf<String?>(null)
        internal set
}

@Composable
fun rememberMapCamera() = remember { MapCamera() }

/** La cámara para elegir una zona: se mueve lejos (~2 000 km) y no regresa sola. */
@Composable
fun rememberFreeMapCamera() = remember { MapCamera(maxPan = FREE_PAN, returns = false) }

/**
 * El mapa (teselas de [store]) con tu posición y como diga [look]: norte arriba o hacia donde vas, siempre plano y,
 * de viaje (andando a [DRIVE_KMH] o más, o con Waze/Maps navegando: [navigating]) en el zoom automático, se acerca
 * hasta ver un par de calles y se inclina como Waze, siempre hacia donde vas; si lo alejas con dos dedos vuelve a
 * ser plano. Con un dedo se mueve ([camera]), un toque hace [onTap] y colores claros u oscuros: las de
 * OpenStreetMap se oscurecen con un filtro; el minimalista ya viene en sus colores y con los nombres de las calles.
 * Tu flecha se desliza entre una lectura del GPS y la siguiente. Para elegir una zona: [fixedZoom] (sin gestos de
 * zoom ni viaje) y [ringKm], un círculo de ese radio alrededor del centro.
 */
@Composable
fun MapCanvas(
    store: TileStore,
    lat: Double,
    lon: Double,
    bearing: Float?,
    kmh: Float?,
    look: MapLook,
    modifier: Modifier = Modifier,
    camera: MapCamera = rememberMapCamera(),
    navigating: Boolean = false,
    fixedZoom: Int? = null,
    ringKm: Float? = null,
    onTap: (() -> Unit)? = null,
) {
    val c = LocalColors.current
    val driveZoom = store.source.maxZoom
    // De viaje: andando o navegando; se acaba tras un rato detenido (un alto no lo quita).
    val moving = fixedZoom == null && ((kmh ?: 0f) >= DRIVE_KMH || navigating)
    LaunchedEffect(moving) {
        if (moving) camera.driving = true else {
            delay(STOP_MS)
            camera.driving = false
        }
    }
    val driving = camera.driving
    // Al empezar o terminar el viaje, lo que hayas acercado o alejado a mano se olvida (abrir en grande no lo cambia).
    LaunchedEffect(driving) {
        if (camera.zoomFor != driving) {
            camera.zoom = null
            camera.zoomFor = driving
        }
    }
    val manual = camera.zoom
    val base = fixedZoom ?: look.zoom.level ?: if (driving) driveZoom else CITY_ZOOM
    val zoom = fixedZoom ?: manual ?: base
    // En lo más cerca (de viaje llega solo; detenido, acercándolo o con "centrar"): alejado, plano.
    val tilted = fixedZoom == null && look.zoom == MapZoom.AUTO && zoom == driveZoom
    // Con dos dedos: mientras se pellizca el mapa se escala ([pinch]) y al doblar (o partir a la mitad) cambia de zoom.
    var pinch by remember { mutableFloatStateOf(1f) }
    // Centrar: el mapa se desliza hasta ti y luego se acerca paso a paso hasta lo más cerca, inclinado.
    val latestZoom by rememberUpdatedState(zoom)
    LaunchedEffect(camera.recenters) {
        if (camera.recenters == 0) return@LaunchedEffect
        val touched = camera.touches
        val fromX = camera.panX
        val fromY = camera.panY
        if (fromX != 0.0 || fromY != 0.0) {
            val start = withFrameNanos { it }
            var t = 0f
            while (t < 1f) {
                t = withFrameNanos { (it - start) / 1_000_000f / RECENTER_GLIDE_MS }.coerceAtMost(1f)
                // Lo tocaste en medio: se queda ahí.
                if (camera.touches != touched) return@LaunchedEffect
                val e = easeInOut(t)
                camera.panX = fromX * (1 - e)
                camera.panY = fromY * (1 - e)
            }
        }
        if (fixedZoom != null || look.zoom != MapZoom.AUTO) return@LaunchedEffect
        while (latestZoom < driveZoom) {
            val start = withFrameNanos { it }
            var t = 0f
            while (t < 1f) {
                t = withFrameNanos { (it - start) / 1_000_000f / ZOOM_STEP_MS }.coerceAtMost(1f)
                if (camera.touches != touched) {
                    pinch = 1f
                    return@LaunchedEffect
                }
                pinch = 1f + easeInOut(t)
            }
            camera.zoom = (latestZoom + 1).takeIf { it != base }
            pinch = 1f
            // Que se dibuje el zoom nuevo antes del siguiente paso.
            withFrameNanos { }
        }
        if (latestZoom > driveZoom) camera.zoom = driveZoom.takeIf { it != base }
    }
    val dark = look.dark(c.dark)
    val filter = if (dark && store.source.recolor) DarkTiles else null
    val accent = c.accent
    val glide = rememberGlide(lat, lon, bearing)
    // Inclinado siempre mira hacia donde vas (como Waze).
    val heading = tilted || look.orientation == MapOrientation.HEADING
    // Inclinado, tu posición va más abajo: se ve más camino adelante.
    val anchorY = if (tilted) 0.82f else 0.6f
    val sky = store.source.sky?.let(::Color) ?: if (dark) Color(0xFF1C1C20) else Color(0xFFE8E8EA)
    val onPinch by rememberUpdatedState { factor: Float ->
        var p = pinch * factor
        var z = zoom
        while (p >= 2f && z < driveZoom) { z++; p /= 2f }
        while (p <= 0.5f && z > MIN_ZOOM) { z--; p *= 2f }
        // En el límite solo cede un poco: se nota que ya no hay más.
        pinch = p.coerceIn(if (z <= MIN_ZOOM) 0.85f else 0.5f, if (z >= driveZoom) 1.15f else 2f)
        if (z != zoom) camera.zoom = z.takeIf { it != base }
    }
    val onPinchEnd by rememberUpdatedState {
        // Al soltar se queda en el zoom más cercano.
        val z = when {
            pinch > 1.41f -> (zoom + 1).coerceAtMost(driveZoom)
            pinch < 0.71f -> (zoom - 1).coerceAtLeast(MIN_ZOOM)
            else -> zoom
        }
        camera.zoom = z.takeIf { it != base }
        pinch = 1f
    }
    // La última proyección dibujada y cuántos px de pantalla mide el mundo: con eso un arrastre se vuelve movimiento del mapa.
    val drawn = remember { DrawnView() }
    val onPan by rememberUpdatedState { at: Offset, delta: Offset ->
        val projection = drawn.projection
        if (projection != null && drawn.worldPx > 0.0) {
            val a = FloatArray(3)
            val b = FloatArray(3)
            if (projection.toPlane(at.x - delta.x, at.y - delta.y, a) && projection.toPlane(at.x, at.y, b)) {
                // Cerca del horizonte un pixel del dedo son cientos de metros: no más de unas veces lo que se movió.
                var dx = (b[0] - a[0]).toDouble()
                var dy = (b[1] - a[1]).toDouble()
                val most = MAX_PAN_STRETCH * hypot(delta.x, delta.y)
                val length = hypot(dx, dy)
                if (length > most && length > 0.0) {
                    dx *= most / length
                    dy *= most / length
                }
                camera.touches++
                camera.panX = (camera.panX - dx / drawn.worldPx).coerceIn(-camera.maxPan, camera.maxPan)
                camera.panY = (camera.panY - dy / drawn.worldPx).coerceIn(-camera.maxPan, camera.maxPan)
            }
        }
    }
    // Movido con el dedo y sin tocarlo un rato: regresa a ti solo (como Waze). Sin recomponer en cada movimiento del dedo.
    LaunchedEffect(camera) {
        // Solo de viaje: detenido, el mapa se queda donde lo dejaste.
        snapshotFlow { Triple(camera.centered, camera.panX + camera.panY, driving) }.collectLatest { (centered, _, onTrip) ->
            if (!centered && camera.returns && onTrip) {
                delay(RECENTER_MS)
                camera.recenter()
            }
        }
    }
    // La calle donde estás: al moverte o cuando llegan los nombres de tu tesela (no con cada tesela).
    LaunchedEffect(store, lat, lon) {
        val origin = tileKey(DATA_ZOOM, tileX(lon, DATA_ZOOM).toInt(), tileY(lat, DATA_ZOOM).toInt())
        snapshotFlow { store.loaded.let { store.source.names(origin) } }.distinctUntilChanged().collect { names ->
            camera.street = names?.let { nearestStreet(origin, it, lat, lon) }
        }
    }
    val labels = remember { LabelCache() }
    val labelColor = if (dark) Color(0xFFC3C6CD) else Color(0xFF4E535C)
    // Un toque: lo que diga [onTap] (en el widget, verlo en grande o abrir tu app de mapas).
    val tap = if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier
    BoxWithConstraints(
        modifier.background(sky).then(tap).pointerInput(Unit) {
            detectMapGestures({ at, delta -> onPan(at, delta) }, { if (fixedZoom == null) onPinch(it) }, { if (fixedZoom == null) onPinchEnd() })
        },
    ) {
        val short = maxWidth < SHORT_ATTRIBUTION
        Spacer(
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = pinch
                scaleY = pinch
                transformOrigin = TransformOrigin(0.5f, anchorY)
            }.drawWithCache {
                // Se arma en cada paso del deslizamiento, al mover el mapa y al llegar una tesela (sin recomponer).
                store.loaded // cada tesela que llega rehace esto (y los nombres nuevos se acomodan)
                val pose = glide()
                val turn = if (heading) -(pose.bearing ?: 0f) else 0f
                // Siempre flecha: hacia arriba con el mapa girado, o con el último rumbo (sin rumbo aún, al norte).
                val arrow = if (heading) 0f else pose.bearing ?: 0f
                val at = Offset(size.width / 2, size.height * anchorY)
                val projection = MapProjection(size.width, size.height, at.x, at.y, turn, tilted, density)
                val ts = tilePx(density)
                val n = (1 shl zoom).toDouble()
                // El centro del mapa: tú, o a donde lo moviste.
                val fx = tileX(pose.lon, zoom) + camera.panX * n
                val fy = tileY(pose.lat, zoom) + camera.panY * n
                drawn.projection = projection
                drawn.worldPx = ts * n
                val matrix = AndroidMatrix().apply { setValues(projection.values) }
                val tiles = visibleTiles(projection, fx, fy, zoom, at, ts)
                // Tú en pantalla (fuera de la vista si moviste el mapa lejos).
                val you = FloatArray(3)
                val youShown = projection.toScreen(at.x - (camera.panX * n * ts).toFloat(), at.y - (camera.panY * n * ts).toFloat(), you)
                // Lo lejano se funde con el cielo: sólido hasta donde se acaba el mapa (en pantalla) y de ahí
                // se desvanece; sin orilla dura en ningún tamaño de widget.
                val fade = if (tilted) {
                    val end = (projection.edge / size.height).coerceIn(0f, 0.9f)
                    Brush.verticalGradient(0f to sky, end to sky, (end + 0.15f).coerceAtMost(1f) to sky.copy(alpha = 0f))
                } else null
                val arrowPath = arrowPath(Offset(you[0], you[1]), 1.dp.toPx())
                // El círculo de la zona: metros por px del plano en el centro del mapa.
                val ring = ringKm?.let { km ->
                    val (centerLat, _) = camera.center(pose.lat, pose.lon)
                    val metersPerPx = EARTH_METERS * cos(Math.toRadians(centerLat)) / (n * ts)
                    (km * 1000 / metersPerPx).toFloat()
                }
                val text = labels.paint(12.sp.toPx(), labelColor, sky)
                val runs = labels.runs(store, tiles, zoom, ts, density)
                val top = if (tilted) projection.edge.coerceAtLeast(0f) + size.height * 0.12f else 0f
                val placed = placeLabels(runs, size.width, size.height, top, text.textSize, density, MAX_LABELS) { x, y, out ->
                    projection.toScreen(at.x + ((x - fx) * ts).toFloat(), at.y + ((y - fy) * ts).toFloat(), out)
                }
                onDrawBehind {
                    drawIntoCanvas { canvas ->
                        canvas.save()
                        canvas.nativeCanvas.concat(matrix)
                        drawTiles(store, tiles, filter)
                        canvas.restore()
                    }
                    store.request(tiles.keys)
                    fade?.let { drawRect(it) }
                    ring?.let {
                        drawCircle(accent.copy(alpha = 0.12f), it, at)
                        drawCircle(accent, it, at, style = Stroke(2.dp.toPx()))
                    }
                    drawIntoCanvas { canvas -> drawLabels(canvas.nativeCanvas, placed, text) }
                    if (youShown) drawArrow(Offset(you[0], you[1]), arrowPath, arrow, accent, tilted)
                }
            },
        )
        Attribution(if (short) store.source.attributionShort else store.source.attribution, Modifier.align(Alignment.BottomEnd))
    }
}

/** Lo último que se dibujó, para convertir arrastres de la pantalla en movimiento del mapa. */
private class DrawnView {
    var projection: MapProjection? = null
    /** Lo que mide el mundo entero en px del plano del mapa (tesela × 2^zoom). */
    var worldPx = 0.0
}

/** Los tramos con nombre de las teselas que se ven (se rehacen solo al cambiar de teselas o de zoom, o al llegar nombres nuevos). */
private class LabelCache {
    private var key: Any? = null
    private var cached: List<LabelRun> = emptyList()
    private var fill: android.graphics.Paint? = null
    private var halo = 0
    /** Lo que mide cada nombre con [fill] (medir texto cuesta). */
    private val widths = HashMap<String, Float>()

    fun paint(size: Float, color: Color, halo: Color): android.graphics.Paint {
        val p = fill
        if (p != null && p.textSize == size && p.color == color.toArgb() && this.halo == halo.toArgb()) return p
        this.halo = halo.toArgb()
        widths.clear()
        key = null
        return android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color.toArgb()
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            // El halo del color del fondo: se lee sobre las calles.
            setShadowLayer(size * 0.18f, 0f, 0f, halo.toArgb())
        }.also { fill = it }
    }

    fun runs(store: TileStore, tiles: TilePlan, z: Int, ts: Float, dp: Float): List<LabelRun> {
        // Solo lo cercano (con todo el detalle): lejos, en el mapa inclinado, los nombres ni se leerían.
        val names = HashMap<Long, TileNames>()
        val origins = HashSet<Long>()
        for (i in tiles.z.indices) {
            if (tiles.z[i] != z) continue
            val o = store.source.origin(tileKey(tiles.z[i], tiles.x[i], tiles.y[i]))
            if (origins.add(o)) store.source.names(o)?.let { names[o] = it }
        }
        // Cambia solo con otras teselas, otro zoom o al llegar los nombres de una (no con cada tesela que llega).
        val newKey = Triple(origins, z, names.size)
        if (newKey == key) return cached
        val measure = fill ?: return emptyList()
        key = newKey
        cached = labelRuns(names, z, ts, dp) { name -> widths.getOrPut(name) { measure.measureText(name) } }
        return cached
    }
}

/** Los nombres ya acomodados, derechos y centrados en su tramo. */
private fun drawLabels(canvas: android.graphics.Canvas, labels: List<PlacedLabel>, paint: android.graphics.Paint) {
    val baseline = paint.textSize * 0.35f
    for (l in labels) {
        canvas.save()
        canvas.translate(l.x, l.y)
        canvas.rotate(l.degrees)
        canvas.drawText(l.name, -l.width / 2, baseline, paint)
        canvas.restore()
    }
}

/** Dónde se ve tu posición: latitud, longitud y rumbo. */
private class Pose(val lat: Double, val lon: Double, val bearing: Float?)

/**
 * Tu posición deslizándose de la lectura anterior del GPS a la nueva en [GLIDE_MS] (llegan una por segundo: sin esto
 * la flecha brinca), a [GLIDE_STEP_MS] por paso (las pantallas de auto no dan para 60 cuadros). Saltos grandes, o
 * moverse menos que [STILL_METERS] (el GPS baila estando quieto), van directo: un solo cuadro. Se lee al dibujar.
 */
@Composable
private fun rememberGlide(lat: Double, lon: Double, bearing: Float?): () -> Pose {
    // De dónde a dónde va y cuánto lleva; los dos son estado: un salto directo también repinta.
    var ends by remember { mutableStateOf(Pose(lat, lon, bearing) to Pose(lat, lon, bearing)) }
    var t by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(lat, lon, bearing) {
        val now = between(ends, t)
        val target = Pose(lat, lon, bearing)
        val meters = distanceMeters(now.lat, now.lon, lat, lon)
        val turned = if (now.bearing != null && bearing != null) abs(((bearing - now.bearing) % 360f + 540f) % 360f - 180f) else 0f
        if (meters > SNAP_METERS || (meters < STILL_METERS && turned < STILL_DEGREES)) {
            ends = target to target
            t = 1f
            return@LaunchedEffect
        }
        ends = now to target
        t = 0f
        val start = withFrameNanos { it }
        while (t < 1f) {
            delay(GLIDE_STEP_MS)
            t = ((withFrameNanos { it } - start) / 1_000_000f / GLIDE_MS).coerceAtMost(1f)
        }
    }
    return { between(ends, t) }
}

/** La posición a [t] (0–1) del camino [ends]; el rumbo gira por el lado corto. */
private fun between(ends: Pair<Pose, Pose>, t: Float): Pose {
    val (from, to) = ends
    val b = when {
        to.bearing == null -> null
        from.bearing == null -> to.bearing
        else -> from.bearing + (((to.bearing - from.bearing) % 360f + 540f) % 360f - 180f) * t
    }
    return Pose(from.lat + (to.lat - from.lat) * t, from.lon + (to.lon - from.lon) * t, b)
}

/** Distancia aproximada en metros (basta para decidir si deslizar). */
private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dy = (lat2 - lat1) * 111_320.0
    val dx = (lon2 - lon1) * 111_320.0 * cos(Math.toRadians(lat1))
    return hypot(dx, dy)
}

/**
 * Los gestos del mapa: con un dedo se mueve ([onPan] con dónde está el dedo y cuánto se movió), con dos se acerca
 * o aleja ([onZoom] con cuánto creció la separación; [onEnd] al soltar). Un toque sin moverse no se toca (el toque
 * y el acomodo del widget siguen funcionando).
 */
private suspend fun PointerInputScope.detectMapGestures(onPan: (Offset, Offset) -> Unit, onZoom: (Float) -> Unit, onEnd: () -> Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var pinched = false
        var panning = false
        var travel = Offset.Zero
        do {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size >= 2) {
                val factor = event.calculateZoom()
                if (factor != 1f) onZoom(factor)
                pinched = true
            } else if (!pinched && pressed.size == 1) {
                val change = pressed[0]
                val delta = change.positionChange()
                if (!panning) {
                    travel += delta
                    panning = travel.getDistance() > viewConfiguration.touchSlop
                } else if (delta != Offset.Zero) {
                    onPan(change.position, delta)
                }
            }
            // Ya movido o pellizcado, todo es del mapa (también al soltar): si no, levantar el dedo contaba como toque.
            if (pinched || panning) event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
        if (pinched) onEnd()
    }
}

/**
 * Las teselas a dibujar alrededor del centro ([fx], [fy] en teselas del zoom [z]), de lo lejano a lo cercano (lo
 * cercano encima): su zoom, clave y dónde van en el plano del mapa (en px, ya redondeado). Inclinado, lo lejano va con menos detalle ([detailDrop]).
 */
internal class TilePlan(val keys: Set<Long>, val z: IntArray, val x: IntArray, val y: IntArray, val left: IntArray, val top: IntArray, val right: IntArray, val bottom: IntArray)

internal fun visibleTiles(projection: MapProjection, fx: Double, fy: Double, z: Int, at: Offset, ts: Float): TilePlan {
    val span = projection.visibleTiles(fx, fy, ts)
    // Cada tesela visible, o la de menos detalle que la cubre (una sola vez aunque cubra varias).
    val picked = LinkedHashMap<Long, IntArray>()
    for (i in span.indices step 2) {
        val tx = span[i]
        val ty = span[i + 1]
        val drop = detailDrop(projection.scaleOf(fx, fy, ts, tx, ty)).coerceAtMost(z - LOD_MIN_ZOOM).coerceAtLeast(0)
        val dz = z - drop
        val ax = Math.floorDiv(tx, 1 shl drop)
        val ay = Math.floorDiv(ty, 1 shl drop)
        val n = 1 shl dz
        if (ay !in 0 until n) continue
        picked.getOrPut(tileKey(dz, Math.floorMod(ax, n), ay)) { intArrayOf(dz, ax, ay) }
    }
    // Lo lejano (menos zoom) primero: lo cercano se dibuja encima y tapa las orillas.
    val order = picked.entries.sortedBy { it.value[0] }
    val count = order.size
    val zs = IntArray(count); val x = IntArray(count); val y = IntArray(count)
    val left = IntArray(count); val top = IntArray(count); val right = IntArray(count); val bottom = IntArray(count)
    for ((i, entry) in order.withIndex()) {
        val (dz, ax, ay) = entry.value
        val scale = (1 shl (z - dz)).toDouble()
        val size = ts * scale
        zs[i] = dz
        x[i] = Math.floorMod(ax, 1 shl dz)
        y[i] = ay
        // Bordes redondeados a píxel con el de la siguiente: sin rayas entre teselas.
        left[i] = (at.x + (ax * scale - fx) * ts).roundToInt()
        top[i] = (at.y + (ay * scale - fy) * ts).roundToInt()
        right[i] = (at.x + (ax * scale - fx) * ts + size).roundToInt()
        bottom[i] = (at.y + (ay * scale - fy) * ts + size).roundToInt()
    }
    return TilePlan(picked.keys, zs, x, y, left, top, right, bottom)
}

/** Lo lejano no baja de este zoom (más allá, las calles ya ni se distinguen). */
private const val LOD_MIN_ZOOM = 12

private fun DrawScope.drawTiles(store: TileStore, plan: TilePlan, filter: ColorFilter?) {
    for (i in plan.x.indices) {
        val z = plan.z[i]
        val x = plan.x[i]
        val y = plan.y[i]
        val offset = IntOffset(plan.left[i], plan.top[i])
        val dst = IntSize(plan.right[i] - plan.left[i], plan.bottom[i] - plan.top[i])
        val image = store.tile(tileKey(z, x, y))
        if (image != null) {
            drawImage(image, dstOffset = offset, dstSize = dst, colorFilter = filter, filterQuality = FilterQuality.Low)
        } else {
            // Mientras llega, el cuarto de la tesela del zoom anterior (al cambiar de zoom no se ve vacío).
            val parent = store.tile(tileKey(z - 1, x / 2, y / 2)) ?: continue
            val half = parent.width / 2
            drawImage(
                parent, IntOffset((x % 2) * half, (y % 2) * half), IntSize(half, half),
                offset, dst, colorFilter = filter, filterQuality = FilterQuality.Low,
            )
        }
    }
}

/** Tu posición: la flecha con el rumbo (color de acento) o un punto si todavía no hay rumbo. Inclinado, un poco acostada. */
private fun DrawScope.drawArrow(at: Offset, arrow: Path, bearing: Float, accent: Color, tilted: Boolean) {
    val u = 1.dp.toPx()
    if (tilted) return scale(1f, 0.7f, at) { drawArrow(at, arrow, bearing, accent, tilted = false) }
    drawCircle(accent.copy(alpha = 0.22f), 34 * u, at)
    rotate(bearing, at) {
        drawPath(arrow, accent)
        drawPath(arrow, Color.White, style = Stroke(3 * u))
    }
}

/** La flecha de [drawArrow] apuntando al norte en [at] ([u] = 1 dp); se arma con la caché del dibujo, no en cada cuadro. */
private fun arrowPath(at: Offset, u: Float) = Path().apply {
    moveTo(at.x, at.y - 22 * u)
    lineTo(at.x + 16 * u, at.y + 17 * u)
    lineTo(at.x, at.y + 8 * u)
    lineTo(at.x - 16 * u, at.y + 17 * u)
    close()
}

/** Los créditos en la esquina (lo piden sus licencias); tocarlos abre su página. */
@Composable
private fun Attribution(text: String, modifier: Modifier) {
    val c = LocalColors.current
    val uri = LocalUriHandler.current
    Label(
        text,
        Typography.small.copy(fontSize = 11.sp),
        c.text,
        // En mapas chicos baja a otra línea: las licencias piden que se lea completo (sin "…").
        maxLines = 2,
        modifier = modifier
            .padding(6.dp)
            .clip(RoundedCornerShape(Radius.small))
            .background(c.surface.copy(alpha = 0.75f))
            .clickable { runCatching { uri.openUri(ATTRIBUTION_URL) } }
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * Mapa claro → oscuro, como el `darken()` de StradaOS: invierte, gira el tono 180° (el agua
 * sigue azul y los parques verdes, el texto queda claro) y aclara un poco.
 */
private val DarkTiles: ColorFilter by lazy {
    val m = android.graphics.ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f))
    m.postConcat(android.graphics.ColorMatrix(floatArrayOf(
        -0.574f, 1.43f, 0.144f, 0f, 0f,
        0.426f, 0.43f, 0.144f, 0f, 0f,
        0.426f, 1.43f, -0.856f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )))
    m.postConcat(android.graphics.ColorMatrix(floatArrayOf(0.8f, 0f, 0f, 0f, 40f, 0f, 0.8f, 0f, 0f, 40f, 0f, 0f, 0.84f, 0f, 44f, 0f, 0f, 0f, 1f, 0f)))
    ColorFilter.colorMatrix(ColorMatrix(m.array))
}
