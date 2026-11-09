package com.irvingguerra.litedash.feature.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogYearsTest {
    private fun entry(year: Int, years: String? = null) = CatalogEntry(
        id = "toyota/rav4", name = "RAV4", make = "Toyota", year = year, author = "", license = "",
        url = "", bytes = 0, sha256 = "", years = years ?: year.toString(),
    )

    @Test fun rangeCoversEveryYearOfTheGeneration() {
        val e = entry(2016, "2016–2018")
        assertTrue(e.covers(2016)); assertTrue(e.covers(2017)); assertTrue(e.covers(2018))
        assertFalse(e.covers(2015)); assertFalse(e.covers(2019))
    }

    @Test fun withoutRangeOnlyItsYear() {
        val e = entry(2014)
        assertEquals("2014", e.years)
        assertTrue(e.covers(2014)); assertFalse(e.covers(2013))
    }

    @Test fun hyphenAlsoWorks() = assertTrue(entry(2011, "2011-2013").covers(2012))
}
