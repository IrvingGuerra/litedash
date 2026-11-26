package com.irvingguerra.litedash

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.TextInput
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.core.ui.blockTouches
import com.irvingguerra.litedash.feature.apps.App
import com.irvingguerra.litedash.feature.apps.AppGrid
import com.irvingguerra.litedash.feature.dashboard.TileTypes
import com.irvingguerra.litedash.feature.obd.ObdState

/**
 * Paneles a pantalla completa en pantallas muy bajas (8.8" de 1280×480): todo se dibuja más chico para que quepa,
 * sin tocar el tamaño del inicio. Con más de [below] de alto no cambia nada.
 */
@Composable
internal fun FitShort(below: Dp = SHORT_PANEL_HEIGHT, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val d = LocalDensity.current
        // El teclado no cuenta (donde sí se le aparta, el alto baja): si contara, en un celular acostado al abrirlo todo se achicaría.
        val keyboard = with(d) { WindowInsets.ime.getBottom(this).toDp() }
        val fit = ((maxHeight + keyboard) / below).coerceIn(MIN_PANEL_FIT, 1f)
        // Siempre por el mismo camino: si cambiara de rama, lo de adentro se rehace y un buscador perdería el foco (y el
        // teclado se cerraría al instante).
        CompositionLocalProvider(LocalDensity provides if (fit >= 1f) d else Density(d.density * fit, d.fontScale)) { content() }
    }
}

/** Por debajo de esta altura los paneles se achican (Ajustes trae título, pestañas y una tarjeta). */
private val SHORT_PANEL_HEIGHT = 400.dp

/** Lo que crece desde un widget (música, radio, mapa) vive arriba de la barra: se achica solo si queda muy bajo. */
internal val SHORT_EXPANDED_HEIGHT = 300.dp

/** Nunca más chico que esto: los textos deben seguir leyéndose. */
private const val MIN_PANEL_FIT = 0.72f

/**
 * Panel a pantalla completa sobre el inicio, con título y botón para cerrar. [header] va centrado entre los dos (el
 * buscador de apps: en pantallas bajas un renglón propio le quitaba media fila a la lista, #28).
 */
