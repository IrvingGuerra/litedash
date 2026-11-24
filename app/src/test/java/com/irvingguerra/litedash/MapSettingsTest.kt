package com.irvingguerra.litedash

import com.irvingguerra.litedash.feature.apps.normalize
import com.irvingguerra.litedash.feature.map.MapRegion
import com.irvingguerra.litedash.feature.map.PackFile
import org.junit.Assert.assertEquals
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

    private val catalog = listOf("AR", "BR", "CA", "CL", "CO", "ES", "IT", "MX", "PE", "US", "VE")

    @Test
    fun aCountryIsFoundByItsNameWithoutAccentsInSpanishOrEnglish() {
        val spanish = Locale("es", "MX")
        assertEquals(listOf("MX"), countriesMatching(normalize("mexico"), catalog, spanish))
        assertEquals(listOf("MX"), countriesMatching(normalize("México"), catalog, Locale.ENGLISH))
        assertEquals(listOf("MX"), countriesMatching(normalize("Méx"), catalog, Locale.ENGLISH))
        // En inglés aunque la app vaya en español, y por una palabra del nombre.
        assertEquals(listOf("US"), countriesMatching(normalize("united"), catalog, spanish))
        assertEquals(listOf("US"), countriesMatching(normalize("unidos"), catalog, Locale.ENGLISH))
        assertEquals(listOf("ES"), countriesMatching(normalize("spain"), catalog, spanish))
        // Por el código exacto; con menos de 3 letras, nada más.
        assertEquals(listOf("MX"), countriesMatching("mx", catalog, spanish))
        assertTrue(countriesMatching("me", catalog, spanish).isEmpty())
        // Un estado no es un país; uno que no está en la lista tampoco sale.
        assertTrue(countriesMatching(normalize("jalisco"), catalog, spanish).isEmpty())
        assertTrue(countriesMatching(normalize("francia"), catalog, spanish).isEmpty())
        // Varios: el que coincide completo va primero.
        assertEquals(listOf("GN", "GQ"), countriesMatching("guinea", listOf("GQ", "GN"), spanish))
    }

    @Test
    fun theShortCountryListPutsYoursFirstAndFillsWithTheMostAsked() {
        // Sin nada propio: los seis más pedidos.
        assertEquals(listOf("MX", "US", "CO", "BR", "AR", "ES"), featuredCountries(catalog, emptyList()))
        // Donde estás y lo bajado primero, sin repetir, y uno que no hay no cuenta.
        assertEquals(listOf("PE", "US", "MX", "CO", "BR", "AR"), featuredCountries(catalog, listOf("PE", "FR", "US", "PE")))
        // Más propios que el tope: se ven todos los propios.
        assertEquals(listOf("VE", "IT", "PE", "CL", "CA", "ES", "AR"), featuredCountries(catalog, listOf("VE", "IT", "PE", "CL", "CA", "ES", "AR")))
        // Con pocos países, todos.
        assertEquals(listOf("MX", "CA"), featuredCountries(listOf("CA", "MX"), emptyList()))
    }
}
