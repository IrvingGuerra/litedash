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
