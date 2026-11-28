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
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlin.math.PI

/** Cómo se rellenan las tarjetas de toda la app: lisas o de vidrio (lo de fábrica). */
enum class CardStyle(val stored: String) {
    SOLID("solid"), GLASS("glass");

    companion object {
        /** Sin elegir, de vidrio; también quien tenía las translúcidas (ya no existen). */
        fun fromStored(value: String?) = if (value == SOLID.stored) SOLID else GLASS
    }
}

/** Qué tan grueso se ve el vidrio: el canto que dobla tu fondo, su sombra interior y el resplandor ([scale]). */
enum class GlassDepth(val stored: String, val scale: Float) {
    THIN("thin", 0.6f), MEDIUM("medium", 1f), THICK("thick", 1.45f);

    companion object {
        /** Sin elegir (o algo desconocido), el de fábrica. */
        fun fromStored(value: String?) = entries.firstOrNull { it.stored == value } ?: MEDIUM
    }
}

/**
 * El fondo de pantalla ya desenfocado (una sola vez, en chico) y el velo que lleva encima: el vidrio lo dibuja detrás
 * de cada tarjeta, recortado como el fondo (que llena la ventana sin deformarse), así parece que la tarjeta lo
 * desenfoca sin costarle nada a la GPU al dibujar (sirve igual en Android 6). El velo se lee al dibujar: moverlo
 * («Oscurecer el fondo») solo redibuja, no recompone.
 */
@Immutable
class CardBackdrop(val image: ImageBitmap, internal val sharp: Pixels? = null, val veil: () -> Color) {
    /** Sus pixeles, para doblarlos en la orilla del cristal ([refracted]); se leen una vez, fuera del hilo principal. */
    internal val pixels: IntArray by lazy {
        IntArray(image.width * image.height).also { image.asAndroidBitmap().getPixels(it, 0, image.width, 0, 0, image.width, image.height) }
    }
}

/**
 * La piel de las tarjetas de toda la app (Inicio y apariencia → Interfaz → Tarjetas): su [style] y, de vidrio, cuánto
 * se ve de ellas ([opacity], 0–1) y qué tan grueso ([depth]). Su color viene del tema ([Colors.withCards]). El [backdrop] solo lo pone el inicio
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
    val depth: GlassDepth = GlassDepth.MEDIUM,
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
internal fun frost(dark: Boolean) = if (dark) 0.04f else 0.07f

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
    if (look.backdrop != null) m = m.blurredBackdrop(look.backdrop, shape, c.dark, look.depth)
    val fill = glassFill(look, floor)
    // Sobre el mapa de fondo, el mismo cristal pero ahumado con el color del fondo, que se vuelve el de la tarjeta
    // conforme se llena.
    val tint = if (look.clear) c.surface.copy(alpha = fill) else lerp(c.background.copy(alpha = smokeAlpha(c)), c.surface, fill)
    val through = 1f - fill
    return m.background(Color.White.copy(alpha = frost(c.dark) * through))
        .background(tint)
        .liquidGlass(c.dark, shape, through, lit = look.clear && look.backdrop != null, look.depth)
}

/**
 * La luz de un cristal líquido, dibujada una vez por tamaño. La orilla brilla donde mira hacia la luz (arriba a la
 * izquierda) y, por el reflejo de adentro, donde mira justo al revés (abajo a la derecha); entre ellas casi se apaga
 * ([rimLight]). En un rectángulo redondeado eso es exacto con poco: cada lado recto lleva una sola fuerza y cada esquina
 * un degradado en barrido centrado en ELLA (ahí el ángulo del barrido es el de la orilla). Del lado de la luz, además,
 * un resplandor ancho y suave hacia adentro: el grosor del vidrio. Todo se va conforme la tarjeta se llena ([through] =
 * cuánto queda de cristal). [lit] = sobre tu fondo (el inicio): en Ajustes y paneles, con muchas tarjetas en lista, va
 * a media fuerza. (Matemática de la luz por la normal: Kyant0/AndroidLiquidGlass, Apache-2.0, y styropyr0/Prismal, MIT.)
 */
