package com.irvingguerra.litedash.core.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
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
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize

/** Cómo se rellenan las tarjetas del inicio: lisas (como siempre), translúcidas o de vidrio. */
enum class CardStyle(val stored: String) {
    SOLID("solid"), TRANSLUCENT("translucent"), GLASS("glass");

    companion object {
        fun fromStored(value: String?) = entries.firstOrNull { it.stored == value } ?: SOLID
    }
}

/**
 * El fondo de pantalla ya desenfocado (una sola vez, en chico) y el velo que lleva encima: el vidrio lo dibuja detrás
 * de cada tarjeta, recortado como el fondo (que llena la ventana sin deformarse), así parece que la tarjeta lo
 * desenfoca sin costarle nada a la GPU al dibujar (sirve igual en Android 6).
 */
@Immutable
class CardBackdrop(val image: ImageBitmap, val veil: Color)

/**
 * Las tarjetas del inicio (Inicio y apariencia → Interfaz → Tarjetas): su [color] (sin especificar = el del tema), su
 * [style] y, translúcidas o de vidrio, cuánto se ve de ellas ([opacity], 0–1). Fuera del inicio no se pone: lisas.
 */
@Immutable
class CardLook(
    val color: Color = Color.Unspecified,
    val style: CardStyle = CardStyle.SOLID,
    val opacity: Float = 1f,
    val backdrop: CardBackdrop? = null,
)

val LocalCardLook = staticCompositionLocalOf { CardLook() }

/** El relleno de una tarjeta con [look] sobre los colores [c]. */
internal fun cardFill(look: CardLook, c: Colors): Color {
    val base = if (look.color.isSpecified) look.color else c.surface
    return if (look.style == CardStyle.SOLID) base else base.copy(alpha = look.opacity.coerceIn(MIN_OPACITY, 1f))
}

/**
 * Los colores para lo de adentro de una tarjeta [look] sobre el fondo [c]: si el color elegido es claro en el tema
 * oscuro (o al revés), los textos toman los del otro tema para leerse. Sin color propio, los mismos [c].
 */
fun cardContentColors(look: CardLook, c: Colors): Colors {
    if (!look.color.isSpecified) return c
    val seen = cardFill(look, c).compositeOver(c.background)
    val light = seen.luminance() > 0.5f
    if (light != !c.dark) return c
    val other = if (light) LightColors else DarkColors
    return c.copy(text = other.text, textMuted = other.textMuted)
}

/** Lo de [Card] según [look]: el fondo desenfocado (vidrio sobre una imagen), el relleno y, de vidrio, su borde y brillo. */
internal fun Modifier.cardSurface(look: CardLook, c: Colors): Modifier {
    val shape = RoundedCornerShape(Radius.card)
    var m = this
    val glass = look.style == CardStyle.GLASS
    if (glass && look.backdrop != null) m = m.blurredBackdrop(look.backdrop)
    m = m.background(cardFill(look, c))
    if (glass) {
        val shine = if (c.dark) 0.07f else 0.35f
        val edge = if (c.dark) 0.14f else 0.55f
        m = m.background(Brush.verticalGradient(listOf(Color.White.copy(alpha = shine), Color.Transparent)))
            .border(1.dp, Color.White.copy(alpha = edge), shape)
    }
    return m
}

/** Dibuja [b] detrás, recortado como el fondo de la ventana: se ve la parte del fondo que la tarjeta tapa. */
private fun Modifier.blurredBackdrop(b: CardBackdrop): Modifier = composed {
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
        drawRect(b.veil)
    }
}

/**
 * Una copia chica y desenfocada de [src] para el vidrio (unos 96 px de ancho; al dibujarse en grande con filtro queda
 * suave). Tres pasadas de caja ≈ desenfoque gaussiano. Correr fuera del hilo principal.
 */
fun blurredCopy(src: Bitmap): ImageBitmap {
    val w = BLUR_WIDTH
    val h = (src.height * w / src.width.coerceAtLeast(1)).coerceAtLeast(1)
    val small = Bitmap.createScaledBitmap(src, w, h, true)
    val px = IntArray(w * h)
    small.getPixels(px, 0, w, 0, 0, w, h)
    val tmp = IntArray(px.size)
    repeat(3) {
        boxBlur(px, tmp, w, h, BLUR_RADIUS, horizontal = true)
        boxBlur(tmp, px, w, h, BLUR_RADIUS, horizontal = false)
    }
    val out = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    if (small !== src) small.recycle()
    return out.asImageBitmap()
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

/** Translúcidas, al menos esto se ve de ellas (si no, el texto se pierde en el fondo). */
const val MIN_OPACITY = 0.25f

private const val BLUR_WIDTH = 96
private const val BLUR_RADIUS = 3
