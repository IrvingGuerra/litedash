package com.irvingguerra.litedash.feature.map

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Las calles para manejar de un estado (formato LDM0 o LDM1 de `prototipos/mapas/armar_mapa.py`), leídas del disco por
 * mmap sin cargarlas a memoria: cruces (vértices), tramos (aristas) con su largo, clase, velocidad máxima, nombre y forma,
 * las dos mitades de cada tramo (con por dónde se puede ir) y las vueltas prohibidas u obligatorias. Un LDM1 con las
 * secciones de dibujo ([drawable]) trae además lo necesario para dibujar las calles sin las del mapa (ver [GraphTiles]).
 * Solo lee (seguro desde varios hilos: todo con posiciones absolutas).
 */
class RoadGraph private constructor(private val channel: FileChannel, private val buf: ByteBuffer) : Closeable {
    val cellCount = buf.getInt(8)
    val vertexCount = buf.getInt(12)
    val halfCount = buf.getInt(16)
    val edgeCount = buf.getInt(20)
    private val nameCount = buf.getInt(24)
    private val turnCount = buf.getInt(28)
    private val oCells = buf.getInt(32)
    private val oVertices = buf.getInt(36)
    private val oHalf = buf.getInt(40)
    private val oEdges = buf.getInt(44)
    private val oShape = buf.getInt(48)
    private val oNames = buf.getInt(52)
    private val oTurns = buf.getInt(56)
    /** Las secciones de dibujo (LDM1 con la bandera 1): tramos largos, vista lejana y las que solo se dibujan; 0 = no. */
    private val oLong = drawOffset(buf, 88)
    private val oFar = drawOffset(buf, 92)
    private val oExtra = drawOffset(buf, 96)
    /** Las calles menores de la vista lejana (zoom 12); 0 en los LDM1 sin ellas (el prototipo). */
    private val oMinor = drawOffset(buf, 100)

    /** Lo que pesa mapeado (bytes). */
    val bytes: Long get() = buf.capacity().toLong()

    /** ¿Trae con qué dibujar las calles (mapa del formato nuevo, sin calles en el `.pmtiles`)? */
    val drawable = oLong > 0 && oFar > 0 && oExtra > 0

    override fun close() = channel.close()

    /** Latitud y longitud del cruce [v] (millonésimas de grado). */
    fun latE6(v: Int) = buf.getInt(oVertices + VERTEX * v)
    fun lonE6(v: Int) = buf.getInt(oVertices + VERTEX * v + 4)

    /** Las mitades que salen del cruce [v]: de [firstHalf] (incluida) a [firstHalf] de v + 1. */
    fun firstHalf(v: Int) = if (v >= vertexCount) halfCount else buf.getInt(oVertices + VERTEX * v + 8)

    /** A dónde lleva la mitad [h]. */
    fun halfTarget(h: Int) = buf.getInt(oHalf + HALF * h)

    /** El tramo de la mitad [h] (sin el bit de "al revés"). */
    fun halfEdge(h: Int) = buf.getInt(oHalf + HALF * h + 4) and 0x7FFFFFFF

    /** ¿La mitad [h] recorre su tramo al revés de como se guardó su forma? */
    fun halfReversed(h: Int) = buf.getInt(oHalf + HALF * h + 4) < 0

    /** ¿Se puede salir del cruce por la mitad [h] (el sentido lo permite)? */
    fun canLeave(h: Int) = buf.get(oHalf + HALF * h + 8).toInt() and 1 != 0

    /** Largo del tramo [e] en metros. */
    fun lengthM(e: Int) = (buf.getInt(oEdges + EDGE * e).toLong() and 0xFFFFFFFFL) / 10.0

    /** Clase del tramo (índice de CLASSES de armar_mapa.py: 0 = autopista … 14 = servicio). */
    fun roadClass(e: Int) = buf.get(oEdges + EDGE * e + 4).toInt() and 0xFF

