package com.irvingguerra.litedash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.feature.apps.normalize
import com.irvingguerra.litedash.feature.apps.rememberApps

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
    val choose = { pkg: String? ->
        d.prefs.musicApp = pkg
        d.music.preferred = pkg
    }
    var other by remember { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(stringResource(R.string.music_system), { choose(null) }, primary = chosen == null)
        for (app in musicApps) Button(app.name, { choose(app.pkg) }, primary = chosen == app.pkg)
        Button(stringResource(R.string.music_other_app), { other = !other }, primary = other)
    }
    if (other) {
        var query by remember { mutableStateOf("") }
        TextInput(query, { query = it }, stringResource(R.string.search_app), Modifier.fillMaxWidth(), icon = true)
        val q = normalize(query.trim())
        if (q.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (app in apps.filter { q in normalize(it.name) }.take(12)) {
                    Button(app.name, { choose(app.pkg); other = false }, primary = chosen == app.pkg)
                }
            }
        }
    }
    Label(stringResource(R.string.music_help), Typography.small, LocalColors.current.textMuted)
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
