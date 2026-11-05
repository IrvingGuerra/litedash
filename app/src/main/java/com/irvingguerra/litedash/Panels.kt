package com.irvingguerra.litedash

import com.irvingguerra.litedash.feature.apps.AppIcon
import com.irvingguerra.litedash.feature.apps.rememberApps
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import android.net.Uri
import com.irvingguerra.litedash.feature.obd.ObdState
import com.irvingguerra.litedash.core.ui.Switch
import com.irvingguerra.litedash.core.ui.SwitchRow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import com.irvingguerra.litedash.core.ui.Segmented
import com.irvingguerra.litedash.core.ui.Radius
import android.app.Activity
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.hardware.SensorManager
import android.hardware.Sensor
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.TextInput
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.feature.apps.App
import com.irvingguerra.litedash.feature.apps.AppGrid
import com.irvingguerra.litedash.feature.apps.normalize
import com.irvingguerra.litedash.feature.car.CAR_ASPECT
import com.irvingguerra.litedash.feature.car.CarDrawing
import com.irvingguerra.litedash.feature.car.CarPackage
import com.irvingguerra.litedash.feature.car.CarRepository
import com.irvingguerra.litedash.feature.car.CarStill
import com.irvingguerra.litedash.feature.car.CarTurntable
import com.irvingguerra.litedash.feature.car.CatalogEntry
import com.irvingguerra.litedash.feature.dashboard.DashboardState
import com.irvingguerra.litedash.feature.dashboard.Grid
import com.irvingguerra.litedash.feature.dashboard.ScreenSize
import com.irvingguerra.litedash.feature.dashboard.Template
import com.irvingguerra.litedash.feature.dashboard.hasCar
import com.irvingguerra.litedash.feature.dashboard.TileTypes
import com.irvingguerra.litedash.feature.dashboard.size
import com.irvingguerra.litedash.feature.dashboard.suggestedTemplate
import com.irvingguerra.litedash.feature.dashboard.tiles
import com.irvingguerra.litedash.feature.music.MusicRepository
import com.irvingguerra.litedash.feature.tpms.PressureUnit
import com.irvingguerra.litedash.feature.updater.Updater
import com.irvingguerra.litedash.feature.updater.Version
import kotlin.math.round
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Panel a pantalla completa sobre el inicio, con título y botón para cerrar. */
@Composable
internal fun PanelScaffold(
    title: String,
    close: () -> Unit,
    actions: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalColors.current
    Column(
        Modifier
            .fillMaxSize()
            .background(c.background)
            // Atrapa los toques para que no lleguen al inicio de abajo.
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .safeDrawingPadding()
            .padding(horizontal = 24.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.CenterVertically) {
            Label(title, Typography.title, maxLines = 1, modifier = Modifier.weight(1f))
            actions()
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
    var noRoom by remember { mutableStateOf(false) }
    // Sin dock propio se ven las sugeridas: fijar parte de ellas (no de cero).
    val dock = d.prefs.dock ?: remember(apps) { dockFavorites(d, apps).map { it.key } }
    val hidden = d.prefs.hidden
    val visible = remember(apps, hidden, showHidden) { if (showHidden) apps else apps.filter { it.key !in hidden } }
    PanelScaffold(stringResource(R.string.apps), close) {
        TextInput(query, { query = it }, stringResource(R.string.search_app), Modifier.fillMaxWidth().widthIn(max = 640.dp), icon = true)
        val app = selected
        if (noRoom) Label(stringResource(R.string.no_room), Typography.body, c.danger, modifier = Modifier.padding(top = 10.dp, start = 8.dp))
        if (app == null) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp, start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Label(stringResource(R.string.drawer_help), Typography.small, c.textMuted, modifier = Modifier.weight(1f))
                if (hidden.isNotEmpty()) {
                    Label(
                        stringResource(if (showHidden) R.string.hide_hidden else R.string.show_hidden, hidden.size),
                        Typography.small, c.accent,
                        modifier = Modifier.clickable { showHidden = !showHidden }.padding(8.dp),
                    )
                }
            }
        } else {
            // Acciones de la app que se dejó presionada.
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Label(app.name, Typography.subtitle, maxLines = 1, modifier = Modifier.padding(top = 12.dp, end = 8.dp))
                val inDock = app.key in dock
                Button(stringResource(if (inDock) R.string.unpin_from_dock else R.string.pin_to_dock), {
                    d.prefs.dock = if (inDock) dock - app.key else dock + app.key
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
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${app.pkg}".toUri())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                    selected = null
                })
                IconButton(Icons.close, { selected = null }, size = 48.dp, background = c.surfaceHigh)
            }
        }
        AppGrid(
            d.apps, visible, query,
            onClick = { d.apps.open(context, it); close() },
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
    PanelScaffold(stringResource(R.string.pick_app), close) {
        TextInput(query, { query = it }, stringResource(R.string.search_app), Modifier.fillMaxWidth().widthIn(max = 640.dp), icon = true)
        if (noRoom) Label(stringResource(R.string.no_room), Typography.body, LocalColors.current.danger, modifier = Modifier.padding(top = 10.dp, start = 8.dp))
        AppGrid(
            d.apps, apps, query,
            onClick = { app ->
                if (tileId != null) {
                    dashboard.setData(tileId, app.key)
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
                val old = d.dashboard.tiles.firstOrNull { it.id == tileId }?.data ?: ""
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
    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        val gap = 14.dp
        val columns = ((maxWidth + gap) / (minWidth + gap)).toInt().coerceAtLeast(2)
        val width = (maxWidth - gap * (columns - 1)) / columns
        FlowRow(horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap)) {
            for (item in items) {
                Card(Modifier.width(width).height(height), onClick = { onClick(item) }) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content(item) }
                }
            }
        }
    }
}

/**
 * Ajustes → Tu auto, en tarjetas: el auto (nombre y color), las opciones del modelo
 * elegido justo debajo (sin bajar toda la lista), los modelos descargados a la vista
 * y el catálogo con buscador (no todos de golpe). Las descargas viven en el
 * repositorio: siguen aunque se cierre Ajustes.
 */
@Composable
private fun CarSettings(d: Dependencies) {
    val prefs = d.prefs
    val cars = d.cars
    val c = LocalColors.current
    val pkg = cars.pkg(prefs.carModel)
    val installed = cars.installed
    var catalog by remember { mutableStateOf<List<CatalogEntry>?>(null) }
    var unreachable by remember { mutableStateOf(false) }
    LaunchedEffect(cars.hasCatalog) {
        if (cars.hasCatalog) runCatching { cars.catalog() }.onSuccess { catalog = it }.onFailure { unreachable = true }
    }
    val tab = tabs(listOf(R.string.tab_my_car, R.string.tab_models, R.string.reminders), "car")

    if (tab == 0) SettingsCard {
        Box(Modifier.fillMaxWidth().widthIn(max = 520.dp).aspectRatio(1.8f)) {
            if (pkg != null) {
                // Arriba el auto; abajo queda lugar para su sombra y su reflejo en el piso.
                CarTurntable(cars, pkg, prefs.carColor, Modifier.fillMaxWidth().fillMaxHeight(0.72f), prefs.carAngle, { prefs.carAngle = it })
            } else {
                CarDrawing(prefs.carColor, Modifier.fillMaxWidth().align(Alignment.Center))
            }
        }
        Label(stringResource(R.string.car_home_help), Typography.small, c.textMuted)
        Label(stringResource(R.string.name), Typography.label, c.textMuted)
        TextInput(prefs.carName ?: "", { prefs.carName = it.take(24).ifBlank { null } }, pkg?.name ?: stringResource(R.string.my_car), Modifier.fillMaxWidth())
        Label(stringResource(R.string.color), Typography.label, c.textMuted)
        ColorPicker(prefs.carColor, prefs::previewCarColor) { prefs.carColor = it }
    }
    if (tab == 0 && pkg != null) {
        SettingsCard("${pkg.make} ${pkg.name} ${pkg.year}") {
            ModelOptions(cars, pkg, catalog?.firstOrNull { it.id == pkg.id }, onDelete = { prefs.carModel = null; cars.delete(pkg) })
        }
    }
    if (tab == 1 && (cars.hasCatalog || installed.isNotEmpty())) {
        SettingsCard(stringResource(R.string.your_models)) { DownloadedModels(prefs, cars, catalog) }
    }
    if (tab == 1) {
        if (cars.hasCatalog) {
            SettingsCard(stringResource(R.string.catalog)) {
                CatalogSearch(prefs, cars, catalog?.filter { e -> installed.none { it.id == e.id } })
                if (unreachable || cars.failed) Label(stringResource(R.string.catalog_error), Typography.small, c.danger)
            }
        } else if (installed.isEmpty()) {
            SettingsCard { Label(stringResource(R.string.car_coming_soon), Typography.small, c.textMuted) }
        }
    }
    if (tab == 2) SettingsCard(stringResource(R.string.reminders), stringResource(R.string.reminders_help)) {
        ReminderSettings(d.reminders)
    }
}

/** El modelo elegido: modelo 3D nuevo, calidad del giro, eliminarlo y su crédito. */
@Composable
private fun ModelOptions(cars: CarRepository, current: CarPackage, entry: CatalogEntry?, onDelete: () -> Unit) {
    val c = LocalColors.current
    val context = LocalContext.current
    if (entry != null) CarUpdateNotice(cars, current, entry)
    // Calidad del giro: subir necesita el catálogo; bajar (liberar espacio) no.
    val high = entry?.high
    if (high != null || current.high) {
        Label(stringResource(R.string.spin_quality), Typography.label, c.textMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val mb = high?.let { "%.1f MB".format(it.bytes / 1e6f) }
            val low = stringResource(R.string.spin_quality_low)
            val hi = stringResource(R.string.spin_quality_high)
            Button(if (current.high && mb != null) "$low · −$mb" else low, { if (current.high) cars.lowerQuality(current) }, primary = !current.high)
            if (entry != null && high != null) {
                Button(if (!current.high) "$hi · +$mb" else hi, { if (!current.high) cars.raiseQuality(current, entry) }, primary = current.high)
            }
        }
        val progress = cars.downloading?.takeIf { it.first == CarRepository.HIGH }?.second
        if (progress != null) Label(stringResource(R.string.downloading, (progress * 100).toInt()), Typography.small, c.accent)
        else Label(stringResource(R.string.spin_quality_help), Typography.small, c.textMuted)
    }
    // Eliminar lo descargado: pide confirmar con un segundo toque.
    val mb by produceState(0f, current) { value = cars.sizeOnDisk(current) / 1e6f }
    Confirmed(stringResource(R.string.delete_car, mb), stringResource(R.string.delete_car_question), action = onDelete)
    // Crédito del modelo 3D (lo pide su licencia).
    if (current.author.isNotBlank()) {
        Label(
            stringResource(R.string.model_credit, current.author, current.license),
            Typography.small,
            c.textMuted,
            modifier = Modifier.clickable {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, current.source.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            },
        )
    }
}

/** Los descargados a la vista (en tu color), el sedán dibujado, y actualizar o eliminar todos. */
@Composable
private fun DownloadedModels(prefs: Preferences, cars: CarRepository, catalog: List<CatalogEntry>?) {
    val c = LocalColors.current
    val installed = cars.installed
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ModelCard(stringResource(R.string.model_generic), selected = prefs.carModel == null, onClick = { prefs.carModel = null }) { m ->
            CarDrawing(prefs.carColor, m)
        }
        for (p in installed) {
            ModelCard("${p.make} ${p.name}", selected = prefs.carModel == p.id, onClick = { prefs.carModel = p.id }) { m ->
                CarStill(cars, p, prefs.carColor, m)
            }
        }
    }
    if (installed.isEmpty()) {
        Label(stringResource(R.string.no_models), Typography.small, c.textMuted)
        return
    }
    // Todos a la vez: los que tienen modelo 3D nuevo, o quitar todo lo descargado.
    val outdated = catalog.orEmpty().mapNotNull { e -> installed.firstOrNull { cars.isOutdated(it, e) }?.let { it to e } }
    val progress = cars.downloading?.takeIf { it.first == CarRepository.ALL }?.second
    if (progress != null) {
        Label(stringResource(R.string.downloading, (progress * 100).toInt()), Typography.small, c.accent)
    } else if (outdated.isNotEmpty()) {
        Confirmed(
            stringResource(R.string.update_all, outdated.size, outdated.sumOf { (p, e) -> cars.updateSize(p, e) } / 1e6f),
            pluralStringResource(R.plurals.update_all_confirm, outdated.size, outdated.size),
            primary = true,
        ) { cars.updateAll(outdated) }
    }
    Confirmed(stringResource(R.string.delete_all), pluralStringResource(R.plurals.delete_all_confirm, installed.size, installed.size)) {
        prefs.carModel = null
        cars.deleteAll()
    }
}

