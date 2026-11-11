package com.irvingguerra.litedash.feature.tpms

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Presión de cada llanta en el tiempo: una muestra cada 30 s, hasta 7 días (20 160 por
 * llanta). Compacto: segundos desde 1970 (Int), décimas de kPa (Short) y °C (Byte) en anillos.
 * Guarda la presión SIN calibrar (el ajuste se aplica al leer: cambiarlo no inventa fugas) y la
 * temperatura, para que el calor de rodar no se confunda con una fuga al enfriarse.
 * No es seguro entre hilos: el repositorio lo usa en el hilo principal y guarda una [snapshot].
 */
class TireHistory {
    private class Ring {
        val times = IntArray(CAPACITY)
        val values = ShortArray(CAPACITY)
        val temps = ByteArray(CAPACITY)
        var start = 0
        var size = 0

        fun add(sec: Int, tenths: Short, temp: Byte) {
            val i = (start + size) % CAPACITY
            times[i] = sec; values[i] = tenths; temps[i] = temp
            if (size < CAPACITY) size++ else start = (start + 1) % CAPACITY
        }

        fun lastTime() = if (size == 0) null else times[(start + size - 1) % CAPACITY]

        fun copy() = Ring().also { r ->
            times.copyInto(r.times); values.copyInto(r.values); temps.copyInto(r.temps)
            r.start = start; r.size = size
        }

        inline fun forEach(block: (Int, Short, Byte) -> Unit) {
            for (k in 0 until size) {
                val i = (start + k) % CAPACITY
                block(times[i], values[i], temps[i])
            }
        }

        /** Tira lo más viejo que [minSec]. */
        fun prune(minSec: Int) {
            while (size > 0 && times[start] < minSec) { start = (start + 1) % CAPACITY; size-- }
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

    /** Guarda [kpa] (y [tempC], si se sabe) de [w] si ya pasaron 30 s desde su última muestra; true si la guardó. */
    fun add(w: Wheel, atMillis: Long, kpa: Float, tempC: Float? = null): Boolean {
        val sec = (atMillis / 1000).toInt()
        val ring = rings.getOrPut(w) { Ring() }
        val last = ring.lastTime()
        if (last != null && sec - last < INTERVAL_S) return false
        ring.add(sec, (kpa * 10).toInt().coerceIn(0, Short.MAX_VALUE.toInt()).toShort(), tempByte(tempC))
        ring.prune(sec - KEEP_S)
        changes++
        return true
    }

    /** Muestras de [w] desde [sinceMillis], la más vieja primero (busca desde la más nueva: pedir la cola es barato). */
    fun samples(w: Wheel, sinceMillis: Long = 0): List<TireSample> {
        val ring = rings[w] ?: return emptyList()
        var first = ring.size
        while (first > 0 && ring.times[(ring.start + first - 1) % CAPACITY] * 1000L >= sinceMillis) first--
        val out = ArrayList<TireSample>(ring.size - first)
        for (k in first until ring.size) {
            val i = (ring.start + k) % CAPACITY
            out += TireSample(ring.times[i] * 1000L, ring.values[i] / 10f, ring.temps[i].takeIf { it != NO_TEMP }?.toFloat())
        }
        return out
    }

    /** Una copia para guardarla en otro hilo; sigue [dirty] hasta que la escritura avise con [saved]. */
    fun snapshot(): TireHistory {
        val copy = TireHistory()
        for ((w, ring) in rings) copy.rings[w] = ring.copy()
        return copy
    }

    /** Formato: "TPH2", y por llanta: clave, cuántas, y (Int segundos, Short décimas, Byte °C) cada una. */
    fun encode(): ByteArray {
        val bytes = ByteArrayOutputStream()
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
        private const val MAGIC = 0x54504832 // "TPH2"
        /** El de antes, sin temperatura: se sigue leyendo. */
        private const val MAGIC_V1 = 0x54504831 // "TPH1"
        /** Muestra sin temperatura. */
        private const val NO_TEMP = Byte.MIN_VALUE

        private fun tempByte(tempC: Float?): Byte =
            tempC?.takeIf { it.isFinite() }?.let { Math.round(it).coerceIn(-127, 127).toByte() } ?: NO_TEMP

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
                        val ring = Ring()
                        repeat(n) { ring.add(input.readInt(), input.readShort(), if (v2) input.readByte() else NO_TEMP) }
                        if (w != null) h.rings[w] = ring
                    }
                }
            }.onFailure { h.rings.clear() }
            return h
        }
    }
}
