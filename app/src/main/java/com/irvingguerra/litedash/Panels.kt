package com.irvingguerra.litedash

import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.foundation.Image
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.net.toUri
import com.irvingguerra.litedash.feature.car.CatalogEntry
import com.irvingguerra.litedash.feature.car.Smoothness
import com.irvingguerra.litedash.feature.car.CarSpin
import com.irvingguerra.litedash.feature.car.CarPackage
import com.irvingguerra.litedash.feature.car.CarRepository
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.feature.updater.Updater
import com.irvingguerra.litedash.feature.updater.Version
import com.irvingguerra.litedash.feature.apps.App
import com.irvingguerra.litedash.feature.apps.AppGrid
import com.irvingguerra.litedash.feature.apps.normalize
import com.irvingguerra.litedash.feature.car.CarColors
import com.irvingguerra.litedash.feature.car.CarDrawing
import com.irvingguerra.litedash.feature.dashboard.Grid
import com.irvingguerra.litedash.feature.dashboard.DashboardState
import com.irvingguerra.litedash.feature.dashboard.Template
import com.irvingguerra.litedash.feature.dashboard.ScreenSize
import com.irvingguerra.litedash.feature.dashboard.suggestedTemplate
import com.irvingguerra.litedash.feature.dashboard.size
import com.irvingguerra.litedash.feature.dashboard.TileTypes
import com.irvingguerra.litedash.feature.dashboard.tiles

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
        modifier.height(56.dp).clip(RoundedCornerShape(28.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(28.dp)).padding(horizontal = 20.dp),
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
    val dock = d.prefs.dock ?: emptyList()
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
                    val spec = TileSpecs.first { it.type == TileTypes.APP }
                    d.dashboard.add(spec.type, spec.initialSize, spec.limits, app.key)
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
                    val spec = TileSpecs.first { it.type == TileTypes.APP }
                    dashboard.add(spec.type, spec.initialSize, spec.limits, app.key)
                }
                close()
            },
            modifier = Modifier.weight(1f).padding(top = 8.dp),
        )
    }
}

