// Los Diseños en pantalla: el panel para elegir uno, su miniatura, la tarjeta de Ajustes y el aviso «Deshacer».
package com.irvingguerra.litedash

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.CardStyle
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.MIN_OPACITY
import com.irvingguerra.litedash.core.ui.Radius
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.core.ui.themeColors
import com.irvingguerra.litedash.feature.dashboard.DashboardState
import com.irvingguerra.litedash.feature.dashboard.Grid
import com.irvingguerra.litedash.feature.dashboard.MAX_PAGES
import com.irvingguerra.litedash.feature.dashboard.TileTypes
import com.irvingguerra.litedash.feature.dashboard.freeArea
import com.irvingguerra.litedash.feature.dashboard.mapBackground
import com.irvingguerra.litedash.feature.dashboard.tiles
import com.irvingguerra.litedash.feature.map.MapPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Diseños (Ajustes → Inicio y apariencia → Inicio → Diseño, o «Diseños» al acomodar): los 8 en tarjetas con su miniatura,
 * nombre y frase; un toque aplica uno a la página que se ve (sin preguntar: se puede deshacer). Abajo, «Deshacer» y
 * «Personalizar» (los ajustes de siempre). Acostada van 4 por fila; angosta o de pie, 2.
 */
@Composable
fun DesignsPanel(d: Dependencies, close: () -> Unit, onCustomize: () -> Unit) {
    val c = LocalColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val grid = d.dashboard.grid ?: return
    val prefs = d.prefs
    val current = designOf(prefs.design)
    val ownCar = !prefs.carModel.isNullOrEmpty()
    val undo = designUndo(d)
    PanelScaffold(stringResource(R.string.designs), close) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Label(stringResource(R.string.designs_help), Typography.body, c.textMuted)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val columns = if (maxWidth >= FOUR_COLUMNS) 4 else 2
                val gap = 16.dp
                val width = (maxWidth - gap * (columns - 1)) / columns
                FlowRow(horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap), maxItemsInEachRow = columns) {
                    for (design in DESIGNS) {
                        val applied = design == current
                        DesignCard(design, grid, d.dashboard, applied, changed = applied && !design.matches(prefs.look, ownCar), Modifier.width(width)) {
                            applyDesign(d, context, scope, design)
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        // Abajo, siempre a la vista: dónde se aplica (y, para conservar esta página, una nueva donde aplicarlo),
        // «Deshacer» y «Personalizar».
        Row(
            Modifier.fillMaxWidth().padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Label(stringResource(R.string.design_this_page), Typography.small, c.textMuted, maxLines = 2)
                if (prefs.designWallpaper != null) Label(stringResource(R.string.design_wallpaper_waiting), Typography.small, c.accent, maxLines = 2)
            }
            if (d.dashboard.pageCount < MAX_PAGES) Button(stringResource(R.string.design_new_page), { d.dashboard.addPage() })
            Button(stringResource(R.string.design_undo), { undoDesign(d, context, scope) }, enabled = undo != null)
            Button(stringResource(R.string.design_customize), onCustomize, primary = true)
        }
    }
    // Al abrir: si un fondo esperaba internet, se intenta otra vez (una).
    LaunchedEffect(Unit) { retryDesignWallpaper(context, prefs) }
}

/** Lo ancho desde el que caben 4 tarjetas por fila. */
private val FOUR_COLUMNS = 860.dp

/** Una tarjeta de la galería: miniatura, nombre (marcado si es el puesto), frase y «Aplicar». */
@Composable
private fun DesignCard(design: Design, grid: Grid, dashboard: DashboardState, applied: Boolean, changed: Boolean, modifier: Modifier, apply: () -> Unit) {
    val c = LocalColors.current
    val shape = RoundedCornerShape(Radius.card)
    Card(modifier.border(if (applied) 3.dp else 0.dp, if (applied) c.accent else Color.Transparent, shape), onClick = apply) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DesignThumbnail(design, grid, dashboard)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Label(stringResource(design.name), Typography.subtitle, maxLines = 1, modifier = Modifier.weight(1f))
                // Toda la tarjeta aplica; aquí se dice (o, si ya está puesto, la palomita).
                if (applied) Icon(Icons.done, c.accent, 22.dp)
                else Label(stringResource(R.string.design_apply).uppercase(currentLocale()), Typography.label, c.accent, maxLines = 1)
            }
            Label(
                if (changed) stringResource(R.string.design_with_changes) else stringResource(design.tagline),
                Typography.small, c.textMuted, maxLines = 1,
            )
        }
    }
}

/**
 * La miniatura de un Diseño, ligera (sin tableros vivos): su fondo en chico (la miniatura de litedash.dev, que se baja
 * una vez; sin internet, el fondo del tema), y encima su acomodo en esta pantalla como rectángulos con sus colores y el
 * ícono de cada widget. Ruta: el color del mapa de fondo con el ícono del mapa en el hueco.
 */
