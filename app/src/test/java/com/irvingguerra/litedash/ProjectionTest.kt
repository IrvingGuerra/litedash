package com.irvingguerra.litedash

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionTest {
    @Test
    fun knownPackagesAndWordsInThePackage() {
        assertTrue(isProjectionPackage("com.zjinnova.zlink"))
        assertTrue(isProjectionPackage("com.example.carplay"))
        assertTrue(isProjectionPackage("com.vendor.tlink_app"))
        assertTrue(isProjectionPackage("com.vendor.androidauto"))
        // Vistos en otros launchers.
        assertTrue(isProjectionPackage("com.carletter.car"))
        assertTrue(isProjectionPackage("com.carlinkit.car"))
        assertTrue(isProjectionPackage("com.autokit"))
        assertTrue(isProjectionPackage("gb.xxy.hr"))
        assertTrue(isProjectionPackage("com.vendor.phonemirror"))
        assertTrue(isProjectionPackage("com.vendor.speedplay_hd"))
        assertTrue(isProjectionPackage("com.vendor.app", "Headunit Reloaded"))
    }

    @Test
    fun newWordsOnlyAtTheStartOfAPart() {
        // La palabra tiene que empezar la parte: "mycarletter" o "screenphonemirror" no cuentan.
        assertFalse(isProjectionPackage("com.vendor.mycarletter"))
        assertFalse(isProjectionPackage("com.vendor.screenphonemirror"))
        // Pedazos sueltos tampoco: "letter", "mirror", "speed", "hr" en otro paquete.
        assertFalse(isProjectionPackage("com.vendor.letter"))
        assertFalse(isProjectionPackage("com.vendor.mirror"))
        assertFalse(isProjectionPackage("com.vendor.speed"))
        assertFalse(isProjectionPackage("com.company.hr"))
    }

    @Test
    fun wordInsideAnotherWordIsNotProjection() {
        // "tlink" va dentro de "btlink" (una app de Bluetooth): no es Android Auto.
        assertFalse(isProjectionPackage("com.vendor.btlink"))
        assertFalse(isProjectionPackage("com.spotify.music"))
    }

    @Test
    fun byNameWhenThePackageSaysNothing() {
        assertTrue(isProjectionPackage("com.vendor.app", "Android Auto"))
        assertTrue(isProjectionPackage("com.vendor.app", "Mi CarPlay"))
        assertFalse(isProjectionPackage("com.vendor.app", "Radio"))
    }
}
