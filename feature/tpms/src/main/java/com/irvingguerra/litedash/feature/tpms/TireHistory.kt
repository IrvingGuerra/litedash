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
 *
 * Pantallas sin pila de reloj: arrancan en una fecha vieja (1970, 2015, la de su sistema) y hasta que la red la
 * corrige la hora va DETRÁS de lo guardado. Nada se borra por eso: lo nuevo se sigue anotando después de la
 * última muestra ([shift], el tiempo que corre en esta sesión), con un hueco de [SESSION_GAP_S] para que la fuga rápida
 * nunca compare dos sesiones distintas, y todo lo que entra y sale se traduce con él, así la
 * gráfica, las alertas y la fuga lenta siguen viendo una línea de tiempo continua. Cuando la hora alcanza lo
 * guardado, se vuelve a la hora tal cual.
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

        /** La primera muestra en o después de [sec] (busca desde la más nueva: pedir la cola es barato). */
        fun firstFrom(sec: Long): Int {
            var first = size
            while (first > 0 && times[at(first - 1)] >= sec) first--
            return first
        }
    }

    private val rings = mutableMapOf<Wheel, Ring>()

    /** Segundos que se le suman a la hora de ahora mientras va detrás de lo guardado (0 = la hora tal cual). */
    private var shift = 0

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
     * Si el reloj regresó más de 30 s (pantallas sin pila de reloj arrancan en una fecha vieja), no se borra nada:
     * se sigue después de lo guardado ([shift]).
     */
    fun add(w: Wheel, atMillis: Long, kpa: Float, tempC: Float? = null): Boolean {
        val raw = (atMillis / 1000).toInt()
        val newest = rings.values.maxOfOrNull { it.lastTime() ?: Int.MIN_VALUE }?.takeIf { it != Int.MIN_VALUE }
        // La hora ya alcanzó lo guardado (la red la corrigió): de nuevo tal cual.
        if (shift != 0 && (newest == null || raw >= newest)) shift = 0
        // La hora va detrás de lo guardado: es otra sesión y se sigue después de la última muestra, separada por más
        // que la ventana de la fuga rápida (si no, las llantas calientes del viaje de ayer contra las frías de hoy
        // parecerían fuga al arrancar).
        if (newest != null && raw + shift < newest - INTERVAL_S) shift = newest - raw + SESSION_GAP_S
        val sec = raw + shift
        val ring = rings.getOrPut(w) { Ring() }
        val last = ring.lastTime()
        if (last != null && sec - last < INTERVAL_S) return false
        ring.add(sec, (kpa * 10).toInt().coerceIn(0, Short.MAX_VALUE.toInt()).toShort(), tempByte(tempC))
        ring.prune(sec - KEEP_S)
        changes++
        return true
    }

    /** Intercambia los historiales de [a] y [b] (sus sensores cambiaron de llanta: cada historial va con su sensor). */
    fun swap(a: Wheel, b: Wheel) {
        if (a == b) return
        val ra = rings.remove(a)
        val rb = rings.remove(b)
        if (rb != null) rings[a] = rb
        if (ra != null) rings[b] = ra
        if (ra != null || rb != null) changes++
    }

    /** Vacía el historial de [w] (entró otro sensor: lo de antes era de uno que ya no está ahí). */
    fun cut(w: Wheel) {
        if (rings.remove(w) != null) changes++
    }

    /** Muestras de [w] entre [sinceMillis] y [untilMillis], la más vieja primero (en la hora de ahora, ver [shift]). */
    fun samples(w: Wheel, sinceMillis: Long = 0, untilMillis: Long = Long.MAX_VALUE): List<TireSample> {
        val ring = rings[w] ?: return emptyList()
        val shiftMs = shift * 1000L
        val until = if (untilMillis == Long.MAX_VALUE) untilMillis else untilMillis + shiftMs
        val out = ArrayList<TireSample>()
        for (k in ring.firstFrom(Math.floorDiv(sinceMillis + shiftMs + 999, 1000L)) until ring.size) {
            val i = ring.at(k)
            val at = ring.times[i] * 1000L
            if (at > until) break
            out += TireSample(at - shiftMs, ring.values[i] / 10f, ring.temps[i].takeIf { it != NO_TEMP }?.toFloat())
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
    fun slowLeak(w: Wheel, nowMillis: Long): Boolean {
        val ring = rings[w] ?: return false
        val now = nowMillis + shift * 1000L
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
        /** Hueco entre la última muestra y una sesión nueva con el reloj atrasado: más que la ventana de la fuga rápida. */
        internal const val SESSION_GAP_S = (FAST_LEAK_WINDOW_MS / 1000).toInt() + INTERVAL_S
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