@Composable
fun CarPanel(prefs: Preferences, cars: CarRepository, close: () -> Unit) {
    val c = LocalColors.current
    val color = prefs.carColor
    val pkg = remember(prefs.carModel, cars.changes) { cars.pkg(prefs.carModel) }
    PanelScaffold(stringResource(R.string.your_car), close) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val horizontal = maxWidth > maxHeight
            val view = @Composable { m: Modifier ->
                Column(m, horizontalAlignment = Alignment.CenterHorizontally) {
                    if (pkg != null) {
                        CarSpin(cars, pkg, color, Modifier.fillMaxWidth().aspectRatio(1.9f))
                        Label(stringResource(R.string.spin_your_car), Typography.small, c.textMuted)
                    } else {
                        CarDrawing(color, Modifier.fillMaxWidth())
                    }
                }
            }
            val options = @Composable { m: Modifier -> CarOptions(prefs, cars, pkg, m) }
            if (horizontal) {
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    view(Modifier.weight(1.3f))
                    options(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 16.dp))
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    view(Modifier.fillMaxWidth())
                    options(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** Nombre, color y modelo del auto: en "Tu auto" y en Ajustes → Tu auto. */
@Composable
private fun CarOptions(prefs: Preferences, cars: CarRepository, pkg: CarPackage?, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Label(stringResource(R.string.name), Typography.label, c.textMuted)
        TextInput(prefs.carName ?: "", { prefs.carName = it.take(24).ifBlank { null } }, pkg?.name ?: stringResource(R.string.my_car), Modifier.fillMaxWidth())
        Label(stringResource(R.string.color), Typography.label, c.textMuted)
        ColorPicker(prefs.carColor) { prefs.carColor = it }
        // Mientras no haya catálogo ni autos instalados, solo existe el genérico.
        if (cars.hasCatalog || cars.installed().isNotEmpty()) {
            Label(stringResource(R.string.model), Typography.label, c.textMuted)
            CarModels(prefs, cars, pkg)
        } else {
            Label(stringResource(R.string.car_coming_soon), Typography.small, c.textMuted)
        }
    }
}

/** Ajustes → Tu auto: el auto como se ve en el inicio y sus opciones. */
@Composable
private fun CarSettings(d: Dependencies) {
    val prefs = d.prefs
    val pkg = remember(prefs.carModel, d.cars.changes) { d.cars.pkg(prefs.carModel) }
    SettingsCard(Icons.car) {
        Box(Modifier.fillMaxWidth().widthIn(max = 520.dp).aspectRatio(2.4f)) {
            if (pkg != null) {
                CarSpin(d.cars, pkg, prefs.carColor, Modifier.fillMaxSize())
            } else {
                CarDrawing(prefs.carColor, Modifier.fillMaxWidth().align(Alignment.Center))
            }
        }
        CarOptions(prefs, d.cars, pkg, Modifier.fillMaxWidth())
    }
    SettingsCard(Icons.bell, stringResource(R.string.reminders), stringResource(R.string.reminders_help)) {
        ReminderSettings(d.reminders)
    }
}

/** Genérico, los autos ya instalados y los del catálogo, con buscador. */
@Composable
private fun CarModels(prefs: Preferences, cars: CarRepository, current: CarPackage?) {
    val c = LocalColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installed = remember(cars.changes) { cars.installed() }
    var catalog by remember { mutableStateOf<List<CatalogEntry>?>(null) }
    var error by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf<Pair<String, Float>?>(null) }
    var query by remember { mutableStateOf("") }
    var previewing by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(cars.hasCatalog) {
        if (cars.hasCatalog) runCatching { cars.catalog() }.onSuccess { catalog = it }.onFailure { error = true }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Busca por marca, modelo o año, sin importar acentos ni mayúsculas.
        TextInput(query, { query = it }, stringResource(R.string.search_car), Modifier.fillMaxWidth(), icon = true)
        val q = normalize(query.trim())
        fun matches(text: String) = q.isEmpty() || q.split(' ').all { it in normalize(text) }
        ModelOption(stringResource(R.string.model_generic), null, selected = current == null) { prefs.carModel = null }
        for (p in installed.filter { matches("${it.make} ${it.name} ${it.year}") }) {
            val fromCatalog = catalog?.firstOrNull { it.id == p.id }
            ModelOption("${p.make} ${p.name} ${p.year}", null, selected = current?.id == p.id, thumbnail = fromCatalog?.let { e -> { cars.thumbnail(e) } }) { prefs.carModel = p.id }
        }
        for (e in catalog.orEmpty().filter { e -> installed.none { it.id == e.id } && matches("${e.make} ${e.name} ${e.year}") }) {
            val progress = downloading?.takeIf { it.first == e.id }?.second
            val fetch = {
                if (downloading == null) {
                    downloading = e.id to 0f
                    scope.launch {
                        runCatching { cars.install(e) { f -> downloading = e.id to f } }
                            .onSuccess { prefs.carModel = e.id; previewing = null }
                            .onFailure { error = true }
                        downloading = null
                    }
                }
            }
            // Tocar un auto solo lo muestra; se baja con el botón (ver no cuesta datos).
            if (previewing == e.id || progress != null) {
                CarPreview(e, cars, progress, onDownload = fetch, onClose = { previewing = null })
            } else {
                ModelOption("${e.make} ${e.name} ${e.year}", "%.1f MB".format(e.bytes / 1e6f), selected = false, thumbnail = { cars.thumbnail(e) }) { previewing = e.id }
            }
        }
        if (error) Label(stringResource(R.string.catalog_error), Typography.small, c.danger)
        // Fluidez del giro: cada nivel baja solo los cuadros que faltan.
        val entry = current?.let { a -> catalog?.firstOrNull { it.id == a.id } }
        if (current != null && current.spin == 24 && entry != null && entry.extras.isNotEmpty()) {
            Label(stringResource(R.string.smoothness), Typography.label, c.textMuted, modifier = Modifier.padding(top = 8.dp))
            val progress = downloading?.takeIf { it.first == "fluidez" }?.second
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((level, text) in listOf(Smoothness.LIGHT to R.string.smoothness_light, Smoothness.MEDIUM to R.string.smoothness_medium, Smoothness.MAX to R.string.smoothness_max)) {
                    // Subir dice cuánto se baja; bajar, cuánto espacio se libera.
                    val gain = entry.extras.filter { it.level in (current.smoothness + 1)..level }.sumOf { it.bytes }
                    val freed = entry.extras.filter { it.level in (level + 1)..current.smoothness }.sumOf { it.bytes }
                    val label = when {
                        gain > 0 -> "${stringResource(text)} · +${"%.1f".format(gain / 1e6f)} MB"
                        freed > 0 -> "${stringResource(text)} · −${"%.1f".format(freed / 1e6f)} MB"
                        else -> stringResource(text)
                    }
                    Button(label, {
                        if (downloading != null || level == current.smoothness) return@Button
                        if (level < current.smoothness) {
                            scope.launch { cars.lowerSmoothness(current, level) }
                            return@Button
                        }
                        downloading = "fluidez" to 0f
                        scope.launch {
                            runCatching { cars.raiseSmoothness(current, entry, level) { f -> downloading = "fluidez" to f } }
                                .onFailure { error = true }
                            downloading = null
                        }
                    }, primary = level == current.smoothness)
                }
            }
            if (progress != null) Label("${(progress * 100).toInt()} %", Typography.small, c.accent)
            else Label(stringResource(R.string.smoothness_help), Typography.small, c.textMuted)
        }
        // Eliminar lo descargado: pide confirmar con un segundo toque.
        if (current != null) {
            var confirm by remember(current.id) { mutableStateOf(false) }
            val mb = remember(current, cars.changes) { cars.sizeOnDisk(current) / 1e6f }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                if (!confirm) {
                    Button(stringResource(R.string.delete_car, mb), { confirm = true })
                } else {
                    Button(stringResource(R.string.delete_confirm), {
                        prefs.carModel = null
                        scope.launch { cars.delete(current) }
                    }, primary = true)
                    Button(stringResource(R.string.cancel), { confirm = false })
                }
            }
        }
        // Crédito del modelo 3D (lo pide su licencia).
        if (current != null && current.author.isNotBlank()) {
            Label(
                stringResource(R.string.model_credit, current.author, current.license),
                Typography.small,
                c.textMuted,
                modifier = Modifier.clickable {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, current.source.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                },
            )
        }
    }
}

