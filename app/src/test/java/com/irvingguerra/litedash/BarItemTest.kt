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

    @Test
    fun projectionGoesAfterBluetooth() {
        val bar = listOf(BarItem.HOME, BarItem.SPLIT_START, BarItem.FAVORITES, BarItem.SPLIT_END, BarItem.NAVIGATE, BarItem.BLUETOOTH, BarItem.VOICE)
        assertEquals(
            listOf(BarItem.HOME, BarItem.SPLIT_START, BarItem.FAVORITES, BarItem.SPLIT_END, BarItem.NAVIGATE, BarItem.BLUETOOTH, BarItem.PROJECTION, BarItem.VOICE, BarItem.SETTINGS),
            BarItem.withProjection(bar),
        )
        // Una sola vez: si ya está (donde sea), no se mueve ni se repite.
        val moved = listOf(BarItem.PROJECTION, BarItem.SPLIT_START, BarItem.SPLIT_END, BarItem.BLUETOOTH, BarItem.SETTINGS)
        assertEquals(moved, BarItem.withProjection(moved))
    }

    @Test
    fun projectionWithoutBluetoothGoesToTheEndOfNavigatesPart() {
        // Navegar al principio: al final de esa parte, antes de la primera raya.
        assertEquals(
            listOf(BarItem.NAVIGATE, BarItem.APPS, BarItem.PROJECTION, BarItem.SPLIT_START, BarItem.MUSIC, BarItem.SPLIT_END, BarItem.SETTINGS),
            BarItem.withProjection(listOf(BarItem.NAVIGATE, BarItem.APPS, BarItem.SPLIT_START, BarItem.MUSIC, BarItem.SPLIT_END)),
        )
        // Navegar al final: antes de Ajustes.
        assertEquals(
            listOf(BarItem.SPLIT_START, BarItem.SPLIT_END, BarItem.NAVIGATE, BarItem.PHONE, BarItem.PROJECTION, BarItem.SETTINGS),
            BarItem.withProjection(listOf(BarItem.SPLIT_START, BarItem.SPLIT_END, BarItem.NAVIGATE, BarItem.PHONE)),
        )
    }

    @Test
    fun projectionWithoutEitherGoesBeforeTheSecondSplit() {
        assertEquals(
            listOf(BarItem.APPS, BarItem.SPLIT_START, BarItem.FAVORITES, BarItem.PROJECTION, BarItem.SPLIT_END, BarItem.VOICE, BarItem.SETTINGS),
            BarItem.withProjection(listOf(BarItem.APPS, BarItem.FAVORITES, BarItem.VOICE)),
        )
        // Se lee por su nombre guardado y pasa por normalize como los demás.
        assertEquals(BarItem.PROJECTION, BarItem.fromStored("projection"))
        assertEquals(
            listOf(BarItem.PROJECTION, BarItem.SPLIT_START, BarItem.SPLIT_END, BarItem.SETTINGS),
            BarItem.normalize(listOf(BarItem.PROJECTION, BarItem.SETTINGS, BarItem.PROJECTION)),
        )
    }

    @Test
    fun projectionIsNotAddedTwiceForWhoHasItAsOwnFavorite() {
        val bar = listOf(BarItem.SPLIT_START, BarItem.FAVORITES, BarItem.SPLIT_END, BarItem.NAVIGATE, BarItem.BLUETOOTH)
        // ZLink entre sus favoritas propias: no se agrega el botón (abrirían lo mismo).
        assertEquals(BarItem.normalize(bar), BarItem.withProjectionOnce(bar, listOf("com.android.chrome/.Main", "com.zjinnova.zlink/.Main")))
        // Sin ella, con las sugeridas (null) o con las favoritas fuera de la barra: sí.
        val added = BarItem.withProjection(bar)
        assertEquals(added, BarItem.withProjectionOnce(bar, listOf("com.android.chrome/.Main")))
        assertEquals(added, BarItem.withProjectionOnce(bar, null))
        val noFavorites = listOf(BarItem.NAVIGATE, BarItem.BLUETOOTH)
        assertEquals(BarItem.withProjection(noFavorites), BarItem.withProjectionOnce(noFavorites, listOf("com.zjinnova.zlink/.Main")))
    }
}
