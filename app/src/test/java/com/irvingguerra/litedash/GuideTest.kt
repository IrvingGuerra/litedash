package com.irvingguerra.litedash

import org.junit.Assert.assertEquals
import org.junit.Test

class GuideTest {
    @Test
    fun widgetShowsWhatFitsItsSize() {
        // Chico: solo la vuelta; mediano: la tarjeta y la llegada; grande: todo (con Flecha / Auto).
        assertEquals(GuideSize.SMALL, guideSizeFor(220f, 220f))
        assertEquals(GuideSize.SMALL, guideSizeFor(600f, 150f))
        assertEquals(GuideSize.MEDIUM, guideSizeFor(460f, 220f))
        assertEquals(GuideSize.MEDIUM, guideSizeFor(900f, 300f))
        assertEquals(GuideSize.MEDIUM, guideSizeFor(400f, 600f))
        assertEquals(GuideSize.LARGE, guideSizeFor(700f, 460f))
        assertEquals(GuideSize.LARGE, guideSizeFor(480f, 330f))
    }

    @Test
    fun goShowsTheGuideOnThePageMap() {
        // Con widget Mapa en la página navega él (aunque también haya mapa de fondo); si no, el fondo; sin ninguno, en grande.
        assertEquals(GuideHome.WIDGET, guideHome(mapWidget = true, mapBackdrop = true))
        assertEquals(GuideHome.WIDGET, guideHome(mapWidget = true, mapBackdrop = false))
        assertEquals(GuideHome.BACKDROP, guideHome(mapWidget = false, mapBackdrop = true))
        assertEquals(GuideHome.EXPANDED, guideHome(mapWidget = false, mapBackdrop = false))
    }
}