@Composable
internal fun PanelScaffold(
    title: String,
    close: () -> Unit,
    header: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalColors.current
    Column(
        Modifier
            .fillMaxSize()
            .canvas()
            // Atrapa los toques para que no lleguen al inicio de abajo.
            .blockTouches()
            .safeDrawingPadding()
            .padding(horizontal = 24.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.CenterVertically) {
            // Sin nada en medio, ahí va la pastilla de la guía (si navegas).
            val guide = LocalGuidePill.current
            if (header == null && guide == null) Label(title, Typography.title, maxLines = 1, modifier = Modifier.weight(1f))
            else if (header == null) {
                Label(title, Typography.title, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                Box(Modifier.weight(1f).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) { guide?.invoke() }
            } else {
                Label(title, Typography.title, maxLines = 1)
                Box(Modifier.weight(1f).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) { header() }
            }
            IconButton(Icons.close, close, background = c.surfaceHigh)
        }
        content()
    }
}

@Composable
fun DrawerPanel(d: Dependencies, apps: List<App>, close: () -> Unit) {
    val c = LocalColors.current
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<App?>(null) }
    var showHidden by remember { mutableStateOf(false) }
    // «Elegir»: el toque marca la app en vez de abrirla (lo mismo que dejarla presionada, que falla en pantallas de auto).
    var choosing by remember { mutableStateOf(false) }
    var noRoom by remember { mutableStateOf(false) }
    // Sin favoritas propias se ven las sugeridas: agregar o quitar parte de ellas (no de cero).
    val dock = rememberFavorites(d, apps).map { it.key }
    val hidden = d.prefs.hidden
    val visible = remember(apps, hidden, showHidden) { if (showHidden) apps else apps.filter { it.key !in hidden } }
    PanelScaffold(stringResource(R.string.apps), close, header = { PanelSearch(query) { query = it } }) {
        val app = selected
        if (noRoom) Label(stringResource(R.string.no_room), Typography.body, c.danger, modifier = Modifier.padding(start = 8.dp))
        if (app == null) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Label(stringResource(R.string.drawer_help), Typography.small, c.textMuted, modifier = Modifier.weight(1f))
                Label(
                    stringResource(if (choosing) R.string.done else R.string.source_choose),
                    Typography.small, c.accent,
                    modifier = Modifier.clickable { choosing = !choosing }.padding(8.dp),
                )
                if (hidden.isNotEmpty()) {
                    Label(
                        stringResource(if (showHidden) R.string.hide_hidden else R.string.show_hidden, hidden.size),
                        Typography.small, c.accent,
                        modifier = Modifier.clickable { showHidden = !showHidden }.padding(8.dp),
                    )
                }
            }
        } else {
            // Acciones de la app que se dejó presionada (o se tocó con «Elegir»).
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Label(app.name, Typography.subtitle, maxLines = 1, modifier = Modifier.padding(top = 12.dp, end = 8.dp))
                val inDock = app.key in dock
                Button(stringResource(if (inDock) R.string.unpin_from_dock else R.string.pin_to_dock), {
                    d.prefs.dock = if (inDock) dock - app.key else dock + app.key
                    // Sin el bloque de favoritas en la barra, la app no se vería en ningún lado.
                    if (!inDock && BarItem.FAVORITES !in d.prefs.barItems) d.prefs.barItems = BarItem.add(d.prefs.barItems, BarItem.FAVORITES)
                    selected = null
                })
                Button(stringResource(R.string.add_to_home), {
                    noRoom = !d.dashboard.addTile(TileTypes.APP, app.key)
                    selected = null
                })
                val isHidden = app.key in hidden
                Button(stringResource(if (isHidden) R.string.show_app else R.string.hide_app), {
                    d.prefs.hidden = if (isHidden) hidden - app.key else hidden + app.key
                    selected = null
                })
                Button(stringResource(R.string.app_info), {
                    openSystemScreen(context, appSettingsIntent(context, Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app.pkg))
                    selected = null
                })
                IconButton(Icons.close, { selected = null }, size = 48.dp, background = c.surfaceHigh)
            }
        }
        AppGrid(
            d.apps, visible, query,
            onClick = { if (choosing) selected = it else { d.apps.open(context, it); close() } },
            onLongPress = { selected = it },
            marked = setOfNotNull(selected?.key),
            modifier = Modifier.weight(1f).padding(top = 8.dp),
        )
    }
}

@Composable
fun PickAppPanel(d: Dependencies, apps: List<App>, tileId: String?, close: () -> Unit) {
    val dashboard = d.dashboard
    var query by remember { mutableStateOf("") }
    var noRoom by remember { mutableStateOf(false) }
    PanelScaffold(stringResource(R.string.pick_app), close, header = { PanelSearch(query) { query = it } }) {
        if (noRoom) Label(stringResource(R.string.no_room), Typography.body, LocalColors.current.danger, modifier = Modifier.padding(start = 8.dp))
        AppGrid(
            d.apps, apps, query,
            onClick = { app ->
                val tile = tileId?.let(dashboard::tile)
                if (tile?.type == TileTypes.PHONE) {
                    // Teléfono: tu app de llamadas para toda la app (también la barra y el volante).
                    d.prefs.phoneApp = app.key
                    close()
                } else if (tileId != null) {
                    // La app nueva, sin perder las opciones del widget.
                    dashboard.setData(tileId, withAppKey(tile?.data ?: "", app.key))
                    close()
                } else if (dashboard.addTile(TileTypes.APP, app.key)) {
                    close()
                } else {
                    noRoom = true
                }
            },
            modifier = Modifier.weight(1f).padding(top = 8.dp),
        )
    }
}

