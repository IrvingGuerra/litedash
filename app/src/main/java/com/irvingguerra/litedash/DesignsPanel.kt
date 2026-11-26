// Los Diseños en pantalla: el panel para elegir uno, su miniatura, la tarjeta de Ajustes y el aviso «Deshacer».
package com.irvingguerra.litedash

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Radius
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.feature.dashboard.DashboardState
import com.irvingguerra.litedash.feature.dashboard.Grid
import com.irvingguerra.litedash.feature.dashboard.MAX_PAGES
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Diseños (Ajustes → Inicio y apariencia → Inicio → Diseño, o «Diseños» al acomodar): los 8 en tarjetas con su miniatura,
 * nombre y frase; un toque pregunta si se aplica a la página que se ve y, al confirmar, lo aplica (se puede deshacer).
 * Abajo, «Deshacer» y «Personalizar» (los ajustes de siempre). Acostada van 4 por fila; angosta o de pie, 2.
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
    val alerts = LocalAlerts.current
    val res = LocalResources.current
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
                            alerts.ask(
                                Alert(
                                    res.getString(R.string.design_apply_title, res.getString(design.name)),
                                    res.getString(R.string.design_apply),
                                    res.getString(R.string.design_apply_message),
                                    destructive = false,
                                ) { applyDesign(d, context, scope, design, dark = c.dark) },
                            )
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

/**
 * Una tarjeta de la galería: miniatura, nombre (con la palomita si es el puesto), sus colores y su frase. [brief] (la
 * bienvenida): solo miniatura, nombre y la palomita.
 */
@Composable
internal fun DesignCard(
    design: Design,
    grid: Grid,
    dashboard: DashboardState,
    applied: Boolean,
    changed: Boolean,
    modifier: Modifier,
    brief: Boolean = false,
    apply: () -> Unit,
) {
    val c = LocalColors.current
    val shape = RoundedCornerShape(Radius.card)
    Card(modifier.border(if (applied) 3.dp else 0.dp, if (applied) c.accent else Color.Transparent, shape), onClick = apply) {
        Column(Modifier.padding(if (brief) BRIEF_PADDING else 12.dp), verticalArrangement = Arrangement.spacedBy(if (brief) 6.dp else 8.dp)) {
            DesignThumbnail(design, grid, dashboard)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Label(stringResource(design.name), Typography.subtitle, maxLines = 1, modifier = Modifier.weight(1f, fill = !brief))
                // Sus colores de un vistazo: fondo, tarjetas, realce y auto.
                if (!brief) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (color in remember(design) { DesignColors.of(design).swatches }) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(color).border(1.dp, c.text.copy(alpha = 0.2f), CircleShape))
                    }
                }
                if (applied) Icon(Icons.done, c.accent, if (brief) 18.dp else 22.dp)
            }
            if (!brief) Label(
                if (changed) stringResource(R.string.design_with_changes) else stringResource(design.tagline),
                Typography.small, c.textMuted, maxLines = 1,
            )
        }
    }
}

/** El margen de una tarjeta [DesignCard] breve (la bienvenida mide con él cuántas caben). */
internal val BRIEF_PADDING = 8.dp

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

/** Intentos por fondo pendiente en esta sesión (cada red que llega cuenta uno): después ya no se insiste. */
private const val RETRY_MAX = 5
private var retried: Pair<String, Int>? = null

/**
 * El fondo de un Diseño que esperaba internet: un intento (sin insistir). Si llega, o si ya no existe en litedash.dev
 * (un 4xx), ya no espera. Se revisa que siga pendiente ya con el candado del fondo: si mientras tanto elegiste otro
 * fondo u otro Diseño, no se baja.
 */
private suspend fun retryDesignWallpaper(context: Context, prefs: Preferences) = retryLock.withLock {
    val id = prefs.designWallpaper ?: return@withLock
    val tries = retried?.takeIf { it.first == id }?.second ?: 0
    if (tries >= RETRY_MAX) return@withLock
    retried = id to tries + 1
    val got = putLiteDashWallpaper(context, id, byDesign = true) { prefs.designWallpaper == id }
    if ((got == WallpaperFetch.DONE || got == WallpaperFetch.GONE) && prefs.designWallpaper == id) prefs.designWallpaper = null
}

/**
 * Mientras el fondo de un Diseño espera internet: cada vez que hay red (al abrir LiteDash con red, o cuando llega) se
 * intenta una vez, hasta [RETRY_MAX] por sesión. Sin fondo pendiente no escucha nada. Va fuera de lo que tapan Ajustes y
 * los paneles (si no, con un panel abierto no se enteraba de la red).
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
