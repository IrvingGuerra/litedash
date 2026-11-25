package com.irvingguerra.litedash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Radius
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.core.ui.horizontalSwipe
import com.irvingguerra.litedash.feature.dashboard.PageBackground
import com.irvingguerra.litedash.feature.dashboard.STACK_MAX_FACES
import com.irvingguerra.litedash.feature.dashboard.STACK_MIN_FACES
import com.irvingguerra.litedash.feature.dashboard.Stack
import com.irvingguerra.litedash.feature.dashboard.Tile
import com.irvingguerra.litedash.feature.dashboard.TileTypes
import com.irvingguerra.litedash.feature.dashboard.isStack
import kotlin.math.abs

/** La franja de los puntitos, debajo de la cara (fuera de su tarjeta: no le tapa nada). */
private val STACK_STRIP = 26.dp

/** Lo que se toca de cada puntito y de cada flecha. */
private val STACK_DOT_TOUCH = 36.dp
private val STACK_ARROW_TOUCH = 48.dp

/** Desde este ancho, flechas discretas a los lados de los puntitos. */
private val STACK_ARROWS_MIN = 320.dp

/** Lo que dura el cambio de cara y cuánto se desliza (fracción del ancho). */
private const val STACK_SLIDE_MS = 180
private const val STACK_SLIDE = 0.25f

/** Caras que usan el arrastre (el auto gira, el mapa se mueve): ahí se cambia solo con los puntitos o las flechas. */
private val DRAG_FACES = setOf(TileTypes.CAR, TileTypes.MAP)

/**
 * La Pila: una cara a la vista, dibujada por [face] como su widget de siempre (las demás ni se componen). Se cambia
 * deslizando a lo ancho sobre ella, con los puntitos o con las flechas; la que se ve se guarda con el acomodo. Con una
 * sola cara que se vea (las de un módulo apagado no cuentan) es ese widget tal cual; sin ninguna, [onEdit] la arma.
 */
@Composable
fun StackTile(d: Dependencies, p: Tile, onEdit: () -> Unit, face: @Composable (Tile) -> Unit) {
    val stack = remember(p) { Stack.of(p) }
    val visible = stack.visible(d.dashboard.hidden)
    val shown = stack.shown(d.dashboard.hidden)
    if (shown == null) return StackEmpty(onEdit)
    if (visible.size == 1) return key(shown.id) { face(shown) }
    val index = visible.indexOf(shown)
    val show = { i: Int -> if (i in visible.indices && i != index) d.dashboard.setData(p.id, stack.copy(current = visible[i].id).encode()) }
    // Al cambiar, la nueva entra deslizándose desde el lado al que se fue (una vez; quieta no se anima nada).
    val slide = remember { Animatable(0f) }
    var last by remember { mutableIntStateOf(index) }
    LaunchedEffect(index) {
        val from = last
        last = index
        if (from == index) return@LaunchedEffect
        slide.snapTo(if (index > from) 1f else -1f)
        slide.animateTo(0f, tween(STACK_SLIDE_MS))
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val arrows = maxWidth >= STACK_ARROWS_MIN
        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .then(if (shown.type in DRAG_FACES) Modifier else Modifier.horizontalSwipe({ show(index + 1) }, { show(index - 1) }))
                    .graphicsLayer {
                        translationX = slide.value * size.width * STACK_SLIDE
                        alpha = 1f - abs(slide.value) * 0.6f
                        // Solo al deslizarse: quieta, la sombra del auto puede salirse como en su widget.
                        clip = slide.value != 0f
                    },
            ) { key(shown.id) { face(shown) } }
            StackDots(visible, index, arrows, show)
        }
    }
}

/** Los puntitos (uno por cara a la vista) y, si es ancha, las flechas. */
@Composable
private fun StackDots(faces: List<Tile>, index: Int, arrows: Boolean, show: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().height(STACK_STRIP), verticalAlignment = Alignment.CenterVertically) {
        if (arrows) StackArrow(faces.getOrNull(index - 1), back = true) { show(index - 1) }
        Row(Modifier.weight(1f).fillMaxHeight(), horizontalArrangement = Arrangement.Center) {
            faces.forEachIndexed { i, f ->
                val label = stringResource(R.string.stack_show, stringResource(nameOf(f.type)))
                Box(
                    Modifier
                        .width(STACK_DOT_TOUCH)
                        .fillMaxHeight()
                        .clickable(remember { MutableInteractionSource() }, indication = null) { show(i) }
                        .semantics { contentDescription = label },
                    contentAlignment = Alignment.Center,
                ) { Dot(i == index) }
            }
        }
        if (arrows) StackArrow(faces.getOrNull(index + 1), back = false) { show(index + 1) }
    }
}

/** Flecha a la cara de junto ([to]; null = no hay más de ese lado: tenue y sin toque). Se anuncia con su nombre. */
@Composable
private fun StackArrow(to: Tile?, back: Boolean, onClick: () -> Unit) {
    val c = LocalColors.current
    val enabled = to != null
    val label = to?.let { stringResource(R.string.stack_show, stringResource(nameOf(it.type))) }.orEmpty()
    Box(
        Modifier
            .width(STACK_ARROW_TOUCH)
            .fillMaxHeight()
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.chevron, c.textMuted.copy(alpha = if (enabled) 0.8f else 0.25f), 16.dp, Modifier.graphicsLayer { if (back) rotationZ = 180f })
    }
}

