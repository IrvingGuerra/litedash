// Tu foto de fondo mientras un Diseño pone el suyo: se guarda aparte una sola vez y «Deshacer» la regresa.
package com.irvingguerra.litedash

import java.io.File
import java.io.IOException

/**
 * Lo de los archivos del fondo con los Diseños, sin Android (se prueba con una carpeta cualquiera; quien llama lo hace en
 * IO y con el candado del fondo). En [dir]: el fondo ([CURRENT]) y cuál de LiteDash es ([MARKER]); tu fondo de antes de
 * los Diseños ([OWN] y [OWN_INFO]: si había imagen y su id de LiteDash) y la huella del fondo que dejó el último Diseño
 * ([PLACED]: tamaño y hora, o «none» si lo dejó sin imagen esperando internet). Si la huella ya no coincide, alguien
 * cambió el fondo después (tu foto nueva manda: ni se guarda encima ni se pisa al deshacer).
 */
internal object OwnWallpaper {
    const val CURRENT = "wallpaper.jpg"
    const val MARKER = "wallpaper.id"
    private const val OWN = "wallpaper.own.jpg"
    private const val OWN_INFO = "wallpaper.own"
    private const val PLACED = "wallpaper.design"
    private const val NONE = "none"

    /** ¿El fondo de ahora es el que dejó un Diseño (nadie lo cambió después)? */
    fun placedByDesign(dir: File): Boolean = read(File(dir, PLACED)) == stamp(dir)

    /**
     * Antes de que un Diseño cambie el fondo: si el de ahora es tuyo, se copia aparte; si es de otro Diseño, se queda la
     * copia que ya había (si no, el segundo Diseño la pisaba con el fondo del primero). Lanza si no se pudo copiar:
     * entonces no se debe cambiar el fondo (se perdería).
     */
    fun keep(dir: File) {
        val info = File(dir, OWN_INFO)
        if (placedByDesign(dir) && info.exists()) return
        val current = File(dir, CURRENT)
        val had = current.exists() && !placedByDesign(dir)
        if (had) replace(File(dir, OWN)) { current.copyTo(it, overwrite = true) }
        val id = if (had) read(File(dir, MARKER)).orEmpty() else ""
        replace(info) { it.writeText(if (had) "1\n$id" else "0\n") }
    }

    /** Un Diseño acaba de poner su fondo (o de quitarlo mientras espera internet): se anota su huella. */
    fun placed(dir: File) = replace(File(dir, PLACED)) { it.writeText(stamp(dir)) }

    /**
     * Regresa tu fondo guardado ([keep]) si el de ahora sigue siendo el de un Diseño; si lo cambiaste después, no se
     * toca. La copia se queda (otro Diseño la vuelve a usar). true si cambió el fondo.
     */
    fun restore(dir: File): Boolean {
        if (!placedByDesign(dir)) return false
        val lines = read(File(dir, OWN_INFO))?.lines() ?: return false
        val current = File(dir, CURRENT)
        val own = File(dir, OWN)
        if (lines.first() == "1") {
            // Sin la copia (no debería pasar) se queda el que está.
            if (!own.exists()) return false
            replace(current) { own.copyTo(it, overwrite = true) }
        } else current.delete()
        val marker = File(dir, MARKER)
        val id = lines.getOrNull(1).orEmpty()
        if (id.isNotEmpty()) replace(marker) { it.writeText(id) } else marker.delete()
        File(dir, PLACED).delete()
        return true
    }

    /** La huella del fondo de ahora: tamaño y hora de escritura, o [NONE] sin imagen. */
    private fun stamp(dir: File): String = File(dir, CURRENT).let { if (it.exists()) "${it.length()}:${it.lastModified()}" else NONE }

    private fun read(f: File): String? = if (f.exists()) f.readText().trim() else null

    private fun replace(target: File, fill: (File) -> Unit) {
        replaceFile(target, fill)
        if (!target.exists()) throw IOException("missing $target")
    }
}
