package com.irvingguerra.litedash.feature.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

class AdtsReaderTest {
    /** Un cuadro ADTS: AAC-LC (perfil 1), frecuencia [rateIndex], [channels]; con [crc], cabecera de 9 bytes. */
    private fun adts(payload: ByteArray, rateIndex: Int = 4, channels: Int = 2, crc: Boolean = false): ByteArray {
        val headerSize = if (crc) 9 else 7
        val length = headerSize + payload.size
        val header = byteArrayOf(
            0xFF.toByte(),
            (if (crc) 0xF0 else 0xF1).toByte(),
            ((1 shl 6) or (rateIndex shl 2) or (channels shr 2)).toByte(),
            (((channels and 3) shl 6) or (length shr 11)).toByte(),
            ((length shr 3) and 0xFF).toByte(),
            (((length and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        ) + if (crc) byteArrayOf(0x12, 0x34) else byteArrayOf()
        return header + payload
    }

    private fun reader(vararg parts: ByteArray) = AdtsReader(ByteArrayInputStream(parts.fold(byteArrayOf()) { a, b -> a + b }))

    private fun AdtsFrame.bytes() = data.copyOf(size)

    @Test
    fun readsHeaderAndPayload() {
        val frame = reader(adts(byteArrayOf(1, 2, 3, 4))).next()!!
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), frame.bytes())
        assertEquals(2, frame.objectType)
        assertEquals(4, frame.rateIndex)
        assertEquals(44100, frame.sampleRate)
        assertEquals(2, frame.channels)
    }

    @Test
    fun findsSyncAfterGarbage() {
        // Basura y un 0xFF que no es sincronía antes del cuadro.
        val r = reader(byteArrayOf(0, 0x12, 0xFF.toByte(), 0x00, 0x7F), adts(byteArrayOf(9, 8)))
        assertArrayEquals(byteArrayOf(9, 8), r.next()!!.bytes())
    }

    @Test
    fun crcHeaderIsNineBytes() {
        val frame = reader(adts(byteArrayOf(5, 6, 7), crc = true)).next()!!
        assertArrayEquals(byteArrayOf(5, 6, 7), frame.bytes())
    }

    @Test
    fun malformedFrameIsSkipped() {
        // Frecuencia 15 (no existe) y sin canales: se saltan y sigue el bueno.
        val r = reader(adts(byteArrayOf(), rateIndex = 15), adts(byteArrayOf(), channels = 0), adts(byteArrayOf(3, 3), rateIndex = 3, channels = 1))
        val frame = r.next()!!
        assertArrayEquals(byteArrayOf(3, 3), frame.bytes())
        assertEquals(48000, frame.sampleRate)
        assertEquals(1, frame.channels)
    }

    @Test
    fun consecutiveFrames() {
        val r = reader(adts(byteArrayOf(1)), adts(byteArrayOf(2, 2)))
        assertArrayEquals(byteArrayOf(1), r.next()!!.bytes())
        assertArrayEquals(byteArrayOf(2, 2), r.next()!!.bytes())
        assertNull(r.next())
    }

    @Test
    fun cutStreamEnds() {
        val whole = adts(byteArrayOf(1, 2, 3, 4, 5))
        assertNull(reader(whole.copyOf(whole.size - 2)).next())
        assertNull(reader(whole.copyOf(4)).next())
        assertNull(reader(byteArrayOf()).next())
    }
}
