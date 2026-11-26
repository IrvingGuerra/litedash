package com.irvingguerra.litedash.core.ui

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.atan2

/** Cómo se rellenan las tarjetas de toda la app: lisas o de vidrio (lo de fábrica). */
enum class CardStyle(val stored: String) {
    SOLID("solid"), GLASS("glass");

    companion object {
        /** Sin elegir, de vidrio; también quien tenía las translúcidas (ya no existen). */
        fun fromStored(value: String?) = if (value == SOLID.stored) SOLID else GLASS
    }
}

/**
 * El fondo de pantalla ya desenfocado (una sola vez, en chico) y el velo que lleva encima: el vidrio lo dibuja detrás
 * de cada tarjeta, recortado como el fondo (que llena la ventana sin deformarse), así parece que la tarjeta lo
 * desenfoca sin costarle nada a la GPU al dibujar (sirve igual en Android 6). El velo se lee al dibujar: moverlo
 * («Oscurecer el fondo») solo redibuja, no recompone.
 */
@Immutable
class CardBackdrop(val image: ImageBitmap, val veil: () -> Color) {
    /** Sus pixeles, para doblarlos en la orilla del cristal ([refracted]); se leen una vez, fuera del hilo principal. */
    internal val pixels: IntArray by lazy {
        IntArray(image.width * image.height).also { image.asAndroidBitmap().getPixels(it, 0, image.width, 0, 0, image.width, image.height) }
    }
}

/**
 * La piel de las tarjetas de toda la app (Inicio y apariencia → Interfaz → Tarjetas): su [style] y, de vidrio, cuánto
 * se ve de ellas ([opacity], 0–1). Su color viene del tema ([Colors.withCards]). El [backdrop] solo lo pone el inicio
 * (cada tarjeta recorta tu fondo desenfocado); lo demás va sobre un lienzo ya desenfocado y no lo necesita.
 */
@Immutable
class CardLook(
    val style: CardStyle = CardStyle.SOLID,
    val opacity: Float = 1f,
    val backdrop: CardBackdrop? = null,
    /**
     * De vidrio, ¿puede ir como cristal (casi sin relleno)? Sí sobre algo quieto: tu fondo, el resplandor del tema o un
     * lienzo. No sobre el mapa de fondo, que se mueve y no se desenfoca: ahí va ahumado ([smokeAlpha]).
     */
    val clear: Boolean = true,
)

// Dinámico: al arrastrar la opacidad solo se redibujan las tarjetas, no toda la app.
val LocalCardLook = compositionLocalOf { CardLook() }

/**
 * Cuánto del color de la tarjeta lleva una de vidrio (0–1): el control «Cuánto se ven» va de nada (puro cristal, o
 * ahumado sobre el mapa de fondo) a lisa. Con tu fondo desenfocado detrás no hace falta más; sin él, nunca menos que
 * [floor] (lo que flota sobre cualquier cosa: la barra, un aviso).
 */
internal fun glassFill(look: CardLook, floor: Float): Float {
    val t = ((look.opacity - MIN_OPACITY) / (1f - MIN_OPACITY)).coerceIn(0f, 1f)
    return if (look.backdrop != null) t else maxOf(t, floor)
}

/**
 * Sobre el mapa de fondo (se mueve y no se desenfoca) el cristal va ahumado: lo menos del color del fondo del tema
 * que necesita para que el texto se lea sobre lo peor que puede traer el mapa (blanco con el tema oscuro, negro con
 * el claro: el mapa puede ir claro u oscuro aparte del tema), con la escarcha debajo como se dibuja.
 */
internal fun smokeAlpha(c: Colors): Float {
    // Depende solo del tema: cada tarjeta sobre el mapa lo pide al recomponerse (se guarda el último).
    smoke?.let { (colors, a) -> if (colors == c) return a }
    val under = Color.White.copy(alpha = frost(c.dark)).compositeOver(if (c.dark) Color.White else Color.Black)
    var a = 1f
    for (step in 0 until 20) {
        val tried = step * 0.05f
        if (contrast(c.text, c.background.copy(alpha = tried).compositeOver(under)) >= 4.5f) {
            a = tried
            break
        }
    }
    smoke = c to a
    return a
}

