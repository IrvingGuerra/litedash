package com.irvingguerra.litedash

import com.irvingguerra.litedash.feature.apps.App
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        assertTrue(isProjectionPackage("com.vendor.app", "ZLink"))
    }

    @Test
    fun byNameTheWordMustStartAWord() {
        // "tlink" dentro de "BTLink" (Bluetooth) o "SmartLink" no es Android Auto.
        assertFalse(isProjectionPackage("com.vendor.app", "BTLink"))
        assertFalse(isProjectionPackage("com.vendor.app", "SmartLink"))
        assertFalse(isProjectionPackage("com.vendor.app", "EZLink"))
    }

    @Test
    fun chosenAppCountsForTheMusic() {
        assertTrue(isProjection("com.zjinnova.zlink", null))
        assertTrue(isProjection("com.vendor.mirror", "com.vendor.mirror/Main"))
        assertFalse(isProjection("com.vendor.mirror", null))
        assertFalse(isProjection("com.spotify.music", "com.vendor.mirror/Main"))
    }

    @Test
    fun chosenAppWinsWhileInstalled() {
        val zlink = App("ZLink", "com.zjinnova.zlink", "Main")
        val other = App("Mi enlace", "com.vendor.mirror", "Main")
        val apps = listOf(other, zlink)
        // Automática: la que encuentra LiteDash.
        assertEquals(zlink, projectionChoice(null, apps))
        // Elegida a mano (aunque no parezca de proyección): esa.
        assertEquals(other, projectionChoice(other.key, apps))
        // Elegida pero ya no está: de vuelta a la que se encuentre.
        assertEquals(zlink, projectionChoice("com.gone/Main", apps))
        // Sin ninguna: nada (el botón y el widget llevan a sus ajustes).
        assertNull(projectionChoice(null, listOf(App("Radio", "com.vendor.radio", "Main"))))
    }
}
