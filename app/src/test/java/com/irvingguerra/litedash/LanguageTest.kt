package com.irvingguerra.litedash

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** La región que lleva el idioma elegido. */
class LanguageTest {
    @Test
    fun keepsSystemRegionWhenSameLanguage() =
        assertEquals(Locale.forLanguageTag("es-AR"), localeFor("es", Locale.forLanguageTag("es-AR")))

    @Test
    fun spanishFromAnotherLanguageIsMexico() =
        assertEquals(Locale.forLanguageTag("es-MX"), localeFor("es", Locale.US))

    @Test
    fun otherLanguagesWithoutRegion() =
        assertEquals(Locale.forLanguageTag("en"), localeFor("en", Locale.forLanguageTag("es-MX")))
}
