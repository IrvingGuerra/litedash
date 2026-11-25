package com.irvingguerra.litedash

import com.irvingguerra.litedash.feature.dashboard.TileTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Las reglas de la Pila que no dependen de la pantalla. */
class StackRulesTest {
    @Test
    fun swipingAlwaysChangesFaceExceptOnTheCarAndTheMap() {
        // La música dentro de una Pila: deslizar cambia de cara (no de canción).
        assertTrue(swipesFaces(TileTypes.MUSIC))
        assertTrue(swipesFaces(TileTypes.RADIO))
        assertTrue(swipesFaces(TileTypes.TIRES))
        assertTrue(swipesFaces(TileTypes.CLOCK))
        // El auto gira y el mapa se mueve con el dedo: ahí solo los puntitos o las flechas.
        assertFalse(swipesFaces(TileTypes.CAR))
        assertFalse(swipesFaces(TileTypes.MAP))
    }

    @Test
    fun defaultStackNeverAddsASecondCar() {
        assertEquals(listOf(TileTypes.CAR, TileTypes.TIRES, TileTypes.MUSIC), defaultStackTypes(hasCar = false, tires = true))
        assertEquals(listOf(TileTypes.TIRES, TileTypes.MUSIC), defaultStackTypes(hasCar = true, tires = true))
        // Con auto y sin llantas quedaría una sola cara: la hora completa la Pila.
        assertEquals(listOf(TileTypes.MUSIC, TileTypes.CLOCK), defaultStackTypes(hasCar = true, tires = false))
    }
}