/** Sin caras a la vista: invita a armarla. */
@Composable
private fun StackEmpty(onEdit: () -> Unit) {
    val c = LocalColors.current
    Card(Modifier.fillMaxSize(), onClick = onEdit) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.stack, c.textMuted, 32.dp)
            Label(stringResource(R.string.stack_add), Typography.body, c.textMuted, maxLines = 2, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

private fun nameOf(type: String) = TileSpecs.firstOrNull { it.type == type }?.name ?: R.string.tile_stack

/** La Pila de fábrica: tu auto, las llantas (si las tienes) y la música. */
fun defaultStack(d: Dependencies): Stack =
    Stack.of(listOfNotNull(TileTypes.CAR, TileTypes.TIRES.takeIf { d.hasTpmsSource }, TileTypes.MUSIC))

/**
 * Lo que se puede agregar a la Pila [stack]: lo que cabe en su tamaño, de un módulo prendido y sin repetir (salvo las apps
 * y los medidores, que cambian por su dato); el Mapa no sobre el mapa de fondo.
 */
private fun faceTypes(d: Dependencies, stack: Tile, faces: List<Tile>): List<String> = TileSpecs.filter { spec ->
    val t = spec.type
    t != TileTypes.STACK && moduleOff(d, t) == null &&
        !(t == TileTypes.MAP && d.dashboard.background == PageBackground.MAP) &&
        spec.limits.minWidth <= stack.width && spec.limits.minHeight <= stack.height &&
        (t == TileTypes.APP || t == TileTypes.GAUGE || faces.none { it.type == t })
}.map { it.type }

/**
 * Opciones de la Pila ([tileId]): la Pila en vivo y su lista de caras. Tocar una la deja a la vista; cada una con sus
 * opciones ([onFaceOptions]), quitar (de [STACK_MIN_FACES] para arriba) y flechas para ordenarla (sin arrastrar).
 * «Agregar a la pila» ofrece lo que cabe ([faceTypes]); [onAdded] sigue con lo que la cara nueva necesite (la app, el dato).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CustomizeStackPanel(
    d: Dependencies,
    tileId: String,
    cell: DpSize,
    close: () -> Unit,
    onFaceOptions: (Tile) -> Unit,
    onAdded: (Tile) -> Unit,
    preview: @Composable (Tile) -> Unit,
) {
    val c = LocalColors.current
    val tile = d.dashboard.tile(tileId)?.takeIf { it.isStack }
    if (tile == null) {
        // Cerrar escribe estado: fuera de la composición.
        LaunchedEffect(Unit) { close() }
        return
    }
    val stack = Stack.of(tile)
    val save = { s: Stack -> d.dashboard.setData(tile.id, s.encode()) }
    var adding by remember { mutableStateOf(false) }
    val shownId = stack.shown(d.dashboard.hidden)?.id
    PanelScaffold(stringResource(R.string.widget_customize, stringResource(R.string.tile_stack)), close) {
        WidgetPanelLayout(
            preview = { m -> WidgetPreview(tile, cell, m) { preview(tile) } },
            options = { m ->
                Column(m.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Label(stringResource(R.string.stack_help), Typography.small, c.textMuted)
                    stack.faces.forEachIndexed { i, f ->
                        FaceRow(
                            f, on = f.id == shownId, off = d.dashboard.hidden(f.type),
                            first = i == 0, last = i == stack.faces.lastIndex,
                            removable = stack.faces.size > STACK_MIN_FACES,
                            onShow = { save(stack.copy(current = f.id)) },
                            onOptions = optionsPanelOf(f.type, f.id)?.let { { onFaceOptions(f) } },
                            onRemove = { save(stack.remove(f.id)) },
                            onMove = { by -> save(stack.move(f.id, by)) },
                        )
                    }
                    if (stack.faces.size < STACK_MAX_FACES) {
                        if (!adding) Button(stringResource(R.string.stack_add), { adding = true })
                        else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (type in faceTypes(d, tile, stack.faces)) Button(stringResource(nameOf(type)), {
                                val face = Stack.face(type)
                                save(stack.add(face).copy(current = face.id))
                                adding = false
                                onAdded(face)
                            })
                        }
                    }
                }
            },
        )
    }
}

/** Una cara en la lista: su ícono y nombre (tocarla la deja a la vista), opciones, quitar y las flechas. */
@Composable
private fun FaceRow(
    f: Tile,
    on: Boolean,
    off: Boolean,
    first: Boolean,
    last: Boolean,
    removable: Boolean,
    onShow: () -> Unit,
    onOptions: (() -> Unit)?,
    onRemove: () -> Unit,
    onMove: (Int) -> Unit,
) {
    val c = LocalColors.current
    val idle = c.textMuted.copy(alpha = 0.35f)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(Radius.inner)).background(c.surfaceHigh)
            .clickable(onClick = onShow).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // La que se ve, del color de la interfaz; la de un módulo apagado, en gris (no se ve hasta prenderlo).
        val tint = when {
            off -> idle
            on -> c.accent
            else -> c.textMuted
        }
        Icon(iconOf(f.type), tint, 22.dp)
        Label(stringResource(nameOf(f.type)), Typography.subtitle, if (off) c.textMuted else c.text, maxLines = 1, modifier = Modifier.weight(1f).padding(start = 6.dp))
        if (onOptions != null) IconButton(Icons.settings, onOptions, size = 44.dp, label = stringResource(com.irvingguerra.litedash.feature.dashboard.R.string.dashboard_settings))
        if (removable) IconButton(Icons.close, onRemove, size = 44.dp, label = stringResource(R.string.remove))
        IconButton(
            Icons.chevron, { onMove(-1) }, Modifier.graphicsLayer { rotationZ = -90f }, size = 44.dp,
            color = if (first) idle else c.text, label = stringResource(R.string.stack_move_up),
        )
        IconButton(
            Icons.chevron, { onMove(1) }, Modifier.graphicsLayer { rotationZ = 90f }, size = 44.dp,
            color = if (last) idle else c.text, label = stringResource(R.string.stack_move_down),
        )
    }
}