/** El catálogo con buscador: aparecen al escribir (o con "Ver todos"), no toda la lista de golpe. */
@Composable
private fun CatalogSearch(prefs: Preferences, cars: CarRepository, available: List<CatalogEntry>?) {
    val c = LocalColors.current
    var query by remember { mutableStateOf("") }
    var showAll by remember { mutableStateOf(false) }
    var previewing by remember { mutableStateOf<String?>(null) }
    // Busca por marca, modelo o año, sin importar acentos ni mayúsculas.
    TextInput(query, { query = it }, stringResource(R.string.search_car), Modifier.fillMaxWidth(), icon = true)
    val list = available ?: return Label(stringResource(R.string.checking), Typography.small, c.textMuted)
    val q = normalize(query.trim())
    val found = if (q.isEmpty() && !showAll) emptyList() else list.filter { e -> q.split(' ').all { it in normalize("${e.make} ${e.name} ${e.year}") } }
    when {
        list.isEmpty() -> Label(stringResource(R.string.catalog_all_downloaded), Typography.small, c.textMuted)
        q.isEmpty() && !showAll -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Label(stringResource(R.string.catalog_hint), Typography.small, c.textMuted, modifier = Modifier.weight(1f))
            Button(stringResource(R.string.catalog_show_all, list.size), { showAll = true })
        }
        found.isEmpty() -> Label(stringResource(R.string.catalog_none), Typography.small, c.textMuted)
    }
    val open = found.firstOrNull { it.id == previewing }
    if (open != null) {
        val progress = cars.downloading?.takeIf { it.first == open.id }?.second
        // Al terminar se elige, aunque ya se haya cerrado Ajustes.
        CarPreview(open, cars, progress, onDownload = { cars.install(open) { prefs.carModel = open.id } }, onClose = { previewing = null })
    }
    // Tocar un auto solo lo muestra en grande; se baja con el botón (ver no cuesta datos).
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (e in found.filter { it.id != previewing }) {
            ModelCard("${e.make} ${e.name}", detail = "${e.year} · %.1f MB".format(e.bytes / 1e6f), selected = false, onClick = { previewing = e.id }) { m ->
                var img by remember(e.id) { mutableStateOf<ImageBitmap?>(null) }
                LaunchedEffect(e.id) { img = cars.thumbnail(e) }
                img?.let { Image(it, null, m, contentScale = ContentScale.Fit) }
            }
        }
    }
}