@Composable
private fun ModelOption(title: String, detail: String?, selected: Boolean, thumbnail: (suspend () -> ImageBitmap?)? = null, onClick: () -> Unit) {
    val c = LocalColors.current
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) c.accent else c.line, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (thumbnail != null) {
            // Vista previa: el auto antes de bajarlo (la imagen viene del catálogo).
            var img by remember(title) { mutableStateOf<ImageBitmap?>(null) }
            LaunchedEffect(title) { img = thumbnail() }
            Box(Modifier.padding(end = 14.dp).size(width = 92.dp, height = 52.dp), contentAlignment = Alignment.Center) {
                img?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            }
        }
        Label(title, Typography.body, maxLines = 1, modifier = Modifier.weight(1f))
        if (detail != null) Label(detail, Typography.small, c.accent, maxLines = 1)
        if (selected) Icon(Icons.done, c.accent, 22.dp)
    }
}

/** Un auto del catálogo abierto en la lista: su vista previa en grande, de quién es y el botón para bajarlo. */
@Composable
private fun CarPreview(e: CatalogEntry, cars: CarRepository, progress: Float?, onDownload: () -> Unit, onClose: () -> Unit) {
    val c = LocalColors.current
    val shape = RoundedCornerShape(16.dp)
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
enum class SettingsSection(val title: Int) { APPEARANCE(R.string.appearance), CAR(R.string.your_car), HOME(R.string.home), MUSIC(R.string.music), MAP(R.string.map), MODULES(R.string.modules), ABOUT(R.string.about) }

private fun SettingsSection.icon() = when (this) {
    SettingsSection.APPEARANCE -> Icons.theme
    SettingsSection.CAR -> Icons.car
    SettingsSection.HOME -> Icons.dashboard
    SettingsSection.MUSIC -> Icons.music
    SettingsSection.MAP -> Icons.navigate
    SettingsSection.MODULES -> Icons.apps
    SettingsSection.ABOUT -> Icons.info
}

/**
 * Ajustes como en las pantallas de auto: menú de secciones a la izquierda y
 * tarjetas a la derecha. En pantallas angostas el menú queda solo con íconos.
 */
@Composable
fun SettingsPanel(
    d: Dependencies,
    close: () -> Unit,
    onEdit: () -> Unit,
    onTemplates: () -> Unit,
    start: SettingsSection = SettingsSection.APPEARANCE,
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
                    SettingsSection.APPEARANCE -> Appearance(d.prefs)
                    SettingsSection.CAR -> CarSettings(d)
                    SettingsSection.HOME -> HomeSettings(d.prefs, onEdit, onTemplates)
                    SettingsSection.MUSIC -> SettingsCard(Icons.music) { MusicSettings(d) }
                    SettingsSection.MAP -> {
                        SettingsCard(Icons.navigate) { MapSettings(d) }
                        SettingsCard(Icons.home, stringResource(R.string.places), stringResource(R.string.places_help)) {
                            Label(stringResource(R.string.place_home), Typography.label, c.textMuted)
                            TextInput(d.prefs.homeAddress ?: "", { d.prefs.homeAddress = it.take(160).ifBlank { null } }, stringResource(R.string.place_hint), Modifier.fillMaxWidth())
                            Label(stringResource(R.string.place_work), Typography.label, c.textMuted)
                            TextInput(d.prefs.workAddress ?: "", { d.prefs.workAddress = it.take(160).ifBlank { null } }, stringResource(R.string.place_hint), Modifier.fillMaxWidth())
                        }
                    }
                    SettingsSection.MODULES -> {
                        Label(stringResource(R.string.modules_help), Typography.body, c.textMuted)
                        Modules(d, onEdit)
                    }
                    SettingsSection.ABOUT -> About(d)
                }
            }
        }
    }
}

