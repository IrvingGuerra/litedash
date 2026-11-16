package com.irvingguerra.litedash.feature.obd

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class Elm327Test {
    /** Adaptador falso: como uno real, contesta [reply] solo después de recibir un comando. */
    private fun elm(reply: String) = Elm327(object : ElmLink {
        private var answer = ByteArrayInputStream(ByteArray(0))
        override val input = object : java.io.InputStream() {
            override fun read() = answer.read()
            override fun available() = answer.available()
        }
        override val output = object : java.io.OutputStream() {
            override fun write(b: Int) {
                if (b == '\r'.code) answer = ByteArrayInputStream(reply.toByteArray())
            }
        }
        override fun close() {}
    })

    /**
     * Adaptador falso que contesta según el comando ([answer] null = calla) y un reloj falso (así un
     * comando sin respuesta expira sin esperar de verdad).
     */
    private class Scripted(private val answer: (String) -> String?) : ElmLink {
        val sent = mutableListOf<String>()
        private var reply = ByteArrayInputStream(ByteArray(0))
        private val line = StringBuilder()
        override val input = object : java.io.InputStream() {
            override fun read() = reply.read()
            override fun available() = reply.available()
        }
        override val output = object : java.io.OutputStream() {
            override fun write(b: Int) {
                if (b != '\r'.code) return run { line.append(b.toChar()) }
                val cmd = line.toString().also { line.clear() }
                sent += cmd
                reply = ByteArrayInputStream((answer(cmd) ?: "").toByteArray())
            }
        }
        override fun close() {}
        private var clock = 0L
        // El reloj solo avanza cuando ya no hay nada que leer (si no, expiraría a media respuesta).
        fun elm() = Elm327(this, now = { if (reply.available() == 0) clock += 1_000; clock })
    }

    @Test fun comando_sin_respuesta_expira_con_su_tipo() {
        val e = Scripted { null }.elm()
        try {
            e.command("ATZ")
            fail()
        } catch (x: ObdException) {
            assertEquals(ObdError.ADAPTER_SILENT, x.kind)
        }
    }

    @Test fun el_auto_que_no_contesta_se_dice_aparte() {
        val e = Scripted { if (it == "0100") "UNABLE TO CONNECT\r>" else "OK\r>" }.elm()
        try {
            e.init()
            fail()
        } catch (x: ObdException) {
            assertEquals(ObdError.CAR_SILENT, x.kind)
            assertEquals("UNABLETOCONNECT", x.message)
        }
    }

    @Test fun modulo_restaura_siempre_y_corta_al_primer_no() {
        val link = Scripted { cmd ->
            when (cmd) {
                "2123" -> "6123AABB\r>"
                "2126" -> "7F2131\r>"
                "2124" -> null // el adaptador se queda callado
                else -> "OK\r>"
            }
        }
        val replies = link.elm().module(listOf("ATSH600"), listOf("2123", "2126", "2124"), listOf("ATSH7DF", "ATCRA"))
        assertEquals(2, replies.size)
        assertArrayEquals(intArrayOf(0xAA, 0xBB), replies[0])
        assertNull(replies[1])
        assertEquals(listOf("ATSH600", "2123", "2126", "ATSH7DF", "ATCRA"), link.sent)
        // Aunque una petición falle, el motor queda como estaba.
        val silent = Scripted { if (it == "2123") null else "OK\r>" }
        try {
            silent.elm().module(listOf("ATSH600"), listOf("2123"), listOf("ATSH7DF"))
            fail()
        } catch (x: ObdException) {
            assertEquals(ObdError.ADAPTER_SILENT, x.kind)
        }
        assertEquals("ATSH7DF", silent.sent.last())
    }

    @Test fun voltaje_del_adaptador_perdido_no_se_traga() {
        // Antes un runCatching lo volvía null y la caída nunca se notaba.
        try {
            Scripted { null }.elm().voltage()
            fail()
        } catch (x: ObdException) {
            assertEquals(ObdError.ADAPTER_SILENT, x.kind)
        }
        assertEquals(12.6f, Scripted { "12.6V\r>" }.elm().voltage())
    }

    @Test fun limpia_eco_y_espacios() {
        assertEquals(listOf("410D3C"), elm("010D\r41 0D 3C\r\r>").command("010D"))
    }

    @Test fun lee_velocidad_y_rpm() {
        assertEquals(60f, Pids.first { it.code == 0x0D }.decode(elm("410D3C\r>").pid(0x0D)!!))
        assertEquals(2700f, Pids.first { it.code == 0x0C }.decode(elm("410C2A30\r>").pid(0x0C)!!))
    }

    @Test fun pids_soportados() {
        // BE1FA813: 01,03,04,05,06,07,0C,0D,0E,0F,10,11,13,15,1C,1F,20
        val s = elm("4100BE1FA813\r>").supportedPids()
        assertTrue(0x0C in s && 0x0D in s && 0x05 in s && 0x20 in s)
        assertFalse(0x02 in s)
    }

    @Test fun codigos_en_can_con_cuenta() {
        assertEquals(listOf("P0133", "P0420"), parseTroubleCodes("03", listOf("430201330420")))
    }

    @Test fun codigos_en_protocolo_viejo() {
        // Sin byte de cuenta: siempre tres pares por renglón.
        assertEquals(listOf("P0301", "C0300"), parseTroubleCodes("03", listOf("43030143000000")))
    }

    @Test fun codigos_en_varias_tramas() {
        // Cuatro códigos no caben en una trama CAN: antes solo salían los dos primeros.
        val lines = listOf("00A", "0:430401330420", "1:01710300AAAAAA")
        assertEquals(listOf("P0133", "P0420", "P0171", "P0300"), parseTroubleCodes("03", lines))
    }

    @Test fun codigos_y_vin_con_encabezados() {
        // Clon que deja "7E8" + largo: antes decía "sin códigos" con la luz prendida.
        assertEquals(listOf("P0133", "P0420"), parseTroubleCodes("03", listOf("7E806430201330420")))
        assertEquals(emptyList<String>(), parseTroubleCodes("03", listOf("7E8024300")))
        assertEquals(
            listOf("P0133", "P0420", "P0171", "P0300"),
            parseTroubleCodes("03", listOf("7E8100A430401330420", "7E82101710300AAAAAA")),
        )
        val vin = listOf("7E81014490201314731", "7E8214A433534343452", "7E82237323532333637")
        assertEquals("1G1JC5444R7252367", parseVin(vin))
    }

    @Test fun vin_en_varios_renglones() {
        val lines = listOf("014", "0:490201314731", "1:4A433534343452", "2:37323532333637")
        assertEquals("1G1JC5444R7252367", parseVin(lines))
    }

    @Test fun monitores() {
        val r = parseReadiness(intArrayOf(0x81, 0x07, 0x65, 0x04))!!
        assertTrue(r.mil)
        assertFalse(r.monitors.first { it.name == MonitorName.EVAP }.complete)
        assertTrue(r.monitors.first { it.name == MonitorName.CATALYST }.complete)
    }

    @Test fun describe_codigos() {
        meaning("P0303").let { assertEquals(R.string.code_misfire, it.text); assertEquals(3, it.cylinder) }
        assertEquals(R.string.code_p0420, meaning("P0420").text)
        meaning("P1234").let { assertEquals(R.string.code_system_fuel_air, it.text); assertTrue(it.maker) }
        assertEquals(R.string.code_system_network, meaning("U0073").text)
    }

    @Test fun codigos_de_la_marca_segun_sae() {
        for (c in listOf("P1234", "P3000", "P3399", "B1001", "C2100", "U1000")) assertTrue(c, isMakerCode(c))
        // P2xxx y P34xx–P39xx son genéricos (antes salían "de la marca"), igual U0/U3.
        for (c in listOf("P0420", "P2096", "P2187", "P3400", "P3999", "B0001", "U0100", "U3000")) assertFalse(c, isMakerCode(c))
        assertFalse(meaning("P2096").maker)
    }

    @Test fun vin_sin_simbolos_de_relleno() {
        // Un ":" (0x3A) de relleno al final ya no se cuela en los últimos 17.
        val lines = listOf("015", "0:490201314731", "1:4A433534343452", "2:373235323336373A")
        assertEquals("1G1JC5444R7252367", parseVin(lines))
    }

    @Test fun modulo_en_una_trama() =
        assertArrayEquals(intArrayOf(0x1A, 0x2B), parseModuleReply("22C00B", listOf("62C00B1A2B")))

    @Test fun modulo_en_varias_tramas() =
        assertArrayEquals(intArrayOf(1, 2, 3, 4, 5, 6, 7, 8), parseModuleReply("2130", listOf("00A", "0:61300102030405", "1:060708")))

    @Test fun modulo_que_dice_que_no() {
        assertNull(parseModuleReply("22C00B", listOf("7F2231")))
        assertNull(parseModuleReply("22C00B", listOf("NODATA")))
    }

    @Test fun clon_que_deja_encabezados() {
        // CAN 11 bits sin quitar "7E8 06": antes los medidores no leían nada (solo el voltaje del adaptador).
        assertEquals(2700f, Pids.first { it.code == 0x0C }.decode(elm("7E804410C2A30\r>").pid(0x0C)!!))
        val s = elm("7E8064100BE1FA813\r>").supportedPids()
        assertTrue(0x0C in s && 0x0D in s && 0x20 in s)
        // CAN 29 bits y protocolos viejos (suma al final).
        assertEquals("3C", afterHead("410D", listOf("18DAF11003410D3C")))
        assertEquals("1AF8", afterHead("410C", listOf("486B10410C1AF8C3")))
    }

    @Test fun lo_normal_no_cambia() {
        assertEquals("3C", afterHead("410D", listOf("410D3C")))
        assertNull(afterHead("410D", listOf("NODATA")))
        assertNull(afterHead("410D", listOf("UNABLETOCONNECT")))
        assertNull(afterHead("410D", listOf("STOPPED")))
    }
}
