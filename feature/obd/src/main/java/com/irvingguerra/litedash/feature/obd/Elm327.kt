package com.irvingguerra.litedash.feature.obd

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** El canal con el adaptador: Bluetooth, Wi-Fi o USB, para el protocolo da igual. */
interface ElmLink : Closeable {
    val input: InputStream
    val output: OutputStream
}

/**
 * Habla con un ELM327 (y sus clones): manda un comando, lee hasta el ">" y deja la
 * respuesta limpia (sin eco, espacios ni "SEARCHING..."). Bloqueante: se llama
 * desde un hilo de fondo y de uno en uno.
 */
class Elm327(private val link: ElmLink, private val now: () -> Long = System::currentTimeMillis) {

    /** Respuesta del adaptador ya en mayúsculas y sin espacios, renglón por renglón. */
    fun command(cmd: String, timeoutMs: Long = 3_000): List<String> {
        link.output.write("$cmd\r".toByteArray())
        link.output.flush()
        val text = StringBuilder()
        val deadline = now() + timeoutMs
        while (true) {
            if (link.input.available() == 0) {
                if (now() > deadline) throw IOException("El adaptador no respondió a $cmd")
                Thread.sleep(5)
                continue
            }
            val b = link.input.read()
            if (b < 0) throw IOException("Se cerró la conexión")
            if (b.toChar() == '>') break
            text.append(b.toChar())
        }
        return text.split('\r', '\n')
            .map { it.replace(" ", "").uppercase() }
            .filter { it.isNotEmpty() && it != cmd.replace(" ", "").uppercase() && !it.startsWith("SEARCHING") && it != "OK" }
    }

    /**
     * Prepara el adaptador: reinicia, sin eco ni espacios, protocolo automático, y
     * despierta la computadora del auto. Devuelve la versión del adaptador.
     */
    fun init(): String {
        // La respuesta llega sin espacios ("ELM327V1.5"): se le regresa el suyo para mostrarla.
        val version = runCatching { command("ATZ", 4_000).lastOrNull() }.getOrNull()?.replace("ELM327V", "ELM327 v") ?: ""
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0")) command(c)
        // La primera consulta busca el protocolo del auto (puede tardar unos segundos).
        val probe = command("0100", 10_000)
        if (probe.none { it.contains("4100") }) throw IOException(probe.firstOrNull() ?: "Sin respuesta del auto")
        return version
    }

    /** Datos del modo 01 para [pid] (sin el "41 PID"); null si el auto no lo da. */
    fun pid(pid: Int): IntArray? {
        val head = "41%02X".format(pid)
        val line = command("01%02X".format(pid)).firstOrNull { it.startsWith(head) } ?: return null
        return hexBytes(line.substring(head.length))
    }

    /** Qué PIDs del modo 01 contesta el auto (consultando 00, 20, 40… mientras haya más). */
    fun supportedPids(): Set<Int> {
        val supported = mutableSetOf<Int>()
        var base = 0x00
        while (base <= 0xA0) {
            // Hay adaptadores que tardan o no contestan las páginas altas: se queda con lo que haya.
            val bits = runCatching { pid(base) }.getOrNull() ?: break
            for (i in 0 until 32) {
                val byte = bits.getOrNull(i / 8) ?: 0
                if (byte and (0x80 shr (i % 8)) != 0) supported += base + i + 1
            }
            if (base + 0x20 !in supported) break
            base += 0x20
        }
        return supported
    }

    /** Códigos de falla: "03" guardados, "07" pendientes, "0A" permanentes. */
    fun troubleCodes(mode: String): List<String> = parseTroubleCodes(mode, command(mode, 5_000))

    /** Borra códigos y apaga la luz de falla (también reinicia los monitores). */
    fun clearCodes(): Boolean = command("04", 5_000).any { it.startsWith("44") }

    /** El número de serie del auto (17 caracteres), si lo da. */
    fun vin(): String? = parseVin(command("0902", 5_000))

    /** Voltaje en el conector OBD (la batería), medido por el adaptador. */
    fun voltage(): Float? = command("ATRV").firstOrNull()?.removeSuffix("V")?.toFloatOrNull()
}

internal fun hexBytes(hex: String): IntArray? {
    if (hex.length % 2 != 0 || hex.any { it !in "0123456789ABCDEF" }) return null
    return IntArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16) }
}

/**
 * Respuesta de los modos 03/07/0A a códigos "P0301". En CAN cada renglón trae primero
 * cuántos códigos son; en los protocolos viejos no: se nota en que los bytes son nones.
 */
internal fun parseTroubleCodes(mode: String, lines: List<String>): List<String> {
    val head = "%02X".format(mode.toInt(16) + 0x40)
    val codes = mutableListOf<String>()
    for (line in lines) {
        val clean = line.substringAfter(':')
        if (!clean.startsWith(head)) continue
        var bytes = hexBytes(clean.substring(head.length)) ?: continue
        if (bytes.size % 2 == 1) bytes = bytes.copyOfRange(1, bytes.size)
        for (i in bytes.indices step 2) {
            val a = bytes[i]
            val b = bytes.getOrNull(i + 1) ?: 0
            if (a == 0 && b == 0) continue
            val letter = "PCBU"[a shr 6]
            codes += "%c%d%X%02X".format(letter, (a shr 4) and 0x3, a and 0xF, b)
        }
    }
    return codes.distinct()
}

/** VIN del modo 09 PID 02: viene en varios renglones ("0:", "1:"…) o en uno. */
internal fun parseVin(lines: List<String>): String? {
    val joined = lines.filter { it.contains(':') || it.startsWith("49") }
        .joinToString("") { it.substringAfter(':') }
    val start = joined.indexOf("4902")
    if (start < 0) return null
    // Después de "4902" viene un byte con el número de mensaje y luego los caracteres.
    val bytes = hexBytes(joined.substring(start + 4).let { if (it.length % 2 == 0) it else it.dropLast(1) }) ?: return null
    val text = bytes.drop(1).filter { it in 0x30..0x5A }.map { it.toChar() }.joinToString("")
    return text.takeLast(17).takeIf { it.length == 17 }
}
