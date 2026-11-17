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
 * desenfoca sin costarle nada a la GPU al dibujar (sirve igual en Android 6).
 */
@Immutable
class CardBackdrop(val image: ImageBitmap, val veil: Color)

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
)

// Dinámico: al arrastrar la opacidad solo se redibujan las tarjetas, no toda la app.
val LocalCardLook = compositionLocalOf { CardLook() }

/** El relleno de una superficie con [look] sobre los colores [c]; de vidrio, nunca menos visible que [floor]. */
private fun skinFill(look: CardLook, c: Colors, floor: Float): Color =
    if (look.style == CardStyle.SOLID) c.surface else c.surface.copy(alpha = look.opacity.coerceIn(maxOf(MIN_OPACITY, floor), 1f))

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
    if (glass && look.backdrop != null) m = m.blurredBackdrop(look.backdrop)
    m = m.background(skinFill(look, c, floor))
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

/** De vidrio, al menos esto se ve de ellas (si no, el texto se pierde sobre un fondo claro). */
const val MIN_OPACITY = 0.45f

private const val BLUR_WIDTH = 96
private const val BLUR_RADIUS = 3
