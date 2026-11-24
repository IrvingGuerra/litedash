package com.irvingguerra.litedash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Radius
import com.irvingguerra.litedash.core.ui.Segmented
import com.irvingguerra.litedash.core.ui.SwitchRow
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.feature.apps.App
import com.irvingguerra.litedash.feature.apps.AppIcon
import com.irvingguerra.litedash.feature.apps.rememberApps

/** El nombre de cada botón que se puede agregar a la barra (Ajustes no: siempre va). */
internal fun BarItem.title(): Int? = when (this) {
    BarItem.HOME -> R.string.bar_item_home
    BarItem.APPS -> R.string.bar_item_apps
    BarItem.MUSIC -> R.string.bar_item_music
    BarItem.VOLUME -> R.string.bar_item_volume
    BarItem.NAVIGATE -> R.string.bar_item_navigate
    BarItem.BLUETOOTH -> R.string.bar_item_bluetooth
    BarItem.PROJECTION -> R.string.tile_projection
    BarItem.PHONE -> R.string.bar_item_phone
    BarItem.VOICE -> R.string.bar_item_voice
    BarItem.FAVORITES -> R.string.bar_item_favorites
    BarItem.SETTINGS, BarItem.SPLIT_START, BarItem.SPLIT_END -> null
}

/**
 * La barra de apps (botón "Barra" al acomodarla): ocultarla (y la de Android), sus botones (una sola barra, la vista
 * previa con flechas: se acomoda con toques), tus apps favoritas, dónde va, su tamaño y, abajo, si es flotante o a lo
 * ancho. Mientras el panel está abierto el inicio no se dibuja: no hay otra barra encima.
 */
