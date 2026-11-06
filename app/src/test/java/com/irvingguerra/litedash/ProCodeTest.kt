package com.irvingguerra.litedash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** El código se normaliza igual sin importar cómo se escriba y su huella es el SHA-256 en hex. */
class ProCodeTest {
    @Test
    fun normalizesSpacesDashesAndCase() {
        assertEquals("ABCD1234EFGH", ProCode.normalize(" abcd-1234 efgh\n"))
    }

    @Test
    fun hashIsSha256OfNormalizedCode() {
        val expected = "72d21fed44fcb4ca886526fe3d1da2fa41976d9a6284b36036f089955dc650fd"
        assertEquals(expected, ProCode.hash("ABCD-1234-EFGH"))
        assertEquals(expected, ProCode.hash(" abcd 1234-efgh "))
    }

    @Test
    fun blankCodeHasNoHash() {
        assertNull(ProCode.hash(" - - "))
    }
}
