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

    @Test
    fun theSavedListIsFreshOnlyWhileYoungAndNewerThanTheApp() {
        val hour = 60 * 60 * 1000L
        val day = 24 * hour
        val now = 100 * day
        // Sin lista guardada, nunca.
        assertFalse(MapPacks.indexFresh(false, now, 0, now, day))
        // Con menos de un día, sí; con más, no.
        assertTrue(MapPacks.indexFresh(true, now - 23 * hour, 0, now, day))
        assertFalse(MapPacks.indexFresh(true, now - 25 * hour, 0, now, day))
        // La pantalla Sin conexión pide una de menos de una hora.
        assertFalse(MapPacks.indexFresh(true, now - 2 * hour, 0, now, MapPacks.INDEX_SCREEN_MAX_AGE_MS))
        assertTrue(MapPacks.indexFresh(true, now - 30 * 60 * 1000L, 0, now, MapPacks.INDEX_SCREEN_MAX_AGE_MS))
        // Guardada antes de actualizar la app: se vuelve a pedir.
        assertFalse(MapPacks.indexFresh(true, now - hour, now - 30 * 60 * 1000L, now, day))
        // Reloj hacia atrás (guardada "en el futuro"): se vuelve a pedir.
        assertFalse(MapPacks.indexFresh(true, now + hour, 0, now, day))
    }

    @Test
    fun theOnlineSwitchComesFromTheSameRead() {
        assertTrue(MapPacks.readIndex(index).photon)
        val off = MapPacks.readIndex("""{"version":1,"base":"x","online":{"photon":false},"regions":[]}""")
        assertFalse(off.photon)
        // Aunque sea de otra versión (sin regiones), el apagado de Photon sí cuenta.
        assertFalse(MapPacks.readIndex("""{"version":2,"online":{"photon":false}}""").photon)
    }

    @Test
    fun aHalfDownloadedMapIsKeptOnlyIfItCanContinue() {
        val day = 24 * 60 * 60 * 1000L
        val now = 10 * day
        assertTrue(MapPacks.keepPartial(400, 1000, now - day, now))
        // Completo también (solo falta revisar su huella).
        assertTrue(MapPacks.keepPartial(1000, 1000, now - day, now))
        // Vacío, más grande que el archivo, viejo o «del futuro» (reloj hacia atrás): se borra.
        assertFalse(MapPacks.keepPartial(0, 1000, now - day, now))
        assertFalse(MapPacks.keepPartial(1200, 1000, now - day, now))
        assertFalse(MapPacks.keepPartial(400, 1000, now - 4 * day, now))
        assertFalse(MapPacks.keepPartial(400, 1000, now + day, now))
    }

    @Test
    fun theSpaceNeededCountsWhatIsLeftAndTheExtras() {
        val ele = MapPacks.parseIndex(index).first()
        val margin = MapPacks.spaceNeeded(ele, ele.map.size)
        // Con el mapa ya bajado, solo el margen (no trae búsqueda ni calles).
        assertEquals(margin, MapPacks.spaceNeeded(ele, 500))
        assertEquals(margin + 100, MapPacks.spaceNeeded(ele, 23))
        val full = MapRegion("x", "MX", emptyMap(), ele.bbox, ele.shape, PackFile("u", 1000, "a"), PackFile("s", 10, "b"), PackFile("r", 20, "c"))
        assertEquals(1000 + 4 * 30 + margin, MapPacks.spaceNeeded(full, 0))
    }
}
