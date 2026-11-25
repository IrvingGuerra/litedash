package com.irvingguerra.litedash

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnWallpaperTest {
    private val dir: File = Files.createTempDirectory("own-wallpaper").toFile()
    private val current = File(dir, OwnWallpaper.CURRENT)
    private val marker = File(dir, OwnWallpaper.MARKER)

    /** Un Diseño pone su fondo [id] (null = sin internet: sin imagen), como lo hace la app: tu foto aparte y su huella. */
    private fun design(id: String?) {
        OwnWallpaper.keep(dir)
        if (id == null) {
            current.delete()
            marker.delete()
        } else {
            current.writeText("fondo $id")
            marker.writeText(id)
        }
        OwnWallpaper.placed(dir)
    }

    @Test
    fun twoDesignsInARowThenUndoBringsBackYourPhoto() {
        current.writeText("mi foto")
        design("grafito-haz")
        design("pista-carbon")
        assertTrue(OwnWallpaper.restore(dir))
        assertEquals("mi foto", current.readText())
        assertFalse(marker.exists())
        // Otra vez deshacer: ya es tu foto, nada cambia.
        assertFalse(OwnWallpaper.restore(dir))
        assertEquals("mi foto", current.readText())
    }

    @Test
    fun withoutInternetYourPhotoStillComesBack() {
        current.writeText("mi foto")
        design(null)
        design(null)
        assertTrue(OwnWallpaper.restore(dir))
        assertEquals("mi foto", current.readText())
    }

    @Test
    fun aLiteDashWallpaperYouChoseKeepsItsName() {
        current.writeText("aurora")
        marker.writeText("aurora-iss")
        design("nube-perla")
        assertTrue(OwnWallpaper.restore(dir))
        assertEquals("aurora", current.readText())
        assertEquals("aurora-iss", marker.readText())
    }

    @Test
    fun undoDoesNotTouchAWallpaperChosenAfterTheDesign() {
        current.writeText("mi foto")
        design("grafito-haz")
        // Eliges otra foto después del Diseño.
        current.writeText("foto nueva y más larga")
        assertFalse(OwnWallpaper.restore(dir))
        assertEquals("foto nueva y más larga", current.readText())
        // El siguiente Diseño guarda esa como la tuya.
        design("neon-noche")
        assertTrue(OwnWallpaper.restore(dir))
        assertEquals("foto nueva y más larga", current.readText())
    }

    @Test
    fun withoutAPhotoUndoLeavesNoImage() {
        design("grafito-haz")
        assertTrue(OwnWallpaper.restore(dir))
        assertFalse(current.exists())
    }
}
