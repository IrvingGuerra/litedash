package com.irvingguerra.litedash.feature.link

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Vincular con un código corto en vez del QR (PROTOCOLO_LINK.md §2b): SPAKE2 sobre P-256 con SHA-256, HKDF y HMAC, tal
 * como lo define el RFC 9382 (sus vectores están en la prueba). Con él, los dos lados sacan una llave larga de un código
 * de 6 dígitos sin que quien escucha la red (o se hace pasar por el otro lado) pueda probar más de UN código por
 * intento; por eso quien lo usa cuenta los intentos fallidos y cambia el código.
 *
 * El celular es «A» (usa el punto M) y la pantalla «B» (usa N). Este archivo es idéntico en LiteDash y en LiteDash
 * Connect (solo cambia el paquete): un cambio aquí va en los dos.
 */
internal class Spake2(
    private val phone: Boolean,
    private val w: BigInteger,
    private val idPhone: ByteArray,
    private val idScreen: ByteArray,
    scalar: BigInteger? = null,
) {
    /** Lo que da un intercambio que terminó: la llave compartida y lo que cada lado manda para probar que la tiene. */
    class Keys(val shared: ByteArray, val phoneProof: ByteArray, val screenProof: ByteArray)

    private val secret: BigInteger = scalar ?: randomScalar()

    /** Lo que este lado manda al otro (un punto de la curva sin comprimir, 65 bytes). */
    val share: ByteArray = encode(add(mul(G, secret), mul(if (phone) M else N, w)))

    /**
     * Las llaves, con lo que mandó el otro lado ([peerShare]) y los datos que viajaron en claro y deben quedar amarrados
     * ([bound]). null si [peerShare] no es un punto válido de la curva.
     */
    fun keys(peerShare: ByteArray, bound: ByteArray = ByteArray(0)): Keys? {
        val peer = decode(peerShare) ?: return null
        val point = mul(add(peer, negate(mul(if (phone) N else M, w))), secret)
        if (point.infinite) return null
        val shareA = if (phone) share else peerShare
        val shareB = if (phone) peerShare else share
        val transcript = prefixed(idPhone) + prefixed(idScreen) + prefixed(shareA) + prefixed(shareB) +
            prefixed(encode(point)) + prefixed(fixed(w))
        val hash = MessageDigest.getInstance("SHA-256").digest(transcript)
        val ke = hash.copyOfRange(0, 16)
        val ka = hash.copyOfRange(16, 32)
        // HKDF-SHA256 sin sal, un solo bloque (32 bytes): la mitad para cada prueba.
        val prk = hmac(ByteArray(32), ka)
        val okm = hmac(prk, "ConfirmationKeys".toByteArray(Charsets.US_ASCII) + bound + byteArrayOf(1))
        return Keys(ke, hmac(okm.copyOfRange(0, 16), transcript), hmac(okm.copyOfRange(16, 32), transcript))
    }

    /** Un punto en coordenadas de Jacobi (x = X/Z², y = Y/Z³); Z = 0 es el punto en el infinito. */
    internal class Point(val x: BigInteger, val y: BigInteger, val z: BigInteger) {
        val infinite get() = z.signum() == 0
    }

    companion object {
        private val P = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
        private val ORDER = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)
        private val B = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)
        private val THREE = BigInteger.valueOf(3)
        private val INFINITY = Point(BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
        private val G = affine(
            "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296",
            "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5",
        )

        // Los puntos M y N del RFC 9382 para P-256 (ahí van comprimidos; aquí con su «y» ya calculada).
        private val M = decompress("02886e2f97ace46e55ba9dd7242579f2993b64e16ef3dcab95afd497333d8fa12f")
        private val N = decompress("03d8bbd6c639c62937b04d997f38c3770719c629d7014d49a24b4f98baa1292b49")

        /** Un código nuevo de [digits] dígitos, parejo (sin sesgo). */
        fun newCode(digits: Int = CODE_DIGITS): String {
            val random = SecureRandom()
            return buildString { repeat(digits) { append(random.nextInt(10)) } }
        }

        const val CODE_DIGITS = 6

        /** El número secreto que sale del código que se teclea. */
        fun secretOf(code: String): BigInteger {
            val hash = MessageDigest.getInstance("SHA-256").digest(("LD-LINK-1 pair w$code").toByteArray(Charsets.US_ASCII))
            return BigInteger(1, hash).mod(ORDER.subtract(BigInteger.ONE)).add(BigInteger.ONE)
        }

        /** La llave de Link (32 bytes) que queda de un intercambio que terminó bien. */
        fun linkKey(shared: ByteArray): ByteArray = hmac(shared, "LD-LINK-1 pair key".toByteArray(Charsets.US_ASCII))

        private fun randomScalar(): BigInteger {
            val random = SecureRandom()
            while (true) {
                val k = BigInteger(256, random)
                if (k.signum() > 0 && k < ORDER) return k
            }
        }

        private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data)
        }

        /** [data] con su largo delante (8 bytes, el menos significativo primero), como pide el RFC. */
        private fun prefixed(data: ByteArray): ByteArray {
            val out = ByteArray(8 + data.size)
            for (i in 0 until 8) out[i] = (data.size.toLong() ushr (8 * i)).toByte()
            System.arraycopy(data, 0, out, 8, data.size)
            return out
        }

        /** [n] en 32 bytes, el más significativo primero. */
        private fun fixed(n: BigInteger): ByteArray {
            val raw = n.toByteArray()
            val out = ByteArray(32)
            val from = maxOf(0, raw.size - 32)
            System.arraycopy(raw, from, out, 32 - (raw.size - from), raw.size - from)
            return out
        }

        private fun affine(x: String, y: String) = Point(BigInteger(x, 16), BigInteger(y, 16), BigInteger.ONE)

        private fun onCurve(x: BigInteger, y: BigInteger) =
            y.multiply(y).mod(P) == x.multiply(x).multiply(x).subtract(THREE.multiply(x)).add(B).mod(P)

        private fun decompress(hex: String): Point {
            val x = BigInteger(hex.substring(2), 16)
            val square = x.multiply(x).multiply(x).subtract(THREE.multiply(x)).add(B).mod(P)
            // P ≡ 3 (mod 4): la raíz es square^((P+1)/4).
            var y = square.modPow(P.add(BigInteger.ONE).shiftRight(2), P)
            if (y.testBit(0) != (hex.substring(0, 2) == "03")) y = P.subtract(y)
            check(onCurve(x, y))
            return Point(x, y, BigInteger.ONE)
        }

        /** El punto sin comprimir: 04 ‖ x ‖ y. */
        internal fun encode(point: Point): ByteArray {
            val inverse = point.z.modInverse(P)
            val square = inverse.multiply(inverse).mod(P)
            val x = point.x.multiply(square).mod(P)
            val y = point.y.multiply(square).multiply(inverse).mod(P)
            return byteArrayOf(4) + fixed(x) + fixed(y)
        }

        /** null si no son 65 bytes de un punto de la curva (así no se acepta un punto tramposo). */
        internal fun decode(bytes: ByteArray): Point? {
            if (bytes.size != 65 || bytes[0] != 4.toByte()) return null
            val x = BigInteger(1, bytes.copyOfRange(1, 33))
            val y = BigInteger(1, bytes.copyOfRange(33, 65))
            if (x >= P || y >= P || !onCurve(x, y)) return null
            return Point(x, y, BigInteger.ONE)
        }

        private fun negate(p: Point) = if (p.infinite) p else Point(p.x, P.subtract(p.y).mod(P), p.z)

        private fun double(p: Point): Point {
            if (p.infinite || p.y.signum() == 0) return INFINITY
            val delta = p.z.multiply(p.z).mod(P)
            val gamma = p.y.multiply(p.y).mod(P)
            val beta = p.x.multiply(gamma).mod(P)
            val alpha = THREE.multiply(p.x.subtract(delta)).multiply(p.x.add(delta)).mod(P)
            val x = alpha.multiply(alpha).subtract(beta.shiftLeft(3)).mod(P)
            val z = p.y.add(p.z).pow(2).subtract(gamma).subtract(delta).mod(P)
            val y = alpha.multiply(beta.shiftLeft(2).subtract(x)).subtract(gamma.multiply(gamma).shiftLeft(3)).mod(P)
            return Point(x, y, z)
        }

        private fun add(p: Point, q: Point): Point {
            if (p.infinite) return q
            if (q.infinite) return p
            val z1z1 = p.z.multiply(p.z).mod(P)
            val z2z2 = q.z.multiply(q.z).mod(P)
            val u1 = p.x.multiply(z2z2).mod(P)
            val u2 = q.x.multiply(z1z1).mod(P)
            val s1 = p.y.multiply(q.z).multiply(z2z2).mod(P)
            val s2 = q.y.multiply(p.z).multiply(z1z1).mod(P)
            val h = u2.subtract(u1).mod(P)
            val r = s2.subtract(s1).mod(P)
            if (h.signum() == 0) return if (r.signum() == 0) double(p) else INFINITY
            val hh = h.multiply(h).mod(P)
            val hhh = h.multiply(hh).mod(P)
            val v = u1.multiply(hh).mod(P)
            val x = r.multiply(r).subtract(hhh).subtract(v.shiftLeft(1)).mod(P)
            val y = r.multiply(v.subtract(x)).subtract(s1.multiply(hhh)).mod(P)
            val z = p.z.multiply(q.z).multiply(h).mod(P)
            return Point(x, y, z)
        }

        private fun mul(p: Point, k: BigInteger): Point {
            var out = INFINITY
            for (i in k.bitLength() - 1 downTo 0) {
                out = double(out)
                if (k.testBit(i)) out = add(out, p)
            }
            return out
        }
    }
}