@Composable
private fun MenuEntry(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, selected: Boolean, narrow: Boolean, onClick: () -> Unit) {
    val c = LocalColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) c.surfaceHigh else androidx.compose.ui.graphics.Color.Transparent)
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
private fun SettingsCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String? = null,
    help: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalColors.current
    Card(Modifier.fillMaxWidth().widthIn(max = 860.dp)) {
        Icon(icon, c.accent.copy(alpha = 0.14f), 72.dp, Modifier.align(Alignment.TopEnd).padding(18.dp))
        Column(Modifier.padding(22.dp).padding(end = 64.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (title != null) Label(title, Typography.subtitle, maxLines = 1)
            content()
            if (help != null) Label(help, Typography.small, c.textMuted)
        }
    }
}

@Composable
private fun Appearance(prefs: Preferences) {
    val c = LocalColors.current
    val context = LocalContext.current
    SettingsCard(Icons.car, stringResource(R.string.your_name), stringResource(R.string.your_name_help)) {
        TextInput(prefs.driverName ?: "", { prefs.driverName = it.take(24).ifBlank { null } }, stringResource(R.string.your_name_hint), Modifier.fillMaxWidth())
    }
    SettingsCard(Icons.theme, stringResource(R.string.theme)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((mode, text) in listOf(ThemeMode.DARK to R.string.theme_dark, ThemeMode.LIGHT to R.string.theme_light, ThemeMode.SUN to R.string.theme_sun, ThemeMode.SYSTEM to R.string.theme_system)) {
                Button(stringResource(text), { prefs.theme = mode }, primary = prefs.theme == mode)
            }
        }
    }
    SettingsCard(Icons.language, stringResource(R.string.language)) {
        val activity = context as? android.app.Activity
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((code, text) in listOf("" to R.string.language_system, "es" to R.string.language_es, "en" to R.string.language_en)) {
                Button(stringResource(text), {
                    if (prefs.language != code) {
                        prefs.language = code
                        activity?.recreate()
                    }
                }, primary = prefs.language == code)
            }
        }
    }
    SettingsCard(Icons.expand, stringResource(R.string.scale), stringResource(R.string.scale_help)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconButton(Icons.minus, { prefs.scale = (prefs.scale - 0.1f).coerceAtLeast(0.8f).roundToTenth() }, background = c.surfaceHigh)
            Label("${(prefs.scale * 100).toInt()} %", Typography.title, modifier = Modifier.widthIn(min = 90.dp))
            IconButton(Icons.plus, { prefs.scale = (prefs.scale + 0.1f).coerceAtMost(1.5f).roundToTenth() }, background = c.surfaceHigh)
        }
    }
}

