package com.irvingguerra.litedash.core.ui

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
    val under = Color.White.copy(alpha = frost(c.dark)).compositeOver(if (c.dark) Color.White else Color.Black)
    var a = 0f
    while (a < 1f) {
        if (contrast(c.text, c.background.copy(alpha = a).compositeOver(under)) >= 4.5f) return a
        a += 0.05f
    }
    return 1f
}

/** La escarcha del cristal: un blanco muy leve que lo despega del fondo (se va conforme la tarjeta se llena). */
internal fun frost(dark: Boolean) = if (dark) 0.07f else 0.16f

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
 * desenfocado detrás (si el inicio lo puso), el relleno translúcido, su brillo y su borde. [floor] = lo menos que se
 * ve una flotante (la barra), que va sobre cualquier cosa.
 */
fun Modifier.skinSurface(look: CardLook, c: Colors, shape: Shape, floor: Float = 0f): Modifier {
    var m = this
    val glass = look.style == CardStyle.GLASS
    if (!glass) return m.background(c.surface)
    if (look.backdrop != null) m = m.blurredBackdrop(look.backdrop)
    val fill = glassFill(look, floor)
    // Cristal: la escarcha, el color de la tarjeta que se haya pedido, un brillo arriba y un filo de luz que nace en
    // la esquina (más fuerte arriba a la izquierda, casi nada al centro). Sobre el mapa de fondo, el mismo cristal
    // pero ahumado con el color del fondo, que se vuelve el de la tarjeta conforme se llena.
    val tint = if (look.clear) c.surface.copy(alpha = fill) else lerp(c.background.copy(alpha = smokeAlpha(c)), c.surface, fill)
    val edge = if (c.dark) GLASS_EDGE_DARK else GLASS_EDGE_LIGHT
    return m.background(Color.White.copy(alpha = frost(c.dark) * (1f - fill)))
        .background(tint)
        .background(Brush.verticalGradient(0f to Color.White.copy(alpha = if (c.dark) 0.14f else 0.35f), 0.55f to Color.Transparent))
        .border(1.dp, Brush.linearGradient(edge.map { Color.White.copy(alpha = it) }), shape)
}

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
    // Los extremos: el promedio de la cuarta parte más clara y de la más oscura.
    val byLight = px.sortedBy { luma(it) }
    val quarter = (byLight.size / 4).coerceAtLeast(1)
    return Blurred(out.asImageBitmap(), average(byLight.takeLast(quarter)), average(byLight.take(quarter)))
}

private fun luma(p: Int) = 2126 * ((p shr 16) and 0xFF) + 7152 * ((p shr 8) and 0xFF) + 722 * (p and 0xFF)

private fun average(px: List<Int>): Color {
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
private const val VIVID_SATURATION = 1.5f
private const val VIVID_BRIGHTNESS = 1.05f
/** Lo más cerrado que llega el velo detrás de un cristal. */
private const val VEIL_MAX = 0.9f
/** El filo del cristal (blanco, de la esquina de arriba a la de abajo): tema oscuro y tema claro. */
private val GLASS_EDGE_DARK = listOf(0.7f, 0.1f, 0.3f)
private val GLASS_EDGE_LIGHT = listOf(1f, 0.3f, 0.55f)
private const val BLUR_RADIUS = 3