/** El último ahumado calculado y de qué tema (solo se toca al componer, en el hilo principal). */
private var smoke: Pair<Colors, Float>? = null

/** La escarcha del cristal: un blanco muy leve que lo despega del fondo (se va conforme la tarjeta se llena). */
internal fun frost(dark: Boolean) = if (dark) 0.04f else 0.12f

/**
 * El velo que lleva tu fondo detrás de un cristal para que el texto se lea: el de siempre ([base]) y, si tu imagen es
 * muy clara (tema oscuro) o muy oscura (tema claro) en alguna parte, lo que haga falta. [extreme] = esa parte.
 */
fun glassVeil(c: Colors, extreme: Color, base: Float): Float {
    var a = base
    while (a < VEIL_MAX) {
        val seen = Color.White.copy(alpha = frost(c.dark)).compositeOver(c.background.copy(alpha = a).compositeOver(extreme))
        if (contrast(c.text, seen) >= 4.5f) return a
        a += 0.05f
    }
    return VEIL_MAX
}

/**
 * Lo menos que se vela tu imagen del inicio para que lo que va sin tarjeta (saludo, fecha, íconos de la barra) se lea:
 * el texto del tema con contraste ≥ 3 sobre el color medio de la imagen ([mean]). «Oscurecer el fondo» nunca baja de
 * aquí; con una imagen que ya se lleva con el tema (oscura en tema oscuro, clara en claro), 0.
 */
fun legibleVeil(c: Colors, mean: Color): Float = veilUntil(c.text, c, mean, 0f, PLAIN_CONTRAST, VEIL_MAX)

/**
 * El velo del lienzo (Ajustes, paneles) sobre tu fondo desenfocado. En tema oscuro, [base] tal cual (como en la 1.5.14:
 * tu fondo se sigue viendo detrás de Ajustes con cualquier imagen). En tema claro, donde el menú no se leía, de [base]
 * lo que haga falta para que lo apagado ([Colors.textMuted]: las secciones sin elegir) dé contraste ≥ 4.5 sobre la
 * parte más oscura de tu imagen ([extreme]); como para llegar justo a 4.5 tendría que ir casi liso (el claro de fábrica
 * da 4.6 sobre su fondo), basta el 90 % de lo que da ahí; si ni así, liso.
 */
fun canvasVeil(c: Colors, extreme: Color, base: Float): Float =
    if (c.dark) base else veilUntil(c.textMuted, c, extreme, base, minOf(4.5f, contrast(c.textMuted, c.background) * 0.9f), 1f)

/** Lo menos, de [base] a [max] en pasos de 0.05, que deja a [ink] con [wanted] de contraste sobre [under] velado. */
private fun veilUntil(ink: Color, c: Colors, under: Color, base: Float, wanted: Float, max: Float): Float {
    var step = 0
    while (true) {
        val a = base + step * 0.05f
        if (a >= max) return max
        if (contrast(ink, c.background.copy(alpha = a).compositeOver(under)) >= wanted) return a
        step++
    }
}

/**
 * Lo que va dentro de una tarjeta de vidrio o sin tarjeta ([plain]): lo que en una lisa es un gris opaco (el aro de
 * un medidor, un botón, una raya) va como texto translúcido, para verse igual sobre cualquier fondo; y lo apagado,
 * un poco más claro. En una tarjeta lisa, los mismos [c]. También lo usa lo que lleva la piel sin ser tarjeta (la barra).
 */
fun contentColors(c: Colors, look: CardLook, plain: Boolean): Colors = when {
    // Una tarjeta dentro de otra: lo de adentro ya viene ajustado.
    c.surfaceHigh.alpha < 1f -> c
    plain -> c.copy(surfaceHigh = c.text.copy(alpha = 0.16f), line = c.text.copy(alpha = 0.16f), textMuted = c.text.copy(alpha = 0.72f))
    look.style == CardStyle.GLASS -> c.copy(surfaceHigh = c.text.copy(alpha = 0.12f), line = c.text.copy(alpha = if (c.dark) 0.14f else 0.2f), textMuted = lerp(c.textMuted, c.text, 0.3f))
    else -> c
}

