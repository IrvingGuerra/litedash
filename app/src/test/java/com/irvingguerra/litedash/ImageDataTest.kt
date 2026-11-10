package com.irvingguerra.litedash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageDataTest {
    @Test
    fun nameAndTiresLiveTogether() {
        // Lo de antes (solo el nombre) se lee igual y no lleva llantas guardadas.
        assertEquals("logo", ImageData.name("logo"))
        assertNull(TileTires.parse("logo"))
        // Con el interruptor: el nombre sigue siendo el mismo.
        val on = ImageData.encode("logo", true)
        assertEquals("logo;tires=on", on)
        assertEquals("logo", ImageData.name(on))
        assertEquals(true, TileTires.parse(on))
        // Sin foto todavía, pero con el interruptor.
        assertEquals("", ImageData.name(ImageData.encode("", false)))
        assertEquals("logo", ImageData.encode("logo", null))
    }
}
