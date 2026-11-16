package com.irvingguerra.litedash

import org.junit.Assert.assertEquals
import org.junit.Test

class SavedImageTest {
    @Test
    fun coverReadsJustEnoughToFillTheScreen() {
        // Foto 16:9 en 1024×600: alcanza con lo ancho de la pantalla (y un poco para cubrir lo alto).
        assertEquals(1067, shownPx(ImageFit.COVER_SCREEN, 1920, 1080, 1024, 600))
        // Foto 4:3 en una pantalla de pie: manda lo alto.
        assertEquals(2560, shownPx(ImageFit.COVER_SCREEN, 1920, 1440, 1080, 1920))
    }

    @Test
    fun fitReadsOnlyWhatShows() {
        // Una foto 16:9 en LD_Vertical se ve a 1080×608: se lee a 1080, no a 1920.
        assertEquals(1080, shownPx(ImageFit.FIT_SCREEN, 1920, 1080, 1080, 1920))
        // Foto 4:3 en 2000×1200: cabe por lo alto.
        assertEquals(1600, shownPx(ImageFit.FIT_SCREEN, 1920, 1440, 2000, 1200))
        // De pie en una pantalla acostada.
        assertEquals(600, shownPx(ImageFit.FIT_SCREEN, 1080, 1920, 1024, 600))
    }

    @Test
    fun halfScreenNeverBelowMinimum() {
        assertEquals(512, shownPx(ImageFit.HALF_SCREEN, 1024, 1024, 1024, 600))
        assertEquals(1000, shownPx(ImageFit.HALF_SCREEN, 1024, 1024, 2000, 1200))
        assertEquals(960, shownPx(ImageFit.HALF_SCREEN, 1024, 768, 1080, 1920))
    }

    @Test
    fun noSizeMeansAsSaved() {
        assertEquals(0, shownPx(ImageFit.COVER_SCREEN, 0, 1080, 1024, 600))
    }

    @Test
    fun exifTurnsEveryOrientation() {
        // 1 normal, 2 espejo, 3 de cabeza, 4 de cabeza en espejo, 5–8 acostadas (con y sin espejo).
        val expected = mapOf(
            1 to (0 to false), 2 to (0 to true), 3 to (180 to false), 4 to (180 to true),
            5 to (90 to true), 6 to (90 to false), 7 to (270 to true), 8 to (270 to false),
            0 to (0 to false), 9 to (0 to false),
        )
        for ((orientation, turn) in expected) assertEquals("orientación $orientation", turn, exifTurn(orientation))
    }
}