    /** Velocidad máxima del tramo en km/h (0 = no dice). */
    fun maxKmh(e: Int) = buf.get(oEdges + EDGE * e + 5).toInt() and 0xFF

    /** Nombre del tramo ("" = sin nombre). */
    fun name(e: Int): String = text(nameRef(e))

    /** El índice del nombre del tramo [e] en la tabla de nombres (para agrupar sin leer el texto). */
    internal fun nameRef(e: Int): Int = buf.getInt(oEdges + EDGE * e + 6)

    /** El texto [n] de la tabla de nombres ("" = ninguno). */
    internal fun text(n: Int): String {
        if (n < 0 || n >= nameCount) return ""
        val base = oNames + 4 + 4 * nameCount
        val a = buf.getInt(oNames + 4 + 4 * n)
        val b = if (n + 1 < nameCount) buf.getInt(oNames + 4 + 4 * (n + 1)) else oTurns - base
        if (a < 0 || b < a || base + b > oTurns) return ""
        val bytes = ByteArray(b - a)
        for (i in bytes.indices) bytes[i] = buf.get(base + a + i)
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * Los puntos del tramo [e] como los recorre la mitad que sale de [from] (con [reversed]): sin el cruce de salida y con
     * el de llegada [to]. En pares lat, lon (millonésimas) agregados a [out].
     */
    internal fun addShape(e: Int, from: Int, to: Int, reversed: Boolean, out: PointList) {
        // La forma se guardó desde el cruce de origen del tramo (el de salida si no va al revés).
        val origin = if (reversed) to else from
        var p = oShape + buf.getInt(oEdges + EDGE * e + 10)
        fun varint(): Long {
            var v = 0L
            var s = 0
            while (true) {
                val b = buf.get(p++).toInt() and 0xFF
                v = v or ((b and 0x7F).toLong() shl s)
                if (b < 0x80) return v
                s += 7
            }
        }
        val n = varint().toInt()
        var la = latE6(origin)
        var lo = lonE6(origin)
        val inner = IntArray(2 * n)
        for (i in 0 until n) {
            la += unzig(varint())
            lo += unzig(varint())
            inner[2 * i] = la
            inner[2 * i + 1] = lo
        }
        if (!reversed) {
            for (i in 0 until n) out.add(inner[2 * i], inner[2 * i + 1])
        } else {
            for (i in n - 1 downTo 0) out.add(inner[2 * i], inner[2 * i + 1])
        }
        out.add(latE6(to), lonE6(to))
    }

    /** ¿El cruce [v] tiene vueltas guardadas (prohibidas u obligatorias)? Ahí importa por dónde se llegó. */
    fun hasTurns(v: Int): Boolean = firstTurn(v) >= 0

    /** La primera vuelta guardada del cruce [via] (están ordenadas por cruce), o -1 si no tiene. */
    private fun firstTurn(via: Int): Int {
        if (turnCount == 0) return -1
        var lo = 0
        var hi = turnCount - 1
        var first = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = buf.getInt(oTurns + TURN * mid)
            if (v < via) lo = mid + 1 else {
                if (v == via) first = mid
                hi = mid - 1
            }
        }
        return first
    }

    /**
     * La vuelta de [inEdge] a [outEdge] en el cruce [via]: ¿se puede? (no si está prohibida o si de [inEdge] solo se
     * puede seguir a otro tramo). Las vueltas están ordenadas por cruce.
     */
    fun turnAllowed(via: Int, inEdge: Int, outEdge: Int): Boolean {
        val first = firstTurn(via)
        if (first < 0) return true
        var i = first
        var only = -1
        while (i < turnCount && buf.getInt(oTurns + TURN * i) == via) {
            val at = oTurns + TURN * i
            if (buf.getInt(at + 4) == inEdge) {
                val to = buf.getInt(at + 8)
                val kind = buf.get(at + 12).toInt()
                if (kind == 0 && to == outEdge) return false
                if (kind == 1) only = to
            }
            i++
        }
        return only < 0 || only == outEdge
    }