/**
 * Una superficie con la piel [look] y la forma [shape] (ya recortada por quien la llama): de vidrio, tu fondo
 * desenfocado detrás (si el inicio lo puso), el relleno translúcido y la luz del cristal ([liquidGlass]).
 * [floor] = lo menos que se ve una flotante (la barra), que va sobre cualquier cosa.
 */
fun Modifier.skinSurface(look: CardLook, c: Colors, shape: Shape, floor: Float = 0f): Modifier {
    var m = this
    val glass = look.style == CardStyle.GLASS
    if (!glass) return m.background(c.surface)
    if (look.backdrop != null) m = m.blurredBackdrop(look.backdrop, shape)
    val fill = glassFill(look, floor)
    // Sobre el mapa de fondo, el mismo cristal pero ahumado con el color del fondo, que se vuelve el de la tarjeta
    // conforme se llena.
    val tint = if (look.clear) c.surface.copy(alpha = fill) else lerp(c.background.copy(alpha = smokeAlpha(c)), c.surface, fill)
    val through = 1f - fill
    return m.background(Color.White.copy(alpha = frost(c.dark) * through))
        .background(tint)
        .liquidGlass(c.dark, shape, through, lit = look.clear && look.backdrop != null)
}

/**
 * La luz de un cristal líquido, dibujada una vez por tamaño: la luz que le cae de arriba, el grosor del vidrio (una
 * banda suave por dentro de la orilla, clara arriba; en tema claro abajo no lleva nada, o con la sombra de afuera
 * se vería una orilla doble) y el brillo fino de la orilla, que destella en
 * la esquina de arriba a la izquierda y en la de abajo a la derecha y casi se apaga entre ellas. Todo se va conforme
 * la tarjeta se llena ([through] = cuánto queda de cristal). [lit] = sobre tu fondo (el inicio): en Ajustes y
 * paneles, con muchas tarjetas en lista, va a media fuerza.
 */
private fun Modifier.liquidGlass(dark: Boolean, shape: Shape, through: Float, lit: Boolean): Modifier = drawWithCache {
    val k = through * (if (lit) 1f else 0.5f)
    val outline = shape.createOutline(size, layoutDirection, this)
    fun white(a: Float) = Color.White.copy(alpha = (a * k).coerceIn(0f, 1f))
    fun black(a: Float) = Color.Black.copy(alpha = (a * k).coerceIn(0f, 1f))
    val light = Brush.verticalGradient(
        0f to white(if (dark) 0.1f else 0.28f), 0.55f to Color.Transparent, 1f to (if (dark) black(0.1f) else Color.Transparent),
    )
    // Cada banda lleva una fracción: sumadas dan un degradado hacia la orilla sin escalones a la vista.
    val part = 1f / GLASS_DEPTH_STEPS
    val depth = Brush.verticalGradient(
        0f to white((if (dark) 0.14f else 0.5f) * part), 0.5f to Color.Transparent,
        1f to (if (dark) white(0.06f * part) else Color.Transparent),
    )
    // El destello va por ángulo desde el centro, con las dos esquinas donde de verdad están (0 = a la derecha, en
    // el sentido del reloj): así una tarjeta muy ancha no lleva media orilla encendida.
    val turn = (atan2(size.height, size.width) / (2 * PI)).toFloat().coerceIn(0.02f, 0.2f)
    val strong = white(if (dark) 0.7f else 1f)
    val second = white(if (dark) 0.42f else 0.3f)
    val faint = white(if (dark) 0.06f else 0f)
    val soft = white(if (dark) 0.16f else 0.55f)
    val rim = Brush.sweepGradient(
        0f to faint, turn to second, (turn + 0.25f) / 2 + 0.02f to faint, 0.25f to faint, 0.5f to soft,
        0.5f + turn to strong, 0.75f to soft, 1f to faint,
    )
    // Los trazos van centrados en la orilla: la mitad de afuera la recorta la tarjeta.
    val bands = List(GLASS_DEPTH_STEPS) { Stroke(GLASS_DEPTH.toPx() * 2 * (it + 1) / GLASS_DEPTH_STEPS) }
    val line = Stroke(GLASS_RIM.toPx() * 2)
    onDrawBehind {
        drawRect(light)
        for (b in bands) drawOutline(outline, depth, style = b)
        drawOutline(outline, rim, style = line)
    }
}

