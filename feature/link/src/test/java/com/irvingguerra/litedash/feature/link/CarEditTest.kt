package com.irvingguerra.litedash.feature.link

import com.irvingguerra.litedash.feature.link.PackageParts.Step
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** `setCar` (§5e) y el orden de las partes de `pkg` (§5f). */
class CarEditTest {
    private fun parse(json: String) = CarEdit.parse(JSONObject(json))

    @Test
    fun validFieldsPass() {
        val e = parse("""{"t":"setCar","color":"c0ffee","finish":"metallic","gloss":0.25,"id":"mitsubishi/lancer-evo-2010","name":" Mi Evo ","entry":{"id":"x"}}""")
        assertEquals(0xC0FFEE, e.color)
        assertEquals("metallic", e.finish)
        assertEquals(0.25f, e.gloss)
        assertEquals("mitsubishi/lancer-evo-2010", e.id)
        assertEquals("Mi Evo", e.name)
        assertNotNull(e.entry)
    }

    @Test
    fun emptyIdAndNameMeanFactory() {
        val e = parse("""{"id":"","name":""}""")
        assertEquals("", e.id)
        assertEquals("", e.name)
    }

    @Test
    fun invalidFieldsAreIgnored() {
        val e = parse("""{"color":"#FF0000","finish":"chrome","gloss":1.5,"id":"../etc","name":"${"x".repeat(41)}","entry":"no"}""")
        assertNull(e.color)
        assertNull(e.finish)
        assertNull(e.gloss)
        assertNull(e.id)
        assertNull(e.name)
        assertNull(e.entry)
        // Lo que falta, también null.
        assertNull(parse("{}").color)
    }

    @Test
    fun idsLikeTheCatalogOnly() {
        for (bad in listOf("a//b", "a/b/c", "/a", "a/", "A/b", "a/..", "a b", "-a", "a/-b", "x".repeat(65))) {
            assertNull(bad, parse(JSONObject().put("id", bad).toString()).id)
        }
        for (good in listOf("bmw/s1000-rr", "sedan", "a1/b-2")) assertEquals(good, parse(JSONObject().put("id", good).toString()).id)
    }

    @Test
    fun colorAndGlossTypes() {
        assertNull(parse("""{"color":123456,"gloss":"0.5"}""").color)
        assertNull(parse("""{"gloss":"0.5"}""").gloss)
        assertEquals(1f, parse("""{"gloss":1}""").gloss)
    }

    @Test
    fun partsInOrder() {
        val p = PackageParts()
        assertEquals(Step.FIRST, p.take("a/b", "car", 0, 3, 100))
        assertEquals("a/b" to "car", p.current)
        assertEquals(Step.MORE, p.take("a/b", "car", 1, 3, 100))
        assertEquals(Step.LAST, p.take("a/b", "car", 2, 3, 100))
        assertNull(p.current)
        // Una sola parte es primera y última a la vez.
        assertEquals(Step.LAST, p.take("a/b", "finish", 0, 1, 10))
    }

    @Test
    fun gapsAndMixesAreBad() {
        val p = PackageParts()
        assertEquals(Step.FIRST, p.take("a", "car", 0, 3, 1))
        assertEquals(Step.BAD, p.take("a", "car", 2, 3, 1))
        // Lo que sigue del que se tiró se ignora (un solo aviso).
        assertEquals(Step.IGNORE, p.take("a", "car", 2, 3, 1))
        // Se puede volver a empezar desde 0.
        assertEquals(Step.FIRST, p.take("a", "car", 0, 3, 1))
        assertEquals(Step.BAD, p.take("b", "car", 1, 3, 1))
        assertEquals(Step.FIRST, p.take("a", "car", 0, 2, 1))
        assertEquals(Step.BAD, p.take("a", "finish", 1, 2, 1))
        assertEquals(Step.FIRST, p.take("a", "car", 0, 2, 1))
        assertEquals(Step.BAD, p.take("a", "car", 1, 3, 1))
        // Otro paquete que empieza a media entrega.
        assertEquals(Step.FIRST, p.take("a", "car", 0, 2, 1))
        assertEquals(Step.BAD, p.take("b", "car", 0, 2, 1))
    }

    @Test
    fun limits() {
        val p = PackageParts()
        assertEquals(Step.BAD, p.take("a", "car", 0, 2, PackageParts.PART_MAX + 1))
        assertEquals(Step.BAD, p.take("a", "car", 0, PackageParts.MAX_PARTS + 1, 1))
        assertEquals(Step.BAD, p.take("a", "car", 0, 0, 1))
        assertEquals(Step.BAD, p.take("a", "car", -1, 2, 1))
        // Una parte suelta sin primera: hueco.
        assertEquals(Step.BAD, p.take("z", "car", 1, 2, 1))
    }

    @Test
    fun cancelForgetsSilently() {
        val p = PackageParts()
        p.take("a", "car", 0, 3, 1)
        p.cancel()
        assertNull(p.current)
        // Lo que siga del cancelado no avisa que falló (el contrato: el `failed` del viejo no se manda).
        assertEquals(Step.IGNORE, p.take("a", "car", 1, 3, 1))
        // Uno nuevo empieza bien.
        assertEquals(Step.FIRST, p.take("b", "car", 0, 2, 1))
    }
}
