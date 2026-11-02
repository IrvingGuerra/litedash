package com.irvingguerra.litedash

import com.irvingguerra.litedash.feature.apps.AppIcon
import com.irvingguerra.litedash.feature.apps.rememberApps
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import android.net.Uri
import com.irvingguerra.litedash.feature.obd.ObdState
import com.irvingguerra.litedash.core.ui.SwitchRow
import com.irvingguerra.litedash.core.ui.Segmented
import com.irvingguerra.litedash.core.ui.Radius
import android.app.Activity
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import com.irvingguerra.litedash.feature.dashboard.TileTypes
import com.irvingguerra.litedash.feature.dashboard.size
import com.irvingguerra.litedash.feature.dashboard.suggestedTemplate
import com.irvingguerra.litedash.feature.dashboard.tiles
import com.irvingguerra.litedash.feature.music.MusicRepository
import com.irvingguerra.litedash.feature.updater.Updater
import com.irvingguerra.litedash.feature.updater.Version
import java.io.File
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
fun TextInput(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier, icon: Boolean = false) {
    val c = LocalColors.current
    Row(
        modifier.height(56.dp).clip(RoundedCornerShape(Radius.card)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(Radius.card)).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon) {
            Icon(Icons.search, c.textMuted, 22.dp)
            Spacer(Modifier.width(12.dp))
        }
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Label(hint, Typography.body, c.textMuted, maxLines = 1)
            BasicTextField(
                value,
                onChange,
                Modifier.fillMaxWidth(),
                textStyle = Typography.body.copy(color = c.text),
                singleLine = true,
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
        }
    }
}

@Composable
fun DrawerPanel(d: Dependencies, apps: List<App>, close: () -> Unit) {
    val c = LocalColors.current
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<App?>(null) }
    var showHidden by remember { mutableStateOf(false) }
    // Sin dock propio se ven las sugeridas: fijar parte de ellas (no de cero).
    val dock = d.prefs.dock ?: remember(apps) { dockFavorites(d, apps).map { it.key } }
    val hidden = d.prefs.hidden
    val visible = remember(apps, hidden, showHidden) { if (showHidden) apps else apps.filter { it.key !in hidden } }
    PanelScaffold(stringResource(R.string.apps), close) {
        TextInput(query, { query = it }, stringResource(R.string.search_app), Modifier.fillMaxWidth().widthIn(max = 640.dp), icon = true)
        val app = selected
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
                    d.dashboard.addTile(TileTypes.APP, app.key)
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
    PanelScaffold(stringResource(R.string.pick_app), close) {
        TextInput(query, { query = it }, stringResource(R.string.search_app), Modifier.fillMaxWidth().widthIn(max = 640.dp), icon = true)
        AppGrid(
            d.apps, apps, query,
            onClick = { app ->
                if (tileId != null) {
                    dashboard.setData(tileId, app.key)
                } else {
                    dashboard.addTile(TileTypes.APP, app.key)
                }
                close()
            },
            modifier = Modifier.weight(1f).padding(top = 8.dp),
        )
    }
}

/**
 * Qué dato del auto muestra un medidor: los que lee el OBD, con su ícono. Los que
 * este auto no da (ya conectado) se ven apagados y avisan.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PickGaugePanel(d: Dependencies, tileId: String?, close: () -> Unit) {
    val c = LocalColors.current
    val obd = d.obdIfCreated()?.takeIf { it.state == ObdState.ON }
    PanelScaffold(stringResource(R.string.pick_gauge), close) {
        Label(stringResource(R.string.pick_gauge_help), Typography.body, c.textMuted, modifier = Modifier.padding(bottom = 14.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            for (spec in GaugeSpecs) {
                val readable = obd?.canRead(spec.signal) ?: true
                Card(Modifier.width(220.dp).height(110.dp), onClick = {
                    if (tileId != null) d.dashboard.setData(tileId, spec.signal) else d.dashboard.addTile(TileTypes.GAUGE, spec.signal)
                    close()
                }) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(spec.icon, if (readable) c.accent else c.textMuted, 28.dp)
                        Label(stringResource(spec.name), Typography.subtitle, if (readable) c.text else c.textMuted, maxLines = 1)
                        if (!readable) Label(stringResource(R.string.gauge_unsupported), Typography.small, c.textMuted, maxLines = 1)
                    }
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
    val tab = tabs(listOf(R.string.tab_my_car, R.string.tab_models, R.string.reminders), "auto")

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
    if (tab == 0) SettingsCard(stringResource(R.string.logo_title), stringResource(R.string.logo_help)) {
        LogoSettings(d, brandName(d, pkg))
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

/** Secciones de Ajustes, en el menú de la izquierda. */
/** Secciones de Ajustes en el orden del menú: cada cosa vive en un solo lugar. */
enum class SettingsSection(val title: Int) {
    CAR(R.string.your_car), HOME(R.string.home), APPS(R.string.apps_section), WORKSHOP(R.string.workshop),
    WHEEL(R.string.wheel), APPEARANCE(R.string.appearance), MODULES(R.string.modules), ABOUT(R.string.about),
}

