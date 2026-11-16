package com.irvingguerra.litedash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetFlagsTest {
    @Test
    fun flagsDefaultOnAndOnlyOffIsWritten() {
        // Sin nada guardado, todo de fábrica (prendido): los acomodos de antes se ven igual.
        assertTrue(WidgetFlags.on("", WidgetFlags.DATE))
        val off = WidgetFlags.set("", WidgetFlags.DATE, false)
        assertEquals("date=off", off)
        assertFalse(WidgetFlags.on(off, WidgetFlags.DATE))
        // Una opción no toca a las otras, y prender de nuevo borra la marca (no escribe "=on").
        val both = WidgetFlags.set(off, WidgetFlags.WEATHER, false)
        assertTrue(WidgetFlags.on(both, WidgetFlags.GREETING))
        assertEquals("weather=off", WidgetFlags.set(both, WidgetFlags.DATE, true))
        // Una clave que empieza igual que otra no la confunde.
        assertTrue(WidgetFlags.on("days=off", WidgetFlags.DATE))
    }

    @Test
    fun appKeyLivesBeforeTheOptions() {
        assertEquals("com.app", appKeyOf("com.app;name=off"))
        assertNull(appKeyOf("name=off"))
        assertNull(appKeyOf(""))
        // Cambiar la app conserva las opciones; quitarla deja solo las opciones.
        assertEquals("com.other;name=off", withAppKey("com.app;name=off", "com.other"))
        assertEquals("name=off", withAppKey("com.app;name=off", null))
        assertEquals("com.app", withAppKey("", "com.app"))
        // Las opciones se siguen leyendo con la app al principio.
        assertFalse(WidgetFlags.on("com.app;name=off", WidgetFlags.NAME))
    }
}
