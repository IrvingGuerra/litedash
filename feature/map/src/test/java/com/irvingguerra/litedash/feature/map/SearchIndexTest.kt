package com.irvingguerra.litedash.feature.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SearchIndexTest {
    @Test
    fun normalizesLikeThePythonBuilder() {
        assertEquals(listOf("avenida", "insurgentes", "sur", "300"), normalizeForSearch("Av. Insurgentes Sur 300"))
        assertEquals(listOf("north", "main", "street"), normalizeForSearch("N Main St"))
        assertEquals(listOf("hauptstrasse"), normalizeForSearch("Hauptstraße"))
        assertEquals(listOf("渋谷", "谷区"), normalizeForSearch("渋谷区"))
        assertEquals(listOf("peña", "colorada", "jose", "maria"), normalizeForSearch("Peña Colorada de José María"))
        assertEquals(listOf("渋", "station"), normalizeForSearch("渋 Station"))
    }

    @Test
    fun findsByPrefixAndAllWords() = withIndex { index ->
        val hits = index.search("insur", null, null)
        assertEquals(setOf("Avenida Insurgentes Sur", "Insurgentes Norte"), hits.map { it.name }.toSet())
        // Las dos palabras: solo la que tiene las dos.
        assertEquals(listOf("Avenida Insurgentes Sur"), index.search("av insurgentes su", null, null).map { it.name })
        assertTrue(index.search("insurgentes oaxaca", null, null).isEmpty())
        val hit = index.search("farmacia guadalajara", null, null).single()
        assertEquals(PlaceKind.PLACE, hit.kind)
        assertEquals(PlaceCategory.PHARMACY, hit.category)
        assertEquals("Centro", hit.locality)
        assertEquals("Guadalajara Pharmacy", hit.alt)
        assertEquals(20.6767, hit.lat, 1e-4)
        assertEquals(-103.3475, hit.lon, 1e-4)
        assertEquals(false, hit.online)
    }

    @Test
    fun ordersByExactThenDistance() = withIndex { index ->
        // "colima": la ciudad (exacta e importante) antes que la calle y la farmacia; entre las calles, la más cercana.
        val hits = index.search("colima", 19.0, -103.0)
        assertEquals("Colima", hits.first().name)
        assertEquals(PlaceKind.CITY, hits.first().kind)
        val streets = hits.filter { it.name == "Calle Colima" }
        assertEquals(listOf("San Pedro", "Las Flores"), streets.map { it.locality })
        assertTrue(streets[0].distanceKm!! < streets[1].distanceKm!!)
        // Exacta primero: "colimas" no es exacta para "colima".
        assertTrue(hits.indexOfFirst { it.name == "Colimas" } > hits.indexOfFirst { it.name == "Calle Colima" })
        assertNull(index.search("colima", null, null).first().distanceKm)
    }

    @Test
    fun theWholeNameBeatsANearbyBusinessThatContainsIt() = withIndex { index ->
        // Junto al bar: el Coliseo (el nombre es lo escrito) primero, luego el que empieza así y al final el que lo contiene.
        val hits = index.search("colosseo", 41.8950, 12.4800)
        assertEquals(listOf("Colosseo", "Colosseo Pizza", "Bar Colosseo"), hits.map { it.name })
        // Aún escribiendo: los que empiezan así (por distancia) antes que el que lo contiene.
        val typing = index.search("coloss", 41.8950, 12.4800).map { it.name }
        assertEquals(listOf("Colosseo Pizza", "Colosseo", "Bar Colosseo"), typing)
        // Entre los que solo contienen la palabra manda la distancia, no el tipo: la farmacia de junto antes que la calle.
        val colima = index.search("colima", 21.0, -102.0)
        assertTrue(colima.indexOfFirst { it.name == "Farmacia Colima" } < colima.indexOfFirst { it.name == "Calle Colima" })
    }

    @Test
    fun aNearbyBranchOfAChainBeatsAFarOneWithTheBareName() = withIndex { index ->
        // «Starbucks» a ~40 km y «Starbucks Reforma 222» a ~1 km: la cercana primero.
        val hits = index.search("starbucks", 19.4300, -99.1300)
        assertEquals(listOf("Starbucks Reforma 222", "Starbucks"), hits.map { it.name })
        // La ciudad sí va antes que un negocio cercano que lleva su nombre.
        val gdl = index.search("guadalajara", 20.6768, -103.3476)
        assertEquals("Guadalajara", gdl.first().name)
        assertTrue(gdl.indexOfFirst { it.name == "Tortas Guadalajara" } > 0)
        // La regla: un nombre igual corriente baja solo si está a más de 10 veces la distancia del mejor que empieza igual.
        assertEquals(TIER_STARTS, rankTier(TIER_EQUAL, anchor = false, km = 40.0, bestStartsKm = 1.0))
        assertEquals(TIER_EQUAL, rankTier(TIER_EQUAL, anchor = false, km = 0.03, bestStartsKm = 115.0))
        assertEquals(TIER_EQUAL, rankTier(TIER_EQUAL, anchor = true, km = 400.0, bestStartsKm = 1.0))
        // Una colonia o una calle «Estadio» a cientos de km no es una población: baja y compite por distancia.
        assertEquals(false, isAnchor(PlaceKind.NEIGHBOURHOOD, PlaceCategory.of(0)))
        assertEquals(false, isAnchor(PlaceKind.STREET, PlaceCategory.of(0)))
        assertEquals(true, isAnchor(PlaceKind.CITY, PlaceCategory.of(0)))
        assertEquals(true, isAnchor(PlaceKind.PLACE, PlaceCategory.SIGHT))
        assertEquals(TIER_EQUAL, rankTier(TIER_EQUAL, anchor = false, km = 40.0, bestStartsKm = null))
        assertEquals(TIER_CONTAINS, rankTier(TIER_CONTAINS, anchor = false, km = 40.0, bestStartsKm = 1.0))
    }

    @Test
    fun aNearbyExactBusinessBeatsAFarLandmarkThatStartsLikeIt() = withIndex { index ->
        // Con el GPS en Tepic: el OXXO a 30 m antes que «Oxxo mirador» (lugar turístico a 115 km).
        assertEquals(listOf("OXXO", "Oxxo mirador"), index.search("oxxo", 21.5000, -104.9000).map { it.name })
    }

    @Test
    fun startingLikeTheQueryGoesByDistanceNotByKind() = withIndex { index ->
        // «Estadio»: el estadio a 1.6 km antes que la colonia «Estadio Poniente» a 180 km.
        assertEquals(listOf("Estadio Olímpico", "Estadio Poniente"), index.search("estadio", 21.5000, -104.9000).map { it.name })
    }

    @Test
    fun theCityWithTheExactNameComesFirst() = withIndex { index ->
        val hits = index.search("tepic", 21.5000, -104.9000)
        assertEquals("Tepic", hits.first().name)
        assertEquals(PlaceKind.CITY, hits.first().kind)
    }

    @Test
    fun onlineResultsFollowTheSameRule() {
        fun hit(name: String, kind: PlaceKind, km: Double?, category: PlaceCategory = PlaceCategory.OTHER) =
            SearchHit(name, null, null, null, kind, category, 0.0, 0.0, km, online = true)
        // Photon puso las calles cercanas con la palabra antes que la ciudad.
        val photon = listOf(
            hit("Avenida Mazatlán", PlaceKind.STREET, 2.0),
            hit("Calle Mazatlán", PlaceKind.STREET, 3.0),
            hit("Mazatlán", PlaceKind.CITY, 260.0),
        )
        assertEquals("Mazatlán", orderHits(photon, "mazatlan").first().name)
        val chain = listOf(hit("Starbucks", PlaceKind.PLACE, 40.0, PlaceCategory.FOOD), hit("Starbucks Reforma 222", PlaceKind.PLACE, 1.0, PlaceCategory.FOOD))
        assertEquals("Starbucks Reforma 222", orderHits(chain, "Starbucks").first().name)
        // Sin saber dónde estás, en cada escalón queda el orden de Photon.
        val noGps = listOf(hit("Calle Tepic", PlaceKind.STREET, null), hit("Tepic Centro", PlaceKind.PLACE, null), hit("Tepic", PlaceKind.CITY, null))
        assertEquals(listOf("Tepic", "Tepic Centro", "Calle Tepic"), orderHits(noGps, "Tepic").map { it.name })
    }

    @Test
    fun tiersFollowTheWholeName() {
        val q = listOf("colosseo")
        assertEquals(0, tier(listOf("colosseo"), q))
        assertEquals(1, tier(listOf("colosseo", "pizza"), q))
        assertEquals(2, tier(listOf("bar", "colosseo"), q))
        assertEquals(3, tier(listOf("colosseum"), listOf("colos")))
        assertEquals(4, tier(listOf("bar", "colosseum"), listOf("colos")))
    }

    @Test
    fun dropsDuplicatesOfTheSamePlace() = withIndex { index ->
        // "Calle Hidalgo" sale 3 veces en San Pedro (una por celda): queda la más cercana.
        val hits = index.search("hidalgo", 20.0, -103.0)
        assertEquals(1, hits.count { it.name == "Calle Hidalgo" && it.locality == "San Pedro" })
        assertEquals(20.0, hits.first { it.name == "Calle Hidalgo" }.lat, 0.01)
    }

    @Test
    fun toleratesOneTypo() = withIndex { index ->
        assertEquals(listOf("Insurgentes Norte"), index.search("insurjentes norte", null, null).map { it.name })
        assertEquals("Guadalajara", index.search("guadalajra", null, null).first().name)
        assertEquals("Colima", index.search("colma", null, null).first().name)
        // Palabras de menos de 5 letras no se corrigen.
        assertTrue(index.search("cola", null, null).isEmpty())
    }

    @Test
    fun spansManyBlocks() = withIndex { index ->
        // Las palabras de relleno llenan varios bloques de 16: cada una se encuentra.
        for (i in 0 until 40) {
            val name = "Relleno x%02d".format(i)
            assertEquals(name, index.search(name, null, null).single().name)
        }
        assertEquals(20, index.search("relleno", null, null).size)
        assertEquals(40, index.search("relleno", null, null, limit = 100).size)
    }

    @Test
    fun damagedFilesDoNotOpen() {
        val good = build(entries())
        assertNull(open(byteArrayOf(1, 2, 3)))
        assertNull(open(good.copyOf().also { it[0] = 'X'.code.toByte() }))
        assertNull(open(good.copyOf().also { it[4] = 2 }))
        assertNull(open(good.copyOf(good.size - 3)))
        assertNull(open(good.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(76, 16) }))
        assertNotNull(open(good))
    }

    private class Entry(
        val kind: Int, val name: String, val lat: Double, val lon: Double,
        val alt: String? = null, val locality: String? = null, val category: Int = 0,
    )

    private fun entries() = listOf(
        Entry(0, "Avenida Insurgentes Sur", 19.40, -99.17, locality = "Del Valle"),
        Entry(0, "Insurgentes Norte", 19.48, -99.14, locality = "Lindavista"),
        Entry(1, "Colima", 19.24, -103.72),
        Entry(0, "Calle Colima", 20.60, -103.30, locality = "Las Flores"),
        Entry(0, "Calle Colima", 19.10, -103.10, locality = "San Pedro"),
        Entry(8, "Farmacia Colima", 21.00, -102.00, locality = "Norte", category = 3),
        Entry(0, "Colimas", 19.00, -103.00, locality = "Ojo"),
        Entry(1, "Guadalajara", 20.67, -103.35),
        Entry(8, "Farmacia Guadalajara", 20.6767, -103.3475, alt = "Guadalajara Pharmacy", locality = "Centro", category = 3),
        Entry(0, "Calle Hidalgo", 20.30, -103.00, locality = "San Pedro"),
        Entry(0, "Calle Hidalgo", 20.00, -103.00, locality = "San Pedro"),
        Entry(0, "Calle Hidalgo", 20.50, -103.00, locality = "San Pedro"),
        Entry(8, "Bar Colosseo", 41.8951, 12.4801, locality = "Monti", category = 4),
        Entry(8, "Colosseo Pizza", 41.8955, 12.4810, locality = "Monti", category = 4),
        Entry(8, "Colosseo", 41.8902, 12.4922, locality = "Celio", category = 14),
        Entry(8, "Starbucks", 19.7900, -99.1300, locality = "Norte", category = 4),
        Entry(8, "Starbucks Reforma 222", 19.4390, -99.1300, locality = "Juárez", category = 4),
        Entry(8, "Tortas Guadalajara", 20.6769, -103.3477, locality = "Centro", category = 4),
        // Tepic: el OXXO a ~30 m, «Oxxo mirador» turístico a ~115 km, el estadio a ~1.6 km, la colonia a ~180 km.
        Entry(8, "OXXO", 21.5002, -104.9001, locality = "Centro", category = 6),
        Entry(8, "Oxxo mirador", 22.5300, -104.9000, locality = "Sierra", category = 14),
        Entry(8, "Estadio Olímpico", 21.5145, -104.9000, locality = "Centro", category = 0),
        Entry(5, "Estadio Poniente", 23.1200, -104.9000),
        Entry(1, "Tepic", 21.5085, -104.8955),
        Entry(8, "Tepic Tacos", 21.5001, -104.9001, locality = "Centro", category = 4),
    ) + (0 until 40).map { Entry(8, "Relleno x%02d".format(it), 18.0 + it * 0.01, -104.0) }

    private fun withIndex(test: (SearchIndex) -> Unit) {
        val f = File.createTempFile("srch", ".srch1")
        try {
            f.writeBytes(build(entries()))
            SearchIndex.open(f)!!.use(test)
        } finally {
            f.delete()
        }
    }

    private fun open(bytes: ByteArray): SearchIndex? {
        val f = File.createTempFile("srch", ".srch1")
        try {
            f.writeBytes(bytes)
            return SearchIndex.open(f)?.also { it.close() }
        } finally {
            f.delete()
        }
    }

    /** Un escritor SRCH1 mínimo, igual que main() de armar_indice.py. */
    private fun build(list: List<Entry>): ByteArray {
        val minLat = list.minOf { it.lat }
        val minLon = list.minOf { it.lon }
        val maxLat = list.maxOf { it.lat }
        val maxLon = list.maxOf { it.lon }
        val names = LinkedHashMap<String, Int>()
        fun id(s: String?) = if (s == null) 0xFFFFFF else names.getOrPut(s) { names.size }
        val records = ByteArrayOutputStream()
        val postings = HashMap<String, MutableList<Int>>()
        fun u24(v: Int) { records.write(v and 0xFF); records.write(v shr 8 and 0xFF); records.write(v shr 16 and 0xFF) }
        list.forEachIndexed { i, e ->
            records.write(e.kind); records.write(e.category)
            u24(((e.lat - minLat) / (maxLat - minLat) * 0xFFFFFF).toInt())
            u24(((e.lon - minLon) / (maxLon - minLon) * 0xFFFFFF).toInt())
            u24(id(e.name)); u24(id(e.alt)); u24(id(e.locality))
            for (w in normalizeForSearch(listOfNotNull(e.name, e.alt).joinToString(" ")).toSet()) {
                postings.getOrPut(w) { mutableListOf() } += i
            }
        }
        val offsets = ByteArrayOutputStream()
        val blob = ByteArrayOutputStream()
        fun u32(out: ByteArrayOutputStream, v: Int) = repeat(4) { out.write(v shr (8 * it) and 0xFF) }
        for (s in names.keys) { u32(offsets, blob.size()); blob.write(s.toByteArray()) }
        u32(offsets, blob.size())
        val words = postings.keys.sortedWith { a, b -> compareBytes(a.toByteArray(), b.toByteArray()) }
        val dict = ByteArrayOutputStream()
        val table = ByteArrayOutputStream()
        fun varint(n: Int) {
            var v = n
            while (v >= 0x80) { dict.write(v and 0x7F or 0x80); v = v ushr 7 }
            dict.write(v)
        }
        words.chunked(16).forEach { block ->
            u32(table, dict.size())
            var prev = ByteArray(0)
            for (w in block) {
                val raw = w.toByteArray()
                var common = 0
                while (common < minOf(prev.size, raw.size) && prev[common] == raw[common]) common++
                varint(common); varint(raw.size - common); dict.write(raw, common, raw.size - common)
                val ids = postings.getValue(w)
                varint(ids.size)
                var last = 0
                for (x in ids) { varint(x - last); last = x }
                prev = raw
            }
        }
        val header = ByteBuffer.allocate(96).order(ByteOrder.LITTLE_ENDIAN)
        header.put("SRCH".toByteArray()).putShort(1).putShort(0)
        header.putInt(list.size).putInt(words.size).putInt(names.size).putInt((words.size + 15) / 16)
        header.putDouble(minLat).putDouble(minLon).putDouble(maxLat).putDouble(maxLon)
        val oRec = 96
        val oNoff = oRec + records.size()
        val oBlob = oNoff + offsets.size()
        val oDict = oBlob + blob.size()
        val oTable = oDict + dict.size()
        header.putInt(oRec).putInt(oNoff).putInt(oBlob).putInt(oDict).putInt(oTable).putInt(17)
        return header.array() + records.toByteArray() + offsets.toByteArray() + blob.toByteArray() +
            dict.toByteArray() + table.toByteArray()
    }

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return a.size - b.size
    }
}