/** Un auto como tarjeta: su imagen arriba y el nombre abajo; marcada si es el elegido. */
@Composable
private fun ModelCard(title: String, selected: Boolean, onClick: () -> Unit, detail: String? = null, picture: @Composable (Modifier) -> Unit) {
    val c = LocalColors.current
    val shape = RoundedCornerShape(Radius.card)
    Column(
        Modifier
            .width(176.dp)
            .clip(shape)
            .background(c.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) c.accent else c.line, shape)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(CAR_ASPECT), contentAlignment = Alignment.Center) { picture(Modifier.fillMaxSize()) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(title, Typography.small, maxLines = 1, modifier = Modifier.weight(1f))
            if (selected) Icon(Icons.done, c.accent, 18.dp)
        }
        if (detail != null) Label(detail, Typography.small, c.accent, maxLines = 1)
    }
}

/** Un botón que pide confirmar con un segundo toque antes de hacer algo que no se deshace solo. */
@Composable
private fun Confirmed(label: String, question: String, primary: Boolean = false, action: () -> Unit) {
    var asking by remember(label) { mutableStateOf(false) }
    if (!asking) return Button(label, { asking = true }, primary = primary)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Label(question, Typography.small, LocalColors.current.textMuted, modifier = Modifier.weight(1f, fill = false))
        Button(stringResource(R.string.yes), { asking = false; action() }, primary = true)
        Button(stringResource(R.string.cancel), { asking = false })
    }
}

/** Un auto del catálogo abierto en la lista: su vista previa en grande, de quién es y el botón para bajarlo. */
@Composable
private fun CarPreview(e: CatalogEntry, cars: CarRepository, progress: Float?, onDownload: () -> Unit, onClose: () -> Unit) {
    val c = LocalColors.current
    val shape = RoundedCornerShape(Radius.card)
    var img by remember(e.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(e.id) { img = cars.thumbnail(e) }
    Column(
        Modifier.fillMaxWidth().clip(shape).background(c.surface).border(2.dp, c.accent, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(2.2f), contentAlignment = Alignment.Center) {
            img?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        }
        Label("${e.make} ${e.name} ${e.year}", Typography.subtitle, maxLines = 1)
        if (e.author.isNotBlank()) Label(stringResource(R.string.model_credit, e.author, e.license), Typography.small, c.textMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (progress != null) {
                Button("${(progress * 100).toInt()} %", {}, primary = true)
            } else {
                Button(stringResource(R.string.download_mb, e.bytes / 1e6f), onDownload, primary = true)
                Button(stringResource(R.string.cancel), onClose)
            }
        }
    }
}

/** Secciones de Ajustes en el orden del menú: cada cosa vive en un solo lugar. */
enum class SettingsSection(val title: Int) {
    CAR(R.string.your_car), APPEARANCE(R.string.appearance), APPS(R.string.apps_section), WORKSHOP(R.string.workshop),
    TIRES(R.string.tires), WHEEL(R.string.wheel), MODULES(R.string.modules), SYSTEM(R.string.system),
}

/**
 * Las secciones de un módulo apagado no salen en el menú (Taller sin OBD, Llantas sin fuente);
 * la que ya está abierta sí (al prender Llantas se entra a elegir su fuente).
 */
private fun SettingsSection.shown(d: Dependencies, current: SettingsSection) = this == current || when (this) {
    SettingsSection.WORKSHOP -> d.obdModule
    SettingsSection.TIRES -> d.hasTpmsSource
    else -> true
}

private fun SettingsSection.icon() = when (this) {
    SettingsSection.APPEARANCE -> Icons.dashboard
    SettingsSection.CAR -> Icons.car
    SettingsSection.APPS -> Icons.apps
    SettingsSection.WHEEL -> Icons.wheel
    SettingsSection.WORKSHOP -> Icons.engine
    SettingsSection.TIRES -> Icons.tire
    SettingsSection.MODULES -> Icons.settings
    SettingsSection.SYSTEM -> Icons.info
}

/**
 * Ajustes como en las pantallas de auto: menú de secciones a la izquierda y
 * tarjetas a la derecha. En pantallas angostas el menú queda solo con íconos.
 */
@Composable
fun SettingsPanel(
    d: Dependencies,
    revision: Int,
    close: () -> Unit,
    onEdit: () -> Unit,
    onTemplates: () -> Unit,
    start: SettingsSection = SettingsSection.CAR,
    /** Las llantas a pantalla completa (desde la tarjeta de Salud). */
    onTires: () -> Unit = {},
) {
    val c = LocalColors.current
    var section by rememberSaveable { mutableStateOf(start) }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(c.background)
            // Atrapa los toques para que no lleguen al inicio de abajo.
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .safeDrawingPadding(),
    ) {
        val narrow = maxWidth < 720.dp
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier.width(if (narrow) 88.dp else 270.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MenuEntry(Icons.back, stringResource(R.string.back_home), selected = false, narrow, close)
                Spacer(Modifier.height(10.dp))
                for (s in SettingsSection.entries) if (s.shown(d, section)) MenuEntry(s.icon(), stringResource(s.title), s == section, narrow) { section = s }
            }
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 8.dp, end = 24.dp, top = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Label(stringResource(section.title), Typography.title.copy(fontSize = 30.sp), maxLines = 1)
                when (section) {
                    SettingsSection.APPEARANCE -> Appearance(d, revision, onEdit, onTemplates)
                    SettingsSection.CAR -> CarSettings(d)
                    SettingsSection.APPS -> AppsSettings(d)
                    SettingsSection.WHEEL -> {
                        SettingsCard(help = stringResource(R.string.wheel_help)) { WheelSettings(d.wheel, d.prefs) }
                        SettingsCard(stringResource(R.string.wheel_assigned)) { WheelAssigned(d.prefs) }
                    }
                    // Con el OBD apagado el escáner ni se crea.
                    SettingsSection.WORKSHOP -> if (d.obdModule) Workshop(d, onTires)
                    SettingsSection.TIRES -> TiresSettings(d)
                    SettingsSection.MODULES -> Modules(d, revision, open = { section = it })
                    SettingsSection.SYSTEM -> SystemSettings(d)
                }
            }
        }
    }
}

/**
 * Pestañas de una sección con muchas partes (para no hacer tanto scroll). Devuelve la
 * elegida; se recuerda por sección ([key]) mientras LiteDash siga abierto (solo en memoria).
 */
@Composable
internal fun tabs(titles: List<Int>, key: String): Int {
    val selected = TabMemory[key] ?: 0
    // Mismo control que las opciones (estilo iOS): una pestaña a la vez.
    Segmented(titles.mapIndexed { i, t -> i to stringResource(t) }, selected, { TabMemory[key] = it }, Modifier.horizontalScroll(rememberScrollState()))
    return selected
}

/** Las pestañas de Ajustes → Apps (Música 0, Radio 1…). */
private const val APPS_TABS = "apps"

/** Las pestañas de Ajustes → Sistema ([SystemTab]). */
private const val SYSTEM_TABS = "system"

/** Ajustes → Sistema abre en Actualizaciones (desde el aviso de versión nueva). */
internal fun openUpdates() = selectTab(SYSTEM_TABS, SystemTab.UPDATES.ordinal)

