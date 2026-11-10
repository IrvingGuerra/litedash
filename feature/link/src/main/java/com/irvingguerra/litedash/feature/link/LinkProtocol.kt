package com.irvingguerra.litedash.feature.link

import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * Link v1: lo que no toca Android (llaves, saludo, canal cifrado y el texto del QR), para poder
 * probarlo en la computadora. La especificación está en PROTOCOLO_LINK.md; la pantalla es el cliente.
 */

internal object LinkProtocol {
    const val VERSION = 1
    const val PORT = 47600
    const val SERVICE_TYPE = "_litedash-link._tcp"
    const val KEY_BYTES = 32
    const val NONCE_BYTES = 16

    /** Línea más larga en el saludo y ya cifrado. */
    const val HELLO_LINE_MAX = 1024
    const val LINE_MAX = 256 * 1024

    private const val CAR_MAX = 40
    private const val SCREEN_MAX = 30

    private fun hmac(key: ByteArray, label: String, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        mac.update(label.toByteArray(Charsets.US_ASCII))
        for (part in parts) mac.update(part)
        return mac.doFinal()
    }

    /** Lo que el celular manda para probar que tiene la llave. */
    fun phoneMac(key: ByteArray, nc: ByteArray, ns: ByteArray) = hmac(key, "LD-LINK-1 srv", nc, ns)

    /** Lo que la pantalla contesta. */
    fun screenMac(key: ByteArray, nc: ByteArray, ns: ByteArray) = hmac(key, "LD-LINK-1 cli", nc, ns)

    /** Llave de sesión de la pantalla al celular. */
    fun screenToPhoneKey(key: ByteArray, nc: ByteArray, ns: ByteArray) = hmac(key, "LD-LINK-1 c2s", nc, ns)

    /** Llave de sesión del celular a la pantalla. */
    fun phoneToScreenKey(key: ByteArray, nc: ByteArray, ns: ByteArray) = hmac(key, "LD-LINK-1 s2c", nc, ns)

    /** Compara en tiempo constante. */
    fun sameMac(a: ByteArray, b: ByteArray) = MessageDigest.isEqual(a, b)

    /** Los 4 dígitos que se ven en los dos lados al vincular (no viajan). */
    fun code(key: ByteArray): String {
        val h = hmac(key, "LD-LINK-1 code")
        val number = ((h[0].toLong() and 0xFF) shl 24) or ((h[1].toLong() and 0xFF) shl 16) or
            ((h[2].toLong() and 0xFF) shl 8) or (h[3].toLong() and 0xFF)
        return (number % 10000).toString().padStart(4, '0')
    }

    /**
     * El texto del QR. El nombre del auto y el modelo de la pantalla son opcionales: se recortan a
     * su máximo y, si con ellos no cabe en el QR, se quita primero la pantalla y luego el auto.
     */
    fun qrText(screenId: String, key: ByteArray, car: String, screen: String): String {
        val base = "litedash://link?v=$VERSION&id=$screenId&k=${Base64.url(key)}"
        val n = percent(car.take(CAR_MAX)).let { if (it.isEmpty()) "" else "&n=$it" }
        val s = percent(screen.take(SCREEN_MAX)).let { if (it.isEmpty()) "" else "&s=$it" }
        return listOf(base + n + s, base + n, base).first { it.length <= QrCode.MAX_BYTES }
    }

    /** Percent-encoding de UTF-8: solo quedan tal cual las letras, los números y `-._~`. */
    private fun percent(text: String) = buildString {
        for (b in text.toByteArray(Charsets.UTF_8)) {
            val c = (b.toInt() and 0xFF).toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "-._~") append(c)
            else append('%').append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
        }
    }

    private const val HEX = "0123456789ABCDEF"
}

/**
 * El canal después del saludo: cada línea es base64(AES-256-GCM). El nonce son 4 ceros y un
 * contador de 8 bytes que sube 1 por mensaje y por dirección; un mensaje repetido, perdido o
 * fuera de orden no descifra ([open] lanza) y la conexión se cierra.
 */
internal class SecureChannel(sendKey: ByteArray, receiveKey: ByteArray) {
    private val sendKey = SecretKeySpec(sendKey, "AES")
    private val receiveKey = SecretKeySpec(receiveKey, "AES")
    private var sent = 0L
    private var received = 0L

    fun seal(json: String): String = Base64.encode(crypt(Cipher.ENCRYPT_MODE, sendKey, sent++, json.toByteArray(Charsets.UTF_8)))

    /** Lanza si la línea no es base64 o no descifra con el contador que toca. */
    fun open(line: String): String = String(crypt(Cipher.DECRYPT_MODE, receiveKey, received++, Base64.decode(line)), Charsets.UTF_8)

    private fun crypt(mode: Int, key: SecretKeySpec, counter: Long, data: ByteArray): ByteArray {
        val nonce = ByteArray(12)
        for (i in 0 until 8) nonce[11 - i] = (counter ushr (8 * i)).toByte()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, key, GCMParameterSpec(128, nonce))
        return cipher.doFinal(data)
    }
}

/** Lee líneas terminadas en `\n` sin perder lo ya leído si la espera se agota a media línea. */
internal class LineReader(private val input: InputStream) {
    private var buffer = ByteArray(256)
    private var length = 0

    /** La siguiente línea (sin el salto); null si el otro lado cerró. Lanza si pasa de [max] bytes. */
    fun readLine(max: Int): String? {
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (b == '\n'.code) return String(buffer, 0, length, Charsets.UTF_8).also { length = 0 }
            if (length >= max) throw IOException("line too long")
            if (length == buffer.size) buffer = buffer.copyOf(length * 2)
            buffer[length++] = b.toByte()
        }
    }
}

/** Base64 propio: `java.util.Base64` no existe en Android 6 y el de Android no corre en las pruebas. */
internal object Base64 {
    private const val STANDARD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private const val URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** Estándar, con relleno y sin saltos. */
    fun encode(data: ByteArray) = encode(data, STANDARD, pad = true)

    /** Para URL (`-` y `_`), sin relleno. */
    fun url(data: ByteArray) = encode(data, URL, pad = false)

    private fun encode(data: ByteArray, alphabet: String, pad: Boolean) = buildString {
        for (i in data.indices step 3) {
            val left = data.size - i
            val n = ((data[i].toInt() and 0xFF) shl 16) or
                (if (left > 1) (data[i + 1].toInt() and 0xFF) shl 8 else 0) or
                (if (left > 2) data[i + 2].toInt() and 0xFF else 0)
            append(alphabet[n ushr 18]).append(alphabet[(n ushr 12) and 63])
            if (left > 1) append(alphabet[(n ushr 6) and 63]) else if (pad) append('=')
            if (left > 2) append(alphabet[n and 63]) else if (pad) append('=')
        }
    }

    /** Estándar, con o sin relleno. Lanza si trae algo que no es base64. */
    fun decode(text: String): ByteArray {
        val clean = text.trimEnd('=')
        val out = ByteArray(clean.length * 3 / 4)
        var bits = 0
        var count = 0
        var k = 0
        for (c in clean) {
            val value = STANDARD.indexOf(c)
            require(value >= 0) { "not base64" }
            bits = (bits shl 6) or value
            count += 6
            if (count >= 8) {
                count -= 8
                out[k++] = (bits ushr count).toByte()
            }
        }
        return out
    }
}
