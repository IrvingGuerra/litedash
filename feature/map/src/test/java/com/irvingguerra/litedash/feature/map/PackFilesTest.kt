package com.irvingguerra.litedash.feature.map

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Lo que pasa con un paquete del formato 2 ya bajado antes de usarlo (abrir, revisar, ponerlo en su lugar, cancelar a la
 * mitad), con una carpeta temporal y el LDM1 de prueba de Colima.
 */
class PackFilesTest {
    private lateinit var dir: File

    /** El LDM1 con dibujo de prueba, abierto. */
    private val ldm: ByteArray by lazy {
        PackFilesTest::class.java.getResourceAsStream("/ldm/colima-centro.ldm.gz")!!.use { GZIPInputStream(it).readBytes() }
    }

    @Before
    fun makeDir() {
        dir = File.createTempFile("packs", "").apply { delete(); mkdirs() }
    }

    @After
    fun dropDir() {
        dir.deleteRecursively()
    }

    private fun gz(name: String, bytes: ByteArray) = File(dir, name).apply { GZIPOutputStream(outputStream()).use { it.write(bytes) } }

    /** Un PMTiles v3 mínimo (solo cabecera y `metadata`): lo que se revisa de un mapa bajado. */
    private fun pmtiles(name: String, layers: List<String>): File {
        val meta = """{"vector_layers":[${layers.joinToString { """{"id":"$it"}""" }}]}""".toByteArray()
        val head = ByteBuffer.allocate(127).order(ByteOrder.LITTLE_ENDIAN)
        head.put("PMTiles".toByteArray()).put(3)
        head.putLong(8, 127).putLong(16, 0).putLong(24, 127).putLong(32, meta.size.toLong()).putLong(40, 127).putLong(56, 127)
        head.put(97, 1).put(98, 2).put(99, 1).put(100, 0).put(101, 14)
        return File(dir, name).apply { writeBytes(head.array() + meta) }
    }

    /** Un paquete del formato 2 en [dir]: el mapa (sin calles salvo [roads]), la búsqueda y las calles ([routes]) con gzip. */
    private fun bundle(roads: Boolean = false, routes: ByteArray = ldm): PackBundle {
        val map = pmtiles("mx-col.pmtiles.part", if (roads) listOf("water", "transportation") else listOf("water", "place"))
        val gz = listOf(gz("mx-col.srch.gz.dl.part", "SRCH1 de prueba".toByteArray()), gz("mx-col.ldm.gz.dl.part", routes))
        val raw = listOf(File(dir, "mx-col.srch.new.part"), File(dir, "mx-col.ldm.new.part"))
        return PackBundle(map, gz, raw, listOf(File(dir, "mx-col.srch"), File(dir, "mx-col.ldm")), File(dir, "mx-col.pmtiles"))
    }

    @Test
    fun aGoodBundleIsCheckedAndPutInPlace() {
        val b = bundle()
        b.unpack { true }
        b.install { true }
        assertTrue(File(dir, "mx-col.pmtiles").isFile)
        assertArrayEquals(ldm, File(dir, "mx-col.ldm").readBytes())
        assertEquals("SRCH1 de prueba", File(dir, "mx-col.srch").readText())
        assertFalse(b.map.exists())
        assertTrue(b.raw.none { it.exists() })
    }

    @Test
    fun aMapWithStreetsIsNotAFormat2Bundle() {
        assertBad { bundle(roads = true).unpack { true } }
    }

    @Test
    fun aTruncatedLdmIsRejected() {
        assertBad { bundle(routes = ldm.copyOf(ldm.size / 2)).unpack { true } }
    }

    @Test
    fun aBrokenGzipIsRejected() {
        val b = bundle()
        b.gz[1].writeBytes(byteArrayOf(1, 2, 3, 4))
        assertBad { b.unpack { true } }
    }

    @Test
    fun cancelledBeforeInstallingNothingChanges() {
        val b = bundle()
        File(dir, "mx-col.ldm").writeText("las de antes")
        b.unpack { true }
        try {
            b.install { false }
            error("debió cancelarse")
        } catch (_: CancellationException) {
        }
        // Lo de antes sigue y lo bajado también (quien llama decide si lo borra).
        assertEquals("las de antes", File(dir, "mx-col.ldm").readText())
        assertFalse(File(dir, "mx-col.pmtiles").exists())
        assertTrue(b.map.isFile)
    }

    @Test
    fun cancelledWhileUnpackingStopsThere() {
        val big = File(dir, "big.gz").apply { GZIPOutputStream(outputStream()).use { it.write(ByteArray(10 shl 20)) } }
        var asked = 0
        try {
            gunzip(big, File(dir, "big"), active = { ++asked < 2 })
            error("debió cancelarse")
        } catch (_: CancellationException) {
        }
        assertEquals(2, asked)
        assertTrue(File(dir, "big").length() < 10 shl 20)
    }