/**
 * La pestaña elegida en cada sección (al cambiar de sección, o cerrar y abrir Ajustes, sigue ahí).
 * Solo en memoria: no se guarda, así que sus claves pueden cambiar sin migrar nada.
 */
private val TabMemory = mutableStateMapOf<String, Int>()

/** Cambia a otra pestaña de la sección [key] desde su contenido (p. ej. de Salud a Códigos). */
internal fun selectTab(key: String, index: Int) {
    TabMemory[key] = index
}

@Composable
private fun MenuEntry(icon: ImageVector, text: String, selected: Boolean, narrow: Boolean, onClick: () -> Unit) {
    val c = LocalColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(Radius.card))
            .background(if (selected) c.surfaceHigh else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (narrow) Arrangement.Center else Arrangement.Start,
    ) {
        Icon(icon, if (selected) c.accent else c.textMuted, 26.dp)
        if (!narrow) Label(text, Typography.subtitle, if (selected) c.text else c.textMuted, maxLines = 1, modifier = Modifier.padding(start = 16.dp))
    }
}

/** Tarjeta de Ajustes: título, controles y el ícono de la sección grande y tenue a la derecha. */
@Composable
internal fun SettingsCard(
    title: String? = null,
    help: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalColors.current
    Card(Modifier.fillMaxWidth().widthIn(max = 860.dp)) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (title != null) Label(title, Typography.subtitle, maxLines = 1)
            content()
            if (help != null) Label(help, Typography.small, c.textMuted)
        }
    }
}

/** Los temas a elegir, en Inicio y apariencia y en la bienvenida: oscuro, claro, día y noche y el del sistema. */
internal val ThemeOptions = listOf(
    ThemeMode.DARK to R.string.theme_dark,
    ThemeMode.LIGHT to R.string.theme_light,
    ThemeMode.SUN to R.string.theme_sun,
    ThemeMode.SYSTEM to R.string.theme_system,
)

/**
 * Pestañas de Inicio y apariencia: primero lo del inicio (acomodo y widgets), luego lo general, día y
 * noche (tema, cuándo es de noche, brillo), tus imágenes y unidades.
 */
private enum class AppearanceTab(val title: Int) {
    LAYOUT(R.string.tab_layout), WIDGETS(R.string.tab_tiles), GENERAL(R.string.tab_general), DAY_NIGHT(R.string.tab_day_night), IMAGES(R.string.tab_images), UNITS(R.string.units_title),
}

@Composable
private fun Appearance(d: Dependencies, revision: Int, onEdit: () -> Unit, onTemplates: () -> Unit) {
    val prefs = d.prefs
    val c = LocalColors.current
    val context = LocalContext.current
    when (AppearanceTab.entries[tabs(AppearanceTab.entries.map { it.title }, "appearance")]) {
        AppearanceTab.LAYOUT -> HomeLayout(d, onEdit, onTemplates)
        AppearanceTab.WIDGETS -> Tiles(d, onEdit)
        AppearanceTab.GENERAL -> {
            SettingsCard(stringResource(R.string.your_name), stringResource(R.string.your_name_help)) {
                TextInput(prefs.driverName ?: "", { prefs.driverName = it.take(24).ifBlank { null } }, stringResource(R.string.your_name_hint), Modifier.fillMaxWidth())
            }
            SettingsCard(stringResource(R.string.language)) {
                val activity = context as? Activity
                Segmented(
                    listOf("" to R.string.language_system, "es" to R.string.language_es, "en" to R.string.language_en).map { (code, text) -> code to stringResource(text) },
                    prefs.language,
                    { code ->
                        if (prefs.language != code) {
                            prefs.language = code
                            activity?.recreate()
                        }
                    },
                )
            }
            SettingsCard(stringResource(R.string.scale), stringResource(R.string.scale_help)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconButton(Icons.minus, { prefs.scale = (prefs.scale - 0.1f).coerceAtLeast(0.8f).roundToTenth() }, background = c.surfaceHigh)
                    Label("${(prefs.scale * 100).toInt()} %", Typography.title, modifier = Modifier.widthIn(min = 90.dp))
                    IconButton(Icons.plus, { prefs.scale = (prefs.scale + 0.1f).coerceAtMost(1.5f).roundToTenth() }, background = c.surfaceHigh)
                }
            }
            // Sin título: el interruptor ya dice qué es.
            SettingsCard {
                SwitchRow(stringResource(R.string.hide_status_bar), prefs.hideStatusBar, { prefs.hideStatusBar = it }, stringResource(R.string.hide_status_bar_help))
            }
            SettingsCard { WeatherSwitch(d.weather) }
        }
        AppearanceTab.DAY_NIGHT -> {
            SettingsCard(stringResource(R.string.theme)) {
                Segmented(
                    ThemeOptions.map { (mode, text) -> mode to stringResource(text) },
                    prefs.theme,
                    { prefs.theme = it },
                )
            }
            SettingsCard(stringResource(R.string.night_source), stringResource(R.string.night_source_help)) {
                val hasSensor = remember { context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_LIGHT) != null }
                val options = listOfNotNull(
                    NightSource.SUN to R.string.night_sun,
                    (NightSource.SENSOR to R.string.night_sensor).takeIf { hasSensor },
                    NightSource.LIGHTS to R.string.night_lights,
                )
                Segmented(options.map { (s, text) -> s to stringResource(text) }, prefs.nightSource, { prefs.nightSource = it })
                if (!hasSensor) Label(stringResource(R.string.night_no_sensor), Typography.small, c.textMuted)
                if (prefs.nightSource == NightSource.LIGHTS) {
                    Label(stringResource(R.string.lights_help), Typography.small, c.textMuted)
                    LightsSettings(prefs)
                }
            }
            SettingsCard(stringResource(R.string.brightness), stringResource(R.string.brightness_help)) {
                BrightnessSettings(prefs, revision)
            }
        }
        AppearanceTab.IMAGES -> {
            SettingsCard(stringResource(R.string.wallpaper_title), stringResource(R.string.wallpaper_help)) {
                WallpaperSettings()
            }
            // Sin título: el interruptor ya dice qué es.
            SettingsCard { IntroSettings(prefs) }
        }
        AppearanceTab.UNITS -> SettingsCard { UnitsSettings(d) }
    }
}

/** Unidades: velocidad y distancia, temperatura y (con llantas) presión. Automático = según idioma y región. */
@Composable
private fun UnitsSettings(d: Dependencies) {
    val c = LocalColors.current
    val prefs = d.prefs
    val auto = imperialUnits(prefs.language)
    Label(stringResource(R.string.units_speed), Typography.label, c.textMuted)
    Segmented(
        listOf(
            SpeedUnits.AUTO to stringResource(R.string.units_auto),
            SpeedUnits.METRIC to stringResource(R.string.units_metric),
            SpeedUnits.IMPERIAL to stringResource(R.string.units_imperial),
        ),
        prefs.speedUnits,
        { prefs.speedUnits = it },
    )
    Label(stringResource(R.string.units_temp), Typography.label, c.textMuted)
    Segmented(
        listOf(
            TempUnits.AUTO to stringResource(R.string.units_auto),
            TempUnits.CELSIUS to stringResource(R.string.unit_c),
            TempUnits.FAHRENHEIT to stringResource(R.string.unit_f),
        ),
        prefs.tempUnits,
        { prefs.tempUnits = it },
    )
    if (prefs.speedUnits == SpeedUnits.AUTO || prefs.tempUnits == TempUnits.AUTO) {
        Label(
            stringResource(
                R.string.units_auto_now,
                stringResource(if (auto) R.string.unit_mph else R.string.unit_kmh),
                stringResource(if (auto) R.string.unit_f else R.string.unit_c),
            ),
            Typography.small, c.textMuted,
        )
    }
    if (d.hasTpmsSource) {
        val tpms = d.tpms
        Label(stringResource(R.string.units_pressure), Typography.label, c.textMuted)
        Segmented(PressureUnit.entries.map { it to it.label }, tpms.unit, { tpms.unit = it })
    }
}

