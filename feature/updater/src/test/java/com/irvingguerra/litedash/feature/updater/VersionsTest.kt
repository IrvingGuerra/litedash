package com.irvingguerra.litedash.feature.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionsTest {
    @Test
    fun matchesGradle() {
        assertEquals(10_000_099L, versionCode("v1.0.0"))
        assertEquals(10_100_001L, versionCode("v1.1.0-beta.1"))
        assertEquals(10_100_050L, versionCode("1.1.0-rc.1"))
        assertEquals(10_100_099L, versionCode("v1.1.0"))
        assertEquals(10_410_099L, versionCode("v1.4.100"))
    }

    @Test
    fun correctOrder() {
        val order = listOf("v1.0.0", "v1.1.0-beta.1", "v1.1.0-beta.2", "v1.1.0-rc.1", "v1.1.0", "v1.1.1", "v1.4.99", "v1.4.100", "v1.4.999", "v1.5.0", "v2.0.0-beta.1")
        val codes = order.map { versionCode(it)!! }
        assertEquals(codes.sorted(), codes)
    }

    @Test
    fun foreignTags() {
        assertNull(versionCode("catalogo"))
        assertNull(versionCode("v1.1"))
        assertNull(versionCode("v1.1.0-beta.0"))
        assertNull(versionCode("v1.4.1000"))
    }

    @Test
    fun prerelease() {
        assertTrue(isPrerelease("v1.1.0-beta.2"))
        assertTrue(isPrerelease("1.1.0-rc.1"))
        assertFalse(isPrerelease("v1.1.0"))
    }
}