private fun SettingsSection.icon() = when (this) {
    SettingsSection.APPEARANCE -> Icons.theme
    SettingsSection.CAR -> Icons.car
    SettingsSection.HOME -> Icons.dashboard
    SettingsSection.APPS -> Icons.apps
    SettingsSection.WHEEL -> Icons.wheel
    SettingsSection.WORKSHOP -> Icons.engine
    SettingsSection.MODULES -> Icons.settings
    SettingsSection.ABOUT -> Icons.info
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
                for (s in SettingsSection.entries) MenuEntry(s.icon(), stringResource(s.title), s == section, narrow) { section = s }
            }
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 8.dp, end = 24.dp, top = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Label(stringResource(section.title), Typography.title.copy(fontSize = 30.sp), maxLines = 1)
                when (section) {
                    SettingsSection.APPEARANCE -> Appearance(d.prefs, revision)
                    SettingsSection.CAR -> CarSettings(d)
                    SettingsSection.HOME -> HomeSettings(d, onEdit, onTemplates)
                    SettingsSection.APPS -> AppsSettings(d)
                    SettingsSection.WHEEL -> {
                        SettingsCard(help = stringResource(R.string.wheel_help)) { WheelSettings(d.wheel, d.prefs) }
                        SettingsCard(stringResource(R.string.wheel_assigned)) { WheelAssigned(d.prefs) }
                    }
                    SettingsSection.WORKSHOP -> Workshop(d)
                    SettingsSection.MODULES -> {
                        Label(stringResource(R.string.modules_help), Typography.body, c.textMuted)
                        Modules(d)
                    }
                    SettingsSection.ABOUT -> About(d)
                }
            }
        }
    }
}

/**
 * Pestañas de una sección con muchas partes (para no hacer tanto scroll). Devuelve la
 * elegida; se recuerda por sección ([key]) mientras Ajustes esté abierto.
 */
@Composable
internal fun tabs(titles: List<Int>, key: String): Int {
    val selected = TabMemory[key] ?: 0
    // Mismo control que las opciones (estilo iOS): una pestaña a la vez.
    Segmented(titles.mapIndexed { i, t -> i to stringResource(t) }, selected, { TabMemory[key] = it }, Modifier.horizontalScroll(rememberScrollState()))
    return selected
}

/** La pestaña elegida en cada sección (al cambiar de sección y regresar sigue ahí). */
private val TabMemory = mutableStateMapOf<String, Int>()

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

@Composable
private fun Appearance(prefs: Preferences, revision: Int) {
    val c = LocalColors.current
    val context = LocalContext.current
    val tab = tabs(listOf(R.string.tab_general, R.string.tab_screen), "apariencia")
    if (tab == 0) SettingsCard(stringResource(R.string.your_name), stringResource(R.string.your_name_help)) {
        TextInput(prefs.driverName ?: "", { prefs.driverName = it.take(24).ifBlank { null } }, stringResource(R.string.your_name_hint), Modifier.fillMaxWidth())
    }
    if (tab == 1) SettingsCard(stringResource(R.string.theme)) {
        Segmented(
            listOf(ThemeMode.DARK to R.string.theme_dark, ThemeMode.LIGHT to R.string.theme_light, ThemeMode.SUN to R.string.theme_sun, ThemeMode.SYSTEM to R.string.theme_system)
                .map { (mode, text) -> mode to stringResource(text) },
            prefs.theme,
            { prefs.theme = it },
        )
    }
    if (tab == 1) SettingsCard(stringResource(R.string.brightness), stringResource(R.string.brightness_help)) {
        BrightnessSettings(prefs, revision)
    }
    if (tab == 0) SettingsCard(stringResource(R.string.language)) {
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
    if (tab == 1) SettingsCard(stringResource(R.string.scale), stringResource(R.string.scale_help)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconButton(Icons.minus, { prefs.scale = (prefs.scale - 0.1f).coerceAtLeast(0.8f).roundToTenth() }, background = c.surfaceHigh)
            Label("${(prefs.scale * 100).toInt()} %", Typography.title, modifier = Modifier.widthIn(min = 90.dp))
            IconButton(Icons.plus, { prefs.scale = (prefs.scale + 0.1f).coerceAtMost(1.5f).roundToTenth() }, background = c.surfaceHigh)
        }
    }
}

