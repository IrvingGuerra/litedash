package com.irvingguerra.litedash

import com.irvingguerra.litedash.feature.dashboard.TileTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OptionsPanelTest {
    @Test
    fun eachWidgetGoesToItsOwnPanel() {
        assertEquals(Panel.CustomizeImage("a"), optionsPanelOf(TileTypes.IMAGE, "a"))
        assertEquals(Panel.CustomizeMap("a"), optionsPanelOf(TileTypes.MAP, "a"))
        assertEquals(Panel.CustomizeCar("a"), optionsPanelOf(TileTypes.CAR, "a"))
        for (type in WIDGET_OPTIONS) assertEquals(Panel.CustomizeWidget("a"), optionsPanelOf(type, "a"))
        // Solo los medidores (y la carretera, que lleva velocímetro) abren el panel del medidor.
        for (type in listOf(TileTypes.SPEEDOMETER, TileTypes.GAUGE, TileTypes.ROAD)) assertEquals(Panel.CustomizeGauge("a"), optionsPanelOf(type, "a"))
    }

    @Test
    fun aWidgetWithoutOptionsHasNoPanel() {
        // Android Auto / CarPlay se elige en Ajustes → Apps: sin engrane (antes caía en el panel del medidor, vacío).
        assertNull(optionsPanelOf(TileTypes.PROJECTION, "a"))
        assertNull(optionsPanelOf("unknown", "a"))
    }

    @Test
    fun theChosenBarButtonIsCenteredWithoutPassingTheEnds() {
        // Al centro de la vista si se puede…
        assertEquals(300, centeredScroll(400..500, viewport = 300, max = 1000))
        // …sin irse antes del principio ni después del final.
        assertEquals(0, centeredScroll(10..60, viewport = 300, max = 1000))
        assertEquals(1000, centeredScroll(1900..1990, viewport = 300, max = 1000))
        // Sin nada que desplazar, se queda.
        assertEquals(0, centeredScroll(400..500, viewport = 300, max = 0))
    }
}
