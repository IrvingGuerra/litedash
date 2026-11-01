package com.irvingguerra.litedash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.feature.apps.rememberApps

/**
 * Sección "Música" de Ajustes: la app de música que se controla y se abre, de
 * las instaladas; "Sistema" sigue lo que suene (radio, USB, Bluetooth o una app).
 */
@Composable
fun ColumnScope.MusicSettings(d: Dependencies) {
    val apps = rememberApps(d.apps).orEmpty()
    val installedApps = remember(apps) {
        val musicPackages = d.music.musicApps() + MUSIC_PACKAGES
        apps.filter { it.pkg in musicPackages }.distinctBy { it.pkg }
    }
    val choose = { pkg: String? ->
        d.prefs.musicApp = pkg
        d.music.preferred = pkg
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(stringResource(R.string.music_system), { choose(null) }, primary = d.prefs.musicApp == null)
        for (app in installedApps) Button(app.name, { choose(app.pkg) }, primary = d.prefs.musicApp == app.pkg)
    }
    Label(stringResource(R.string.music_help), Typography.small, LocalColors.current.textMuted)
}

/** Sección "Mapa" de Ajustes: qué app de navegación abre la pieza Navegar. */
@Composable
fun ColumnScope.MapSettings(d: Dependencies) {
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
