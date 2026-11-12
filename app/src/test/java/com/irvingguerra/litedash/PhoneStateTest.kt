package com.irvingguerra.litedash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** El mensaje `state` para LiteDash Connect (PROTOCOLO_LINK.md §5g). */
class PhoneStateTest {
    @Test
    fun everythingKnown() {
        val state = PhoneCarState(
            miles = true, fahrenheit = false, pressure = "psi", tripStartedAt = 1_000, tripKm = 12.3,
            codes = 2, volts = 12.6, coolant = 90,
            tires = listOf(PhoneTire("fl", 230.5, 25, false), PhoneTire("rr", 150.0, null, true)),
        ).json(now = 1_000 + 25 * 60_000 + 30_000)
        val units = state.getJSONObject("units")
        assertEquals(listOf("mi", "c", "psi"), listOf(units.getString("speed"), units.getString("temp"), units.getString("pressure")))
        val trip = state.getJSONObject("trip")
        assertEquals(12.3, trip.getDouble("km"), 0.0)
        assertEquals(25, trip.getInt("min"))
        val obd = state.getJSONObject("obd")
        assertEquals(2, obd.getInt("codes"))
        assertEquals(12.6, obd.getDouble("volts"), 0.0)
        assertEquals(90, obd.getInt("coolant"))
        val tires = state.getJSONArray("tires")
        assertEquals(2, tires.length())
        val fl = tires.getJSONObject(0)
        assertEquals("fl", fl.getString("pos"))
        assertEquals(230.5, fl.getDouble("kpa"), 0.0)
        assertEquals(25, fl.getInt("c"))
        assertFalse(fl.getBoolean("warn"))
        val rr = tires.getJSONObject(1)
        assertFalse(rr.has("c"))
        assertTrue(rr.getBoolean("warn"))
        // Los decimales viajan cortos (no 12.300000190734863).
        assertTrue(state.toString(), state.toString().contains("12.3,") || state.toString().contains("12.3}"))
    }

    @Test
    fun onlyWhatIsKnown() {
        val state = PhoneCarState(
            miles = false, fahrenheit = true, pressure = null, tripStartedAt = 0, tripKm = null,
            codes = null, volts = 12.1, coolant = null, tires = emptyList(),
        ).json(now = 5_000)
        assertEquals("km", state.getJSONObject("units").getString("speed"))
        assertEquals("f", state.getJSONObject("units").getString("temp"))
        assertFalse(state.getJSONObject("units").has("pressure"))
        assertFalse(state.has("trip"))
        assertFalse(state.has("tires"))
        assertEquals(1, state.getJSONObject("obd").length())
        assertEquals(12.1, state.getJSONObject("obd").getDouble("volts"), 0.0)
        // Sin nada del OBD, ni la llave.
        assertFalse(PhoneCarState(false, false, null, 0, null, null, null, null, emptyList()).json(0).has("obd"))
    }
}
