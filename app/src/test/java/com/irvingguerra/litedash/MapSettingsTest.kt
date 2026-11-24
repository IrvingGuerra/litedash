package com.irvingguerra.litedash

import com.irvingguerra.litedash.feature.apps.normalize
import com.irvingguerra.litedash.feature.map.MapRegion
import com.irvingguerra.litedash.feature.map.PackFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class MapSettingsTest {
    private fun region(id: String, country: String, vararg names: Pair<String, String>) =
        MapRegion(id, country, names.toMap(), doubleArrayOf(0.0, 0.0, 1.0, 1.0), emptyList(), PackFile("", 1, ""))

    private fun finds(keys: Set<String>, typed: String) = normalize(typed).let { q -> keys.any { q in it } }

    @Test
    fun aStateIsFoundByItsNameInAnotherLanguageAndByItsCountry() {
        val cdmx = region("mx-cdmx", "MX", "en" to "Mexico City", "es" to "Ciudad de México")
        val keys = regionSearchKeys(cdmx, Locale.ENGLISH)
        assertTrue(finds(keys, "ciudad de mex"))
        assertTrue(finds(keys, "Mexico City"))
        assertTrue(finds(keys, "mexico"))
        val ontario = region("ca-on", "CA", "en" to "Ontario")
        val spanish = regionSearchKeys(ontario, Locale("es", "MX"))
        assertTrue(finds(spanish, "ontario"))
        assertTrue(finds(spanish, "Canadá"))
        assertTrue(finds(spanish, "canada"))
        assertFalse(finds(spanish, "brasil"))
    }
}
