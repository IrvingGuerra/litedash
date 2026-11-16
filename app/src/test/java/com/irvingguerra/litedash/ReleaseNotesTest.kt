package com.irvingguerra.litedash

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseNotesTest {
    @Test
    fun titlesBulletsAndWrappedLines() {
        val notes = """
            ### Agregado
            - **Mapa** con zoom
              continuo y suave.
            - Otra cosa

            ### Arreglado
            - Un bug
        """.trimIndent()
        assertEquals(
            listOf("### Agregado", "Mapa con zoom continuo y suave.", "Otra cosa", "### Arreglado", "Un bug"),
            releaseNotes(notes),
        )
    }

    @Test
    fun loosePlainTextBeforeAnyBulletIsIgnored() {
        assertEquals(listOf("Uno"), releaseNotes("Intro sin viñeta\n- Uno"))
    }

    @Test
    fun aLineRightAfterATitleIsNotGluedToIt() {
        assertEquals(listOf("### Agregado", "Uno"), releaseNotes("### Agregado\ntexto suelto\n- Uno"))
    }
}