@Composable
private fun HomeLayout(d: Dependencies, onEdit: () -> Unit, onTemplates: () -> Unit) {
    val prefs = d.prefs
    val context = LocalContext.current
    SettingsCard(stringResource(R.string.layout), stringResource(R.string.arrange_help)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(stringResource(R.string.arrange), onEdit, primary = true)
            Button(stringResource(R.string.templates), onTemplates)
        }
    }
    SettingsCard(stringResource(R.string.bar_position), stringResource(R.string.bar_position_help)) {
        Segmented(
            listOf(BarPosition.AUTO to stringResource(R.string.bar_auto), BarPosition.SIDE to stringResource(R.string.bar_side), BarPosition.BOTTOM to stringResource(R.string.bar_bottom)),
            prefs.barPosition,
            { prefs.barPosition = it },
        )
    }
    SettingsCard { ParkedSettings(prefs) }
    val (requestHome, returns) = rememberRequestHome()
    val isHome = remember(returns) { isHomeApp(context) }
    SettingsCard(stringResource(R.string.welcome_home_title)) {
        if (isHome) {
            Label(stringResource(R.string.welcome_already_home), Typography.body, LocalColors.current.accent)
        } else {
            Button(stringResource(R.string.make_home), { prefs.dontAskHome = false; requestHome() }, primary = true)
        }
    }
}

/** Sección Apps: la de música (con el reproductor USB), la radio, la de navegación (con Casa y Trabajo) y las favoritas de la barra. */
@Composable
private fun AppsSettings(d: Dependencies) {
    val c = LocalColors.current
    when (tabs(listOf(R.string.music, R.string.tab_radio, R.string.tab_navigation, R.string.tab_favorites, R.string.tab_split), APPS_TABS)) {
        0 -> {
            SettingsCard { UsbPlayerCard() }
            SettingsCard(stringResource(R.string.music_app_title)) { MusicSettings(d) }
            SettingsCard(stringResource(R.string.music_sources), stringResource(R.string.music_sources_help)) { MusicSourcesSettings(d) }
        }
        1 -> RadioSettings(d)
        2 -> {
            SettingsCard { MapSettings(d) }
            SettingsCard(stringResource(R.string.places), stringResource(R.string.places_help)) {
                Label(stringResource(R.string.place_home), Typography.label, c.textMuted)
                TextInput(d.prefs.homeAddress ?: "", { d.prefs.homeAddress = it.take(160).ifBlank { null } }, stringResource(R.string.place_hint), Modifier.fillMaxWidth())
                Label(stringResource(R.string.place_work), Typography.label, c.textMuted)
                TextInput(d.prefs.workAddress ?: "", { d.prefs.workAddress = it.take(160).ifBlank { null } }, stringResource(R.string.place_hint), Modifier.fillMaxWidth())
            }
        }
        3 -> SettingsCard(stringResource(R.string.tab_favorites), stringResource(R.string.favorites_help)) { Favorites(d) }
        else -> SettingsCard { SplitSettings(d) }
    }
}

/** Las favoritas de la barra, en su orden: un toque en la x la quita. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Favorites(d: Dependencies) {
    val apps = rememberApps(d.apps).orEmpty()
    val favorites = remember(apps, d.prefs.dock, d.prefs.musicApp) { dockFavorites(d, apps) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (app in favorites) {
            Row(
                Modifier.clip(RoundedCornerShape(Radius.inner)).background(LocalColors.current.surfaceHigh).padding(start = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(d.apps, app, 32.dp)
                Label(app.name, Typography.body, maxLines = 1, modifier = Modifier.padding(horizontal = 10.dp).widthIn(max = 160.dp))
                IconButton(Icons.close, { d.prefs.dock = favorites.map { it.key } - app.key }, size = 44.dp)
            }
        }
    }
}

/** Pestañas de Sistema; Actualizaciones va primero (ahí abre el aviso de versión nueva). */
private enum class SystemTab(val title: Int) {
    UPDATES(R.string.tab_updates), ANDROID(R.string.android_settings), STATS(R.string.stats_title), YOUR_SCREEN(R.string.tab_your_screen),
}

/**
 * Sistema: lo de LiteDash y de la pantalla en pestañas. Actualizaciones (la versión con sus
 * novedades y, en la de GitHub, buscar la siguiente), atajos a Ajustes de Android, estadísticas
 * anónimas e info de tu pantalla.
 */
@Composable
private fun SystemSettings(d: Dependencies) {
    when (SystemTab.entries[tabs(SystemTab.entries.map { it.title }, SYSTEM_TABS).coerceAtMost(SystemTab.entries.lastIndex)]) {
        SystemTab.UPDATES -> {
            About(d)
            if (d.updater.available) SettingsCard { Updates(d.updater) }
        }
        SystemTab.ANDROID -> AndroidSettings()
        SystemTab.STATS -> SettingsCard {
            SwitchRow(stringResource(R.string.stats_switch), d.prefs.stats, { d.prefs.stats = it }, stringResource(R.string.stats_help))
        }
        SystemTab.YOUR_SCREEN -> ScreenInfo(d)
    }
}

/** La pantalla en unas líneas (lo que más sirve para reportar un problema) y copiar todo. */
@Composable
private fun ScreenInfo(d: Dependencies) {
    val c = LocalColors.current
    val context = LocalContext.current
    SettingsCard(stringResource(R.string.copy_info), stringResource(R.string.copy_info_help)) {
        val lines = remember { screenLines(context) }
        for (line in lines) Label(line, Typography.body, maxLines = 2)
        var copied by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(stringResource(R.string.copy_info), { copySystemInfo(context, d.music); copied = true }, primary = true)
            if (copied) Label(stringResource(R.string.copied), Typography.small, c.accent)
        }
    }
}

/** La versión en grande, sus novedades y tu auto a la derecha (como DuduAuto). */
@Composable
private fun About(d: Dependencies) {
    val c = LocalColors.current
    Card(Modifier.fillMaxWidth().widthIn(max = 860.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wide = maxWidth > 560.dp
            val pkg = d.cars.pkg(d.prefs.carModel)
            if (pkg != null && wide) Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
                CarStill(d.cars, pkg, d.prefs.carColor, Modifier.fillMaxWidth(0.46f).aspectRatio(CAR_ASPECT))
            }
            Column(Modifier.padding(22.dp).fillMaxWidth(if (pkg != null && wide) 0.56f else 1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Label("LiteDash ${BuildConfig.VERSION_NAME}", Typography.title.copy(fontSize = 30.sp), maxLines = 1)
                Label(BuildConfig.CHANNEL, Typography.small, c.textMuted)
                if (BuildConfig.RELEASE_NOTES.isNotBlank()) {
                    Label(stringResource(R.string.release_notes), Typography.label, c.textMuted)
                    for (line in releaseNotes()) {
                        if (line.startsWith("###")) Label(line.removePrefix("###").trim(), Typography.subtitle, maxLines = 1)
                        else Label("• $line", Typography.small)
                    }
                }
            }
        }
    }
}