    /**
     * El tramo más cercano a ([lat], [lon]) donde se puede manejar, buscando en las celdas de alrededor (hasta
     * [maxMeters]); null si no hay. Revisa la forma de cada tramo, no solo sus cruces. Los tramos de [skip] no cuentan.
     */
    fun snap(lat: Double, lon: Double, maxMeters: Double = SNAP_MAX_M, skip: Set<Int>? = null): Snap? {
        val row = ((lat + 90) / CELL).toInt()
        val col = ((lon + 180) / CELL).toInt()
        val kx = cos(Math.toRadians(lat)) * M_PER_DEG
        var best: Snap? = null
        var bestD = maxMeters
        val pts = PointList()
        val seen = HashSet<Int>()
        // De adentro hacia afuera: el radio crece hasta encontrar algo cerca (los tramos largos cruzan celdas sin cruces).
        for (ring in 0..SNAP_RINGS) {
            for (r in row - ring..row + ring) for (c in col - ring..col + ring) {
                if (ring > 0 && r != row - ring && r != row + ring && c != col - ring && c != col + ring) continue
                val (from, to) = cellVertices(r * 65536 + c) ?: continue
                for (v in from until to) {
                    for (h in firstHalf(v) until firstHalf(v + 1)) {
                        val t = halfTarget(h)
                        // Cada tramo una vez (aunque su otro cruce quede lejos, fuera de las celdas revisadas).
                        val e = halfEdge(h)
                        if (!seen.add(e) || skip?.contains(e) == true) continue
                        pts.clear()
                        pts.add(latE6(v), lonE6(v))
                        addShape(e, v, t, halfReversed(h), pts)
                        var along = 0.0
                        for (i in 0 until pts.size - 1) {
                            val ay = pts.lat(i) / 1e6
                            val ax = pts.lon(i) / 1e6
                            val by = pts.lat(i + 1) / 1e6
                            val bx = pts.lon(i + 1) / 1e6
                            val dx = (bx - ax) * kx
                            val dy = (by - ay) * M_PER_DEG
                            val px = (lon - ax) * kx
                            val py = (lat - ay) * M_PER_DEG
                            val len2 = dx * dx + dy * dy
                            val f = if (len2 == 0.0) 0.0 else ((px * dx + py * dy) / len2).coerceIn(0.0, 1.0)
                            val ex = px - f * dx
                            val ey = py - f * dy
                            val d = sqrt(ex * ex + ey * ey)
                            val seg = sqrt(len2)
                            if (d < bestD) {
                                bestD = d
                                best = Snap(h, v, t, e, along + f * seg, ay + f * (by - ay), ax + f * (bx - ax), d)
                            }
                            along += seg
                        }
                    }
                }
            }
            // Ya hay uno más cerca que lo que queda por revisar en el siguiente anillo.
            if (best != null && bestD < ring * CELL * M_PER_DEG * 0.7) break
        }
        // El avance medido sobre la forma; el largo guardado manda (puede diferir un poco).
        return best?.let { s ->
            val total = lengthM(s.edge)
            var shapeLen = 0.0
            pts.clear()
            pts.add(latE6(s.from), lonE6(s.from))
            addShape(s.edge, s.from, s.to, halfReversed(s.half), pts)
            for (i in 0 until pts.size - 1) {
                val dy = (pts.lat(i + 1) - pts.lat(i)) / 1e6 * M_PER_DEG
                val dx = (pts.lon(i + 1) - pts.lon(i)) / 1e6 * cos(Math.toRadians(pts.lat(i) / 1e6)) * M_PER_DEG
                shapeLen += sqrt(dx * dx + dy * dy)
            }
            val along = if (shapeLen > 0) s.alongM / shapeLen * total else 0.0
            // Siempre desde el cruce de menor número: dos puntos del mismo tramo quedan medidos igual.
            if (s.from <= s.to) s.copy(alongM = along) else {
                val twin = (firstHalf(s.to) until firstHalf(s.to + 1)).firstOrNull { halfEdge(it) == s.edge && halfTarget(it) == s.from }
                if (twin == null) s.copy(alongM = along) else s.copy(half = twin, from = s.to, to = s.from, alongM = total - along)
            }
        }
    }