/**
 * La sombra de una tarjeta de cristal sobre tu fondo: unos aros que se van apagando hacia afuera, un poco caídos. Va
 * ANTES de recortar la tarjeta y solo cuando lleva tu fondo desenfocado detrás (que es opaco y tapa lo que cae adentro):
 * la sombra de Android se vería a través del cristal. Los aros se calculan una vez por tamaño ([drawWithCache]); en
 * equipos de poca memoria ([cheap]) es un solo aro.
 */
fun Modifier.glassShadow(look: CardLook, dark: Boolean, radius: Dp, cheap: Boolean = false): Modifier {
    if (look.style != CardStyle.GLASS || look.backdrop == null || !look.clear) return this
    return drawWithCache {
        val r = radius.toPx()
        val rings = if (cheap) 1 else SHADOW_RINGS
        val ring = SHADOW_SPREAD.toPx() / rings
        val drop = SHADOW_DROP.toPx()
        // Nace metida bajo la tarjeta: arriba y a los lados casi no asoma, cae hacia abajo.
        val inset = SHADOW_INSET.toPx()
        val strength = (if (dark) 0.14f else 0.03f) * (if (cheap) 0.6f else 1f)
        val stroke = Stroke(ring + 1f)
        val shapes = List(rings) { i ->
            val out = ring * (i + 0.5f) - inset
            val fade = 1f - i / rings.toFloat()
            ShadowRing(Color.Black.copy(alpha = strength * fade * fade), Offset(-out, -out + drop), Size(size.width + 2 * out, size.height + 2 * out), CornerRadius((r + out).coerceAtLeast(0f)))
        }
        onDrawBehind { for (s in shapes) drawRoundRect(s.color, s.at, s.size, s.corner, style = stroke) }
    }
}

private class ShadowRing(val color: Color, val at: Offset, val size: Size, val corner: CornerRadius)

/** ¿Equipo de poca memoria (Android lo marca así o da ≤ 128 MB por app)? Se pregunta una vez. */
fun lowMemoryDevice(context: Context): Boolean = lowMemory ?: run {
    val am = context.getSystemService(ActivityManager::class.java)
    (am == null || am.isLowRamDevice || am.memoryClass <= LOW_MEMORY_MB).also { lowMemory = it }
}

private var lowMemory: Boolean? = null
private const val LOW_MEMORY_MB = 128

/**
 * Dibuja [b] detrás, recortado como el fondo de la ventana: se ve la parte del fondo que la tarjeta tapa. Con [lens]
 * (la forma de un cristal) esa parte va doblada en la orilla, como a través de un vidrio grueso ([refracted]): se
 * calcula fuera del hilo principal cuando la tarjeta deja de moverse y, mientras, va la de antes o el fondo sin doblar.
 * En equipos de poca memoria, sin doblar.
 */
fun Modifier.blurredBackdrop(b: CardBackdrop, lens: Shape? = null): Modifier = composed {
    // Dónde está la tarjeta y cuánto mide la ventana: se leen al dibujar (moverla solo la redibuja).
    var at by remember { mutableStateOf(Offset.Zero) }
    var root by remember { mutableStateOf(Size.Zero) }
    var extent by remember { mutableStateOf(Size.Zero) }
    var bent by remember(b.image) { mutableStateOf<Bent?>(null) }
    val context = LocalContext.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    if (lens != null && !lowMemoryDevice(context)) LaunchedEffect(b.image, lens) {
        snapshotFlow { Triple(at, root, extent) }.collectLatest { (where, window, size) ->
            if (window.width <= 0f || size.width < 1f || size.height < 1f) return@collectLatest
            val radius = when (val o = lens.createOutline(size, direction, density)) {
                is Outline.Rounded -> o.roundRect.topLeftCornerRadius.x
                is Outline.Rectangle -> 0f
                else -> return@collectLatest
            }
            delay(LENS_SETTLE_MS)
            val depth = with(density) { LENS_DEPTH.toPx() }
            val image = withContext(Dispatchers.Default) { refracted(b, where, window, size, radius, depth) }
            bent = Bent(image, size)
        }
    }
    onGloballyPositioned {
        at = it.positionInRoot()
        root = it.findRootCoordinates().size.toSize()
        extent = it.size.toSize()
    }.drawBehind {
        if (root.width <= 0f) return@drawBehind
        val ready = bent?.takeIf { it.size == size }
        if (ready != null) {
            drawImage(ready.image, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.High)
        } else {
            val iw = b.image.width.toFloat()
            val ih = b.image.height.toFloat()
            val s = maxOf(root.width / iw, root.height / ih)
            translate((root.width - iw * s) / 2 - at.x, (root.height - ih * s) / 2 - at.y) {
                scale(s, Offset.Zero) { drawImage(b.image, filterQuality = FilterQuality.High) }
            }
        }
        drawRect(b.veil())
    }
}

