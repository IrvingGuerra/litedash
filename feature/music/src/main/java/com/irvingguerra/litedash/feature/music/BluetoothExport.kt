package com.irvingguerra.litedash.feature.music

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * «Exportar la app de Bluetooth» (Ajustes → Apps → Música → Bluetooth): un .zip con la app de Bluetooth de fábrica de la
 * pantalla y un info.txt con el equipo y lo que esa app deja ver, para que el usuario lo mande a soporte y se aprenda a
 * hablarle (idea de StradaOS #79). Solo apps del sistema y datos del equipo: nada de cuentas, celulares ni ubicación.
 */
object BluetoothExport {
    private const val TAG = "BluetoothExport"
    private const val MAX_APPS = 6
    private const val MAX_APP_BYTES = 60L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 120L * 1024 * 1024
    private const val MAX_MANIFEST_NAMES = 400

    /** Las de Android (y su servicio MIDI): no hacen falta. */
    private const val ANDROID_BLUETOOTH = "com.android.bluetooth"

    /**
     * Las candidatas, las más probables primero: la enlazada o conocida, las de marca de [HeadUnit] instaladas y las del
     * sistema con nombre de Bluetooth. [includeAndroid] (solo en depuración) deja entrar la de Android para probar en el
     * emulador. Bloquea: consultas al instalador.
     */
    fun candidates(context: Context, music: MusicRepository, includeAndroid: Boolean): List<String> {
        val installed = runCatching {
            @Suppress("DEPRECATION") // getInstalledApplications(Int): la de flags largos es de Android 13
            context.packageManager.getInstalledApplications(0).associate { it.packageName to (it.flags and ApplicationInfo.FLAG_SYSTEM != 0) }
        }.getOrDefault(emptyMap())
        val known = runCatching { listOf(music.bluetoothApp(), music.sourceApps[SourceKind.BLUETOOTH], music.knownBluetooth) }
            .getOrDefault(emptyList())
        return pickCandidates(installed, known, HeadUnit.bluetoothApps, includeAndroid)
    }

    /**
     * La selección, pura: [installed] = paquete → es del sistema. Las de marca ([brand]) entran aunque no lleven la
     * bandera de sistema (hay pantallas que las traen en /data/app, ver [HeadUnit.of]); las demás, solo del sistema.
     */
    internal fun pickCandidates(installed: Map<String, Boolean>, known: List<String?>, brand: List<String>, includeAndroid: Boolean): List<String> {
        fun allowed(pkg: String): Boolean {
            if (pkg.startsWith("com.google.")) return false
            if (pkg.startsWith(ANDROID_BLUETOOTH)) return includeAndroid && pkg == ANDROID_BLUETOOTH && installed[pkg] == true
            return pkg in brand && pkg in installed || installed[pkg] == true
        }
        val guessed = installed.keys.filter { looksExportable(it) || includeAndroid && it == ANDROID_BLUETOOTH }
            .sortedWith(compareBy({ rank(it) }, { it }))
        return (known.filterNotNull() + brand + guessed).distinct().filter(::allowed).take(MAX_APPS)
    }

    /** Nombre de Bluetooth: el de los reportes ([looksBluetooth]) o algún segmento con a2dp, avrcp o goc (Goodocom). */
    private fun looksExportable(pkg: String): Boolean =
        looksBluetooth(pkg) || pkg.lowercase().split('.').drop(1).any { "a2dp" in it || "avrcp" in it || "goc" in it }

    /** Primero las de música («btmusic»), luego las de Bluetooth y al final los servicios (a2dp, goc…). */
    private fun rank(pkg: String): Int {
        val p = pkg.lowercase()
        return when {
            "music" in p -> 0
            looksBluetooth(p) -> 1
            else -> 2
        }
    }

    /** «litedash-bluetooth-<modelo>-<AAAAMMDD>.zip». */
    fun fileName(now: Date = Date()): String {
        val model = Build.MODEL.orEmpty().replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').take(40).ifEmpty { "screen" }
        return "litedash-bluetooth-$model-${SimpleDateFormat("yyyyMMdd", Locale.US).format(now)}.zip"
    }

    /** Lo que se copia de una app: sus archivos (nombre dentro del zip → archivo) o por qué no cupo. */
    private class Plan(val pkg: String, val files: List<Pair<String, File>>, val bytes: Long, val skipped: String?)

    private fun plan(context: Context, pkgs: List<String>): List<Plan> {
        var total = 0L
        return pkgs.mapNotNull { pkg ->
            val info = runCatching { context.packageManager.getApplicationInfo(pkg, 0) }.getOrNull() ?: return@mapNotNull null
            val splits = info.splitSourceDirs.orEmpty().map { File(it) }
            val files = listOf("apps/$pkg/base.apk" to File(info.sourceDir)) + splits.map { "apps/$pkg/${it.name}" to it }
            val bytes = files.sumOf { it.second.length() }
            val skipped = when {
                bytes > MAX_APP_BYTES -> "pasa de 60 MB"
                total + bytes > MAX_TOTAL_BYTES -> "ya no cabe (tope de 120 MB en total)"
                files.any { !it.second.canRead() } -> "Android no deja leerla"
                else -> null
            }
            if (skipped == null) total += bytes
            Plan(pkg, files, bytes, skipped)
        }
    }

    /** El info.txt: el equipo y, por app, versión, tamaño y lo que deja ver (componentes y nombres del manifiesto). */
    private fun info(context: Context, plans: List<Plan>): String = buildString {
        val own = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        appendLine("LiteDash ${own?.versionName} (${own?.let(PackageInfoCompat::getLongVersionCode)}) · app de Bluetooth exportada")
        appendLine("Equipo: ${Build.MANUFACTURER} / ${Build.BRAND} / ${Build.MODEL} / ${Build.DEVICE}")
        appendLine("Placa: ${Build.BOARD} · ${Build.HARDWARE}")
        appendLine("Android API ${Build.VERSION.SDK_INT}")
        for (p in plans) {
            appendLine().appendLine("== ${p.pkg} ==")
            val pi = runCatching { context.packageManager.getPackageInfo(p.pkg, 0) }.getOrNull()
            appendLine("versión: ${pi?.versionName} (${pi?.let(PackageInfoCompat::getLongVersionCode)})")
            appendLine("tamaño: ${p.bytes / 1024} KB en ${p.files.size} archivo(s)")
            if (p.skipped != null) appendLine("omitida del zip: ${p.skipped}")
            val components = runCatching { AppProbe.components(context, p.pkg, activities = true) }.getOrDefault(emptyList())
            appendLine("componentes (a = actividad, r = receptor, s = servicio, p = proveedor):")
            for ((name, exported) in components) appendLine("  $name exportado=${yes(exported)}")
            val names = runCatching { AppProbe.manifestNames(context, p.pkg, MAX_MANIFEST_NAMES) }.getOrDefault(emptyList())
            if (names.isNotEmpty()) appendLine("manifiesto (acciones, clases y permisos): ${names.joinToString()}")
        }
    }

    /** Escribe el zip en [out]; falso si no se pudo (se anota). Bloquea: lee y copia varios MB. */
    fun write(context: Context, out: OutputStream, pkgs: List<String>): Boolean = try {
        val plans = plan(context, pkgs)
        ZipOutputStream(out.buffered()).use { zip ->
            zip.setLevel(Deflater.BEST_SPEED) // los APK ya vienen comprimidos: no vale la pena tardar más
            zip.putNextEntry(ZipEntry("info.txt"))
            zip.write(info(context, plans).toByteArray())
            zip.closeEntry()
            for (p in plans) {
                if (p.skipped != null) continue
                for ((name, file) in p.files) {
                    zip.putNextEntry(ZipEntry(name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        true
    } catch (e: IOException) {
        Log.w(TAG, "no se pudo exportar", e)
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "no se pudo exportar", e)
        false
    }
}
