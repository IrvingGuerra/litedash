package com.irvingguerra.litedash.feature.link

import kotlin.math.abs
import kotlin.math.max

/** Un código QR ya armado: [size] módulos por lado; `get(x, y)` dice si ese módulo es oscuro. */
class QrMatrix internal constructor(val size: Int, private val dark: BooleanArray) {
    operator fun get(x: Int, y: Int) = dark[y * size + x]
}

/**
 * Codificador QR propio (sin librerías): modo byte, corrección M, versiones 1 a 10 (hasta
 * [MAX_BYTES] bytes). Sigue ISO/IEC 18004: Reed-Solomon, las ocho máscaras con su penalización
 * e información de formato y de versión.
 */
object QrCode {
    /** Lo más que cabe (versión 10, corrección M). */
    const val MAX_BYTES = 213

    private const val MAX_VERSION = 10

    /** Corrección M por versión (1 a 10): bytes de corrección por bloque y cuántos bloques. */
    private val ECC_PER_BLOCK = intArrayOf(10, 16, 26, 18, 24, 16, 18, 22, 22, 26)
    private val BLOCKS = intArrayOf(1, 1, 1, 2, 2, 4, 4, 4, 5, 5)

    /** Los dos bits que nombran la corrección M en la información de formato. */
    private const val FORMAT_M = 0

    private const val PENALTY_RUN = 3
    private const val PENALTY_BLOCK = 3
    private const val PENALTY_FINDER = 40
    private const val PENALTY_BALANCE = 10

    /** El QR de [data] en la versión más chica donde quepa; null si pasa de [MAX_BYTES]. */
    fun encode(data: ByteArray): QrMatrix? {
        val version = (1..MAX_VERSION).firstOrNull { capacity(it) >= data.size } ?: return null
        return Builder(version).build(interleaved(version, dataCodewords(version, data)))
    }

    /** Módulos de datos (los que no son patrones fijos) de una versión. */
    private fun rawModules(version: Int): Int {
        var modules = (16 * version + 128) * version + 64
        if (version >= 2) {
            val align = version / 7 + 2
            modules -= (25 * align - 10) * align - 55
            if (version >= 7) modules -= 36
        }
        return modules
    }

    private fun dataLength(version: Int) = rawModules(version) / 8 - ECC_PER_BLOCK[version - 1] * BLOCKS[version - 1]

    /** Bits del contador de bytes: 8 hasta la versión 9, 16 desde la 10. */
    private fun countBits(version: Int) = if (version < 10) 8 else 16

    /** Bytes de texto que caben: los de datos menos el modo (4 bits) y el contador. */
    private fun capacity(version: Int) = (dataLength(version) * 8 - 4 - countBits(version)) / 8

    /** Modo byte + contador + datos + terminador, rellenado hasta el largo de la versión. */
    private fun dataCodewords(version: Int, data: ByteArray): IntArray {
        val out = IntArray(dataLength(version))
        var bit = 0
        fun put(value: Int, bits: Int) {
            for (i in bits - 1 downTo 0) {
                if ((value ushr i) and 1 != 0) out[bit ushr 3] = out[bit ushr 3] or (0x80 ushr (bit and 7))
                bit++
            }
        }
        put(0b0100, 4)
        put(data.size, countBits(version))
        for (b in data) put(b.toInt() and 0xFF, 8)
        // El terminador (hasta 4 ceros) y el relleno al byte ya son ceros; siguen EC y 11 alternados.
        var next = (minOf(bit + 4, out.size * 8) + 7) / 8
        var pad = 0xEC
        while (next < out.size) {
            out[next++] = pad
            pad = pad xor 0xEC xor 0x11
        }
        return out
    }

    /** Parte los datos en bloques, les calcula la corrección y los intercala como van en el QR. */
    private fun interleaved(version: Int, data: IntArray): IntArray {
        val blocks = BLOCKS[version - 1]
        val ecc = ECC_PER_BLOCK[version - 1]
        val total = rawModules(version) / 8
        val shortBlocks = blocks - total % blocks
        val shortLength = total / blocks
        val divisor = rsDivisor(ecc)
        val parts = ArrayList<IntArray>(blocks)
        var from = 0
        for (i in 0 until blocks) {
            val length = shortLength - ecc + if (i < shortBlocks) 0 else 1
            val part = data.copyOfRange(from, from + length)
            from += length
            // Todos del mismo largo: los cortos llevan un hueco (que se salta) antes de la corrección.
            val block = IntArray(shortLength + 1)
            part.copyInto(block)
            rsRemainder(part, divisor).copyInto(block, block.size - ecc)
            parts += block
        }
        val out = IntArray(total)
        var k = 0
        for (i in 0..shortLength) for (j in 0 until blocks) {
            if (i != shortLength - ecc || j >= shortBlocks) out[k++] = parts[j][i]
        }
        return out
    }

