// Ajustes → Tu auto: el auto elegido, sus modelos descargados y el catálogo.
package com.irvingguerra.litedash

import com.irvingguerra.litedash.core.ui.Radius
import android.content.Intent
import android.text.format.Formatter
import java.io.IOException
import org.json.JSONException
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
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
import com.irvingguerra.litedash.core.ui.raised
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
import com.irvingguerra.litedash.feature.car.PaintFinish

/** Dónde se recuerda la pestaña de Tu auto. */
private const val CAR_TABS = "car"

/** Ajustes → Tu auto abre en Modelos («Ver el catálogo» de la bienvenida). */
internal fun openCarModels() = selectTab(CAR_TABS, 1)

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
    // Cada «Reintentar» lo sube y vuelve a pedir el catálogo.
    var attempt by remember { mutableIntStateOf(0) }
    // La falla de una descarga de otra vez no se queda pegada al volver a entrar.
    LaunchedEffect(Unit) { cars.forgetFailure() }
    LaunchedEffect(cars.hasCatalog, attempt) {
        if (!cars.hasCatalog) return@LaunchedEffect
        unreachable = false
        // Sin red, un 4xx (también IOException) o un catálogo que no es JSON: se avisa abajo con «Reintentar».
        try {
            catalog = cars.catalog()
        } catch (e: IOException) {
            unreachable = true
        } catch (e: JSONException) {
            unreachable = true
        }
    }
    val tab = tabs(listOf(R.string.tab_my_car, R.string.tab_models, R.string.reminders), CAR_TABS)
    val entry = pkg?.let { p -> catalog?.firstOrNull { it.id == p.id } }

    if (tab == 0) SettingsCard {
        Box(Modifier.fillMaxWidth().widthIn(max = 520.dp).aspectRatio(1.8f)) {
            if (pkg != null) {
                // Arriba el auto; abajo queda lugar para su sombra y su reflejo en el piso.
                CarTurntable(cars, pkg, prefs.carPaint, Modifier.fillMaxWidth().fillMaxHeight(0.72f), prefs.carAngle, { prefs.carAngle = it })
            } else {
                CarDrawing(prefs.carColor, Modifier.fillMaxWidth().align(Alignment.Center))
            }
        }
        Label(stringResource(R.string.car_home_help), Typography.small, c.textMuted)
        Label(stringResource(R.string.name), Typography.label, c.textMuted)
        CarNameField(prefs, pkg?.name ?: stringResource(R.string.my_car))
        Label(stringResource(R.string.color), Typography.label, c.textMuted)
        CarColorPicker(prefs)
        if (pkg != null) FinishPicker(prefs, cars, pkg, entry)
    }
    if (tab == 0 && pkg != null) {
        SettingsCard("${pkg.make} ${pkg.name} ${entry?.years ?: pkg.year}") {
            ModelOptions(cars, pkg, entry, onDelete = { prefs.carModel = null; cars.delete(pkg) })
        }
    }
    if (tab == 1 && (cars.hasCatalog || installed.isNotEmpty() || cars.unreadable.isNotEmpty())) {
        SettingsCard(stringResource(R.string.your_models)) { DownloadedModels(prefs, cars, catalog) }
    }
    if (tab == 1) {
        if (cars.hasCatalog) {
            SettingsCard(stringResource(R.string.catalog)) {
                val list = catalog
                if (list != null) {
                    CatalogSearch(prefs, cars, list)
                } else if (unreachable) {
                    // Falló la lista: el error y con qué intentarlo otra vez (nada de «Buscando…»).
                    Label(downloadError(R.string.catalog_error), Typography.small, c.danger)
                    Button(stringResource(R.string.retry), { attempt++ })
                } else {
                    Label(stringResource(R.string.checking), Typography.small, c.textMuted)
                }
            }
        } else if (installed.isEmpty() && cars.unreadable.isEmpty()) {
            SettingsCard { Label(stringResource(R.string.car_coming_soon), Typography.small, c.textMuted) }
        }
    }
    if (tab == 2) SettingsCard(stringResource(R.string.reminders), stringResource(R.string.reminders_help)) {
        ReminderSettings(d.reminders)
    }
}

/**
 * Acabado de la pintura (y su brillo en Brillante y Metálico): solo si el auto trae sus capas o
 * el catálogo las tiene para este mismo modelo 3D. Elegir uno distinto de Satinado las baja.
 */
