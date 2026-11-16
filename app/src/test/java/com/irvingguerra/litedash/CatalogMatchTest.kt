package com.irvingguerra.litedash

import com.irvingguerra.litedash.feature.apps.normalize
import com.irvingguerra.litedash.feature.car.CatalogEntry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogMatchTest {
    private fun entry(make: String, name: String, years: String) =
        CatalogEntry(id = "x", name = name, make = make, year = years.take(4).toInt(), author = "", license = "", url = "", bytes = 0, sha256 = "", years = years)

    private val rav4 = entry("Toyota", "RAV4", "2016–2018")
    private val peugeot = entry("Peugeot", "301", "2013–2016")

    private fun CatalogEntry.finds(query: String, make: String? = null) = matches(make, normalize(query.trim()))

    @Test
    fun yearInsideTheRange() {
        assertTrue(rav4.finds("2017"))
        assertFalse(rav4.finds("2019"))
        assertTrue(rav4.finds("rav4 2016"))
    }

    @Test
    fun wordsAndAccents() {
        assertTrue(peugeot.finds("PEUGEOT"))
        assertTrue(peugeot.finds("peugeot 301"))
        assertFalse(rav4.finds("rav 5"))
        assertTrue(rav4.finds(""))
    }

    @Test
    fun makeFilter() {
        assertTrue(rav4.finds("", make = "Toyota"))
        assertFalse(peugeot.finds("", make = "Toyota"))
    }
}