@Composable
internal fun DesignThumbnail(design: Design, grid: Grid, dashboard: DashboardState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // «Día y noche» se muestra de noche (como en su galería).
    val dark = design.theme != ThemeMode.LIGHT
    val colors = remember(design) { themeColors(dark, design.accent, design.cardColor) }
    val tiles = remember(design, grid) { design.template.tiles(grid, skip = dashboard.hidden) }
    val wallpaper = design.wallpaper
    val thumb by produceState<ImageBitmap?>(null, wallpaper) { value = wallpaper?.let { wallpaperThumb(context, it) } }
    val mapLand = remember(dark) { Color(MapPalette.factory(dark).land) }
    // Lo que se ve de la tarjeta: lisa entera; de vidrio, según «Cuánto se ven».
    val fill = if (design.cardStyle == CardStyle.SOLID) 1f else 0.55f + 0.4f * (design.cardOpacity - MIN_OPACITY) / (1f - MIN_OPACITY)
    BoxWithConstraints(
        modifier.fillMaxWidth().aspectRatio(grid.columns.toFloat() / grid.rows)
            .clip(RoundedCornerShape(Radius.inner))
            .background(if (design.template.mapBackground) mapLand else colors.background),
    ) {
        thumb?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        val cw = maxWidth / grid.columns
        val ch = maxHeight / grid.rows
        if (design.template.mapBackground) tiles.freeArea(grid)?.let { free ->
            Box(Modifier.offset(cw * free.col, ch * free.row).size(cw * free.width, ch * free.height), contentAlignment = Alignment.Center) {
                Icon(Icons.map, colors.accent.copy(alpha = 0.8f), minOf(cw * free.width, ch * free.height) * 0.4f)
            }
        }
        for (t in tiles) {
            val w = cw * t.width
            val h = ch * t.height
            val iconSize: Dp = minOf(w, h, 22.dp) * 0.7f
            Box(
                Modifier
                    .offset(cw * t.col, ch * t.row)
                    .size(w, h)
                    .padding(2.dp)
                    .clip(RoundedCornerShape(Radius.small))
                    .background(if (t.plain || t.type == TileTypes.CAR) Color.Transparent else colors.surface.copy(alpha = fill)),
                contentAlignment = Alignment.Center,
            ) {
                // Lo que va sin tarjeta (el auto, la hora o el velocímetro flotando) se ve más marcado; el velocímetro, del color de la interfaz.
                val tint = when {
                    t.type == TileTypes.SPEEDOMETER -> colors.accent
                    t.plain || t.type == TileTypes.CAR -> colors.text
                    else -> colors.textMuted
                }
                Icon(iconOf(t.type), tint, if (t.plain || t.type == TileTypes.CAR) iconSize * 1.6f else iconSize)
            }
        }
    }
}

/** Ajustes → Inicio y apariencia → Inicio, primera tarjeta: el Diseño puesto (miniatura y nombre) y «Cambiar». */
@Composable
internal fun DesignSettingsCard(d: Dependencies, onDesigns: () -> Unit) {
    val c = LocalColors.current
    val current = designOf(d.prefs.design)
    val grid = d.dashboard.grid
    SettingsCard(stringResource(R.string.design_title), stringResource(R.string.designs_help)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (current != null && grid != null) DesignThumbnail(current, grid, d.dashboard, Modifier.width(160.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Label(current?.let { stringResource(it.name) } ?: stringResource(R.string.design_none), Typography.subtitle, maxLines = 1)
                if (current != null && !current.matches(d.prefs.look, !d.prefs.carModel.isNullOrEmpty())) {
                    Label(stringResource(R.string.design_with_changes), Typography.small, c.textMuted, maxLines = 2)
                }
            }
            Button(stringResource(R.string.design_change), onDesigns, primary = true)
        }
    }
}

/** Cuánto dura a la vista el aviso «Diseño aplicado · Deshacer». */
private const val NOTICE_MS = 10_000L

/** Recién aplicado un Diseño: el aviso con «Deshacer» arriba del inicio, 10 s (con los demás avisos flotantes). */
@Composable
internal fun DesignAppliedNotice(d: Dependencies) {
    val design = DesignNotice.design ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(DesignNotice.shown) {
        delay(NOTICE_MS)
        DesignNotice.design = null
    }
    if (designUndo(d) == null) return
    Notice(stringResource(R.string.design_applied, stringResource(design.name)), Icons.done) {
        Button(stringResource(R.string.design_undo), { undoDesign(d, context, scope) }, primary = true)
    }
}

/** Uno a la vez baja el fondo que esperaba internet (al abrir Diseños y al volver la red no se pisan). */
private val retryLock = Mutex()

/** El fondo de un Diseño que esperaba internet: un intento (sin insistir); si llega, ya no espera. */
private suspend fun retryDesignWallpaper(context: Context, prefs: Preferences) = retryLock.withLock {
    val id = prefs.designWallpaper ?: return@withLock
    if (putLiteDashWallpaper(context, id) && prefs.designWallpaper == id) prefs.designWallpaper = null
}

/**
 * Mientras el fondo de un Diseño espera internet: cada vez que hay red (al abrir LiteDash con red, o cuando llega) se
 * intenta una vez. Sin fondo pendiente no escucha nada.
 */
@Composable
internal fun PendingDesignWallpaper(prefs: Preferences) {
    val pending = prefs.designWallpaper ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    DisposableEffect(pending) {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch { retryDesignWallpaper(context, prefs) }
            }
        }
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        val registered = connectivity != null && runCatching { connectivity.registerNetworkCallback(request, callback) }.isSuccess
        onDispose { if (registered) runCatching { connectivity?.unregisterNetworkCallback(callback) } }
    }
}
