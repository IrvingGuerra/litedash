package com.irvingguerra.litedash.core.ui

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize

/** Cómo se rellenan las tarjetas de toda la app: lisas o de vidrio (lo de fábrica). */
enum class CardStyle(val stored: String) {
    SOLID("solid"), GLASS("glass");

    companion object {
        /** Sin elegir, de vidrio; también quien tenía las translúcidas (ya no existen). */
        fun fromStored(value: String?) = if (value == SOLID.stored) SOLID else GLASS
    }
}

/** El filo de luz en la orilla de una tarjeta de vidrio: sin él (lo de fábrica), delgado o marcado (con canto). */
enum class CardEdge(val stored: String) {
    NONE("none"), THIN("thin"), BOLD("bold");

    companion object {
        fun fromStored(value: String?) = entries.firstOrNull { it.stored == value } ?: NONE
    }
}

/**
 * El fondo de pantalla ya desenfocado (una sola vez, en chico) y el velo que lleva encima: el vidrio lo dibuja detrás
 * de cada tarjeta, recortado como el fondo (que llena la ventana sin deformarse), así parece que la tarjeta lo
 * desenfoca sin costarle nada a la GPU al dibujar (sirve igual en Android 6). El velo se lee al dibujar: moverlo
 * («Oscurecer el fondo») solo redibuja, no recompone.
 */
@Immutable
class CardBackdrop(val image: ImageBitmap, val veil: () -> Color)

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
    /** De vidrio, su filo de luz. */
    val edge: CardEdge = CardEdge.NONE,
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
 * desenfocado detrás (si el inicio lo puso), el relleno translúcido, su brillo, su reflejo y su filo ([CardLook.edge]).
 * [floor] = lo menos que se ve una flotante (la barra), que va sobre cualquier cosa.
 */
fun Modifier.skinSurface(look: CardLook, c: Colors, shape: Shape, floor: Float = 0f): Modifier {
    var m = this
    val glass = look.style == CardStyle.GLASS
    if (!glass) return m.background(c.surface)
    if (look.backdrop != null) m = m.blurredBackdrop(look.backdrop)
    val fill = glassFill(look, floor)
    // Cristal líquido: la escarcha, el color de la tarjeta que se haya pedido, un brillo arriba, un reflejo en diagonal
    // (una franja de luz quieta, que se va conforme la tarjeta se llena) y, si se pide, el filo de luz. Sobre el mapa
    // de fondo, el mismo cristal pero ahumado con el color del fondo, que se vuelve el de la tarjeta conforme se llena.
    val tint = if (look.clear) c.surface.copy(alpha = fill) else lerp(c.background.copy(alpha = smokeAlpha(c)), c.surface, fill)
    val through = 1f - fill
    m = m.background(Color.White.copy(alpha = frost(c.dark) * through))
        .background(tint)
        .background(Brush.verticalGradient(0f to Color.White.copy(alpha = if (c.dark) 0.1f else 0.25f), 0.55f to Color.Transparent))
    // Solo sobre tu fondo (el inicio): en Ajustes y paneles, con muchas tarjetas en lista, estorbaría.
    if (look.clear && look.backdrop != null) {
        val shine = (if (c.dark) REFLECTION_DARK else REFLECTION_LIGHT) * through
        m = m.background(
            Brush.linearGradient(
                0.18f to Color.Transparent, 0.3f to Color.White.copy(alpha = shine), 0.42f to Color.White.copy(alpha = shine * 0.2f), 0.55f to Color.Transparent,
                start = Offset.Zero, end = Offset.Infinite,
            ),
        )
    }
    return m.glassEdge(look.edge, c.dark, shape)
}

/**
 * El filo de luz: nace en la esquina de arriba (más fuerte ahí, casi nada al centro). Delgado, una línea a media
 * fuerza; marcado, la línea entera y un canto ancho y suave por dentro, que le da grosor al cristal.
 */
private fun Modifier.glassEdge(edge: CardEdge, dark: Boolean, shape: Shape): Modifier {
    if (edge == CardEdge.NONE) return this
    val stops = if (dark) GLASS_EDGE_DARK else GLASS_EDGE_LIGHT
    val k = if (edge == CardEdge.THIN) 0.5f else 1f
    val line = border(1.dp, Brush.linearGradient(stops.map { Color.White.copy(alpha = it * k) }), shape)
    if (edge == CardEdge.THIN) return line
    return border(BEVEL, Brush.linearGradient(0f to Color.White.copy(alpha = 0.22f), 0.4f to Color.Transparent, 0.75f to Color.Transparent, 1f to Color.White.copy(alpha = 0.1f)), shape)
        .then(line)
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
        val strength = (if (dark) 0.2f else 0.09f) * (if (cheap) 0.6f else 1f)
        val stroke = Stroke(ring + 1f)
        val shapes = List(rings) { i ->
            val out = ring * (i + 0.5f)
            val fade = 1f - i / rings.toFloat()
            ShadowRing(Color.Black.copy(alpha = strength * fade * fade), Offset(-out, -out + drop), Size(size.width + 2 * out, size.height + 2 * out), CornerRadius(r + out))
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

/** Dibuja [b] detrás, recortado como el fondo de la ventana: se ve la parte del fondo que la tarjeta tapa. */
fun Modifier.blurredBackdrop(b: CardBackdrop): Modifier = composed {
    // Dónde está la tarjeta y cuánto mide la ventana: se leen al dibujar (moverla solo la redibuja).
    var at by remember { mutableStateOf(Offset.Zero) }
    var root by remember { mutableStateOf(Size.Zero) }
    onGloballyPositioned {
        at = it.positionInRoot()
        root = it.findRootCoordinates().size.toSize()
    }.drawBehind {
        if (root.width <= 0f) return@drawBehind
        val iw = b.image.width.toFloat()
        val ih = b.image.height.toFloat()
        val s = maxOf(root.width / iw, root.height / ih)
        translate((root.width - iw * s) / 2 - at.x, (root.height - ih * s) / 2 - at.y) {
            scale(s, Offset.Zero) { drawImage(b.image, filterQuality = FilterQuality.High) }
        }
        drawRect(b.veil())
    }
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
private const val CLEAR_WIDTH = 224
private const val VIVID_SATURATION = 1.9f
private const val VIVID_BRIGHTNESS = 1.12f
/** Lo más cerrado que llega el velo detrás de un cristal (y el de tu fondo en el inicio). */
private const val VEIL_MAX = 0.9f
/** Contraste mínimo de lo que va sin tarjeta sobre el color medio de tu fondo (texto grande y en negritas). */
private const val PLAIN_CONTRAST = 3f
/** Puntos por lado con que se saca el color medio de tu fondo. */
private const val MEAN_SAMPLES = 64
/** El filo del cristal (blanco, de la esquina de arriba a la de abajo): tema oscuro y tema claro. */
private val GLASS_EDGE_DARK = listOf(0.7f, 0.1f, 0.3f)
private val GLASS_EDGE_LIGHT = listOf(1f, 0.3f, 0.55f)
/** El canto del filo marcado. */
private val BEVEL = 4.dp
/** Lo más fuerte del reflejo en diagonal (blanco): tema oscuro y tema claro. */
private const val REFLECTION_DARK = 0.13f
private const val REFLECTION_LIGHT = 0.3f
/** La sombra del cristal: hasta dónde llega, en cuántos aros y cuánto cae. */
private val SHADOW_SPREAD = 14.dp
private const val SHADOW_RINGS = 7
private val SHADOW_DROP = 4.dp
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
