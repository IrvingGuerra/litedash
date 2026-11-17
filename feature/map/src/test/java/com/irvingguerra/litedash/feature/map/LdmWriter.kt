package com.irvingguerra.litedash.feature.map

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Escritor mínimo de LDM0 para las pruebas: sigue exactamente `prototipos/mapas/armar_mapa.py` (cabecera de 64 B,
 * celdas de 0.01° ordenadas, vértices ordenados por celda, medias aristas con permisos, aristas, forma con varints
 * zigzag desde el vértice de origen, nombres y vueltas ordenadas por vía).
 */
internal object LdmWriter {
    /** Tramo de [a] a [b] (vértices originales); [oneway] 1 = solo a→b, -1 = solo b→a, 0 = los dos. */
    data class Edge(
        val a: Int, val b: Int, val inner: List<IntArray> = emptyList(), val cls: Int = 6, val maxKmh: Int = 0,
        val name: String = "", val oneway: Int = 0,
    )

    /** Vuelta en el vértice original [via]: [kind] 0 = prohibida, 1 = obligatoria. */
    data class Turn(val via: Int, val inEdge: Int, val outEdge: Int, val kind: Int)

    fun cellOf(lat: Int, lon: Int): Int =
        ((lat / 1e6 + 90) / 0.01).toInt() * 65536 + ((lon / 1e6 + 180) / 0.01).toInt()

    /** Igual que dist_dm de armar_mapa.py, en decímetros. */
    private fun distDm(aLat: Int, aLon: Int, bLat: Int, bLon: Int): Double {
        val lat = Math.toRadians((aLat + bLat) / 2e6)
        val dy = (bLat - aLat) * 0.111195
        val dx = (bLon - aLon) * 0.111195 * cos(lat)
        return sqrt(dx * dx + dy * dy) * 10
    }

    fun lengthDm(e: Edge, vertices: List<IntArray>): Long {
        val pts = listOf(vertices[e.a]) + e.inner + listOf(vertices[e.b])
        var sum = 0.0
        for (k in 0 until pts.size - 1) sum += distDm(pts[k][0], pts[k][1], pts[k + 1][0], pts[k + 1][1])
        return Math.round(sum)
    }

    /** Escribe el archivo; regresa el número nuevo de cada vértice original (ya ordenados por celda). */
    fun write(file: File, vertices: List<IntArray>, edges: List<Edge>, turns: List<Turn>): IntArray {
        val order = vertices.indices.sortedWith(
            compareBy<Int>({ cellOf(vertices[it][0], vertices[it][1]) }, { vertices[it][0] }, { vertices[it][1] }),
        )
        val index = IntArray(vertices.size)
        order.forEachIndexed { i, v -> index[v] = i }

        val names = ArrayList<String>()
        val nameId = HashMap<String, Int>()
        val shape = ByteArrayOutputStream()
        val edgeRows = ArrayList<LongArray>() // largo, clase, máxima, nombre, forma
        for (e in edges) {
            val nm = nameId.getOrPut(e.name) { names += e.name; names.size - 1 }
            val off = shape.size()
            varint(shape, e.inner.size.toLong())
            var pla = vertices[e.a][0]
            var plo = vertices[e.a][1]
            for (p in e.inner) {
                varint(shape, zig(p[0] - pla))
                varint(shape, zig(p[1] - plo))
                pla = p[0]
                plo = p[1]
            }
            edgeRows += longArrayOf(lengthDm(e, vertices), e.cls.toLong(), e.maxKmh.toLong(), nm.toLong(), off.toLong())
        }

        // Medias aristas por vértice nuevo (target, arista con bit 31, permisos).
        val out = Array(vertices.size) { ArrayList<IntArray>() }
        edges.forEachIndexed { ei, e ->
            val fwd = e.oneway >= 0
            val back = e.oneway <= 0
            out[index[e.a]] += intArrayOf(index[e.b], ei, (if (fwd) 1 else 0) or (if (back) 2 else 0))
            out[index[e.b]] += intArrayOf(index[e.a], ei or Int.MIN_VALUE, (if (back) 1 else 0) or (if (fwd) 2 else 0))
        }

        val mapped = turns.map { intArrayOf(index[it.via], it.inEdge, it.outEdge, it.kind) }
            .sortedWith(compareBy<IntArray>({ it[0] }, { it[1] }, { it[2] }, { it[3] }))

        val body = Le()
        val sec = IntArray(7)
        // celdas
        sec[0] = 64 + body.size()
        var last = Int.MIN_VALUE
        for (i in order.indices) {
            val v = order[i]
            val c = cellOf(vertices[v][0], vertices[v][1])
            if (c != last) {
                body.u32(c); body.u32(i); last = c
            }
        }
        val cellCount = (64 + body.size() - sec[0]) / 8
        // vértices
        sec[1] = 64 + body.size()
        var first = 0
        for (i in order.indices) {
            val v = order[i]
            body.u32(vertices[v][0]); body.u32(vertices[v][1]); body.u32(first)
            first += out[i].size
        }
        // medias
        sec[2] = 64 + body.size()
        for (lst in out) for (h in lst) {
            body.u32(h[0]); body.u32(h[1]); body.u8(h[2])
        }
        // aristas
        sec[3] = 64 + body.size()
        for (r in edgeRows) {
            body.u32(r[0].toInt()); body.u8(r[1].toInt()); body.u8(r[2].toInt()); body.u32(r[3].toInt()); body.u32(r[4].toInt())
        }
        // forma
        sec[4] = 64 + body.size()
        body.bytes(shape.toByteArray())
        // nombres
        sec[5] = 64 + body.size()
        val blob = ByteArrayOutputStream()
        val offs = names.map { s -> blob.size().also { blob.write(s.toByteArray(Charsets.UTF_8)) } }
        body.u32(names.size)
        offs.forEach { body.u32(it) }
        body.bytes(blob.toByteArray())
        // vueltas
        sec[6] = 64 + body.size()
        for (t in mapped) {
            body.u32(t[0]); body.u32(t[1]); body.u32(t[2]); body.u8(t[3])
        }

        val head = Le()
        head.bytes("LDM0".toByteArray(Charsets.US_ASCII))
        head.u16(0); head.u16(0)
        for (c in intArrayOf(cellCount, vertices.size, out.sumOf { it.size }, edges.size, names.size, mapped.size)) head.u32(c)
        for (o in sec) head.u32(o)
        while (head.size() < 64) head.u8(0)
        file.writeBytes(head.toByteArray() + body.toByteArray())
        return index
    }

    private fun zig(v: Int): Long = (((v shl 1) xor (v shr 31)).toLong()) and 0xFFFFFFFFL

    private fun varint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v >= 0x80) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    /** Bytes en little-endian. */
    private class Le : ByteArrayOutputStream() {
        fun u8(v: Int) = write(v and 0xFF)
        fun u16(v: Int) {
            u8(v); u8(v shr 8)
        }

        fun u32(v: Int) {
            u8(v); u8(v shr 8); u8(v shr 16); u8(v shr 24)
        }

        fun bytes(b: ByteArray) = write(b, 0, b.size)
    }
}
