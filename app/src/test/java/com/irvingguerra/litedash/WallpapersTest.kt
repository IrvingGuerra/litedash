package com.irvingguerra.litedash

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class WallpapersTest {
    @Test
    fun onlySafeIdsSurvive() {
        val list = parseWallpapers(
            """{"wallpapers":[{"id":"aurora-suave","credit":"NASA"},{"id":"../x"},{"id":"Glow"},{"id":""},{"id":"glow-2"}]}""",
        )!!
        assertEquals(listOf("aurora-suave", "glow-2"), list.map { it.id })
        assertEquals("NASA", list[0].credit)
        assertEquals("", list[1].credit)
    }

    @Test
    fun notAListIsNull() {
        // P. ej. la página de registro de un wifi en vez de index.json.
        assertNull(parseWallpapers("<html>login</html>"))
        assertNull(parseWallpapers("""{"otra":1}"""))
    }

    @Test
    fun replaceFileKeepsTheOldOneOnFailure() {
        val dir = Files.createTempDirectory("wallpapers").toFile()
        val target = File(dir, "wallpaper.jpg").apply { writeText("vieja") }
        try {
            replaceFile(target) { temp ->
                temp.writeText("a medias")
                throw IOException("se cortó")
            }
            fail("debía lanzar")
        } catch (e: IOException) {
            assertEquals("vieja", target.readText())
        }
        assertEquals("ok", replaceFile(target) { temp -> temp.writeText("nueva"); "ok" })
        assertEquals("nueva", target.readText())
        // Sin temporales sueltos.
        assertFalse(File(dir, "wallpaper.jpg.part").exists())
        dir.deleteRecursively()
    }
}
