package com.irvingguerra.litedash

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Segmented
import com.irvingguerra.litedash.core.ui.TextInput
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.feature.apps.App
import com.irvingguerra.litedash.feature.apps.normalize
import com.irvingguerra.litedash.feature.apps.rememberApps
import com.irvingguerra.litedash.feature.music.MusicMode
import com.irvingguerra.litedash.feature.music.R as MusicR
import com.irvingguerra.litedash.feature.music.SoundPiece
import com.irvingguerra.litedash.feature.music.SourceKind
import com.irvingguerra.litedash.feature.music.label
import com.irvingguerra.litedash.feature.music.recipeLabel
import com.irvingguerra.litedash.feature.player.Library
import com.irvingguerra.litedash.feature.player.PlayerBrowser
import com.irvingguerra.litedash.feature.player.PlayerSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Ajustes → Apps → Música → App → "Tu app de música": la del método App, cuyo nombre sale arriba
 * de la música en grande y a la que despierta play; de las instaladas, o cualquiera con "Otra app"
 * (algunos reproductores no se anuncian como app de música). "Quitar" = ninguna: la música
 * muestra lo que suene.
 */
@Composable
fun MusicSettings(d: Dependencies) {
    val apps = rememberApps(d.apps).orEmpty()
    val chosen = d.music.preferred
    // Preguntarle a Android cuáles son de música es una consulta entre procesos: fuera del hilo principal.
    val detected by produceState(emptySet<String>(), d.music) { value = withContext(Dispatchers.IO) { d.music.musicApps() } }
    val musicApps = remember(apps, chosen, detected) {
        val musicPackages = detected + MUSIC_PACKAGES + listOfNotNull(chosen)
        apps.filter { it.pkg in musicPackages }.distinctBy { it.pkg }
    }
    AppPicker(
        apps, musicApps.map { it.pkg to it.name }, chosen, d.music::choose, { d.music.choose(it.pkg) },
        onClear = chosen?.let { { d.music.choose(null) } },
    )
    Label(stringResource(R.string.music_help), Typography.small, LocalColors.current.textMuted)
}

/**
 * Ajustes → Apps → Música: el método de música, uno solo a la vez (Irving, 2026-11-08): Archivos (el
 * reproductor de LiteDash), Bluetooth (el celular) o App. La pestaña elegida es la que suena en la
 * pieza Música; debajo, sus ajustes.
 */
@Composable
fun MusicModeSettings(d: Dependencies) {
    SettingsCard(stringResource(R.string.music_mode_title), stringResource(R.string.music_mode_help)) {
        Segmented(MusicMode.entries.map { it to it.label() }, d.music.mode, { d.music.mode = it }, Modifier.fillMaxWidth(), check = true)
        // El celular conectado presta el Bluetooth: que no parezca que el método cambió solo.
        if (d.music.phoneHolds) Label(stringResource(R.string.music_mode_phone), Typography.small, LocalColors.current.textMuted)
    }
    when (d.music.mode) {
        MusicMode.FILES -> {
            // La biblioteca siempre aquí: en el inicio solo cabe en un widget de media pantalla o más.
            // Una sola tarjeta: la biblioteca trae arriba "Elegir carpeta" (y "Quitar carpeta") para las pantallas
            // que no dejan leer la memoria sola.
            SettingsCard(stringResource(R.string.files_library), stringResource(R.string.files_library_help)) {
                PlayerBrowser(PlayerSource.USB, onPicked = {}, Modifier.fillMaxWidth().height(LIBRARY_HEIGHT))
            }
        }
        MusicMode.BLUETOOTH -> {
            SettingsCard(stringResource(R.string.bt_pair), stringResource(R.string.bt_pair_help)) { BluetoothSettings(d) }
            // La calibración de la música es la del Bluetooth: solo con ese método (la de la radio, en la pestaña Radio).
            SoundCalibrationCard(d, SoundPiece.MUSIC)
        }
        MusicMode.APP -> SettingsCard(stringResource(R.string.music_app_title)) { MusicSettings(d) }
    }
}

/**
 * "Calibrar sonido" de [piece] (el Bluetooth en Música, la FM en Radio): con qué receta suena en esta pantalla (la
 * calibración la encuentra sola la primera vez que suena) y "Calibrar otra vez", que olvida solo lo guardado de esa
 * pieza. Solo si hay algo que calibrar.
 */
