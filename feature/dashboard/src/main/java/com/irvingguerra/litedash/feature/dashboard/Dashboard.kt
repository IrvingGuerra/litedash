package com.irvingguerra.litedash.feature.dashboard

import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.LocalPlainCard
import com.irvingguerra.litedash.core.ui.Radius
import kotlin.math.roundToInt
import kotlinx.coroutines.withTimeoutOrNull

private val GAP = 6.dp

/** Los botones redondos de arriba de la pieza elegida en el modo edición (✕, sin tarjeta, ajustes). */
private val EDIT_BUTTON = 40.dp

/** Lo que tardan las demás piezas en hacerse a un lado mientras arrastras. */
private const val MAKE_ROOM_MS = 180

/** Aire entre esos botones y la orilla de la pieza, y entre uno y otro. */
private val EDIT_MARGIN = 6.dp

/** Dónde empieza el botón [i] de esa fila (0 = la ✕, pegada a la orilla). */
private fun editButtonStart(i: Int) = EDIT_MARGIN + (EDIT_BUTTON + EDIT_MARGIN) * i

/** Arrastre en curso: mover la pieza o cambiarle el tamaño (cuánto, va aparte en un estado propio). */
private data class Drag(val id: String, val resizing: Boolean)

/**
 * El tablero del inicio. Fuera del modo edición solo pinta las piezas (nada se
 * recalcula ni se anima). Dejar presionado en cualquier parte entra al modo
 * edición; ahí cada pieza se arrastra (las que estorban se hacen a un lado, [arrange]) y,
 * tocándola, muestra sus botones: quitar, sin tarjeta, ajustes y la esquina del tamaño.
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
    /** Editando, las piezas que se personalizan ([hasSettings]) llevan un botón que hace esto. */
    onSettings: ((Tile) -> Unit)? = null,
    hasSettings: (Tile) -> Boolean = { false },
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
        // Cuánto se ha movido el dedo: se lee al acomodar o dibujar la pieza, no al
        // componer, así arrastrar no vuelve a armar todo el tablero en cada evento.
        var dragOffset by remember { mutableStateOf(Offset.Zero) }
        // La pieza tocada al acomodar: solo ella lleva botones (las demás, su borde), así no tapan todo.
        var selected by remember { mutableStateOf<String?>(null) }
        if (!editing && selected != null) selected = null

        // Fondo: dejar presionado entra al modo edición; editando, se ven las celdas.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(editing) {
                    // Editando, tocar el fondo suelta la pieza elegida.
                    if (!editing) detectTapGestures(onLongPress = { currentOnEdit() })
                    else detectTapGestures(onTap = { selected = null })
                }
                .then(
                    if (editing) Modifier.drawBehind {
                        val r = 1.5.dp.toPx()
                        for (i in 0..grid.columns) for (j in 0..grid.rows) {
                            drawCircle(c.line, r, Offset(i * cwPx, j * chPx))
                        }
                    } else Modifier,
                ),
        )

        // Vista previa de dónde caerá la pieza que se está arrastrando: cambia solo al pasar a otra celda.
        val preview by remember(tiles, cwPx, chPx, grid) {
            derivedStateOf { drag?.let { a -> tiles.firstOrNull { it.id == a.id }?.let { target(it, a, dragOffset, cwPx, chPx, limits, grid) } } }
        }
        // El acomodo si la soltaras ahí: las que estorban, ya a un lado (null = no cabe). Se recalcula
        // solo cuando la vista previa cambia de celda, no en cada movimiento del dedo.
        val arranged by remember(tiles, grid, cwPx, chPx) { derivedStateOf { preview?.let { tiles.arrange(it, grid) } } }
        preview?.let { shown ->
            val ok = arranged != null
            Box(
                Modifier
                    .offset(cellWidth * shown.col, cellHeight * shown.row)
                    .size(cellWidth * shown.width, cellHeight * shown.height)
                    .padding(GAP)
                    .clip(RoundedCornerShape(Radius.card))
                    .background((if (ok) c.accent else c.danger).copy(alpha = 0.18f))
                    .border(2.dp, if (ok) c.accent else c.danger, RoundedCornerShape(Radius.card)),
            )
        }

        // El auto va primero: su sombra y reflejo pueden salirse de su pieza y deben
        // quedar debajo de las demás.
        val ordered = remember(tiles) { tiles.sortedBy { if (it.type == TileTypes.CAR) 0 else 1 } }
        // Con llave: el estado de cada pieza (su animación) se queda con ella aunque cambie el orden.
        for (p in ordered) key(p.id) {
            val a = drag?.takeIf { it.id == p.id }
            // Las demás se van a donde quedarían (con una animación corta); al soltar, ahí se guardan.
            val spot = if (a == null) arranged?.firstOrNull { it.id == p.id } ?: p else p
            // Solo se anima mientras arrastras: al soltar, al girar la pantalla o al cargar otro acomodo va
            // directo a su lugar (si no, se vería un cuadro en el de antes).
            val target = IntOffset((cwPx * spot.col).roundToInt(), (chPx * spot.row).roundToInt())
            val moving by animateIntOffsetAsState(target, tween(MAKE_ROOM_MS), label = "tile")
            val chosen = editing && selected == p.id
            Box(
                Modifier
                    .offset { if (drag != null) moving else target }
                    .then(
                        if (a?.resizing == true) {
                            // Mientras cambia de tamaño, la pieza sigue al dedo, acotada a su mínimo y a la orilla
                            // de la cuadrícula: lo que se ve al arrastrar es lo que queda al soltar.
                            Modifier.layout { measurable, _ ->
                                val m = limits(p.type)
                                val w = (p.width * cwPx + dragOffset.x).coerceIn(m.minWidth * cwPx, maxOf(m.minWidth, grid.columns - p.col) * cwPx).roundToInt()
                                val h = (p.height * chPx + dragOffset.y).coerceIn(m.minHeight * chPx, maxOf(m.minHeight, grid.rows - p.row) * chPx).roundToInt()
                                val placeable = measurable.measure(Constraints.fixed(w, h))
                                layout(w, h) { placeable.place(0, 0) }
                            }
                        } else {
                            Modifier.size(cellWidth * p.width, cellHeight * p.height)
                        },
                    )
                    .graphicsLayer {
                        if (a != null && !a.resizing) {
                            translationX = dragOffset.x
                            translationY = dragOffset.y
                        }
                    }
                    .padding(GAP),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .then(if (editing) Modifier else Modifier.longPress { currentOnEdit() }),
                ) { CompositionLocalProvider(LocalPlainCard provides p.plain) { content(p) } }

                if (editing) {
                    // Arrastrar la pieza (mover) o su esquina (cambiar de tamaño): el mismo gesto.
                    fun Modifier.tileDrag(resizing: Boolean) = pointerInput(p.id, tiles, grid, cwPx, chPx) {
                        detectDragGestures(
                            onDragStart = { drag = Drag(p.id, resizing); dragOffset = Offset.Zero; selected = p.id },
                            onDrag = { change, d ->
                                change.consume()
                                dragOffset += d
                            },
                            onDragEnd = {
                                arranged?.takeIf { it != tiles }?.let(onChange)
                                drag = null
                            },
                            onDragCancel = { drag = null },
                        )
                    }
                    // Capa que atrapa los gestos: editando, la pieza no se usa, se acomoda (tocarla la elige).
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(Radius.card))
                            .border(if (chosen) 2.dp else 1.dp, if (chosen) c.accent else c.accent.copy(alpha = 0.35f), RoundedCornerShape(Radius.card))
                            .pointerInput(p.id) { detectTapGestures(onTap = { selected = if (selected == p.id) null else p.id }) }
                            .tileDrag(resizing = false),
                    )
                    if (chosen) IconButton(
                        Icons.close,
                        onClick = { onChange(tiles.filterNot { it.id == p.id }) },
                        modifier = Modifier.align(Alignment.TopStart).padding(EDIT_MARGIN),
                        size = EDIT_BUTTON,
                        background = c.surfaceHigh,
                    )
                    // Con o sin fondo de tarjeta (el auto, la carretera y la imagen no llevan). Junto
                    // a la ✕: en las piezas de una fila, la esquina de la derecha ya la ocupa la del tamaño.
                    if (chosen && !p.cardless) IconButton(
                        Icons.noCard,
                        onClick = { onChange(tiles.replace(p.copy(plain = !p.plain))) },
                        modifier = Modifier.align(Alignment.TopStart).padding(start = editButtonStart(1), top = EDIT_MARGIN),
                        size = EDIT_BUTTON,
                        background = if (p.plain) c.accent else c.surfaceHigh,
                        color = if (p.plain) c.onPrimary else c.text,
                    )
                    if (chosen && onSettings != null && hasSettings(p)) IconButton(
                        Icons.settings,
                        onClick = { onSettings(p) },
                        // Junto a la ✕ si no lleva el de "sin tarjeta" (auto, carretera, imagen): sin hueco en medio.
                        modifier = Modifier.align(Alignment.TopStart).padding(start = editButtonStart(if (p.cardless) 1 else 2), top = EDIT_MARGIN),
                        size = EDIT_BUTTON,
                        background = c.surfaceHigh,
                    )
                    if (chosen) Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .tileDrag(resizing = true),
                    ) {
                        // Sin clickable: el gesto de esta esquina es solo arrastrar.
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(50)).background(c.accent), contentAlignment = Alignment.Center) {
                            Icon(Icons.corner, c.onPrimary, 22.dp)
                        }
                    }
                }
            }
        }
    }
}

