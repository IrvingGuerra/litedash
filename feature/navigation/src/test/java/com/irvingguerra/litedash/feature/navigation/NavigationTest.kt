package com.irvingguerra.litedash.feature.navigation

import android.app.Notification
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationTest {
    @Test
    fun onlyNavigationNoticesCount() {
        // Fijo (como navegan Waze y Maps) o de categoría navegación: sí.
        assertTrue(navigates("com.waze", Notification.FLAG_ONGOING_EVENT, null))
        assertTrue(navigates("com.google.android.apps.maps", 0, Notification.CATEGORY_NAVIGATION))
        // Un aviso suelto de la misma app (un mensaje, una alerta de tráfico): no.
        assertFalse(navigates("com.waze", 0, null))
        assertFalse(navigates("com.waze", Notification.FLAG_AUTO_CANCEL, Notification.CATEGORY_MESSAGE))
        // Los de prueba cuentan aunque no sean fijos.
        assertTrue(navigates(NavigationRepository.TEST_PACKAGE, 0, null))
    }

    @Test
    fun sameTextIsNotNews() {
        val turn = TurnInstruction("k", "300 m", "Gira a la derecha", "12:40", null)
        assertTrue(turn.says("k", "300 m", "Gira a la derecha", "12:40"))
        assertFalse(turn.says("k", "200 m", "Gira a la derecha", "12:40"))
        assertFalse(turn.says("otro", "300 m", "Gira a la derecha", "12:40"))
        assertFalse(turn.says("k", "300 m", "Gira a la derecha", "12:41"))
    }
}