private fun Float.roundToTenth() = round(this * 10) / 10f

fun nameOf(p: Template) = when (p) {
    Template.CENTER -> R.string.template_center
    Template.CARDS -> R.string.template_cards
    Template.SIDE -> R.string.template_side
    Template.COLUMNS -> R.string.template_columns
    Template.PANEL -> R.string.template_panel
    Template.VERTICAL -> R.string.template_vertical
    Template.SPEED -> R.string.template_speed
    Template.CAR_SPEED -> R.string.template_car_speed
    Template.SPORT -> R.string.template_sport
    Template.MINIMAL -> R.string.template_minimal
    Template.SPEED_WIDGETS -> R.string.template_speed_widgets
    Template.WALL_CARDS -> R.string.template_wall_cards
    Template.WALL_SIDE -> R.string.template_wall_side
    Template.MUSIC_RADIO -> R.string.template_music_radio
    Template.MAP_CAR -> R.string.template_map_car
    Template.COPILOT -> R.string.template_copilot
    Template.ROUTE -> R.string.template_route
    Template.VERTICAL_MAP -> R.string.template_vertical_map
}

private fun nameOf(t: ScreenSize) = when (t) {
    ScreenSize.SMALL -> R.string.screens_small
    ScreenSize.MEDIUM -> R.string.screens_medium
    ScreenSize.LARGE -> R.string.screens_large
    ScreenSize.VERTICAL -> R.string.screens_vertical
}

/** Las que lucen el mapa: van en su pestaña y no se repiten en la del auto. */
private val MAP_TEMPLATES = listOf(Template.MAP_CAR, Template.COPILOT, Template.ROUTE, Template.VERTICAL_MAP)

/**
 * Estilos de plantilla (una pestaña cada uno): con el auto 3D, con el mapa de protagonista,
 * con la carretera en lugar del auto ([hero]), solo velocímetro (sin OBD: usa el GPS) o para
 * lucir tu fondo, como BYD (ahí va la Mínima: hora y música, sin velocímetro).
 */
private enum class TemplateTab(val title: Int, val templates: List<Template>, val hero: String = TileTypes.CAR) {
    CAR(R.string.templates_tab_car, Template.entries.filter { it.hasCar && it !in MAP_TEMPLATES }),
    MAP(R.string.templates_tab_map, MAP_TEMPLATES),
    ROAD(R.string.templates_tab_road, listOf(Template.MAP_CAR, Template.CARDS, Template.CENTER, Template.CAR_SPEED, Template.SPORT, Template.VERTICAL), TileTypes.ROAD),
    SPEED(R.string.templates_tab_speed, listOf(Template.SPEED, Template.SPEED_WIDGETS)),
    WALLPAPER(R.string.templates_tab_wallpaper, listOf(Template.WALL_CARDS, Template.WALL_SIDE, Template.MINIMAL)),
}

/**
 * Plantillas en pestañas por estilo y, en cada una, agrupadas por tamaño de pantalla;
 * primero el de la tuya, con el recomendado marcado.
 */
