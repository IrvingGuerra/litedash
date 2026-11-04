package com.irvingguerra.litedash.feature.tpms

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Presión de cada llanta en el tiempo: una muestra cada 30 s, hasta 7 días (20 160 por
 * llanta). Compacto: segundos desde 1970 (Int) y décimas de kPa (Short) en anillos.
 * No es seguro entre hilos: el repositorio lo usa solo en el hilo principal.
 */
class TireHistory {
    private class Ring {
        val times = IntArray(CAPACITY)
        val values = ShortArray(CAPACITY)
        var start = 0
        var size = 0

        fun add(sec: Int, tenths: Short) {
            val i = (start + size) % CAPACITY
            times[i] = sec; values[i] = tenths
            if (size < CAPACITY) size++ else start = (start + 1) % CAPACITY
        }

        fun lastTime() = if (size == 0) null else times[(start + size - 1) % CAPACITY]

        inline fun forEach(block: (Int, Short) -> Unit) {
            for (k in 0 until size) {
                val i = (start + k) % CAPACITY
                block(times[i], values[i])
            }
        }

        /** Tira lo más viejo que [minSec]. */
        fun prune(minSec: Int) {
            while (size > 0 && times[start] < minSec) { start = (start + 1) % CAPACITY; size-- }
        }
    }

    private val rings = mutableMapOf<Wheel, Ring>()

    /** Cambió algo desde la última vez que se guardó. */
    var dirty = false
        private set

    /** Guarda [kpa] de [w] si ya pasaron 30 s desde su última muestra; true si la guardó. */
    fun add(w: Wheel, atMillis: Long, kpa: Float): Boolean {
        val sec = (atMillis / 1000).toInt()
        val ring = rings.getOrPut(w) { Ring() }
        val last = ring.lastTime()
        if (last != null && sec - last < INTERVAL_S) return false
        ring.add(sec, (kpa * 10).toInt().coerceIn(0, Short.MAX_VALUE.toInt()).toShort())
        ring.prune(sec - KEEP_S)
        dirty = true
        return true
    }

    /** Muestras de [w] desde [sinceMillis], la más vieja primero. */
    fun samples(w: Wheel, sinceMillis: Long = 0): List<TireSample> {
        val ring = rings[w] ?: return emptyList()
        val out = ArrayList<TireSample>(ring.size)
        ring.forEach { t, v -> val ms = t * 1000L; if (ms >= sinceMillis) out += TireSample(ms, v / 10f) }
        return out
    }

    fun all(sinceMillis: Long = 0): Map<Wheel, List<TireSample>> = rings.keys.associateWith { samples(it, sinceMillis) }

    /** Formato: "TPH1", y por llanta: clave, cuántas, y (Int segundos, Short décimas) cada una. */
    fun encode(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeByte(rings.size)
            for ((w, ring) in rings) {
                out.writeUTF(w.key)
                out.writeInt(ring.size)
                ring.forEach { t, v -> out.writeInt(t); out.writeShort(v.toInt()) }
            }
        }
        dirty = false
        return bytes.toByteArray()
    }

    companion object {
        const val INTERVAL_S = 30
        const val KEEP_S = 7 * 24 * 3600
        const val CAPACITY = KEEP_S / INTERVAL_S
        private const val MAGIC = 0x54504831 // "TPH1"

        /** Lee lo que dejó [encode]; si está dañado, empieza vacío. */
        fun decode(bytes: ByteArray?): TireHistory {
            val h = TireHistory()
            if (bytes == null) return h
            runCatching {
                DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                    if (input.readInt() != MAGIC) return h
                    repeat(input.readUnsignedByte()) {
                        val w = Wheel.fromKey(input.readUTF())
                        val n = input.readInt()
                        val ring = Ring()
                        repeat(n) { ring.add(input.readInt(), input.readShort()) }
                        if (w != null) h.rings[w] = ring
                    }
                }
            }.onFailure { h.rings.clear() }
            return h
        }
    }
}
