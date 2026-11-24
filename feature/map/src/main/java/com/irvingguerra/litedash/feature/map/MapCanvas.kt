package com.irvingguerra.litedash.feature.map

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Radius
import com.irvingguerra.litedash.core.ui.Typography
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

private const val TILE = 256

/** Desde esta velocidad vas de viaje; detenido más de [STOP_MS], ya no. */
private const val DRIVE_KMH = 12f
private const val STOP_MS = 25_000L

/** Para elegir una zona el mapa se mueve hasta ~2 000 km de ti. */
private const val FREE_PAN = 0.05

/** Movido con el dedo, regresa a ti tras este rato sin tocarlo. */
private const val RECENTER_MS = 20_000L

/** Cuánto tarda en deslizarse hasta ti al centrar. */
private const val RECENTER_GLIDE_MS = 900f

/** El zoom animado (al centrar o al soltar los dedos): tanto por nivel, entre un mínimo y un máximo. */
private const val ZOOM_LEVEL_MS = 260f
private const val ZOOM_MIN_MS = 140f
private const val ZOOM_MAX_MS = 1100f

/** Agrandado más (o achicado menos) que esto, se pasa al nivel de teselas de junto: √2 y su inverso. */
internal const val SCALE_UP = 1.4142f
internal const val SCALE_DOWN = 0.7071f

/** En el nivel más cercano o el más lejano, los dedos solo ceden esto: se nota que ya no hay más. */
private const val EDGE_DOWN = 0.85f
private const val EDGE_UP = 1.15f

/** Lo que baja tu posición en la vista de plano a inclinado (se ve más camino adelante). */
private const val ANCHOR_FLAT = 0.6f
private const val ANCHOR_TILTED = 0.82f

/** Plano (widget, fondo, en grande), el zoom automático va tantos niveles más lejos que lo más cerca de la fuente. */
private const val FLAT_ZOOM_DROP = 2

/**
 * Navegando, detenido o despacio, más cerca que lo más cerca de la fuente (como Waze): las teselas del último nivel se
 * agrandan hasta tantos niveles más (×2 por nivel).
 */
internal const val NAV_OVERZOOM = 1f

/** Navegando: lo que se aleja el zoom de detenido a [NAV_FAST_KMH] o más (más camino a la vista rápido). */
private const val NAV_ZOOM_DROP = 3f
private const val NAV_FAST_KMH = 110f

/** Navegando con «siempre al norte» (plano), tanto más lejos: plano a lo más cerca se ve muy poco. */
private const val NAV_NORTH_DROP = 1f

/** Cerca de la vuelta ([MapCanvas] `turnAhead`): un poco más cerca y menos inclinado, para ver bien la esquina. */
private const val NAV_TURN_ZOOM = 0.4f
private const val NAV_TURN_TILT = 0.4f

/** Lo más rápido que cambian el zoom (niveles por segundo, como Horizon) y la inclinación (0–1 por segundo). */
private const val NAV_ZOOM_RATE = 0.6f
private const val NAV_TILT_RATE = 0.8f

/** El zoom de la navegación se pide en pasos de esto (la velocidad baila y no debe reiniciar el cambio a cada rato). */
private const val NAV_ZOOM_GRAIN = 0.05f

/** Con «Automático», lo que tarda en pasar de norte arriba a hacia donde vas al empezar o acabar un viaje. */
private const val AUTO_TURN_MS = 900

/** Arranca y llega suave. */
private fun easeInOut(t: Float): Float = if (t < 0.5f) 2 * t * t else 1 - (-2 * t + 2).let { it * it } / 2

/** Lo más que se aleja el mapa de ti con el dedo (fracción del mundo, ~80 km). */
private const val MAX_PAN = 0.002

/** El zoom para enseñar un lugar buscado: se ven sus calles alrededor. */
private const val PLACE_ZOOM = 16

/** En el plano, un movimiento del dedo no avanza más de tantas veces lo que se movió en pantalla. */
private const val MAX_PAN_STRETCH = 4.0

/** Lo que tarda tu flecha en deslizarse a la lectura nueva del GPS (llegan una por segundo). */
private const val GLIDE_MS = 950

/** Cada cuánto avanza el deslizamiento (~30 cuadros por segundo). */
private const val GLIDE_STEP_MS = 33L

/** Lo que tarda una flechita de la ruta en llegar a donde estaba la siguiente. */
private const val FLOW_CYCLE_MS = 1600.0

/** Con esta memoria por app (MB) o menos, la ruta no se anima. */
private const val FLOW_MIN_MEMORY_MB = 128

/**
 * ¿La pantalla da para animar la ruta? Android 9 o más nuevo (antes cada trazo se vuelve imagen) y que no sea de poca
 * memoria; si no, la ruta va quieta.
 */
internal fun routeCanFlow(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
    val am = context.getSystemService(ActivityManager::class.java) ?: return false
    return !am.isLowRamDevice && am.memoryClass > FLOW_MIN_MEMORY_MB
}

/** Más que esto de una lectura a otra (o casi nada, quieto) va directo, sin deslizar. */
private const val SNAP_METERS = 150.0
private const val STILL_METERS = 3.0
private const val STILL_DEGREES = 3f

/** Más lento que esto (detenido o a vuelta de rueda) tampoco se desliza: el GPS baila varios metros y animaría sin parar. */
private const val CREEP_KMH = 4f

/** El lado de una tesela en pantalla: 256 px a densidad 1, hasta el doble (más se vería borrosa). */
fun tilePx(density: Float) = TILE * density.coerceIn(1f, 2f)

/** Abajo de este ancho los créditos van en su versión corta. */
private val SHORT_ATTRIBUTION = 320.dp

/** Los créditos llevan aquí (lo pide la licencia de los datos de OpenStreetMap). */
private const val ATTRIBUTION_URL = "https://www.openstreetmap.org/copyright"

