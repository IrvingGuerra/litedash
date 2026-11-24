// Ajustes → Apps: música, radio, navegación, Android Auto / CarPlay y pantalla dividida.
package com.irvingguerra.litedash

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Segmented
import com.irvingguerra.litedash.core.ui.TextInput
import com.irvingguerra.litedash.core.ui.Typography

/** Pestañas de Ajustes → Apps (por nombre: si se reordenan o se esconde una, los atajos que abren una siguen bien). */
private enum class AppsTab(val title: Int) {
    MUSIC(R.string.music), RADIO(R.string.tile_radio), NAVIGATION(R.string.tab_navigation), PROJECTION(R.string.tile_projection),
    SPLIT(R.string.tab_split),
}

/** Las que se ven: Pantalla dividida solo donde Android la deja (como un módulo que no está, desaparece). */
private val shownAppsTabs by lazy { AppsTab.entries.filter { it != AppsTab.SPLIT || SplitScreen.supported() } }

/** Ajustes → Apps abre en [tab] (por su lugar entre las que se ven). */
private fun selectAppsTab(tab: AppsTab) = selectTab(APPS_TABS, shownAppsTabs.indexOf(tab).coerceAtLeast(0))

/**
 * Sección Apps: la música (Archivos, Bluetooth o App), la radio, la de navegación (con Casa y Trabajo), la de
 * Android Auto / CarPlay y la pantalla dividida. [requestMap]: pedir el mapa sin conexión de tu país (lleva al reporte).
 */
@Composable
internal fun AppsSettings(d: Dependencies, requestMap: () -> Unit) {
    val shown = shownAppsTabs
    when (shown[tabs(shown.map { it.title }, APPS_TABS).coerceIn(0, shown.lastIndex)]) {
        AppsTab.MUSIC -> MusicModeSettings(d)
        AppsTab.RADIO -> RadioSettings(d)
        AppsTab.NAVIGATION -> NavigationSettings(d, requestMap)
        AppsTab.PROJECTION -> SettingsCard(stringResource(R.string.projection_app_title)) { ProjectionSettings(d) }
        AppsTab.SPLIT -> SettingsCard { SplitSettings(d) }
    }
}

/**
 * Pestaña Navegación, como Música y Radio: arriba con qué se navega (el mapa de LiteDash o tu app de mapas, un solo
 * camino: [Preferences.mapExternal]) y debajo lo de esa opción; luego Casa y Trabajo y, en los dos modos, el aspecto
 * del mapa de LiteDash y sus mapas sin conexión (el widget Mapa lo dibuja aunque el toque abra tu app).
 */
@Composable
private fun NavigationSettings(d: Dependencies, requestMap: () -> Unit) {
    val c = LocalColors.current
    val external = d.prefs.mapExternal
    SettingsCard(stringResource(R.string.map_mode_title), stringResource(if (external) R.string.map_mode_app_help else R.string.map_mode_own_help)) {
        Segmented(
            listOf(false to stringResource(R.string.map_tab_look), true to stringResource(R.string.map_tab_app)),
            external,
            { d.prefs.mapExternal = it },
            Modifier.fillMaxWidth(),
            check = true,
        )
    }
    if (external) SettingsCard(stringResource(R.string.map_app_title)) { MapAppSettings(d) }
    SettingsCard(stringResource(R.string.places), stringResource(R.string.places_help)) {
        Label(stringResource(R.string.place_home), Typography.label, c.textMuted)
        TextInput(d.prefs.homeAddress ?: "", { d.prefs.homeAddress = it.take(PLACE_MAX).ifBlank { null } }, stringResource(R.string.place_hint), Modifier.fillMaxWidth())
        Label(stringResource(R.string.place_work), Typography.label, c.textMuted)
        TextInput(d.prefs.workAddress ?: "", { d.prefs.workAddress = it.take(PLACE_MAX).ifBlank { null } }, stringResource(R.string.place_hint), Modifier.fillMaxWidth())
    }
    // «Aspecto del mapa», no «Mapa de LiteDash»: ese nombre es la opción de arriba, y estas pestañas valen en los dos
    // modos (el widget Mapa siempre lo dibuja LiteDash).
    when (tabs(listOf(R.string.map_tab_style, R.string.map_offline), NAVIGATION_TABS)) {
        LOOK_TAB -> SettingsCard(help = stringResource(R.string.map_look_help)) { MapLookSettings(d) }
        else -> SettingsCard { MapOfflineSettings(d, requestMap) }
    }
}

/** Largo máximo de la dirección de Casa o Trabajo. */
private const val PLACE_MAX = 160

/** La pestaña elegida de Ajustes → Apps. */
private const val APPS_TABS = "apps"

/** Las del mapa propio en Apps → Navegación (Aspecto del mapa, Sin conexión). */
private const val NAVIGATION_TABS = "navigation"

/** El aspecto del mapa, la primera de [NAVIGATION_TABS]; Sin conexión, la segunda. */
private const val LOOK_TAB = 0
private const val OFFLINE_TAB = 1

/** Ajustes → Apps abre en Radio (desde la radio sin nada enlazado). */
internal fun openRadioSettings() = selectAppsTab(AppsTab.RADIO)

/** Ajustes → Apps abre en Navegación → Aspecto del mapa (desde el engrane del widget Mapa). */
internal fun openMapLook() {
    selectAppsTab(AppsTab.NAVIGATION)
    selectTab(NAVIGATION_TABS, LOOK_TAB)
}

/** Ajustes → Apps abre en Navegación → Sin conexión (desde la tarjeta de la ruta, para bajar el mapa de tu estado, y desde el engrane del widget Mapa). */
internal fun openOfflineMaps() {
    selectAppsTab(AppsTab.NAVIGATION)
    selectTab(NAVIGATION_TABS, OFFLINE_TAB)
}

/** Ajustes → Apps abre en Android Auto / CarPlay (desde su botón de la barra, el widget o el volante sin app). */
internal fun openProjectionSettings() = selectAppsTab(AppsTab.PROJECTION)

/** Ajustes → Apps abre en Música (desde Ajustes → Celular, para emparejar el Bluetooth). */
internal fun openMusicSettings() = selectAppsTab(AppsTab.MUSIC)
