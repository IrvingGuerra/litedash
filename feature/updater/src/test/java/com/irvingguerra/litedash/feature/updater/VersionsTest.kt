package com.irvingguerra.litedash.feature.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionsTest {
    @Test
    fun matchesGradle() {
        assertEquals(1_000_099L, versionCode("v1.0.0"))
        assertEquals(1_010_001L, versionCode("v1.1.0-beta.1"))
        assertEquals(1_010_050L, versionCode("1.1.0-rc.1"))
        assertEquals(1_010_099L, versionCode("v1.1.0"))
    }

    @Test
    fun correctOrder() {
        val order = listOf("v1.0.0", "v1.1.0-beta.1", "v1.1.0-beta.2", "v1.1.0-rc.1", "v1.1.0", "v1.1.1", "v2.0.0-beta.1")
        val codes = order.map { versionCode(it)!! }
        assertEquals(codes.sorted(), codes)
    }

    @Test
    fun foreignTags() {
        assertNull(versionCode("catalogo"))
        assertNull(versionCode("v1.1"))
        assertNull(versionCode("v1.1.0-beta.0"))
    }

    @Test
    fun prerelease() {
        assertTrue(isPrerelease("v1.1.0-beta.2"))
        assertTrue(isPrerelease("1.1.0-rc.1"))
        assertFalse(isPrerelease("v1.1.0"))
    }
}
