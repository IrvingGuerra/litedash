package com.irvingguerra.litedash.feature.map

import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

/**
 * El mapa de un estado en un solo archivo PMTiles v3 (github.com/protomaps/PMTiles, el formato abierto que arma
 * Planetiler): las teselas se leen directo del disco, una por una, sin cargar el archivo a memoria. Solo lo que usa
 * LiteDash: teselas MVT, directorios con gzip (o sin comprimir) y las teselas tal como vienen (con gzip, igual que
 * las que se bajan de OpenFreeMap: el almacén ya sabe abrirlas).
 *
 * No es seguro entre hilos: quien lo use lo llama desde un solo hilo (o con su propio candado).
 */
class PmTiles private constructor(private val file: RandomAccessFile, private val header: Header) : AutoCloseable {

    /** Los zooms que trae y su caja (grados). */
    val minZoom get() = header.minZoom
    val maxZoom get() = header.maxZoom
    val bounds get() = header.bounds

    /** Los últimos directorios leídos (el raíz y unas hojas): buscar en la misma zona no vuelve a leer el disco. */
    private val directories = object : LinkedHashMap<Long, List<Entry>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<Entry>>) = size > CACHED_DIRECTORIES
    }

    /** La tesela z/x/y tal como está guardada (con su compresión), o null si el archivo no la trae. */
    fun tile(z: Int, x: Int, y: Int): ByteArray? {
        if (z < header.minZoom || z > header.maxZoom) return null
        val id = tileId(z, x, y)
        var offset = header.rootOffset
        var length = header.rootLength
        repeat(MAX_DEPTH) {
            val entries = directory(offset, length)
            val e = findEntry(entries, id) ?: return null
            if (e.runLength > 0) {
                if (id >= e.tileId + e.runLength) return null
                return read(header.tileDataOffset + e.offset, e.length)
            }
            // Un puntero a un directorio hoja (de la sección de hojas).
            offset = header.leafOffset + e.offset
            length = e.length.toLong()
        }
        return null
    }

    override fun close() = file.close()

    private fun directory(offset: Long, length: Long): List<Entry> =
        directories.getOrPut(offset) { parseDirectory(decompress(read(offset, length.toInt()), header.internalCompression)) }

    private fun read(offset: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        file.seek(offset)
        file.readFully(out)
        return out
    }

    internal class Header(
        val rootOffset: Long, val rootLength: Long, val leafOffset: Long, val tileDataOffset: Long,
        val internalCompression: Int, val tileCompression: Int, val tileType: Int, val minZoom: Int, val maxZoom: Int,
        val bounds: DoubleArray,
    )

    internal class Entry(val tileId: Long, val offset: Long, val length: Int, val runLength: Int)

    companion object {
        /** Abre [file] si es un PMTiles v3 de teselas MVT; null si no lo es o está dañado. */
        fun open(file: File): PmTiles? = runCatching {
            val raf = RandomAccessFile(file, "r")
            val bytes = ByteArray(HEADER_SIZE)
            raf.readFully(bytes)
            val h = parseHeader(bytes)
            if (h == null || h.tileType != TILE_TYPE_MVT) {
                raf.close()
                null
            } else PmTiles(raf, h)
        }.getOrNull()

        internal fun parseHeader(b: ByteArray): Header? {
            if (b.size < HEADER_SIZE || String(b, 0, 7, Charsets.US_ASCII) != "PMTiles" || b[7].toInt() != 3) return null
            val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            return Header(
                rootOffset = buf.getLong(8), rootLength = buf.getLong(16), leafOffset = buf.getLong(40),
                tileDataOffset = buf.getLong(56), internalCompression = b[97].toInt(), tileCompression = b[98].toInt(),
                tileType = b[99].toInt(), minZoom = b[100].toInt(), maxZoom = b[101].toInt(),
                bounds = doubleArrayOf(buf.getInt(102) / 1e7, buf.getInt(106) / 1e7, buf.getInt(110) / 1e7, buf.getInt(114) / 1e7),
            )
        }

        /** Un directorio ya sin comprimir: n, luego ids (en diferencias), corridas, largos y desplazamientos. */
        internal fun parseDirectory(b: ByteArray): List<Entry> {
            val r = Varints(b)
            val n = r.next().toInt()
            val ids = LongArray(n)
            var last = 0L
            for (i in 0 until n) { last += r.next(); ids[i] = last }
            val runs = IntArray(n) { r.next().toInt() }
            val lengths = IntArray(n) { r.next().toInt() }
            val offsets = LongArray(n)
            for (i in 0 until n) {
                val v = r.next()
                // 0 = justo después de la anterior.
                offsets[i] = if (v == 0L && i > 0) offsets[i - 1] + lengths[i - 1] else v - 1
            }
            return List(n) { Entry(ids[it], offsets[it], lengths[it], runs[it]) }
        }

        /** La entrada con el id más grande que no pasa de [id] (búsqueda binaria). */
        internal fun findEntry(entries: List<Entry>, id: Long): Entry? {
            var lo = 0
            var hi = entries.size - 1
            var found: Entry? = null
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val e = entries[mid]
                if (e.tileId <= id) { found = e; lo = mid + 1 } else hi = mid - 1
            }
            return found
        }

        /** El id de z/x/y en PMTiles: las teselas de los zooms de antes y luego la curva de Hilbert del zoom. */
        internal fun tileId(z: Int, x: Int, y: Int): Long {
            var acc = 0L
            for (i in 0 until z) acc += 1L shl (2 * i)
            val n = 1L shl z
            var rx: Long
            var ry: Long
            var d = 0L
            var tx = x.toLong()
            var ty = y.toLong()
            var s = n / 2
            while (s > 0) {
                rx = if (tx and s > 0) 1 else 0
                ry = if (ty and s > 0) 1 else 0
                d += s * s * ((3 * rx) xor ry)
                // Girar el cuadrante.
                if (ry == 0L) {
                    if (rx == 1L) { tx = s - 1 - tx; ty = s - 1 - ty }
                    val t = tx; tx = ty; ty = t
                }
                s /= 2
            }
            return acc + d
        }

        private fun decompress(b: ByteArray, compression: Int): ByteArray = when (compression) {
            COMPRESSION_GZIP -> GZIPInputStream(ByteArrayInputStream(b)).use { it.readBytes() }
            else -> b
        }

        private const val HEADER_SIZE = 127
        private const val TILE_TYPE_MVT = 1
        private const val COMPRESSION_GZIP = 2
        private const val MAX_DEPTH = 4
        private const val CACHED_DIRECTORIES = 8
    }
}

/** Lee varints sin signo uno tras otro. */
private class Varints(private val b: ByteArray) {
    private var at = 0

    fun next(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val v = b[at++].toInt() and 0xFF
            result = result or ((v and 0x7F).toLong() shl shift)
            if (v and 0x80 == 0) return result
            shift += 7
        }
    }
}