/** El zoom con fracción mientras se pellizca: el nivel de teselas y cuánto se agranda ([MapCamera.scale]). */
internal class ZoomStep(val level: Int, val scale: Float)

/**
 * Dos dedos agrandan [scale] por [factor] en el nivel [zoom]: pasada la mitad ([SCALE_UP]/[SCALE_DOWN]) se cambia al
 * nivel de junto, entre [min] y [max]; en esos topes solo cede [EDGE_DOWN]–[EDGE_UP] (o lo que ya venía agrandado al
 * soltar la navegación, que llega a [NAV_OVERZOOM] más cerca: no brinca).
 */
internal fun pinchStep(scale: Float, factor: Float, zoom: Int, min: Int, max: Int): ZoomStep {
    var p = scale * factor
    var z = zoom
    while (p >= SCALE_UP && z < max) { z++; p /= 2f }
    while (p < SCALE_DOWN && z > min) { z--; p *= 2f }
    return ZoomStep(z, p.coerceIn(if (z <= min) EDGE_DOWN else SCALE_DOWN, if (z >= max) maxOf(EDGE_UP, scale) else SCALE_UP))
}

/** El zoom con fracción [z] como nivel de teselas (el más cercano, entre [min] y [max]) y lo que se agranda ese nivel. */
internal fun zoomStep(z: Float, min: Int, max: Int): ZoomStep {
    val level = z.roundToInt().coerceIn(min, max)
    return ZoomStep(level, 2f.pow(z - level))
}

/** Cuánto se inclina el mapa (0–1) en el zoom con fracción [z]: del todo en [driveZoom] y nada un nivel más lejos. */
internal fun tiltFor(z: Float, driveZoom: Float): Float = (z - (driveZoom - 1)).coerceIn(0f, 1f)

/**
 * El zoom (con fracción) de la navegación a [kmh]: detenido, [NAV_OVERZOOM] más cerca que lo más cerca de la fuente
 * ([driveZoom]) y [NAV_ZOOM_DROP] niveles más lejos desde [NAV_FAST_KMH]; [northUp] (plano) aleja uno. Con [turnAhead]
 * se acerca un poco, pero nunca más que [driveZoom]: la esquina debe quedar a la vista. En pasos de [NAV_ZOOM_GRAIN].
 */
internal fun navZoomFor(kmh: Float?, driveZoom: Int, turnAhead: Boolean, northUp: Boolean): Float {
    val fast = ((kmh ?: 0f) / NAV_FAST_KMH).coerceIn(0f, 1f)
    val z = driveZoom + NAV_OVERZOOM - NAV_ZOOM_DROP * fast + (if (turnAhead) NAV_TURN_ZOOM else 0f) - (if (northUp) NAV_NORTH_DROP else 0f)
    return ((z / NAV_ZOOM_GRAIN).roundToInt() * NAV_ZOOM_GRAIN).coerceAtMost(driveZoom + if (turnAhead) 0f else NAV_OVERZOOM)
}

/** [from] acercándose a [to] a lo más [step]. */
internal fun approach(from: Float, to: Float, step: Float): Float = if (abs(to - from) <= step) to else from + if (to > from) step else -step