@Composable
private fun FinishPicker(prefs: Preferences, cars: CarRepository, pkg: CarPackage, entry: CatalogEntry?) {
    val c = LocalColors.current
    val offered = entry?.takeIf { cars.offersFinish(pkg, it) }
    if (!pkg.finish && offered == null) return
    val finish = prefs.paintFinish
    val idle = cars.downloading == null
    // Con otro acabado y el auto sin capas (otro modelo, calidad alta nueva…): se bajan solas.
    // Tras una falla espera a que se toque un acabado o se vuelva a abrir la pantalla (si no, reintentaría sin parar).
    val finishFailed = cars.failed == CarRepository.FINISH
    LaunchedEffect(pkg, finish, offered, idle) {
        if (idle && cars.failed != CarRepository.FINISH && finish != PaintFinish.SATIN && offered != null) cars.fetchFinish(pkg, offered)
    }
    Label(stringResource(R.string.finish), Typography.label, c.textMuted)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((f, label) in listOf(
            PaintFinish.MATTE to R.string.finish_matte,
            PaintFinish.SATIN to R.string.finish_satin,
            PaintFinish.GLOSS to R.string.finish_gloss,
            PaintFinish.METALLIC to R.string.finish_metallic,
        )) {
            Button(stringResource(label), {
                prefs.paintFinish = f
                if (f != PaintFinish.SATIN && offered != null) cars.fetchFinish(pkg, offered)
            }, primary = finish == f)
        }
    }
    val progress = cars.downloading?.takeIf { it.first == CarRepository.FINISH }?.second
    if (progress != null) {
        Label(stringResource(R.string.preparing_finish, (progress * 100).toInt()), Typography.small, c.accent)
    } else if (finishFailed && finish != PaintFinish.SATIN && !pkg.finish) {
        Label(downloadError(R.string.catalog_error), Typography.small, c.danger)
    }
    if (finish.shines) {
        Label(stringResource(R.string.shine), Typography.label, c.textMuted)
        LevelSlider(prefs.paintGloss, prefs::previewPaintGloss) { prefs.paintGloss = it }
    }
}

/** El modelo elegido: modelo 3D nuevo, calidad del giro, eliminarlo y su crédito. */
@Composable
private fun ModelOptions(cars: CarRepository, current: CarPackage, entry: CatalogEntry?, onDelete: () -> Unit) {
    val c = LocalColors.current
    val context = LocalContext.current
    if (entry != null) CarUpdateNotice(cars, current, entry)
    if (cars.failed == current.id) Label(downloadError(R.string.catalog_error), Typography.small, c.danger)
    // Calidad del giro: subir necesita el catálogo; bajar (liberar espacio) no.
    val high = entry?.high
    if (high != null || current.high) {
        Label(stringResource(R.string.spin_quality), Typography.label, c.textMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val size = high?.let { Formatter.formatShortFileSize(context, it.bytes) }
            val low = if (current.high && size != null) stringResource(R.string.spin_quality_low_saves, size) else stringResource(R.string.spin_quality_low)
            Button(low, { if (current.high) cars.lowerQuality(current) }, primary = !current.high)
            if (entry != null && size != null) {
                val hi = if (!current.high) stringResource(R.string.spin_quality_high_costs, size) else stringResource(R.string.spin_quality_high)
                Button(hi, { if (!current.high) cars.raiseQuality(current, entry) }, primary = current.high)
            }
        }
        val progress = cars.downloading?.takeIf { it.first == CarRepository.HIGH }?.second
        if (progress != null) Label(stringResource(R.string.downloading, (progress * 100).toInt()), Typography.small, c.accent)
        else if (cars.failed == CarRepository.HIGH) Label(downloadError(R.string.catalog_error), Typography.small, c.danger)
        else Label(stringResource(R.string.spin_quality_help), Typography.small, c.textMuted)
    }
    // Eliminar lo descargado: pide confirmar.
    val mb by produceState(0f, current) { value = cars.sizeOnDisk(current) / 1e6f }
    Confirmed(stringResource(R.string.delete_car, mb), stringResource(R.string.delete_car_question), stringResource(R.string.confirm_delete), action = onDelete)
    // Crédito del modelo 3D (lo pide su licencia).
    if (current.author.isNotBlank()) {
        Label(
            stringResource(R.string.model_credit, current.author, current.license),
            Typography.small,
            c.textMuted,
            modifier = Modifier.clickable {
                openSystemScreen(context, Intent(Intent.ACTION_VIEW, current.source.toUri()))
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
                CarStill(cars, p, prefs.carPaint, m)
            }
        }
    }
    // Los que no se pudieron leer (descarga dañada) no salen arriba pero ocupan espacio: "Eliminar todos" también los quita.
    val stored = installed.size + cars.unreadable.size
    if (installed.isEmpty()) Label(stringResource(R.string.no_models), Typography.small, c.textMuted)
    if (stored == 0) return
    // Todos a la vez: los que tienen modelo 3D nuevo, o quitar todo lo descargado.
    val outdated = catalog.orEmpty().mapNotNull { e -> installed.firstOrNull { cars.isOutdated(it, e) }?.let { it to e } }
    val progress = cars.downloading?.takeIf { it.first == CarRepository.ALL }?.second
    if (progress != null) {
        Label(stringResource(R.string.downloading, (progress * 100).toInt()), Typography.small, c.accent)
    } else if (outdated.isNotEmpty()) {
        if (cars.failed == CarRepository.ALL) Label(downloadError(R.string.catalog_error), Typography.small, c.danger)
        Confirmed(
            stringResource(R.string.update_all, outdated.size, outdated.sumOf { (p, e) -> cars.updateSize(p, e) } / 1e6f),
            pluralStringResource(R.plurals.update_all_confirm, outdated.size, outdated.size),
            stringResource(R.string.confirm_update),
            primary = true,
            destructive = false,
        ) { cars.updateAll(outdated) }
    }
    Confirmed(stringResource(R.string.delete_all), pluralStringResource(R.plurals.delete_all_confirm, stored, stored), stringResource(R.string.confirm_delete)) {
        prefs.carModel = null
        cars.deleteAll()
    }
}

