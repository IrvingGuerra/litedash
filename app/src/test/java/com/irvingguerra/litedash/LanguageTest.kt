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

    // «Como el sistema»: español e inglés vienen en la APK; los demás, su paquete bajado o inglés (no el español de values/).
    @Test
    fun systemInSpanishOrEnglishStaysAsIs() {
        assertEquals("", systemLanguage(Locale.forLanguageTag("es-MX")) { true })
        assertEquals("", systemLanguage(Locale.US) { true })
    }

    @Test
    fun systemInGermanWithoutPackIsEnglish() = assertEquals("en", systemLanguage(Locale.GERMANY) { false })

    @Test
    fun catalanGalicianAndBasqueStayInSpanish() {
        // "" = el sistema tal cual: sin `values-ca` cae en `values/`, que es español (como en la 1.5.14).
        for (tag in listOf("ca-ES", "gl-ES", "eu-ES", "ca")) assertEquals(tag, "", systemLanguage(Locale.forLanguageTag(tag)) { true })
        for (tag in listOf("es-US", "es-419", "es-AR")) assertEquals(tag, "", systemLanguage(Locale.forLanguageTag(tag)) { false })
    }

    @Test
    fun systemInGermanWithPackUsesIt() = assertEquals("de", systemLanguage(Locale.GERMANY) { it == "de" })

    @Test
    fun regionalPackFirst() {
        assertEquals("zh-CN", systemLanguage(Locale.forLanguageTag("zh-CN")) { it == "zh-CN" || it == "zh" })
        assertEquals("pt", systemLanguage(Locale.forLanguageTag("pt-BR")) { it == "pt" })
    }

    @Test
    fun rightToLeftSystemIsEnglish() = assertEquals("en", systemLanguage(Locale.forLanguageTag("ar-EG")) { true })

    @Test
    fun `featured languages put the chosen and system ones first, then the popular ones up to six`() {
        val list = listOf("ar", "de", "fr", "hi", "it", "ja", "pt-BR", "ru", "tr", "zh").map { LanguageChoice(it, it, 100, 1, "") }
        val tags = { current: String, system: List<String> -> featuredLanguages(list, current, system).map { it.tag } }
        assertEquals(listOf("pt-BR", "tr", "fr", "de", "ru", "it"), tags("", listOf("es")))
        assertEquals(listOf("ja", "hi", "pt-BR", "tr", "fr", "de"), tags("ja", listOf("hi", "ja")))
        assertEquals(listOf("fr", "pt-BR", "tr", "de", "ru", "it"), tags("fr", listOf("fr")))
    }
}
