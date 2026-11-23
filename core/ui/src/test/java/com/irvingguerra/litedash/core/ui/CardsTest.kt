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

    @Test fun lo_guardado_que_no_es_lisa_es_vidrio() {
        assertEquals(CardStyle.GLASS, CardStyle.fromStored("glass"))
        assertEquals(CardStyle.GLASS, CardStyle.fromStored("otra cosa"))
    }
}
