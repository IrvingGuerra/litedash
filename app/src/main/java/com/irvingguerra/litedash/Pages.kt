package com.irvingguerra.litedash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Radius
import com.irvingguerra.litedash.core.ui.Segmented
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.feature.dashboard.DashboardState
import com.irvingguerra.litedash.feature.dashboard.MAX_PAGES
import com.irvingguerra.litedash.feature.dashboard.PageBackground
import com.irvingguerra.litedash.feature.dashboard.PageSwipe
import com.irvingguerra.litedash.feature.dashboard.TileTypes
import com.irvingguerra.litedash.feature.dashboard.Tile
import com.irvingguerra.litedash.feature.dashboard.freeArea
import kotlinx.coroutines.launch

/**
 * Deslizar de página con el dedo (#22): el tablero sigue al dedo ([shift], en px) y, si pasó de [SWIPE_FRACTION] del
 * ancho ([width]) hacia una página que existe, entra la de junto desde ese lado; si no, regresa. En la primera y la
 * última, el tablero cede poco (se nota que no hay más). Con una sola página, null: todo como siempre.
 */
@Composable
internal fun rememberPageSwipe(pages: DashboardState, shift: Animatable<Float, *>, width: Float): PageSwipe? {
    val scope = rememberCoroutineScope()
    val w by rememberUpdatedState(width)
    if (pages.pageCount < 2) return null
    return remember(pages, shift) {
        PageSwipe(
            onDrag = { dx ->
                val more = if (shift.value + dx < 0) pages.page < pages.pageCount - 1 else pages.page > 0
                // Hacia donde no hay página, un tercio de lo que se mueve el dedo.
                val step = if (more) dx else dx / 3
                scope.launch { shift.snapTo((shift.value + step).coerceIn(-w, w)) }
            },
            onEnd = {
                scope.launch {
                    val moved = shift.value
                    val target = when {
                        moved < -w * SWIPE_FRACTION && pages.page < pages.pageCount - 1 -> pages.page + 1
                        moved > w * SWIPE_FRACTION && pages.page > 0 -> pages.page - 1
                        else -> pages.page
                    }
                    if (target != pages.page) {
                        // La nueva entra desde el lado contrario al que se fue la de antes.
                        pages.goTo(target)
                        shift.snapTo(if (moved < 0) moved + w else moved - w)
                    }
                    shift.animateTo(0f, tween(PAGE_MS, easing = FastOutSlowInEasing))
                }
            },
        )
    }
}

/**
 * Los puntitos de las páginas (la que se ve, alargada), en el margen de abajo del tablero; tocar uno lleva a esa página.
 * Se ven chiquitos, pero cada uno se toca en una zona de [DOT_TOUCH_WIDTH]×[DOT_TOUCH_HEIGHT] (con el dedo, en el auto)
 * que baja desde su franja, nunca sube sobre los widgets.
 */
@Composable
internal fun PageDots(pages: DashboardState, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (i in 0 until pages.pageCount) Dot(i == pages.page)
        }
        // Las zonas de toque, sobre los puntitos y en el mismo orden (sin cambiar cómo se ven). Empiezan arriba de su franja
        // y crecen solo hacia ABAJO, fuera del tablero (la barra, dibujada encima, gana en sus botones): nunca tapan la
        // orilla de un widget.
        Row(
            Modifier
                .matchParentSize()
                .wrapContentSize(Alignment.TopCenter, unbounded = true)
                .requiredSize(DOT_TOUCH_WIDTH * pages.pageCount, DOT_TOUCH_HEIGHT),
        ) {
            for (i in 0 until pages.pageCount) {
                val label = stringResource(R.string.page_n, i + 1)
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(remember { MutableInteractionSource() }, indication = null) { pages.goTo(i) }
                        .semantics { contentDescription = label },
                )
            }
        }
    }
}

private val DOT_TOUCH_WIDTH = 40.dp

/** Un puntito de páginas: el de la que se ve, alargado. */
@Composable
private fun Dot(on: Boolean) {
    val c = LocalColors.current
    Box(
        Modifier
            .height(6.dp)
            .width(if (on) 18.dp else 6.dp)
            .clip(CircleShape)
            .background(if (on) c.text else c.textMuted.copy(alpha = 0.6f)),
    )
}
private val DOT_TOUCH_HEIGHT = 32.dp

/**
 * Al acomodar, junto al título: las páginas (tocar una la edita), «+» agrega una vacía y ✕ quita la que se ve (no la
 * primera, que es tu inicio de siempre; con confirmación).
 */