/** Tu fondo ya doblado para una tarjeta y cuánto medía cuando se calculó. */
private class Bent(val image: ImageBitmap, val size: Size)

/**
 * La parte de [b] que cae bajo una tarjeta de [size] en [at] (ventana de [root]), vista a través de un cristal de
 * esquinas [radius]: al centro, tal cual; en una franja de [depth] junto a la orilla, cada punto toma el fondo de más
 * adentro, cada vez más conforme se acerca a la orilla (perfil de un canto redondo), hasta verse reflejado: lo que
 * hace que un vidrio grueso se note sobre cualquier fondo. A media resolución (el fondo ya viene suave). Correr fuera
 * del hilo principal.
 */
internal fun refracted(b: CardBackdrop, at: Offset, root: Size, size: Size, radius: Float, depth: Float): ImageBitmap {
    val iw = b.image.width
    val ih = b.image.height
    val src = b.pixels
    val cover = maxOf(root.width / iw, root.height / ih)
    val left = (root.width - iw * cover) / 2
    val top = (root.height - ih * cover) / 2
    val w = (size.width * LENS_SCALE).toInt().coerceAtLeast(1)
    val h = (size.height * LENS_SCALE).toInt().coerceAtLeast(1)
    val out = IntArray(w * h)
    val halfW = size.width / 2
    val halfH = size.height / 2
    val r = radius.coerceAtMost(minOf(halfW, halfH))
    val reach = depth.coerceAtMost(minOf(halfW, halfH))
    val push = reach * LENS_PUSH
    for (y in 0 until h) {
        val py = (y + 0.5f) / LENS_SCALE
        for (x in 0 until w) {
            val px = (x + 0.5f) / LENS_SCALE
            // Distancia a la orilla del rectángulo redondeado (hacia adentro) y hacia dónde queda el centro.
            val qx = abs(px - halfW) - (halfW - r)
            val qy = abs(py - halfH) - (halfH - r)
            var nx = 0f
            var ny = 0f
            val inside: Float
            if (qx > 0f && qy > 0f) {
                val len = sqrt(qx * qx + qy * qy).coerceAtLeast(0.001f)
                inside = r - len
                nx = qx / len
                ny = qy / len
            } else if (qx > qy) {
                inside = -qx + r
                nx = 1f
            } else {
                inside = -qy + r
                ny = 1f
            }
            var sx = px
            var sy = py
            if (inside < reach) {
                val t = 1f - (inside / reach).coerceIn(0f, 1f)
                val bend = push * (1f - sqrt(1f - t * t))
                sx -= (if (px < halfW) -nx else nx) * bend
                sy -= (if (py < halfH) -ny else ny) * bend
            }
            out[y * w + x] = sample(src, iw, ih, (at.x + sx - left) / cover - 0.5f, (at.y + sy - top) / cover - 0.5f)
        }
    }
    return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** El color de [src] en ([x], [y]) entre sus cuatro pixeles vecinos (orillas repetidas). */
private fun sample(src: IntArray, w: Int, h: Int, x: Float, y: Float): Int {
    val fx = x.coerceIn(0f, w - 1f)
    val fy = y.coerceIn(0f, h - 1f)
    val x0 = fx.toInt()
    val y0 = fy.toInt()
    val x1 = minOf(x0 + 1, w - 1)
    val y1 = minOf(y0 + 1, h - 1)
    val ax = ((fx - x0) * 256).toInt()
    val ay = ((fy - y0) * 256).toInt()
    val a = src[y0 * w + x0]
    val bb = src[y0 * w + x1]
    val c = src[y1 * w + x0]
    val d = src[y1 * w + x1]
    fun ch(shift: Int): Int {
        val up = ((a shr shift) and 0xFF) * (256 - ax) + ((bb shr shift) and 0xFF) * ax
        val down = ((c shr shift) and 0xFF) * (256 - ax) + ((d shr shift) and 0xFF) * ax
        return (up * (256 - ay) + down * ay) shr 16
    }
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/** Tu fondo ya desenfocado y sus extremos: el color de su cuarta parte más clara ([light]) y de la más oscura ([shade]). */
class Blurred(val image: ImageBitmap, val light: Color, val shade: Color)

/**
 * Una copia chica y desenfocada de [src] para el vidrio (unos 96 px de ancho; al dibujarse en grande con filtro queda
 * suave). Tres pasadas de caja ≈ desenfoque gaussiano. [vivid] = para el cristal: menos desenfoque (más ancha) y los
 * colores un poco más vivos, que es lo que se ve a través. Correr fuera del hilo principal.
 */
fun blurredCopy(src: Bitmap, vivid: Boolean = false): Blurred {
    val w = if (vivid) CLEAR_WIDTH else BLUR_WIDTH
    val h = (src.height * w / src.width.coerceAtLeast(1)).coerceAtLeast(1)
    val small = Bitmap.createScaledBitmap(src, w, h, true)
    val px = IntArray(w * h)
    small.getPixels(px, 0, w, 0, 0, w, h)
    val tmp = IntArray(px.size)
    repeat(3) {
        boxBlur(px, tmp, w, h, BLUR_RADIUS, horizontal = true)
        boxBlur(tmp, px, w, h, BLUR_RADIUS, horizontal = false)
    }
    if (vivid) for (i in px.indices) px[i] = livelier(px[i])
    val out = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    if (small !== src) small.recycle()
    val (light, shade) = extremes(px)
    return Blurred(out.asImageBitmap(), light, shade)
}

/** Los extremos de [px]: el promedio de su cuarta parte más clara y el de la más oscura. */
internal fun extremes(px: IntArray): Pair<Color, Color> {
    val byLight = px.sortedBy { luma(it) }
    val quarter = (byLight.size / 4).coerceAtLeast(1)
    return average(byLight.takeLast(quarter)) to average(byLight.take(quarter))
}

/**
 * El color medio de [src], de una muestra de unos [MEAN_SAMPLES] puntos por lado leída renglón por renglón (no copia
 * la imagen entera: en pantallas de 1 GB un fondo completo son 8 MB). Una vez por imagen, fuera del hilo principal.
 */
fun meanColor(src: Bitmap): Color {
    val w = src.width
    val h = src.height
    if (w <= 0 || h <= 0) return Color.Black
    val step = (maxOf(w, h) / MEAN_SAMPLES).coerceAtLeast(1)
    val row = IntArray(w)
    val picked = ArrayList<Int>()
    var y = step / 2
    while (y < h) {
        src.getPixels(row, 0, w, 0, y, w, 1)
        for (x in step / 2 until w step step) picked += row[x]
        y += step
    }
    return average(picked)
}

private fun luma(p: Int) = 2126 * ((p shr 16) and 0xFF) + 7152 * ((p shr 8) and 0xFF) + 722 * (p and 0xFF)

internal fun average(px: List<Int>): Color {
    var r = 0L; var g = 0L; var b = 0L
    for (p in px) { r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF }
    val n = px.size.coerceAtLeast(1)
    return Color((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
}

/** Un pixel con los colores más vivos ([VIVID_SATURATION]) y apenas más claro, para lo que se ve a través del cristal. */
private fun livelier(p: Int): Int {
    val r = (p shr 16) and 0xFF
    val g = (p shr 8) and 0xFF
    val b = p and 0xFF
    val gray = (2126 * r + 7152 * g + 722 * b) / 10000f
    fun ch(v: Int) = ((gray + (v - gray) * VIVID_SATURATION) * VIVID_BRIGHTNESS).toInt().coerceIn(0, 255)
    return (p and 0xFF000000.toInt()) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
}

/** Un promedio de caja de radio [r] a lo ancho o a lo alto, de [from] a [to] (orillas repetidas). */
internal fun boxBlur(from: IntArray, to: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
    val lines = if (horizontal) h else w
    val length = if (horizontal) w else h
    val n = 2 * r + 1
    for (line in 0 until lines) {
        fun at(i: Int) = from[if (horizontal) line * w + i.coerceIn(0, length - 1) else i.coerceIn(0, length - 1) * w + line]
        var a = 0; var rr = 0; var g = 0; var bb = 0
        for (i in -r..r) at(i).let { a += it ushr 24; rr += (it shr 16) and 0xFF; g += (it shr 8) and 0xFF; bb += it and 0xFF }
        for (i in 0 until length) {
            to[if (horizontal) line * w + i else i * w + line] = ((a / n) shl 24) or ((rr / n) shl 16) or ((g / n) shl 8) or (bb / n)
            val out = at(i - r)
            val inn = at(i + r + 1)
            a += (inn ushr 24) - (out ushr 24)
            rr += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
            g += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
            bb += (inn and 0xFF) - (out and 0xFF)
        }
    }
}

/** De vidrio, al menos esto se ve de ellas (si no, el texto se pierde sobre un fondo claro). */
const val MIN_OPACITY = 0.45f

private const val BLUR_WIDTH = 96
/** El cristal desenfoca menos: la copia es más ancha (mismo radio). */
private const val CLEAR_WIDTH = 480
private const val VIVID_SATURATION = 1.9f
private const val VIVID_BRIGHTNESS = 1.12f
/** Lo más cerrado que llega el velo detrás de un cristal (y el de tu fondo en el inicio). */
private const val VEIL_MAX = 0.9f
/** Contraste mínimo de lo que va sin tarjeta sobre el color medio de tu fondo (texto grande y en negritas). */
private const val PLAIN_CONTRAST = 3f
/** Puntos por lado con que se saca el color medio de tu fondo. */
private const val MEAN_SAMPLES = 64
/** El canto del cristal que dobla tu fondo: cuánto entra desde la orilla y cuánto lo empuja (en cantos; > 1 lo refleja). */
private val LENS_DEPTH = 26.dp
private const val LENS_PUSH = 1.6f
/** A qué fracción del tamaño de la tarjeta se calcula el fondo doblado. */
private const val LENS_SCALE = 0.5f
/** Cuánto debe estar quieta la tarjeta para doblar su fondo (al arrastrarla o deslizar la página va el de antes). */
private const val LENS_SETTLE_MS = 90L
/** El grosor del cristal: hasta dónde entra desde la orilla y en cuántas bandas se dibuja. */
private val GLASS_DEPTH = 12.dp
private const val GLASS_DEPTH_STEPS = 8
/** El brillo fino de la orilla del cristal. */
private val GLASS_RIM = 1.dp
/** La sombra del cristal: hasta dónde llega, en cuántos aros y cuánto cae. */
private val SHADOW_SPREAD = 24.dp
private const val SHADOW_RINGS = 16
private val SHADOW_DROP = 9.dp
private val SHADOW_INSET = 6.dp
/**
 * De lo que se oscurece tu fondo en el inicio, cuánto lleva detrás de un cristal: adentro se ve más claro y vivo que
 * afuera (la luz «atrapada» es lo que lo hace parecer vidrio). Si el texto no se leería, [glassVeil] lo cierra igual.
 */
const val GLASS_LIFT = 0.4f
/**
 * Cuánto se exagera lo más claro (o lo más oscuro) de tu fondo al calcular ese velo: el promedio de su cuarta parte
 * más clara se queda corto si la imagen trae una mancha muy brillante justo donde cae el texto.
 */
const val GLASS_GUARD = 0.35f
private const val BLUR_RADIUS = 3