@Composable
private fun HomeSettings(prefs: Preferences, onEdit: () -> Unit, onTemplates: () -> Unit) {
    val context = LocalContext.current
    SettingsCard(Icons.dashboard, stringResource(R.string.layout), stringResource(R.string.arrange_help)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(stringResource(R.string.arrange), onEdit, primary = true)
            Button(stringResource(R.string.templates), onTemplates)
        }
    }
    SettingsCard(Icons.clock, stringResource(R.string.parked_mode), stringResource(R.string.parked_mode_help)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(stringResource(R.string.on), { prefs.parkedMode = true }, primary = prefs.parkedMode)
            Button(stringResource(R.string.off), { prefs.parkedMode = false }, primary = !prefs.parkedMode)
        }
    }
    val (requestHome, returns) = rememberRequestHome()
    val isHome = remember(returns) { isHomeApp(context) }
    SettingsCard(Icons.home, stringResource(R.string.welcome_home_title)) {
        if (isHome) {
            Label(stringResource(R.string.welcome_already_home), Typography.body, LocalColors.current.accent)
        } else {
            Button(stringResource(R.string.make_home), { prefs.dontAskHome = false; requestHome() }, primary = true)
        }
    }
}

@Composable
private fun About(d: Dependencies) {
    val c = LocalColors.current
    val context = LocalContext.current
    SettingsCard(Icons.info, "LiteDash ${BuildConfig.VERSION_NAME}") {
        Label(BuildConfig.CHANNEL, Typography.small, c.textMuted)
        if (d.updater.available) Updates(d.updater)
    }
    SettingsCard(Icons.apps, stringResource(R.string.copy_info), stringResource(R.string.copy_info_help)) {
        var copied by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(stringResource(R.string.copy_info), { copySystemInfo(context); copied = true })
            if (copied) Label(stringResource(R.string.copied), Typography.small, c.accent)
        }
    }
}

private fun Float.roundToTenth() = kotlin.math.round(this * 10) / 10f

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
    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(grid.columns.toFloat() / grid.rows).clip(RoundedCornerShape(12.dp)).background(c.background)) {
        val cw = maxWidth / grid.columns
        val ch = maxHeight / grid.rows
        for (tile in p.tiles(grid)) {
            Box(
                Modifier
                    .offset(cw * tile.col, ch * tile.row)
                    .size(cw * tile.width, ch * tile.height)
                    .padding(2.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (tile.type == TileTypes.CAR) c.accent.copy(alpha = 0.55f) else c.surfaceHigh),
            )
        }
    }
}

