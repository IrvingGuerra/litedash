package com.irvingguerra.litedash.feature.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

/** Quitar una página: las de después se recorren con lo suyo y nada de las de antes se toca. */
class RemovePageTest {
    /** Lo guardado de [pages] páginas: la primera con las llaves de siempre, las demás con su número. */
    private fun saved(pages: Int): Map<String, Any?> = buildMap {
        put("fine_layout_8x6", "home")
        put("fine_saved_at_8x6", 10L)
        put("pages", pages)
        for (p in 1 until pages) {
            put("page_layout_${p}_8x6", "page$p")
            put("page_layout_${p}_6x8", "page$p-vertical")
            put("page_saved_at_${p}_8x6", p.toLong())
            put("page_background_$p", "bg$p")
            put("page_template_$p", "T$p")
        }
    }

    /** [all] con los cambios aplicados (null = se suelta). */
    private fun applied(all: Map<String, Any?>, changes: Map<String, Any?>) =
        (all + changes).filterValues { it != null }

    @Test
    fun theLaterPagesMoveBackWithTheirLayoutsAndBackground() {
        val all = saved(5)
        val after = applied(all, withoutPage(all, page = 2, count = 5))
        // La primera y la 1 quedan igual.
        assertEquals("home", after["fine_layout_8x6"])
        assertEquals(10L, after["fine_saved_at_8x6"])
        assertEquals("page1", after["page_layout_1_8x6"])
        assertEquals("bg1", after["page_background_1"])
        // La 3 pasa a ser la 2 y la 4 la 3, con sus dos cuadrículas, su hora y su fondo.
        assertEquals("page3", after["page_layout_2_8x6"])
        assertEquals("page3-vertical", after["page_layout_2_6x8"])
        assertEquals(3L, after["page_saved_at_2_8x6"])
        assertEquals("bg3", after["page_background_2"])
        // Y la plantilla que se le puso.
        assertEquals("T3", after["page_template_2"])
        assertEquals("page4", after["page_layout_3_8x6"])
        assertEquals("bg4", after["page_background_3"])
        // De la última (ya copiada) no queda nada.
        assertEquals(emptyList<String>(), after.keys.filter { it.contains("_4_") || it.endsWith("_4") })
    }

    @Test
    fun removingTheLastPageOnlyLetsItGo() {
        val all = saved(3)
        val after = applied(all, withoutPage(all, page = 2, count = 3))
        assertEquals("page1", after["page_layout_1_8x6"])
        assertEquals("bg1", after["page_background_1"])
        assertEquals(emptyList<String>(), after.keys.filter { it.contains("_2_") || it.endsWith("_2") })
    }

    @Test
    fun aPageWithoutBackgroundLeavesNoneBehind() {
        // La 3 nunca eligió fondo: la 2 (que recibe lo de la 3) se queda sin el suyo viejo.
        val all = saved(4) - "page_background_3"
        val after = applied(all, withoutPage(all, page = 2, count = 4))
        assertEquals(null, after["page_background_2"])
        assertEquals("page3", after["page_layout_2_8x6"])
    }
}
