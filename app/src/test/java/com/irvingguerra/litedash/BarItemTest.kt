package com.irvingguerra.litedash

import org.junit.Assert.assertEquals
import org.junit.Test

class BarItemTest {
    @Test
    fun settingsAlwaysLastAndOnce() {
        // Agregar un botón lo deja antes de Ajustes; repetidos y Ajustes de en medio se acomodan.
        assertEquals(
            listOf(BarItem.APPS, BarItem.MUSIC, BarItem.SPLIT_START, BarItem.SPLIT_END, BarItem.SETTINGS),
            BarItem.normalize(listOf(BarItem.APPS, BarItem.SETTINGS, BarItem.MUSIC, BarItem.APPS)),
        )
        // Sin Ajustes, se agrega: nunca te quedas sin cómo salir del inicio.
        assertEquals(listOf(BarItem.SPLIT_START, BarItem.SPLIT_END, BarItem.SETTINGS), BarItem.normalize(emptyList()))
        // Lo guardado se lee por nombre; lo desconocido se ignora.
        assertEquals(listOf(BarItem.VOLUME, BarItem.FAVORITES), listOf("volume", "nada", "favorites").mapNotNull(BarItem::fromStored))
        // La hora ya no es un botón de la barra: la guardada se ignora.
        assertEquals(listOf(BarItem.APPS, BarItem.SPLIT_START, BarItem.SPLIT_END, BarItem.SETTINGS), BarItem.normalize(listOf("clock", "apps").mapNotNull(BarItem::fromStored)))
    }

    @Test
    fun aBarFromBeforeKeepsItsThreeParts() {
        // Antes las favoritas eran el centro: las divisiones caen a sus lados y nada cambia de lugar.
        assertEquals(
            listOf(BarItem.APPS, BarItem.SPLIT_START, BarItem.FAVORITES, BarItem.SPLIT_END, BarItem.NAVIGATE, BarItem.SETTINGS),
            BarItem.normalize(listOf(BarItem.APPS, BarItem.FAVORITES, BarItem.NAVIGATE)),
        )
        // Ya con divisiones, se respeta lo guardado (un botón al centro, las favoritas al final).
        val own = listOf(BarItem.SPLIT_START, BarItem.MUSIC, BarItem.SPLIT_END, BarItem.FAVORITES, BarItem.SETTINGS)
        assertEquals(own, BarItem.normalize(own))
    }

    @Test
    fun crossedSplitsKeepTheirOrder() {
        // Arrastrar la segunda raya antes de la primera: la primera que aparece es la del principio.
        assertEquals(
            listOf(BarItem.SPLIT_START, BarItem.APPS, BarItem.SPLIT_END, BarItem.SETTINGS),
            BarItem.normalize(listOf(BarItem.SPLIT_END, BarItem.APPS, BarItem.SPLIT_START)),
        )
    }

    @Test
    fun addingPutsFavoritesInTheMiddleAndButtonsAtTheEnd() {
        val bar = BarItem.normalize(listOf(BarItem.APPS))
        assertEquals(listOf(BarItem.APPS, BarItem.SPLIT_START, BarItem.FAVORITES, BarItem.SPLIT_END, BarItem.SETTINGS), BarItem.add(bar, BarItem.FAVORITES))
        assertEquals(listOf(BarItem.APPS, BarItem.SPLIT_START, BarItem.SPLIT_END, BarItem.PHONE, BarItem.SETTINGS), BarItem.add(bar, BarItem.PHONE))
        assertEquals(bar, BarItem.add(bar, BarItem.APPS))
    }
}
