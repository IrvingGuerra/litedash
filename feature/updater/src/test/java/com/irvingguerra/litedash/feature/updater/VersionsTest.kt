package com.irvingguerra.litedash.feature.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VersionsTest {
    @Test
    fun readsTheTag() {
        assertEquals(releaseOf("1.4.162"), releaseOf("v1.4.162"))
        assertEquals(releaseOf("1.5.0"), releaseOf(" 1.5.0 "))
    }

    @Test
    fun correctOrder() {
        val order = listOf("v1.0.0", "v1.1.0", "v1.1.1", "v1.4.99", "v1.4.100", "v1.4.999", "v1.4.1000", "v1.5.0", "v2.0.0")
        val releases = order.map { releaseOf(it)!! }
        assertEquals(releases.sorted(), releases)
        assertEquals(releases.distinct(), releases)
    }

    @Test
    fun foreignAndTestTags() {
        assertNull(releaseOf("catalogo"))
        assertNull(releaseOf("v1.1"))
        // Las de prueba nunca se ofrecen.
        assertNull(releaseOf("v1.1.0-beta.2"))
        assertNull(releaseOf("1.1.0-rc.1"))
    }
}
