package com.irvingguerra.litedash.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AccentTest {
    @Test fun sin_color_elegido_queda_igual() {
        assertSame(DarkColors, DarkColors.withAccent(null))
        assertSame(LightColors, LightColors.withAccent(null))
    }

    @Test fun en_oscuro_nada_casi_negro_ni_casi_blanco() {
        assertTrue(DarkColors.withAccent(Color.Black).primary.luminance() >= 0.12f)
        assertTrue(DarkColors.withAccent(Color.White).primary.luminance() <= 0.8f)
    }

    @Test fun en_claro_nada_demasiado_claro() =
        assertTrue(LightColors.withAccent(Color.Yellow).primary.luminance() <= 0.35f)

    @Test fun el_texto_sobre_el_color_es_el_que_mas_se_lee() {
        // Amarillo en oscuro: se lee mejor con tinta; azul fuerte en claro: con blanco.
        assertEquals(DarkColors.background, DarkColors.withAccent(Color(0xFFFFD60A)).onPrimary)
        assertEquals(Color.White, LightColors.withAccent(Color(0xFF0A3DFF)).onPrimary)
    }

    @Test fun acento_y_principal_son_el_mismo() {
        val c = DarkColors.withAccent(Color(0xFF3FB27F))
        assertEquals(c.accent, c.primary)
    }
}