/**
 * Lo que la app ve del mapa y le puede pedir: si lo moviste con el dedo ([centered] = false; de viaje vuelve solo tras
 * [RECENTER_MS] sin tocarlo; [recenter] te regresa al zoom de fábrica), mirar otro lugar ([lookAt]), la calle donde
 * estás ([street]) y, navegando, si te sigue ([following]). El widget y su mapa en grande comparten cámara: el zoom y el regreso a ti
 * los anima uno solo, y lo que llega (los dedos o una animación nueva) detiene a lo anterior donde iba.
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

    /** Cuántas veces se pidió centrar (el mapa vuelve a ti y a su zoom de fábrica). */
    internal var recenters by mutableIntStateOf(0)

    /** Los centrados ya atendidos (acabados o detenidos con el dedo). */
    private var handled = 0

    /** Un centrado se quedó a medias porque su mapa dejó de verse: sube y el otro mapa lo retoma. */
    internal var handOffs by mutableIntStateOf(0)

    /** Regresa a ti deslizándose (lo anima `MapCanvas`) y vuelve al zoom de fábrica (navegando, a seguirte inclinada). */
    fun recenter() {
        place = null
        reach = 0.0
        recenters++
    }

    /** Lo más que se aleja el centro mientras enseña un lugar ([show]); 0 = solo [maxPan]. */
    private var reach by mutableDoubleStateOf(0.0)

    /** Hasta dónde se deja mover el centro de ti ahora (fracción del mundo). */
    internal val panLimit: Double get() = maxOf(maxPan, reach)

    /** El lugar que se enseña ([show]): el centro se queda en él aunque te muevas, hasta moverlo con el dedo o centrar. */
    internal var place by mutableStateOf<Pair<Double, Double>?>(null)

    /** ¿Se enseña (o se enseñó y se movió con el dedo) un lugar lejos de ti? Centrar lo termina. */
    val showingPlace: Boolean get() = reach > 0.0

    /**
     * Enseña el lugar ([lat], [lon]): el centro va ahí aunque esté lejos (cuando ya se sabe dónde estás) y el mapa se
     * aleja a un zoom de ver calles. Se queda así hasta centrar (no regresa solo). [scale] = cuánto se agranda o achica
     * el nivel fijo de quien lo pone (el encuadre de una ruta).
     */
    fun show(lat: Double, lon: Double, scale: Float = 1f) {
        stopPan()
        stopZoom()
        place = lat to lon
        reach = FREE_PAN
        zoom = PLACE_ZOOM
        this.scale = scale
        following = false
    }

    /** Pone el centro en [place] estando tú en ([lat], [lon]). */
    internal fun aim(lat: Double, lon: Double) {
        val (pLat, pLon) = place ?: return
        panX = (tileX(pLon, 0) - tileX(lon, 0)).coerceIn(-FREE_PAN, FREE_PAN)
        panY = (tileY(pLat, 0) - tileY(lat, 0)).coerceIn(-FREE_PAN, FREE_PAN)
    }

    /** El zoom que pusiste con dos dedos o al centrar (null = el de fábrica): igual en el widget y en grande. */
    internal var zoom by mutableStateOf<Int?>(null)

    /**
     * Cuánto se ve agrandado el nivel [zoom] ([SCALE_DOWN]–[SCALE_UP]; 1 = tal cual): el zoom con fracción mientras se
     * pellizca o se anima. Solo lo lee el dibujo (cambiarlo no recompone).
     */
    internal var scale by mutableFloatStateOf(1f)

    /** ¿Quedó entre dos niveles de zoom ([scale] ≠ 1: un pellizco o un encuadre a medias)? [recenter] lo acomoda. */
    val scaled: Boolean get() = scale != 1f

    /** De viaje (andando o navegando); se guarda aquí para que el widget y el mapa en grande coincidan. */
    internal var driving by mutableStateOf(false)

    /**
     * La cámara de navegación (`MapCanvas` con `navigation`): te sigue inclinada con el zoom de la velocidad ([navZoom] y
     * [navTilt], que el mapa cambia despacio) hasta que la mueves o pellizcas; entonces se suelta y [recenter] la regresa.
     */
    var following by mutableStateOf(true)
        internal set
    internal var navZoom by mutableFloatStateOf(Float.NaN)
    internal var navTilt by mutableFloatStateOf(1f)

    /** Suelta: el zoom y la inclinación que llevaba al soltarse (al alejarse un nivel de ahí queda plano). */
    internal var followZoom = 0f
    internal var followTilt = 0f

    /** Un dedo toma la cámara de navegación: deja de seguirte donde iba (el zoom con fracción pasa a [zoom] y [scale]). */
    internal fun letGo(base: Int, max: Int) {
        if (!following || navZoom.isNaN()) return
        val step = zoomStep(navZoom, MIN_ZOOM, max)
        zoom = step.level.takeIf { it != base }
        scale = step.scale
        followZoom = navZoom
        followTilt = navTilt
        following = false
    }

    /** Pasos de zoom pedidos con botones ([zoomBy]) que el mapa aún no atiende (+ acerca, − aleja). */
    internal var zoomSteps by mutableIntStateOf(0)

    /**
     * Acerca ([steps] > 0) o aleja un nivel por paso, deslizándose como al soltar los dos dedos (para botones + y −:
     * en pantallas de auto el pellizco falla). Entre el nivel más lejano y el más cercano de la fuente; con un zoom fijo
     * (elegir una zona) no hace nada.
     */
    fun zoomBy(steps: Int) {
        zoomSteps += steps
    }

    /** El último [driving] que se atendió: al empezar un viaje el mapa vuelve a su zoom de fábrica. */
    internal var zoomFor = false

    /** La animación del zoom, la del centrado y su parte de deslizarse (la que un dedo detiene). */
    private var zoomAnimation: Job? = null
    private var recentering: Job? = null
    private var panAnimation: Job? = null
    private var panStopped = false

    /** Los dedos toman el zoom: la animación que iba se queda donde estaba. */
    internal fun stopZoom() {
        zoomAnimation?.cancel()
        zoomAnimation = null
    }

    /** Un dedo mueve el mapa: si se deslizaba hacia ti, se queda donde lo dejaste. */
    internal fun stopPan() {
        panAnimation?.let {
            panStopped = true
            it.cancel()
        }
        panAnimation = null
    }

    /**
     * Lleva el zoom desde [from] (con la fracción de [scale]) hasta [target] sin brincos, en [scope]: el nivel de teselas
     * cambia al pasar la mitad entre dos niveles y [scale] pone el resto. [base] = el de fábrica (se guarda como null).
     * Detiene la animación de zoom que fuera (la nueva sigue desde donde iba).
     */
    internal fun glideZoom(scope: CoroutineScope, from: Int, target: Int, base: Int, max: Int): Job {
        zoomAnimation?.cancel()
        val job = scope.launch {
            val me = currentCoroutineContext().job
            val start = from + log2(scale)
            val span = target - start
            val ms = (ZOOM_LEVEL_MS * abs(span)).coerceIn(ZOOM_MIN_MS, ZOOM_MAX_MS)
            var settled = false
            try {
                if (abs(span) > 0.01f) {
                    val began = withFrameNanos { it }
                    var t = 0f
                    while (t < 1f) {
                        t = ((withFrameNanos { it } - began) / 1_000_000f / ms).coerceAtMost(1f)
                        val step = zoomStep(start + span * easeInOut(t), MIN_ZOOM, max)
                        zoom = step.level.takeIf { it != base }
                        scale = step.scale
                    }
                }
                zoom = target.takeIf { it != base }
                scale = 1f
                settled = true
            } finally {
                // Cancelado a medias porque el mapa dejó de verse (no porque otro tomó el zoom): que no quede entre dos niveles.
                if (zoomAnimation === me) {
                    zoomAnimation = null
                    if (!settled) scale = 1f
                }
            }
        }
        zoomAnimation = job
        return job
    }

    /**
     * Regresa a seguirte (navegando): el zoom se desliza hasta el de la velocidad ([navZoom], que puede cambiar en el
     * camino) y la inclinación sube con él; al llegar, [following]. Los dedos la detienen donde iba ([stopZoom]).
     */
    private fun glideFollow(scope: CoroutineScope, base: Int, max: Int): Job {
        zoomAnimation?.cancel()
        val job = scope.launch {
            val me = currentCoroutineContext().job
            try {
                if (!navZoom.isNaN()) {
                    val start = (zoom ?: base) + log2(scale)
                    followTilt = navTilt
                    val ms = (ZOOM_LEVEL_MS * abs(navZoom - start)).coerceIn(ZOOM_MIN_MS, ZOOM_MAX_MS)
                    val began = withFrameNanos { it }
                    var t = 0f
                    while (t < 1f) {
                        t = ((withFrameNanos { it } - began) / 1_000_000f / ms).coerceAtMost(1f)
                        followZoom = navZoom
                        val step = zoomStep(start + (navZoom - start) * easeInOut(t), MIN_ZOOM, max)
                        zoom = step.level.takeIf { it != base }
                        scale = step.scale
                    }
                }
                zoom = null
                scale = 1f
                following = true
            } finally {
                if (zoomAnimation === me) zoomAnimation = null
            }
        }
        zoomAnimation = job
        return job
    }

    /**
     * Centrar: se desliza hasta ti y luego, si [zooms], vuelve al zoom de fábrica [base] (con [follow], a seguirte), de
     * corrido. Lo anima un solo mapa a la vez. Si su mapa dejó de verse a medias ([abandoned]), lo deja para el otro ([handOffs]).
     */
    internal suspend fun animateRecenter(base: Int, max: Int, zooms: Boolean, follow: Boolean, abandoned: () -> Boolean) {
        if (recenters == handled || recentering?.isActive == true) return
        val me = currentCoroutineContext().job
        val asked = recenters
        recentering = me
        var done = false
        try {
            val fromX = panX
            val fromY = panY
            if (fromX != 0.0 || fromY != 0.0) {
                panAnimation = me
                val start = withFrameNanos { it }
                var t = 0f
                while (t < 1f) {
                    t = withFrameNanos { (it - start) / 1_000_000f / RECENTER_GLIDE_MS }.coerceAtMost(1f)
                    val e = easeInOut(t)
                    panX = fromX * (1 - e)
                    panY = fromY * (1 - e)
                }
                panAnimation = null
            }
            // Los dedos en medio del zoom lo detienen: el centrado igual cuenta como hecho.
            if (zooms) coroutineScope { if (follow) glideFollow(this, base, max) else glideZoom(this, zoom ?: base, base, base, max) }
            done = true
        } finally {
            if (recentering === me) recentering = null
            if (panAnimation === me) panAnimation = null
            when {
                done || panStopped -> handled = maxOf(handled, asked)
                abandoned() -> handOffs++
            }
            panStopped = false
        }
    }

    /** Mueve el centro del mapa a ([lat], [lon]) estando tú en ([fromLat], [fromLon]) (hasta donde deja [maxPan]). */
    fun lookAt(lat: Double, lon: Double, fromLat: Double, fromLon: Double) {
        panX = (tileX(lon, 0) - tileX(fromLon, 0)).coerceIn(-panLimit, panLimit)
        panY = (tileY(lat, 0) - tileY(fromLat, 0)).coerceIn(-panLimit, panLimit)
    }

    /** El centro del mapa (latitud, longitud) estando tú en [lat], [lon]: tú mismo, o a donde lo moviste. */
    fun center(lat: Double, lon: Double): Pair<Double, Double> =
        tileLat(tileY(lat, 0) + panY, 0) to tileLon(tileX(lon, 0) + panX, 0)

    /** La calle donde estás; null = no se sabe (lejos de una calle con nombre). */
    var street by mutableStateOf<String?>(null)
        internal set
}

