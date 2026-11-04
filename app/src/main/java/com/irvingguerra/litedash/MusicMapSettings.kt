package com.irvingguerra.litedash

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.alpha
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.SwitchRow
import com.irvingguerra.litedash.core.ui.TextInput
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.feature.apps.App
import com.irvingguerra.litedash.feature.apps.normalize
import com.irvingguerra.litedash.feature.apps.rememberApps
import com.irvingguerra.litedash.feature.music.SourceKind
import com.irvingguerra.litedash.feature.music.label
import com.irvingguerra.litedash.feature.player.Library

/**
 * Sección "Música" de Ajustes: la app de música que se controla y se abre, de
 * las instaladas; "Sistema" sigue lo que suene (radio, USB, Bluetooth o una app).
 * Los reproductores de USB y Bluetooth de muchas pantallas no se anuncian como app
 * de música: con "Otra app" se busca y elige cualquiera.
 */
@Composable
fun MusicSettings(d: Dependencies) {
    val apps = rememberApps(d.apps).orEmpty()
    val chosen = d.prefs.musicApp
    val musicApps = remember(apps, chosen) {
        val musicPackages = d.music.musicApps() + MUSIC_PACKAGES + listOfNotNull(chosen)
        apps.filter { it.pkg in musicPackages }.distinctBy { it.pkg }
    }
    var other by remember { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(stringResource(R.string.music_system), { d.music.choose(null) }, primary = chosen == null)
        for (app in musicApps) Button(app.name, { d.music.choose(app.pkg) }, primary = chosen == app.pkg)
        Button(stringResource(R.string.music_other_app), { other = !other }, primary = other)
    }
    if (other) AppSearch(apps, chosen) { d.music.choose(it); other = false }
    Label(stringResource(R.string.music_help), Typography.small, LocalColors.current.textMuted)
}

/**
 * "Fuentes de tu pantalla": qué app reproduce la USB y el Bluetooth. Cada renglón dice
 * la app enlazada (o "Sin enlazar"), con Elegir (buscador) y Quitar. La radio va aparte,
 * en su pestaña ([RadioSettings]).
 */
@Composable
fun MusicSourcesSettings(d: Dependencies) {
    val apps = rememberApps(d.apps).orEmpty()
    var choosing by remember { mutableStateOf<SourceKind?>(null) }
    for (kind in listOf(SourceKind.USB, SourceKind.BLUETOOTH)) {
        // Con el reproductor USB de LiteDash prendido, la app de USB no se usa: se ve gris y lo dice.
        val ownUsb = kind == SourceKind.USB && d.prefs.usbPlayer
        Box(Modifier.alpha(if (ownUsb) 0.45f else 1f)) {
            Column { SourceRow(d, kind, apps, choosing == kind && !ownUsb, enabled = !ownUsb) { choosing = if (it) kind else null } }
        }
        if (ownUsb) Label(stringResource(R.string.source_usb_own_player), Typography.small, LocalColors.current.textMuted)
        if (kind == SourceKind.BLUETOOTH) BluetoothNamePermission()
    }
}

/**
 * Pestaña "Radio" de Ajustes → Apps: la app de radio FM de la pantalla (lo principal; muchas
 * veces no hay internet en el auto) y, opcional, la radio por internet de LiteDash.
 */
@Composable
fun RadioSettings(d: Dependencies) {
    val apps = rememberApps(d.apps).orEmpty()
    var choosing by remember { mutableStateOf(false) }
    SettingsCard(stringResource(R.string.radio_fm_app), stringResource(R.string.radio_fm_app_help)) {
        SourceRow(d, SourceKind.RADIO, apps, choosing) { choosing = it }
    }
    SettingsCard { InternetRadioSwitch(d) }
}

/**
 * Renglón de una fuente de la pantalla: su nombre, la app enlazada (o "Sin enlazar"),
 * Elegir (abre el buscador debajo) y Quitar. [choosing] dice si el buscador está abierto.
 */
@Composable
private fun SourceRow(d: Dependencies, kind: SourceKind, apps: List<App>, choosing: Boolean, enabled: Boolean = true, onChoosing: (Boolean) -> Unit) {
    val c = LocalColors.current
    val pkg = d.music.sourceApps[kind]
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Label(kind.label(), Typography.body, modifier = Modifier.weight(1f))
        Label(pkg?.let { p -> apps.firstOrNull { it.pkg == p }?.name ?: d.music.appName(p) } ?: stringResource(R.string.source_unlinked), Typography.small, c.textMuted, 1)
        Button(stringResource(R.string.source_choose), { if (enabled) onChoosing(!choosing) }, primary = choosing)
        if (pkg != null) Button(stringResource(R.string.source_remove), { if (enabled) d.music.setSource(kind, null) })
    }
    if (choosing) {
        Label(stringResource(R.string.source_choose_for, kind.label()), Typography.small, c.textMuted)
        AppSearch(apps, pkg) { d.music.setSource(kind, it); onChoosing(false) }
        Spacer(Modifier.height(6.dp))
    }
}

/** Interruptor del reproductor USB propio (Ajustes → Apps → Música). */
@Composable
fun UsbPlayerSwitch(d: Dependencies) {
    val set = rememberUsbPlayerToggle(d)
    SwitchRow(stringResource(R.string.module_usb_player), d.prefs.usbPlayer, set, stringResource(R.string.module_usb_player_help))
}

/** Interruptor de la radio por internet (Ajustes → Apps → Radio). */
@Composable
fun InternetRadioSwitch(d: Dependencies) {
    val set = rememberInternetRadioToggle(d)
    SwitchRow(stringResource(R.string.module_radio), d.prefs.internetRadio, set, stringResource(R.string.radio_internet_help))
}

/**
 * Prender o apagar el reproductor USB: al prender pide el permiso de leer la música (y desde
 * Android 13 el de avisos, para el del reproductor); al apagar, si sonaba, se para. Lo usan
 * Ajustes → Apps y Módulos.
 */
@Composable
fun rememberUsbPlayerToggle(d: Dependencies): (Boolean) -> Unit {
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    return { on ->
        d.prefs.usbPlayer = on
        OwnPlayer.refresh(d.music, d.prefs)
        if (on) ask.launch((listOf(Library.permission) + notificationPermission()).toTypedArray())
    }
}

/** Prender o apagar la radio por internet: al prender, desde Android 13 pide el permiso de avisos. */
@Composable
fun rememberInternetRadioToggle(d: Dependencies): (Boolean) -> Unit {
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    return { on ->
        d.prefs.internetRadio = on
        OwnPlayer.refresh(d.music, d.prefs)
        val notifications = notificationPermission()
        if (on && notifications.isNotEmpty()) ask.launch(notifications.toTypedArray())
    }
}

/** El permiso de avisos del reproductor, que solo existe desde Android 13. */
private fun notificationPermission() =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

/**
 * Desde Android 12 ver el nombre del celular conectado pide BLUETOOTH_CONNECT (el mismo
 * permiso del Taller): un botón para darlo si aún no está.
 */
@Composable
private fun BluetoothNamePermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) }
    if (granted) return
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    Button(stringResource(R.string.bluetooth_name_permission), { ask.launch(Manifest.permission.BLUETOOTH_CONNECT) }, Modifier.padding(bottom = 6.dp))
}

/** Buscador de apps instaladas: escribir y tocar una la elige ([selected] va resaltada). */
@Composable
private fun AppSearch(apps: List<App>, selected: String?, onPick: (String) -> Unit) {
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

/** Sección "Mapa" de Ajustes: qué app de navegación abre la pieza Navegar. */
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
