package com.irvingguerra.litedash

import org.json.JSONException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Leer y revisar un idioma descargable, y elegir entre su texto y el inglés. */
class LanguagePackTest {
    private fun pack(body: String) = parseLanguagePack("""{"lang":"tr","name":"Türkçe","base":10416299,$body}""")

    @Test
    fun readsStringsPluralsAndArrays() {
        val p = pack(""""strings":{"cancel":"İptal"},"plurals":{"days":{"one":"%d gün","other":"%d gün"}},"arrays":{"compass_points":["K","D"]}""")
        assertEquals("tr", p.tag)
        assertEquals("Türkçe", p.name)
        assertEquals(10416299L, p.base)
        assertEquals("İptal", p.strings["cancel"])
        assertEquals(setOf("one", "other"), p.plurals["days"]?.keys)
        assertEquals(listOf("K", "D"), p.arrays["compass_points"])
    }

    @Test
    fun unreadableJsonIsRejectedWhole() {
        assertThrows(JSONException::class.java) { parseLanguagePack("<html>wifi login</html>") }
        assertThrows(JSONException::class.java) { parseLanguagePack("""{"name":"x"}""") }
    }

    @Test
    fun badTagsAreRejectedWhole() {
        for (tag in listOf("es", "en", "en-GB", "TR", "t", "../x", "ar", "he-IL", "fa")) {
            assertThrows(tag, PackException::class.java) { parseLanguagePack("""{"lang":"$tag","name":"X","strings":{}}""") }
        }
        assertEquals("pt-BR", parseLanguagePack("""{"lang":"pt-BR","name":"Português"}""").tag)
    }

    @Test
    fun tooBigIsRejectedWhole() {
        val big = "x".repeat(PACK_MAX_BYTES)
        assertThrows(PackException::class.java) { pack(""""strings":{"a":"$big"}""") }
    }

    @Test
    fun badEntriesAreDroppedOneByOne() {
        val long = "x".repeat(PACK_MAX_TEXT + 1)
        val p = pack(
            """"strings":{"ok":"Tamam","empty":"  ","long":"$long","Bad-Key":"x","number":5},""" +
                """"plurals":{"p":{"one":"bir","weird":"x"},"none":{"x":"y"}},"arrays":{"a":["x",1],"b":["y"]}""",
        )
        assertEquals(mapOf("ok" to "Tamam"), p.strings)
        assertEquals(mapOf("p" to mapOf("one" to "bir")), p.plurals)
        assertEquals(mapOf("b" to listOf("y")), p.arrays)
    }

    @Test
    fun markersMustMatchEnglish() {
        assertTrue(sameMarkers("%2\$s, %1\$d km", "%1\$d km to %2\$s"))
        assertTrue(sameMarkers("%%%d", "%d%%"))
        assertTrue(sameMarkers("%.1f V", "%.1f V"))
        assertFalse(sameMarkers("%s km", "%d km"))
        assertFalse(sameMarkers("km", "%d km"))
        // Lo que String.format no acepta aunque "parezca" el mismo marcador, y un % suelto.
        for (bad in listOf("%#s", "%-s", "%<s", "%S")) assertFalse(bad, sameMarkers(bad, "%s"))
        for (bad in listOf("%D", "%.2d", "%d %", "%é %d")) assertFalse(bad, sameMarkers(bad, "%d"))
        assertTrue(sameMarkers("100 %", "100 %"))
        assertFalse(sameMarkers("%d %d km", "%d km"))
        assertFalse(sameMarkers("%d %s", "%s %d"))
        assertFalse(sameMarkers("%2\$s", "%1\$s"))
    }

    @Test
    fun textFallsBackToEnglish() {
        val p = pack(""""strings":{"ok":"%1${'$'}d araba","bad":"%s araba"}""")
        assertEquals("%1\$d araba", p.text("ok", "%1\$d cars"))
        assertNull(p.text("bad", "%1\$d cars"))
        assertNull(p.text("missing", "Cancel"))
    }

    @Test
    fun pluralFormsMayDropTheNumberButNeverAskForMore() {
        assertTrue(pluralFits("Arabayı sil?", "Delete the %1\$d cars?"))
        assertTrue(pluralFits("%1\$d arabayı sil?", "Delete the %1\$d cars?"))
        assertFalse(pluralFits("%2\$d", "Delete the %1\$d cars?"))
        assertFalse(pluralFits("%s", "%d"))
        assertTrue(pluralFits("%d", "%d of %d"))
        assertFalse(pluralFits("%s", "%d of %s"))
        val p = pack(""""plurals":{"cars":{"one":"Arabayı sil?","other":"%1${'$'}s?"}}""")
        assertEquals(mapOf("one" to "Arabayı sil?"), p.plural("cars", "Delete the %1\$d cars?"))
        assertEquals(emptyMap<String, String>(), p.plural("missing", "x"))
    }

    @Test
    fun arraysNeedSameSizeAndMarkers() {
        val p = pack(""""arrays":{"compass_points":["K","KD"],"short":["K"]}""")
        assertArrayEquals(arrayOf("K", "KD"), p.array("compass_points", arrayOf("N", "NE")))
        assertNull(p.array("short", arrayOf("N", "NE")))
        assertNull(p.array("missing", arrayOf("N")))
    }

    @Test
    fun simplePluralCategoryForAndroid6() {
        assertEquals("one", simplePluralCategory(1, setOf("one", "other")))
        assertEquals("other", simplePluralCategory(2, setOf("one", "other")))
        assertEquals("other", simplePluralCategory(0, setOf("one", "other")))
        // Sin "one" (japonés, chino): todo es "other".
        assertEquals("other", simplePluralCategory(1, setOf("other")))
    }

    /** El paquete de prueba del README: todo el inglés entre corchetes sigue siendo válido. */
    @Test
    fun pseudoLanguageKeepsEveryMarker() {
        for (en in listOf("Cancel", "%1\$d cars", "%d%% · %s", "In %1\$s: %2\$s")) {
            assertTrue(en, sameMarkers("[$en]", en))
        }
    }

    @Test
    fun indexKeepsOnlyUsableLanguages() {
        val sha = "a".repeat(64)
        val list = parseLanguageIndex(
            """{"languages":[""" +
                """{"lang":"tr","name":"Türkçe","progress":87,"bytes":51234,"sha256":"$sha","base":10416299},""" +
                """{"lang":"ar","name":"العربية","progress":10,"bytes":100,"sha256":"$sha"},""" +
                """{"lang":"de","name":"Deutsch","progress":10,"bytes":${PACK_MAX_BYTES + 1},"sha256":"$sha"},""" +
                """{"lang":"fr","name":"Français","progress":10,"bytes":100,"sha256":"nope"}]}""",
        )
        assertEquals(listOf("tr"), list?.map { it.tag })
        assertEquals(87, list?.first()?.progress)
        assertNull(parseLanguageIndex("<html>"))
        assertEquals(emptyList<LanguageChoice>(), parseLanguageIndex("""{"languages":[]}"""))
    }
}
