package com.irvingguerra.litedash.feature.actualizar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionesTest {
    @Test
    fun igualQueGradle() {
        assertEquals(1_000_099L, codigoDeVersion("v1.0.0"))
        assertEquals(1_010_001L, codigoDeVersion("v1.1.0-beta.1"))
        assertEquals(1_010_050L, codigoDeVersion("1.1.0-rc.1"))
        assertEquals(1_010_099L, codigoDeVersion("v1.1.0"))
    }

    @Test
    fun ordenCorrecto() {
        val orden = listOf("v1.0.0", "v1.1.0-beta.1", "v1.1.0-beta.2", "v1.1.0-rc.1", "v1.1.0", "v1.1.1", "v2.0.0-beta.1")
        val codigos = orden.map { codigoDeVersion(it)!! }
        assertEquals(codigos.sorted(), codigos)
    }

    @Test
    fun etiquetasAjenas() {
        assertNull(codigoDeVersion("catalogo"))
        assertNull(codigoDeVersion("v1.1"))
        assertNull(codigoDeVersion("v1.1.0-beta.0"))
    }

    @Test
    fun prueba() {
        assertTrue(esPrueba("v1.1.0-beta.2"))
        assertTrue(esPrueba("1.1.0-rc.1"))
        assertFalse(esPrueba("v1.1.0"))
    }
}