@Composable
fun rememberMapCamera() = remember { MapCamera() }

/** La cámara para elegir una zona: se mueve lejos (~2 000 km) y no regresa sola. */
@Composable
fun rememberFreeMapCamera() = remember { MapCamera(maxPan = FREE_PAN, returns = false) }

/**
 * El mapa (teselas de [store]) con tu posición y como diga [look], siempre plano: en el zoom automático, cerca (unas
 * calles); con «Automático» al norte detenido y hacia donde vas de viaje (andando a [DRIVE_KMH] o más, o con Waze/Maps
 * navegando: [navigating]); «centrar» o empezar un viaje lo regresan al zoom de fábrica. Solo se inclina la guía: con
 * [navigation] (la cámara que te sigue inclinada y muy cerca, más lejos entre más rápido vas, un poco menos inclinada
 * con [turnAhead]; un dedo la suelta y se aplana al alejarla) y a escala real ([routeWidth], la vista de tu auto). El zoom
 * es continuo: las teselas se agrandan o achican entre un nivel y otro, y mientras llegan las nuevas se ven las del
 * nivel anterior.
 * Con un dedo se mueve ([camera]), un toque hace [onTap] y colores claros u oscuros (las teselas ya vienen en sus
 * colores y con los nombres de las calles).
 * Tu flecha se desliza entre una lectura del GPS y la siguiente. Para elegir una zona: [fixedZoom] (sin gestos de
 * zoom ni viaje) y [ringKm], un círculo de ese radio alrededor del centro.
 * Solo el mapa [active] trabaja: tapado (por el mapa en grande u otra vista) no pide teselas, no anima ni busca la
 * calle, y se queda dibujando su último cuadro sin rehacerlo.
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
    active: Boolean = true,
    onTap: (() -> Unit)? = null,
    /** La cámara de navegación (solo la guía): inclinada, rumbo arriba y con el zoom de la velocidad ([MapCamera.following]). */
    navigation: Boolean = false,
    /** Navegando, la siguiente vuelta está cerca: se acerca un poco y se aplana algo. */
    turnAhead: Boolean = false,
    /**
     * Dónde vas tú y los créditos, en fracciones del mapa (null = todo el mapa): de fondo del inicio, el hueco que dejan
     * los widgets. Sin gestos ([interactive] = false) el mapa solo se ve.
     */
    focus: Rect? = null,
    interactive: Boolean = true,
    /** Tu auto en vez de la flecha (null = la flecha). */
    marker: ImageBitmap? = null,
    /** Un lugar marcado con un pin (latitud, longitud), p. ej. el que buscaste; null = ninguno. */
    pin: Pair<Double, Double>? = null,
    /** Dejar presionado un punto del mapa: su latitud y longitud (null = nada). */
    onLongPress: ((Double, Double) -> Unit)? = null,
    /** Una ruta que se dibuja (pares lat, lon en millonésimas de grado, [Route.points]); null = ninguna. */
    route: IntArray? = null,
    /** Lo ya recorrido de la ruta (punto + fracción hacia el siguiente): se dibuja en gris. */
    routeDone: Float = 0f,
    /**
     * La ruta a escala real (vista de tu auto): de este ancho en el zoom más cercano y en perspectiva, como una calle;
     * null = una línea del mismo grosor en toda la pantalla.
     */
    routeWidth: Dp? = null,
) {
    // La ruta en coordenadas del mundo (zoom 0), una vez: cada cuadro solo la multiplica por su zoom.
    val routeWorld = remember(route) {
        route?.let { r -> DoubleArray(r.size) { i -> if (i % 2 == 0) tileX(r[i + 1] / 1e6, 0) else tileY(r[i - 1] / 1e6, 0) } }
    }
    val c = LocalColors.current
    val driveZoom = store.source.maxZoom
    // Navegando (sin «Ver ruta») te sigue la cámara inclinada; a escala real (tu auto) siempre va inclinado y cerca.
    val navFollow = navigation && fixedZoom == null
    val carView = routeWidth != null && fixedZoom == null
    // De viaje: andando o navegando; se acaba tras un rato detenido (un alto no lo quita).
    val moving = fixedZoom == null && ((kmh ?: 0f) >= DRIVE_KMH || navigating)
    LaunchedEffect(moving) {
        if (moving) camera.driving = true else {
            delay(STOP_MS)
            camera.driving = false
        }
    }
    val driving = camera.driving
    // Al empezar un viaje, lo que hayas alejado a mano se olvida: vuelve a ti (abrir en grande no lo cambia).
    LaunchedEffect(driving) {
        if (camera.zoomFor != driving) {
            camera.zoomFor = driving
            if (driving && (camera.zoom != null || (navFollow && !camera.following))) camera.recenter()
        }
    }
    // De fábrica: el nivel fijo que se eligió o, en automático, lo más cerca (inclinado) o un poco más lejos (plano).
    val base = fixedZoom ?: look.zoom.level ?: if (navFollow || carView) driveZoom else driveZoom - FLAT_ZOOM_DROP
    val chosen = fixedZoom ?: camera.zoom ?: base
    val scope = rememberCoroutineScope()
    // La cámara de navegación: el zoom según la velocidad y la inclinación (plana con «siempre al norte»), cambiando
    // despacio hacia lo que toca; quieta, no se rehace nada.
    val northUp = look.orientation == MapOrientation.NORTH_UP && marker == null
    val navGoal by rememberUpdatedState(
        if (!navFollow) null
        else navZoomFor(kmh, driveZoom, turnAhead, northUp) to if (northUp) 0f else if (turnAhead) NAV_TURN_TILT else 1f,
    )
    LaunchedEffect(navFollow, active) {
        if (!navFollow || !active) return@LaunchedEffect
        snapshotFlow { navGoal }.collectLatest { goal ->
            val (z, t) = goal ?: return@collectLatest
            if (camera.navZoom.isNaN()) {
                camera.navZoom = z
                camera.navTilt = t
                return@collectLatest
            }
            // Con los cuadros de la pantalla: con la app detenida no llegan y esto se queda quieto.
            var last = withFrameNanos { it }
            while (camera.navZoom != z || camera.navTilt != t) {
                delay(GLIDE_STEP_MS)
                val now = withFrameNanos { it }
                val dt = (now - last) / 1e9f
                last = now
                camera.navZoom = approach(camera.navZoom, z, NAV_ZOOM_RATE * dt)
                camera.navTilt = approach(camera.navTilt, t, NAV_TILT_RATE * dt)
            }
        }
    }
    // Si este mapa se va o lo tapan a media animación de centrar, el otro la retoma.
    val shown = remember { Shown() }
    val activeNow by rememberUpdatedState(active)
    DisposableEffect(Unit) { onDispose { shown.gone = true } }
    // Centrar: el mapa se desliza hasta ti y luego vuelve a su zoom de fábrica, de corrido.
    LaunchedEffect(camera.recenters, camera.handOffs, active) {
        if (active) camera.animateRecenter(base, driveZoom, zooms = fixedZoom == null, follow = navFollow) { shown.gone || !activeNow }
    }
    val dark = look.dark(c.dark)
    val accent = c.accent
    val glide = rememberGlide(lat, lon, bearing, active, creeping = kmh != null && kmh < CREEP_KMH)
    // Con tu auto en vez de la flecha, siempre gira el mapa: el auto va de espaldas hacia arriba y no se gira.
    val headingUp = look.orientation == MapOrientation.HEADING || marker != null
    // «Automático»: al norte detenido y hacia donde vas de viaje (mirando un lugar o con zoom fijo, al norte); gira despacio.
    val autoTurn by animateFloatAsState(
        if (look.orientation == MapOrientation.NORTH && driving && fixedZoom == null && !camera.showingPlace) 1f else 0f,
        tween(AUTO_TURN_MS),
    )
    val sky = store.source.sky?.let(::Color) ?: if (dark) Color(0xFF1C1C20) else Color(0xFFE8E8EA)
    val zoomable by rememberUpdatedState(fixedZoom == null)
    // Con dos dedos el mapa se agranda o achica ([MapCamera.scale]) y, pasada la mitad, cambia al nivel de junto.
    val onPinch by rememberUpdatedState { factor: Float ->
        camera.stopZoom()
        if (navFollow) camera.letGo(base, driveZoom)
        // El de ahora mismo (varios movimientos pueden llegar antes de recomponer).
        val step = pinchStep(camera.scale, factor, camera.zoom ?: base, MIN_ZOOM, driveZoom)
        camera.scale = step.scale
        camera.zoom = step.level.takeIf { it != base }
    }
    // Los botones de zoom ([MapCamera.zoomBy]): un nivel por paso desde donde va, deslizándose.
    LaunchedEffect(camera.zoomSteps, active) {
        val steps = camera.zoomSteps
        if (steps == 0 || !active) return@LaunchedEffect
        camera.zoomSteps = 0
        if (fixedZoom != null) return@LaunchedEffect
        camera.stopZoom()
        val from = camera.zoom ?: base
        camera.glideZoom(scope, from, (from + steps).coerceIn(MIN_ZOOM, driveZoom), base, driveZoom)
    }
    val onPinchEnd by rememberUpdatedState {
        // Al soltar se acomoda, deslizándose, en el nivel donde quedó.
        val z = camera.zoom ?: base
        camera.glideZoom(scope, z, z, base, driveZoom)
        Unit
    }
    // Lo que se dibuja, armado una vez y reusado; con su última proyección un arrastre se vuelve movimiento del mapa.
    val frame = remember { MapFrame() }
    // Lo que se ve se pide al almacén cuando cambia, fuera del dibujo; tapado o fuera de la vista, deja de pedirse.
    LaunchedEffect(store, active) {
        if (!active) return@LaunchedEffect
        try {
            snapshotFlow { frame.wanted }.collect { store.request(frame, it) }
        } finally {
            store.forget(frame)
        }
    }
    val onPan by rememberUpdatedState { at: Offset, delta: Offset ->
        val projection = frame.projection
        val a = frame.dragFrom
        val b = frame.dragTo
        if (frame.worldPx > 0.0 && projection.toPlane(at.x - delta.x, at.y - delta.y, a) && projection.toPlane(at.x, at.y, b)) {
            // Cerca del horizonte un pixel del dedo son cientos de metros: no más de unas veces lo que se movió.
            var dx = (b[0] - a[0]).toDouble()
            var dy = (b[1] - a[1]).toDouble()
            val most = MAX_PAN_STRETCH * hypot(delta.x, delta.y)
            val length = hypot(dx, dy)
            if (length > most && length > 0.0) {
                dx *= most / length
                dy *= most / length
            }
            camera.stopPan()
            if (navFollow) camera.letGo(base, driveZoom)
            camera.place = null
            val limit = camera.panLimit
            camera.panX = (camera.panX - dx / frame.worldPx).coerceIn(-limit, limit)
            camera.panY = (camera.panY - dy / frame.worldPx).coerceIn(-limit, limit)
        }
    }
    // Movido con el dedo (o, navegando, suelta) y sin tocarlo un rato: regresa a ti solo (como Waze). Solo de viaje
    // (detenido, el mapa se queda donde lo dejaste) y solo el mapa que se ve (el widget tapado y el de en grande comparten cámara).
    val loose by rememberUpdatedState(navFollow)
    LaunchedEffect(camera, active) {
        if (!active || !camera.returns) return@LaunchedEffect
        snapshotFlow {
            val moved = !camera.centered || (loose && !camera.following)
            // También el pellizco dentro del mismo nivel ([MapCamera.scale]): cada toque vuelve a contar el rato.
            if (moved && camera.driving && !camera.showingPlace) Touched(camera.panX, camera.panY, camera.zoom, camera.scale) else null
        }.collectLatest { moved ->
            if (moved != null) {
                delay(RECENTER_MS)
                camera.recenter()
            }
        }
    }
    // Enseñando un lugar: el centro sigue en él con cada lectura del GPS.
    val shownPlace = camera.place
    LaunchedEffect(shownPlace, lat, lon) { if (shownPlace != null) camera.aim(lat, lon) }
    // La calle donde estás: al moverte o cuando llegan los nombres de tu tesela (no con cada tesela).
    LaunchedEffect(store, lat, lon, active) {
        if (!active) return@LaunchedEffect
        val origin = tileKey(DATA_ZOOM, tileX(lon, DATA_ZOOM).toInt(), tileY(lat, DATA_ZOOM).toInt())
        snapshotFlow { store.loaded.let { store.source.names(origin) } }.distinctUntilChanged().collect { names ->
            camera.street = names?.let { nearestStreet(origin, it, lat, lon) }
        }
    }
    val labelColor = store.source.label?.let(::Color) ?: if (dark) Color(0xFFC3C6CD) else Color(0xFF4E535C)
    val labelHalo = store.source.halo?.let(::Color) ?: sky
    // La ruta «río» de los mapas planos: sus flechitas avanzan solo con la ruta a la vista, este mapa activo, la opción
    // prendida y una pantalla que da para eso (a ~30 cuadros por segundo). Con la opción apagada quedan quietas (el
    // cuadro no se rehace por ellas); en una pantalla que no da para eso, ni se dibujan.
    val context = LocalContext.current
    val canFlow = remember { routeCanFlow(context) }
    val flowing = route != null && routeWidth == null && active && look.flow && canFlow
    val flow = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(flowing) {
        if (!flowing) return@LaunchedEffect
        // Al ritmo de los cuadros (con la app detenida no llegan y se queda quieta) y no más de ~30 por segundo.
        val start = withFrameNanos { it } - (flow.floatValue * FLOW_CYCLE_MS * 1e6).toLong()
        while (true) {
            delay(GLIDE_STEP_MS)
            val now = withFrameNanos { it }
            flow.floatValue = ((now - start) / 1e6 % FLOW_CYCLE_MS / FLOW_CYCLE_MS).toFloat()
        }
    }
    // Un toque: lo que diga [onTap] (en el widget, verlo en grande o abrir tu app de mapas).
    val tap = if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier
    val longPress by rememberUpdatedState(onLongPress)
    val press = if (onLongPress == null || !interactive) Modifier else Modifier.pointerInput(Unit) {
        detectTapGestures(onLongPress = { at -> frame.toLatLon(at.x, at.y)?.let { (la, lo) -> longPress?.invoke(la, lo) } })
    }
    BoxWithConstraints(
        modifier.background(sky).then(tap).then(press).then(
            if (!interactive) Modifier else Modifier.pointerInput(Unit) {
                detectMapGestures({ at, delta -> onPan(at, delta) }, { if (zoomable) onPinch(it) }, { if (zoomable) onPinchEnd() })
            },
        ),
    ) {
        val short = maxWidth < SHORT_ATTRIBUTION
        Spacer(
            Modifier.fillMaxSize().drawWithCache {
                // Se rehace en cada paso del deslizamiento o del zoom, al mover el mapa y al llegar una tesela (sin
                // recomponer). Tapado no lee nada: sigue el último cuadro y no se rehace hasta que se vuelve a ver.
                if (active || !frame.ready(size.width, size.height)) {
                    store.loaded // cada tesela que llega rehace esto (y los nombres nuevos se acomodan)
                    val pose = glide.read()
                    // El nivel de teselas, cuánto se agranda y cuánto se inclina (0–1).
                    val following = navFollow && camera.following && !camera.navZoom.isNaN()
                    val step = if (following) zoomStep(camera.navZoom, MIN_ZOOM, driveZoom) else null
                    val zoom = step?.level ?: chosen
                    val scale = step?.scale ?: camera.scale
                    val tilt = when {
                        following -> camera.navTilt
                        // Suelta: se acuesta al alejarla un nivel de donde iba.
                        navFollow -> camera.followTilt * tiltFor(zoom + log2(scale), camera.followZoom)
                        carView -> tiltFor(zoom + log2(scale), driveZoom.toFloat())
                        else -> 0f
                    }
                    val bearing = if (pose.bearing.isNaN()) 0f else pose.bearing
                    // Hacia donde vas, o al norte; con «Automático» gira de uno a otro por el lado corto.
                    val turn = if (headingUp) -bearing else -shortTurn(0f, bearing) * autoTurn
                    // Inclinado, tu posición va más abajo: se ve más camino adelante (dentro de [focus], si lo hay).
                    val f = focus ?: WHOLE
                    val atX = size.width * f.center.x
                    val atY = size.height * (f.top + f.height * (ANCHOR_FLAT + (ANCHOR_TILTED - ANCHOR_FLAT) * tilt))
                    // Las teselas del nivel, agrandadas o achicadas con el zoom a medias; tu flecha y los nombres, no.
                    val ts = tilePx(density) * scale
                    val n = (1 shl zoom).toDouble()
                    val panX = camera.panX
                    val panY = camera.panY
                    frame.update(
                        size.width, size.height, density, atX, atY, turn, tilt,
                        // El centro del mapa: tú, o a donde lo moviste.
                        tileX(pose.lon, zoom) + panX * n, tileY(pose.lat, zoom) + panY * n, zoom, ts, panX, panY,
                        store.source, tilePx(density), frame.textPaint(12.sp.toPx(), labelColor, labelHalo),
                        // Siempre flecha: hacia arriba con el mapa girado, o con el último rumbo (sin rumbo aún, al norte).
                        sky, bearing + turn, ringKm, if (ringKm == null) 0.0 else tileLat(tileY(pose.lat, 0) + panY, 0),
                        pin?.let { tileX(it.second, zoom) } ?: Double.NaN, pin?.let { tileY(it.first, zoom) } ?: Double.NaN,
                        routeWorld, routeDone,
                        routeWidth?.let { it.toPx() * ts * n.toFloat() / (tilePx(density) * 2f.pow(driveZoom)) } ?: 0f,
                        routeArrows = canFlow,
                    )
                }
                // Leer el avance aquí solo vuelve a pintar (no rehace el cuadro).
                onDrawBehind { frame.draw(this, store, accent, marker, focus, dark, flow.floatValue) }
            },
        )
        val credits = if (short) store.source.attributionShort else store.source.attribution
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        if (focus == null) Attribution(credits, Modifier.align(Alignment.BottomEnd))
        else Box(Modifier.offset(maxWidth * focus.left, maxHeight * focus.top).size(maxWidth * focus.width, maxHeight * focus.height)) {
            // Abajo a la izquierda del hueco (abajo al centro van los puntitos de las páginas); si ahí los pisaría tu auto
            // o la flecha, arriba a la izquierda.
            var creditsSize by remember { mutableStateOf(IntSize.Zero) }
            val holeW = boxW * focus.width
            val holeH = boxH * focus.height
            val you = youExtent(marker, holeW, holeH, LocalDensity.current.density)
            val top = creditsCollide(
                holeW, holeH, creditsSize.width.toFloat(), creditsSize.height.toFloat(),
                you.halfWidth, you.below, ANCHOR_FLAT,
            )
            Attribution(credits, Modifier.align(if (top) Alignment.TopStart else Alignment.BottomStart).onSizeChanged { creditsSize = it })
        }
    }
}