@Composable
fun BarSettingsPanel(d: Dependencies, apps: List<App>, close: () -> Unit) {
    val c = LocalColors.current
    PanelScaffold(stringResource(R.string.bar_title), close) {
        Column(Modifier.weight(1f).widthIn(max = 720.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            BarSettings(d, apps, inTab = false) { title, content ->
                if (title != null) Label(title.uppercase(currentLocale()), Typography.label, c.textMuted)
                content()
            }
        }
    }
}

/** La misma configuración como pestaña de Ajustes → Inicio y apariencia. */
@Composable
internal fun BarSettingsTab(d: Dependencies) {
    BarSettings(d, rememberApps(d.apps).orEmpty(), inTab = true) { title, content -> SettingsCard(title) { content() } }
}

/**
 * Lo que se configura de la barra, por partes: cada una va en [section] con su título (el panel las pone con
 * un rótulo; la pestaña, en tarjetas). [inTab]: es la pestaña de Ajustes (con la barra oculta solo quedan los interruptores).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BarSettings(d: Dependencies, apps: List<App>, inTab: Boolean, section: @Composable (title: String?, content: @Composable () -> Unit) -> Unit) {
    val c = LocalColors.current
    val prefs = d.prefs
    // La voz solo se ofrece si la pantalla trae asistente.
    val hasVoice = rememberVoiceIntent() != null
    // Las dos barras que se ocultan, juntas: la de apps (LiteDash) y la de Android (la de arriba).
    section(null) {
        SwitchRow(stringResource(R.string.bar_hide), prefs.barHidden, { prefs.barHidden = it }, stringResource(R.string.bar_hide_help))
        SwitchRow(stringResource(R.string.hide_status_bar), prefs.hideStatusBar, { prefs.hideStatusBar = it }, stringResource(R.string.hide_status_bar_help))
    }
    // En la pestaña, oculta solo quedan los interruptores; en el panel se está acomodando y la barra se ve: va todo.
    if (prefs.barHidden && inTab) return
    // Ajustes ya va siempre.
    // Lo elegido en la vista previa: también lo recién agregado, para que quede a la vista y se mueva de una vez.
    var selected by remember { mutableStateOf<BarItem?>(null) }
    val missing = BarItem.entries.filter { it != BarItem.SETTINGS && !it.isSplit && it !in prefs.barItems && (it != BarItem.VOICE || hasVoice) }
    section(stringResource(R.string.bar_buttons)) {
        BarArrange(d, apps, hasVoice, selected) { selected = it }
        Label(stringResource(R.string.bar_edit_help), Typography.body, c.textMuted)
        if (missing.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Se agrega antes de Ajustes (Android Auto / CarPlay, junto a Bluetooth como al ponerlo solo) y queda elegido:
            // luego se mueve con las flechas.
            for (item in missing) item.title()?.let { title ->
                Button(stringResource(R.string.bar_add_item, stringResource(title)), {
                    prefs.barItems = if (item == BarItem.PROJECTION) BarItem.withProjection(prefs.barItems) else BarItem.add(prefs.barItems, item)
                    selected = item
                })
            }
        }
    }
    if (BarItem.FAVORITES in prefs.barItems) section(stringResource(R.string.bar_item_favorites)) { Favorites(d, apps) }
    section(stringResource(R.string.bar_place)) {
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
    }
    if (prefs.barPosition != BarPosition.SIDE && prefs.barPosition != BarPosition.RIGHT) section(stringResource(R.string.bar_style)) {
        Segmented(
            listOf(BarStyle.FLOATING to stringResource(R.string.bar_style_floating), BarStyle.FULL to stringResource(R.string.bar_style_full)),
            prefs.barStyle,
            { prefs.barStyle = it },
        )
    }
    section(stringResource(R.string.bar_size)) {
        Segmented(
            listOf(BarSize.SMALL to stringResource(R.string.bar_size_small), BarSize.NORMAL to stringResource(R.string.bar_size_normal), BarSize.LARGE to stringResource(R.string.bar_size_large)),
            prefs.barSize,
            { prefs.barSize = it },
        )
    }
}

/**
 * La barra para acomodarla desde sus ajustes: se toca lo que se quiere mover (un botón, una raya, tus favoritas) y
 * arriba salen las flechas y «Quitar». Nada de dejar presionado: en muchas pantallas de auto no responde bien.
 */
@Composable
private fun BarArrange(d: Dependencies, apps: List<App>, hasVoice: Boolean, selected: BarItem?, onSelect: (BarItem?) -> Unit) {
    val c = LocalColors.current
    val prefs = d.prefs
    val shown = prefs.barItems.filter { it != BarItem.VOICE || hasVoice }
    val movable = shown.filter { it != BarItem.SETTINGS }
    val chosen = selected?.takeIf { it in movable }
    // Lo recorre un lugar; lo que no se ve aquí (la voz sin asistente) conserva el suyo ([reorderBar]).
    fun move(by: Int) {
        val item = chosen ?: return
        val to = movable.indexOf(item) + by
        if (to !in movable.indices) return
        prefs.barItems = reorderBar(prefs.barItems, movable, item, if (by < 0) to - 0.5f else to + 0.5f) { movable.indexOf(it).toFloat() }
        // Una raya que cruza a la otra cambia de nombre (la primera siempre es la del principio): se sigue por su lugar.
        onSelect(prefs.barItems.filter { it in movable }.getOrNull(to))
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (chosen == null) return@Row
        val at = movable.indexOf(chosen)
        Label(
            stringResource(if (chosen.isSplit) R.string.bar_split else chosen.title() ?: R.string.bar_split), Typography.subtitle,
            maxLines = 1, modifier = Modifier.weight(1f),
        )
        // Las rayas no se quitan: solo se mueven. «Quitar» va antes de las flechas: así ellas no cambian de lugar.
        if (!chosen.isSplit) Button(stringResource(R.string.remove), {
            prefs.barItems = prefs.barItems - chosen
            onSelect(null)
        })
        val idle = c.textMuted.copy(alpha = 0.35f)
        IconButton(
            Icons.chevron, { move(-1) }, Modifier.graphicsLayer { rotationZ = 180f }, size = 48.dp, background = c.surfaceHigh,
            color = if (at > 0) c.text else idle, label = stringResource(R.string.bar_move_left),
        )
        IconButton(
            Icons.chevron, { move(1) }, size = 48.dp, background = c.surfaceHigh,
            color = if (at < movable.lastIndex) c.text else idle, label = stringResource(R.string.bar_move_right),
        )
    }
    BarPreview(d.apps, rememberFavorites(d, apps), projectionChoice(d, apps), shown, chosen) { onSelect(if (selected == it) null else it) }
}

/** Tus apps favoritas de la barra: quitar con la x y agregar buscándolas. */
@Composable
private fun Favorites(d: Dependencies, apps: List<App>) {
    val favorites = rememberFavorites(d, apps)
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
    AppSearch(apps.filter { app -> favorites.none { it.key == app.key } }, null) { app ->
        d.prefs.dock = favorites.map { it.key } + app.key
    }
}
