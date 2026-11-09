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
        // Lo que haya quedado de un comando que expiró (su ">" tardío) se tira: si no, el
        // siguiente leería esa respuesta y todas quedarían desfasadas.
        while (link.input.available() > 0) link.input.read()
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
        firstReply = probe
        if (probe.none { it.contains("4100") }) throw IOException(probe.firstOrNull() ?: "Sin respuesta del auto")
        return version
    }

    /** Lo que contestó el auto al primer "0100" (para los reportes). */
    var firstReply: List<String> = emptyList()
        private set

    /** Datos del modo 01 para [pid] (sin el "41 PID"); null si el auto no lo da. */
    fun pid(pid: Int): IntArray? = afterHead("41%02X".format(pid), command("01%02X".format(pid)))?.let(::hexBytes)

    /**
     * Consultas a otro módulo del auto (no al motor): antes [setup] (su dirección, p. ej.
     * "ATSH750", y lo que pida), luego cada una de [requests] ("22C00B"). Por cada una, los datos
     * tras el inicio de la respuesta positiva; null si no contestó o dijo que no (y ya no se
     * pregunta lo demás: no es este módulo). Siempre deja el adaptador como lo espera el motor
     * ([restore]): si no, los medidores dejarían de leer.
     */
    fun module(setup: List<String>, requests: List<String>, restore: List<String>): List<IntArray?> = try {
        for (c in setup) command(c)
        val out = ArrayList<IntArray?>(requests.size)
        for (r in requests) {
            val reply = parseModuleReply(r, command(r, MODULE_TIMEOUT_MS))
            out += reply
            if (reply == null) break
        }
        out
    } finally {
        for (c in restore) runCatching { command(c) }
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

/**
 * La respuesta de un módulo a [request] (en hex, sin espacios): une los renglones de una
 * respuesta larga ("00A", "0:62C00B…", "1:…" sin encabezados) y regresa lo que sigue de la
 * respuesta positiva ([request] con el primer byte + 0x40); null si no está o si dijo que no (7F).
 */
internal fun parseModuleReply(request: String, lines: List<String>): IntArray? {
    val req = request.replace(" ", "").uppercase()
    if (req.length < 2) return null
    val positive = "%02X".format(req.substring(0, 2).toInt(16) + 0x40) + req.substring(2)
    val joined = lines
        .filterNot { MULTI_LENGTH.matches(it) }
        .joinToString("") { it.replace(MULTI_INDEX, "") }
    val at = joined.indexOf(positive)
    if (at < 0) return null
    return hexBytes(joined.substring(at + positive.length))
}

/** Lo que se espera a otro módulo del auto: uno que no existe no contesta nada. */
private const val MODULE_TIMEOUT_MS = 1_500L

/** El renglón con el largo de una respuesta en varias tramas ("00A") y el número de cada trama ("1:"). */
private val MULTI_LENGTH = Regex("[0-9A-F]{3}")
private val MULTI_INDEX = Regex("^[0-9A-F]:")

/**
 * Los datos que siguen de [head] ("410C") en la respuesta. Lo normal: el renglón empieza con [head].
 * Solo si ninguno, el respaldo para clones que dejan el encabezado aunque se pidió ATH0: CAN de
 * 11 bits ("7E8" + largo), de 29 bits ("18DAF110" + largo) o los viejos (3 bytes + suma al final).
 */
internal fun afterHead(head: String, lines: List<String>): String? {
    lines.firstOrNull { it.startsWith(head) }?.let { return it.substring(head.length) }
    for (line in lines) {
        for (size in CAN_HEADERS) {
            if (line.length < size + 2 + head.length) continue
            val length = line.substring(size, size + 2).toIntOrNull(16) ?: continue
            val data = line.substring(size + 2)
            if (length * 2 >= head.length && length * 2 <= data.length && data.startsWith(head)) {
                return data.substring(head.length, length * 2)
            }
        }
        if (line.length >= 6 + head.length + 2 && line.substring(6).startsWith(head)) {
            return line.substring(6 + head.length, line.length - 2)
        }
    }
    return null
}

/** Largo (en hex) del encabezado CAN que algunos clones no quitan: 11 y 29 bits. */
private val CAN_HEADERS = intArrayOf(3, 8)

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