@Composable
private fun SoundCalibrationCard(d: Dependencies, piece: SoundPiece) {
    if (!d.music.calibrates(piece)) return
    SettingsCard(stringResource(R.string.sound_calibration), stringResource(R.string.sound_calibration_help)) {
        val name = stringResource(if (piece == SoundPiece.MUSIC) MusicR.string.source_bluetooth else MusicR.string.radio)
        val recipe = d.music.soundRecipe(piece)
        Label(stringResource(R.string.sound_recipe_line, name, recipeLabel(piece, recipe)), Typography.body)
        if (recipe != null) Button(stringResource(R.string.sound_calibrate_again), { d.music.recalibrate(piece) })
    }
}

/** Alto de la biblioteca en Ajustes (la página ya desplaza: la lista necesita uno fijo). */
private val LIBRARY_HEIGHT = 420.dp

/**
 * Pestaña "Radio" de Ajustes → Apps: una sola radio a la vez, como la música (Irving, 2026-11-08):
 * la FM de la pantalla (su app, lo principal: muchas veces no hay internet en el auto) o la radio
 * por internet de LiteDash. La elegida lleva palomita y es la que suena en el widget Radio.
 */
@Composable
fun RadioSettings(d: Dependencies) {
    val apps = rememberApps(d.apps).orEmpty()
    val setInternet = rememberInternetRadioToggle(d)
    val internet = d.music.radioInternet
    SettingsCard(stringResource(R.string.radio_mode_title), stringResource(R.string.radio_mode_help)) {
        Segmented(
            listOf(false to stringResource(MusicR.string.radio_fm), true to stringResource(MusicR.string.radio_internet)),
            internet,
            { on -> if (on != internet) setInternet(on) },
            Modifier.fillMaxWidth(),
            check = true,
        )
        // La radio por internet no tiene más ajustes: lo que hace, aquí mismo (sin tarjeta vacía).
        if (internet) Label(stringResource(R.string.radio_internet_help), Typography.small, LocalColors.current.textMuted)
    }
    if (!internet) {
        SettingsCard(stringResource(R.string.radio_fm_app), stringResource(R.string.radio_fm_app_help)) {
            RadioAppPicker(d, apps)
        }
        SoundCalibrationCard(d, SoundPiece.RADIO)
    }
}

/**
 * La app de la FM de la pantalla, con el mismo selector que las demás: la enlazada con palomita (o «Sin enlazar»),
 * «Elegir» (abre el buscador) y «Quitar».
 */
@Composable
private fun RadioAppPicker(d: Dependencies, apps: List<App>) {
    val pkg = d.music.sourceApps[SourceKind.RADIO]
    // Una app que ya no está (o sin ícono de inicio) no tiene nombre: su paquete, para que no quede en blanco.
    val linked = pkg?.let { p -> p to (apps.firstOrNull { it.pkg == p }?.name ?: d.music.appName(p).ifEmpty { p }) }
    if (linked == null) Label(stringResource(R.string.source_unlinked), Typography.body, LocalColors.current.textMuted)
    AppPicker(
        apps, listOfNotNull(linked), pkg, {}, { d.music.setSource(SourceKind.RADIO, it.pkg) },
        onClear = pkg?.let { { d.music.setSource(SourceKind.RADIO, null) } },
        other = stringResource(R.string.source_choose),
        searchHelp = stringResource(R.string.source_choose_for),
    )
}

/**
 * Cambiar la radio entre internet (true) y la FM de la pantalla: la pieza Radio queda con esa y
 * la otra se calla. Al pasar a internet, desde Android 13 pide el permiso de avisos.
 */
@Composable
private fun rememberInternetRadioToggle(d: Dependencies): (Boolean) -> Unit {
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    return { on ->
        d.music.radioInternet = on
        val notifications = Library.noticePermission
        if (on && notifications.isNotEmpty()) ask.launch(notifications.toTypedArray())
    }
}

/** A lo más tantas apps encontradas en el buscador. */
private const val SEARCH_MAX = 12

/**
 * Buscador de apps instaladas: escribir y tocar una la elige (la app exacta, con su actividad). [selected] (su
 * [App.key] o solo su paquete) va con palomita.
 */
@Composable
internal fun AppSearch(apps: List<App>, selected: String?, onPick: (App) -> Unit) {
    var query by remember { mutableStateOf("") }
    // Los nombres se normalizan una vez, no en cada tecla.
    val names = remember(apps) { apps.map { it to normalize(it.name) } }
    TextInput(query, { query = it }, stringResource(R.string.search_app), Modifier.fillMaxWidth(), icon = true)
    val q = normalize(query.trim())
    if (q.isNotEmpty()) {
        val hits = names.filter { q in it.second }.take(SEARCH_MAX).map { (app, _) -> app }
        val marked = selected?.let { sel -> hits.firstOrNull { it.key == sel } ?: hits.firstOrNull { it.pkg == sel } }?.key
        if (hits.isNotEmpty()) {
            Segmented<String?>(hits.map { it.key to it.name }, marked, { key -> hits.firstOrNull { it.key == key }?.let(onPick) }, check = true)
        }
    }
}

