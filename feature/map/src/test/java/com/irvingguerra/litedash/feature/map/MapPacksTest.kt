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

    @Test
    fun aPausedMapCountsOnlyForItsOwnRegionAndFile() {
        val full = MapRegion("x", "MX", emptyMap(), doubleArrayOf(0.0, 0.0, 1.0, 1.0), emptyList(), PackFile("u", 1000, "abc"), null, null)
        // Lo que lleva el .part se descuenta al reanudar: con 600 de 1000 bajados, faltan 400 más el margen.
        assertEquals(600, MapPacks.partialOf(full, PausedPack("x", "ABC", 600)))
        assertEquals(MapPacks.spaceNeeded(full, 0) - 600, MapPacks.spaceNeeded(full, MapPacks.partialOf(full, PausedPack("x", "abc", 600))))
        // De otra región o de otra versión del archivo: de cero.
        assertEquals(0, MapPacks.partialOf(full, PausedPack("y", "abc", 600)))
        assertEquals(0, MapPacks.partialOf(full, PausedPack("x", "def", 600)))
        assertEquals(0, MapPacks.partialOf(full, null))
    }

    // ---- Índice por país (countries.json + countries/<cc>.json) ----

    private val countries = """
        {"version":1,"release":"mx-x+us-y","source":"OSM","online":{"photon":false},"countries":[
          {"code":"MX","file":"countries/mx.json","sha256":"aa","size":10,"total":128,"bbox":[-100,19,-98,21],
           "regions":{"mx-ele":{"es":"Ele","en":"El"},"mx-chico":{"es":"Chico"}}},
          {"code":"US","file":"../regions.json","sha256":"bb","size":10,"total":1,"bbox":[-125,24,-66,49],
           "regions":{"us-x":{"en":"X"}}},
          {"code":"CO","file":"countries/co.json","sha256":"cc","size":10,"total":1,"bbox":[-80,-5,-66,13],"regions":{}},
          {"code":"CA","file":"countries/ca.json","sha256":"dd","size":10,"total":1,"bbox":[-141,41,-52,84],
           "regions":{"ca-on":{"en":"Ontario","es":"Ontario"}}}
        ]}
    """.trimIndent()

    @Test
    fun readsTheCountryIndexAndSkipsBrokenCountries() {
        val read = MapPacks.readCountries(countries)
        // US apunta fuera de countries/ y CO no trae regiones: se saltan.
        assertEquals(listOf("MX", "CA"), read.countries.map { it.code })
        assertFalse(read.photon)
        val mx = read.countries.first()
        assertEquals("countries/mx.json", mx.file)
        assertEquals("aa", mx.sha256)
        assertEquals(setOf("mx-ele", "mx-chico"), mx.regions.map { it.id }.toSet())
        assertTrue(mx.regions.all { it.country == "MX" })
        val ele = mx.regions.first { it.id == "mx-ele" }
        assertEquals("El", ele.name("en"))
        assertEquals("Ele", ele.name("es"))
        // Sin inglés, el id (como en MapRegion).
        assertEquals("mx-chico", mx.regions.first { it.id == "mx-chico" }.name("de"))
        assertTrue(mx.covers(20.0, -99.0))
        assertFalse(mx.covers(25.0, -99.0))
    }

    @Test
    fun otherVersionsOfTheCountryIndexAreIgnoredButKeepPhoton() {
        val other = MapPacks.readCountries("""{"version":2,"online":{"photon":false},"countries":[]}""")
        assertTrue(other.countries.isEmpty())
        assertFalse(other.photon)
    }

    /** Lo que hace que la app use `regions.json`: un índice por país que no existe (404) o que no es uno. */
    @Test
    fun aMissingCountryIndexFallsBackToRegionsJson() {
        assertTrue(runCatching { MapPacks.readCountries("404: Not Found") }.isFailure)
        // regions.json no es un índice por país (no trae "countries")...
        assertTrue(runCatching { MapPacks.readCountries(index) }.isFailure)
        // ...pero se sigue leyendo como siempre.
        assertEquals(2, MapPacks.parseIndex(index).size)
    }

    /** El archivo de un país tiene el esquema de regions.json (sin "online"): se lee igual y no apaga Photon. */
    @Test
    fun aCountryFileReadsLikeRegionsJson() {
        val file = index.replace(""""release":"mx-x",""", """"release":"mx-x","source":"OSM",""")
        val read = MapPacks.readIndex(file)
        assertEquals(listOf("mx-ele", "mx-chico"), read.regions.map { it.id })
        assertTrue(read.photon)
    }

    @Test
    fun sha256OfBytes() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", MapPacks.sha256Of(ByteArray(0)))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", MapPacks.sha256Of("abc".toByteArray()))
    }
}