@Composable
fun AddTilePanel(dashboard: DashboardState, close: () -> Unit, onPickApp: () -> Unit) {
    val c = LocalColors.current
    var noRoom by remember { mutableStateOf(false) }
    PanelScaffold(stringResource(R.string.add_tile), close) {
        if (noRoom) {
            Label(stringResource(R.string.no_room), Typography.body, c.danger, modifier = Modifier.padding(bottom = 12.dp))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            for (spec in TileSpecs) {
                Card(Modifier.width(200.dp).height(120.dp), onClick = {
                    if (spec.type == TileTypes.APP) onPickApp()
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

private fun iconOf(type: String) = when (type) {
    TileTypes.CAR -> Icons.car
    TileTypes.MUSIC -> Icons.music
    TileTypes.NAVIGATE -> Icons.navigate
    TileTypes.CLOCK -> Icons.clock
    TileTypes.PHONE -> Icons.phone
    TileTypes.WEATHER -> Icons.sun
    TileTypes.TRIP -> Icons.speed
    TileTypes.PROJECTION -> Icons.phoneCar
    else -> Icons.apps
}


/** Cada módulo: qué hace y si está en tu inicio. Los que vienen después se anuncian, apagados. */
@Composable
private fun Modules(d: Dependencies, onEdit: () -> Unit) {
    val c = LocalColors.current
    val permission = remember(d.dashboard.tiles.any { it.type == TileTypes.MUSIC }) { d.music.hasPermission() }
    // Tipo que no cupo al agregarlo: se avisa y se ofrece acomodar el inicio.
    var noRoom by remember { mutableStateOf<String?>(null) }

    @Composable
    fun ModuleItem(type: String, icon: androidx.compose.ui.graphics.vector.ImageVector, name: String, detail: String) {
        val onHome = d.dashboard.tiles.any { it.type == type }
        val didNotFit = noRoom == type && !onHome
        ModuleRow(icon, name, if (didNotFit) stringResource(R.string.no_room) else detail, if (didNotFit) c.danger else c.textMuted) {
            when {
                onHome -> Label(stringResource(R.string.on_your_home), Typography.small, c.accent)
                didNotFit -> Button(stringResource(R.string.arrange), onEdit, primary = true)
                else -> Button(stringResource(R.string.add), {
                    val spec = TileSpecs.first { it.type == type }
                    noRoom = if (d.dashboard.add(spec.type, spec.initialSize, spec.limits)) null else type
                })
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ModuleItem(TileTypes.MUSIC, Icons.music, stringResource(R.string.tile_music), stringResource(if (permission) R.string.music_with_permission else R.string.music_without_permission))
        ModuleItem(TileTypes.WEATHER, Icons.sun, stringResource(R.string.tile_weather), stringResource(R.string.weather_module))
        ModuleItem(TileTypes.TRIP, Icons.speed, stringResource(R.string.tile_trip), stringResource(R.string.trip_module))
        ModuleItem(TileTypes.PROJECTION, Icons.phoneCar, stringResource(R.string.tile_projection), stringResource(R.string.projection_module))
    }
}

@Composable
private fun ModuleRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String,
    detailColor: androidx.compose.ui.graphics.Color = LocalColors.current.textMuted,
    action: @Composable () -> Unit,
) {
    val c = LocalColors.current
    val shape = RoundedCornerShape(16.dp)
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

/** Datos de la pantalla para reportar problemas (marca, modelo, Android, resolución, memoria). */
private fun copySystemInfo(context: android.content.Context) {
    val m = context.resources.displayMetrics
    val memory = android.app.ActivityManager.MemoryInfo().also {
        context.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it)
    }
    val text = buildString {
        appendLine("LiteDash ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.CHANNEL}")
        appendLine("Equipo: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (${android.os.Build.DEVICE})")
        appendLine("Plataforma: ${android.os.Build.HARDWARE} · ${android.os.Build.BOARD}")
        appendLine("Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        appendLine("Pantalla: ${m.widthPixels}×${m.heightPixels} px · ${m.densityDpi} dpi")
        appendLine("Memoria: ${memory.totalMem / (1024 * 1024)} MB")
        append("CPU: ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
    }
    context.getSystemService(android.content.ClipboardManager::class.java)
        .setPrimaryClip(android.content.ClipData.newPlainText("LiteDash", text))
}


/** Estados del botón de actualizaciones (solo versión de GitHub). */
private sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val v: Version) : UpdateState
    data class Downloading(val v: Version, val progress: Float) : UpdateState
    data class NeedsPermission(val v: Version) : UpdateState
    data object Installing : UpdateState
    data object Error : UpdateState
}

@Composable
private fun Updates(a: Updater) {
    val c = LocalColors.current
    val scope = rememberCoroutineScope()
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
                state = UpdateState.Installing
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
            UpdateState.Installing -> Label(stringResource(R.string.installing), Typography.body, c.accent)
        }
        Updater.installError?.let { Label(stringResource(R.string.install_error, it), Typography.small, c.danger) }
    }
}

@Composable
private fun NewVersion(v: Version, onInstall: () -> Unit) {
    val c = LocalColors.current
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.accent, shape).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Label(stringResource(R.string.new_version, v.name), Typography.subtitle)
        // Las notas del Release como lista corta: une los renglones partidos del
        // CHANGELOG y quita el formato de Markdown.
        val notes = buildList {
            for (r in v.notes.lines().map { it.trim() }) when {
                r.isBlank() || r.startsWith("#") -> Unit
                r.startsWith("- ") -> add("• " + r.removePrefix("- "))
                isNotEmpty() -> this[lastIndex] = this[lastIndex] + " " + r
                else -> add(r)
            }
        }.take(6).joinToString("\n").replace("**", "")
        if (notes.isNotBlank()) Label(notes, Typography.small, c.textMuted, maxLines = 8)
        Button(stringResource(R.string.download_install, v.bytes / 1e6f), onInstall, primary = true)
    }
}
