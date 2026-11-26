// Plantillas del inicio: solo la distribución de la página (no tocan colores, fondo, tema ni auto, eso es de los
// Diseños). Nombres, pestañas por estilo y sus miniaturas.
package com.irvingguerra.litedash

import com.irvingguerra.litedash.core.ui.Radius
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.feature.dashboard.DashboardState
import com.irvingguerra.litedash.feature.dashboard.Grid
import com.irvingguerra.litedash.feature.dashboard.ScreenSize
import com.irvingguerra.litedash.feature.dashboard.Template
import com.irvingguerra.litedash.feature.dashboard.Tile
import com.irvingguerra.litedash.feature.dashboard.hasCar
import com.irvingguerra.litedash.feature.dashboard.isDesign
import com.irvingguerra.litedash.feature.dashboard.mapBackground
import com.irvingguerra.litedash.feature.dashboard.TileTypes
import com.irvingguerra.litedash.feature.dashboard.coarse
import com.irvingguerra.litedash.feature.dashboard.freeArea
import com.irvingguerra.litedash.feature.dashboard.suggestedTemplate
import com.irvingguerra.litedash.feature.dashboard.tiles

private fun nameOf(p: Template) = when (p) {
    Template.CENTER -> R.string.template_center
    Template.CARDS -> R.string.template_cards
    Template.SIDE -> R.string.template_side
    Template.COLUMNS -> R.string.template_columns
    Template.PANEL -> R.string.template_panel
    Template.VERTICAL -> R.string.template_vertical
    Template.SPEED -> R.string.template_speed
    Template.CAR_SPEED -> R.string.template_car_speed
    Template.SPORT -> R.string.template_sport
    Template.MINIMAL -> R.string.template_minimal
    Template.SPEED_WIDGETS -> R.string.template_speed_widgets
    Template.WALL_CARDS -> R.string.template_wall_cards
    Template.WALL_SIDE -> R.string.template_wall_side
    Template.MUSIC_RADIO -> R.string.template_music_radio
    Template.MAP_CAR -> R.string.template_map_car
    Template.COPILOT -> R.string.template_copilot
    Template.ROUTE -> R.string.template_route
    Template.VERTICAL_MAP -> R.string.template_vertical_map
    Template.MAP_BACKGROUND -> R.string.template_map_background
    // Las de los Diseños no salen aquí (tienen su galería): ninguna pestaña las lleva.
    Template.DESIGN_GRAPHITE, Template.DESIGN_CLOUD, Template.DESIGN_TRACK, Template.DESIGN_ROUTE, Template.DESIGN_COLUMN,
    Template.DESIGN_NEON, Template.DESIGN_SAND, Template.DESIGN_FOREST -> error("$p es de un Diseño")
}

/** Para pantallas paradas o acostadas: las de acostadas se acomodan a cualquier tamaño (de 7" a 12"). */
private fun Template.fits(vertical: Boolean) = if (vertical) ScreenSize.VERTICAL in sizes else sizes.any { it != ScreenSize.VERTICAL }

/** Las que lucen el mapa: van en su pestaña y no se repiten en la del auto. */
private val MAP_TEMPLATES = listOf(Template.MAP_BACKGROUND, Template.MAP_CAR, Template.COPILOT, Template.ROUTE, Template.VERTICAL_MAP)

/**
 * Estilos de acomodo (una pestaña cada uno): con el auto 3D, con el mapa de protagonista,
 * con la carretera en lugar del auto ([hero]), solo velocímetro (sin OBD: usa el GPS) o para
 * lucir tu fondo, como BYD (ahí va la Mínima: hora y música, sin velocímetro). Las de los Diseños no van: para no
 * confundir, salen solo en su galería.
 */
private enum class TemplateTab(val title: Int, val templates: List<Template>, val hero: String = TileTypes.CAR) {
    CAR(R.string.templates_tab_car, Template.entries.filter { it.hasCar && it !in MAP_TEMPLATES && !it.isDesign }),
    MAP(R.string.templates_tab_map, MAP_TEMPLATES),
    ROAD(R.string.templates_tab_road, listOf(Template.MAP_CAR, Template.CARDS, Template.CENTER, Template.CAR_SPEED, Template.SPORT, Template.VERTICAL), TileTypes.ROAD),
    SPEED(R.string.templates_tab_speed, listOf(Template.SPEED, Template.SPEED_WIDGETS)),
    WALLPAPER(R.string.templates_tab_wallpaper, listOf(Template.WALL_CARDS, Template.WALL_SIDE, Template.MINIMAL)),
}

/**
 * Plantillas (Ajustes → Inicio y apariencia → Inicio → Acomodo, o «Plantillas» al acomodar): cambian solo la distribución
 * de la página que se ve; tus colores, tema y auto se quedan. Del fondo solo deciden si la página es de mapa (la del mapa
 * de fondo) o de imagen (las demás), como siempre. En pestañas por estilo, solo las de tu pantalla (acostada o de pie; la cuadrícula fina acomoda cada una a
 * cualquier tamaño) y con la recomendada marcada. No se repiten las que en esta pantalla quedan igual que otra, y una
 * pestaña sin ninguna para tu pantalla no sale.
 */