private fun Modifier.liquidGlass(dark: Boolean, shape: Shape, through: Float, lit: Boolean, depth: GlassDepth): Modifier = drawWithCache {
    val k = through * (if (lit) 1f else 0.5f)
    val outline = shape.createOutline(size, layoutDirection, this)
    fun white(a: Float) = Color.White.copy(alpha = (a * k).coerceIn(0f, 1f))
    val wash = Brush.verticalGradient(0f to white(if (dark) 0.05f else 0.12f), 0.45f to Color.Transparent)
    val radius = when (outline) {
        is Outline.Rounded -> outline.roundRect.topLeftCornerRadius.x
        is Outline.Rectangle -> 0f
        // Una forma que no es un rectángulo redondeado: solo un filo parejo.
        else -> return@drawWithCache onDrawBehind {
            drawRect(wash)
            drawOutline(outline, white(if (dark) 0.3f else 0.6f), style = Stroke(GLASS_RIM.toPx() * 2))
        }
    }.coerceAtMost(minOf(size.width, size.height) / 2)
    val rim = rimStrokes(size, radius, GLASS_RIM.toPx(), 0f) { nx, ny -> white((if (dark) 0.8f else 1f) * rimLight(nx, ny, if (dark) 0.07f else 0.12f)) }
    // El resplandor: solo donde la orilla mira a la luz, ancho y tenue.
    // En tres trazos que se van cerrando hacia la orilla: sumados dan un degradado sin escalón a la vista.
    val glowWidth = (GLASS_GLOW * depth.scale).toPx().coerceAtMost(radius.coerceAtLeast(GLASS_RIM.toPx() * 3))
    val glow = (1..GLOW_STEPS).flatMap { step ->
        rimStrokes(size, radius, glowWidth * step / GLOW_STEPS, GLASS_RIM.toPx()) { nx, ny -> white((if (dark) 0.2f else 0.22f) / GLOW_STEPS * litSide(nx, ny)) }
    }
    onDrawBehind {
        drawRect(wash)
        for (g in glow) g.draw(this)
        for (r in rim) r.draw(this)
    }
}

/** Hacia dónde está la luz del cristal (arriba a la izquierda), como vector unitario en pantalla (y hacia abajo). */
private const val LIGHT_X = -0.6f
private const val LIGHT_Y = -0.8f
private const val LIGHT_POWER = 4.5f

/** Cuánto mira a la luz una orilla cuya normal hacia afuera es ([nx], [ny]): 1 = de frente, 0 = de lado o de espaldas. */
internal fun litSide(nx: Float, ny: Float): Float = (nx * LIGHT_X + ny * LIGHT_Y).coerceAtLeast(0f).pow(LIGHT_POWER)

/**
 * El brillo fino de la orilla: fuerte de frente a la luz y también de espaldas (el reflejo de adentro), casi nada de
 * lado; nunca menos que [floor], para que la tarjeta no pierda su orilla.
 */
internal fun rimLight(nx: Float, ny: Float, floor: Float): Float {
    val lobe = abs(nx * LIGHT_X + ny * LIGHT_Y).pow(LIGHT_POWER)
    return floor + (1f - floor) * lobe
}

/** Un trazo de la orilla ya armado: un lado recto o el arco de una esquina. */
private class RimStroke(val brush: Brush, val from: Offset, val to: Offset, val arc: Size?, val start: Float, val stroke: Stroke) {
    fun draw(scope: androidx.compose.ui.graphics.drawscope.DrawScope) = with(scope) {
        if (arc == null) drawLine(brush, from, to, stroke.width)
        else drawArc(brush, start, 90f, useCenter = false, topLeft = from, size = arc, style = stroke)
    }
}

/**
 * La orilla de un rectángulo redondeado de [size] y esquinas [radius] en trazos de [width], metidos [inset] desde el
 * borde: 4 lados (cada uno con el color de su normal) y 4 esquinas (un barrido centrado en cada una, con [color] en
 * 7 puntos del cuarto de vuelta). Los lados o esquinas que no llevan nada no se dibujan.
 */