/** Lo que ocupa tu auto (o la flecha) alrededor de tu posición, en px: la mitad de lo ancho y lo que baja (con su sombra). */
internal class YouExtent(val halfWidth: Float, val below: Float)

/** Las mismas medidas con que [MapFrame] dibuja tu auto ([marker]) en un hueco de [holeW] × [holeH] px, o la flecha. */
internal fun youExtent(marker: ImageBitmap?, holeW: Float, holeH: Float, density: Float): YouExtent {
    if (marker == null || marker.width == 0) return YouExtent(ARROW_GLOW * density, ARROW_GLOW * density)
    val w = markerWidthDp(minOf(holeW, holeH) / density) * density
    val h = w * marker.height / marker.width
    return YouExtent(w * 0.55f, h * MARKER_BELOW)
}

/**
 * ¿Los créditos ([creditsW] × [creditsH] px, abajo a la izquierda de un hueco de [holeW] × [holeH]) quedan debajo de
 * tu auto o la flecha? Va al centro a lo ancho y a [anchor] del alto; ocupa [halfWidth] a cada lado y [below] hacia abajo.
 */
internal fun creditsCollide(
    holeW: Float, holeH: Float, creditsW: Float, creditsH: Float, halfWidth: Float, below: Float, anchor: Float,
): Boolean {
    if (creditsW <= 0f || creditsH <= 0f) return false
    val x = holeW / 2
    val y = holeH * anchor
    return x - halfWidth < creditsW && y + below > holeH - creditsH
}


