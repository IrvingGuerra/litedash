package com.irvingguerra.litedash.feature.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapPacksTest {
    // Un "estado" en forma de L y uno cuadrado chico, como en el index.json de litedash-maps.
    private val index = """
        {"version":1,"release":"mx-x","base":"https://example.org/mx-x/","regions":[
          {"id":"mx-ele","country":"MX","name":{"es":"Ele","en":"El"},"bbox":[-100,19,-98,21],
           "shape":[[[-100,19],[-98,19],[-98,20],[-99,20],[-99,21],[-100,21],[-100,19]]],
           "files":{"map":{"name":"mx-ele.pmtiles","size":123,"unpacked":123,"sha256":"ab"}}},
          {"id":"mx-roto","country":"MX","name":{"es":"Roto"}},
          {"id":"mx-chico","country":"MX","name":{"es":"Chico"},"bbox":[-98.6,20.4,-98.2,20.8],
           "shape":[[[-98.6,20.4],[-98.2,20.4],[-98.2,20.8],[-98.6,20.8],[-98.6,20.4]]],
           "files":{"map":{"name":"mx-chico.pmtiles","size":5,"unpacked":5,"sha256":"cd"}}}
        ]}
    """.trimIndent()

    @Test
    fun readsTheIndexAndSkipsBrokenRegions() {
        val regions = MapPacks.parseIndex(index)
        assertEquals(listOf("mx-ele", "mx-chico"), regions.map { it.id })
        val ele = regions.first()
        assertEquals("https://example.org/mx-x/mx-ele.pmtiles", ele.map.url)
        assertEquals("El", ele.name("en"))
        // Sin el idioma, en inglés; sin inglés, el id.
        assertEquals("El", ele.name("de"))
        assertEquals("mx-chico", regions[1].name("de"))
    }

    @Test
    fun knowsWhichStateYouAreIn() {
        val ele = MapPacks.parseIndex(index).first()
        assertTrue(ele.contains(19.5, -99.5))
        assertTrue(ele.contains(20.5, -99.5))
        // En la caja pero fuera de la L (el hueco de arriba a la derecha).
        assertFalse(ele.contains(20.5, -98.5))
        assertFalse(ele.contains(25.0, -99.5))
    }

    @Test
    fun otherVersionsOfTheIndexAreIgnored() {
        assertTrue(MapPacks.parseIndex("""{"version":2,"base":"x","regions":[]}""").isEmpty())
    }

    @Test
    fun aSavedPackKeepsItsShape() {
        val ele = MapPacks.parseIndex(index).first()
        val saved = InstalledPack(ele.id, "ab", 123, 1L, ele.bbox, ele.shape)
        val back = MapPacks.packFromJson(MapPacks.packToJson(saved))
        // Solo sus teselas: el hueco de la L (otro estado) no es suyo.
        assertTrue(back.contains(19.5, -99.5))
        assertFalse(back.contains(20.5, -98.5))
        // Uno adoptado sin contorno vale por su caja.
        assertTrue(InstalledPack("x", "", 1, 1, ele.bbox, emptyList()).contains(20.5, -98.5))
    }
}