@Composable
private fun HomeSettings(d: Dependencies, onEdit: () -> Unit, onTemplates: () -> Unit) {
    val prefs = d.prefs
    val context = LocalContext.current
    val tab = tabs(listOf(R.string.tab_layout, R.string.tab_tiles), "inicio")
    if (tab == 1) return Tiles(d, onEdit)
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
    SettingsCard {
        SwitchRow(stringResource(R.string.parked_mode), prefs.parkedMode, { prefs.parkedMode = it }, stringResource(R.string.parked_mode_help))
    }
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

/** Sección Apps: la de música, la de navegación (con Casa y Trabajo) y las favoritas de la barra. */
@Composable
private fun AppsSettings(d: Dependencies) {
    val c = LocalColors.current
    when (tabs(listOf(R.string.music, R.string.tab_navigation, R.string.tab_favorites), "apps")) {
        0 -> SettingsCard { MusicSettings(d) }
        1 -> {
            SettingsCard { MapSettings(d) }
            SettingsCard(stringResource(R.string.places), stringResource(R.string.places_help)) {
                Label(stringResource(R.string.place_home), Typography.label, c.textMuted)
                TextInput(d.prefs.homeAddress ?: "", { d.prefs.homeAddress = it.take(160).ifBlank { null } }, stringResource(R.string.place_hint), Modifier.fillMaxWidth())
                Label(stringResource(R.string.place_work), Typography.label, c.textMuted)
                TextInput(d.prefs.workAddress ?: "", { d.prefs.workAddress = it.take(160).ifBlank { null } }, stringResource(R.string.place_hint), Modifier.fillMaxWidth())
            }
        }
        else -> SettingsCard(stringResource(R.string.tab_favorites), stringResource(R.string.favorites_help)) { Favorites(d) }
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

@Composable
private fun About(d: Dependencies) {
    val c = LocalColors.current
    val context = LocalContext.current
    // Como DuduAuto: la versión en grande, sus novedades y tu auto a la derecha.
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
                if (d.updater.available) Updates(d.updater)
            }
        }
    }
    SettingsCard(stringResource(R.string.copy_info), stringResource(R.string.copy_info_help)) {
        var copied by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(stringResource(R.string.copy_info), { copySystemInfo(context, d.music); copied = true })
            if (copied) Label(stringResource(R.string.copied), Typography.small, c.accent)
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
}

private fun nameOf(t: ScreenSize) = when (t) {
    ScreenSize.SMALL -> R.string.screens_small
    ScreenSize.MEDIUM -> R.string.screens_medium
    ScreenSize.LARGE -> R.string.screens_large
    ScreenSize.VERTICAL -> R.string.screens_vertical
}

/** Acomodos agrupados por tamaño de pantalla; primero el de la tuya, con el recomendado marcado. */
@Composable
fun TemplatesPanel(dashboard: DashboardState, close: () -> Unit) {
    val grid = dashboard.grid ?: return
    val c = LocalColors.current
    val yours = grid.size
    val suggested = grid.suggestedTemplate()
    PanelScaffold(stringResource(R.string.templates), close) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Label(stringResource(R.string.templates_help), Typography.body, c.textMuted)
            for (group in listOf(yours) + ScreenSize.entries.filter { it != yours }) {
                val templates = Template.entries.filter { group in it.sizes }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Label(stringResource(nameOf(group)).uppercase(), Typography.label, c.textMuted)
                        if (group == yours) Label(stringResource(R.string.your_screen).uppercase(), Typography.label, c.accent)
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        for (p in templates) {
                            Card(Modifier.width(260.dp), onClick = { dashboard.template(p); close() }) {
                                Column(Modifier.padding(16.dp)) {
                                    TemplateThumbnail(p, grid)
                                    Spacer(Modifier.height(12.dp))
                                    Label(stringResource(nameOf(p)), Typography.subtitle, maxLines = 1)
                                    if (group == yours && p == suggested) {
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
private fun TemplateThumbnail(p: Template, grid: Grid) {
    val c = LocalColors.current
    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(grid.columns.toFloat() / grid.rows).clip(RoundedCornerShape(Radius.inner)).background(c.background)) {
        val cw = maxWidth / grid.columns
        val ch = maxHeight / grid.rows
        for (tile in p.tiles(grid)) {
            Box(
                Modifier
                    .offset(cw * tile.col, ch * tile.row)
                    .size(cw * tile.width, ch * tile.height)
                    .padding(2.dp)
                    .clip(RoundedCornerShape(Radius.small))
                    .background(if (tile.type == TileTypes.CAR) c.accent.copy(alpha = 0.55f) else c.surfaceHigh),
            )
        }
    }
}

@Composable
fun AddTilePanel(dashboard: DashboardState, close: () -> Unit, onPickApp: () -> Unit, onPickGauge: () -> Unit) {
    val c = LocalColors.current
    var noRoom by remember { mutableStateOf(false) }
    PanelScaffold(stringResource(R.string.add_tile), close) {
        if (noRoom) {
            Label(stringResource(R.string.no_room), Typography.body, c.danger, modifier = Modifier.padding(bottom = 12.dp))
        }
        // Todas a la vista: tantas columnas como quepan (de 180 dp o más) y, si aun así
        // no alcanza el alto (pantallas muy chicas), se desplaza.
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            val gap = 14.dp
            val columns = ((maxWidth + gap) / (180.dp + gap)).toInt().coerceAtLeast(2)
            val width = (maxWidth - gap * (columns - 1)) / columns
            val height = if (isSmallScreen()) 100.dp else 120.dp
            FlowRow(horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap)) {
                for (spec in TileSpecs) {
                    Card(Modifier.width(width).height(height), onClick = {
                        if (spec.type == TileTypes.APP) onPickApp()
                        else if (spec.type == TileTypes.GAUGE) onPickGauge()
                        else if (dashboard.add(spec.type, spec.initialSize, spec.limits)) close()
                        else noRoom = true
                    }) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(iconOf(spec.type), c.accent, 28.dp)
                            Label(stringResource(spec.name), Typography.subtitle, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

private fun iconOf(type: String) = when (type) {
    TileTypes.CAR -> Icons.car
    TileTypes.MUSIC -> Icons.music
    TileTypes.NAVIGATE -> Icons.navigate
    TileTypes.CLOCK -> Icons.clock
    TileTypes.PHONE -> Icons.phone
    TileTypes.WEATHER -> Icons.sun
    TileTypes.TRIP -> Icons.speed
    TileTypes.SPEEDOMETER -> Icons.speed
    TileTypes.GAUGE -> Icons.tachometer
    TileTypes.WORKSHOP -> Icons.engine
    TileTypes.PROJECTION -> Icons.phoneCar
    else -> Icons.apps
}


/** Inicio → Piezas: todo lo que puedes poner en tu inicio y si ya está. */
@Composable
private fun Tiles(d: Dependencies, onEdit: () -> Unit) {
    val c = LocalColors.current
    // Tipo que no cupo al agregarlo: se avisa y se ofrece acomodar el inicio.
    var noRoom by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (spec in TileSpecs.filter { it.type != TileTypes.APP && it.type != TileTypes.CAR }) {
            val onHome = d.dashboard.tiles.any { it.type == spec.type }
            val didNotFit = noRoom == spec.type && !onHome
            val detail = if (didNotFit) stringResource(R.string.no_room) else spec.detail?.let { stringResource(it) } ?: ""
            ModuleRow(iconOf(spec.type), stringResource(spec.name), detail, if (didNotFit) c.danger else c.textMuted) {
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
 * Módulos: lo que LiteDash enciende aparte, con su permiso. Apagado no corre ni pide nada;
 * las piezas que lo usan lo piden al agregarlas.
 */
@Composable
private fun Modules(d: Dependencies) {
    val c = LocalColors.current
    val context = LocalContext.current
    val (_, returns) = rememberRequestHome()
    val notifications = remember(returns) { d.music.hasPermission() }
    val location = remember(returns) { d.trip.hasPermission() }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val obd = d.obdIfCreated()?.state == ObdState.ON
        ModuleRow(Icons.engine, stringResource(R.string.module_obd), stringResource(if (obd) R.string.module_obd_on else if (d.hasObdAdapter) R.string.module_obd_saved else R.string.module_obd_off)) {
            Label(stringResource(if (obd) R.string.module_on else R.string.module_off), Typography.small, if (obd) c.accent else c.textMuted)
        }
        ModuleRow(Icons.bell, stringResource(R.string.module_notifications), stringResource(R.string.module_notifications_help)) {
            if (notifications) Label(stringResource(R.string.module_on), Typography.small, c.accent)
            else Button(stringResource(R.string.module_turn_on), { d.music.requestPermission() })
        }
        ModuleRow(Icons.location, stringResource(R.string.module_location), stringResource(R.string.module_location_help)) {
            if (location) Label(stringResource(R.string.module_on), Typography.small, c.accent)
            else Button(stringResource(R.string.module_settings), { openAppSettings(context) })
        }
        for ((name, help) in listOf(R.string.module_tpms to R.string.module_tpms_help, R.string.module_cameras to R.string.module_cameras_help)) {
            ModuleRow(Icons.car, stringResource(name), stringResource(help)) {
                Label(stringResource(R.string.module_soon), Typography.small, c.textMuted)
            }
        }
    }
}

/** Los permisos de LiteDash en Ajustes de Android (para dar o quitar ubicación). */
private fun openAppSettings(context: Context) {
    val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
private fun ModuleRow(
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

/** Datos de la pantalla para reportar problemas (marca, modelo, Android, resolución, memoria). */
private fun copySystemInfo(context: Context, music: MusicRepository) {
    val m = context.resources.displayMetrics
    val memory = ActivityManager.MemoryInfo().also {
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(it)
    }
    val text = buildString {
        appendLine("LiteDash ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.CHANNEL}")
        appendLine("Equipo: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("Plataforma: ${Build.HARDWARE} · ${Build.BOARD}")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Pantalla: ${m.widthPixels}×${m.heightPixels} px · ${m.densityDpi} dpi")
        appendLine("Memoria: ${memory.totalMem / (1024 * 1024)} MB")
        appendLine("CPU: ${Build.SUPPORTED_ABIS.joinToString()}")
        append(music.describe())
    }
    context.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText("LiteDash", text))
}


/** Cuánto se espera la confirmación de Android antes de ofrecer el instalador de siempre. */
private const val INSTALLER_WAIT_MS = 8_000L

/** Estados del botón de actualizaciones (solo versión de GitHub). */
private sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val v: Version) : UpdateState
    data class Downloading(val v: Version, val progress: Float) : UpdateState
    data class NeedsPermission(val v: Version) : UpdateState
    data class Installing(val apk: File) : UpdateState
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
        state = UpdateState.Downloading(v, 0f)
        scope.launch {
            runCatching {
                val apk = a.download(v) { f -> state = UpdateState.Downloading(v, f) }
                state = UpdateState.Installing(apk)
                a.install(apk)
            }.onFailure { state = UpdateState.Error }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (val e = state) {
            UpdateState.Idle, UpdateState.UpToDate, UpdateState.Error -> {
                Button(stringResource(R.string.check_updates), {
                    state = UpdateState.Checking
                    scope.launch {
                        state = runCatching { a.checkForUpdate() }
                            .fold({ v -> if (v == null) UpdateState.UpToDate else UpdateState.Available(v) }, { UpdateState.Error })
                    }
                })
                if (e == UpdateState.UpToDate) Label(stringResource(R.string.up_to_date), Typography.small, c.accent)
                if (e == UpdateState.Error) Label(stringResource(R.string.update_error), Typography.small, c.danger)
            }
            UpdateState.Checking -> Label(stringResource(R.string.checking), Typography.body, c.textMuted)
            is UpdateState.Available -> NewVersion(e.v) { install(e.v) }
            is UpdateState.Downloading -> Label(stringResource(R.string.downloading, (e.progress * 100).toInt()), Typography.body, c.accent)
            is UpdateState.NeedsPermission -> {
                Label(stringResource(R.string.install_permission), Typography.small, c.textMuted)
                Button(stringResource(R.string.already_allowed), { install(e.v) }, primary = true)
            }
            is UpdateState.Installing -> {
                Label(stringResource(R.string.installing), Typography.body, c.accent)
                // Si la confirmación de Android no aparece en unos segundos (o falla), el respaldo.
                var stuck by remember(e) { mutableStateOf(false) }
                LaunchedEffect(e) {
                    delay(INSTALLER_WAIT_MS)
                    stuck = true
                }
                if (stuck || Updater.installError != null) {
                    Button(stringResource(R.string.open_installer), { a.openWithInstaller(e.apk) }, primary = true)
                }
            }
        }
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
