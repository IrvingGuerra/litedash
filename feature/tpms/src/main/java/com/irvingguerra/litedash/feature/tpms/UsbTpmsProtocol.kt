package com.irvingguerra.litedash.feature.tpms

/** Algo que mandó el receptor USB (ya validado: `55 AA`, largo y XOR correctos). */
sealed interface UsbFrame {
    /** Estado de una llanta (LEN 8): P, T y banderas tal como llegan. */
    data class State(val pos: Int, val rawPressure: Int, val rawTemp: Int, val flags: Int) : UsbFrame {
        val wheel: Wheel? get() = UsbTpmsProtocol.wheelOfPos(pos)
        /** kPa manométrica SIN el ajuste del usuario (ese lo pone el repositorio). */
        val pressureKpa: Float get() = rawPressure * UsbTpmsProtocol.KPA_PER_COUNT
        val tempC: Float get() = (rawTemp - 50).toFloat()
        val lost get() = flags and (UsbTpmsProtocol.FLAG_LOST or UsbTpmsProtocol.FLAG_LOST_ALT) != 0
        val lowBattery get() = flags and UsbTpmsProtocol.FLAG_LOW_BATTERY != 0
        val leak get() = flags and UsbTpmsProtocol.FLAG_LEAK != 0
    }

    /** ID de un sensor (LEN 9); [index] 1..5 = DI, DD, TI, TD, refacción. "00000000" = sin sensor. */
    data class SensorId(val index: Int, val id: String) : UsbFrame {
        val wheel: Wheel? get() = UsbTpmsProtocol.wheelOfIndex(index)
        val empty get() = id.all { it == '0' }
    }

    /** El receptor aprendió el sensor de [pos] (respuesta a emparejar). */
    data class PairOk(val pos: Int) : UsbFrame {
        val wheel: Wheel? get() = UsbTpmsProtocol.wheelOfPos(pos)
    }

    /** Confirmó el cambio de posiciones [a] ↔ [b] (códigos POS). */
    data class SwapAck(val a: Int, val b: Int) : UsbFrame

    /** Latido; [needsAck] = el del receptor (`06 00 88`), que se contesta con [UsbTpmsProtocol.ack]. */
    data class Heartbeat(val needsAck: Boolean) : UsbFrame

    /** Trama válida que no se conoce (se guarda en el registro para diagnóstico). */
    class Unknown(val bytes: ByteArray) : UsbFrame {
        override fun toString() = "Unknown(${bytes.hex()})"
        override fun equals(other: Any?) = other is Unknown && other.bytes.contentEquals(bytes)
        override fun hashCode() = bytes.contentHashCode()
    }
}

/**
 * Receptor USB "TPMS for Android" (433 MHz, protocolo V3): `55 AA LEN CMD/POS DATOS… CS`,
 * LEN = largo total, CS = XOR de todo lo anterior. 19200 baudios, 8N1.
 * El borrado de sensores (`06 58 55`) existe pero no se expone a propósito.
 */
object UsbTpmsProtocol {
    const val BAUD = 19_200
    /** 1 cuenta ≈ 0.5 psi. */
    const val KPA_PER_COUNT = 3.44f
    const val FLAG_LOST = 0x20
    const val FLAG_LOST_ALT = 0x80
    const val FLAG_LOW_BATTERY = 0x10
    const val FLAG_LEAK = 0x08
    const val MIN_LEN = 6
    const val MAX_LEN = 12

    /** XOR de los primeros [len] bytes (todo lo que va antes del CS). */
    fun checksum(bytes: ByteArray, len: Int): Int {
        var x = 0
        for (i in 0 until len) x = x xor (bytes[i].toInt() and 0xFF)
        return x
    }

    /** Código POS de las tramas de estado y comandos. */
    fun posOf(w: Wheel): Int = when (w) {
        Wheel.FRONT_LEFT -> 0x00
        Wheel.FRONT_RIGHT -> 0x01
        Wheel.REAR_LEFT -> 0x10
        Wheel.REAR_RIGHT -> 0x11
        Wheel.SPARE -> 0x05
    }

    fun wheelOfPos(pos: Int): Wheel? = Wheel.entries.firstOrNull { posOf(it) == pos }

    /** Las tramas de ID numeran distinto: 1..5 = DI, DD, TI, TD, refacción. */
    fun wheelOfIndex(index: Int): Wheel? = Wheel.entries.getOrNull(index - 1)

