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
    /** La app de Bluetooth del estéreo (o el emparejado): para conectar el celular sin buscarla. */
    val onBluetooth: () -> Unit,
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
    /** El mapa de LiteDash en grande: su botón va marcado (otro toque lo cierra). */
    mapOpen: Boolean = false,
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
    val item = @Composable { it: BarItem -> BarButton(it, sizes, vertical, musicOpen, mapOpen, actions) }
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
    val thickness = barThickness(prefs, vertical)
    val shell: Modifier = when {
        vertical -> Modifier.fillMaxHeight().width(thickness).padding(vertical = 10.dp)
        else -> Modifier.fillMaxWidth().height(thickness).padding(horizontal = 16.dp, vertical = 8.dp)
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
                EditableBar(prefs, items, vertical, favorites.isEmpty(), item, favoritesBlock, actions.onBarSettings)
            }
            return@Box
        }
        val split = items.indexOf(BarItem.FAVORITES)
        val start = if (split >= 0) items.take(split) else items.dropLast(1)
        val end = if (split >= 0) items.drop(split + 1) else listOf(BarItem.SETTINGS)
        // Los que no quepan se recortan; Ajustes (al final) nunca: sin él no habría cómo salir del inicio.
        val group = @Composable { list: List<BarItem> ->
            if (vertical) Column(Modifier.clipToBounds(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) { for (i in list) item(i) }
            else Row(Modifier.clipToBounds(), verticalAlignment = Alignment.CenterVertically) { for (i in list) item(i) }
        }
        Box(surface, contentAlignment = Alignment.Center) {
            DockLayout(
                vertical = vertical,
                wrap = floating,
                start = { group(start) },
                center = {
                    // Caben las que quepan: en pantallas chicas no se encima.
                    BoxWithConstraints(contentAlignment = Alignment.Center) {
                        if (split >= 0) favoritesBlock(
                            if (vertical) (maxHeight / (sizes.button + 4.dp)).toInt() else (maxWidth / (sizes.box + 8.dp)).toInt(),
                        )
                    }
                },
                end = { group(end) },
            )
        }
    }
}

/** Lo que ocupa la barra: su ancho al lado ([vertical]) o su alto abajo. Oculta, el inicio lo descuenta de la cuadrícula. */
@Composable
fun barThickness(prefs: Preferences, vertical: Boolean): Dp {
    val short = isShortScreen()
    val small = isSmallScreen() && !short
    return (if (vertical) (if (small) 76.dp else 88.dp) else if (small) 96.dp else if (short) 64.dp else 84.dp) * prefs.barSize.scale
}

/** Medidas de la barra: botón, ícono de app y caja de cada favorita (abajo) o del volumen (al lado). */
private data class BarSizes(val button: Dp, val icon: Dp, val box: Dp = button, val volume: Dp = button)