/**
 * Qué dato del auto muestra un medidor: los que lee el OBD, con su ícono. Los que
 * este auto no da (ya conectado) se ven apagados y avisan.
 */
@Composable
fun PickGaugePanel(d: Dependencies, tileId: String?, close: () -> Unit) {
    val c = LocalColors.current
    val obd = d.obdIfCreated()?.takeIf { it.state == ObdState.ON }
    var noRoom by remember { mutableStateOf(false) }
    PanelScaffold(stringResource(R.string.pick_gauge), close) {
        Label(stringResource(R.string.pick_gauge_help), Typography.body, c.textMuted, modifier = Modifier.padding(bottom = 14.dp))
        if (noRoom) Label(stringResource(R.string.no_room), Typography.body, c.danger, modifier = Modifier.padding(bottom = 12.dp))
        CardGrid(GaugeSpecs, minWidth = 200.dp, height = 120.dp, onClick = { spec ->
            if (tileId != null) {
                // Cambia el dato y se queda con su estilo.
                val old = d.dashboard.tile(tileId)?.data ?: ""
                d.dashboard.setData(tileId, GaugeLook.parse(old).encode(spec.signal))
                close()
            } else if (d.dashboard.addTile(TileTypes.GAUGE, spec.signal)) close()
            else noRoom = true
        }) { spec ->
            val readable = obd?.canRead(spec.signal) ?: true
            Icon(spec.icon, if (readable) c.accent else c.textMuted, 28.dp)
            Label(stringResource(spec.name), Typography.subtitle, if (readable) c.text else c.textMuted, maxLines = 1)
            if (!readable) Label(stringResource(R.string.gauge_unsupported), Typography.small, c.textMuted, maxLines = 1)
        }
    }
}

/**
 * Tarjetas en cuadrícula, todas a la vista: tantas columnas como quepan (de [minWidth]
 * o más, mínimo dos) y, si aun así no alcanza el alto (pantallas muy chicas), se desplaza.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ColumnScope.CardGrid(
    items: List<T>,
    minWidth: Dp,
    height: Dp,
    onClick: (T) -> Unit,
    content: @Composable ColumnScope.(T) -> Unit,
) {
    ScrollingColumns(minWidth, 14.dp) { width ->
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            for (item in items) {
                Card(Modifier.width(width).height(height), onClick = { onClick(item) }) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content(item) }
                }
            }
        }
    }
}

/**
 * Lo que queda del panel, con scroll si no cabe; [content] recibe el ancho de cada tarjeta para que quepan
 * tantas columnas de [minWidth] o más como se pueda ([columnWidth]), separadas por [gap].
 */
@Composable
private fun ColumnScope.ScrollingColumns(minWidth: Dp, gap: Dp, content: @Composable (width: Dp) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        content(columnWidth(maxWidth, minWidth, gap))
    }
}

/** Los grupos de "Agregar al inicio", en orden, con sus widgets. */
private enum class AddGroup(val title: Int, val types: List<String>) {
    CAR(R.string.add_group_car, listOf(TileTypes.CAR, TileTypes.ROAD, TileTypes.IMAGE, TileTypes.SPEEDOMETER, TileTypes.GAUGE, TileTypes.TIRES, TileTypes.WORKSHOP, TileTypes.TRIP)),
    MEDIA(R.string.add_group_media, listOf(TileTypes.MUSIC, TileTypes.RADIO)),
    ROAD(R.string.add_group_nav, listOf(TileTypes.MAP, TileTypes.PROJECTION, TileTypes.PHONE)),
    INFO(R.string.add_group_info, listOf(TileTypes.CLOCK, TileTypes.WEATHER)),
    APPS(R.string.add_group_apps, listOf(TileTypes.APP)),
}

