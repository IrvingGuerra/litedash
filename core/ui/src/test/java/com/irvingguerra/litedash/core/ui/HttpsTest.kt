package com.irvingguerra.litedash.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpsTest {
    @Test
    fun resumesOnlyAValidPiece() {
        assertEquals(0L, Https.resumeFrom(0, 1000))
        assertEquals(400L, Https.resumeFrom(400, 1000))
        // Completo: se sigue desde el final (quien llama solo calcula la huella).
        assertEquals(1000L, Https.resumeFrom(1000, 1000))
        // Más grande que el archivo: es otro, de cero.
        assertEquals(0L, Https.resumeFrom(1200, 1000))
        // Sin tamaño conocido, lo que haya.
        assertEquals(300L, Https.resumeFrom(300, 0))
    }

    @Test
    fun appendsOnlyWhenTheServerSendsTheMissingPart() {
        assertTrue(Https.appendsAt(206, "bytes 400-999/1000", 400))
        // 200 = el archivo entero: se escribe desde el principio.
        assertFalse(Https.appendsAt(200, null, 400))
        // Un rango que no empieza donde vamos no se pega.
        assertFalse(Https.appendsAt(206, "bytes 0-999/1000", 400))
        assertFalse(Https.appendsAt(206, null, 400))
    }

    @Test
    fun clockBeforeThisVersionLooksWrong() {
        assertTrue(Https.clockLooksWrong(0L)) // 1970
        assertTrue(Https.clockLooksWrong(1_420_070_400_000L)) // 2015
        assertFalse(Https.clockLooksWrong(1_795_000_000_000L)) // nov 2026
    }
}
