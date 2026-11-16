package com.irvingguerra.litedash.feature.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Los topes al descomprimir un paquete (el celular también puede mandarlos) y la revisión de su huella. */
class PackageFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun zip(vararg files: Pair<String, ByteArray>): File = tmp.newFile().also { f ->
        ZipOutputStream(f.outputStream()).use { out ->
            for ((name, bytes) in files) {
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
    }

    private fun sha256(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    @Test
    fun unzipsAPackage() {
        val target = File(tmp.root, "car")
        unzipPackage(zip("auto.json" to "{}".toByteArray(), "giro/00.webp" to ByteArray(3000) { it.toByte() }), target)
        assertEquals("{}", File(target, "auto.json").readText())
        assertEquals(3000L, File(target, "giro/00.webp").length())
    }

    @Test
    fun skipsPathsOutsideTheFolder() {
        val target = File(tmp.root, "car")
        unzipPackage(zip("../evil.txt" to "x".toByteArray(), "giro/../../evil2.txt" to "x".toByteArray(), "ok.txt" to "y".toByteArray()), target)
        assertFalse(File(tmp.root, "evil.txt").exists())
        assertFalse(File(tmp.root, "evil2.txt").exists())
        assertTrue(File(target, "ok.txt").exists())
    }

    @Test
    fun rejectsAZipBomb() {
        // 20 MB de ceros caben en unos KB: se expande mucho más que [UNZIP_RATIO] veces (y que [UNZIP_MIN]).
        val bomb = zip("giro/00.webp" to ByteArray(20 shl 20))
        assertTrue(bomb.length() * UNZIP_RATIO < UNZIP_MIN)
        assertThrows(Damaged::class.java) { unzipPackage(bomb, File(tmp.root, "car")) }
    }

    @Test
    fun rejectsTooManyFiles() {
        val many = zip(*Array(UNZIP_ENTRIES + 1) { "f$it" to ByteArray(0) })
        assertThrows(Damaged::class.java) { unzipPackage(many, File(tmp.root, "car")) }
    }

    @Test
    fun rejectsSomethingThatIsNotAZip() {
        val junk = tmp.newFile().apply { writeBytes(ByteArray(100) { 0x50 }) }
        val target = File(tmp.root, "car")
        // Un archivo que no es zip no deja nada (ZipInputStream no ve entradas) o lanza Damaged: nunca otra cosa.
        runCatching { unzipPackage(junk, target) }.exceptionOrNull()?.let { assertTrue(it is Damaged) }
        assertTrue(target.listFiles().isNullOrEmpty())
    }

    @Test
    fun verifiedKeepsTheAnnouncedFile() {
        val f = zip("a" to "hola".toByteArray())
        assertEquals(f, verified(f, f.length(), sha256(f).uppercase()))
        assertTrue(f.exists())
    }

    @Test
    fun verifiedDeletesAnotherFile() {
        val f = zip("a" to "hola".toByteArray())
        val sha = sha256(f)
        assertThrows(Damaged::class.java) { verified(f, f.length() + 1, sha) }
        assertFalse(f.exists())
        val g = zip("a" to "adiós".toByteArray())
        assertThrows(Damaged::class.java) { verified(g, g.length(), "0".repeat(64)) }
        assertFalse(g.exists())
    }
}
