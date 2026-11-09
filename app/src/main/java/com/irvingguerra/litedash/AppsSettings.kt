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
        2 -> {
            SettingsCard { MapSettings(d) }
            SettingsCard(stringResource(R.string.places), stringResource(R.string.places_help)) {
                Label(stringResource(R.string.place_home), Typography.label, c.textMuted)
                TextInput(d.prefs.homeAddress ?: "", { d.prefs.homeAddress = it.take(160).ifBlank { null } }, stringResource(R.string.place_hint), Modifier.fillMaxWidth())
                Label(stringResource(R.string.place_work), Typography.label, c.textMuted)
                TextInput(d.prefs.workAddress ?: "", { d.prefs.workAddress = it.take(160).ifBlank { null } }, stringResource(R.string.place_hint), Modifier.fillMaxWidth())
            }
        }
        else -> SettingsCard { SplitSettings(d) }
    }
}

/** Las pestañas de Ajustes → Apps (Música 0, Radio 1…). */
private const val APPS_TABS = "apps"

/** Ajustes → Apps abre en Radio (desde la radio sin nada enlazado). */
internal fun openRadioSettings() = selectTab(APPS_TABS, 1)
