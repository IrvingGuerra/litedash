package com.irvingguerra.litedash

import org.junit.Assert.assertEquals
import org.junit.Test

class BarItemTest {
    @Test
    fun settingsAlwaysLastAndOnce() {
        // Agregar un botón lo deja antes de Ajustes; repetidos y Ajustes de en medio se acomodan.
        assertEquals(
            listOf(BarItem.APPS, BarItem.MUSIC, BarItem.SETTINGS),
            BarItem.normalize(listOf(BarItem.APPS, BarItem.SETTINGS, BarItem.MUSIC, BarItem.APPS)),
        )
        // Sin Ajustes, se agrega: nunca te quedas sin cómo salir del inicio.
        assertEquals(listOf(BarItem.SETTINGS), BarItem.normalize(emptyList()))
        // Lo guardado se lee por nombre; lo desconocido se ignora.
        assertEquals(listOf(BarItem.VOLUME, BarItem.FAVORITES), listOf("volume", "nada", "favorites").mapNotNull(BarItem::fromStored))
        // La hora ya no es un botón de la barra: la guardada se ignora.
        assertEquals(listOf(BarItem.APPS, BarItem.SETTINGS), BarItem.normalize(listOf("clock", "apps").mapNotNull(BarItem::fromStored)))
    }
}