    private fun frame(vararg body: Int): ByteArray {
        val out = ByteArray(body.size + 3)
        out[0] = 0x55; out[1] = 0xAA.toByte(); out[2] = out.size.toByte()
        // body = del byte 3 en adelante, con un 0 al final donde va el CS.
        for (i in body.indices) out[i + 3] = body[i].toByte()
        out[out.size - 1] = checksum(out, out.size - 1).toByte()
        return out
    }

    // Comandos de LEN 6: CMD y su dato; el 0 final es el lugar del CS.
    private fun cmd(vararg b: Int) = frame(*b, 0)

    /** Latido de la pantalla (`55 AA 06 19 00 E0`); opcional, cada 1 s. */
    fun heartbeat(): ByteArray = cmd(0x19, 0x00)
    /** Respuesta al latido del receptor (`55 AA 06 00 00 F9`). */
    fun ack(): ByteArray = cmd(0x00, 0x00)
    /** Pide los IDs de los sensores (`55 AA 06 07 00 FE`). */
    fun queryIds(): ByteArray = cmd(0x07, 0x00)
    /** Empieza a aprender el sensor de [w] (luego inflar/desinflar o girar la llanta). */
    fun pair(w: Wheel): ByteArray = cmd(0x01, posOf(w))
    fun stopPair(): ByteArray = cmd(0x06, 0x00)
    /** Cambia de lugar los sensores de [a] y [b] en el receptor. */
    fun swap(a: Wheel, b: Wheel): ByteArray = frame(0x03, posOf(a), posOf(b), 0)

    /**
     * Junta lo que llega por el cable y saca tramas completas: busca `55 AA`, valida LEN
     * (6..12) y XOR; si algo falla tira un byte y vuelve a buscar. No es seguro entre hilos.
     */
    class FrameReader {
        /** Mientras se empareja, `06 19` es la respuesta; si no, es un latido. */
        @Volatile var pairing = false
        /** Ya llegó un ID de refacción distinto de cero (si no, se ignora la trama fija de refacción). */
        var spareKnown = false
            private set
        /** Cada trama válida tal cual (con su CS), para el registro de diagnóstico. */
        var onRaw: ((ByteArray) -> Unit)? = null

        private var buf = ByteArray(64)
        private var count = 0

        fun feed(data: ByteArray, n: Int = data.size): List<UsbFrame> {
            if (count + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, count + n))
            System.arraycopy(data, 0, buf, count, n)
            count += n
            val out = mutableListOf<UsbFrame>()
            var start = 0
            while (true) {
                // Buscar el encabezado.
                while (start < count && !(buf.u8(start) == 0x55 && (start + 1 >= count || buf.u8(start + 1) == 0xAA))) start++
                if (count - start < 3) break
                val len = buf.u8(start + 2)
                if (len !in MIN_LEN..MAX_LEN) { start++; continue }
                if (count - start < len) break
                val frame = buf.copyOfRange(start, start + len)
                if (checksum(frame, len - 1) != frame.u8(len - 1)) { start++; continue }
                start += len
                onRaw?.invoke(frame)
                parse(frame)?.let { out += it }
            }
            // Lo que no se usó pasa al principio.
            System.arraycopy(buf, start, buf, 0, count - start)
            count -= start
            return out
        }

        private fun parse(f: ByteArray): UsbFrame? = when (f.size) {
            6 -> when (f.u8(3)) {
                0x18 -> UsbFrame.PairOk(f.u8(4))
                0x19 -> if (pairing) UsbFrame.PairOk(f.u8(4)) else UsbFrame.Heartbeat(needsAck = false)
                0x00 -> if (f.u8(4) == 0x88) UsbFrame.Heartbeat(needsAck = true) else UsbFrame.Unknown(f)
                else -> UsbFrame.Unknown(f)
            }
            7 -> if (f.u8(3) == 0x30) UsbFrame.SwapAck(f.u8(4), f.u8(5)) else UsbFrame.Unknown(f)
            8 -> {
                val s = UsbFrame.State(f.u8(3), f.u8(4), f.u8(5), f.u8(6))
                // Trama fija de refacción que el receptor manda aunque no haya sensor.
                val placeholder = s.pos == 0x05 && s.rawPressure == 0x46 && s.rawTemp == 0x46 && s.flags == 0
                if (placeholder && !spareKnown) null else s
            }
            9 -> UsbFrame.SensorId(f.u8(3), f.hex(4, 8)).also {
                if (it.wheel == Wheel.SPARE) spareKnown = !it.empty
            }
            else -> UsbFrame.Unknown(f)
        }
    }
}
