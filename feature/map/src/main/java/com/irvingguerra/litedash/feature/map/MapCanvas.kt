package com.irvingguerra.litedash.feature.map

import android.graphics.Matrix as AndroidMatrix
import androidx.compose.foundation.background
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

/** El lado de una tesela en pantalla: 256 px a densidad 1, hasta el doble (más se vería borrosa). */
fun tilePx(density: Float) = TILE * density.coerceIn(1f, 2f)

/** Abajo de este ancho los créditos van en su versión corta. */
private val SHORT_ATTRIBUTION = 320.dp

/** Los créditos llevan aquí (lo pide la licencia de los datos de OpenStreetMap, que usan las dos fuentes). */
private const val ATTRIBUTION_URL = "https://www.openstreetmap.org/copyright"

/**
 * El mapa (teselas de [store]) con tu posición y como diga [look]: norte arriba o hacia donde vas,
 * plano o inclinado, zoom según la velocidad o fijo (sin [onTap], un toque lo aleja; tras el más
 * lejano vuelve al elegido) y colores claros u oscuros: las de OpenStreetMap se oscurecen con un
 * filtro; el minimalista ya viene en sus colores.
 */
@Composable
fun MapCanvas(store: TileStore, lat: Double, lon: Double, bearing: Float?, kmh: Float?, look: MapLook, modifier: Modifier = Modifier, onTap: (() -> Unit)? = null) {
    val c = LocalColors.current
    var manual by remember { mutableStateOf<Int?>(null) }
    // El automático recuerda el anterior para no brincar alrededor de los umbrales (no es estado: no repinta).
    val auto = remember { intArrayOf(MAX_ZOOM) }
    auto[0] = autoZoom(kmh, auto[0])
    val tilted = look.view == MapView.TILTED
    // Inclinado se ve mucho más terreno: un zoom menos (cada tesela cubre el doble) para no pedir decenas.
    val zoom = manual ?: ((look.zoom.level ?: auto[0]) - if (tilted) 1 else 0).coerceAtLeast(MIN_ZOOM)
    val dark = look.dark(c.dark)
    val filter = if (dark && store.source.recolor) DarkTiles else null
    val accent = c.accent
    // Hacia donde vas: el mapa gira al revés del rumbo (sin rumbo todavía, se queda como está).
    val turn = if (look.orientation == MapOrientation.HEADING) -(bearing ?: 0f) else 0f
    val arrow = if (look.orientation == MapOrientation.HEADING) bearing?.let { 0f } else bearing
    // Inclinado, tu posición va más abajo: se ve más camino adelante.
    val anchorY = if (tilted) 0.78f else 0.6f
    val sky = store.source.sky?.let(::Color) ?: if (dark) Color(0xFF1C1C20) else Color(0xFFE8E8EA)
    // Un toque: lo que diga [onTap] (en el widget, verlo en grande); si no, alejar.
    BoxWithConstraints(modifier.background(sky).clickable { onTap?.invoke() ?: run { manual = nextZoom(zoom) } }) {
        val short = maxWidth < SHORT_ATTRIBUTION
        Spacer(
            Modifier.fillMaxSize().drawWithCache {
                // Se arma al cambiar el tamaño o la posición; entre tesela y tesela solo se redibuja.
                val at = Offset(size.width / 2, size.height * anchorY)
                val projection = MapProjection(size.width, size.height, at.x, at.y, turn, tilted, density)
                val matrix = AndroidMatrix().apply { setValues(projection.values) }
                val tiles = visibleTiles(projection, lat, lon, zoom, at, tilePx(density))
                // Lo lejano se funde con el cielo: sólido hasta donde se acaba el mapa (en pantalla) y de ahí
                // se desvanece; sin orilla dura en ningún tamaño de widget.
                val fade = if (tilted) {
                    val end = (projection.edge / size.height).coerceIn(0f, 0.9f)
                    Brush.verticalGradient(0f to sky, end to sky, (end + 0.15f).coerceAtMost(1f) to sky.copy(alpha = 0f))
                } else null
                onDrawBehind {
                    store.loaded // cada tesela nueva repinta
                    drawIntoCanvas { canvas ->
                        canvas.save()
                        canvas.nativeCanvas.concat(matrix)
                        drawTiles(store, tiles, filter)
                        canvas.restore()
                    }
                    store.request(tiles.keys)
                    fade?.let { drawRect(it) }
                    drawArrow(at, arrow, accent, tilted)
                }
            },
        )
        Attribution(if (short) store.source.attributionShort else store.source.attribution, Modifier.align(Alignment.BottomEnd))
    }
}

/**
 * Las teselas a dibujar, de lo lejano a lo cercano (lo cercano encima): su zoom, clave y dónde van en el
 * plano del mapa (en px, ya redondeado). Inclinado, lo lejano va con menos detalle ([detailDrop]).
 */
internal class TilePlan(val keys: Set<Long>, val z: IntArray, val x: IntArray, val y: IntArray, val left: IntArray, val top: IntArray, val right: IntArray, val bottom: IntArray)

internal fun visibleTiles(projection: MapProjection, lat: Double, lon: Double, z: Int, at: Offset, ts: Float): TilePlan {
    val fx = tileX(lon, z)
    val fy = tileY(lat, z)
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
private fun DrawScope.drawArrow(at: Offset, bearing: Float?, accent: Color, tilted: Boolean) {
    val u = 1.dp.toPx()
    if (tilted) return scale(1f, 0.7f, at) { drawArrow(at, bearing, accent, tilted = false) }
    drawCircle(accent.copy(alpha = 0.25f), 22 * u, at)
    if (bearing == null) {
        drawCircle(Color.White, 9 * u, at)
        drawCircle(accent, 7 * u, at)
        return
    }
    val arrow = Path().apply {
        moveTo(at.x, at.y - 14 * u)
        lineTo(at.x + 10 * u, at.y + 11 * u)
        lineTo(at.x, at.y + 5 * u)
        lineTo(at.x - 10 * u, at.y + 11 * u)
        close()
    }
    rotate(bearing, at) {
        drawPath(arrow, accent)
        drawPath(arrow, Color.White, style = Stroke(2 * u))
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