    /** El cruce de donde sale la mitad [h] (búsqueda binaria: las mitades van ordenadas por cruce). */
    internal fun halfOrigin(h: Int): Int {
        var lo = 0
        var hi = vertexCount - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (firstHalf(mid) <= h) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** ¿El tramo de la mitad [h] es de un solo sentido (alguna de sus dos mitades no deja salir)? */
    internal fun oneWay(h: Int): Boolean {
        if (!canLeave(h)) return true
        val e = halfEdge(h)
        val from = halfOrigin(h)
        val t = halfTarget(h)
        for (o in firstHalf(t) until firstHalf(t + 1)) if (o != h && halfEdge(o) == e && halfTarget(o) == from) return !canLeave(o)
        return false
    }

    /** Las listas por celda de las secciones de dibujo: [LONG] = mitades dueñas, [FAR], [EXTRA] y [MINOR] = líneas. */
    internal fun forEachListed(section: Int, cell: Int, action: (Int) -> Unit) {
        val s = sectionOffset(section)
        if (s <= 0) return
        val n = buf.getInt(s)
        var lo = 0
        var hi = n - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = buf.getInt(s + 4 + 8 * mid)
            when {
                c < cell -> lo = mid + 1
                c > cell -> hi = mid - 1
                else -> {
                    val reader = VarintReader(buf, s + 8 + 8 * n + buf.getInt(s + 8 + 8 * mid))
                    val count = reader.next().toInt()
                    var v = 0
                    repeat(count) {
                        v += reader.next().toInt()
                        action(v)
                    }
                    return
                }
            }
        }
    }

    private fun sectionOffset(section: Int) = when (section) {
        LONG -> oLong
        FAR -> oFar
        EXTRA -> oExtra
        else -> oMinor
    }

    /**
     * La línea en [offset] de la sección [section] ([FAR], [EXTRA] o [MINOR]): sus puntos (lat, lon en millonésimas) a
     * [out] y su clase, sentido y nombre en [line].
     */
    internal fun readLine(section: Int, offset: Int, out: PointList, line: DrawLine) {
        val s = sectionOffset(section)
        val n = buf.getInt(s)
        val data = s + 8 + 8 * n + buf.getInt(s + 4 + 8 * n) + 4
        val reader = VarintReader(buf, data + offset)
        val count = reader.next().toInt()
        line.roadClass = buf.get(reader.pos).toInt() and 0xFF
        line.oneWay = buf.get(reader.pos + 1).toInt() and 1 != 0
        reader.pos += 2
        line.nameRef = reader.next().toInt() - 1
        val unit = when (section) {
            FAR -> 10
            MINOR -> 50
            else -> 1
        }
        var la = 0
        var lo = 0
        repeat(count) {
            la += unzig(reader.next())
            lo += unzig(reader.next())
            out.add(la * unit, lo * unit)
        }
    }

