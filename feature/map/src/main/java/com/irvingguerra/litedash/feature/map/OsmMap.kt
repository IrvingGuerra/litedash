package com.irvingguerra.litedash.feature.map

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

/**
 * El mapa de OpenStreetMap con tu posición: norte arriba, tú al 60 % del alto (se ve más
 * adelante), la flecha con el rumbo y el zoom según la velocidad; un toque lo aleja (tras el
 * más lejano, vuelve al automático). En modo oscuro las teselas se invierten y giran de tono.
 */
@Composable
fun OsmMap(store: TileStore, lat: Double, lon: Double, bearing: Float?, kmh: Float?, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    var manual by remember { mutableStateOf<Int?>(null) }
    // El automático recuerda el anterior para no brincar alrededor de los umbrales (no es estado: no repinta).
    val auto = remember { intArrayOf(MAX_ZOOM) }
    auto[0] = autoZoom(kmh, auto[0])
    val zoom = manual ?: auto[0]
    val filter = if (c.dark) DarkTiles else null
    val accent = c.accent
    Box(modifier.background(c.surfaceHigh).clickable { manual = nextZoom(zoom) }) {
        Canvas(Modifier.fillMaxSize()) {
            store.loaded // cada tesela nueva repinta
            drawTiles(store, lat, lon, zoom, filter)
            drawArrow(Offset(size.width / 2, size.height * 0.6f), bearing, accent)
        }
        Attribution(Modifier.align(Alignment.BottomEnd))
    }
}

private fun DrawScope.drawTiles(store: TileStore, lat: Double, lon: Double, z: Int, filter: ColorFilter?) {
    val ts = TILE * density.coerceIn(1f, 2f)
    val n = 1 shl z
    val fx = tileX(lon, z)
    val fy = tileY(lat, z)
    val cx = size.width / 2
    val cy = size.height * 0.6f
    val keys = HashSet<Long>()
    for (tx in tileRange(fx, cx, size.width, ts)) for (ty in tileRange(fy, cy, size.height, ts)) {
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

/** Tu posición: la flecha con el rumbo (color de acento) o un punto si todavía no hay rumbo. */
private fun DrawScope.drawArrow(at: Offset, bearing: Float?, accent: Color) {
    val u = 1.dp.toPx()
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

/** "© OpenStreetMap" en la esquina (lo pide su licencia); tocarlo abre su página de derechos. */
@Composable
private fun Attribution(modifier: Modifier) {
    val c = LocalColors.current
    val uri = LocalUriHandler.current
    Label(
        "© OpenStreetMap",
        Typography.small.copy(fontSize = 11.sp),
        c.text,
        maxLines = 1,
        modifier = modifier
            .padding(6.dp)
            .clip(RoundedCornerShape(Radius.small))
            .background(c.surface.copy(alpha = 0.75f))
            .clickable { runCatching { uri.openUri("https://www.openstreetmap.org/copyright") } }
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
