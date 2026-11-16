package com.irvingguerra.litedash.feature.tpms

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Presión de cada llanta en el tiempo: una muestra cada 30 s, hasta 7 días (20 160 por
 * llanta). Compacto: segundos desde 1970 (Int), décimas de kPa (Short) y °C (Byte) en anillos
 * que crecen según se llenan (una llanta con pocas muestras no aparta los 7 días).
 * Guarda la presión SIN calibrar (el ajuste se aplica al leer: cambiarlo no inventa fugas) y la
 * temperatura, para que el calor de rodar no se confunda con una fuga al enfriarse.
 * No es seguro entre hilos: el repositorio lo usa en el hilo principal y guarda una [snapshot].
 */
class TireHistory {
    private class Ring(initial: Int = FIRST_SIZE) {
        var times = IntArray(initial)
        var values = ShortArray(initial)
        var temps = ByteArray(initial)
        var start = 0
        var size = 0

        /** Índice en los arreglos de la muestra [k] (0 = la más vieja). */
        fun at(k: Int) = (start + k) % times.size

        fun add(sec: Int, tenths: Short, temp: Byte) {
            if (size == times.size && size < CAPACITY) grow()
            val i = at(size)
            times[i] = sec; values[i] = tenths; temps[i] = temp
            if (size < times.size) size++ else start = (start + 1) % times.size
        }

        /** Dobla el espacio (hasta [CAPACITY]) y deja la más vieja en 0. */
        private fun grow() {
            val n = minOf(CAPACITY, times.size * 2)
            val t = IntArray(n); val v = ShortArray(n); val c = ByteArray(n)
            for (k in 0 until size) { val i = at(k); t[k] = times[i]; v[k] = values[i]; c[k] = temps[i] }
            times = t; values = v; temps = c; start = 0
        }

        fun lastTime() = if (size == 0) null else times[at(size - 1)]

        /** Del tamaño justo: es la copia que se guarda. */
        fun copy() = Ring(maxOf(size, 1)).also { r -> forEach { t, v, c -> r.add(t, v, c) } }

        inline fun forEach(block: (Int, Short, Byte) -> Unit) {
            for (k in 0 until size) {
                val i = at(k)
                block(times[i], values[i], temps[i])
            }
        }

        /** Tira lo más viejo que [minSec]. */
        fun prune(minSec: Int) {
            while (size > 0 && times[start] < minSec) { start = (start + 1) % times.size; size-- }
        }

        /** Tira lo más nuevo que [maxSec] (el reloj regresó en el tiempo). */
        fun dropAfter(maxSec: Int) {
            while (size > 0 && times[at(size - 1)] > maxSec) size--
        }

        /** La primera muestra en o después de [sec] (busca desde la más nueva: pedir la cola es barato). */
        fun firstFrom(sec: Long): Int {
            var first = size
            while (first > 0 && times[at(first - 1)] >= sec) first--
            return first
        }
    }

    private val rings = mutableMapOf<Wheel, Ring>()

    /** Sube con cada muestra nueva: [saved] sabe si entró algo mientras se escribía. */
    var changes = 0L
        private set
    private var savedChanges = 0L

    /** Hay muestras que todavía no llegan al disco. */
    val dirty get() = changes != savedChanges

    /** Ya se escribió lo que había hasta [changes] (llamar solo si la escritura salió bien). */
    fun saved(changes: Long) {
        if (changes > savedChanges) savedChanges = changes
    }

    /**
     * Guarda [kpa] (y [tempC], si se sabe) de [w] si ya pasaron 30 s desde su última muestra; true si la guardó.
     * Si el reloj regresó más de 30 s (pantallas sin pila de reloj arrancan en una fecha vieja), lo
     * posterior a ahora se tira: si no, no se guardaría nada hasta que el reloj alcanzara la fecha de antes.
     */
    fun add(w: Wheel, atMillis: Long, kpa: Float, tempC: Float? = null): Boolean {
        val sec = (atMillis / 1000).toInt()
        val ring = rings.getOrPut(w) { Ring() }
        val last = ring.lastTime()
        if (last != null && last - sec > INTERVAL_S) ring.dropAfter(sec)
        else if (last != null && sec - last < INTERVAL_S) return false
        ring.add(sec, (kpa * 10).toInt().coerceIn(0, Short.MAX_VALUE.toInt()).toShort(), tempByte(tempC))
        ring.prune(sec - KEEP_S)
        changes++
        return true
    }

    /** Muestras de [w] entre [sinceMillis] y [untilMillis], la más vieja primero. */
    fun samples(w: Wheel, sinceMillis: Long = 0, untilMillis: Long = Long.MAX_VALUE): List<TireSample> {
        val ring = rings[w] ?: return emptyList()
        val out = ArrayList<TireSample>()
        for (k in ring.firstFrom(Math.floorDiv(sinceMillis + 999, 1000L)) until ring.size) {
            val i = ring.at(k)
            val at = ring.times[i] * 1000L
            if (at > untilMillis) break
            out += TireSample(at, ring.values[i] / 10f, ring.temps[i].takeIf { it != NO_TEMP }?.toFloat())
        }
        return out
    }

    /** Las muestras de [w] que miran las alertas: los últimos 5 min hasta [now] (nunca "del futuro"). */
    fun recent(w: Wheel, now: Long): List<TireSample> = samples(w, now - FAST_LEAK_WINDOW_MS, now)

