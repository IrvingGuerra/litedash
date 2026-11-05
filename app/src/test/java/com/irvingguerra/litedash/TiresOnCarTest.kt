package com.irvingguerra.litedash

import org.junit.Assert.assertEquals
import org.junit.Test

class TiresOnCarTest {
    @Test
    fun nothingSavedIsWheels() = assertEquals(TiresOnCar.WHEELS, TiresOnCar.fromStored(null))

    // Antes de la 1.4.82 "corners" era el "Mostrar" (la única forma de verlas): ahora van en las ruedas.
    @Test
    fun oldShowIsWheels() = assertEquals(TiresOnCar.WHEELS, TiresOnCar.fromStored("corners"))

    @Test
    fun choicesRoundTrip() = TiresOnCar.entries.forEach { assertEquals(it, TiresOnCar.fromStored(it.stored)) }
}
