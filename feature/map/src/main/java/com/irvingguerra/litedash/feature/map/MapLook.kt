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
 * De qué tono es el mapa (encima de [MapStyle], que decide si es de día o de noche): la paleta de fábrica, la del
 * color de tu interfaz o la de un color que eliges ([MapLook.custom]).
 */
enum class MapPalettes(val stored: String) { FACTORY("factory"), INTERFACE("interface"), CUSTOM("custom") }

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
    val palette: MapPalettes = MapPalettes.FACTORY,
    /** El color propio (RGB) de [MapPalettes.CUSTOM]; se guarda aunque se elija otra paleta. null = aún ninguno. */
    val custom: Int? = null,
    /** La ruta «fluye» (sus flechas avanzan); apagado, quietas. */
    val flow: Boolean = true,
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
        palette.takeIf { it != MapPalettes.FACTORY }?.let { "$PALETTE=${it.stored}" },
        custom?.let { "$CUSTOM=" + (it and 0xFFFFFF).toString(16).padStart(6, '0') },
        "$FLOW=off".takeIf { !flow },
    ).joinToString(";")

    /**
     * Los colores con que se dibuja: [themeDark] como en [dark]; [interfaceColor] = el color de tu interfaz (RGB), o
     * null si es el de fábrica (entonces el grafito de LiteDash).
     */
    fun colors(themeDark: Boolean, interfaceColor: Int?): MapPalette {
        val night = dark(themeDark)
        return when (palette) {
            MapPalettes.FACTORY -> MapPalette.factory(night)
            MapPalettes.INTERFACE -> MapPalette.derive(interfaceColor ?: GRAPHITE, night)
            MapPalettes.CUSTOM -> custom?.let { MapPalette.derive(it, night) } ?: MapPalette.factory(night)
        }
    }

    companion object {
        private const val ORIENTATION = "orientation"
        private const val ZOOM = "zoom"
        private const val STYLE = "style"
        private const val PALETTE = "palette"
        private const val CUSTOM = "color"
        private const val FLOW = "flow"

        /** El grafito de las tarjetas de fábrica: «Como mi interfaz» sin color elegido. */
        private const val GRAPHITE = 0x1A1D22

        fun parse(data: String): MapLook {
            val f = data.split(';').mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            return MapLook(
                orientation = MapOrientation.entries.firstOrNull { it.stored == f[ORIENTATION] } ?: MapOrientation.NORTH,
                zoom = MapZoom.entries.firstOrNull { it.stored == f[ZOOM] } ?: MapZoom.AUTO,
                style = MapStyle.entries.firstOrNull { it.stored == f[STYLE] } ?: MapStyle.THEME,
                palette = MapPalettes.entries.firstOrNull { it.stored == f[PALETTE] } ?: MapPalettes.FACTORY,
                custom = f[CUSTOM]?.toIntOrNull(16)?.takeIf { it in 0..0xFFFFFF },
                flow = f[FLOW] != "off",
            )
        }
    }
}