@Composable
internal fun PageBar(pages: DashboardState, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    val alerts = LocalAlerts.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        // Con una sola página, solo «+» (la «1» sola no dice nada).
        if (pages.pageCount > 1) for (i in 0 until pages.pageCount) {
            val on = i == pages.page
            val label = stringResource(R.string.page_n, i + 1)
            PageChip(if (on) c.accent else c.surfaceHigh, label, { pages.goTo(i) }) { Label("${i + 1}", Typography.subtitle, if (on) c.onPrimary else c.text) }
        }
        if (pages.pageCount < MAX_PAGES) PageChip(c.surfaceHigh, stringResource(R.string.page_add), { pages.addPage() }) { Icon(Icons.plus, c.text, 20.dp) }
        if (pages.page > 0) {
            val page = pages.page
            val alert = Alert(
                stringResource(R.string.page_remove_question, page + 1),
                stringResource(R.string.page_remove),
                stringResource(R.string.page_remove_message),
            ) { pages.removePage(page) }
            PageChip(c.surfaceHigh, stringResource(R.string.page_remove), { alerts.ask(alert) }) { Icon(Icons.close, c.danger, 18.dp) }
        }
    }
}

/** Lo que ocupa [PageBar] con [pages] (para saber si cabe la ayuda junto al título). */
internal fun pageBarWidth(pages: DashboardState): Dp {
    val chips = (if (pages.pageCount > 1) pages.pageCount else 0) + (if (pages.pageCount < MAX_PAGES) 1 else 0) + (if (pages.page > 0) 1 else 0)
    return CHIP * chips + 6.dp * (chips - 1).coerceAtLeast(0)
}

/** Un botón cuadrado de [PageBar]; [label] = lo que dice el lector de pantalla. */
@Composable
private fun PageChip(fill: Color, label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(CHIP).clip(RoundedCornerShape(Radius.inner)).background(fill).clickable(onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { content() }
}

private val CHIP = 40.dp

/**
 * Dónde vas en el mapa de fondo: el hueco libre más grande que dejan los widgets de la página, en fracciones de la
 * ventana ([root]), sabiendo dónde está el tablero en ella ([board]). null = no hay hueco (al centro de todo).
 */
internal fun mapFocus(pages: DashboardState, board: Rect?, root: Size): Rect? {
    val grid = pages.grid ?: return null
    if (board == null || root.width <= 0f || root.height <= 0f) return null
    val free = pages.shown.freeArea(grid) ?: return null
    val cw = board.width / grid.columns
    val ch = board.height / grid.rows
    return Rect(
        (board.left + free.col * cw) / root.width,
        (board.top + free.row * ch) / root.height,
        (board.left + (free.col + free.width) * cw) / root.width,
        (board.top + (free.row + free.height) * ch) / root.height,
    )
}

/**
 * El fondo de la página [page] (de fábrica, la que se ve; Ajustes → Inicio usa la primera): tu imagen (la de siempre, la
 * misma para las páginas que la usan) o el mapa (cómo te ves en él y el permiso de ubicación si falta).
 */
@Composable
internal fun PageBackgroundSettings(d: Dependencies, page: Int = d.dashboard.page) {
    val c = LocalColors.current
    val pages = d.dashboard
    val background = pages.backgrounds.getOrElse(page) { PageBackground.WALLPAPER }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Segmented(
            listOf(PageBackground.WALLPAPER to stringResource(R.string.page_background_image), PageBackground.MAP to stringResource(R.string.page_background_map)),
            background,
            { pages.setBackground(page, it) },
            check = true,
        )
        when (background) {
            PageBackground.WALLPAPER -> {
                Label(stringResource(R.string.wallpaper_help), Typography.body, c.textMuted)
                WallpaperSettings(d.prefs)
            }
            PageBackground.MAP -> {
                Label(stringResource(R.string.map_backdrop_help), Typography.body, c.textMuted)
                // Con un widget Mapa en la página se verían dos mapas: se avisa y se ofrece quitarlo (solo se sabe de la
                // que se ve; las otras avisan al acomodarlas → Fondo).
                if (page == pages.page && pages.shown.any { it.type == TileTypes.MAP }) {
                    Label(stringResource(R.string.map_backdrop_has_widget), Typography.small, c.warning)
                    // Se pierde su aspecto guardado y no hay deshacer: con confirmación.
                    val removeMap = stringResource(R.string.map_backdrop_remove_widget)
                    Confirmed(removeMap, removeMap, stringResource(R.string.remove)) { pages.changeShown(pages.shown.filterNot { it.type == TileTypes.MAP }) }
                }
                val (granted, ask) = rememberGpsPermission(d)
                if (!granted) Button(stringResource(R.string.tap_to_enable), ask, primary = true)
            }
        }
    }
}

/** Lo que tarda en acomodarse la página al soltar. */
private const val PAGE_MS = 260

/** Cuánto del ancho hay que deslizar para pasar de página. */
private const val SWIPE_FRACTION = 0.18f
