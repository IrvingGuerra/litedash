package com.irvingguerra.litedash.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryWalkTest {
    /** Un árbol falso: cada carpeta (su ruta) con sus nombres; los que terminan en "/" son carpetas. */
    private fun walk(tree: Map<String, List<String>>): List<String> =
        Library.walk("", { dir ->
            tree[dir]?.map { name ->
                val isDir = name.endsWith("/")
                val clean = name.trimEnd('/')
                Library.Entry(if (dir.isEmpty()) clean else "$dir/$clean", clean, isDir, !isDir && Library.isAudio(clean))
            }
        }) { _, name, folder -> if (folder.isEmpty()) name else "$folder|$name" }

    @Test
    fun songsWithTheirFolder() {
        val found = walk(
            mapOf(
                "" to listOf("intro.mp3", "Rock/", "notas.txt"),
                "Rock" to listOf("Metallica/", "uno.FLAC"),
                "Rock/Metallica" to listOf("one.m4a", "cover.jpg"),
            ),
        )
        assertEquals(setOf("intro.mp3", "Rock|uno.FLAC", "Rock/Metallica|one.m4a"), found.toSet())
    }

    @Test
    fun skipsHiddenAndSystemFolders() {
        val found = walk(
            mapOf(
                "" to listOf(".oculta.mp3", ".git/", "Android/", "LOST.DIR/", "Música/"),
                ".git" to listOf("a.mp3"),
                "Android" to listOf("b.mp3"),
                "LOST.DIR" to listOf("c.mp3"),
                "Música" to listOf("d.mp3"),
            ),
        )
        assertEquals(listOf("Música|d.mp3"), found)
    }

    @Test
    fun depthIsCapped() {
        // d1/d2/…/d12, con una canción en cada nivel.
        val tree = HashMap<String, List<String>>()
        var path = ""
        for (level in 1..12) {
            tree[path] = listOf("d$level/", "s$level.mp3")
            path = if (path.isEmpty()) "d$level" else "$path/d$level"
        }
        val levels = walk(tree).map { it.substringAfterLast('|').removePrefix("s").removeSuffix(".mp3").toInt() }
        // La raíz es el nivel 0: se leen las carpetas hasta la profundidad máxima.
        assertEquals((1..Library.MAX_DEPTH + 1).toList(), levels.sorted())
    }

    @Test
    fun foldersAreCapped() {
        val many = (1..Library.MAX_DIRS + 50).map { "c$it/" }
        val tree = mapOf("" to many) + many.associate { it.trimEnd('/') to listOf("x.mp3") }
        assertTrue(walk(tree).size < Library.MAX_DIRS)
    }

    @Test
    fun filesAreCappedInsideOneFolder() {
        val tree = mapOf("" to (1..Library.MAX_FILES + 500).map { "s$it.mp3" })
        assertEquals(Library.MAX_FILES, walk(tree).size)
    }

    @Test
    fun audioByExtension() {
        assertTrue(Library.isAudio("canción.MP3"))
        assertTrue(Library.isAudio("a.b.opus"))
        assertFalse(Library.isAudio("mp3"))
        assertFalse(Library.isAudio("portada.jpg"))
    }

    @Test
    fun groupsThatDifferOnlyInCaseKeepEverySong() {
        val songs = listOf("Maná|Rayando el sol", "MANÁ|Oye mi amor", "Café Tacvba|Eres", "maná|Clavado en un bar")
        val groups = Library.groups(songs) { it.substringBefore('|') }
        assertEquals(listOf("Café Tacvba", "Maná"), groups.keys.toList())
        assertEquals(3, groups["Maná"]?.size)
        assertEquals(songs.size, groups.values.sumOf { it.size })
    }
}