    /** Multiplicación en GF(2^8) con el polinomio 0x11D. */
    private fun multiply(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z ushr 7) * 0x11D)
            z = z xor (((y ushr i) and 1) * x)
        }
        return z
    }

    /** Polinomio generador de Reed-Solomon de ese grado (sin el término mayor, que es 1). */
    private fun rsDivisor(degree: Int): IntArray {
        val result = IntArray(degree)
        result[degree - 1] = 1
        var root = 1
        repeat(degree) {
            for (j in 0 until degree) {
                result[j] = multiply(result[j], root)
                if (j + 1 < degree) result[j] = result[j] xor result[j + 1]
            }
            root = multiply(root, 2)
        }
        return result
    }

    /** Los bytes de corrección de [data]: el residuo de dividirlo entre [divisor]. */
    private fun rsRemainder(data: IntArray, divisor: IntArray): IntArray {
        val result = IntArray(divisor.size)
        for (b in data) {
            val factor = b xor result[0]
            result.copyInto(result, 0, 1)
            result[result.size - 1] = 0
            for (i in result.indices) result[i] = result[i] xor multiply(divisor[i], factor)
        }
        return result
    }

    /** Dibuja una versión: patrones fijos, datos, y la máscara que menos penaliza. */
    private class Builder(private val version: Int) {
        private val size = version * 4 + 17
        private val dark = BooleanArray(size * size)
        /** Los módulos de los patrones fijos: ni llevan datos ni se enmascaran. */
        private val fixed = BooleanArray(size * size)

        fun build(codewords: IntArray): QrMatrix {
            drawFixed()
            drawCodewords(codewords)
            var best = 0
            var least = Int.MAX_VALUE
            for (mask in 0 until 8) {
                applyMask(mask)
                drawFormat(mask)
                val penalty = penalty()
                if (penalty < least) {
                    least = penalty
                    best = mask
                }
                // Aplicarla otra vez la quita (es un XOR).
                applyMask(mask)
            }
            applyMask(best)
            drawFormat(best)
            return QrMatrix(size, dark)
        }

        private fun at(x: Int, y: Int) = dark[y * size + x]

        private fun set(x: Int, y: Int, on: Boolean) {
            dark[y * size + x] = on
            fixed[y * size + x] = true
        }

        private fun drawFixed() {
            // Sincronía (renglón y columna 6); las esquinas la pisan después.
            for (i in 0 until size) {
                set(6, i, i % 2 == 0)
                set(i, 6, i % 2 == 0)
            }
            drawFinder(3, 3)
            drawFinder(size - 4, 3)
            drawFinder(3, size - 4)
            val align = alignmentPositions()
            val last = align.size - 1
            for (i in align.indices) for (j in align.indices) {
                // Menos donde están las tres esquinas.
                if (!(i == 0 && j == 0 || i == 0 && j == last || i == last && j == 0)) drawAlignment(align[i], align[j])
            }
            // Aparta el lugar del formato (la máscara aún no se sabe) y dibuja la versión.
            drawFormat(0)
            drawVersion()
        }

        /** Esquina de 7×7 con su separador claro alrededor. */
        private fun drawFinder(cx: Int, cy: Int) {
            for (dy in -4..4) for (dx in -4..4) {
                val x = cx + dx
                val y = cy + dy
                if (x !in 0 until size || y !in 0 until size) continue
                val ring = max(abs(dx), abs(dy))
                set(x, y, ring != 2 && ring != 4)
            }
        }

        private fun drawAlignment(cx: Int, cy: Int) {
            for (dy in -2..2) for (dx in -2..2) set(cx + dx, cy + dy, max(abs(dx), abs(dy)) != 1)
        }

        /** Centros de los patrones de alineación (ninguno en la versión 1). */
        private fun alignmentPositions(): IntArray {
            if (version == 1) return IntArray(0)
            val count = version / 7 + 2
            val step = (version * 4 + count * 2 + 1) / (count * 2 - 2) * 2
            val result = IntArray(count)
            result[0] = 6
            var position = size - 7
            for (i in count - 1 downTo 1) {
                result[i] = position
                position -= step
            }
            return result
        }

        /** Los 15 bits de formato (corrección + máscara, con su BCH), en sus dos copias. */
        private fun drawFormat(mask: Int) {
            val data = (FORMAT_M shl 3) or mask
            var rem = data
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
            val bits = ((data shl 10) or rem) xor 0x5412
            fun bit(i: Int) = (bits ushr i) and 1 != 0
            for (i in 0..5) set(8, i, bit(i))
            set(8, 7, bit(6))
            set(8, 8, bit(7))
            set(7, 8, bit(8))
            for (i in 9..14) set(14 - i, 8, bit(i))
            for (i in 0..7) set(size - 1 - i, 8, bit(i))
            for (i in 8..14) set(8, size - 15 + i, bit(i))
            // El módulo que siempre es oscuro.
            set(8, size - 8, true)
        }

        /** Los 18 bits de versión (desde la 7), en sus dos copias. */
        private fun drawVersion() {
            if (version < 7) return
            var rem = version
            repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
            val bits = (version shl 12) or rem
            for (i in 0 until 18) {
                val on = (bits ushr i) and 1 != 0
                val a = size - 11 + i % 3
                val b = i / 3
                set(a, b, on)
                set(b, a, on)
            }
        }

        /** Los datos en zigzag, de abajo a la derecha hacia la izquierda, de dos columnas en dos. */
        private fun drawCodewords(data: IntArray) {
            var i = 0
            var right = size - 1
            while (right >= 1) {
                // La columna de sincronía se salta.
                if (right == 6) right = 5
                for (step in 0 until size) for (j in 0..1) {
                    val x = right - j
                    val upward = (right + 1) and 2 == 0
                    val y = if (upward) size - 1 - step else step
                    if (!fixed[y * size + x] && i < data.size * 8) {
                        dark[y * size + x] = (data[i ushr 3] ushr (7 - (i and 7))) and 1 != 0
                        i++
                    }
                }
                right -= 2
            }
        }

        private fun applyMask(mask: Int) {
            for (y in 0 until size) for (x in 0 until size) {
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if (invert && !fixed[y * size + x]) dark[y * size + x] = !dark[y * size + x]
            }
        }

        /** Qué tan difícil de leer queda con la máscara puesta (menos es mejor). */
        private fun penalty(): Int {
            var result = 0
            // Corridas del mismo color y dibujos que parecen esquina, por renglones y por columnas.
            for (rows in arrayOf(true, false)) for (a in 0 until size) {
                var color = false
                var run = 0
                val history = IntArray(7)
                for (b in 0 until size) {
                    val module = if (rows) at(b, a) else at(a, b)
                    if (module == color) {
                        run++
                        if (run == 5) result += PENALTY_RUN else if (run > 5) result++
                    } else {
                        addHistory(run, history)
                        if (!color) result += finderLike(history) * PENALTY_FINDER
                        color = module
                        run = 1
                    }
                }
                result += finderLikeAtEnd(color, run, history) * PENALTY_FINDER
            }
            // Cuadros de 2×2 del mismo color.
            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val color = at(x, y)
                if (color == at(x + 1, y) && color == at(x, y + 1) && color == at(x + 1, y + 1)) result += PENALTY_BLOCK
            }
            // Qué tan lejos está de mitad oscuro y mitad claro.
            val total = size * size
            val k = (abs(dark.count { it } * 20 - total * 10) + total - 1) / total - 1
            return result + k * PENALTY_BALANCE
        }

        /** Cuántas veces las últimas corridas forman 1:1:3:1:1 con cuatro claros a un lado. */
        private fun finderLike(history: IntArray): Int {
            val n = history[1]
            val core = n > 0 && history[2] == n && history[3] == n * 3 && history[4] == n && history[5] == n
            return (if (core && history[0] >= n * 4 && history[6] >= n) 1 else 0) +
                (if (core && history[6] >= n * 4 && history[0] >= n) 1 else 0)
        }

        private fun finderLikeAtEnd(color: Boolean, run: Int, history: IntArray): Int {
            var length = run
            if (color) {
                addHistory(length, history)
                length = 0
            }
            // El borde cuenta como claro.
            addHistory(length + size, history)
            return finderLike(history)
        }

        private fun addHistory(run: Int, history: IntArray) {
            val length = if (history[0] == 0) run + size else run
            history.copyInto(history, 1, 0, history.size - 1)
            history[0] = length
        }
    }
}
