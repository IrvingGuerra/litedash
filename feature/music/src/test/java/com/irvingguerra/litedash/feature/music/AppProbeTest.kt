package com.irvingguerra.litedash.feature.music

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class AppProbeTest {
    /** Un XML binario mínimo: encabezado + tabla de textos, en UTF-8 o UTF-16. */
    private fun axml(strings: List<String>, utf8: Boolean): ByteArray {
        val data = ByteArrayOutputStream()
        val offsets = strings.map { s ->
            val at = data.size()
            if (utf8) {
                val bytes = s.toByteArray(Charsets.UTF_8)
                data.write(s.length); data.write(bytes.size); data.write(bytes); data.write(0)
            } else {
                data.write(s.length and 0xFF); data.write(s.length shr 8)
                data.write(s.toByteArray(Charsets.UTF_16LE)); data.write(0); data.write(0)
            }
            at
        }
        val headerSize = 28
        val stringsStart = headerSize + strings.size * 4
        val poolSize = stringsStart + data.size()
        val b = ByteBuffer.allocate(8 + poolSize).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort(0x0003).putShort(8).putInt(8 + poolSize)
        b.putShort(0x0001).putShort(headerSize.toShort()).putInt(poolSize).putInt(strings.size).putInt(0)
            .putInt(if (utf8) 0x100 else 0).putInt(stringsStart).putInt(0)
        offsets.forEach { b.putInt(it) }
        b.put(data.toByteArray())
        return b.array()
    }

    private val sample = listOf("manifest", "com.tw.radio", "com.tw.radio.action.PLAY", "ñandú", "com.tw.radio.RadioService")

    @Test
    fun readsUtf8Pool() = assertEquals(sample, AppProbe.stringPool(axml(sample, utf8 = true)))

    @Test
    fun readsUtf16Pool() = assertEquals(sample, AppProbe.stringPool(axml(sample, utf8 = false)))

    @Test
    fun ignoresGarbage() = assertEquals(emptyList<String>(), AppProbe.stringPool(ByteArray(64) { 7 }))
}
