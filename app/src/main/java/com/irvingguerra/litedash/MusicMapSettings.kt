package com.irvingguerra.litedash

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
import com.irvingguerra.litedash.core.ui.TextInput
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.feature.apps.App
import com.irvingguerra.litedash.feature.apps.normalize
import com.irvingguerra.litedash.feature.apps.rememberApps
import com.irvingguerra.litedash.feature.music.SourceKind
import com.irvingguerra.litedash.feature.music.label

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
 * "Fuentes de tu pantalla": qué app reproduce la USB, el Bluetooth y la radio. Cada
 * renglón dice la app enlazada (o "Sin enlazar"), con Elegir (buscador) y Quitar.
 */
@Composable
fun MusicSourcesSettings(d: Dependencies) {
    val c = LocalColors.current
    val apps = rememberApps(d.apps).orEmpty()
    var choosing by remember { mutableStateOf<SourceKind?>(null) }
    for (kind in SourceKind.entries) {
        val pkg = d.music.sourceApps[kind]
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Label(kind.label(), Typography.body, modifier = Modifier.weight(1f))
            Label(pkg?.let { p -> apps.firstOrNull { it.pkg == p }?.name ?: d.music.appName(p) } ?: stringResource(R.string.source_unlinked), Typography.small, c.textMuted, 1)
            Button(stringResource(R.string.source_choose), { choosing = if (choosing == kind) null else kind }, primary = choosing == kind)
            if (pkg != null) Button(stringResource(R.string.source_remove), { d.music.setSource(kind, null) })
        }
        if (choosing == kind) {
            Label(stringResource(R.string.source_choose_for, kind.label()), Typography.small, c.textMuted)
            AppSearch(apps, pkg) { d.music.setSource(kind, it); choosing = null }
            Spacer(Modifier.height(6.dp))
        }
        if (kind == SourceKind.BLUETOOTH) BluetoothNamePermission()
    }
}

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
