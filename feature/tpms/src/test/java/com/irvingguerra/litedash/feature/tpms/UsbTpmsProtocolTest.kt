package com.irvingguerra.litedash.feature.tpms

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbTpmsProtocolTest {
    private fun hex(s: String): ByteArray = s.split(" ").filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

    private fun one(s: String, reader: UsbTpmsProtocol.FrameReader = UsbTpmsProtocol.FrameReader()): UsbFrame =
        reader.feed(hex(s)).single()

    @Test
    fun checksumsDeLaDocumentacion() {
        for (f in listOf(
            "55 AA 08 10 24 49 00 8A", "55 AA 08 10 0D 49 08 AB", "55 AA 08 00 98 44 00 2B", "55 AA 08 01 1F 49 00 A0",
            "55 AA 09 01 99 D1 12 7C D1", "55 AA 06 19 00 E0", "55 AA 06 00 00 F9", "55 AA 06 07 00 FE",
            "55 AA 06 01 00 F8", "55 AA 06 06 00 FF", "55 AA 07 03 00 01 FA", "55 AA 08 05 46 46 00 F2", "55 AA 06 58 55 F4",
        )) {
            val b = hex(f)
            assertEquals(f, b.last().toInt() and 0xFF, UsbTpmsProtocol.checksum(b, b.size - 1))
        }
    }

    @Test
    fun estadoTraseraIzquierdaNormal() {
        val s = one("55 AA 08 10 24 49 00 8A") as UsbFrame.State
        assertEquals(Wheel.REAR_LEFT, s.wheel)
        assertEquals(123.8f, s.pressureKpa, 0.05f)
        assertEquals(23f, s.tempC, 0f)
        assertFalse(s.leak); assertFalse(s.lost); assertFalse(s.lowBattery)
    }

    @Test
    fun estadoConFuga() {
        val s = one("55 AA 08 10 0D 49 08 AB") as UsbFrame.State
        assertEquals(Wheel.REAR_LEFT, s.wheel)
        assertEquals(44.7f, s.pressureKpa, 0.05f)
        assertEquals(23f, s.tempC, 0f)
        assertTrue(s.leak)
    }

    @Test
    fun estadoDelanteras() {
        val fl = one("55 AA 08 00 98 44 00 2B") as UsbFrame.State
        assertEquals(Wheel.FRONT_LEFT, fl.wheel)
        assertEquals(522.9f, fl.pressureKpa, 0.1f) // la doc redondea a 523
        assertEquals(18f, fl.tempC, 0f)
        val fr = one("55 AA 08 01 1F 49 00 A0") as UsbFrame.State
        assertEquals(Wheel.FRONT_RIGHT, fr.wheel)
        assertEquals(106.6f, fr.pressureKpa, 0.05f)
        assertEquals(23f, fr.tempC, 0f)
    }

    @Test
    fun banderas() {
        fun state(flags: Int): UsbFrame.State {
            val b = byteArrayOf(0x55, 0xAA.toByte(), 8, 0x11, 0x40, 0x48, flags.toByte(), 0)
            b[7] = UsbTpmsProtocol.checksum(b, 7).toByte()
            return UsbTpmsProtocol.FrameReader().feed(b).single() as UsbFrame.State
        }
        assertTrue(state(0x20).lost)
        assertTrue(state(0x80).lost)
        assertTrue(state(0x10).lowBattery)
        assertTrue(state(0x08).leak)
        assertEquals(Wheel.REAR_RIGHT, state(0).wheel)
    }

    @Test
    fun idDeSensor() {
        val id = one("55 AA 09 01 99 D1 12 7C D1") as UsbFrame.SensorId
        assertEquals(Wheel.FRONT_LEFT, id.wheel)
        assertEquals("99D1127C", id.id)
        assertFalse(id.empty)
        assertEquals(Wheel.SPARE, UsbTpmsProtocol.wheelOfIndex(5))
        assertEquals(Wheel.REAR_LEFT, UsbTpmsProtocol.wheelOfIndex(3))
    }

    @Test
    fun refaccionFijaSeIgnoraSinIdDeRefaccion() {
        val r = UsbTpmsProtocol.FrameReader()
        assertTrue(r.feed(hex("55 AA 08 05 46 46 00 F2")).isEmpty())
        // ID de refacción en cero: sigue sin contar.
        val zero = byteArrayOf(0x55, 0xAA.toByte(), 9, 5, 0, 0, 0, 0, 0).also { it[8] = UsbTpmsProtocol.checksum(it, 8).toByte() }
        assertTrue((r.feed(zero).single() as UsbFrame.SensorId).empty)
        assertTrue(r.feed(hex("55 AA 08 05 46 46 00 F2")).isEmpty())
        val real = byteArrayOf(0x55, 0xAA.toByte(), 9, 5, 0x12, 0x34, 0x56, 0x78, 0).also { it[8] = UsbTpmsProtocol.checksum(it, 8).toByte() }
        r.feed(real)
        assertTrue(r.spareKnown)
        assertEquals(Wheel.SPARE, (r.feed(hex("55 AA 08 05 46 46 00 F2")).single() as UsbFrame.State).wheel)
    }

    @Test
    fun comandos() {
        assertArrayEquals(hex("55 AA 06 19 00 E0"), UsbTpmsProtocol.heartbeat())
        assertArrayEquals(hex("55 AA 06 00 00 F9"), UsbTpmsProtocol.ack())
        assertArrayEquals(hex("55 AA 06 07 00 FE"), UsbTpmsProtocol.queryIds())
        assertArrayEquals(hex("55 AA 06 01 00 F8"), UsbTpmsProtocol.pair(Wheel.FRONT_LEFT))
        assertArrayEquals(hex("55 AA 06 06 00 FF"), UsbTpmsProtocol.stopPair())
        assertArrayEquals(hex("55 AA 07 03 00 01 FA"), UsbTpmsProtocol.swap(Wheel.FRONT_LEFT, Wheel.FRONT_RIGHT))
        for (w in Wheel.entries) {
            val p = UsbTpmsProtocol.pair(w)
            assertEquals(UsbTpmsProtocol.posOf(w), p[4].toInt() and 0xFF)
            assertEquals(p.last().toInt() and 0xFF, UsbTpmsProtocol.checksum(p, p.size - 1))
        }
    }

    @Test
    fun respuestasDeEmparejarYLatidos() {
        val r = UsbTpmsProtocol.FrameReader()
        val pairOk18 = byteArrayOf(0x55, 0xAA.toByte(), 6, 0x18, 0x10, 0).also { it[5] = UsbTpmsProtocol.checksum(it, 5).toByte() }
        assertEquals(UsbFrame.PairOk(0x10), r.feed(pairOk18).single())
        // 06 19 sin emparejar = latido; emparejando = respuesta.
        assertEquals(UsbFrame.Heartbeat(needsAck = false), r.feed(hex("55 AA 06 19 00 E0")).single())
        r.pairing = true
        assertEquals(UsbFrame.PairOk(0x00), r.feed(hex("55 AA 06 19 00 E0")).single())
        r.pairing = false
        val rxBeat = byteArrayOf(0x55, 0xAA.toByte(), 6, 0, 0x88.toByte(), 0).also { it[5] = UsbTpmsProtocol.checksum(it, 5).toByte() }
        assertEquals(UsbFrame.Heartbeat(needsAck = true), r.feed(rxBeat).single())
    }

    @Test
    fun confirmacionDeCambio() {
        val ack = byteArrayOf(0x55, 0xAA.toByte(), 7, 0x30, 0, 1, 0).also { it[6] = UsbTpmsProtocol.checksum(it, 6).toByte() }
        assertEquals(UsbFrame.SwapAck(0, 1), UsbTpmsProtocol.FrameReader().feed(ack).single())
    }

    @Test
    fun tramaPartidaEnVariosPedazos() {
        val r = UsbTpmsProtocol.FrameReader()
        val all = hex("55 AA 08 10 24 49 00 8A 55 AA 09 01 99 D1 12 7C D1")
        val out = mutableListOf<UsbFrame>()
        // Byte por byte.
        for (b in all) out += r.feed(byteArrayOf(b))
        assertEquals(2, out.size)
        assertTrue(out[0] is UsbFrame.State)
        assertEquals("99D1127C", (out[1] as UsbFrame.SensorId).id)
        // Cortes a la mitad del encabezado y del cuerpo.
        val r2 = UsbTpmsProtocol.FrameReader()
        assertTrue(r2.feed(all.copyOfRange(0, 1)).isEmpty())
        assertTrue(r2.feed(all.copyOfRange(1, 5)).isEmpty())
        assertEquals(1, r2.feed(all.copyOfRange(5, 10)).size)
        assertEquals(1, r2.feed(all.copyOfRange(10, all.size)).size)
    }

    @Test
    fun basuraAntesYEntreTramas() {
        val r = UsbTpmsProtocol.FrameReader()
        val out = r.feed(hex("00 FF 55 13 AA 55 55 AA 08 10 24 49 00 8A 55 7E 55 AA 08 01 1F 49 00 A0"))
        assertEquals(listOf(Wheel.REAR_LEFT, Wheel.FRONT_RIGHT), out.map { (it as UsbFrame.State).wheel })
    }

    @Test
    fun checksumMaloSeTiraYSeRecupera() {
        val r = UsbTpmsProtocol.FrameReader()
        // La primera con CS malo; la segunda empieza dentro de su cuerpo aparente.
        val out = r.feed(hex("55 AA 08 10 24 49 00 8B 55 AA 08 00 98 44 00 2B"))
        assertEquals(Wheel.FRONT_LEFT, (out.single() as UsbFrame.State).wheel)
        // Largo fuera de rango: se resincroniza.
        val out2 = r.feed(hex("55 AA 20 55 AA 06 07 00 FE"))
        assertTrue(out2.single() is UsbFrame.Unknown)
    }

    @Test
    fun registroCrudo() {
        val r = UsbTpmsProtocol.FrameReader()
        val raws = mutableListOf<String>()
        r.onRaw = { bytes, _ -> raws += bytes.hex() }
        r.feed(hex("55 AA 08 10 24 49 00 8A 55 AA 08 05 46 46 00 F2"))
        assertEquals(listOf("55AA08102449008A", "55AA0805464600F2"), raws)
    }
}
