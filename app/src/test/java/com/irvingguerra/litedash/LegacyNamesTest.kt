package com.irvingguerra.litedash

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyNamesTest {
    @Test
    fun simpleValues() {
        assertEquals("dark", LegacyNames.theme("OSCURO"))
        assertEquals("sun", LegacyNames.theme("SOL"))
        assertEquals("lights", LegacyNames.nightSource("LUCES"))
        assertEquals("side", LegacyNames.barPosition("lateral"))
        assertEquals("auto", LegacyNames.barPosition("auto"))
        assertEquals("car", LegacyNames.carView("auto"))
        assertEquals("road", LegacyNames.carView("carretera"))
        // Lo desconocido se queda igual (el lector usa su valor de fábrica).
        assertEquals("raro", LegacyNames.theme("raro"))
    }

    @Test
    fun wheelKeys() {
        assertEquals("87=next\n88=previous\n24=volume_up\n5=normal", LegacyNames.wheelKeys("87=siguiente\n88=anterior\n24=subir_volumen\n5=normal"))
    }

    @Test
    fun reminders() {
        val out = JSONArray(LegacyNames.reminders("""[{"id":7,"tipo":"verificacion","fecha":"2026-12-01"},{"id":8,"tipo":"otro","fecha":"2027-01-15"}]"""))
        assertEquals(2, out.length())
        assertEquals(7L, out.getJSONObject(0).getLong("id"))
        assertEquals("inspection", out.getJSONObject(0).getString("type"))
        assertEquals("2026-12-01", out.getJSONObject(0).getString("date"))
        assertEquals("other", out.getJSONObject(1).getString("type"))
        assertEquals("no es json", LegacyNames.reminders("no es json"))
    }

    @Test
    fun gauge() {
        assertEquals("coolant_temp", LegacyNames.gauge("motor_temp"))
        assertEquals(
            "rpm;style=needle;color=dynamic;max=240;ticks=0;numbers=1;redline=1",
            LegacyNames.gauge("rpm;estilo=aguja;color=dinamico;max=240;marcas=0;numeros=1;roja=1"),
        )
        // El velocímetro no lleva señal.
        assertEquals("style=gradient;color=purple", LegacyNames.gauge("estilo=degradado;color=morado"))
        assertEquals("", LegacyNames.gauge(""))
    }

    @Test
    fun layout() {
        val old = listOf(
            "auto-0\tauto\t0\t0\t6\t4\t\t",
            "medidor-3\tmedidor\t6\t0\t2\t2\tgasolina;estilo=anillo\t1",
            "velocimetro-k2\tvelocimetro\t8\t0\t2\t2\testilo=degradado",
            "navegar-0\tnavegar\t0\t4\t2\t2\tcom.waze/com.waze.Main",
            "mapa-0\tmapa\t2\t4\t2\t2\t",
            "x1\treloj\t4\t4\t2\t2\t\t",
        ).joinToString("\n")
        val new = LegacyNames.layout(old).lines().map { it.split('\t') }
        assertEquals(listOf("car-0", "car"), new[0].take(2))
        assertEquals(listOf("gauge-3", "gauge"), new[1].take(2))
        assertEquals("fuel;style=ring", new[1][6])
        assertEquals("1", new[1][7])
        assertEquals("style=gradient", new[2][6])
        assertEquals("com.waze/com.waze.Main", new[3][6])
        assertEquals("navigate-0", new[3][0])
        // Choca con navigate-0: se queda el id viejo, pero el tipo sí cambia.
        assertEquals(listOf("mapa-0", "navigate"), new[4].take(2))
        assertEquals(listOf("x1", "clock"), new[5].take(2))
        // Mismo número de campos que antes en cada renglón.
        assertEquals(old.lines().map { it.split('\t').size }, new.map { it.size })
    }
}
