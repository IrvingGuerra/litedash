package com.irvingguerra.litedash.feature.map

import android.graphics.Camera
import android.graphics.Matrix as AndroidMatrix
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
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

/** Cuánto se acuesta el mapa inclinado (grados). */
private const val TILT_DEGREES = 50f

/** Inclinado, hasta dónde se dibuja hacia el horizonte (en tamaños de la vista). */
private const val REACH = 1.6f

/**
 * El mapa de OpenStreetMap con tu posición y como diga [look]: norte arriba o hacia donde vas,
 * plano o inclinado, zoom según la velocidad o fijo (un toque lo aleja; tras el más lejano vuelve
 * al elegido) y colores del tema, claros u oscuros. En oscuro las teselas se invierten y giran de tono.
 */
@Composable
fun OsmMap(store: TileStore, lat: Double, lon: Double, bearing: Float?, kmh: Float?, look: MapLook, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    var manual by remember { mutableStateOf<Int?>(null) }
    // El automático recuerda el anterior para no brincar alrededor de los umbrales (no es estado: no repinta).
    val auto = remember { intArrayOf(MAX_ZOOM) }
    auto[0] = autoZoom(kmh, auto[0])
    val tilted = look.view == MapView.TILTED
    // Inclinado se ve mucho más terreno: un zoom menos (cada tesela cubre el doble) para no pedir decenas.
    val zoom = manual ?: ((look.zoom.level ?: auto[0]) - if (tilted) 1 else 0).coerceAtLeast(MIN_ZOOM)
    val dark = look.dark(c.dark)
    // El minimalista ya viene en sus colores; las de OpenStreetMap se oscurecen aquí.
    val filter = if (dark && store.source.recolor) DarkTiles else null
    val accent = c.accent
    // Hacia donde vas: el mapa gira al revés del rumbo (sin rumbo todavía, se queda como está).
    val turn = if (look.orientation == MapOrientation.HEADING) -(bearing ?: 0f) else 0f
    // Inclinado, tu posición va más abajo: se ve más camino adelante.
    val anchorY = if (tilted) 0.78f else 0.6f
    val horizon = if (dark) Color(0xFF1C1C20) else Color(0xFFE8E8EA)
    Box(modifier.background(horizon).clickable { manual = nextZoom(zoom) }) {
        Canvas(Modifier.fillMaxSize()) {
            store.loaded // cada tesela nueva repinta
            val at = Offset(size.width / 2, size.height * anchorY)
            val m = mapMatrix(at, turn, tilted)
            drawIntoCanvas { canvas ->
                canvas.save()
                canvas.nativeCanvas.concat(m)
                drawTiles(store, lat, lon, zoom, filter, at, m)
                canvas.restore()
            }
            // Lo lejano se funde con el cielo: sólido hasta donde se acaba el mapa (en pantalla) y de ahí
            // se desvanece; sin orilla dura en ningún tamaño de widget.
            if (tilted) {
                val edge = floatArrayOf(at.x, at.y - REACH * maxOf(size.width, size.height)).also { m.mapPoints(it) }[1]
                val end = (edge / size.height).coerceIn(0f, 0.9f)
                drawRect(Brush.verticalGradient(0f to horizon, end to horizon, (end + 0.15f).coerceAtMost(1f) to horizon.copy(alpha = 0f)))
            }
            drawArrow(at, if (look.orientation == MapOrientation.HEADING) bearing?.let { 0f } else bearing, accent, tilted)
        }
        Attribution(store.source, Modifier.align(Alignment.BottomEnd))
    }
}

/**
 * Del plano del mapa a la pantalla: gira [turn] grados alrededor de tu posición [at] y, si va
 * [tilted], lo acuesta en perspectiva (la cámara mira hacia adelante, como al navegar).
 */
private fun DrawScope.mapMatrix(at: Offset, turn: Float, tilted: Boolean): AndroidMatrix {
    val m = AndroidMatrix()
    if (turn != 0f) m.postRotate(turn, at.x, at.y)
    if (tilted) {
        val tilt = AndroidMatrix()
        Camera().apply {
            // Más lejos en pantallas grandes: misma perspectiva en cualquier densidad.
            setLocation(0f, 0f, -8f * density)
            rotateX(TILT_DEGREES)
            getMatrix(tilt)
        }
        tilt.preTranslate(-at.x, -at.y)
        tilt.postTranslate(at.x, at.y)
        m.postConcat(tilt)
    }
    return m
}

private fun DrawScope.drawTiles(store: TileStore, lat: Double, lon: Double, z: Int, filter: ColorFilter?, at: Offset, m: AndroidMatrix) {
    val ts = tilePx(density)
    val n = 1 shl z
    val fx = tileX(lon, z)
    val fy = tileY(lat, z)
    val cx = at.x
    val cy = at.y
    // Lo que se ve, en el plano del mapa: las esquinas de la pantalla llevadas de regreso. Inclinado,
    // las de arriba quedan muy lejos: se limita a unas pantallas (más allá ya se funde con el cielo).
    val inverse = AndroidMatrix().also { m.invert(it) }
    val corners = floatArrayOf(0f, 0f, size.width, 0f, 0f, size.height, size.width, size.height)
    inverse.mapPoints(corners)
    val far = REACH * maxOf(size.width, size.height)
    var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
    for (i in corners.indices step 2) {
        val x = (corners[i] - cx).coerceIn(-far, far)
        val y = (corners[i + 1] - cy).coerceIn(-far, far)
        minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y)
    }
    val keys = HashSet<Long>()
    for (tx in tileSpan(fx, minX, maxX, ts)) for (ty in tileSpan(fy, minY, maxY, ts)) {
        if (ty !in 0 until n) continue
        val x = Math.floorMod(tx, n)
        val key = tileKey(z, x, ty)
        keys += key
        // Bordes redondeados a píxel con el de la siguiente: sin rayas entre teselas.
        val left = (cx + (tx - fx) * ts).roundToInt()
        val top = (cy + (ty - fy) * ts).roundToInt()
        val dst = IntSize((cx + (tx + 1 - fx) * ts).roundToInt() - left, (cy + (ty + 1 - fy) * ts).roundToInt() - top)
        val image = store.tile(key)
        if (image != null) {
            drawImage(image, dstOffset = IntOffset(left, top), dstSize = dst, colorFilter = filter, filterQuality = FilterQuality.Low)
        } else {
            // Mientras llega, el cuarto de la tesela del zoom anterior (al cambiar de zoom no se ve vacío).
            val parent = store.tile(tileKey(z - 1, x / 2, ty / 2)) ?: continue
            val half = parent.width / 2
            drawImage(
                parent, IntOffset((x % 2) * half, (ty % 2) * half), IntSize(half, half),
                IntOffset(left, top), dst, colorFilter = filter, filterQuality = FilterQuality.Low,
            )
        }
    }
    store.request(keys)
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
private fun Attribution(source: TileSource, modifier: Modifier) {
    val c = LocalColors.current
    val uri = LocalUriHandler.current
    Label(
        source.attribution,
        Typography.small.copy(fontSize = 11.sp),
        c.text,
        maxLines = 1,
        modifier = modifier
            .padding(6.dp)
            .clip(RoundedCornerShape(Radius.small))
            .background(c.surface.copy(alpha = 0.75f))
            .clickable { runCatching { uri.openUri(source.attributionUrl) } }
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
