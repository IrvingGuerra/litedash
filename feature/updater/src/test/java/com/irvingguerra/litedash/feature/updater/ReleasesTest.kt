package com.irvingguerra.litedash.feature.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Elegir la versión de la lista de releases de GitHub y leer su suma. */
class ReleasesTest {
    private fun release(tag: String, draft: Boolean = false, prerelease: Boolean = false, apk: Boolean = true, sums: Boolean = true) = """
        {"tag_name":"$tag","draft":$draft,"prerelease":$prerelease,"body":" Novedades de $tag \n","assets":[
          ${if (apk) """{"name":"litedash-${tag.removePrefix("v")}.apk","browser_download_url":"https://example.com/$tag.apk","size":1963871},""" else ""}
          ${if (sums) """{"name":"SHA256SUMS.txt","browser_download_url":"https://example.com/$tag.sums","size":200},""" else ""}
          {"name":"notas.txt","browser_download_url":"https://example.com/notas","size":1}
        ]}
    """

    private fun list(vararg releases: String) = releases.joinToString(",", "[", "]")

    @Test
    fun picksTheNewestNormalRelease() {
        val json = list(
            release("v1.4.163", draft = true),
            release("v1.4.162"),
            release("catalogo"),
            release("v1.4.161"),
            release("v1.5.0-beta.1", prerelease = true),
        )
        val v = pickNewest(json, versionCode("v1.4.160")!!)!!
        assertEquals("1.4.162", v.name)
        assertEquals(10_416_299L, v.code)
        assertEquals("Novedades de v1.4.162", v.notes)
        assertEquals("https://example.com/v1.4.162.apk", v.apk)
        assertEquals("https://example.com/v1.4.162.sums", v.checksums)
        assertEquals(1_963_871L, v.bytes)
    }

    @Test
    fun nothingNewerOrWithoutApk() {
        assertNull(pickNewest(list(release("v1.4.162")), versionCode("v1.4.162")!!))
        assertNull(pickNewest(list(release("v1.4.163", apk = false)), versionCode("v1.4.162")!!))
        // GitHub marcó prerelease aunque la etiqueta parezca normal.
        assertNull(pickNewest(list(release("v1.4.163", prerelease = true)), versionCode("v1.4.162")!!))
    }

    @Test
    fun withoutSumsFileTheVersionStillShows() {
        // Se ofrece; al bajarla, sin suma no se instala.
        assertNull(pickNewest(list(release("v1.4.163", sums = false)), 0)!!.checksums)
    }

    @Test
    fun checksumOfTheApkLine() {
        val lines = listOf(
            "1111111111111111111111111111111111111111111111111111111111111111  notas.txt",
            "  abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789  litedash-1.4.162.apk ",
            "2222222222222222222222222222222222222222222222222222222222222222  litedash.apk",
        )
        assertEquals("abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789", checksumOf(lines))
        assertNull(checksumOf(listOf("1111  notas.txt")))
        assertNull(checksumOf(emptyList()))
    }
}
