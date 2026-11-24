package com.irvingguerra.litedash

import androidx.compose.ui.geometry.Rect
import com.irvingguerra.litedash.feature.dashboard.Grid
import com.irvingguerra.litedash.feature.dashboard.PageBackground
import com.irvingguerra.litedash.feature.dashboard.Tile
import com.irvingguerra.litedash.feature.dashboard.TileTypes
import com.irvingguerra.litedash.feature.map.PlaceCategory
import com.irvingguerra.litedash.feature.map.PlaceKind
import com.irvingguerra.litedash.feature.map.Route
import com.irvingguerra.litedash.feature.map.SearchHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        // Angosto y bajo: la tarjeta no cabe ni al lado ni encima de tu flecha.
        assertEquals(GuideSize.SMALL, guideSizeFor(320f, 200f))
        assertEquals(GuideSize.MEDIUM, guideSizeFor(320f, 260f))
    }

    @Test
    fun narrowCardsGoOnTop() {
        // Mediano angosto: la tarjeta a todo lo ancho y tu flecha debajo; ancho, al lado.
        assertTrue(guideStacked(GuideSize.MEDIUM, 320f, 0f, 8f))
        assertFalse(guideStacked(GuideSize.MEDIUM, 600f, 0f, 8f))
        // Mapa en grande en un celular de pie: los botones (~200 dp) bajo la tarjeta; acostado, al lado.
        assertTrue(guideStacked(GuideSize.FULL, 400f, 200f, 12f))
        assertFalse(guideStacked(GuideSize.FULL, 1200f, 200f, 12f))
        assertFalse(guideStacked(GuideSize.SMALL, 200f, 0f, 8f))
    }

    @Test
    fun youGoBetweenTheCards() {
        assertNull(guideFocus(0, 0, 600))
        // Solo la llegada abajo: arriba de ella, nunca menos de la mitad.
        assertEquals(Rect(0f, 0f, 1f, 0.8f), guideFocus(0, 120, 600))
        assertEquals(Rect(0f, 0f, 1f, 0.5f), guideFocus(0, 500, 600))
        // La vuelta arriba y la llegada abajo: entre las dos, al menos un tercio.
        assertEquals(Rect(0f, 0.25f, 1f, 0.75f), guideFocus(150, 150, 600))
        val tight = guideFocus(300, 250, 600)!!
        assertEquals(0.5f, tight.top, 0.001f)
        assertEquals(0.85f, tight.bottom, 0.001f)
    }

    @Test
    fun guideShowsOnThePageMap() {
        // Con widget Mapa en la página navega él (aunque también haya mapa de fondo); si no, el fondo con hueco; sin ninguno, en grande.
        assertEquals(GuideHome.WIDGET, guideHome(mapWidget = true, backdropHole = true))
        assertEquals(GuideHome.WIDGET, guideHome(mapWidget = true, backdropHole = false))
        assertEquals(GuideHome.BACKDROP, guideHome(mapWidget = false, backdropHole = true))
        assertEquals(GuideHome.EXPANDED, guideHome(mapWidget = false, backdropHole = false))
    }

    @Test
    fun aFullPageHidesTheBackdropGuide() {
        val grid = Grid(4, 3)
        val clock = Tile("a", TileTypes.CLOCK, 0, 0, 2, 3)
        val full = listOf(clock, Tile("b", TileTypes.WEATHER, 2, 0, 2, 3))
        assertEquals(GuideHome.BACKDROP, pageGuideHome(PageBackground.MAP, listOf(clock), grid))
        // Sin hueco la guía quedaría detrás de los widgets: cuenta como sin mapa (pastilla y mapa en grande).
        assertEquals(GuideHome.EXPANDED, pageGuideHome(PageBackground.MAP, full, grid))
        assertEquals(GuideHome.EXPANDED, pageGuideHome(PageBackground.WALLPAPER, listOf(clock), grid))
        assertEquals(GuideHome.WIDGET, pageGuideHome(PageBackground.WALLPAPER, listOf(Tile("m", TileTypes.MAP, 0, 0, 2, 2)), grid))
    }

    @Test
    fun oneRuleForWhereTheGuideGoes() {
        // La página que se ve si tiene mapa; si no, la primera que sí; sin ninguna, en grande (null).
        assertEquals(1, guidePage(1, GuideHome.WIDGET) { error("no se busca") })
        assertEquals(1, guidePage(1, GuideHome.BACKDROP) { error("no se busca") })
        assertEquals(3, guidePage(1, GuideHome.EXPANDED) { 3 })
        assertNull(guidePage(1, GuideHome.EXPANDED) { null })
    }

    @Test
    fun arrivalCloseSurvivesRecreation() {
        val session = DriveSession(
            SearchHit("Casa", null, null, null, PlaceKind.entries.first(), PlaceCategory.entries.first(), 0.001, 0.001, null, online = false),
            Route(intArrayOf(0, 0, 1000, 1000), 150.0, 20.0, ""),
        )
        assertEquals(ARRIVED_CLOSE_MS, session.closeInMs(1_000L))
        // La ventana se recrea 10 s después: la cuenta sigue, no vuelve a empezar.
        assertEquals(ARRIVED_CLOSE_MS - 10_000L, session.closeInMs(11_000L))
        assertEquals(0L, session.closeInMs(1_000L + ARRIVED_CLOSE_MS + 5_000L))
        // Si te vuelves a salir de la ruta, la próxima llegada empieza de cero.
        session.notArrived()
        assertEquals(ARRIVED_CLOSE_MS, session.closeInMs(90_000L))
    }
}
