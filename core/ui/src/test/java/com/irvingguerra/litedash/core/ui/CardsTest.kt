package com.irvingguerra.litedash.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CardsTest {
    /** Colores difíciles: los extremos, los más vivos y un gris a la mitad. */
    private val hard = listOf(
        Color.White, Color.Black, Color.Yellow, Color.Red, Color.Green, Color.Blue, Color.Cyan, Color.Magenta,
        Color(0xFF808080), Color(0xFFFFB224), Color(0xFF1F4FD8), Color(0xFFB89B6A),
    )
    private val themes = listOf(DarkColors, LightColors)

    @Test fun sin_color_elegido_queda_igual() {
        assertSame(DarkColors, DarkColors.withCards(null))
        assertSame(LightColors, LightColors.withCards(null))
    }

    @Test fun en_oscuro_tarjetas_oscuras_y_en_claro_muy_claras() {
        for (color in hard) {
            assertTrue("$color", DarkColors.withCards(color).surface.luminance() <= 0.08f)
            assertTrue("$color", LightColors.withCards(color).surface.luminance() >= 0.77f)
        }
    }

    @Test fun el_tono_elegido_se_conserva() {
        // Un azul vivo sigue siendo azul en los dos temas.
        for (theme in themes) {
            val card = theme.withCards(Color(0xFF1F4FD8)).surface
            assertTrue(card.blue > card.red && card.blue > card.green)
        }
    }

    @Test fun lo_apagado_de_fabrica_se_lee_en_los_dos_temas() {
        for (c in themes) for (under in listOf(c.background, c.surface, c.surfaceHigh)) {
            assertTrue("tema oscuro=${c.dark} sobre $under", contrast(c.textMuted, under) >= 4.5f)
        }
    }

    @Test fun el_texto_siempre_se_lee() {
        for (theme in themes) for (color in hard) for (accent in listOf(null) + hard) {
            val c = theme.withAccent(accent).withCards(color)
            val where = "tema oscuro=${theme.dark} tarjeta=$color realce=$accent"
            assertTrue(where, contrast(c.text, c.surface) >= 4.5f)
            assertTrue(where, contrast(c.text, c.surfaceHigh) >= 4.5f)
            assertTrue(where, contrast(c.text, c.background) >= 4.5f)
            assertTrue(where, contrast(c.textMuted, c.surface) >= 3f)
            assertTrue(where, contrast(c.accent, c.surface) >= 3f)
            assertTrue(where, contrast(c.onPrimary, c.primary) >= 3f)
        }
    }

    @Test fun de_vidrio_el_texto_se_lee_sobre_el_peor_fondo() {
        // Sobre un fondo de pantalla blanco (oscuro) o negro (claro) bajo su velo: como viene de fábrica se lee de
        // sobra, y en lo más transparente que se permite todavía se lee.
        for (theme in themes) for (color in listOf<Color?>(null) + hard) {
            val c = theme.withCards(color)
            val worst = if (c.dark) Color.White else Color.Black
            val wallpaper = c.background.copy(alpha = 0.4f).compositeOver(worst)
            fun seen(opacity: Float) = c.surface.copy(alpha = opacity).compositeOver(wallpaper)
            val where = "tema oscuro=${theme.dark} tarjeta=$color"
            // Con relleno (sobre el mapa de fondo), como siempre.
            assertTrue(where, contrast(c.text, seen(0.6f)) >= 4.5f)
            assertTrue(where, contrast(c.text, seen(MIN_OPACITY)) >= 3.5f)
            // Como cristal casi no hay relleno: el velo de detrás se cierra solo hasta que el texto se lee.
            val veil = glassVeil(c, worst, 0.4f)
            val glass = Color.White.copy(alpha = frost(c.dark)).compositeOver(c.background.copy(alpha = veil).compositeOver(worst))
            assertTrue(where, contrast(c.text, glass) >= 4.5f)
            // Con «Oscurecer el fondo» en nada (velo 0) la tarjeta también se cierra lo necesario.
            val clean = glassVeil(c, worst, 0f)
            assertTrue(where, contrast(c.text, Color.White.copy(alpha = frost(c.dark)).compositeOver(c.background.copy(alpha = clean).compositeOver(worst))) >= 4.5f)
            // Y sobre una imagen que ya se lleva bien con el tema, el velo de siempre (no se oscurece de más).
            assertEquals(where, 0.4f, glassVeil(c, c.background, 0.4f), 0.001f)
        }
    }

    @Test fun sobre_el_mapa_de_fondo_el_cristal_ahumado_se_lee() {
        // El mapa puede traer blanco (tema oscuro) o negro (claro) detrás; de lo más ahumado a lisa, el texto se lee.
        for (theme in themes) for (color in listOf<Color?>(null) + hard) {
            val c = theme.withCards(color)
            val worst = if (c.dark) Color.White else Color.Black
            val smoke = smokeAlpha(c)
            assertTrue("tema oscuro=${theme.dark}: ahumado $smoke", smoke < 0.9f)
            for (step in 0..10) {
                val fill = step / 10f
                // Como lo dibuja skinSurface: la escarcha, encima el humo que se vuelve el color de la tarjeta.
                val under = Color.White.copy(alpha = frost(c.dark) * (1f - fill)).compositeOver(worst)
                val seen = lerp(c.background.copy(alpha = smoke), c.surface, fill).compositeOver(under)
                assertTrue("tema oscuro=${theme.dark} tarjeta=$color relleno=$fill", contrast(c.text, seen) >= 4.5f)
            }
        }
    }

    @Test fun lo_de_adentro_del_vidrio_no_lleva_grises_opacos() {
        val inner = contentColors(DarkColors, CardLook(CardStyle.GLASS, 0.6f), plain = false)
        assertTrue(inner.surfaceHigh.alpha < 0.3f)
        assertTrue(inner.line.alpha < 0.3f)
        assertSame(DarkColors, contentColors(DarkColors, CardLook(CardStyle.SOLID), plain = false))
    }

    @Test fun las_translucidas_guardadas_pasan_a_vidrio() {
        assertEquals(CardStyle.GLASS, CardStyle.fromStored("translucent"))
        assertEquals(CardStyle.GLASS, CardStyle.fromStored(null))
        assertEquals(CardStyle.SOLID, CardStyle.fromStored("solid"))
    }

    @Test(timeout = 20_000) fun cualquier_color_termina_y_se_lee() {
        // Una rejilla de todo el cubo de color: ningún bucle se queda dando vueltas y nada baja de lo mínimo.
        val steps = (0..255 step 17).toList()
        for (theme in themes) for (r in steps) for (g in steps) for (b in steps) {
            val c = theme.withAccent(Color(b, r, g)).withCards(Color(r, g, b))
            assertTrue(contrast(c.text, c.surface) >= 4.5f)
            assertTrue(contrast(c.textMuted, c.surface) >= 3f)
            assertTrue(contrast(c.accent, c.surface) >= 3f)
        }
    }

    @Test fun de_vidrio_lo_apagado_tambien_se_lee() {
        for (theme in themes) for (color in listOf<Color?>(null) + hard) {
            val c = theme.withCards(color)
            val inner = contentColors(c, CardLook(CardStyle.GLASS, 0.6f), plain = false)
            assertEquals(c.text, inner.text)
            assertEquals(c.surface, inner.surface)
            assertTrue("tema oscuro=${theme.dark} tarjeta=$color", contrast(inner.textMuted, c.surface) >= 3f)
            // Una tarjeta dentro de otra no vuelve a aclarar lo apagado.
            assertSame(inner, contentColors(inner, CardLook(CardStyle.GLASS, 0.6f), plain = false))
        }
    }

    @Test fun sin_tarjeta_se_lee_sobre_el_color_medio_de_tu_fondo() {
        // Imagen negra con tema claro e imagen blanca con tema oscuro: el velo se cierra hasta contraste 3.
        for ((theme, mean) in listOf(LightColors to Color.Black, DarkColors to Color.White)) {
            val veil = legibleVeil(theme, mean)
            assertTrue("tema oscuro=${theme.dark}: velo $veil", veil > 0f && veil < 0.9f)
            assertTrue(contrast(theme.text, theme.background.copy(alpha = veil).compositeOver(mean)) >= 3f)
            // Un paso antes todavía no se leía: es lo menos que hace falta.
            assertTrue(contrast(theme.text, theme.background.copy(alpha = veil - 0.05f).compositeOver(mean)) < 3f)
        }
        // Una imagen a medio tono ya se lee con los dos temas; y en tema oscuro una imagen oscura normal (la aurora):
        // nada que cambiar.
        for (theme in themes) assertEquals(0f, legibleVeil(theme, Color(0xFF808080)), 0f)
        assertEquals(0f, legibleVeil(DarkColors, Color(0xFF2A2350)), 0f)
    }

    @Test fun el_lienzo_deja_leer_lo_apagado_sobre_tu_fondo_en_tema_claro() {
        for (theme in themes.filter { !it.dark }) for (color in listOf<Color?>(null) + hard) {
            val c = theme.withCards(color)
            val where = "tema claro tarjeta=$color"
            for (extreme in listOf(Color.Black, Color.White, Color(0xFF5A8C96))) {
                val veil = canvasVeil(c, extreme, 0.62f)
                assertTrue(where, veil >= 0.62f && veil <= 1f)
                val seen = contrast(c.textMuted, c.background.copy(alpha = veil).compositeOver(extreme))
                // 4.5, o el 90 % de lo que el tema da sobre su propio fondo liso si no llega.
                assertTrue("$where sobre $extreme: $seen", seen >= minOf(4.5f, contrast(c.textMuted, c.background) * 0.9f) - 0.01f)
            }
        }
        // Sobre una imagen que ya se lleva con el tema claro (blanca), el velo de siempre.
        assertEquals(0.62f, canvasVeil(LightColors, Color.White, 0.62f), 0.001f)
    }

    @Test fun el_lienzo_en_tema_oscuro_es_el_de_la_1_5_14() {
        // Con cualquier imagen (también una muy clara: dunas, bruma, cielo) y cualquier color de tarjeta: 0.62 fijo.
        for (theme in themes.filter { it.dark }) for (color in listOf<Color?>(null) + hard) {
            for (extreme in listOf(Color.Black, Color.White, Color(0xFFDCD2B4), Color(0xFF5A8C96))) {
                assertEquals("tarjeta=$color sobre $extreme", 0.62f, canvasVeil(theme.withCards(color), extreme, 0.62f), 0f)
            }
        }
    }

    @Test fun los_extremos_son_la_cuarta_parte_mas_clara_y_la_mas_oscura() {
        val px = IntArray(8) { if (it < 2) 0xFF000000.toInt() else if (it < 6) 0xFF808080.toInt() else 0xFFFFFFFF.toInt() }
        val (light, shade) = extremes(px)
        assertEquals(Color.White, light)
        assertEquals(Color.Black, shade)
    }

    @Test fun el_desenfoque_deja_igual_lo_liso_y_suaviza_un_escalon_parejo() {
        val w = 9
        val flat = IntArray(w) { 0xFF336699.toInt() }
        val out = IntArray(w)
        boxBlur(flat, out, w, 1, 2, horizontal = true)
        assertTrue(out.all { it == 0xFF336699.toInt() })
        // Negro a la izquierda y blanco a la derecha: sube sin saltos y queda simétrico alrededor del centro.
        val step = IntArray(w) { if (it < 4) 0xFF000000.toInt() else if (it > 4) 0xFFFFFFFF.toInt() else 0xFF808080.toInt() }
        boxBlur(step, out, w, 1, 2, horizontal = true)
        val blue = out.map { it and 0xFF }
        assertEquals(blue.sorted(), blue)
        for (i in 0 until w) assertTrue("$blue", kotlin.math.abs(255 - blue[i] - blue[w - 1 - i]) <= 2)
        // A lo alto, igual.
        boxBlur(step, out, 1, w, 2, horizontal = false)
        assertEquals(blue, out.map { it and 0xFF })
    }

    @Test fun lo_guardado_que_no_es_lisa_es_vidrio() {
        assertEquals(CardStyle.GLASS, CardStyle.fromStored("glass"))
        assertEquals(CardStyle.GLASS, CardStyle.fromStored("otra cosa"))
    }
}
