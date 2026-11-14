// Ajustes → Apps: música, radio, navegación y pantalla dividida.
package com.irvingguerra.litedash

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.TextInput
import com.irvingguerra.litedash.core.ui.Typography

/** Sección Apps: la música (Archivos, Bluetooth o App), la radio, la de navegación (con Casa y Trabajo) y la pantalla dividida. */
@Composable
internal fun AppsSettings(d: Dependencies) {
    val c = LocalColors.current
    when (tabs(listOf(R.string.music, R.string.tab_radio, R.string.tab_navigation, R.string.tab_split), APPS_TABS)) {
        0 -> MusicModeSettings(d)
        1 -> RadioSettings(d)
        // Navegación: tu app con Casa y Trabajo, el aspecto del mapa de LiteDash y las zonas sin conexión.
        2 -> when (tabs(listOf(R.string.map_tab_app, R.string.map_tab_look, R.string.map_offline), NAVIGATION_TABS)) {
            0 -> {
                SettingsCard { MapSettings(d) }
                SettingsCard(stringResource(R.string.places), stringResource(R.string.places_help)) {
                    Label(stringResource(R.string.place_home), Typography.label, c.textMuted)
                    TextInput(d.prefs.homeAddress ?: "", { d.prefs.homeAddress = it.take(160).ifBlank { null } }, stringResource(R.string.place_hint), Modifier.fillMaxWidth())
                    Label(stringResource(R.string.place_work), Typography.label, c.textMuted)
                    TextInput(d.prefs.workAddress ?: "", { d.prefs.workAddress = it.take(160).ifBlank { null } }, stringResource(R.string.place_hint), Modifier.fillMaxWidth())
                }
            }
            1 -> SettingsCard(help = stringResource(R.string.map_look_help)) { MapLookSettings(d) }
            else -> SettingsCard { MapOfflineSettings(d) }
        }
        else -> SettingsCard { SplitSettings(d) }
    }
}

/** Las pestañas de Ajustes → Apps (Música 0, Radio 1…). */
private const val APPS_TABS = "apps"

/** Las de Apps → Navegación (App de mapas, Mapa de LiteDash, Sin conexión). */
private const val NAVIGATION_TABS = "navigation"

/** Ajustes → Apps abre en Radio (desde la radio sin nada enlazado). */
internal fun openRadioSettings() = selectTab(APPS_TABS, 1)

/** Ajustes → Apps abre en Música (desde Ajustes → Celular, para emparejar el Bluetooth). */
internal fun openMusicSettings() = selectTab(APPS_TABS, 0)
