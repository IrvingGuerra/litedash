package com.irvingguerra.litedash.feature.link

import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URLDecoder
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Link v1 con los vectores de PROTOCOLO_LINK.md §7 (los mismos que pasa la app del celular). */
class LinkProtocolTest {
    private val key = ByteArray(32) { it.toByte() }
    private val nc = ByteArray(16) { (100 + it).toByte() }
    private val ns = ByteArray(16) { (200 + it).toByte() }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun vectorEncodings() {
        assertEquals("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", Base64.url(key))
        assertEquals("ZGVmZ2hpamtsbW5vcHFycw==", Base64.encode(nc))
        assertEquals("yMnKy8zNzs/Q0dLT1NXW1w==", Base64.encode(ns))
        assertArrayEquals(nc, Base64.decode("ZGVmZ2hpamtsbW5vcHFycw=="))
        assertArrayEquals(ns, Base64.decode("yMnKy8zNzs/Q0dLT1NXW1w=="))
    }

    @Test
    fun vectorMacs() {
        assertEquals("VfJ8/fBXDDi07gGZsfS3nwF2g8MMBt9u3mN2qo2/Lvg=", Base64.encode(LinkProtocol.phoneMac(key, nc, ns)))
        assertEquals("j7nDwb98skXTEAYrHATQVDjuIHO85gzyfhRFY+agujg=", Base64.encode(LinkProtocol.screenMac(key, nc, ns)))
        assertTrue(LinkProtocol.sameMac(LinkProtocol.phoneMac(key, nc, ns), Base64.decode("VfJ8/fBXDDi07gGZsfS3nwF2g8MMBt9u3mN2qo2/Lvg=")))
        assertFalse(LinkProtocol.sameMac(LinkProtocol.phoneMac(key, nc, ns), LinkProtocol.screenMac(key, nc, ns)))
    }

    @Test
    fun vectorSessionKeys() {
        assertEquals("6d34dc689e249cfc2a395f819353bb309d04dd9be4955eb7cdc50d528df20c70", hex(LinkProtocol.screenToPhoneKey(key, nc, ns)))
        assertEquals("2275bac011a9eba299201459dd05bd7ebce9d5e80b6fa8d4939fab3f60aeb2dd", hex(LinkProtocol.phoneToScreenKey(key, nc, ns)))
    }

    @Test
    fun vectorCode() {
        assertEquals("8058", LinkProtocol.code(key))
        // Siempre cuatro dígitos, con ceros a la izquierda.
        for (i in 0 until 300) assertTrue(Regex("\\d{4}").matches(LinkProtocol.code(ByteArray(32) { (it * i).toByte() })))
    }

    @Test
    fun base64RoundTrip() {
        for (n in 0..40) {
            val data = ByteArray(n) { (it * 37 + n).toByte() }
            assertEquals(java.util.Base64.getEncoder().encodeToString(data), Base64.encode(data))
            assertEquals(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(data), Base64.url(data))
            assertArrayEquals(data, Base64.decode(Base64.encode(data)))
        }
        assertThrows(IllegalArgumentException::class.java) { Base64.decode("no es base64!") }
    }

    private fun channels(): Pair<SecureChannel, SecureChannel> {
        val c2s = LinkProtocol.screenToPhoneKey(key, nc, ns)
        val s2c = LinkProtocol.phoneToScreenKey(key, nc, ns)
        return SecureChannel(c2s, s2c) to SecureChannel(s2c, c2s)
    }

    @Test
    fun channelRoundTripBothWays() {
        val (screen, phone) = channels()
        val info = JSONObject().put("t", "info").put("role", "screen").put("car", "Señor Lancer 日本").toString()
        assertEquals(info, phone.open(screen.seal(info)))
        assertEquals("""{"t":"ping"}""", screen.open(phone.seal("""{"t":"ping"}""")))
        assertEquals("""{"t":"pong"}""", phone.open(screen.seal("""{"t":"pong"}""")))
        // Mismo texto, otro contador: la línea cifrada cambia.
        assertFalse(screen.seal("x") == screen.seal("x"))
    }

    @Test
    fun channelFirstMessageMatchesAesGcmWithCounterZero() {
        // Calculado aparte con `cryptography` (AESGCM, nonce de 12 ceros, sin datos adicionales).
        val (screen, _) = channels()
        assertEquals("l5Qa10AAxQFdVo9sB7wBg4so/8TyJwkxVHqSzA==", screen.seal("""{"t":"ping"}"""))
    }

