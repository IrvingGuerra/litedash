package com.irvingguerra.litedash.feature.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineMapsTest {
    @Test
    fun aMissingOrEmptyListIsTrustedButADamagedOneIsNot() {
        val f = java.io.File.createTempFile("areas", ".json")
        try {
            f.delete()
            // Nunca guardó una zona: lista cierta y vacía (se puede barrer lo de una zona cancelada).
            assertEquals(emptyList<OfflineArea>(), readAreaList(f))
            // Borró todas sus zonas: igual.
            f.writeText("[]")
            assertEquals(emptyList<OfflineArea>(), readAreaList(f))
            f.writeText("""[{"id":"a","lat":19.4,"lon":-99.1,"km":10,"tiles":5,"bytes":9,"savedAt":1}]""")
            assertEquals("a", readAreaList(f)!!.single().id)
            // Un apagón al guardarla (0 bytes o cortada): no se toca nada.
            f.writeText("")
            assertEquals(null, readAreaList(f))
            f.writeText("""[{"id":"a","lat"""")
            assertEquals(null, readAreaList(f))
        } finally {
            f.delete()
        }
    }

    private val zocaloLat = 19.4326
    private val zocaloLon = -99.1332

    @Test
    fun onlyTilesInsideTheCircle() {
        val keys = areaTiles(zocaloLat, zocaloLon, 25)
        val z14 = keys.filter { keyZ(it) == 14 }
        // Una tesela del 14 mide ~2.3 km aquí: el círculo de 25 km lleva ~369, el cuadrado ~470.
        assertTrue(z14.size in 340..400)
        for (k in z14) {
            val d = distanceKm(zocaloLat, zocaloLon, tileLat(keyY(k) + 0.5, 14), tileLon(keyX(k) + 0.5, 14))
            assertTrue("a $d km", d <= 25.0)
        }
        // La de en medio va primero (de la más cercana a la más lejana) y no hay repetidas.
        assertEquals(tileKey(14, tileX(zocaloLon, 14).toInt(), tileY(zocaloLat, 14).toInt()), z14.first())
        assertEquals(keys.size, keys.toSet().size)
        // Las esquinas del cuadrado quedan fuera.
        val corner = tileKey(14, tileX(zocaloLon, 14).toInt() + 10, tileY(zocaloLat, 14).toInt() + 10)
        assertFalse(corner in z14)
    }

    @Test
    fun parentsCoverEveryDataTile() {
        val keys = areaTiles(zocaloLat, zocaloLon, 10)
        val z14 = keys.filter { keyZ(it) == 14 }
        val z13 = keys.filter { keyZ(it) == 13 }.toSet()
        val z12 = keys.filter { keyZ(it) == 12 }.toSet()
        assertEquals(z14.map { tileKey(13, keyX(it) shr 1, keyY(it) shr 1) }.toSet(), z13)
        assertEquals(z14.map { tileKey(12, keyX(it) shr 2, keyY(it) shr 2) }.toSet(), z12)
        // Solo zooms 12–14 y en ese orden: primero lo que se ve de cerca.
        assertEquals(keys.size, z14.size + z13.size + z12.size)
        assertEquals(listOf(14, 13, 12), keys.map { keyZ(it) }.distinct())
        assertTrue(z13.size < z14.size && z12.size < z13.size)
    }

    @Test
    fun deletingKeepsSharedTiles() {
        val a = area("a", zocaloLat, zocaloLon, 10)
        // A 15 km al norte: se enciman un poco.
        val b = area("b", zocaloLat + 0.135, zocaloLon, 10)
        val far = area("far", 25.67, -100.31, 10)
        // Como lo hace delete(): las de la zona menos las de las que quedan.
        val orphans = areaTiles(a.lat, a.lon, a.km).toSet() - tilesOf(listOf(b, far))
        val tilesA = areaTiles(a.lat, a.lon, a.km).toSet()
        val tilesB = areaTiles(b.lat, b.lon, b.km).toSet()
        val shared = tilesA intersect tilesB
        assertTrue(shared.isNotEmpty())
        assertTrue(orphans.none { it in shared })
        assertEquals(tilesA - shared, orphans)
        // Sin otras zonas, se va todo.
        assertEquals(tilesA, areaTiles(a.lat, a.lon, a.km).toSet() - tilesOf(emptyList()))
    }

    @Test
    fun strayTilesSkipKeptAndOtherFiles() {
        val kept = tileKey(14, 3680, 7290)
        val stray = tileKey(13, 1840, 3645)
        val names = listOf("14_3680_7290", "13_1840_3645", "areas.json", "areas.json123.tmp", "14_3680_72905678.tmp", "x_y_z")
        assertEquals(listOf(stray), strayTiles(names, setOf(kept)))
        assertEquals(emptyList<Long>(), strayTiles(names, setOf(kept, stray)))
    }

    @Test
    fun coversInsideTheRadius() {
        val a = area("a", zocaloLat, zocaloLon, 10)
        assertTrue(a.contains(zocaloLat + 0.05, zocaloLon))   // ~5.6 km
        assertFalse(a.contains(zocaloLat + 0.1, zocaloLon))   // ~11 km
        // En la orilla (9.8 km) la tesela de debajo puede no haberse bajado: ya no cuenta como adentro.
        assertFalse(a.contains(zocaloLat + 0.088, zocaloLon))
        // Todo lo que cuenta como adentro tiene su tesela del 14 en la zona.
        val keys = areaTiles(a.lat, a.lon, a.km).toSet()
        for (i in 0..40) for (j in 0..40) {
            val lat = zocaloLat - 0.1 + i * 0.005
            val lon = zocaloLon - 0.1 + j * 0.005
            if (a.contains(lat, lon)) assertTrue("$lat,$lon", tileKey(14, tileX(lon, 14).toInt(), tileY(lat, 14).toInt()) in keys)
        }
        assertEquals(111.2, distanceKm(0.0, 0.0, 1.0, 0.0), 0.1)
    }

    @Test
    fun sameSpotSameId() {
        assertEquals(areaId(19.4326, -99.1332, 25), areaId(19.4329, -99.1334, 25))
        assertFalse(areaId(19.4326, -99.1332, 25) == areaId(19.4326, -99.1332, 10))
    }

    private fun area(id: String, lat: Double, lon: Double, km: Int) = OfflineArea(id, lat, lon, km, 0, 0L, 0L)

    @Test
    fun radiiStayWithinOpenFreeMapLimits() {
        // Cerca del ecuador (CDMX) caben los radios que se ofrecen (10, 20, 30 km); 50 km ya no.
        for (km in listOf(10, 20, 30)) assertTrue("$km km", OfflineMaps.withinLimits(areaTiles(zocaloLat, zocaloLon, km).size))
        assertFalse(OfflineMaps.withinLimits(areaTiles(zocaloLat, zocaloLon, 50).size))
        // Más al norte las teselas son más chicas: 30 km en Edimburgo pasa del tope y 20 km cabe.
        assertFalse(OfflineMaps.withinLimits(areaTiles(55.95, -3.19, 30).size))
        assertTrue(OfflineMaps.withinLimits(areaTiles(55.95, -3.19, 20).size))
        assertFalse(OfflineMaps.withinLimits(OfflineMaps.MAX_TILES + 1))
    }
}
