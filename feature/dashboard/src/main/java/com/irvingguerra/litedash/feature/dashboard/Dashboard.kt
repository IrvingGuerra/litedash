package com.irvingguerra.litedash.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.LocalColors
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

private val GAP = 6.dp

/** Arrastre en curso: mover la pieza o cambiarle el tamaño. */
private data class Drag(val id: String, val resizing: Boolean, val dx: Float = 0f, val dy: Float = 0f)

/**
 * El tablero del inicio. Fuera del modo edición solo pinta las piezas (nada se
 * recalcula ni se anima). Dejar presionado en cualquier parte entra al modo
 * edición; ahí cada pieza se arrastra, cambia de tamaño desde la esquina o se quita.
 */
@Composable
fun Dashboard(
    grid: Grid,
    tiles: List<Tile>,
    editing: Boolean,
    limits: (String) -> SizeLimits,
    onChange: (List<Tile>) -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Tile) -> Unit,
) {
    val c = LocalColors.current
    val currentOnEdit by rememberUpdatedState(onEdit)
    BoxWithConstraints(modifier.fillMaxSize()) {
        val cellWidth = maxWidth / grid.columns
        val cellHeight = maxHeight / grid.rows
        val density = LocalDensity.current
        val cwPx = with(density) { cellWidth.toPx() }
        val chPx = with(density) { cellHeight.toPx() }
        var drag by remember { mutableStateOf<Drag?>(null) }

        // Fondo: dejar presionado entra al modo edición; editando, se ven las celdas.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(editing) {
                    if (!editing) detectTapGestures(onLongPress = { currentOnEdit() })
                }
                .then(
                    if (editing) Modifier.drawBehind {
                        val r = 2.dp.toPx()
                        for (i in 0..grid.columns) for (j in 0..grid.rows) {
                            drawCircle(c.line, r, Offset(i * cwPx, j * chPx))
                        }
                    } else Modifier,
                ),
        )

        // Vista previa de dónde caerá la pieza que se está arrastrando.
        val preview = drag?.let { a -> tiles.firstOrNull { it.id == a.id }?.let { target(it, a, cwPx, chPx, limits) } }
        if (preview != null) {
            val ok = tiles.fits(preview, grid)
            Box(
                Modifier
                    .offset(cellWidth * preview.col, cellHeight * preview.row)
                    .size(cellWidth * preview.width, cellHeight * preview.height)
                    .padding(GAP)
                    .clip(RoundedCornerShape(20.dp))
                    .background((if (ok) c.accent else c.danger).copy(alpha = 0.18f))
                    .border(2.dp, if (ok) c.accent else c.danger, RoundedCornerShape(20.dp)),
            )
        }

        // El auto va primero: su sombra y reflejo pueden salirse de su pieza y deben
        // quedar debajo de las demás.
        for (p in tiles.sortedBy { if (it.type == TileTypes.CAR) 0 else 1 }) {
            val a = drag?.takeIf { it.id == p.id }
            val widthDp: Dp
            val heightDp: Dp
            if (a != null && a.resizing) {
                // Mientras cambia de tamaño, la pieza sigue al dedo (acotada a su mínimo).
                val m = limits(p.type)
                widthDp = with(density) { (p.width * cwPx + a.dx).coerceAtLeast(m.minWidth * cwPx).toDp() }
                heightDp = with(density) { (p.height * chPx + a.dy).coerceAtLeast(m.minHeight * chPx).toDp() }
            } else {
                widthDp = cellWidth * p.width
                heightDp = cellHeight * p.height
            }
            Box(
                Modifier
                    .offset(cellWidth * p.col, cellHeight * p.row)
                    .size(widthDp, heightDp)
                    .graphicsLayer {
                        if (a != null && !a.resizing) {
                            translationX = a.dx
                            translationY = a.dy
                        }
                    }
                    .padding(GAP),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .then(if (editing) Modifier else Modifier.longPress { currentOnEdit() }),
                ) { content(p) }

                if (editing) {
                    // Capa que atrapa los gestos: editando, la pieza no se usa, se acomoda.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(20.dp))
                            .border(2.dp, c.accent.copy(alpha = 0.7f), RoundedCornerShape(20.dp))
                            .pointerInput(p.id, tiles, grid) {
                                detectDragGestures(
                                    onDragStart = { drag = Drag(p.id, resizing = false) },
                                    onDrag = { change, d ->
                                        change.consume()
                                        drag = drag?.let { it.copy(dx = it.dx + d.x, dy = it.dy + d.y) }
                                    },
                                    onDragEnd = {
                                        drag?.let { end -> drop(p, end, cwPx, chPx, limits, tiles, grid, onChange) }
                                        drag = null
                                    },
                                    onDragCancel = { drag = null },
                                )
                            },
                    )
                    IconButton(
                        Icons.close,
                        onClick = { onChange(tiles.filterNot { it.id == p.id }) },
                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                        size = 40.dp,
                        background = c.surfaceHigh,
                    )
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .pointerInput(p.id, tiles, grid) {
                                detectDragGestures(
                                    onDragStart = { drag = Drag(p.id, resizing = true) },
                                    onDrag = { change, d ->
                                        change.consume()
                                        drag = drag?.let { it.copy(dx = it.dx + d.x, dy = it.dy + d.y) }
                                    },
                                    onDragEnd = {
                                        drag?.let { end -> drop(p, end, cwPx, chPx, limits, tiles, grid, onChange) }
                                        drag = null
                                    },
                                    onDragCancel = { drag = null },
                                )
                            },
                    ) {
                        // Sin clickable: el gesto de esta esquina es solo arrastrar.
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(50)).background(c.accent), contentAlignment = Alignment.Center) {
                            Icon(Icons.corner, c.background, 22.dp)
                        }
                    }
                }
            }
        }
    }
}

private fun target(p: Tile, a: Drag, cwPx: Float, chPx: Float, limits: (String) -> SizeLimits): Tile =
    if (a.resizing) {
        val (width, height) = limits(p.type).clamp(
            ((p.width * cwPx + a.dx) / cwPx).roundToInt(),
            ((p.height * chPx + a.dy) / chPx).roundToInt(),
        )
        p.copy(width = width, height = height)
    } else {
        p.copy(col = p.col + (a.dx / cwPx).roundToInt(), row = p.row + (a.dy / chPx).roundToInt())
    }

private fun drop(
    p: Tile,
    a: Drag,
    cwPx: Float,
    chPx: Float,
    limits: (String) -> SizeLimits,
    tiles: List<Tile>,
    c: Grid,
    onChange: (List<Tile>) -> Unit,
) {
    val moved = target(p, a, cwPx, chPx, limits)
    if (moved != p && tiles.fits(moved, c)) onChange(tiles.replace(moved))
}

/**
 * Detecta "dejar presionado" sin quitarle los toques normales a los botones de
 * la pieza: escucha antes que ellos (pase Initial) y solo consume si se cumplió
 * el tiempo, para que el botón no reciba también un toque.
 */
private fun Modifier.longPress(onLongPress: () -> Unit) = pointerInput(Unit) {
    val timeout = viewConfiguration.longPressTimeoutMillis
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val up = withTimeoutOrNull(timeout) { waitForUpOrCancellation(PointerEventPass.Initial) }
        if (up == null) {
            onLongPress()
            // Se tragan los eventos hasta que se levante el dedo.
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                ev.changes.forEach { it.consume() }
                if (ev.changes.all { !it.pressed }) break
            }
        }
    }
}
