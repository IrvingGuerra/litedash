package com.irvingguerra.litedash

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
import com.irvingguerra.litedash.feature.music.SourceKind
import com.irvingguerra.litedash.feature.music.label
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
    var other by remember { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (app in musicApps) Button(app.name, { d.music.choose(app.pkg) }, primary = chosen == app.pkg)
        Button(stringResource(R.string.music_other_app), { other = !other }, primary = other)
        if (chosen != null) Button(stringResource(R.string.source_remove), { d.music.choose(null) })
    }
    if (other) AppSearch(apps, chosen) { d.music.choose(it); other = false }
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
        MusicMode.BLUETOOTH -> SettingsCard(stringResource(R.string.bt_pair), stringResource(R.string.bt_pair_help)) { BluetoothSettings(d) }
        MusicMode.APP -> SettingsCard(stringResource(R.string.music_app_title)) { MusicSettings(d) }
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
    var choosing by remember { mutableStateOf(false) }
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
    }
    if (internet) {
        SettingsCard(stringResource(R.string.module_radio), stringResource(R.string.radio_internet_help)) {}
    } else {
        SettingsCard(stringResource(R.string.radio_fm_app), stringResource(R.string.radio_fm_app_help)) {
            RadioAppRow(d, apps, choosing) { choosing = it }
        }
    }
}

/**
 * Renglón de la FM de la pantalla: la app enlazada (o "Sin enlazar"), Elegir (abre el buscador
 * debajo) y Quitar. [choosing] dice si el buscador está abierto.
 */
@Composable
private fun RadioAppRow(d: Dependencies, apps: List<App>, choosing: Boolean, onChoosing: (Boolean) -> Unit) {
    val c = LocalColors.current
    val name = stringResource(MusicR.string.source_radio)
    val pkg = d.music.sourceApps[SourceKind.RADIO]
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Label(name, Typography.body, modifier = Modifier.weight(1f))
        Label(pkg?.let { p -> apps.firstOrNull { it.pkg == p }?.name ?: d.music.appName(p) } ?: stringResource(R.string.source_unlinked), Typography.small, c.textMuted, 1)
        Button(stringResource(R.string.source_choose), { onChoosing(!choosing) }, primary = choosing)
        if (pkg != null) Button(stringResource(R.string.source_remove), { d.music.setSource(SourceKind.RADIO, null) })
    }
    if (choosing) {
        Label(stringResource(R.string.source_choose_for, name), Typography.small, c.textMuted)
        AppSearch(apps, pkg) { d.music.setSource(SourceKind.RADIO, it); onChoosing(false) }
        Spacer(Modifier.height(6.dp))
    }
}

/**
 * Cambiar la radio entre internet (true) y la FM de la pantalla: la pieza Radio queda con esa y
 * la otra se calla. Al pasar a internet, desde Android 13 pide el permiso de avisos.
 */
@Composable
fun rememberInternetRadioToggle(d: Dependencies): (Boolean) -> Unit {
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    return { on ->
        d.music.radioInternet = on
        val notifications = Library.noticePermission
        if (on && notifications.isNotEmpty()) ask.launch(notifications.toTypedArray())
    }
}


/** Buscador de apps instaladas: escribir y tocar una la elige ([selected] va resaltada). */
@Composable
internal fun AppSearch(apps: List<App>, selected: String?, onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    TextInput(query, { query = it }, stringResource(R.string.search_app), Modifier.fillMaxWidth(), icon = true)
    val q = normalize(query.trim())
    if (q.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (app in apps.filter { q in normalize(it.name) }.take(12)) {
                Button(app.name, { onPick(app.pkg) }, primary = selected == app.pkg)
            }
        }
    }
}

/** Sección "Mapa" de Ajustes: qué app de navegación abren el widget Mapa y el volante. */
@Composable
fun MapSettings(d: Dependencies) {
    val apps = rememberApps(d.apps).orEmpty()
    val mapApps = remember(apps) { NAVIGATION_PACKAGES.mapNotNull { pkg -> apps.firstOrNull { it.pkg == pkg } } }
    val selected = mapApp(d, apps)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (app in mapApps) Button(app.name, { d.prefs.mapApp = app.key }, primary = app == selected)
    }
    Label(
        stringResource(if (mapApps.isEmpty()) R.string.map_no_apps else R.string.map_help),
        Typography.small,
        LocalColors.current.textMuted,
    )
}
