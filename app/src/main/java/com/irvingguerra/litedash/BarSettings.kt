package com.irvingguerra.litedash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Radius
import com.irvingguerra.litedash.core.ui.Segmented
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.feature.apps.AppIcon
import com.irvingguerra.litedash.feature.apps.rememberApps

/** El nombre de cada botón que se puede agregar a la barra (Ajustes no: siempre va). */
private fun BarItem.title(): Int? = when (this) {
    BarItem.APPS -> R.string.bar_item_apps
    BarItem.MUSIC -> R.string.bar_item_music
    BarItem.VOLUME -> R.string.bar_item_volume
    BarItem.NAVIGATE -> R.string.bar_item_navigate
    BarItem.BLUETOOTH -> R.string.bar_item_bluetooth
    BarItem.PHONE -> R.string.bar_item_phone
    BarItem.VOICE -> R.string.bar_item_voice
    BarItem.FAVORITES -> R.string.bar_item_favorites
    BarItem.SETTINGS -> null
}

/**
 * La barra de apps (botón "Barra" al acomodarla): agregar los botones que no tiene, tus apps favoritas,
 * dónde va, su tamaño y, abajo, si es flotante o a lo ancho. Quitar y ordenar se hace en la barra misma.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BarSettingsPanel(d: Dependencies, close: () -> Unit) {
    val c = LocalColors.current
    val prefs = d.prefs
    // La voz solo si la pantalla trae un asistente; Ajustes ya va siempre.
    val context = LocalContext.current
    val voice = remember { voiceIntent(context) != null }
    val missing = BarItem.entries.filter { it != BarItem.SETTINGS && it !in prefs.barItems && (it != BarItem.VOICE || voice) }
    PanelScaffold(stringResource(R.string.bar_position), close) {
        Column(Modifier.weight(1f).widthIn(max = 720.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Label(stringResource(R.string.bar_edit_help), Typography.body, c.textMuted)
            if (missing.isNotEmpty()) {
                Label(stringResource(R.string.bar_add).uppercase(), Typography.label, c.textMuted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Se agrega antes de Ajustes; luego se arrastra a donde se quiera.
                    for (item in missing) item.title()?.let { title -> Button("+ " + stringResource(title), { prefs.barItems = prefs.barItems + item }) }
                }
            }
            if (BarItem.FAVORITES in prefs.barItems) {
                Label(stringResource(R.string.bar_item_favorites).uppercase(), Typography.label, c.textMuted)
                Favorites(d)
            }
            Label(stringResource(R.string.bar_place).uppercase(), Typography.label, c.textMuted)
            Segmented(
                listOf(
                    BarPosition.AUTO to stringResource(R.string.bar_auto),
                    BarPosition.SIDE to stringResource(R.string.bar_side),
                    BarPosition.RIGHT to stringResource(R.string.bar_right),
                    BarPosition.BOTTOM to stringResource(R.string.bar_bottom),
                ),
                prefs.barPosition,
                { prefs.barPosition = it },
            )
            Label(stringResource(R.string.bar_position_help), Typography.small, c.textMuted)
            if (prefs.barPosition != BarPosition.SIDE && prefs.barPosition != BarPosition.RIGHT) {
                Label(stringResource(R.string.bar_style).uppercase(), Typography.label, c.textMuted)
                Segmented(
                    listOf(BarStyle.FLOATING to stringResource(R.string.bar_style_floating), BarStyle.FULL to stringResource(R.string.bar_style_full)),
                    prefs.barStyle,
                    { prefs.barStyle = it },
                )
            }
            Label(stringResource(R.string.bar_size), Typography.label, c.textMuted)
            Segmented(
                listOf(BarSize.SMALL to stringResource(R.string.bar_size_small), BarSize.NORMAL to stringResource(R.string.bar_size_normal), BarSize.LARGE to stringResource(R.string.bar_size_large)),
                prefs.barSize,
                { prefs.barSize = it },
            )
        }
    }
}

/** Tus apps favoritas de la barra: quitar con la x y agregar buscándolas. */
@Composable
private fun Favorites(d: Dependencies) {
    val apps = rememberApps(d.apps).orEmpty()
    val favorites = remember(apps, d.prefs.dock, d.music.preferred) { dockFavorites(d, apps) }
    Label(stringResource(R.string.favorites_help), Typography.small, LocalColors.current.textMuted)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (app in favorites) {
            Row(
                Modifier.clip(RoundedCornerShape(Radius.inner)).background(LocalColors.current.surfaceHigh).padding(start = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(d.apps, app, 32.dp)
                Label(app.name, Typography.body, maxLines = 1, modifier = Modifier.padding(horizontal = 10.dp).widthIn(max = 160.dp))
                IconButton(Icons.close, { d.prefs.dock = favorites.map { it.key } - app.key }, size = 44.dp)
            }
        }
    }
    // Escribir y tocar una la pone al final (si ya está, no se repite).
    AppSearch(apps.filter { app -> favorites.none { it.key == app.key } }, null) { pkg ->
        apps.firstOrNull { it.pkg == pkg }?.let { app -> d.prefs.dock = favorites.map { it.key } + app.key }
    }
}

