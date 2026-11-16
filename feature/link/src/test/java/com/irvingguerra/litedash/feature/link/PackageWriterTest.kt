package com.irvingguerra.litedash.feature.link

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** El paquete que manda el celular (§5f): tope, reinicio con la parte 0 y largo exacto. */
class PackageWriterTest {
    @get:Rule val temp = TemporaryFolder()

    private fun writer() = PackageWriter { File(temp.root, "link-packages") }

    @Test
    fun completeWhenItMeasuresWhatWasAsked() {
        val w = writer()
        assertTrue(w.part(0, byteArrayOf(1, 2, 3), 5))
        assertTrue(w.part(1, byteArrayOf(4, 5), 5))
        val file = w.finish(5)!!
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), file.readBytes())
    }

    @Test
    fun shorterThanAskedIsIncomplete() {
        val w = writer()
        assertTrue(w.part(0, byteArrayOf(1, 2, 3), 5))
        assertNull(w.finish(5))
        assertEquals(0, File(temp.root, "link-packages").listFiles()!!.size)
    }

    @Test
    fun neverWritesPastTheCap() {
        val w = writer()
        assertTrue(w.part(0, ByteArray(4), 5))
        assertFalse(w.part(1, ByteArray(2), 5))
    }

    @Test
    fun partZeroStartsOver() {
        val w = writer()
        assertTrue(w.part(0, byteArrayOf(9, 9, 9), 3))
        assertTrue(w.part(0, byteArrayOf(1, 2, 3), 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), w.finish(3)!!.readBytes())
        assertEquals(1, File(temp.root, "link-packages").listFiles()!!.size)
    }

    @Test
    fun dropLeavesNothing() {
        val w = writer()
        assertTrue(w.part(0, byteArrayOf(1), 3))
        w.drop()
        assertEquals(0, File(temp.root, "link-packages").listFiles()!!.size)
        // Sin parte 0 no hay archivo donde escribir.
        assertFalse(w.part(1, byteArrayOf(1), 3))
    }
}
