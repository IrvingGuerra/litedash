package com.irvingguerra.litedash.feature.music

import android.content.Context
import android.content.pm.PackageManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Lo que una app de fábrica deja ver por fuera, para los reportes: sus componentes exportados y
 * los nombres de acciones de su manifiesto. Las radios y apps de Bluetooth de fábrica no publican
 * sesión ni receptor del botón de play, pero casi todas escuchan órdenes propias
 * ("com.marca.radio.action.PLAY"); con cada reporte se aprende cómo hablarle a otra pantalla.
 */
object AppProbe {
    private const val MAX_NAMES = 60
    private const val MAX_NAME_LENGTH = 120
    /** Textos más largos que esto no son nombres: ni se leen. */
    private const val MAX_READ = 400
    private const val MAX_STRINGS = 5_000

    /** Texto para el reporte (varias líneas con sangría); vacío si no se pudo leer nada. Lee el APK: fuera del hilo principal. */
    fun describe(context: Context, pkg: String): String = buildString {
        val components = runCatching { exported(context, pkg) }.getOrDefault(emptyList())
        if (components.isNotEmpty()) append("\n    exportados: ").append(components.joinToString())
        val names = runCatching { manifestNames(context, pkg) }.getOrDefault(emptyList())
        if (names.isNotEmpty()) append("\n    manifiesto: ").append(names.joinToString())
    }

    /** Receptores, servicios y proveedores exportados (sin el paquete delante: ".RadioService"). */
    @Suppress("DEPRECATION") // getPackageInfo(String, Int): la de flags largos es de Android 13
    private fun exported(context: Context, pkg: String): List<String> {
        val flags = PackageManager.GET_RECEIVERS or PackageManager.GET_SERVICES or PackageManager.GET_PROVIDERS
        val info = context.packageManager.getPackageInfo(pkg, flags)
        val all = info.receivers.orEmpty().map { "r:" to it } + info.services.orEmpty().map { "s:" to it } + info.providers.orEmpty().map { "p:" to it }
        return all.filter { it.second.exported }.map { (kind, c) -> kind + c.name.removePrefix(pkg) }.take(MAX_NAMES)
    }

    /**
     * Los nombres con puntos del manifiesto (acciones, clases, permisos), leídos de su tabla de
     * textos: el manifiesto dentro del APK es XML binario y sus textos van todos juntos al inicio.
     */
    private fun manifestNames(context: Context, pkg: String): List<String> {
        val apk = context.packageManager.getApplicationInfo(pkg, 0).sourceDir
        val bytes = ZipFile(apk).use { zip -> zip.getEntry("AndroidManifest.xml")?.let { e -> zip.getInputStream(e).use { it.readBytes() } } }
            ?: return emptyList()
        return stringPool(bytes)
            .filter { NAME.matches(it) && !it.startsWith("android.") && !it.startsWith("androidx.") && !it.startsWith("com.google.") }
            .map { it.take(MAX_NAME_LENGTH) }
            .distinct()
            .take(MAX_NAMES)
    }

    private val NAME = Regex("""[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z0-9_]+){2,}""")

    /**
     * La tabla de textos (ResStringPool) del XML binario de Android. Nunca truena: con un archivo
     * raro o mal hecho regresa vacío, y los textos largos (que no son nombres) ni se leen.
     */
    internal fun stringPool(bytes: ByteArray): List<String> = runCatching {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 36 || b.getShort(0).toInt() != 0x0003) return emptyList()
        val pool = b.getShort(2).toInt() and 0xFFFF // el primer trozo va justo después del encabezado
        if (pool + 28 > bytes.size || b.getShort(pool).toInt() != 0x0001) return emptyList()
        val headerSize = b.getShort(pool + 2).toInt() and 0xFFFF
        val count = b.getInt(pool + 8)
        val utf8 = b.getInt(pool + 16) and 0x100 != 0
        val stringsStart = pool + b.getInt(pool + 20)
        if (count !in 0..MAX_STRINGS) return emptyList()
        (0 until count).mapNotNull { i ->
            runCatching {
                var at = stringsStart + b.getInt(pool + headerSize + i * 4)
                if (utf8) {
                    at += if (bytes[at].toInt() and 0x80 != 0) 2 else 1 // largo en UTF-16 (no se usa)
                    var length = bytes[at].toInt() and 0xFF
                    if (length and 0x80 != 0) { length = (length and 0x7F) shl 8 or (bytes[at + 1].toInt() and 0xFF); at++ }
                    if (length > MAX_READ) null else String(bytes, at + 1, length, Charsets.UTF_8)
                } else {
                    var length = b.getShort(at).toInt() and 0xFFFF
                    if (length and 0x8000 != 0) { length = (length and 0x7FFF) shl 16 or (b.getShort(at + 2).toInt() and 0xFFFF); at += 2 }
                    if (length > MAX_READ) null else String(bytes, at + 2, length * 2, Charsets.UTF_16LE)
                }
            }.getOrNull()
        }
    }.getOrDefault(emptyList())
}