private fun rimStrokes(size: Size, radius: Float, width: Float, inset: Float, color: (nx: Float, ny: Float) -> Color): List<RimStroke> {
    val out = ArrayList<RimStroke>(8)
    val half = width / 2 + inset
    val stroke = Stroke(width)
    val w = size.width
    val h = size.height
    fun side(nx: Float, ny: Float, from: Offset, to: Offset) {
        val c = color(nx, ny)
        if (c.alpha > 0.004f) out += RimStroke(androidx.compose.ui.graphics.SolidColor(c), from, to, null, 0f, stroke)
    }
    side(0f, -1f, Offset(radius, half), Offset(w - radius, half))
    side(0f, 1f, Offset(radius, h - half), Offset(w - radius, h - half))
    side(-1f, 0f, Offset(half, radius), Offset(half, h - radius))
    side(1f, 0f, Offset(w - half, radius), Offset(w - half, h - radius))
    if (radius <= half) return out
    val arc = Size(2 * (radius - half), 2 * (radius - half))
    // Cada esquina: su centro, dónde empieza su cuarto de vuelta (0° = a la derecha, en el sentido del reloj).
    fun corner(cx: Float, cy: Float, start: Float) {
        val stops = ArrayList<Pair<Float, Color>>(RIM_STOPS + 3)
        var any = false
        for (i in 0..RIM_STOPS) {
            val deg = start + 90f * i / RIM_STOPS
            val rad = deg * (PI.toFloat() / 180f)
            val c = color(kotlin.math.cos(rad), kotlin.math.sin(rad))
            if (c.alpha > 0.004f) any = true
            stops += (deg / 360f) to c
        }
        if (!any) return
        // El barrido da la vuelta entera: fuera del cuarto repite sus extremos (no se dibuja ahí).
        // (La esquina que termina en 360° continúa en 0°: ahí va su último color, sin costura.)
        val closes = stops.last().first >= 1f
        if (stops.first().first > 0f) stops.add(0, 0f to (if (closes) stops.last().second else stops.first().second))
        if (!closes) stops += 1f to stops.last().second
        out += RimStroke(Brush.sweepGradient(*stops.toTypedArray(), center = Offset(cx, cy)), Offset(cx - radius + half, cy - radius + half), Offset.Zero, arc, start, stroke)
    }
    corner(w - radius, h - radius, 0f)
    corner(radius, h - radius, 90f)
    corner(radius, radius, 180f)
    corner(w - radius, radius, 270f)
    return out
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
        val strength = (if (dark) 0.14f else 0.075f) * (if (cheap) 0.6f else 1f)
        val stroke = Stroke(ring + 1f)
        val shapes = List(rings) { i ->
            val out = ring * (i + 0.5f) - inset
            val fade = 1f - i / rings.toFloat()
            ShadowRing(Color.Black.copy(alpha = strength * fade * fade), Offset(-out, -out + drop), Size(size.width + 2 * out, size.height + 2 * out), CornerRadius((r + out).coerceAtLeast(0f)))
        }
        // El contorno oscuro que separa el cristal de un fondo claro (como el vidrio de iOS): por fuera, fino, más
        // marcado a los lados que arriba y abajo (ahí ya van la luz y la sombra).
        val edge = GLASS_EDGE.toPx()
        val edgeAlpha = if (dark) 0.5f else 0.2f
        val edgeBrush = Brush.verticalGradient(
            0f to Color.Black.copy(alpha = edgeAlpha * 0.42f), 0.12f to Color.Black.copy(alpha = edgeAlpha),
            0.88f to Color.Black.copy(alpha = edgeAlpha), 1f to Color.Black.copy(alpha = edgeAlpha * 0.42f),
        )
        onDrawBehind {
            for (s in shapes) drawRoundRect(s.color, s.at, s.size, s.corner, style = stroke)
            drawRoundRect(edgeBrush, Offset(-edge / 2, -edge / 2), Size(size.width + edge, size.height + edge), CornerRadius(r + edge / 2), style = Stroke(edge))
        }
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
 * [thickness] = qué tan ancho es ese canto. En equipos de poca memoria, sin doblar.
 */
fun Modifier.blurredBackdrop(b: CardBackdrop, lens: Shape? = null, dark: Boolean = true, thickness: GlassDepth = GlassDepth.MEDIUM): Modifier = composed {
    // Dónde está la tarjeta y cuánto mide la ventana: se leen al dibujar (moverla solo la redibuja).
    var at by remember { mutableStateOf(Offset.Zero) }
    var root by remember { mutableStateOf(Size.Zero) }
    var extent by remember { mutableStateOf(Size.Zero) }
    var bent by remember(b.image) { mutableStateOf<Bent?>(null) }
    val context = LocalContext.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    if (lens != null && !lowMemoryDevice(context)) LaunchedEffect(b.image, lens, dark, thickness) {
        snapshotFlow { Triple(at, root, extent) }.collectLatest { (where, window, size) ->
            if (window.width <= 0f || size.width < 1f || size.height < 1f) return@collectLatest
            val radius = when (val o = lens.createOutline(size, direction, density)) {
                is Outline.Rounded -> o.roundRect.topLeftCornerRadius.x
                is Outline.Rectangle -> 0f
                else -> return@collectLatest
            }
            delay(LENS_SETTLE_MS)
            val k = thickness.scale
            val (depth, push, inner) = with(density) { Triple((LENS_DEPTH * k).toPx(), (LENS_PUSH * k).toPx(), (INNER_SHADOW * k).toPx()) }
            val image = withContext(Dispatchers.Default) { refracted(b, where, window, size, radius, depth, push, inner, if (dark) INNER_SHADOW_ALPHA else INNER_SHADOW_LIGHT) }
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
 * La parte de [b] que cae bajo una tarjeta de [size] en [at] (ventana de [root]), vista a través de un cristal grueso
 * de esquinas [radius]. Al centro, tu fondo desenfocado tal cual. En el canto (una franja de [depth] junto a la orilla)
 * cada punto toma el fondo de más adentro, hasta [push], con la compresión pegada al borde de un vidrio de verdad
 * ([LENS_PROFILE]); y lo toma de la copia NÍTIDA ([CardBackdrop.sharp]): un fondo ya desenfocado doblado no se nota, uno
 * con detalle sí. Cada color se dobla un poco distinto ([LENS_DISPERSION]: el filo prismático). Encima, la sombra
 * interior ([inner] de ancho, [innerAlpha] de fuerza) que despega la orilla, y un grano mínimo contra las bandas. Correr fuera del hilo
 * principal. (Perfil y dispersión: Kyant0/AndroidLiquidGlass, Apache-2.0, y styropyr0/Prismal, MIT.)
 */
internal fun refracted(b: CardBackdrop, at: Offset, root: Size, size: Size, radius: Float, depth: Float, push: Float, inner: Float, innerAlpha: Float): ImageBitmap {
    val iw = b.image.width
    val ih = b.image.height
    val src = b.pixels
    val cover = maxOf(root.width / iw, root.height / ih)
    val left = (root.width - iw * cover) / 2
    val top = (root.height - ih * cover) / 2
    // La copia nítida cubre la ventana igual, con su propio tamaño.
    val fine = b.sharp
    val fineCover = if (fine != null) maxOf(root.width / fine.w, root.height / fine.h) else 1f
    val fineLeft = if (fine != null) (root.width - fine.w * fineCover) / 2 else 0f
    val fineTop = if (fine != null) (root.height - fine.h * fineCover) / 2 else 0f
    // Con detalle fino el canto pide más resolución; una tarjeta enorme baja la suya para no pasar de [LENS_MAX_PIXELS].
    val scale = minOf(LENS_SCALE, sqrt(LENS_MAX_PIXELS / (size.width * size.height).coerceAtLeast(1f)))
    val w = (size.width * scale).toInt().coerceAtLeast(1)
    val h = (size.height * scale).toInt().coerceAtLeast(1)
    val out = IntArray(w * h)
    val halfW = size.width / 2
    val halfH = size.height / 2
    val r = radius.coerceAtMost(minOf(halfW, halfH))
    val reach = depth.coerceAtMost(minOf(halfW, halfH))
    val shade = inner.coerceAtLeast(1f)
    for (y in 0 until h) {
        val py = (y + 0.5f) / scale
        for (x in 0 until w) {
            val px = (x + 0.5f) / scale
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
            var color = sample(src, iw, ih, (at.x + px - left) / cover - 0.5f, (at.y + py - top) / cover - 0.5f)
            if (inside < reach) {
                val slope = LENS_PROFILE[((inside / reach).coerceIn(0f, 1f) * (LENS_PROFILE.size - 1)).toInt()]
                val dx = -(if (px < halfW) -nx else nx) * push * slope
                val dy = -(if (py < halfH) -ny else ny) * push * slope
                val bent = if (fine != null) {
                    val spread = LENS_DISPERSION * slope
                    fun at(k: Float) = sample(fine.px, fine.w, fine.h, (at.x + px + dx * k - fineLeft) / fineCover - 0.5f, (at.y + py + dy * k - fineTop) / fineCover - 0.5f)
                    (0xFF shl 24) or (at(1f - spread) and 0xFF0000) or (at(1f) and 0xFF00) or (at(1f + spread) and 0xFF)
                } else sample(src, iw, ih, (at.x + px + dx - left) / cover - 0.5f, (at.y + py + dy - top) / cover - 0.5f)
                // Nítido en casi toda la franja y se funde con el centro esmerilado en su último tramo.
                val t = (inside / reach).coerceIn(0f, 1f)
                color = mix(color, bent, 1f - smooth(LENS_CLEAR, 1f, t))
            }
            if (inside < shade) {
                val t = 1f - (inside / shade).coerceIn(0f, 1f)
                color = mix(color, 0xFF000000.toInt(), innerAlpha * t * t)
            }
            out[y * w + x] = grained(color, x, y)
        }
    }
    return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** La compresión del canto: 1 en la orilla, ¼ a un cuarto de la franja y 0 donde termina (64 valores). */
private val LENS_PROFILE = FloatArray(64) { i ->
    val t = i / 63f
    val floor = 1f / 25f
    ((1f / ((1f + 4f * t) * (1f + 4f * t))) - floor) / (1f - floor)
}

/** [a] con [amount] (0–1) de [b], canal por canal. */
private fun mix(a: Int, b: Int, amount: Float): Int {
    val k = (amount.coerceIn(0f, 1f) * 256).toInt()
    fun ch(shift: Int) = (((a shr shift) and 0xFF) * (256 - k) + ((b shr shift) and 0xFF) * k) shr 8
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/** [p] con un grano fijo de ±[GRAIN] niveles según su lugar: rompe las bandas de un degradado suave. */
private fun grained(p: Int, x: Int, y: Int): Int {
    var v = x * 374761393 + y * 668265263
    v = (v xor (v ushr 13)) * 1274126177
    val n = ((v xor (v ushr 16)) and 0xFF) * (2 * GRAIN + 1) / 256 - GRAIN
    fun ch(shift: Int) = (((p shr shift) and 0xFF) + n).coerceIn(0, 255)
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
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
class Blurred(val image: ImageBitmap, val light: Color, val shade: Color, val sharp: Pixels? = null)

/** Los pixeles de una imagen y su tamaño (la copia nítida de tu fondo que se dobla en el canto del cristal). */
class Pixels(val px: IntArray, val w: Int, val h: Int)

/**
 * Una copia chica y desenfocada de [src] para el vidrio (unos 96 px de ancho; al dibujarse en grande con filtro queda
 * suave). Tres pasadas de caja ≈ desenfoque gaussiano. [vivid] = para el cristal: menos desenfoque (más ancha) y los
 * colores un poco más vivos, que es lo que se ve a través. Correr fuera del hilo principal.
 */
fun blurredCopy(src: Bitmap, vivid: Boolean = false, sharp: Boolean = false): Blurred {
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
    return Blurred(out.asImageBitmap(), light, shade, if (sharp) sharpCopy(src) else null)
}

/**
 * Tu fondo casi nítido (hasta [SHARP_WIDTH] de ancho, una pasada de caja mínima contra el dentado) y con el color vivo
 * del cristal: lo que el canto dobla. Unos 2 MB, compartidos por todas las tarjetas; no se hace en equipos de poca memoria.
 */
private fun sharpCopy(src: Bitmap): Pixels {
    val w = minOf(src.width, SHARP_WIDTH).coerceAtLeast(1)
    val h = (src.height * w / src.width.coerceAtLeast(1)).coerceAtLeast(1)
    val small = if (w == src.width) src else Bitmap.createScaledBitmap(src, w, h, true)
    val px = IntArray(w * h)
    small.getPixels(px, 0, w, 0, 0, w, h)
    if (small !== src) small.recycle()
    val tmp = IntArray(px.size)
    boxBlur(px, tmp, w, h, 1, horizontal = true)
    boxBlur(tmp, px, w, h, 1, horizontal = false)
    for (i in px.indices) px[i] = livelier(px[i])
    return Pixels(px, w, h)
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

/**
 * Un pixel con los colores más vivos y apenas más claro, para lo que se ve a través del cristal. Con curva: lo que ya
 * viene muy saturado o muy claro casi no se toca (no se quema ni se vuelve neón); lo apagado es lo que más sube.
 */
internal fun livelier(p: Int): Int {
    val r = (p shr 16) and 0xFF
    val g = (p shr 8) and 0xFF
    val b = p and 0xFF
    val gray = (2126 * r + 7152 * g + 722 * b) / 10000f
    val spread = (maxOf(r, g, b) - minOf(r, g, b)) / 255f
    val room = 1f - smooth(0.2f, 0.85f, spread)
    val headroom = 1f - smooth(0.75f, 0.98f, gray / 255f)
    val amount = 1f + (VIVID_SATURATION - 1f) * (0.3f + 0.7f * room * headroom)
    val lift = 1f + (VIVID_BRIGHTNESS - 1f) * headroom
    fun ch(v: Int) = ((gray + (v - gray) * amount) * lift).toInt().coerceIn(0, 255)
    return (p and 0xFF000000.toInt()) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
}

/** 0 hasta [from], 1 desde [to], suave entre ellos. */
private fun smooth(from: Float, to: Float, x: Float): Float {
    val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
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
private const val VIVID_SATURATION = 1.6f
private const val VIVID_BRIGHTNESS = 1.1f
/** Lo más ancha que va la copia nítida de tu fondo (la que se dobla en el canto). */
private const val SHARP_WIDTH = 960
/** Lo más cerrado que llega el velo detrás de un cristal (y el de tu fondo en el inicio). */
private const val VEIL_MAX = 0.9f
/** Contraste mínimo de lo que va sin tarjeta sobre el color medio de tu fondo (texto grande y en negritas). */
private const val PLAIN_CONTRAST = 3f
/** Puntos por lado con que se saca el color medio de tu fondo. */
private const val MEAN_SAMPLES = 64
/** El canto del cristal que dobla tu fondo: cuánto entra desde la orilla y hasta cuánto empuja lo que se ve. */
private val LENS_DEPTH = 44.dp
private val LENS_PUSH = 40.dp
/**
 * Hasta qué fracción de la franja el canto va del todo nítido; de ahí al centro se funde con el esmerilado, poco a poco:
 * un corte rápido se veía como un marco por dentro de la tarjeta (Irving, 2026-11-27).
 */
private const val LENS_CLEAR = 0.12f
/** Cuánto más (el azul) o menos (el rojo) se dobla cada color en la orilla: el filo prismático. */
private const val LENS_DISPERSION = 0.12f
/** A qué fracción del tamaño de la tarjeta se calcula el fondo doblado, y su tope de pixeles (≈ 1.6 MB por tarjeta). */
private const val LENS_SCALE = 0.75f
private const val LENS_MAX_PIXELS = 400_000f
/** Cuánto debe estar quieta la tarjeta para doblar su fondo (al arrastrarla o deslizar la página va el de antes). */
private const val LENS_SETTLE_MS = 90L
/** La sombra interior de la orilla: qué tan adentro llega y qué tan oscura es justo en el borde. */
private val INNER_SHADOW = 9.dp
private const val INNER_SHADOW_ALPHA = 0.16f
/** En tema claro, mucho menos: sobre blanco una orilla oscura se ve sucia (Irving, Nube, 2026-11-27). */
private const val INNER_SHADOW_LIGHT = 0.05f
/** El grano del fondo doblado, en niveles de 0–255 hacia cada lado. */
private const val GRAIN = 3
/** El brillo fino de la orilla del cristal, el resplandor del lado de la luz y el contorno oscuro de afuera. */
private val GLASS_RIM = 1.dp
private val GLASS_GLOW = 10.dp
private val GLASS_EDGE = 0.7.dp
/** En cuántos pasos se arma el degradado de la orilla en cada esquina. */
private const val RIM_STOPS = 6
private const val GLOW_STEPS = 3
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
