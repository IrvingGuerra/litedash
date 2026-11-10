package com.irvingguerra.litedash

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Radius
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.feature.apps.App
import com.irvingguerra.litedash.feature.apps.AppIcon
import com.irvingguerra.litedash.feature.apps.AppRepository
import com.irvingguerra.litedash.feature.dashboard.longPress

/** Lo que hacen los botones de la barra (los arma el inicio). */
class BarActions(
    val onApps: () -> Unit,
    val onMusic: () -> Unit,
    val onNavigate: () -> Unit,
    val onPhone: () -> Unit,
    /** El asistente de voz de la pantalla; null si no trae (el botón no aparece ni se ofrece). */
    val onVoice: (() -> Unit)?,
    val onSettings: () -> Unit,
    /** Al acomodar: el panel de la barra (agregar botones y apps, posición, tamaño, estilo). */
    val onBarSettings: () -> Unit,
    /** Dejar presionada la barra entra a acomodar (como los widgets). */
    val onEdit: () -> Unit,
)

/**
 * La barra de apps: abajo ([vertical] = false) o a un lado. Lleva los botones de [Preferences.barItems] en su
 * orden: los de antes de tus favoritas a un lado y los de después al otro, con las favoritas al centro.
 * Abajo es flotante (una píldora al centro) o a lo ancho, según [Preferences.barStyle]. Al acomodar
 * ([editing]) cada botón lleva ✕ y se arrastra para cambiar su lugar; Ajustes no se mueve ni se quita.
 */
@Composable
fun AppBar(
    repo: AppRepository,
    prefs: Preferences,
    favorites: List<App>,
    vertical: Boolean,
    editing: Boolean,
    musicOpen: Boolean,
    actions: BarActions,
) {
    val c = LocalColors.current
    val k = prefs.barSize.scale
    val short = isShortScreen()
    val small = isSmallScreen() && !short
    val sizes = if (vertical) BarSizes(button = (if (small) 52.dp else 56.dp) * k, icon = (if (small) 40.dp else 44.dp) * k, volume = (if (small) 40.dp else 44.dp) * k)
    else BarSizes(
        button = (if (small) 64.dp else if (short) 46.dp else 56.dp) * k,
        icon = (if (small) 56.dp else if (short) 36.dp else 44.dp) * k,
        box = (if (small) 68.dp else if (short) 46.dp else 56.dp) * k,
    )
    val items = prefs.barItems.filter { it != BarItem.VOICE || actions.onVoice != null }
    val item = @Composable { it: BarItem -> BarButton(it, sizes, vertical, musicOpen, actions) }
    val favoritesBlock = @Composable { maxApps: Int? ->
        val shown = if (maxApps != null) favorites.take(maxApps) else favorites
        if (vertical) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (app in shown) AppShortcut(repo, app, sizes.button, sizes.icon)
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            for (app in shown) AppShortcut(repo, app, sizes.box, sizes.icon)
        }
    }
    val floating = !vertical && prefs.barStyle == BarStyle.FLOATING
    // Fuera de acomodar, dejarla presionada entra a acomodar (sin quitarle los toques a sus botones).
    val press = if (editing) Modifier else Modifier.longPress(actions.onEdit)
    val shell: Modifier = when {
        vertical -> Modifier.fillMaxHeight().width((if (small) 76.dp else 88.dp) * k).padding(vertical = 10.dp)
        else -> Modifier.fillMaxWidth().height((if (small) 96.dp else if (short) 64.dp else 84.dp) * k).padding(horizontal = 16.dp, vertical = 8.dp)
    }
    Box(shell, contentAlignment = Alignment.Center) {
        val shape = RoundedCornerShape(if (floating) 40.dp else Radius.card)
        // Al acomodar, el borde va por fuera de la orilla (no adentro del relleno) y deja aire a los lados.
        val surface = Modifier
            .then(if (vertical || floating) Modifier.fillMaxHeight() else Modifier.fillMaxSize())
            .clip(shape)
            .background(if (vertical) Color.Transparent else c.surface)
            .then(if (editing) Modifier.border(1.5.dp, c.accent, shape) else press)
            .padding(horizontal = if (vertical) 0.dp else if (floating || editing) 18.dp else 8.dp)
        if (editing) {
            Box(surface, contentAlignment = Alignment.Center) {
                EditableBar(prefs, items, vertical, item, favoritesBlock, actions.onBarSettings)
            }
            return@Box
        }
        val split = items.indexOf(BarItem.FAVORITES)
        val start = if (split >= 0) items.take(split) else items.dropLast(1)
        val end = if (split >= 0) items.drop(split + 1) else listOf(BarItem.SETTINGS)
        if (vertical) Column(surface, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (i in start) item(i)
            // Las favoritas que quepan entre los botones de arriba y los de abajo.
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds(), contentAlignment = Alignment.Center) {
                if (split >= 0) favoritesBlock((maxHeight / (sizes.button + 4.dp)).toInt())
            }
            for (i in end) item(i)
        } else Box(surface, contentAlignment = Alignment.Center) {
            DockLayout(
                wrap = floating,
                start = { Row(verticalAlignment = Alignment.CenterVertically) { for (i in start) item(i) } },
                center = {
                    // Caben las que quepan: en pantallas chicas no se encima.
                    BoxWithConstraints(contentAlignment = Alignment.Center) {
                        if (split >= 0) favoritesBlock((maxWidth / (sizes.box + 8.dp)).toInt())
                    }
                },
                end = { Row(verticalAlignment = Alignment.CenterVertically) { for (i in end) item(i) } },
            )
        }
    }
}

