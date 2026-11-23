package com.irvingguerra.litedash.feature.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CreditsPlacementTest {
    @Test
    fun creditsMoveUpWhenYourCarWouldCoverThem() {
        // Hueco chico abajo a la derecha (300 × 250 px) con tu auto grande abajo: chocaría con los créditos (290 × 40).
        assertTrue(creditsCollide(300f, 250f, 290f, 40f, halfWidth = 70f, below = 60f, anchor = 0.82f))
        // Hueco ancho: el auto al centro queda lejos de la esquina izquierda.
        assertFalse(creditsCollide(1200f, 600f, 290f, 40f, halfWidth = 70f, below = 60f, anchor = 0.82f))
        // Hueco alto: el auto queda arriba de los créditos.
        assertFalse(creditsCollide(300f, 900f, 290f, 40f, halfWidth = 70f, below = 60f, anchor = 0.6f))
        // Sin medir aún los créditos, se quedan donde siempre.
        assertFalse(creditsCollide(300f, 250f, 0f, 0f, halfWidth = 70f, below = 60f, anchor = 0.82f))
    }
}