/**
 * El catálogo: buscador, marcas (con cuántos modelos tiene cada una) y la cuadrícula filtrada.
 * Los descargados también salen: tocarlos los elige; los demás abren su vista previa.
 */
@Composable
private fun CatalogSearch(prefs: Preferences, cars: CarRepository, list: List<CatalogEntry>) {
    val c = LocalColors.current
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var make by remember { mutableStateOf<String?>(null) }
    var previewing by remember { mutableStateOf<String?>(null) }
    // Busca por marca, modelo o año, sin importar acentos ni mayúsculas.
    TextInput(query, { query = it }, stringResource(R.string.search_car), Modifier.fillMaxWidth(), icon = true)
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
    val found = sorted.filter { it.matches(make, q) }
    if (found.isEmpty()) return Label(stringResource(R.string.catalog_missing), Typography.small, c.textMuted)
    val downloaded = cars.installed.map { it.id }.toSet()
    // Un descargado no se previsualiza (ya está): si se acaba de bajar, su vista previa se cierra.
    val open = found.firstOrNull { it.id == previewing && it.id !in downloaded }
    if (open != null) {
        val progress = cars.downloading?.takeIf { it.first == open.id }?.second
        // Al terminar se elige, aunque ya se haya cerrado Ajustes.
        CarPreview(open, cars, progress, failed = cars.failed == open.id, onDownload = { cars.install(open) { prefs.carModel = open.id } }, onClose = { previewing = null })
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
                    detail = if (have) stringResource(R.string.catalog_downloaded) else stringResource(R.string.catalog_entry_detail, e.years, Formatter.formatShortFileSize(context, e.bytes)),
                    selected = prefs.carModel == e.id,
                    onClick = { if (have) prefs.carModel = e.id else previewing = e.id },
                ) { m ->
                    rememberThumbnail(cars, e)?.let { Image(it, null, m, contentScale = ContentScale.Fit) }
                }
            }
        }
    }
}

/**
 * ¿Sale [this] en el catálogo con la marca [make] (null = todas) y la búsqueda [query] (ya con [normalize])? Cada
 * palabra debe estar en la marca, el modelo o los años; un año de 4 cifras también vale si cae en su rango.
 */
internal fun CatalogEntry.matches(make: String?, query: String): Boolean {
    if (make != null && this.make != make) return false
    val text = normalize("${this.make} $name $years")
    return query.split(' ').all { it in text || it.length == 4 && it.toIntOrNull()?.let(::covers) == true }
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
            .background(c.raised)
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

/** La miniatura de [e]: null mientras llega (o si no tiene). */
@Composable
private fun rememberThumbnail(cars: CarRepository, e: CatalogEntry): ImageBitmap? =
    produceState<ImageBitmap?>(null, e.id, e.thumbnail) { value = cars.thumbnail(e) }.value

/** Un auto del catálogo abierto en la lista: su vista previa en grande, de quién es y el botón para bajarlo. */
@Composable
private fun CarPreview(e: CatalogEntry, cars: CarRepository, progress: Float?, failed: Boolean, onDownload: () -> Unit, onClose: () -> Unit) {
    val c = LocalColors.current
    val shape = RoundedCornerShape(Radius.card)
    val img = rememberThumbnail(cars, e)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(c.raised).border(2.dp, c.accent, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(2.2f), contentAlignment = Alignment.Center) {
            img?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        }
        Label("${e.make} ${e.name} ${e.years}", Typography.subtitle, maxLines = 1)
        if (e.author.isNotBlank()) Label(stringResource(R.string.model_credit, e.author, e.license), Typography.small, c.textMuted)
        // La falla de ESTA descarga, junto a su botón; se quita al reintentar.
        if (failed && progress == null) Label(downloadError(R.string.catalog_error), Typography.small, c.danger)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (progress != null) {
                Label(stringResource(R.string.downloading, (progress * 100).toInt()), Typography.small, c.accent)
            } else {
                Button(stringResource(R.string.download_mb, e.bytes / 1e6f), onDownload, primary = true)
                Button(stringResource(R.string.cancel), onClose)
            }
        }
    }
}