    @Test
    fun repeatedMessageFails() {
        val (screen, phone) = channels()
        val line = screen.seal("""{"t":"ping"}""")
        phone.open(line)
        assertThrows(Exception::class.java) { phone.open(line) }
    }

    @Test
    fun outOfOrderMessageFails() {
        val (screen, phone) = channels()
        val first = screen.seal("uno")
        val second = screen.seal("dos")
        assertThrows(Exception::class.java) { phone.open(second) }
        // Y de ahí en adelante ya nada descifra: la conexión se cierra.
        assertThrows(Exception::class.java) { phone.open(first) }
    }

    @Test
    fun tamperedOrForeignMessageFails() {
        val (screen, phone) = channels()
        val line = screen.seal("""{"t":"bye","reason":"unlink"}""")
        val bytes = Base64.decode(line).also { it[3] = (it[3].toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) { phone.open(Base64.encode(bytes)) }
        // Lo que manda un lado no lo abre ese mismo lado (cada dirección tiene su llave).
        val (other, _) = channels()
        assertThrows(Exception::class.java) { other.open(other.seal("eco")) }
        assertThrows(Exception::class.java) { channels().second.open("no es base64!") }
    }

    /** Lee el texto del QR como lo haría el celular. */
    private fun parse(text: String): Map<String, String> {
        val uri = URI(text)
        assertEquals("litedash", uri.scheme)
        assertEquals("link", uri.host)
        return uri.rawQuery.split('&').associate { part ->
            // URLDecoder cambia "+" por espacio: el QR nunca lleva "+" (va como %2B).
            assertFalse(part.contains('+'))
            part.substringBefore('=') to URLDecoder.decode(part.substringAfter('='), "UTF-8")
        }
    }

    @Test
    fun qrTextOfTheVector() {
        val text = LinkProtocol.qrText("0011223344556677", key, "Lancer Evolution X", "SRNUBI")
        assertEquals("litedash://link?v=1&id=0011223344556677&k=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8&n=Lancer%20Evolution%20X&s=SRNUBI", text)
        assertEquals(mapOf("v" to "1", "id" to "0011223344556677", "k" to "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", "n" to "Lancer Evolution X", "s" to "SRNUBI"), parse(text))
    }

    @Test
    fun qrTextEscapesAndTrims() {
        val fields = parse(LinkProtocol.qrText("00aa", key, "Mi Jetta+ñ & tú=1 / 100%", "sdk_gphone64 arm64"))
        assertEquals("Mi Jetta+ñ & tú=1 / 100%", fields["n"])
        assertEquals("sdk_gphone64 arm64", fields["s"])
        // Lo opcional vacío no viaja; lo largo se recorta a 40 y 30 caracteres.
        val bare = parse(LinkProtocol.qrText("00aa", key, "", ""))
        assertEquals(setOf("v", "id", "k"), bare.keys)
        val long = parse(LinkProtocol.qrText("00aa", key, "a".repeat(60), "b".repeat(60)))
        assertEquals("a".repeat(40), long["n"])
        assertEquals("b".repeat(30), long["s"])
    }

    @Test
    fun qrTextAlwaysFitsTheCode() {
        // Con acentos cada letra pesa 6 bytes: primero se va la pantalla y luego el auto.
        val both = LinkProtocol.qrText("0011223344556677", key, "ñ".repeat(12), "é".repeat(10))
        assertEquals(setOf("v", "id", "k", "n"), parse(both).keys)
        val none = LinkProtocol.qrText("0011223344556677", key, "日".repeat(40), "ñ".repeat(30))
        assertEquals(setOf("v", "id", "k"), parse(none).keys)
        for (text in listOf(both, none)) assertTrue(QrCode.encode(text.toByteArray()) != null)
    }

    @Test
    fun lineReaderSplitsAndLimits() {
        val reader = LineReader(ByteArrayInputStream("uno\ndos y más ñ\n\nsin fin".toByteArray()))
        assertEquals("uno", reader.readLine(1024))
        assertEquals("dos y más ñ", reader.readLine(1024))
        assertEquals("", reader.readLine(1024))
        // El otro lado cerró a media línea.
        assertNull(reader.readLine(1024))
        assertThrows(java.io.IOException::class.java) { LineReader(ByteArrayInputStream(ByteArray(2000) { 'a'.code.toByte() })).readLine(1024) }
        assertEquals("a".repeat(1024), LineReader(ByteArrayInputStream(("a".repeat(1024) + "\n").toByteArray())).readLine(1024))
    }
}