/** Un botón de la barra. Las favoritas no pasan por aquí: van en su propio bloque. */
@Composable
private fun BarButton(item: BarItem, sizes: BarSizes, vertical: Boolean, musicOpen: Boolean, mapOpen: Boolean, actions: BarActions) {
    val c = LocalColors.current
    when (item) {
        BarItem.APPS -> IconButton(Icons.apps, actions.onApps, size = sizes.button)
        // La pantalla Música (como en Tesla); marcado mientras está abierta.
        BarItem.MUSIC -> IconButton(Icons.music, actions.onMusic, size = sizes.button, background = if (musicOpen) c.surfaceHigh else Color.Transparent)
        BarItem.VOLUME -> Volume(if (vertical) sizes.volume else sizes.button, vertical = vertical)
        BarItem.NAVIGATE -> IconButton(Icons.navigate, actions.onNavigate, size = sizes.button, background = if (mapOpen) c.surfaceHigh else Color.Transparent)
        BarItem.BLUETOOTH -> IconButton(Icons.bluetooth, actions.onBluetooth, size = sizes.button)
        BarItem.PHONE -> IconButton(Icons.phone, actions.onPhone, size = sizes.button)
        BarItem.VOICE -> actions.onVoice?.let { IconButton(Icons.mic, it, size = sizes.button) }
        // Siempre: sin Ajustes no habría cómo salir del inicio.
        BarItem.SETTINGS -> IconButton(Icons.settings, actions.onSettings, size = sizes.button)
        BarItem.FAVORITES -> Unit
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
    noFavorites: Boolean,
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
        prefs.barItems = reorderBar(prefs.barItems, movable, i, (centers[i] ?: 0f) + offset) { centers[it] ?: 0f }
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
                if (i == BarItem.FAVORITES) FavoritesEdit(empty = noFavorites, vertical, favoritesBlock) else item(i)
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

/** Las favoritas al acomodar: el bloque entero se mueve; sin ninguna ([empty]), el ícono de apps en su lugar. */
@Composable
private fun FavoritesEdit(empty: Boolean, vertical: Boolean, favoritesBlock: @Composable (Int?) -> Unit) {
    val c = LocalColors.current
    // Un contorno fino (no una caja de color): se nota que es un bloque que se mueve entero.
    Box(
        Modifier.border(1.dp, c.line, RoundedCornerShape(Radius.inner)).padding(horizontal = if (vertical) 2.dp else 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (empty) Icon(Icons.apps, c.textMuted, 24.dp)
        // Al acomodar caben menos (los ✕ y "Barra" ocupan lugar): las primeras tres.
        else favoritesBlock(if (vertical) 2 else 3)
    }
}

/**
 * La barra [stored] (la guardada) con [moved] soltado en [at] (su centro en la pantalla, por el eje de la barra).
 * Queda antes del primero de [shown] (los que se ven y se mueven) cuyo centro ([center]) está en [at] o después; si
 * no hay, antes de Ajustes. Lo que no se ve (la voz sin asistente) conserva su lugar.
 */
internal fun reorderBar(stored: List<BarItem>, shown: List<BarItem>, moved: BarItem, at: Float, center: (BarItem) -> Float): List<BarItem> {
    val next = (shown - moved).firstOrNull { center(it) >= at } ?: BarItem.SETTINGS
    val all = stored - moved
    return all.toMutableList().apply { add(all.indexOf(next).coerceAtLeast(0), moved) }
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
 * La barra en tres partes: [start] al principio (izquierda o arriba), [end] al final y [center] (tus favoritas) en
 * medio. [end] se mide primero (lleva Ajustes: nunca se queda sin lugar) y [start] con lo que sobre. Abajo, el centro
 * va al centro de TODA la barra, no del hueco entre los dos (como Tesla), con lo que dejan libre los lados por igual; con
 * [wrap] (flotante) mide solo lo que ocupa y la píldora queda al centro. A un lado ([vertical]), el centro llena el
 * hueco entre los dos.
 */
@Composable
private fun DockLayout(vertical: Boolean, wrap: Boolean, start: @Composable () -> Unit, center: @Composable () -> Unit, end: @Composable () -> Unit) {
    Layout(contents = listOf(start, center, end), modifier = (if (wrap) Modifier.fillMaxHeight() else Modifier.fillMaxSize()).clipToBounds()) { (s, m, e), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        if (vertical) {
            val max = constraints.maxHeight
            val width = constraints.maxWidth
            val gap = 4.dp.roundToPx()
            val endPlaced = e.first().measure(loose)
            val startPlaced = s.first().measure(loose.copy(maxHeight = (max - endPlaced.height - gap).coerceAtLeast(0)))
            val room = (max - startPlaced.height - endPlaced.height - 2 * gap).coerceAtLeast(0)
            val centerPlaced = m.first().measure(loose.copy(minHeight = room, maxHeight = room))
            return@Layout layout(width, max) {
                startPlaced.place((width - startPlaced.width) / 2, 0)
                centerPlaced.place((width - centerPlaced.width) / 2, startPlaced.height + gap)
                endPlaced.place((width - endPlaced.width) / 2, max - endPlaced.height)
            }
        }
        val max = constraints.maxWidth
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