/** Lo que baja tu auto desde tu posición, en fracción de su alto (la sombra llega a 0.22 + 0.36). */
private const val MARKER_BELOW = 0.58f

/** Todo el mapa: tú al centro a lo ancho. */
private val WHOLE = Rect(0f, 0f, 1f, 1f)

/** Cómo quedó el mapa movido a mano (para volver a contar el rato antes de regresar a ti con cada cambio). */
private data class Touched(val panX: Double, val panY: Double, val zoom: Int?, val scale: Float)

/** Si el mapa ya salió de la vista (para dejarle a otro lo que animaba). */
private class Shown {
    var gone = false
}

/** Dónde se ve tu posición: latitud, longitud y rumbo (NaN = sin rumbo). Se reusa. */
internal class Pose {
    var lat = 0.0
    var lon = 0.0
    var bearing = Float.NaN

    fun set(lat: Double, lon: Double, bearing: Float) {
        this.lat = lat; this.lon = lon; this.bearing = bearing
    }
}

/**
 * Tu posición deslizándose de la lectura anterior del GPS a la nueva en [GLIDE_MS] (llegan una por segundo: sin esto
 * la flecha brinca), a [GLIDE_STEP_MS] por paso (las pantallas de auto no dan para 60 cuadros). Saltos grandes, o
 * moverse menos que [STILL_METERS] o ir a menos de [CREEP_KMH] (el GPS baila estando quieto), van directo: un solo cuadro.
 * Tapado no se desliza.
 */
