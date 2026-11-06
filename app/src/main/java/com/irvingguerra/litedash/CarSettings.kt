// Ajustes → Tu auto: el auto elegido, sus modelos descargados y el catálogo.
package com.irvingguerra.litedash

import com.irvingguerra.litedash.core.ui.Radius
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.TextInput
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.feature.apps.normalize
import com.irvingguerra.litedash.feature.car.CAR_ASPECT
import com.irvingguerra.litedash.feature.car.CarDrawing
import com.irvingguerra.litedash.feature.car.CarPackage
import com.irvingguerra.litedash.feature.car.CarRepository
import com.irvingguerra.litedash.feature.car.CarStill
import com.irvingguerra.litedash.feature.car.CarTurntable
import com.irvingguerra.litedash.feature.car.CatalogEntry
import com.irvingguerra.litedash.feature.music.MusicRepository
import kotlinx.coroutines.launch

/**
 * Ajustes → Tu auto, en tarjetas: el auto (nombre y color), las opciones del modelo
 * elegido justo debajo (sin bajar toda la lista), los modelos descargados a la vista
 * y el catálogo con buscador y filtro por marca. Las descargas viven en el
 * repositorio: siguen aunque se cierre Ajustes.
 */
@Composable
internal fun CarSettings(d: Dependencies) {
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
                CatalogSearch(prefs, cars, d.music, catalog)
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

/**
 * El catálogo: buscador, marcas (con cuántos modelos tiene cada una) y la cuadrícula filtrada.
 * Los descargados también salen: tocarlos los elige; los demás abren su vista previa.
 */
@Composable
private fun CatalogSearch(prefs: Preferences, cars: CarRepository, music: MusicRepository, catalog: List<CatalogEntry>?) {
    val c = LocalColors.current
    var query by remember { mutableStateOf("") }
    var make by remember { mutableStateOf<String?>(null) }
    var previewing by remember { mutableStateOf<String?>(null) }
    // Busca por marca, modelo o año, sin importar acentos ni mayúsculas.
    TextInput(query, { query = it }, stringResource(R.string.search_car), Modifier.fillMaxWidth(), icon = true)
    val list = catalog ?: return Label(stringResource(R.string.checking), Typography.small, c.textMuted)
    // Marcas con más modelos primero; dentro de cada una, por nombre y año.
    val makes = remember(list) {
        list.groupingBy { it.make }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
    }
    val sorted = remember(list, makes) {
        val rank = makes.withIndex().associate { (i, m) -> m.key to i }
        list.sortedWith(compareBy<CatalogEntry> { rank[it.make] }.thenBy { it.name }.thenBy { it.year })
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(stringResource(R.string.catalog_all_makes), { make = null }, primary = make == null)
        for ((name, count) in makes) Button("$name · $count", { make = name }, primary = make == name)
    }
    val q = normalize(query.trim())
    val found = sorted.filter { e ->
        (make == null || e.make == make) && q.split(' ').all { it in normalize("${e.make} ${e.name} ${e.year}") }
    }
    if (found.isEmpty()) return CarRequest(music, query.trim().ifEmpty { make.orEmpty() })
    val downloaded = cars.installed.map { it.id }.toSet()
    // Un descargado no se previsualiza (ya está): si se acaba de bajar, su vista previa se cierra.
    val open = found.firstOrNull { it.id == previewing && it.id !in downloaded }
    if (open != null) {
        val progress = cars.downloading?.takeIf { it.first == open.id }?.second
        // Al terminar se elige, aunque ya se haya cerrado Ajustes.
        CarPreview(open, cars, progress, onDownload = { cars.install(open) { prefs.carModel = open.id } }, onClose = { previewing = null })
    }
    val shown = found.filter { it.id != open?.id }
    // Con "Todas" y sin texto, agrupados por marca; si no, una sola cuadrícula.
    val groups = if (make == null && q.isEmpty()) shown.groupBy { it.make }.toList() else listOf(null to shown)
    for ((title, entries) in groups) {
        if (title != null) Label(title, Typography.label, c.textMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (e in entries) {
                val have = e.id in downloaded
                // Tocar uno sin bajar solo lo muestra en grande; se baja con el botón (ver no cuesta datos).
                ModelCard(
                    "${e.make} ${e.name}",
                    detail = if (have) stringResource(R.string.catalog_downloaded) else "${e.year} · %.1f MB".format(e.bytes / 1e6f),
                    selected = prefs.carModel == e.id,
                    onClick = { if (have) prefs.carModel = e.id else previewing = e.id },
                ) { m ->
                    var img by remember(e.id) { mutableStateOf<ImageBitmap?>(null) }
                    LaunchedEffect(e.id) { img = cars.thumbnail(e) }
                    img?.let { Image(it, null, m, contentScale = ContentScale.Fit) }
                }
            }
        }
    }
}

/** Sin resultados: "aún no lo tenemos" y pedirlo con un reporte (lo escrito o la marca elegida). */
@Composable
private fun CarRequest(music: MusicRepository, what: String) {
    val c = LocalColors.current
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var state by remember(what) { mutableStateOf<ReportState>(ReportState.Idle) }
    if (state is ReportState.Sent) return Label(stringResource(R.string.catalog_request_thanks), Typography.small, c.accent)
    Label(stringResource(R.string.catalog_missing), Typography.small, c.textMuted)
    if (what.isEmpty()) return
    val sending = state == ReportState.Sending
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            stringResource(if (sending) R.string.report_sending else R.string.catalog_request),
            {
                if (state != ReportState.Sending) {
                    state = ReportState.Sending
                    scope.launch { state = requestCar(context, music, what) }
                }
            },
            Modifier.graphicsLayer { alpha = if (sending) 0.45f else 1f },
            primary = true,
        )
        when (state) {
            ReportState.Error -> Label(stringResource(R.string.report_error), Typography.small, c.danger)
            ReportState.Wait -> Label(stringResource(R.string.report_wait), Typography.small, c.textMuted)
            else -> Unit
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
