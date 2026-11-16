package com.irvingguerra.litedash.feature.car

import com.irvingguerra.litedash.core.ui.Https
import java.io.File
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * [file] tal cual llegó (el celular ya lo bajó): si no mide [bytes] (≤ 0 = no se revisa) o su SHA-256 no es
 * [sha256], se borra y lanza [Damaged]. Solo en IO.
 */
internal fun verified(file: File, bytes: Long, sha256: String): File {
    val ok = (bytes <= 0 || file.length() == bytes) && runCatching { Https.sha256(file) }.getOrNull().equals(sha256, ignoreCase = true)
    if (ok) return file
    file.delete()
    throw Damaged("El archivo llegó dañado")
}

/**
 * Descomprime [zip] en [target] con topes para cualquier paquete (también los del catálogo): a lo más
 * [UNZIP_RATIO] veces lo que mide el zip (entre [UNZIP_MIN] y [UNZIP_MAX]), [UNZIP_ENTRIES] archivos y
 * [UNZIP_FILE_MAX] por archivo, contando lo que de verdad sale (no lo que dice el zip); lo que apunte fuera de
 * [target] ("../") se salta. Los paquetes reales son imágenes WebP que casi no se comprimen (≤ 1.0×, ≤ 127
 * archivos, ≤ 70 KB cada uno): un zip que se expande de más no es un auto ([Damaged]). Solo en IO.
 */
internal fun unzipPackage(zip: File, target: File) {
    val budget = (zip.length() * UNZIP_RATIO).coerceIn(UNZIP_MIN, UNZIP_MAX)
    val inside = target.canonicalPath + File.separator
    var total = 0L
    var entries = 0
    val buffer = ByteArray(64 * 1024)
    try {
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val item = input.nextEntry ?: break
                if (++entries > UNZIP_ENTRIES) throw Damaged("Demasiados archivos")
                val file = File(target, item.name)
                if (!file.canonicalPath.startsWith(inside)) continue
                if (item.isDirectory) file.mkdirs() else {
                    file.parentFile?.mkdirs()
                    file.outputStream().use { out ->
                        var written = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            written += n
                            total += n
                            if (written > UNZIP_FILE_MAX || total > budget) throw Damaged("El paquete se expande de más")
                            out.write(buffer, 0, n)
                        }
                    }
                }
            }
        }
    } catch (e: ZipException) {
        throw Damaged("Zip dañado: ${e.message}")
    }
}

// Topes al descomprimir: con mucho aire sobre los paquetes de hoy (~2.5 MB, 127 archivos).
internal const val UNZIP_RATIO = 8
internal const val UNZIP_MIN = 1L shl 20
internal const val UNZIP_MAX = 200L shl 20
internal const val UNZIP_ENTRIES = 2_000
internal const val UNZIP_FILE_MAX = 16L shl 20