/**
 * Elegir una app (Música → App, Radio, Tu app de mapas, Android Auto / CarPlay), igual en todos: las encontradas
 * [found] (id y nombre) con la elegida [current] marcada con palomita, como los selectores de modo; [other] («Otra
 * app…» o «Elegir») abre el buscador de [apps] para cualquiera (algunas no se anuncian) y, si hay [onClear], «Quitar».
 * Los dos son acciones, no elecciones: nunca se pintan como la elegida.
 */
@Composable
internal fun AppPicker(
    apps: List<App>,
    found: List<Pair<String, String>>,
    current: String?,
    onPick: (String) -> Unit,
    onSearch: (App) -> Unit,
    onClear: (() -> Unit)? = null,
    other: String = stringResource(R.string.music_other_app),
    searchHelp: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    if (found.isNotEmpty()) Segmented<String?>(found, current, { id -> id?.let(onPick) }, check = true)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(other, { open = !open })
        if (onClear != null) Button(stringResource(R.string.remove), onClear)
    }
    if (open) {
        searchHelp?.let { Label(it, Typography.small, LocalColors.current.textMuted) }
        AppSearch(apps, current) { app ->
            onSearch(app)
            open = false
        }
    }
}

/**
 * Ajustes → Apps → Navegación → "Tu app de mapas" (con "App de mapas" elegido): las apps de navegación instaladas
 * (las conocidas primero y luego cualquiera que abra una dirección) y «Otra app…» para la que no se anuncia; la marcada
 * es la que se abre. Solo se compone con «App de mapas» elegido: con el mapa de LiteDash no se guarda nada al mirar.
 */
@Composable
fun MapAppSettings(d: Dependencies) {
    val context = LocalContext.current
    val apps = rememberApps(d.apps).orEmpty()
    // Se vuelve a buscar cuando cambian las apps instaladas.
    val detected by produceState(emptySet<String>(), apps) { value = withContext(Dispatchers.IO) { navigationPackages(context) } }
    val selected = mapApp(d, apps)
    val mapApps = remember(apps, detected, selected) {
        val known = NAVIGATION_PACKAGES.mapNotNull { pkg -> apps.firstOrNull { it.pkg == pkg } }
        val found = known + apps.filter { it.pkg in detected && known.none { k -> k.pkg == it.pkg } }.distinctBy { it.pkg }
        // La elegida (también con «Otra app…») se ve marcada: en su lugar si su app ya está, si no al final.
        if (selected == null) found
        else found.map { if (it.pkg == selected.pkg) selected else it } + listOfNotNull(selected.takeIf { s -> found.none { it.pkg == s.pkg } })
    }
    // Sin ninguna elegida ni conocida instalada (solo Vela Maps, por ejemplo): queda la primera que se encontró, para
    // que "App de mapas" sí abra una. Fuera de la composición: escribe un ajuste.
    LaunchedEffect(selected, mapApps) {
        if (selected == null) mapApps.firstOrNull()?.let { d.prefs.mapApp = it.key }
    }
    AppPicker(apps, mapApps.map { it.key to it.name }, selected?.key, { d.prefs.mapApp = it }, { d.prefs.mapApp = it.key })
    Label(
        stringResource(if (mapApps.isEmpty()) R.string.map_no_apps else R.string.map_help),
        Typography.small,
        LocalColors.current.textMuted,
    )
    // Sin el acceso a avisos no llegan las vueltas de tu app de mapas: el mismo ajuste de Android que Módulos → Permisos.
    // Se vuelve a revisar al regresar a LiteDash (se da allá).
    var notifications by remember { mutableStateOf(true) }
    LifecycleResumeEffect(d.music) {
        notifications = d.music.hasPermission()
        onPauseOrDispose {}
    }
    if (mapApps.isNotEmpty() && !notifications) Button(stringResource(R.string.map_allow_notifications), { d.music.requestPermission() }, primary = true)
}

/** Las apps instaladas que abren una dirección ("geo:") o son de mapas. Consulta a Android: fuera del hilo principal. */
private fun navigationPackages(context: Context): Set<String> {
    val pm = context.packageManager
    val asks = listOf(
        Intent(Intent.ACTION_VIEW, "geo:0,0?q=x".toUri()),
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MAPS),
    )
    return asks.flatMapTo(HashSet()) { intent ->
        runCatching { pm.queryIntentActivities(intent, 0) }.getOrDefault(emptyList()).map { it.activityInfo.packageName }
    }
}
