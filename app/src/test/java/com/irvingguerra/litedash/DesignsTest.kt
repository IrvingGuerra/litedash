package com.irvingguerra.litedash

import androidx.compose.ui.graphics.toArgb
import com.irvingguerra.litedash.core.ui.CardStyle
import com.irvingguerra.litedash.core.ui.MIN_OPACITY
import com.irvingguerra.litedash.feature.car.CarColors
import com.irvingguerra.litedash.feature.dashboard.DESIGN_TEMPLATES
import com.irvingguerra.litedash.feature.dashboard.Grid
import com.irvingguerra.litedash.feature.dashboard.PageBackground
import com.irvingguerra.litedash.feature.dashboard.PageSnapshot
import com.irvingguerra.litedash.feature.dashboard.Template
import com.irvingguerra.litedash.feature.map.MapLook
import com.irvingguerra.litedash.feature.map.MapOrientation
import com.irvingguerra.litedash.feature.map.MapPalettes
import com.irvingguerra.litedash.feature.map.MapZoom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignsTest {
    /** Lo de fábrica de toda la app (lo de una instalación nueva). */
    private val factory = DesignLook(ThemeMode.DARK, null, null, CardStyle.GLASS, MIN_OPACITY, null, BarStyle.FLOATING, CarColors[0].toArgb())

    /** Lo de alguien que ya personalizó todo. */
    private val mine = DesignLook(ThemeMode.LIGHT, 0xFF30D158.toInt(), 0xFF1F4FD8.toInt(), CardStyle.GLASS, 0.8f, "orientation=heading;zoom=mid;flow=off", BarStyle.FULL, 0xFF123456.toInt())

    @Test
    fun eightDesignsWithTheirOwnLayoutsAndGraphiteFirst() {
        assertEquals(8, DESIGNS.size)
        assertEquals(DESIGN_TEMPLATES, DESIGNS.map { it.template })
        assertEquals(DESIGNS.size, DESIGNS.map { it.id }.toSet().size)
        assertEquals("graphite", FACTORY_DESIGN.id)
        // Todos con su fondo de litedash.dev menos Ruta (su fondo es el mapa).
        assertEquals(listOf("route"), DESIGNS.filter { it.wallpaper == null }.map { it.id })
    }

    @Test
    fun graphiteIsWhatANewInstallAlreadyHas() {
        // Lo de fábrica ya es Grafito: arrancar con él no cambia ningún color.
        assertTrue(FACTORY_DESIGN.matches(factory, ownCar = false))
        assertEquals(factory, FACTORY_DESIGN.over(factory, ownCar = false))
    }

    @Test
    fun welcomeOffersThreeCarColorsStartingWithTheDesigns() {
        for (design in DESIGNS) {
            val colors = design.carColors()
            assertEquals(3, colors.toSet().size)
            assertEquals(design.carColor, colors.first())
        }
    }

    @Test
    fun freshInstallOnlyTheFirstTime() {
        assertTrue(isFreshInstall(sawWelcome = false, hasDock = false))
        // Quien actualiza ya tiene alguno de los dos: no se le aplica nada.
        assertFalse(isFreshInstall(sawWelcome = true, hasDock = false))
        assertFalse(isFreshInstall(sawWelcome = false, hasDock = true))
        assertFalse(isFreshInstall(sawWelcome = true, hasDock = true))
    }

    @Test
    fun applyingKeepsTheMapUsageAndYourCatalogCar() {
        val track = designOf("track")!!
        val after = track.over(mine, ownCar = true)
        assertEquals(ThemeMode.DARK, after.theme)
        assertEquals(0xFFFF453A.toInt(), after.accent)
        assertNull(after.cards)
        // Del mapa solo la paleta y el estilo; la orientación, el zoom y la ruta quieta se quedan.
        val map = MapLook.parse(after.mapLook!!)
        // Pista: el mapa grafito de su galería.
        assertEquals(MapPalettes.CUSTOM, map.palette)
        assertEquals(0x1A1D22, map.custom)
        assertEquals(MapOrientation.HEADING, map.orientation)
        assertEquals(MapZoom.MID, map.zoom)
        assertFalse(map.flow)
        // Con modelo del catálogo el auto no se toca; sin él, su color.
        assertEquals(mine.carColor, after.carColor)
        assertEquals(track.carColor.toArgb(), track.over(mine, ownCar = false).carColor)
        assertTrue(track.matches(after, ownCar = true))
        assertFalse(track.matches(mine, ownCar = true))
    }

    @Test
    fun undoBringsBackExactlyWhatThereWas() {
        val page = PageSnapshot(2, Grid(40, 13), "music-0\tmusic\t0\t0\t8\t4\t\t\ncar-1\tcar\t8\t0\t8\t4\tid=evo\t1", PageBackground.MAP, Template.MAP_BACKGROUND)
        for (design in DESIGNS) for (wallpaper in listOf(false, true)) {
            val undo = DesignUndo("cloud", mine, page, "applied\tlayout", wallpaper, pending = "nube-perla")
            val back = DesignUndo.decode(undo.encode())!!
            assertEquals(undo.design, back.design)
            assertEquals(mine, back.look)
            assertEquals(page, back.page)
            assertEquals(undo.applied, back.applied)
            assertEquals(wallpaper, back.wallpaper)
            assertEquals("nube-perla", back.pending)
            // Aplicar y luego deshacer: lo de toda la app queda idéntico.
            val applied = design.over(mine, ownCar = false)
            assertEquals(mine, DesignUndo("x", mine, page, "").let { DesignUndo.decode(it.encode())!!.look })
            assertTrue(applied != mine)
        }
        // Lo de fábrica (sin colores ni mapa propios) también regresa igual.
        val clean = DesignUndo.decode(DesignUndo(null, factory, page.copy(template = null, background = PageBackground.WALLPAPER), "").encode())!!
        assertEquals(factory, clean.look)
        assertNull(clean.design)
        assertNull(clean.page.template)
        assertFalse(clean.wallpaper)
        assertNull(clean.pending)
    }

    @Test
    fun damagedUndoIsIgnored() {
        assertNull(DesignUndo.decode(null))
        assertNull(DesignUndo.decode(""))
        assertNull(DesignUndo.decode("{"))
        assertNull(DesignUndo.decode("{\"theme\":\"dark\"}"))
    }

    @Test
    fun dayAndNightStaysWhenApplyingADesign() {
        val sun = mine.copy(theme = ThemeMode.SUN)
        for (design in DESIGNS) {
            val after = design.over(sun, ownCar = false)
            // Se queda «Día y noche»; lo demás del Diseño sí se pone.
            assertEquals(ThemeMode.SUN, after.theme)
            assertEquals(design.accent?.toArgb(), after.accent)
            assertEquals(design.cardStyle, after.cardStyle)
            // Conservarlo no cuenta como «con tus cambios».
            assertTrue(design.matches(after, ownCar = false))
            // En la bienvenida, el tema del Diseño tal cual.
            assertEquals(design.theme, design.over(sun, ownCar = false, keepSun = false).theme)
        }
        // Un tema fijo sí lo cambia el Diseño (y Ruta pone «Día y noche»).
        assertEquals(ThemeMode.DARK, designOf("graphite")!!.over(mine, ownCar = false).theme)
        assertEquals(ThemeMode.SUN, designOf("route")!!.over(mine, ownCar = false).theme)
        // Deshacer regresa el tema de antes aunque no haya cambiado.
        val page = PageSnapshot(0, Grid(40, 13), "", PageBackground.WALLPAPER, null)
        assertEquals(sun, DesignUndo.decode(DesignUndo("cloud", sun, page, "").encode())!!.look)
    }

    @Test
    fun everyDesignWallpaperHasItsDayAndNightTwin() {
        for (design in DESIGNS) {
            val id = design.wallpaper ?: continue
            val night = themedWallpaper(id, dark = true)
            val day = themedWallpaper(id, dark = false)
            assertTrue(design.id, night != null && day != null && night != day)
            // El del Diseño es el de su tema; la pareja lleva de regreso al mismo par.
            assertEquals(id, if (design.theme == ThemeMode.LIGHT) day else night)
            assertEquals(night, themedWallpaper(day!!, dark = true))
            assertEquals(day, themedWallpaper(night!!, dark = false))
        }
        // Tu foto o un fondo suelto no tienen pareja: no se tocan.
        assertNull(themedWallpaper("aurora-iss", dark = true))
    }
}