@Composable
fun TemplatesPanel(dashboard: DashboardState, prefs: Preferences, close: () -> Unit) {
    val grid = dashboard.grid ?: return
    val c = LocalColors.current
    val yours = grid.size
    val suggested = grid.suggestedTemplate()
    PanelScaffold(stringResource(R.string.templates), close) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Label(stringResource(R.string.templates_help), Typography.body, c.textMuted)
            val tab = TemplateTab.entries[tabs(TemplateTab.entries.map { it.title }, "templates")]
            fun apply(p: Template) {
                dashboard.template(p, tab.hero)
                close()
            }
            when (tab) {
                TemplateTab.MAP -> Label(stringResource(R.string.templates_map_help), Typography.small, c.textMuted)
                TemplateTab.ROAD -> Label(stringResource(R.string.templates_road_help), Typography.small, c.textMuted)
                TemplateTab.SPEED -> Label(stringResource(R.string.templates_speed_help), Typography.small, c.textMuted)
                TemplateTab.WALLPAPER -> {
                    Label(stringResource(R.string.templates_wallpaper_help), Typography.small, c.textMuted)
                    WallpaperSettings()
                }
                TemplateTab.CAR -> Unit
            }
            for (group in listOf(yours) + ScreenSize.entries.filter { it != yours }) {
                val templates = tab.templates.filter { group in it.sizes }
                if (templates.isEmpty()) continue
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Label(stringResource(nameOf(group)).uppercase(), Typography.label, c.textMuted)
                        if (group == yours) Label(stringResource(R.string.your_screen).uppercase(), Typography.label, c.accent)
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        for (p in templates) {
                            Card(Modifier.width(260.dp), onClick = { apply(p) }) {
                                Column(Modifier.padding(16.dp)) {
                                    TemplateThumbnail(p, grid, tab.hero, dashboard.hidden)
                                    Spacer(Modifier.height(12.dp))
                                    Label(stringResource(nameOf(p)), Typography.subtitle, maxLines = 1)
                                    if (tab == TemplateTab.CAR && group == yours && p == suggested) {
                                        Label(stringResource(R.string.recommended).uppercase(), Typography.label, c.accent, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** Dibujo de la plantilla en chiquito, con las proporciones de esta pantalla. */
@Composable
private fun TemplateThumbnail(p: Template, grid: Grid, hero: String, skip: (String) -> Boolean) {
    val c = LocalColors.current
    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(grid.columns.toFloat() / grid.rows).clip(RoundedCornerShape(Radius.inner)).background(c.background)) {
        val cw = maxWidth / grid.columns
        val ch = maxHeight / grid.rows
        // Igual que al aplicarla: sin piezas de módulos apagados.
        val tiles = p.tiles(grid, hero, skip)
        // La protagonista en ámbar: el auto (o la carretera) o, en las que no lo llevan, el velocímetro o la primera pieza.
        val main = (tiles.firstOrNull { it.type == hero } ?: tiles.firstOrNull { it.type == TileTypes.SPEEDOMETER } ?: tiles.firstOrNull())?.id
        for (tile in tiles) {
            val width = cw * tile.width
            val height = ch * tile.height
            Box(
                Modifier
                    .offset(cw * tile.col, ch * tile.row)
                    .size(width, height)
                    .padding(2.dp)
                    .clip(RoundedCornerShape(Radius.small))
                    .background(if (tile.id == main) c.accent.copy(alpha = 0.55f) else c.surfaceHigh),
                contentAlignment = Alignment.Center,
            ) {
                // El ícono de cada widget: se ve qué es cada cosa (mapa, música, radio…) sin aplicarla.
                Icon(iconOf(tile.type), if (tile.id == main) c.surface else c.textMuted, minOf(width, height, 22.dp) * 0.7f)
            }
        }
    }
}

@Composable
fun AddTilePanel(d: Dependencies, close: () -> Unit, onPickApp: () -> Unit, onPickGauge: () -> Unit) {
    val c = LocalColors.current
    var noRoom by remember { mutableStateOf(false) }
    PanelScaffold(stringResource(R.string.add_tile), close) {
        if (noRoom) {
            Label(stringResource(R.string.no_room), Typography.body, c.danger, modifier = Modifier.padding(bottom = 12.dp))
        }
        // Todas a la vista: tantas columnas como quepan (de 180 dp o más).
        // Los de un módulo apagado no se ofrecen.
        CardGrid(TileSpecs.filter { moduleOff(d, it.type) == null }, minWidth = 180.dp, height = if (isSmallScreen()) 100.dp else 120.dp, onClick = { spec ->
            if (spec.type == TileTypes.APP) onPickApp()
            else if (spec.type == TileTypes.GAUGE) onPickGauge()
            else if (d.dashboard.addTile(spec.type)) close()
            else noRoom = true
        }) { spec ->
            Icon(iconOf(spec.type), c.accent, 28.dp)
            Label(stringResource(spec.name), Typography.subtitle, maxLines = 1)
        }
    }
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


/** Inicio → Widgets: todo lo que puedes poner en tu inicio y si ya está (sin los de módulos apagados). */
@Composable
private fun Tiles(d: Dependencies, onEdit: () -> Unit) {
    val c = LocalColors.current
    // Tipo que no cupo al agregarlo: se avisa y se ofrece acomodar el inicio.
    var noRoom by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (spec in TileSpecs.filter { it.type != TileTypes.APP && moduleOff(d, it.type) == null }) {
            val onHome = d.dashboard.tiles.any { it.type == spec.type }
            val didNotFit = noRoom == spec.type && !onHome
            val detail = if (didNotFit) stringResource(R.string.no_room) else spec.detail?.let { stringResource(it) } ?: ""
            TileRow(iconOf(spec.type), stringResource(spec.name), detail, if (didNotFit) c.danger else c.textMuted) {
                when {
                    onHome -> Label(stringResource(R.string.on_your_home), Typography.small, c.accent)
                    didNotFit -> Button(stringResource(R.string.arrange), onEdit, primary = true)
                    else -> Button(stringResource(R.string.add), { noRoom = if (d.dashboard.addTile(spec.type)) null else spec.type })
                }
            }
        }
    }
}

/**
 * Módulos, en dos pestañas. Módulos: cada uno con su interruptor y, prendido, una flecha a sus
 * ajustes; apagado en gris, no corre ni pide nada y desaparece de todo. Permisos: los que se dan
 * con Android (avisos de otras apps y ubicación), con el mismo interruptor.
 */
@Composable
private fun Modules(d: Dependencies, revision: Int, open: (SettingsSection) -> Unit) {
    val permissions = tabs(listOf(R.string.modules, R.string.tab_permissions), "modules") == 1
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (permissions) return@Column Permissions(d, revision)
        ModuleRow(Icons.engine, stringResource(R.string.module_obd), d.obdModule, { d.obdModule = it }) { open(SettingsSection.WORKSHOP) }
        // Llantas: prendido = hay una fuente (sin crear el módulo para saberlo). Al prender vuelve
        // la última que se usó; si nunca hubo, a elegirla en Ajustes → Llantas.
        ModuleRow(Icons.tire, stringResource(R.string.module_tpms), d.hasTpmsSource, { on ->
            val tpms = d.tpms
            if (!on) tpms.source = null
            else tpms.lastSource?.let { tpms.source = it } ?: open(SettingsSection.TIRES)
        }) { open(SettingsSection.TIRES) }
        ModuleRow(Icons.car, stringResource(R.string.module_cameras), on = false, onChange = null, soon = true)
    }
}

/**
 * Módulos → Permisos: prender los pide; apagar solo se puede en Ajustes de Android. [revision]
 * cambia al volver a la app: lo que se dio o quitó allá se nota al regresar.
 */
@Composable
private fun Permissions(d: Dependencies, revision: Int) {
    val context = LocalContext.current
    val (_, returns) = rememberRequestHome()
    val notifications = remember(returns, revision) { d.music.hasPermission() }
    val (location, askLocation) = rememberGpsPermission(d, revision)
    ModuleRow(Icons.bell, stringResource(R.string.module_notifications), notifications, { on ->
        if (on) d.music.requestPermission() else openAppSettings(context)
    })
    ModuleRow(Icons.location, stringResource(R.string.module_location), location, { on ->
        if (on) askLocation() else openAppSettings(context)
    })
}

/**
 * Atajos a los ajustes del sistema: muchas pantallas de auto esconden sus ajustes detrás
 * del launcher de la marca, y al poner LiteDash de inicio ya no se encuentran.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AndroidSettings() {
    val c = LocalColors.current
    val context = LocalContext.current
    val shortcuts = remember {
        listOf(
            R.string.sys_all to Settings.ACTION_SETTINGS,
            R.string.sys_bluetooth to Settings.ACTION_BLUETOOTH_SETTINGS,
            R.string.sys_wifi to Settings.ACTION_WIFI_SETTINGS,
            R.string.sys_display to Settings.ACTION_DISPLAY_SETTINGS,
            R.string.sys_sound to Settings.ACTION_SOUND_SETTINGS,
            R.string.sys_apps to Settings.ACTION_APPLICATION_SETTINGS,
            R.string.sys_date to Settings.ACTION_DATE_SETTINGS,
            R.string.sys_language to Settings.ACTION_LOCALE_SETTINGS,
            R.string.sys_home to Settings.ACTION_HOME_SETTINGS,
        ).filter { (_, action) -> Intent(action).resolveActivity(context.packageManager) != null }
    }
    SettingsCard(stringResource(R.string.android_settings), stringResource(R.string.android_settings_help)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((name, action) in shortcuts) {
                Button(stringResource(name), { runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }, primary = action == Settings.ACTION_SETTINGS)
            }
        }
        if (shortcuts.isEmpty()) Label(stringResource(R.string.android_settings_none), Typography.small, c.textMuted)
    }
}

/** Los permisos de LiteDash en Ajustes de Android (para dar o quitar ubicación). */
private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Un renglón de Inicio → Widgets: ícono, nombre, detalle y su botón. */
@Composable
private fun TileRow(
    icon: ImageVector,
    title: String,
    detail: String,
    detailColor: Color = LocalColors.current.textMuted,
    action: @Composable () -> Unit,
) {
    val c = LocalColors.current
    val shape = RoundedCornerShape(Radius.card)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.line, shape).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, c.accent, 26.dp)
        Column(Modifier.weight(1f)) {
            Label(title, Typography.subtitle, maxLines = 1)
            Label(detail, Typography.small, detailColor, maxLines = 2)
        }
        action()
    }
}

/**
 * Un módulo: ícono, nombre e interruptor (todo eso se toca para prender o apagar) y, prendido y
 * con [onOpen], una flecha redonda aparte que entra a sus ajustes. Apagado, en gris. [soon]: aún
 * no existe (interruptor apagado sin tocar y la etiqueta "Pronto").
 */
@Composable
private fun ModuleRow(
    icon: ImageVector,
    title: String,
    on: Boolean,
    onChange: ((Boolean) -> Unit)?,
    soon: Boolean = false,
    onOpen: (() -> Unit)? = null,
) {
    val c = LocalColors.current
    val shape = RoundedCornerShape(Radius.card)
    val dim = if (on) 1f else 0.45f
    Row(
        Modifier.fillMaxWidth().height(76.dp).clip(shape).background(c.surface).border(1.dp, c.line, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .then(if (onChange != null) Modifier.clickable { onChange(!on) } else Modifier)
                .padding(start = 20.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(icon, if (on) c.accent else c.textMuted.copy(alpha = dim), 28.dp)
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Label(title, Typography.subtitle, if (on) c.text else c.textMuted.copy(alpha = dim), maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                if (soon) Label(
                    stringResource(R.string.module_soon),
                    Typography.small.copy(fontWeight = FontWeight.SemiBold),
                    c.textMuted,
                    maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(c.surfaceHigh).padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            Switch(on, Modifier.graphicsLayer { alpha = if (soon) 0.45f else 1f })
        }
        // La flecha va aparte (con su línea): entrar no prende ni apaga nada.
        Box(Modifier.width(76.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            if (on && onOpen != null) {
                Box(Modifier.align(Alignment.CenterStart).width(1.dp).fillMaxHeight(0.5f).background(c.line))
                IconButton(Icons.chevron, onOpen, size = 48.dp, background = c.surfaceHigh, color = c.text)
            }
        }
    }
}

/** Notas de una versión (sección del CHANGELOG) en renglones: títulos ("### Agregado") y viñetas sin formato. */
private fun releaseNotes(notes: String = BuildConfig.RELEASE_NOTES): List<String> {
    val items = mutableListOf<String>()
    for (raw in notes.lines()) {
        val line = raw.trim()
        when {
            line.isEmpty() -> Unit
            line.startsWith("###") -> items += line
            line.startsWith("- ") -> items += line.removePrefix("- ")
            items.isNotEmpty() && !items.last().startsWith("###") -> items[items.lastIndex] = items.last() + " " + line
        }
    }
    return items.map { it.replace("**", "") }
}

/** Lo básico de la pantalla: equipo, Android, resolución y memoria (sin etiquetas: se entienden solos). */
private fun screenLines(context: Context): List<String> {
    val m = context.resources.displayMetrics
    return listOf(
        "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) · ${Build.HARDWARE}",
        "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.SUPPORTED_ABIS.joinToString()}",
        "${m.widthPixels}×${m.heightPixels} px · ${m.densityDpi} dpi · ${memoryMb(context)} MB",
    )
}

/** La memoria (RAM) de la pantalla en MB. */
private fun memoryMb(context: Context): Long {
    val memory = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
    return memory.totalMem / (1024 * 1024)
}

/** Datos de la pantalla para reportar problemas (marca, modelo, Android, resolución, memoria). */
private fun copySystemInfo(context: Context, music: MusicRepository) {
    val m = context.resources.displayMetrics
    val text = buildString {
        appendLine("LiteDash ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.CHANNEL}")
        appendLine("Equipo: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("Plataforma: ${Build.HARDWARE} · ${Build.BOARD}")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Pantalla: ${m.widthPixels}×${m.heightPixels} px · ${m.densityDpi} dpi")
        appendLine("Memoria: ${memoryMb(context)} MB")
        appendLine("CPU: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("Pantalla dividida: ${if (SplitScreen.supported()) "sí" else "no"}")
        appendLine(lightsInfo(context))
        append(music.describe())
    }
    context.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText("LiteDash", text))
}


/**
 * Para conectar los faros: si hay sensor de luz y qué ajustes de la pantalla hablan de
 * luces o iluminación (cada marca los nombra distinto; solo se leen los que Android deja).
 */
private fun lightsInfo(context: Context): String {
    val sensor = context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_LIGHT)
    val names = Regex("illum|headl|lamp|light|dimm|night|backlight|ill_", RegexOption.IGNORE_CASE)
    val found = listOf(
        "system" to Settings.System.CONTENT_URI,
        "global" to Settings.Global.CONTENT_URI,
        "secure" to Settings.Secure.CONTENT_URI,
    ).flatMap { (table, uri) ->
        runCatching {
            context.contentResolver.query(uri, arrayOf("name", "value"), null, null, null)?.use { c ->
                buildList { while (c.moveToNext()) c.getString(0)?.takeIf(names::containsMatchIn)?.let { add("$table/$it=${c.getString(1)}") } }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }
    return "Sensor de luz: ${sensor?.name ?: "no"}\nLuces: ${found.joinToString().ifEmpty { "—" }}"
}

/** Cuánto se espera la confirmación de Android antes de ofrecer el instalador de siempre. */
private const val INSTALLER_WAIT_MS = 8_000L

/** Estados del botón de actualizaciones (solo versión de GitHub). */
private sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val v: Version) : UpdateState
    data class NeedsPermission(val v: Version) : UpdateState
    data object Error : UpdateState
}

@Composable
private fun Updates(a: Updater) {
    val c = LocalColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // La confirmación de Android ("¿Instalar?") la abre esta pantalla, que está al frente.
    val confirm = Updater.confirm
    LaunchedEffect(confirm) {
        if (confirm == null) return@LaunchedEffect
        Updater.confirm = null
        runCatching { context.startActivity(confirm) }
    }
    // Si al abrir ya se encontró una versión nueva, se muestra de una vez.
    var state by remember { mutableStateOf(a.found?.let { UpdateState.Available(it) } ?: UpdateState.Idle) }

    fun install(v: Version) {
        if (!a.canInstall()) {
            state = UpdateState.NeedsPermission(v)
            a.requestInstallPermission()
            return
        }
        // La descarga vive en el Updater: sigue aunque se cierre Ajustes.
        a.downloadAndInstall(v)
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (val p = a.progress) {
            is Updater.Progress.Downloading -> Label(stringResource(R.string.downloading, p.percent), Typography.body, c.accent)
            is Updater.Progress.Installing -> {
                Label(stringResource(R.string.installing), Typography.body, c.accent)
                // Si la confirmación de Android no aparece en unos segundos (o falla), el respaldo.
                var stuck by remember(p) { mutableStateOf(false) }
                LaunchedEffect(p) {
                    delay(INSTALLER_WAIT_MS)
                    stuck = true
                }
                if (stuck || Updater.installError != null) {
                    Button(stringResource(R.string.open_installer), { a.openWithInstaller(p.apk) }, primary = true)
                }
            }
            // Sin descarga en curso: buscar, la versión encontrada o el permiso de instalar.
            else -> when (val e = state) {
                UpdateState.Idle, UpdateState.UpToDate, UpdateState.Error -> {
                    Button(stringResource(R.string.check_updates), {
                        state = UpdateState.Checking
                        scope.launch {
                            state = runCatching { a.checkForUpdate() }
                                .fold({ v -> if (v == null) UpdateState.UpToDate else UpdateState.Available(v) }, { UpdateState.Error })
                        }
                    })
                    if (e == UpdateState.UpToDate) Label(stringResource(R.string.up_to_date), Typography.small, c.accent)
                }
                UpdateState.Checking -> Label(stringResource(R.string.checking), Typography.body, c.textMuted)
                is UpdateState.Available -> NewVersion(e.v) { install(e.v) }
                is UpdateState.NeedsPermission -> {
                    Label(stringResource(R.string.install_permission), Typography.small, c.textMuted)
                    Button(stringResource(R.string.already_allowed), { install(e.v) }, primary = true)
                }
            }
        }
        // Falló la búsqueda o la última descarga.
        if (state == UpdateState.Error || a.progress == Updater.Progress.Failed) Label(stringResource(R.string.update_error), Typography.small, c.danger)
        Updater.installError?.let { Label(stringResource(R.string.install_error, it), Typography.small, c.danger) }
    }
}

@Composable
private fun NewVersion(v: Version, onInstall: () -> Unit) {
    val c = LocalColors.current
    val shape = RoundedCornerShape(Radius.card)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.accent, shape).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Label(stringResource(R.string.new_version, v.name), Typography.subtitle)
        // Las notas del Release como lista corta (sin los títulos de sección).
        val notes = releaseNotes(v.notes).filterNot { it.startsWith("###") }.take(6).joinToString("\n") { "• $it" }
        if (notes.isNotBlank()) Label(notes, Typography.small, c.textMuted, maxLines = 8)
        Button(stringResource(R.string.download_install, v.bytes / 1e6f), onInstall, primary = true)
    }
}