private class Glide(lat: Double, lon: Double, bearing: Float) {
    val from = Pose().apply { set(lat, lon, bearing) }
    val to = Pose().apply { set(lat, lon, bearing) }
    private val now = Pose()
    /** Cuánto lleva (0–1) y cuántas veces cambió de camino: los dos son estado, un salto directo también repinta. */
    var t by mutableFloatStateOf(1f)
    var trips by mutableIntStateOf(0)

    /** Dónde va ahora (se lee al dibujar). */
    fun read(): Pose {
        trips
        between(from, to, t, now)
        return now
    }
}

@Composable
private fun rememberGlide(lat: Double, lon: Double, bearing: Float?, active: Boolean, creeping: Boolean): Glide {
    val heading = bearing ?: Float.NaN
    val glide = remember { Glide(lat, lon, heading) }
    LaunchedEffect(lat, lon, bearing, active) {
        val now = Pose()
        between(glide.from, glide.to, glide.t, now)
        val meters = distanceKm(now.lat, now.lon, lat, lon) * 1000
        val turned = if (now.bearing.isNaN() || heading.isNaN()) 0f else abs(shortTurn(now.bearing, heading))
        if (!active || creeping || meters > SNAP_METERS || (meters < STILL_METERS && turned < STILL_DEGREES)) {
            glide.from.set(lat, lon, heading)
            glide.to.set(lat, lon, heading)
            glide.t = 1f
            glide.trips++
            return@LaunchedEffect
        }
        glide.from.set(now.lat, now.lon, now.bearing)
        glide.to.set(lat, lon, heading)
        glide.t = 0f
        glide.trips++
        val start = withFrameNanos { it }
        while (glide.t < 1f) {
            delay(GLIDE_STEP_MS)
            glide.t = ((withFrameNanos { it } - start) / 1_000_000f / GLIDE_MS).coerceAtMost(1f)
        }
    }
    return glide
}