private fun target(p: Tile, a: Drag, d: Offset, cwPx: Float, chPx: Float, limits: (String) -> SizeLimits, c: Grid): Tile =
    if (a.resizing) {
        val (width, height) = limits(p.type).clamp(
            ((p.width * cwPx + d.x) / cwPx).roundToInt(),
            ((p.height * chPx + d.y) / chPx).roundToInt(),
            c.columns - p.col,
            c.rows - p.row,
        )
        p.copy(width = width, height = height)
    } else {
        p.copy(col = p.col + (d.x / cwPx).roundToInt(), row = p.row + (d.y / chPx).roundToInt())
    }

/**
 * Detecta "dejar presionado" sin quitarle los toques normales a los botones de
 * la pieza: escucha antes que ellos (pase Initial) y solo consume si se cumplió
 * el tiempo, para que el botón no reciba también un toque.
 */
private fun Modifier.longPress(onLongPress: () -> Unit) = pointerInput(Unit) {
    val timeout = viewConfiguration.longPressTimeoutMillis
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        // Termina antes del tiempo si se suelta, si se arrastra o si entra otro dedo:
        // así una pieza puede usar el arrastre (el auto gira con el dedo sin entrar a edición).
        val ended = withTimeoutOrNull(timeout) {
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                val change = ev.changes.firstOrNull { it.id == down.id }
                if (change == null || !change.pressed || ev.changes.size > 1) break
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
            }
            true
        }
        if (ended == null) {
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