    /**
     * Fuga lenta de [w], simple a propósito: con al menos 24 h de historial dentro de los últimos 3
     * días, promedio de las primeras 6 h de esa ventana menos promedio de la última hora ≥ 20 kPa,
     * todo llevado a 20 °C. Sin eso, unas muestras de rodar en caliente contra las de hoy en frío
     * (el auto se enfrió estacionado) parecían fuga. Si hay muestras con temperatura solo se usan
     * esas (las de antes de guardarla no se mezclan). Recorre el anillo sin crear objetos (corre con
     * cada muestra nueva).
     */
    fun slowLeak(w: Wheel, now: Long): Boolean {
        val ring = rings[w] ?: return false
        val from = ring.firstFrom(Math.floorDiv(now - SLOW_LEAK_WINDOW_MS + 999, 1000L))
        // Primera pasada: ¿hay temperatura?, y dónde empieza y acaba la ventana.
        var withTemp = false
        for (k in from until ring.size) {
            val i = ring.at(k)
            if (ring.times[i] * 1000L > now) break
            if (plausibleTemp(ring.temps[i])) { withTemp = true; break }
        }
        var first = Long.MIN_VALUE
        var last = Long.MIN_VALUE
        var count = 0
        for (k in from until ring.size) {
            val i = ring.at(k)
            val t = ring.times[i] * 1000L
            if (t > now) break
            if (withTemp && !plausibleTemp(ring.temps[i])) continue
            if (count++ == 0) first = t
            last = t
        }
        if (count < 2 || last - first < SLOW_LEAK_MIN_SPAN_MS) return false
        // Segunda pasada: promedios en frío del principio y de la última hora.
        var oldSum = 0.0; var oldN = 0
        var newSum = 0.0; var newN = 0
        for (k in from until ring.size) {
            val i = ring.at(k)
            val t = ring.times[i] * 1000L
            if (t > now) break
            if (withTemp && !plausibleTemp(ring.temps[i])) continue
            val temp = ring.temps[i].takeIf { it != NO_TEMP }?.toFloat()
            val cold = coldKpa(ring.values[i] / 10f, temp)
            if (t - first <= SLOW_LEAK_EDGE_MS) { oldSum += cold; oldN++ }
            if (now - t <= SLOW_LEAK_RECENT_MS) { newSum += cold; newN++ }
        }
        if (newN == 0) return false
        return oldSum / oldN - newSum / newN >= SLOW_LEAK_KPA
    }

    /** Una copia (del tamaño justo) para guardarla en otro hilo; sigue [dirty] hasta que la escritura avise con [saved]. */
    fun snapshot(): TireHistory {
        val copy = TireHistory()
        for ((w, ring) in rings) copy.rings[w] = ring.copy()
        return copy
    }

    /** Formato: "TPH2", y por llanta: clave, cuántas, y (Int segundos, Short décimas, Byte °C) cada una. */
    fun encode(): ByteArray {
        // Del tamaño exacto: sin ir doblando el búfer.
        val size = 4 + 1 + rings.entries.sumOf { (w, r) -> 2 + w.key.length + 4 + SAMPLE_BYTES * r.size }
        val bytes = ByteArrayOutputStream(size)
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeByte(rings.size)
            for ((w, ring) in rings) {
                out.writeUTF(w.key)
                out.writeInt(ring.size)
                ring.forEach { t, v, c -> out.writeInt(t); out.writeShort(v.toInt()); out.writeByte(c.toInt()) }
            }
        }
        return bytes.toByteArray()
    }

    companion object {
        const val INTERVAL_S = 30
        const val KEEP_S = 7 * 24 * 3600
        const val CAPACITY = KEEP_S / INTERVAL_S
        /** Una hora de muestras al empezar; se dobla al llenarse. */
        private const val FIRST_SIZE = 120
        private const val SAMPLE_BYTES = 7
        private const val MAGIC = 0x54504832 // "TPH2"
        /** El de antes de la 1.4.131, sin temperatura: se sigue leyendo (ver estado de la auditoría). */
        private const val MAGIC_V1 = 0x54504831 // "TPH1"
        /** Muestra sin temperatura. */
        private const val NO_TEMP = Byte.MIN_VALUE

        private fun tempByte(tempC: Float?): Byte =
            tempC?.takeIf { it.isFinite() }?.let { Math.round(it).coerceIn(-127, 127).toByte() } ?: NO_TEMP

        private fun plausibleTemp(b: Byte) = b != NO_TEMP && plausible(b.toFloat()) != null

        /** Lee lo que dejó [encode]; si está dañado, empieza vacío. */
        fun decode(bytes: ByteArray?): TireHistory {
            val h = TireHistory()
            if (bytes == null) return h
            runCatching {
                DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                    val v2 = when (input.readInt()) { MAGIC -> true; MAGIC_V1 -> false; else -> return h }
                    repeat(input.readUnsignedByte()) {
                        val w = Wheel.fromKey(input.readUTF())
                        val n = input.readInt()
                        // Un conteo imposible = archivo dañado.
                        if (n !in 0..CAPACITY) error("n=$n")
                        val ring = Ring(maxOf(n, 1))
                        repeat(n) { ring.add(input.readInt(), input.readShort(), if (v2) input.readByte() else NO_TEMP) }
                        if (w != null) h.rings[w] = ring
                    }
                }
            }.onFailure { h.rings.clear() }
            return h
        }
    }
}