/** La posición a [t] (0–1) del camino de [from] a [to], en [out]; el rumbo gira por el lado corto. */
internal fun between(from: Pose, to: Pose, t: Float, out: Pose) {
    val b = when {
        to.bearing.isNaN() -> Float.NaN
        from.bearing.isNaN() -> to.bearing
        else -> from.bearing + shortTurn(from.bearing, to.bearing) * t
    }
    out.set(from.lat + (to.lat - from.lat) * t, from.lon + (to.lon - from.lon) * t, b)
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
            var pressed = 0
            var finger: PointerInputChange? = null
            for (i in event.changes.indices) {
                val change = event.changes[i]
                if (change.pressed) {
                    pressed++
                    finger = change
                }
            }
            if (pressed >= 2) {
                val factor = event.calculateZoom()
                if (factor != 1f) onZoom(factor)
                pinched = true
            } else if (!pinched && finger != null) {
                val delta = finger.positionChange()
                if (!panning) {
                    travel += delta
                    panning = travel.getDistance() > viewConfiguration.touchSlop
                } else if (delta != Offset.Zero) {
                    onPan(finger.position, delta)
                }
            }
            // Ya movido o pellizcado, todo es del mapa (también al soltar): si no, levantar el dedo contaba como toque.
            if (pinched || panning) for (i in event.changes.indices) event.changes[i].consume()
        } while (pressed > 0)
        if (pinched) onEnd()
    }
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
            .clickable {
                // Sin navegador en la pantalla no hay dónde abrirla: el crédito igual se lee.
                try {
                    uri.openUri(ATTRIBUTION_URL)
                } catch (_: ActivityNotFoundException) {
                }
            }
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
