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

/** Pestañas de Ajustes → Apps (por nombre: si se reordenan, los atajos que abren una siguen bien). */
private enum class AppsTab(val title: Int) {
    MUSIC(R.string.music), RADIO(R.string.tile_radio), NAVIGATION(R.string.tab_navigation), SPLIT(R.string.tab_split),
}

/**
 * Sección Apps: la música (Archivos, Bluetooth o App), la radio, la de navegación (con Casa y Trabajo) y la pantalla
 * dividida. [requestMap]: pedir el mapa sin conexión de tu país (lleva al reporte).
 */
@Composable
internal fun AppsSettings(d: Dependencies, requestMap: () -> Unit) {
    val c = LocalColors.current
    when (AppsTab.entries[tabs(AppsTab.entries.map { it.title }, APPS_TABS).coerceAtMost(AppsTab.entries.lastIndex)]) {
        AppsTab.MUSIC -> MusicModeSettings(d)
        AppsTab.RADIO -> RadioSettings(d)
        // Navegación: tu app con Casa y Trabajo, el aspecto del mapa de LiteDash y las zonas sin conexión.
        AppsTab.NAVIGATION -> when (tabs(listOf(R.string.map_tab_app, R.string.map_tab_look, R.string.map_offline), NAVIGATION_TABS)) {
            0 -> {
                SettingsCard { MapSettings(d) }
                SettingsCard(stringResource(R.string.places), stringResource(R.string.places_help)) {
                    Label(stringResource(R.string.place_home), Typography.label, c.textMuted)
                    TextInput(d.prefs.homeAddress ?: "", { d.prefs.homeAddress = it.take(PLACE_MAX).ifBlank { null } }, stringResource(R.string.place_hint), Modifier.fillMaxWidth())
                    Label(stringResource(R.string.place_work), Typography.label, c.textMuted)
                    TextInput(d.prefs.workAddress ?: "", { d.prefs.workAddress = it.take(PLACE_MAX).ifBlank { null } }, stringResource(R.string.place_hint), Modifier.fillMaxWidth())
                }
            }
            1 -> SettingsCard(help = stringResource(R.string.map_look_help)) { MapLookSettings(d) }
            else -> SettingsCard { MapOfflineSettings(d, requestMap) }
        }
        AppsTab.SPLIT -> SettingsCard { SplitSettings(d) }
    }
}

/** Largo máximo de la dirección de Casa o Trabajo. */
private const val PLACE_MAX = 160

/** La pestaña elegida de Ajustes → Apps. */
private const val APPS_TABS = "apps"

/** Las de Apps → Navegación (App de mapas, Mapa de LiteDash, Sin conexión). */
private const val NAVIGATION_TABS = "navigation"

/** Ajustes → Apps abre en Radio (desde la radio sin nada enlazado). */
internal fun openRadioSettings() = selectTab(APPS_TABS, AppsTab.RADIO.ordinal)

/** Ajustes → Apps abre en Música (desde Ajustes → Celular, para emparejar el Bluetooth). */
internal fun openMusicSettings() = selectTab(APPS_TABS, AppsTab.MUSIC.ordinal)
