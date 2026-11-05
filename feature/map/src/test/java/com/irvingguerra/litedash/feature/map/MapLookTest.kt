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
        val look = MapLook(MapOrientation.HEADING, MapView.TILTED, MapZoom.MID, MapStyle.DARK, MapDetail.FULL)
        assertEquals(look, MapLook.parse(look.encode()))
        assertEquals(16, MapZoom.MID.level)
        assertEquals(MapLook(view = MapView.TILTED), MapLook.parse("view=tilted"))
        assertEquals(true, MapLook(style = MapStyle.THEME).dark(themeDark = true))
        assertEquals(false, MapLook(style = MapStyle.LIGHT).dark(themeDark = true))
        // De fábrica el minimalista; lo guardado como minimalista (1.4.68–1.4.70) se sigue leyendo igual.
        assertEquals(MapDetail.MINIMAL, MapLook.parse("").detail)
        assertEquals(MapDetail.MINIMAL, MapLook.parse("detail=minimal").detail)
    }
}
