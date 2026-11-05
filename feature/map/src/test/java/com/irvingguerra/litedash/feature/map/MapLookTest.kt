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
        val look = MapLook(MapOrientation.HEADING, MapView.TILTED, MapZoom.MID, MapStyle.DARK, MapDetail.MINIMAL)
        assertEquals(look, MapLook.parse(look.encode()))
        assertEquals(16, MapZoom.MID.level)
        assertEquals(MapLook(view = MapView.TILTED), MapLook.parse("view=tilted"))
        assertEquals(true, MapLook(style = MapStyle.THEME).dark(themeDark = true))
        assertEquals(false, MapLook(style = MapStyle.LIGHT).dark(themeDark = true))
    }
}
