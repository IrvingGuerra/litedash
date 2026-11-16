package com.irvingguerra.litedash.feature.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VersionsTest {
    /** Los números son los que da `versionCodeFrom` de app/build.gradle.kts para esas versiones (a mano: Gradle no corre aquí). */
    @Test
    fun formula() {
        assertEquals(10_000_099L, versionCode("v1.0.0"))
        assertEquals(10_100_099L, versionCode("1.1.0"))
        assertEquals(10_410_099L, versionCode("v1.4.100"))
        assertEquals(10_416_299L, versionCode("v1.4.162"))
    }

    @Test
    fun correctOrder() {
        val order = listOf("v1.0.0", "v1.1.0", "v1.1.1", "v1.4.99", "v1.4.100", "v1.4.999", "v1.5.0", "v2.0.0")
        val codes = order.map { versionCode(it)!! }
        assertEquals(codes.sorted(), codes)
    }

    @Test
    fun foreignAndTestTags() {
        assertNull(versionCode("catalogo"))
        assertNull(versionCode("v1.1"))
        assertNull(versionCode("v1.4.1000"))
        // Las de prueba nunca se ofrecen.
        assertNull(versionCode("v1.1.0-beta.2"))
        assertNull(versionCode("1.1.0-rc.1"))
    }
}
