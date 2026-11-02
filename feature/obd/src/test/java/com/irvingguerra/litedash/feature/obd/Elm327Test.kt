package com.irvingguerra.litedash.feature.obd

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun vin_en_varios_renglones() {
        val lines = listOf("014", "0:490201314731", "1:4A433534343452", "2:37323532333637")
        assertEquals("1G1JC5444R7252367", parseVin(lines))
    }

    @Test fun monitores() {
        val r = parseReadiness(intArrayOf(0x81, 0x07, 0x65, 0x04))!!
        assertTrue(r.mil)
        assertEquals(1, r.codes)
        assertFalse(r.monitors.first { it.name == MonitorName.EVAP }.complete)
        assertTrue(r.monitors.first { it.name == MonitorName.CATALYST }.complete)
    }

    @Test fun describe_codigos() {
        assertEquals("Falla de encendido en el cilindro 3", describe("P0303", true))
        assertTrue(describe("P1234", true).contains("marca"))
    }
}