    @Test
    fun aHalfUnpackedBundleIsRedoneFromItsParts() {
        // Se cortó a medio abrir (corriente, app cerrada): lo abierto quedó a medias, los gzip enteros.
        val b = bundle()
        b.raw[1].writeBytes(ldm.copyOf(100))
        b.unpack { true }
        assertArrayEquals(ldm, b.raw[1].readBytes())
        b.install { true }
        assertNotNull(RoadGraph.open(File(dir, "mx-col.ldm"))?.also { it.close() })
    }

    @Test
    fun theStreetsOnlyCountForTheSameMap() {
        val before = InstalledPack("mx-col", "aa", 1, 1, doubleArrayOf(0.0, 0.0, 1.0, 1.0), emptyList(), format = 2)
        assertTrue(samePack(before, before.copy(routesSha = "x")))
        // Mientras bajaban, [start] puso otra versión del mapa, otro formato o lo borraron: ya no son suyas.
        assertFalse(samePack(before, before.copy(sha256 = "bb")))
        assertFalse(samePack(before, before.copy(format = 1)))
        assertFalse(samePack(before, null))
    }

    @Test
    fun graphsBeyondTheLimitAreDroppedOldestFirst() {
        val open = listOf("a" to 100L, "b" to 200L)
        assertEquals(listOf("a"), graphsToDrop(open, 50, max = 2, budget = Long.MAX_VALUE, keep = null))
        // El del ruteador se queda: sale el siguiente.
        assertEquals(listOf("b"), graphsToDrop(open, 50, max = 2, budget = Long.MAX_VALUE, keep = "a"))
        // 32 bits: por bytes, aunque quepan por número.
        assertEquals(listOf("a", "b"), graphsToDrop(open, 250, max = 3, budget = 300, keep = null))
        assertEquals(emptyList<String>(), graphsToDrop(listOf("a" to 100L), 50, max = 2, budget = 300, keep = null))
    }

    @Test
    fun missingStreetsAreOnlyFetchedAloneOnAFreeNetwork() {
        val now = 10_000_000L
        assertEquals(RoutesAsk.FETCH, routesAsk(online = true, metered = false, roomy = true, tries = 0, lastAt = 0, now = now))
        // Red que se cobra por dato: se ofrece el botón, nunca se bajan solas.
        assertEquals(RoutesAsk.OFFER, routesAsk(online = true, metered = true, roomy = true, tries = 0, lastAt = 0, now = now))
        assertEquals(RoutesAsk.NO_ROOM, routesAsk(online = true, metered = false, roomy = false, tries = 0, lastAt = 0, now = now))
        assertEquals(RoutesAsk.WAIT, routesAsk(online = false, metered = false, roomy = true, tries = 0, lastAt = 0, now = now))
        // Cada intento espera el doble: 1 min, 2, 4…
        assertEquals(RoutesAsk.WAIT, routesAsk(true, false, true, tries = 2, lastAt = now - 90_000, now = now))
        assertEquals(RoutesAsk.FETCH, routesAsk(true, false, true, tries = 2, lastAt = now - 130_000, now = now))
        assertEquals(ROUTES_ASK_MAX_MS, routesBackoff(20))
        // Tras el último intento de la sesión, solo con el botón.
        assertEquals(RoutesAsk.OFFER, routesAsk(true, false, true, tries = ROUTES_MAX_TRIES, lastAt = 0, now = now))
    }

    @Test
    fun ldmWithBrokenDrawingSectionsDoesNotOpen() {
        val good = File(dir, "good.ldm").apply { writeBytes(ldm) }
        assertTrue(RoadGraph.hasDrawing(good))
        RoadGraph.open(good)!!.close()
        // La sección `far` dice tener muchísimas celdas: se sale del archivo.
        val far = ByteBuffer.wrap(ldm).order(ByteOrder.LITTLE_ENDIAN).getInt(92)
        val broken = ldm.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(far, 0x7FFFFFF0) }
        val bad = File(dir, "bad.ldm").apply { writeBytes(broken) }
        assertFalse(RoadGraph.hasDrawing(bad))
        assertNull(RoadGraph.open(bad))
        // Un desplazamiento de sección fuera del archivo.
        val outside = ldm.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(96, ldm.size + 10) }
        assertNull(RoadGraph.open(File(dir, "out.ldm").apply { writeBytes(outside) }))
        // Truncado (se cortó al escribirlo) y vacío (corte de corriente).
        val cut = File(dir, "cut.ldm").apply { writeBytes(ldm.copyOf(ldm.size - 1000)) }
        assertFalse(RoadGraph.hasDrawing(cut))
        assertNull(RoadGraph.open(cut))
        val empty = File(dir, "empty.ldm").apply { writeBytes(ByteArray(0)) }
        assertFalse(RoadGraph.hasDrawing(empty))
        assertNull(RoadGraph.open(empty))
    }

    private fun assertBad(block: () -> Unit) {
        try {
            block()
            error("debió rechazarse")
        } catch (_: BadPackException) {
        }
    }
}
