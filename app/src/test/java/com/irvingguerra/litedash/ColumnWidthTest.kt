package com.irvingguerra.litedash

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class ColumnWidthTest {
    @Test
    fun asManyColumnsAsFitButAtLeastTwo() {
        // 1000 de ancho, tarjetas de 240 o más con 12 de separación: caben cuatro.
        assertEquals(240.5.dp.value, columnWidth(1000.dp, 240.dp, 12.dp).value, 0.01f)
        // Justo para tres: el medio punto de menos evita que el redondeo deje fuera la última.
        assertEquals(199.5.dp.value, columnWidth(624.dp, 200.dp, 12.dp).value, 0.01f)
        // Aunque no quepan, dos columnas (más angostas que el mínimo).
        assertEquals(143.5.dp.value, columnWidth(300.dp, 240.dp, 12.dp).value, 0.01f)
    }
}
