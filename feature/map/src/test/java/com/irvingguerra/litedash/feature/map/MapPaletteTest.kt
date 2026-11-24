package com.irvingguerra.litedash.feature.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class MapPaletteTest {
    /** Una rejilla de colores base: cada 30° de tono en tres intensidades y luces, más grises, blanco y negro. */
    private val bases = buildList {
        for (h in 0 until 360 step 30) for (s in listOf(0.3f, 0.7f, 1f)) for (l in listOf(0.2f, 0.5f, 0.8f)) add(hsl(h.toFloat(), s, l))
        addAll(listOf(0x000000, 0xFFFFFF, 0x808080, 0x1A1D22, 0xFFB224, 0x1F4FD8))
    }

    private fun palettes() = listOf(MapPalette.NIGHT, MapPalette.DAY).map { it to (it === MapPalette.NIGHT) } +
        bases.flatMap { b -> listOf(true, false).map { MapPalette.derive(b, it) to it } }

    @Test
    fun streetNamesAlwaysRead() {
        for ((p, night) in palettes()) {
            val where = "noche=$night tierra=${hex(p.land)}"
            assertTrue(where, contrast(p.label, p.land) >= 4.5)
            assertTrue(where, contrast(p.label, p.halo) >= 4.5)
            // Los nombres van encima de las calles.
            for (road in p.roads) assertTrue("$where calle=${hex(road)}", contrast(p.label, road) >= 3.0)
        }
    }

    @Test
    fun streetsStandOutFromLand() {
        for ((p, night) in palettes()) {
            val where = "noche=$night tierra=${hex(p.land)}"
            // De noche la calle (más clara) se separa de la tierra; de día la calle es blanca y la separa su orilla.
            for (i in p.roads.indices) {
                val edge = if (night) p.roads[i] else p.casings[i]
                assertTrue("$where tipo=$i", contrast(edge, p.land) >= 1.3)
            }
            // La autopista se distingue de una calle normal.
            assertTrue(where, contrast(p.roads[RoadTier.MOTORWAY.ordinal], p.roads[RoadTier.MINOR.ordinal]) >= 1.15)
        }
    }

    @Test
    fun nightIsDarkAndDayIsLight() {
        for (b in bases) {
            assertTrue(hex(b), luminance(MapPalette.derive(b, true).land) < 0.06)
            assertTrue(hex(b), luminance(MapPalette.derive(b, false).land) > 0.8)
        }
    }

    @Test
    fun keepsTheHueAndTheFactoryWater() {
        val blue = MapPalette.derive(0x1F4FD8, dark = true)
        val land = blue.land
        assertTrue((land and 0xFF) > (land shr 16 and 0xFF))
        assertEquals(MapPalette.NIGHT.water, blue.water)
        assertEquals(MapPalette.DAY.park, MapPalette.derive(0x1F4FD8, dark = false).park)
        // Un gris da un mapa gris.
        val gray = MapPalette.derive(0x808080, dark = true).land
        assertEquals(gray shr 16 and 0xFF, gray and 0xFF)
    }

    private fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)

    /** Luminancia relativa (WCAG) de un color RGB. */
    private fun luminance(c: Int): Double {
        fun ch(v: Int) = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * ch(c shr 16 and 0xFF) + 0.7152 * ch(c shr 8 and 0xFF) + 0.0722 * ch(c and 0xFF)
    }

    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}
