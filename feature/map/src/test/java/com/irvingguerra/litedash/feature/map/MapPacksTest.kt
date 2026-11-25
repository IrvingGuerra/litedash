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
        assertTrue(MapPacks.parseIndex("""{"version":3,"base":"x","regions":[]}""").isEmpty())
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
        assertFalse(MapPacks.readIndex("""{"version":3,"online":{"photon":false}}""").photon)
    }

    @Test
    fun theOnlineRouteSwitchIsOnUnlessTheIndexTurnsItOff() {
        // Sin "osrm" (índices de antes): prendida.
        assertTrue(MapPacks.readIndex(index).osrm)
        val off = MapPacks.readIndex("""{"version":1,"base":"x","online":{"photon":true,"osrm":false},"regions":[]}""")
        assertTrue(off.photon)
        assertFalse(off.osrm)
        assertFalse(MapPacks.readCountries("""{"version":3,"online":{"osrm":false},"countries":[]}""").osrm)
        assertTrue(MapPacks.readCountries(countries).osrm)
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
    fun aWholeCountryNeedsAllItsStatesPlusTheBiggestExtra() {
        val margin = MapPacks.spaceNeeded(MapRegion("m", "MX", emptyMap(), doubleArrayOf(0.0, 0.0, 1.0, 1.0), emptyList(), PackFile("u", 0, "a")), 0)
        val a = MapRegion("a", "MX", emptyMap(), doubleArrayOf(0.0, 0.0, 1.0, 1.0), emptyList(), PackFile("u", 1000, "a"), PackFile("s", 10, "b"), PackFile("r", 20, "c"))
        val b = MapRegion("b", "MX", emptyMap(), doubleArrayOf(0.0, 0.0, 1.0, 1.0), emptyList(), PackFile("u", 500, "d"), PackFile("s", 50, "e"), null)
        assertEquals(0, MapPacks.spaceNeededAll(emptyList(), null))
        // Uno solo: lo mismo que bajarlo suelto.
        assertEquals(MapPacks.spaceNeeded(a, 0), MapPacks.spaceNeededAll(listOf(a), null))
        // Los dos completos (1030 + 550) más lo de más del que más pide al bajarse (b: 3 × 50) y el margen.
        assertEquals(1030 + 550 + 3 * 50 + margin, MapPacks.spaceNeededAll(listOf(a, b), null))
        // Lo que ya lleva el mapa a medias se descuenta.
        assertEquals(1030 + 550 + 3 * 50 + margin - 400, MapPacks.spaceNeededAll(listOf(a, b), PausedPack("a", "a", 400)))
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

    // ---- Formato 2 (calles dibujadas del .ldm): countries2.json + countries2/<cc>.json ----

    private val indexV2 = """
        {"version":2,"release":"mx-z","base":"https://example.org/mx-z/","regions":[
          {"id":"mx-nuevo","country":"MX","format":2,"name":{"es":"Nuevo"},"bbox":[-100,19,-98,21],"shape":[],
           "files":{"map":{"name":"a.pmtiles","size":1000,"sha256":"m"},
                    "search":{"name":"a.srch.gz","size":10,"unpacked":30,"sha256":"s"},
                    "routes":{"name":"a.ldm.gz","size":200,"unpacked":450,"sha256":"r"}}},
          {"id":"mx-viejo","country":"MX","name":{"es":"Viejo"},"bbox":[-100,19,-98,21],"shape":[],
           "files":{"map":{"name":"b.pmtiles","size":5,"sha256":"b"}}},
          {"id":"mx-sin-calles","country":"MX","format":2,"name":{"es":"Sin"},"bbox":[-100,19,-98,21],"shape":[],
           "files":{"map":{"name":"c.pmtiles","size":5,"sha256":"c"},"search":{"name":"c.srch.gz","size":1,"sha256":"d"}}},
          {"id":"mx-futuro","country":"MX","format":3,"name":{"es":"Futuro"},"bbox":[-100,19,-98,21],"shape":[],
           "files":{"map":{"name":"d.pmtiles","size":5,"sha256":"e"}}}
        ]}
    """.trimIndent()

    @Test
    fun theV2IndexSaysTheFormatOfEachRegion() {
        val regions = MapPacks.parseIndex(indexV2)
        // Un formato 2 sin sus tres archivos y uno más nuevo que esta app se saltan; uno sin "format" es del 1.
        assertEquals(listOf("mx-nuevo", "mx-viejo"), regions.map { it.id })
        val nuevo = regions[0]
        assertEquals(2, nuevo.format)
        assertEquals(1, regions[1].format)
        assertEquals(450, nuevo.routes!!.unpacked)
        assertEquals("https://example.org/mx-z/a.ldm.gz", nuevo.routes!!.url)
        // En el índice de la versión 1, "format" no cuenta (las apps viejas no lo leen: nada cambia para ellas).
        assertEquals(1, MapPacks.parseIndex(indexV2.replace(""""version":2""", """"version":1""")).first().format)
    }

    @Test
    fun aV2PackNeedsRoomForAllThreeFilesOpened() {
        val nuevo = MapPacks.parseIndex(indexV2).first()
        val margin = MapPacks.spaceNeeded(MapRegion("m", "MX", emptyMap(), doubleArrayOf(0.0, 0.0, 1.0, 1.0), emptyList(), PackFile("u", 0, "a")), 0)
        // Los tres gzip (1210) más la búsqueda y las calles abiertas (lo que dice el índice: 30 + 450).
        assertEquals(1210 + 480 + margin, MapPacks.spaceNeeded(nuevo, 0))
        // Lo ya bajado de cualquiera de los tres se descuenta.
        assertEquals(1210 + 480 + margin - 1100, MapPacks.spaceNeeded(nuevo, 1100))
        // Sin "unpacked", ~3 veces el gzip.
        val guess = MapRegion("g", "MX", emptyMap(), nuevo.bbox, emptyList(), PackFile("u", 100, "a"), PackFile("s", 10, "b"), PackFile("r", 20, "c"), 2)
        assertEquals(130 + 3 * 30 + margin, MapPacks.spaceNeeded(guess, 0))
    }

    @Test
    fun aHalfDownloadedV2PackIsResumedOnlyForTheSameThreeFiles() {
        val nuevo = MapPacks.parseIndex(indexV2).first()
        assertEquals(700, MapPacks.partialOf(nuevo, PausedPack("mx-nuevo", nuevo.packKey, 700)))
        // Solo la huella del mapa no basta: cambió el .ldm, es otro paquete.
        assertEquals(0, MapPacks.partialOf(nuevo, PausedPack("mx-nuevo", "m", 700)))
        assertEquals("b", MapPacks.parseIndex(indexV2)[1].packKey)
    }

    @Test
    fun aSavedPackKeepsItsFormat() {
        val saved = InstalledPack("mx-nuevo", "m", 1000, 1L, doubleArrayOf(0.0, 0.0, 1.0, 1.0), emptyList(), "s", "r", format = 2)
        assertEquals(2, MapPacks.packFromJson(MapPacks.packToJson(saved)).format)
        // Lo guardado por una versión de antes no lo dice: formato 1.
        val old = MapPacks.packToJson(saved).apply { remove("format") }
        assertEquals(1, MapPacks.packFromJson(old).format)
    }

    @Test
    fun theV2CountryIndexPointsToV2OrV1CountryFiles() {
        val read = MapPacks.readCountries(
            countries.replace(""""version":1""", """"version":2""").replace("countries/mx.json", "countries2/mx.json"),
        )
        // Un país ya rearmado apunta a countries2/; uno que aún no, a su archivo de siempre.
        assertEquals(listOf("countries2/mx.json", "countries/ca.json"), read.countries.map { it.file })
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
        assertEquals(128, mx.total)
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
        val other = MapPacks.readCountries("""{"version":3,"online":{"photon":false},"countries":[]}""")
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

    @Test
    fun anyIndexCanTurnOnlineOff() {
        val s = OnlineSwitches()
        assertTrue(s.search && s.routing)
        // El índice por país apaga la ruta; luego llega `regions.json` con todo prendido: sigue apagada.
        s.set(OnlineSwitches.COUNTRIES, photon = true, osrm = false)
        s.set(OnlineSwitches.REGIONS, photon = true, osrm = true)
        assertTrue(s.search)
        assertFalse(s.routing)
        // Al revés también, y con la búsqueda.
        s.set(OnlineSwitches.COUNTRIES, photon = true, osrm = true)
        s.set(OnlineSwitches.REGIONS, photon = false, osrm = false)
        assertFalse(s.search)
        assertFalse(s.routing)
        // Los dos lo vuelven a prender.
        s.set(OnlineSwitches.REGIONS, photon = true, osrm = true)
        assertTrue(s.search && s.routing)
    }

    @Test
    fun aV2UpdateThatOnlyChangesTheStreetsIsAnUpdate() {
        val nuevo = MapPacks.parseIndex(indexV2).first()
        val saved = InstalledPack("mx-nuevo", "m", 1000, 1, nuevo.bbox, emptyList(), searchSha = "s", routesSha = "r", format = 2)
        assertFalse(MapPacks.isOutdated(saved, nuevo))
        // Solo cambió el .ldm (los carriles) o solo la búsqueda: también es actualización.
        assertTrue(MapPacks.isOutdated(saved.copy(routesSha = "r-viejo"), nuevo))
        assertTrue(MapPacks.isOutdated(saved.copy(searchSha = "s-viejo"), nuevo))
        assertTrue(MapPacks.isOutdated(saved.copy(sha256 = "m-viejo"), nuevo))
        // Unas calles que aún no se bajan no son una versión vieja (para eso está «Bajar calles»).
        assertFalse(MapPacks.isOutdated(saved.copy(routesSha = ""), nuevo))
        // En el formato 1 solo cuenta el mapa (la búsqueda y las calles se bajan solas).
        val viejo = MapPacks.parseIndex(indexV2)[1]
        assertFalse(MapPacks.isOutdated(InstalledPack("mx-viejo", "b", 5, 1, viejo.bbox, emptyList(), routesSha = "otra"), viejo))
    }

    @Test
    fun onlyTilesInTheChangedBoxAreRedrawn() {
        val colima = doubleArrayOf(-104.7, 18.6, -103.4, 19.6)
        // La tesela del 14 del centro de Colima (-103.72, 19.24) sí; una de Monterrey, no.
        assertTrue(tileTouches(tileKey(14, 3471, 7299), colima))
        assertFalse(tileTouches(tileKey(14, 3626, 6983), colima))
        // Una del 2 que lo abarca todo, sí.
        assertTrue(tileTouches(tileKey(2, 0, 1), colima))
    }

    @Test
    fun theMapMetadataSaysIfItHasStreets() {
        assertTrue(PmTiles.listsRoads("""{"vector_layers":[{"id":"water"},{"id":"transportation"}]}"""))
        assertFalse(PmTiles.listsRoads("""{"vector_layers":[{"id":"water"},{"id":"transportation_name"}]}"""))
        // Sin la lista de capas (otro armador), se supone que sí.
        assertTrue(PmTiles.listsRoads("""{"name":"transportation"}"""))
        // "transportation" en otro lado (una descripción) no cuenta.
        assertFalse(PmTiles.listsRoads("""{"description":"sin transportation","vector_layers":[{"id":"water"}]}"""))
    }
}