/**
 * Agregar al inicio: los widgets por grupos (tu auto, música, navegación…), cada uno con su ícono, su
 * nombre y qué hace. Los del OBD apagado no se ofrecen; Llantas sí: agregarla prende su módulo (o lleva a
 * elegir los sensores).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddTilePanel(d: Dependencies, close: () -> Unit, onPickApp: () -> Unit, onPickGauge: () -> Unit, onTiresSettings: () -> Unit) {
    val c = LocalColors.current
    var noRoom by remember { mutableStateOf(false) }
    fun add(type: String) {
        when (type) {
            TileTypes.APP -> onPickApp()
            TileTypes.GAUGE -> onPickGauge()
            TileTypes.TIRES -> {
                var picking = false
                if (d.addTiresTile { picking = true }) { if (picking) onTiresSettings() else close() } else noRoom = true
            }
            else -> if (d.dashboard.addTile(type)) close() else noRoom = true
        }
    }
    PanelScaffold(stringResource(R.string.add_tile), close) {
        if (noRoom) Label(stringResource(R.string.no_room), Typography.body, c.danger, modifier = Modifier.padding(bottom = 12.dp))
        val gap = 12.dp
        ScrollingColumns(240.dp, gap) { width ->
            Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                for (group in AddGroup.entries) {
                    val specs = group.types.mapNotNull { t -> TileSpecs.firstOrNull { it.type == t } }.filter { offered(d, it.type) }
                    if (specs.isEmpty()) continue
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Label(stringResource(group.title).uppercase(currentLocale()), Typography.label, c.textMuted, modifier = Modifier.padding(start = 4.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap)) {
                            for (spec in specs) AddCard(spec, Modifier.width(width)) { add(spec.type) }
                        }
                    }
                }
            }
        }
    }
}

/** Un widget para agregar: su ícono en un círculo del color de la interfaz, su nombre y qué hace. */
@Composable
private fun AddCard(spec: TileSpec, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalColors.current
    Card(modifier.height(if (isSmallScreen()) 96.dp else 104.dp), onClick = onClick) {
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(c.accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Icon(iconOf(spec.type), c.accent, 24.dp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Label(stringResource(spec.name), Typography.subtitle, maxLines = 1)
                // Tres renglones: caben en la tarjeta y en pantallas de pie o idiomas largos dos no alcanzaban (Radio).
                spec.detail?.let { Label(stringResource(it), Typography.small, c.textMuted, maxLines = 3) }
            }
        }
    }
}

/**
 * El ancho de cada tarjeta para que quepan tantas columnas de [min] o más (dos como mínimo) en [total].
 * Medio punto menos: con la interfaz escalada, el redondeo a píxeles dejaba fuera la última.
 */
internal fun columnWidth(total: Dp, min: Dp, gap: Dp): Dp {
    val columns = ((total + gap) / (min + gap)).toInt().coerceAtLeast(2)
    return (total - gap * (columns - 1)) / columns - 0.5.dp
}

internal fun iconOf(type: String) = when (type) {
    TileTypes.CAR -> Icons.car
    TileTypes.ROAD -> Icons.road
    TileTypes.IMAGE -> Icons.image
    TileTypes.MUSIC -> Icons.music
    TileTypes.RADIO -> Icons.radio
    TileTypes.MAP -> Icons.map
    TileTypes.CLOCK -> Icons.clock
    TileTypes.PHONE -> Icons.phone
    TileTypes.WEATHER -> Icons.sun
    TileTypes.TRIP -> Icons.location
    TileTypes.SPEEDOMETER -> Icons.speed
    TileTypes.GAUGE -> Icons.tachometer
    TileTypes.WORKSHOP -> Icons.engine
    TileTypes.PROJECTION -> Icons.phoneCar
    TileTypes.TIRES -> Icons.tire
    else -> Icons.apps
}

/** Buscador de apps en la cabecera de un panel: píldora baja y hasta 420 dp, centrada entre el título y la ✕. */
@Composable
private fun PanelSearch(query: String, onChange: (String) -> Unit) {
    TextInput(query, onChange, stringResource(R.string.search_app), Modifier.widthIn(max = 420.dp).fillMaxWidth(), icon = true, compact = true)
}
