package com.irvingguerra.litedash.feature.car

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Una entrada de catalogo.json tal como la publica el catálogo (y la manda el celular, §5e). */
class CatalogEntryJsonTest {
    private val sha = "707145da6072c3f6e76d25066b4f7f77b41a03f158a59dcc4af02fa4da8de24f"
    private val base = "https://github.com/IrvingGuerra/litedash/releases/download/catalogo/"

    private fun json() = JSONObject()
        .put("id", "mitsubishi/lancer-evo-2010").put("nombre", "Lancer Evolution X").put("marca", "Mitsubishi")
        .put("anio", 2015).put("anios", "2008–2015").put("url", base + "evo.zip").put("bytes", 2218383).put("sha256", sha)
        .put("extras", org.json.JSONArray().put(JSONObject().put("nivel", 1).put("url", base + "evo-fluidez1.zip").put("bytes", 2130628).put("sha256", sha)))
        .put("acabado", JSONObject().put("url", base + "evo-acabado.zip").put("bytes", 882692).put("sha256", sha))

    @Test
    fun readsTheCatalogFields() {
        val e = CatalogEntry.fromJson(json())
        assertEquals("mitsubishi/lancer-evo-2010", e.id)
        assertEquals("Mitsubishi", e.make)
        assertEquals("2008–2015", e.years)
        assertEquals(2218383L, e.bytes)
        assertNotNull(e.high)
        assertEquals(882692L, e.finish?.bytes)
        assertTrue(e.isSafe)
    }

    @Test
    fun unsafeWhenAnyFileIsNot() {
        assertFalse(CatalogEntry.fromJson(json().put("url", "http://10.0.0.1/evo.zip")).isSafe)
        // Solo la release del catálogo de LiteDash: un zip de otro lado (aunque sea https) no se baja.
        assertFalse(CatalogEntry.fromJson(json().put("url", "https://example.com/evo.zip")).isSafe)
        assertFalse(CatalogEntry.fromJson(json().put("url", "https://github.com/otro/repo/releases/download/x/evo.zip")).isSafe)
        assertFalse(CatalogEntry.fromJson(json().put("sha256", "abc")).isSafe)
        assertFalse(CatalogEntry.fromJson(json().put("bytes", 0)).isSafe)
        assertFalse(CatalogEntry.fromJson(json().put("bytes", 65L shl 20)).isSafe)
        val badFinish = json().put("acabado", JSONObject().put("url", "file:///sdcard/x.zip").put("bytes", 10).put("sha256", sha))
        assertFalse(CatalogEntry.fromJson(badFinish).isSafe)
    }

    @Test(expected = Exception::class)
    fun missingFieldsThrow() {
        CatalogEntry.fromJson(JSONObject().put("id", "x"))
    }
}
