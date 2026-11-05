package com.irvingguerra.litedash.feature.map

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MvtTest {
    @Test
    fun readsRoadsWaterAndSkipsTheRest() {
        val tile = message {
            // Edificios: no se leen.
            bytes(3, layer("building", keys = listOf("render_height"), values = listOf("12"), features = listOf(feature(3, listOf(0, 0), square()))))
            // Una calle primaria de (10, 20) a (110, 20) y luego a (110, 70), y una vereda (no se dibuja).
            bytes(3, layer(
                "transportation", keys = listOf("class"), values = listOf("primary", "path"),
                features = listOf(
                    feature(2, listOf(0, 0), listOf(command(1, 1), zz(10), zz(20), command(2, 2), zz(100), zz(0), zz(0), zz(50))),
                    feature(2, listOf(0, 1), listOf(command(1, 1), zz(0), zz(0), command(2, 1), zz(5), zz(5))),
                ),
            ))
            bytes(3, layer("water", keys = emptyList(), values = emptyList(), features = listOf(feature(3, emptyList(), square()))))
        }
        val t = parseVectorTile(tile)!!
        assertEquals(4096, t.extent)
        val primary = t.roads[RoadTier.PRIMARY.ordinal]
        assertEquals(1, primary.size)
        assertArrayEquals(floatArrayOf(10f, 20f, 110f, 20f, 110f, 70f), primary[0].points, 0f)
        assertArrayEquals(intArrayOf(0), primary[0].parts)
        assertTrue(t.roads.filterIndexed { i, _ -> i != RoadTier.PRIMARY.ordinal }.all { it.isEmpty() })
        assertEquals(1, t.water.size)
        assertEquals(4, t.water[0].points.size / 2)
        assertTrue(t.parks.isEmpty())
    }

    @Test
    fun roadClasses() {
        assertEquals(RoadTier.MOTORWAY, roadTier("trunk"))
        assertEquals(RoadTier.SECONDARY, roadTier("tertiary"))
        assertEquals(RoadTier.SERVICE, roadTier("service"))
        assertNull(roadTier("rail"))
        assertNull(roadTier(null))
    }

    @Test
    fun damagedTileGivesWhatWasRead() {
        val tile = message { bytes(3, layer("water", emptyList(), emptyList(), listOf(feature(3, emptyList(), square())))) }
        val t = parseVectorTile(tile + byteArrayOf(0x1A, 0x7F))!! // otra capa cortada a la mitad
        assertEquals(1, t.water.size)
    }

    @Test
    fun notATileIsNull() {
        // La página de un portal cautivo guardada como tesela: ni una capa.
        assertNull(parseVectorTile("<!DOCTYPE html><html><body>Wi-Fi</body></html>".toByteArray()))
        // Vacía (204): no hay nada ahí, se dibuja lisa.
        val empty = parseVectorTile(ByteArray(0))!!
        assertTrue(empty.water.isEmpty() && empty.roads.all { it.isEmpty() })
    }

    @Test
    fun shapeBounds() {
        val s = Shape(floatArrayOf(10f, 20f, 110f, 20f, 110f, 70f), intArrayOf(0))
        assertEquals(10f, s.left, 0f)
        assertEquals(20f, s.top, 0f)
        assertEquals(110f, s.right, 0f)
        assertEquals(70f, s.bottom, 0f)
        assertTrue(s.touches(100f, 60f, 200f, 200f))
        assertTrue(s.touches(0f, 0f, 10f, 20f)) // la orilla cuenta
        assertFalse(s.touches(111f, 0f, 200f, 200f))
        assertFalse(s.touches(0f, 71f, 512f, 512f))
        assertFalse(Shape(FloatArray(0), IntArray(0)).touches(0f, 0f, 4096f, 4096f))
    }

    // --- Un escritor mínimo de protobuf para armar teselas de prueba ---

    private fun square() = listOf(command(1, 1), zz(0), zz(0), command(2, 3), zz(10), zz(0), zz(0), zz(10), zz(-10), zz(0), command(7, 1))

    private fun command(id: Int, count: Int) = (count shl 3) or id
    private fun zz(n: Int) = (n shl 1) xor (n shr 31)

    private fun layer(name: String, keys: List<String>, values: List<String>, features: List<ByteArray>) = message {
        string(1, name)
        features.forEach { bytes(2, it) }
        keys.forEach { string(3, it) }
        values.forEach { v -> bytes(4, message { string(1, v) }) }
        varint(5, 4096)
    }

    private fun feature(type: Int, tags: List<Int>, geometry: List<Int>) = message {
        bytes(2, packed(tags))
        varint(3, type)
        bytes(4, packed(geometry))
    }

    private fun packed(values: List<Int>) = ByteArrayOutputStream().apply { values.forEach { writeVarint(it.toLong()) } }.toByteArray()

    private fun message(build: Writer.() -> Unit) = Writer().apply(build).out.toByteArray()

    private class Writer {
        val out = ByteArrayOutputStream()
        fun varint(field: Int, value: Int) {
            out.writeVarint((field shl 3).toLong())
            out.writeVarint(value.toLong())
        }
        fun bytes(field: Int, data: ByteArray) {
            out.writeVarint((field shl 3 or 2).toLong())
            out.writeVarint(data.size.toLong())
            out.write(data)
        }
        fun string(field: Int, s: String) = bytes(field, s.toByteArray())
    }
}

private fun ByteArrayOutputStream.writeVarint(v: Long) {
    var x = v
    while (x and 0x7F.inv().toLong() != 0L) {
        write(((x and 0x7F) or 0x80).toInt())
        x = x ushr 7
    }
    write(x.toInt())
}