/** Medidas de la barra: botón, ícono de app y caja de cada favorita (abajo) o del volumen (al lado). */
private data class BarSizes(val button: Dp, val icon: Dp, val box: Dp = button, val volume: Dp = button)

/** Un botón de la barra. Las favoritas no pasan por aquí: van en su propio bloque. */
@Composable
private fun BarButton(item: BarItem, sizes: BarSizes, vertical: Boolean, musicOpen: Boolean, actions: BarActions) {
    val c = LocalColors.current
    when (item) {
        BarItem.CLOCK -> BarClock(vertical)
        BarItem.APPS -> IconButton(Icons.apps, actions.onApps, size = sizes.button)
        // La pantalla Música (como en Tesla); marcado mientras está abierta.
        BarItem.MUSIC -> IconButton(Icons.music, actions.onMusic, size = sizes.button, background = if (musicOpen) c.surfaceHigh else Color.Transparent)
        BarItem.VOLUME -> Volume(if (vertical) sizes.volume else sizes.button, vertical = vertical)
        BarItem.NAVIGATE -> IconButton(Icons.navigate, actions.onNavigate, size = sizes.button)
        BarItem.PHONE -> IconButton(Icons.phone, actions.onPhone, size = sizes.button)
        BarItem.VOICE -> actions.onVoice?.let { IconButton(Icons.mic, it, size = sizes.button) }
        // Siempre: sin Ajustes no habría cómo salir del inicio.
        BarItem.SETTINGS -> IconButton(Icons.settings, actions.onSettings, size = sizes.button)
        BarItem.FAVORITES -> Unit
    }
}

/** La hora en la barra con los íconos de estado: a un lado, con la a.m./p.m. en chico; abajo, en una línea. */
@Composable
private fun BarClock(vertical: Boolean) {
    val c = LocalColors.current
    val clock = rememberClock()
    if (!vertical) return Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Label(clock.full, Typography.subtitle, maxLines = 1)
        StatusIcons()
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            Label(clock.time, Typography.subtitle, maxLines = 1)
            if (clock.marker != null) Label(clock.marker, Typography.small.copy(fontSize = 11.sp), c.textMuted, maxLines = 1, modifier = Modifier.padding(start = 2.dp, bottom = 2.dp))
        }
        Box(Modifier.padding(bottom = 6.dp)) { StatusIcons() }
    }
}

/**
 * La barra al acomodar: sus botones con ✕; se dejan presionados un momento y se arrastran para cambiar su
 * orden (caen donde se sueltan). Si no caben, se deslizan. Ajustes y "Barra" van fijos al final: siempre se
 * alcanzan, aunque haya muchos botones o la pantalla sea chica. Se trabaja sobre la lista guardada: lo que
 * no se ve aquí (la voz sin asistente) no se pierde al quitar o mover otro.
 */
@Composable
private fun EditableBar(
    prefs: Preferences,
    items: List<BarItem>,
    vertical: Boolean,
    item: @Composable (BarItem) -> Unit,
    favoritesBlock: @Composable (Int?) -> Unit,
    onBarSettings: () -> Unit,
) {
    val movable = items.filter { it != BarItem.SETTINGS }
    // Dónde está el centro de cada uno (en la pantalla) y cuánto se ha arrastrado el que se mueve.
    val centers = remember { mutableStateMapOf<BarItem, Float>() }
    var dragging by remember { mutableStateOf<BarItem?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    fun drop(i: BarItem) {
        val at = (centers[i] ?: 0f) + offset
        val others = movable - i
        // El que queda justo después en la barra; en la lista guardada va antes de él (o antes de Ajustes).
        val next = others.firstOrNull { (centers[it] ?: 0f) >= at } ?: BarItem.SETTINGS
        val all = prefs.barItems - i
        prefs.barItems = all.toMutableList().apply { add(all.indexOf(next).coerceAtLeast(0), i) }
    }
    val scroll = rememberScrollState()
    val list = @Composable {
        for (i in movable) {
            Box(
                Modifier
                    .onGloballyPositioned { centers[i] = it.boundsInRoot().center.let { p -> if (vertical) p.y else p.x } }
                    .zIndex(if (dragging == i) 1f else 0f)
                    .graphicsLayer { if (dragging == i) { if (vertical) translationY = offset else translationX = offset } }
                    .pointerInput(i, movable) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { dragging = i; offset = 0f },
                            onDrag = { change, d ->
                                change.consume()
                                offset += if (vertical) d.y else d.x
                            },
                            onDragEnd = {
                                drop(i)
                                dragging = null
                            },
                            onDragCancel = { dragging = null },
                        )
                    }
                    .padding(top = 6.dp, start = 6.dp),
            ) {
                if (i == BarItem.FAVORITES) FavoritesEdit(prefs, vertical, favoritesBlock) else item(i)
                RemoveBadge(Modifier.align(Alignment.TopStart).offset(x = (-6).dp, y = (-6).dp)) { prefs.barItems = prefs.barItems - i }
            }
        }
    }
    if (vertical) Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(scroll), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) { list() }
        item(BarItem.SETTINGS)
        EditChip(stringResource(R.string.bar_edit), vertical = true, onBarSettings)
    } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.weight(1f, fill = false).horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { list() }
        item(BarItem.SETTINGS)
        EditChip(stringResource(R.string.bar_edit), vertical = false, onBarSettings)
    }
}