    /** Los cruces de la celda [cell]: de (incluido) a (sin incluir); null si la celda no tiene. */
    internal fun cellVertices(cell: Int): Pair<Int, Int>? {
        var lo = 0
        var hi = cellCount - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = buf.getInt(oCells + 8 * mid)
            when {
                c < cell -> lo = mid + 1
                c > cell -> hi = mid - 1
                else -> {
                    val first = buf.getInt(oCells + 8 * mid + 4)
                    val next = if (mid + 1 < cellCount) buf.getInt(oCells + 8 * (mid + 1) + 4) else vertexCount
                    return first to next
                }
            }
        }
        return null
    }

    companion object {
        /** Las secciones de dibujo ([forEachListed]). */
        internal const val LONG = 0
        internal const val FAR = 1
        internal const val EXTRA = 2
        internal const val MINOR = 3
        /** Celdas de las secciones de dibujo (grados): las de la ruta, la vista lejana y sus calles menores. */
        internal const val CELL_DEG = 0.01
        internal const val FAR_CELL_DEG = 0.1
        internal const val MINOR_CELL_DEG = 0.05
        private const val FLAG_DRAW = 1
        private const val VERTEX = 12
        private const val HALF = 9
        private const val EDGE = 14
        private const val TURN = 13
        private const val CELL = 0.01
        internal const val M_PER_DEG = 111_195.0
        private const val SNAP_MAX_M = 2_000.0
        private const val SNAP_RINGS = 3

        /**
         * Abre [file]; null si no es un LDM0 o LDM1 sano (cabecera y secciones, también las de dibujo, dentro del archivo y en
         * orden). Si el archivo está sano pero no cabe en la memoria de la pantalla (el `map` falla en equipos de 32 bits),
         * [RouteException] con [RouteError.TOO_BIG]: no está dañado y no hay que volver a bajarlo.
         */
        fun open(file: File): RoadGraph? {
            val raf = runCatching { RandomAccessFile(file, "r") }.getOrNull() ?: return null
            return try {
                val channel = raf.channel
                val size = channel.size()
                if (size < 64 || size > Int.MAX_VALUE) return null.also { channel.close() }
                val mapped = try {
                    channel.map(FileChannel.MapMode.READ_ONLY, 0, size)
                } catch (_: java.io.IOException) {
                    channel.close()
                    throw RouteException(RouteError.TOO_BIG)
                } catch (_: OutOfMemoryError) {
                    channel.close()
                    throw RouteException(RouteError.TOO_BIG)
                }
                val buf = mapped.order(ByteOrder.LITTLE_ENDIAN)
                val ok = buf.get(0) == 'L'.code.toByte() && buf.get(1) == 'D'.code.toByte() && buf.get(2) == 'M'.code.toByte() &&
                    (buf.get(3) == '0'.code.toByte() || buf.get(3) == '1'.code.toByte()) && valid(buf, size) &&
                    (!flagged(buf) || drawingFits(size) { buf.getInt(it.toInt()) })
                if (!ok) null.also { channel.close() } else RoadGraph(channel, buf)
            } catch (e: RouteException) {
                throw e
            } catch (_: Exception) {
                runCatching { raf.close() }
                null
            }
        }

        /**
         * ¿[file] es un LDM1 sano con las secciones de dibujo (lo que necesita un mapa del formato 2)? Lee la cabecera y el
         * principio de cada sección (no el archivo completo ni lo mapea): sirve para revisar un paquete antes de usarlo.
         */
        fun hasDrawing(file: File): Boolean = runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val size = raf.length()
                if (size < 128 || size > Int.MAX_VALUE) return false
                val head = ByteArray(128)
                raf.readFully(head)
                val b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
                val ok = head[0] == 'L'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == 'M'.code.toByte() &&
                    head[3] == '1'.code.toByte() && flagged(b) && valid(b, size)
                ok && drawingFits(size) { at -> raf.seek(at); Integer.reverseBytes(raf.readInt()) }
            }
        }.getOrDefault(false)

        /** ¿La cabecera dice que trae las secciones de dibujo (LDM1 con la bandera 1)? */
        private fun flagged(b: ByteBuffer) = b.get(3) == '1'.code.toByte() && b.getShort(6).toInt() and FLAG_DRAW != 0

        /**
         * ¿Las secciones de dibujo caben en un archivo de [size] bytes? [int] lee un entero (little endian) en una posición.
         * `long`, `far` y `extra` deben estar; `minor` puede faltar (0).
         */
        private fun drawingFits(size: Long, int: (Long) -> Int): Boolean {
            val offsets = IntArray(4) { int(88L + 4 * it) }
            return sectionFits(offsets[0].toLong(), size, lines = false, int) &&
                (1..2).all { sectionFits(offsets[it].toLong(), size, lines = true, int) } &&
                (offsets[3] == 0 || sectionFits(offsets[3].toLong(), size, lines = true, int))
        }

        /**
         * ¿La sección en [s] cabe en [size]? Su `n` y sus entradas, el tamaño de su bloque y el bloque y, con [lines]
         * (`far`, `extra`, `minor`), el tamaño de sus líneas y las líneas. Un número negativo o que se sale = dañada.
         */
        private fun sectionFits(s: Long, size: Long, lines: Boolean, int: (Long) -> Int): Boolean {
            if (s < 128 || s + 8 > size) return false
            val n = int(s).toLong()
            val data = s + 8 + 8 * n
            if (n < 0 || data > size) return false
            val block = int(s + 4 + 8 * n).toLong()
            if (block < 0 || data + block > size) return false
            if (!lines) return true
            if (data + block + 4 > size) return false
            val bytes = int(data + block).toLong()
            return bytes >= 0 && data + block + 4 + bytes <= size
        }

        /** El desplazamiento de una sección de dibujo en la cabecera (byte [at]); 0 si no la trae o no cabe. */
        private fun drawOffset(b: ByteBuffer, at: Int): Int {
            if (!flagged(b)) return 0
            val o = b.getInt(at)
            return if (o >= 128 && o + 8 <= b.capacity()) o else 0
        }

        private fun valid(b: ByteBuffer, size: Long): Boolean {
            val counts = IntArray(6) { b.getInt(8 + 4 * it) }
            val offs = IntArray(7) { b.getInt(32 + 4 * it) }
            if (counts.any { it < 0 } || offs.any { it < 64 || it > size }) return false
            for (i in 0 until 6) if (offs[i] > offs[i + 1]) return false
            return offs[0] + 8L * counts[0] <= offs[1] && offs[1] + 12L * counts[1] <= offs[2] &&
                offs[2] + 9L * counts[2] <= offs[3] && offs[3] + 14L * counts[3] <= offs[4] &&
                offs[5] + 4L + 4L * counts[4] <= offs[6] && offs[6] + 13L * counts[5] <= size
        }

        private fun unzig(v: Long): Int = ((v ushr 1) xor -(v and 1)).toInt()
    }
}