@Composable
fun TemplatesPanel(dashboard: DashboardState, close: () -> Unit, onDesigns: () -> Unit) {
    val grid = dashboard.grid ?: return
    val c = LocalColors.current
    val suggested = grid.coarse.suggestedTemplate()
    // Arriba, los Diseños (el acomodo con sus colores y su fondo).
    PanelScaffold(stringResource(R.string.templates), close, header = { Button(stringResource(R.string.designs), onDesigns) }) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Label(stringResource(R.string.templates_help), Typography.body, c.textMuted)
            val shownTabs = TemplateTab.entries.filter { t -> t.templates.any { it.fits(grid.vertical) } }
            val tab = shownTabs[tabs(shownTabs.map { it.title }, "templates").coerceAtMost(shownTabs.lastIndex)]
            val alerts = LocalAlerts.current
            val res = LocalResources.current
            fun apply(p: Template) {
                val use = { dashboard.template(p, tab.hero); close() }
                // Reemplaza todo el acomodo de la página (y sus opciones): con algo ya puesto, se pregunta antes.
                if (dashboard.tiles.isEmpty()) use()
                else alerts.ask(Alert(res.getString(R.string.template_apply_title, res.getString(nameOf(p))), res.getString(R.string.template_apply_confirm), res.getString(R.string.template_apply_message), onConfirm = use))
            }
            when (tab) {
                TemplateTab.MAP -> Label(stringResource(R.string.templates_map_help), Typography.small, c.textMuted)
                TemplateTab.ROAD -> Label(stringResource(R.string.templates_road_help), Typography.small, c.textMuted)
                TemplateTab.SPEED -> Label(stringResource(R.string.templates_speed_help), Typography.small, c.textMuted)
                TemplateTab.WALLPAPER -> Label(stringResource(R.string.templates_wallpaper_help), Typography.small, c.textMuted)
                TemplateTab.CAR -> Unit
            }
            // Cada plantilla armada una vez por pestaña (para no repetir y para su miniatura).
            val layouts = remember(tab, grid) { tab.templates.filter { it.fits(grid.vertical) }.associateWith { it.tiles(grid, tab.hero, dashboard.hidden) } }
            val templates = layouts.keys.distinctBy { p -> layouts.getValue(p).map { listOf(it.type, it.col, it.row, it.width, it.height) } }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for (p in templates) {
                    Card(Modifier.width(260.dp), onClick = { apply(p) }) {
                        Column(Modifier.padding(16.dp)) {
                            TemplateThumbnail(layouts.getValue(p), grid, tab.hero, mapBackground = p.mapBackground)
                            Spacer(Modifier.height(12.dp))
                            Label(stringResource(nameOf(p)), Typography.subtitle, maxLines = 1)
                            if (tab == TemplateTab.CAR && p == suggested) {
                                Label(stringResource(R.string.recommended).uppercase(currentLocale()), Typography.label, c.accent, maxLines = 1)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** Dibujo de la plantilla ya armada ([tiles], igual que al aplicarla) en chiquito, con las proporciones de esta pantalla. */
@Composable
private fun TemplateThumbnail(tiles: List<Tile>, grid: Grid, hero: String, mapBackground: Boolean = false) {
    val c = LocalColors.current
    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(grid.columns.toFloat() / grid.rows).clip(RoundedCornerShape(Radius.inner)).background(c.background)) {
        val cw = maxWidth / grid.columns
        val ch = maxHeight / grid.rows
        // El mapa de fondo: el ícono del mapa grande en el hueco, en el color de la interfaz.
        if (mapBackground) {
            val free = tiles.freeArea(grid)
            if (free != null) Box(Modifier.offset(cw * free.col, ch * free.row).size(cw * free.width, ch * free.height), contentAlignment = Alignment.Center) {
                Icon(iconOf(TileTypes.MAP), c.accent.copy(alpha = 0.7f), minOf(cw * free.width, ch * free.height) * 0.5f)
            }
        }
        // La protagonista en el color de la interfaz: el auto (o la carretera) o, en las que no lo llevan, el velocímetro o la primera pieza.
        val main = (tiles.firstOrNull { it.type == hero } ?: tiles.firstOrNull { it.type == TileTypes.SPEEDOMETER } ?: tiles.firstOrNull())?.id
        for (tile in tiles) {
            val width = cw * tile.width
            val height = ch * tile.height
            Box(
                Modifier
                    .offset(cw * tile.col, ch * tile.row)
                    .size(width, height)
                    .padding(2.dp)
                    .clip(RoundedCornerShape(Radius.small))
                    .background(if (tile.id == main) c.accent.copy(alpha = 0.55f) else c.surfaceHigh),
                contentAlignment = Alignment.Center,
            ) {
                // El ícono de cada widget: se ve qué es cada cosa (mapa, música, radio…) sin aplicarla.
                Icon(iconOf(tile.type), if (tile.id == main) c.surface else c.textMuted, minOf(width, height, 22.dp) * 0.7f)
            }
        }
    }
}
