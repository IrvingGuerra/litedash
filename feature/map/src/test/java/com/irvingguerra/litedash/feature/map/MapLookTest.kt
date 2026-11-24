package com.irvingguerra.litedash.feature.map

import org.junit.Assert.assertEquals
import org.junit.Test

class MapLookTest {
    @Test
    fun defaultsAreNotWritten() {
        assertEquals("", MapLook().encode())
        assertEquals(MapLook(), MapLook.parse(""))
        // Lo desconocido (de una versión futura) se ignora.
        assertEquals(MapLook(), MapLook.parse("view=globe;other=1"))
    }

    @Test
    fun roundTrip() {
        val look = MapLook(MapOrientation.HEADING, MapZoom.MID, MapStyle.DARK)
        assertEquals(look, MapLook.parse(look.encode()))
        assertEquals(16, MapZoom.MID.level)
        // El inclinado ya no es opción (1.4.157): lo guardado antes se lee como plano.
        assertEquals(MapLook(), MapLook.parse("view=tilted"))
        assertEquals(true, MapLook(style = MapStyle.THEME).dark(themeDark = true))
        assertEquals(false, MapLook(style = MapStyle.LIGHT).dark(themeDark = true))
        // El mapa «Completo» ya no existe (después de la 1.5.15): lo guardado con él se lee como el minimalista, con lo demás igual.
        assertEquals(MapLook(), MapLook.parse("detail=minimal"))
        assertEquals(MapLook(zoom = MapZoom.FAR), MapLook.parse("detail=full;zoom=far"))
    }

    @Test
    fun paletteRoundTrip() {
        val look = MapLook(palette = MapPalettes.CUSTOM, custom = 0x1F4FD8, flow = false)
        assertEquals("palette=custom;color=1f4fd8;flow=off", look.encode())
        assertEquals(look, MapLook.parse(look.encode()))
        // De fábrica: la paleta nueva, sin color y con la ruta animada.
        assertEquals(MapPalette.NIGHT, MapLook().colors(themeDark = true, interfaceColor = 0x7A3FE0))
        assertEquals(MapPalette.DAY, MapLook(style = MapStyle.LIGHT).colors(themeDark = true, interfaceColor = null))
        // Propio sin color guardado (no debería pasar): la de fábrica. Un color raro se ignora.
        assertEquals(MapPalette.DAY, MapLook(palette = MapPalettes.CUSTOM).colors(themeDark = false, interfaceColor = null))
        assertEquals(null, MapLook.parse("palette=custom;color=zz").custom)
    }
}
