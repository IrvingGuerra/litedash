package com.irvingguerra.litedash.feature.tablero

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeloTest {
    // Las pantallas de la matriz de pruebas, ya sin barra de estado ni dock.
    private val pantallas = listOf(
        Cuadricula.para(1024f, 410f), // 7"
        Cuadricula.para(1280f, 530f), // 10"
        Cuadricula.para(1333f, 610f), // 11"
        Cuadricula.para(1920f, 530f), // ancha
        Cuadricula.para(768f, 830f), // vertical
        Cuadricula.para(360f, 300f), // teléfono chico
    )

    @Test
    fun cuadriculaCreceConLaPantalla() {
        assertEquals(Cuadricula(10, 4), pantallas[0])
        assertTrue(pantallas[2].columnas * pantallas[2].filas > pantallas[0].columnas * pantallas[0].filas)
        assertTrue(pantallas[4].vertical)
    }

    @Test
    fun plantillasNuncaSeEncimanNiSeSalen() {
        for (c in pantallas) for (p in Plantilla.entries) {
            val piezas = p.piezas(c)
            assertTrue("$p en ${c.clave} vacía", piezas.isNotEmpty())
            for (pieza in piezas) assertTrue("$p en ${c.clave}: $pieza", piezas.cabe(pieza, c))
            assertEquals("ids repetidos", piezas.size, piezas.map { it.id }.toSet().size)
        }
    }

    @Test
    fun todasLasPlantillasLlevanAuto() {
        for (c in pantallas) for (p in Plantilla.entries) {
            assertTrue("$p en ${c.clave}", p.piezas(c).any { it.tipo == Tipos.AUTO })
        }
    }

    @Test
    fun noCabeSiSeEnciman() {
        val c = Cuadricula(10, 4)
        val a = Pieza("a", Tipos.AUTO, 0, 0, 4, 4)
        val lista = listOf(a)
        assertFalse(lista.cabe(Pieza("b", Tipos.MUSICA, 3, 0, 3, 1), c))
        assertTrue(lista.cabe(Pieza("b", Tipos.MUSICA, 4, 0, 3, 1), c))
        assertFalse(lista.cabe(Pieza("b", Tipos.MUSICA, 8, 0, 3, 1), c)) // se sale
        assertTrue(lista.cabe(a.copy(col = 6), c)) // moverse sobre sí misma está bien
    }

    @Test
    fun lugarLibreEncuentraHueco() {
        val c = Cuadricula(4, 3)
        val lista = listOf(Pieza("a", Tipos.AUTO, 0, 0, 4, 2))
        assertEquals(0 to 2, lista.lugarLibre(2, 1, c))
        assertNull(lista.lugarLibre(2, 2, c))
    }

    @Test
    fun medidasAcotan() {
        assertEquals(3 to 1, Medidas(3, 1, 6, 2).acotar(1, 0))
        assertEquals(6 to 2, Medidas(3, 1, 6, 2).acotar(9, 9))
    }

    @Test
    fun columnaJuntoAlMapaLlevaAutoYMusica() {
        // Un tercio y la mitad de las pantallas de la matriz, sin barra de botones.
        for (c in listOf(Cuadricula.para(341f, 480f), Cuadricula.para(512f, 480f), Cuadricula.para(444f, 700f), Cuadricula.para(640f, 600f))) {
            val piezas = Plantilla.LADO.piezas(c)
            assertTrue(c.clave, piezas.any { it.tipo == Tipos.AUTO })
            assertTrue(c.clave, piezas.any { it.tipo == Tipos.MUSICA && it.alto == 2 })
            assertTrue(c.clave, piezas.none { it.tipo == Tipos.NAVEGAR })
            for (pieza in piezas) assertTrue("${c.clave}: $pieza", piezas.cabe(pieza, c))
        }
    }
}
