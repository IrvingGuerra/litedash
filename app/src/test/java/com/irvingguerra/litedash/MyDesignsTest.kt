package com.irvingguerra.litedash

import com.irvingguerra.litedash.core.ui.CardStyle
import com.irvingguerra.litedash.feature.dashboard.Grid
import com.irvingguerra.litedash.feature.dashboard.PageBackground
import com.irvingguerra.litedash.feature.dashboard.PageSnapshot
import com.irvingguerra.litedash.feature.dashboard.Template
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MyDesignsTest {
    private val look = DesignLook(ThemeMode.LIGHT, 0xFF30D158.toInt(), null, CardStyle.GLASS, 0.8f, "palette=custom;color=1a1d22", BarStyle.FULL, 0xFF123456.toInt())
    private val page = PageSnapshot(0, Grid(20, 10), "clock-1 clock 0 0 4 2", PageBackground.MAP, Template.DESIGN_ROUTE)
    private val first = MyDesign("my-a", "Diario", look, page, image = true)
    private val second = MyDesign("my-b", "Viaje \"largo\"", look.copy(theme = ThemeMode.SUN, accent = null), page.copy(template = null, background = PageBackground.WALLPAPER), image = false)

    @Test
    fun savedDesignsReadBackTheSame() {
        assertEquals(listOf(first, second), MyDesign.decode(MyDesign.encode(listOf(first, second))))
        // Sin ninguno no se guarda nada.
        assertNull(MyDesign.encode(emptyList()))
        assertEquals(emptyList<MyDesign>(), MyDesign.decode(null))
        assertEquals(emptyList<MyDesign>(), MyDesign.decode("no es json"))
    }

    @Test
    fun aDamagedDesignIsSkippedAndTheOthersStay() {
        val saved = JSONArray(MyDesign.encode(listOf(first, second)))
        saved.getJSONObject(0).remove("layout")
        assertEquals(listOf(second), MyDesign.decode(saved.toString()))
    }

    @Test
    fun aCatalogCarKeepsItsColor() {
        val now = look.copy(theme = ThemeMode.DARK, carColor = 0xFFABCDEF.toInt())
        assertEquals(look, first.over(now, ownCar = false))
        assertEquals(look.copy(carColor = now.carColor), first.over(now, ownCar = true))
        assertTrue(first.matches(look.copy(carColor = 1), ownCar = true))
        assertFalse(first.matches(look.copy(carColor = 1), ownCar = false))
        assertFalse(first.matches(look.copy(cardOpacity = 0.5f), ownCar = true))
    }

    @Test
    fun namesAreTrimmedAndNeverEmpty() {
        assertEquals("Mi Diseño 1", designName("  Mi   Diseño 1 "))
        assertNull(designName("   "))
        assertEquals(MY_DESIGN_NAME_MAX, designName("x".repeat(80))!!.length)
    }
}