/** Las favoritas al acomodar: el bloque entero se mueve; si está vacío, dice cómo agregarlas. */
@Composable
private fun FavoritesEdit(prefs: Preferences, vertical: Boolean, favoritesBlock: @Composable (Int?) -> Unit) {
    val c = LocalColors.current
    // Un contorno fino (no una caja de color): se nota que es un bloque que se mueve entero.
    Box(
        Modifier.border(1.dp, c.line, RoundedCornerShape(Radius.inner)).padding(horizontal = if (vertical) 2.dp else 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (prefs.dock?.isEmpty() == true) Icon(Icons.apps, c.textMuted, 24.dp)
        // Al acomodar caben menos (los ✕ y "Barra" ocupan lugar): las primeras tres.
        else favoritesBlock(if (vertical) 2 else 3)
    }
}

/** El ✕ en la esquina de un botón al acomodar la barra, por fuera del ícono para no taparlo. */
@Composable
private fun RemoveBadge(modifier: Modifier, onClick: () -> Unit) {
    val c = LocalColors.current
    Box(
        modifier.zIndex(1f).size(22.dp).clip(CircleShape).background(c.text).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.close, c.background, 13.dp) }
}

/** "Barra" al acomodar: una píldora chica del color de la interfaz; en la lateral (angosta), solo un +. */
@Composable
private fun EditChip(text: String, vertical: Boolean, onClick: () -> Unit) {
    val c = LocalColors.current
    Box(
        Modifier.padding(start = if (vertical) 0.dp else 6.dp).clip(RoundedCornerShape(50)).background(c.accent.copy(alpha = 0.18f)).clickable(onClick = onClick)
            .padding(horizontal = if (vertical) 10.dp else 14.dp, vertical = if (vertical) 10.dp else 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (vertical) Icon(Icons.plus, c.accent, 20.dp)
        else Label(text, Typography.subtitle.copy(fontSize = 14.sp), c.accent, maxLines = 1)
    }
}

/**
 * La barra de abajo en tres partes: [start] pegado a la izquierda, [end] a la derecha y [center] (tus
 * favoritas) al centro de TODA la barra, no del hueco entre los dos (como Tesla). El centro tiene lo que dejan
 * libre los lados por igual. [end] se mide primero (lleva Ajustes: nunca se queda sin lugar) y [start] con
 * lo que sobre. Con [wrap] (flotante) mide solo lo que ocupa, y la píldora queda al centro.
 */
@Composable
private fun DockLayout(wrap: Boolean, start: @Composable () -> Unit, center: @Composable () -> Unit, end: @Composable () -> Unit) {
    Layout(contents = listOf(start, center, end), modifier = (if (wrap) Modifier.fillMaxHeight() else Modifier.fillMaxSize()).clipToBounds()) { (s, m, e), constraints ->
        val max = constraints.maxWidth
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val endPlaced = e.first().measure(loose)
        val startPlaced = s.first().measure(loose.copy(maxWidth = (max - endPlaced.width).coerceAtLeast(0)))
        val gap = 16.dp.roundToPx()
        val sideWidth = maxOf(startPlaced.width, endPlaced.width) + gap
        val centerPlaced = m.first().measure(loose.copy(maxWidth = (max - 2 * sideWidth).coerceAtLeast(0)))
        // Flotante: lo que ocupa; sin favoritas en medio, sin el hueco de emparejar los lados.
        val width = when {
            !wrap -> max
            centerPlaced.width == 0 -> minOf(max, startPlaced.width + gap + endPlaced.width)
            else -> minOf(max, 2 * sideWidth + centerPlaced.width)
        }
        val height = constraints.maxHeight
        layout(width, height) {
            startPlaced.place(0, (height - startPlaced.height) / 2)
            endPlaced.place(width - endPlaced.width, (height - endPlaced.height) / 2)
            centerPlaced.place((width - centerPlaced.width) / 2, (height - centerPlaced.height) / 2)
        }
    }
}

/** Una app favorita de la barra: su ícono, un toque la abre. */
@Composable
private fun AppShortcut(repo: AppRepository, app: App, box: Dp, icon: Dp) {
    val context = LocalContext.current
    Box(Modifier.size(box).clip(RoundedCornerShape(Radius.inner)).clickable { repo.open(context, app) }, contentAlignment = Alignment.Center) {
        AppIcon(repo, app, icon)
    }
}
