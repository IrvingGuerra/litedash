package com.irvingguerra.litedash.feature.map

import androidx.compose.runtime.Immutable

/**
 * Hacia dónde mira el mapa (siempre plano; solo la guía se inclina). [NORTH] («Automático», de fábrica): al norte
 * detenido y hacia donde vas de viaje; [HEADING]: siempre hacia donde vas; [NORTH_UP]: siempre al norte.
 */
enum class MapOrientation(val stored: String) { NORTH("north"), HEADING("heading"), NORTH_UP("north_up") }

/** Automático (cerca: unas calles) o fijo (cerca = calles, lejos = colonias). */
enum class MapZoom(val stored: String, val level: Int?) { AUTO("auto", null), NEAR("near", 17), MID("mid", 16), FAR("far", 15) }

/** Los colores: como el tema de LiteDash (día y noche), o siempre claros u oscuros. */
enum class MapStyle(val stored: String) { THEME("theme"), LIGHT("light"), DARK("dark") }

/**
 * Cómo se ve el widget Mapa; se guarda en su `Tile.data` como "orientation=heading;zoom=mid…".
 * Lo de fábrica (orientación y zoom automáticos, como el tema) no se escribe. El «detail» de
 * hasta la 1.5.15 (el mapa «Completo» de OpenStreetMap, ya quitado) se ignora: todo es el minimalista.
 */
@Immutable
data class MapLook(
    val orientation: MapOrientation = MapOrientation.NORTH,
    val zoom: MapZoom = MapZoom.AUTO,
    val style: MapStyle = MapStyle.THEME,
) {
    /** ¿Se ve oscuro? Con [MapStyle.THEME], lo que diga el tema de LiteDash ([themeDark]). */
    fun dark(themeDark: Boolean) = when (style) {
        MapStyle.THEME -> themeDark
        MapStyle.LIGHT -> false
        MapStyle.DARK -> true
    }

    fun encode(): String = listOfNotNull(
        orientation.takeIf { it != MapOrientation.NORTH }?.let { "$ORIENTATION=${it.stored}" },
        zoom.takeIf { it != MapZoom.AUTO }?.let { "$ZOOM=${it.stored}" },
        style.takeIf { it != MapStyle.THEME }?.let { "$STYLE=${it.stored}" },
    ).joinToString(";")

    companion object {
        private const val ORIENTATION = "orientation"
        private const val ZOOM = "zoom"
        private const val STYLE = "style"

        fun parse(data: String): MapLook {
            val f = data.split(';').mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            return MapLook(
                orientation = MapOrientation.entries.firstOrNull { it.stored == f[ORIENTATION] } ?: MapOrientation.NORTH,
                zoom = MapZoom.entries.firstOrNull { it.stored == f[ZOOM] } ?: MapZoom.AUTO,
                style = MapStyle.entries.firstOrNull { it.stored == f[STYLE] } ?: MapStyle.THEME,
            )
        }
    }
}
