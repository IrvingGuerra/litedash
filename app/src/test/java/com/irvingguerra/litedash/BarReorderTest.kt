package com.irvingguerra.litedash

import org.junit.Assert.assertEquals
import org.junit.Test

class BarReorderTest {
    private val stored = listOf(BarItem.APPS, BarItem.VOLUME, BarItem.MUSIC, BarItem.VOICE, BarItem.NAVIGATE, BarItem.SETTINGS)
    // La voz no se ve (sin asistente): no está entre los que se mueven.
    private val shown = listOf(BarItem.APPS, BarItem.VOLUME, BarItem.MUSIC, BarItem.NAVIGATE)
    private val centers = mapOf(BarItem.APPS to 0f, BarItem.VOLUME to 100f, BarItem.MUSIC to 200f, BarItem.NAVIGATE to 300f)

    private fun drop(moved: BarItem, at: Float) = reorderBar(stored, shown, moved, at) { centers.getValue(it) }

    @Test
    fun dropsBeforeTheNextOne() {
        // Apps soltado entre Música y Navegar: queda antes de Navegar (y la voz, que no se ve, conserva su lugar).
        assertEquals(
            listOf(BarItem.VOLUME, BarItem.MUSIC, BarItem.VOICE, BarItem.APPS, BarItem.NAVIGATE, BarItem.SETTINGS),
            drop(BarItem.APPS, 250f),
        )
        // Navegar al principio.
        assertEquals(
            listOf(BarItem.NAVIGATE, BarItem.APPS, BarItem.VOLUME, BarItem.MUSIC, BarItem.VOICE, BarItem.SETTINGS),
            drop(BarItem.NAVIGATE, -50f),
        )
    }

    @Test
    fun pastTheLastGoesBeforeSettings() {
        assertEquals(
            listOf(BarItem.VOLUME, BarItem.MUSIC, BarItem.VOICE, BarItem.NAVIGATE, BarItem.APPS, BarItem.SETTINGS),
            drop(BarItem.APPS, 900f),
        )
        // Sin moverse, lo que se ve queda en el mismo orden.
        assertEquals(shown, drop(BarItem.MUSIC, 200f).filter { it in shown })
    }
}