/** Lo que dice una línea de las secciones de dibujo (se reusa). */
internal class DrawLine {
    var roadClass = 0
    var oneWay = false
    /** Índice en la tabla de nombres; -1 = sin nombre. */
    var nameRef = -1
}

/** Lee varints sin signo de [buf] desde [pos]. */
private class VarintReader(private val buf: ByteBuffer, var pos: Int) {
    fun next(): Long {
        var v = 0L
        var s = 0
        while (true) {
            val b = buf.get(pos++).toInt() and 0xFF
            v = v or ((b and 0x7F).toLong() shl s)
            if (b < 0x80) return v
            s += 7
        }
    }
}

/**
 * Dónde cae un punto sobre las calles: el tramo [edge] (mitad [half] de [from] a [to]), a [alongM] metros de [from]
 * (sobre el tramo), el punto más cercano ([lat], [lon]) y a cuánto quedó ([offM]).
 */
data class Snap(
    val half: Int, val from: Int, val to: Int, val edge: Int, val alongM: Double, val lat: Double, val lon: Double, val offM: Double,
)

/** Pares (lat, lon) de enteros que crece sin crear objetos por punto. */
internal class PointList {
    private var data = IntArray(64)
    var size = 0
        private set

    fun add(lat: Int, lon: Int) {
        if (2 * size + 2 > data.size) data = data.copyOf(data.size * 2)
        data[2 * size] = lat
        data[2 * size + 1] = lon
        size++
    }

    fun lat(i: Int) = data[2 * i]
    fun lon(i: Int) = data[2 * i + 1]
    fun clear() {
        size = 0
    }
}
