package com.irvingguerra.litedash.core.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VehicleBusTest {
    @Test fun gana_la_fuente_de_mayor_prioridad() {
        val bus = VehicleBus()
        bus.publish(Signal.SPEED, 50f, Source.GPS)
        assertEquals(50f, bus.value(Signal.SPEED))
        bus.publish(Signal.SPEED, 52f, Source.OBD)
        assertEquals(52f, bus.value(Signal.SPEED))
        assertEquals(Source.OBD, bus.source(Signal.SPEED))
        bus.publish(Signal.SPEED, 49f, Source.GPS)
        assertEquals(52f, bus.value(Signal.SPEED))
    }

    @Test fun al_desconectar_regresa_al_gps() {
        val bus = VehicleBus()
        bus.publish(Signal.SPEED, 50f, Source.GPS)
        bus.publish(Signal.SPEED, 52f, Source.OBD)
        bus.clear(Source.OBD)
        assertEquals(50f, bus.value(Signal.SPEED))
        bus.publish(Signal.SPEED, null, Source.GPS)
        assertNull(bus.value(Signal.SPEED))
    }

    @Test fun cuenta_quien_quiere_cada_senal() {
        val bus = VehicleBus()
        bus.want(Signal.RPM); bus.want(Signal.RPM)
        bus.unwant(Signal.RPM)
        assertEquals(setOf(Signal.RPM), bus.wanted)
        bus.unwant(Signal.RPM)
        assertEquals(emptySet<String>(), bus.wanted)
    }
}
