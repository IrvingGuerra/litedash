package com.irvingguerra.litedash.feature.link

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * El codificador QR contra matrices de referencia: módulo por módulo, con la máscara que cada
 * quien eligió sola (así se prueba también la penalización). Las referencias salen de qrcodegen
 * 1.8.0 (Project Nayuki) y coinciden con las de python-qrcode con esa misma máscara.
 */
class QrCodeTest {
    /** Versión esperada y la matriz por renglones en hexadecimal (1 = oscuro). */
    private class Reference(val version: Int, val hex: String)

    /** Bytes de 14 a 213 (lo que cabe en cada versión, de la 1 a la 10), igual que en el generador. */
    private fun filler(n: Int) = ByteArray(n) { (33 + (it * 7 + n) % 90).toByte() }

    private val capacity = intArrayOf(14, 26, 42, 62, 84, 106, 122, 152, 180, 213)

    private val inputs: Map<String, ByteArray> = mapOf(
        "hello" to "LiteDash".toByteArray(),
        "link" to LINK.toByteArray(),
        "utf8" to "Señor Ñandú çé 日本".toByteArray(),
    ) + capacity.mapIndexed { i, n -> "v${i + 1}" to filler(n) }

    private fun hex(qr: QrMatrix): String {
        val bits = buildString { for (y in 0 until qr.size) for (x in 0 until qr.size) append(if (qr[x, y]) '1' else '0') }
        return BigInteger(bits, 2).toString(16).padStart((bits.length + 3) / 4, '0')
    }

    @Test
    fun matchesReferenceMatrices() {
        assertEquals(inputs.keys, references.keys)
        for ((name, data) in inputs) {
            val reference = references.getValue(name)
            val qr = QrCode.encode(data)
            assertNotNull(name, qr)
            assertEquals("$name: versión", reference.version * 4 + 17, qr!!.size)
            assertEquals("$name: matriz", reference.hex, hex(qr))
        }
    }

    @Test
    fun picksTheSmallestVersionThatFits() {
        for ((i, n) in capacity.withIndex()) {
            assertEquals((i + 1) * 4 + 17, QrCode.encode(ByteArray(n))!!.size)
            if (i < capacity.lastIndex) assertEquals((i + 2) * 4 + 17, QrCode.encode(ByteArray(n + 1))!!.size)
        }
        assertEquals(21, QrCode.encode(ByteArray(0))!!.size)
    }

    @Test
    fun tooLongDoesNotFit() {
        assertNotNull(QrCode.encode(ByteArray(QrCode.MAX_BYTES)))
        assertNull(QrCode.encode(ByteArray(QrCode.MAX_BYTES + 1)))
    }

    private val references = mapOf(
        "hello" to Reference(1, "1fc47f824a0dd7176eaabb7535d82ea0ff55fc026017c17c48eb8d1383b238397422b009ee7f351a0af9975d21bac9e5d4a3208059fd686"),
        "link" to Reference(7, "1fc7b6a9d97f826222a1d20dd5af84b6976eb15e3986bb75671f3d75d82fc28e3820ff55555555fc02a7a369c017c597f62d7ce90e61c7dd6a14c53d8bd18974e6038748e48e5e4ec4b24eed85b097b90054523b98c379c32275cdf10480ae8ac867dcd30da135f62ce4589260e57ca12e7fd0df8a1f388a308c7989e542ad69dd73625862a0a3e1f39bfaa7f818ccb246556df2ef6947a1b012b836998e35d343e243a8c396753440c11de55e1a19a7c09eb379e9e93a80ac9481afeace13605376692c4f9e7893a0a5c935e23fce9f800c9a8cf18fff0615627d520bd7632ca3f750f1f2e5f0bae2f89f027dd6dae317c3a09ed7208909fd7b43c632a"),
        "utf8" to Reference(2, "1fcd37f8254a0dd43576eb34bb75385d82c0a0ff555fc03a8017cda7c215194ebb019931159e04dee7e5e80c4da7ace7690056c71fd009a8bff1f55e0a1e217569f4baa8e5dd6d8f608dd93fddd4f"),
        "v1" to Reference(1, "1fd47f82da0dd6976e84bb75f5d827a0ff55fc012013fd970d52771fd27d437f5d6a000e357f757a0a19975f2ebad7a5d2e7e08efffdb64"),
        "v2" to Reference(2, "1fc587f82fda0dd09576e9debb75785d825b20ff555fc01b801544b12e535a73d0e0bef791c6ce396d40f1e5439475d533ea337cbfc00a688ff10d6608b627753dfcba245a5d45cc609da85fd3d33"),
        "v3" to Reference(3, "1fc6c27f8211220dd590976eb6fcbb75c0a5d82b1aa0ff5555fc03c0c017c24c7c2468559d7aba000a3ee6504606127791c3cc47ebe5b37202f763687226a1b2f8e5f898252856c0b35f473ff008688d7f19cd620a44e2775b01f7baa0d11dd6eb05a09483e5fd5eac4"),
        "v4" to Reference(4, "1fce5227f8209e820dd5d4f576ea0906bb75f3ff5d82b744a0ff55555fc0320b0017cf8347cf484f4779758b0541800497bdf4119180286c0fa0a96dc529827003c8ed476143b2974b075b78fd60b265241efb658bbe89ecca492541cc8172ab2b6f33b36bc25f70088d68eff08925520aa8d62f75b0cdf0bab42cdfdd7df7c8209be7c99fd6703a6"),
        "v5" to Reference(5, "1fc54efc7f82d4b5920dd13589d76e82fcc4bb75898ab5d8265fb5a0ff555555fc0189b180154bd9d112a1e76e7a5e9334151b7373fb18e725cd3bff5a54a7ee076d9b04157e46b7593b7063dce0c891f79ea27bb03061455b66f5978045dbbccd7f87c59ee4a7925060d7202dd7a13ac36e0249041a5ca62cbd87061ed924bd7e255db08e3ff00a085988ff17172d6609551f2357574c39faba7218d105d623720ce088b1f665fd32ad12f"),
        "v6" to Reference(6, "1fcf122367f826aecfe20dd6cd34c576ebfdc8babb75fcac0f5d82ad2652a0ff5555555fc0320dc64017cf9fcba7c0192ba6199251ca56f26124c4148c000fbed7483c312435197b1d2ed55d193917cde26555fd0e55d11557b554798cf03921b438d65c52abf357fa167e02d51bba619eb93ac74d109205652ea964d39d5483b9d23fc05fb5b5f63297c034e2dc6526f730e57f1d85671147f8c160a12946a01d836cf117ce1c65ff000f496228aff11d55dd5a0b8cbe26357583504df9ba98fc2089dd74100a8f2083d3a22d5fd38276e28"),
        "v7" to Reference(7, "1fd5aaa8d97f829ff774d20dd1dd77a0976ea7bc4d56bb745fbf41f5d825978eb420ff55555555fc034ea334a016e879fb6d4bf8108ec3dc6358aec291cfd359b08579d1cfa5ceca9000d0e80b8c3def4b66969b2003520781dcf95aea2639e628f93704f04b305d4d75203318265ef4a1bfaebf545fb68de2887588ad6b05453d6e63cca30fa33dfbb5fccdf7e12ac6e888283ed5c5800e384b8dbb3dda26d6892ffe4e18bb90c75b6db174844ad87392b4940534d0ab67244fa048d7ce1648544d548fd05e25e64946f336a07f6e5f000e888ac48bff5975559d720b60628aa39748f1f299f0baa8c6d4a8cdd75a011941a09d6bda61f3fd87c638124"),
        "v8" to Reference(8, "1fcb874d4c17f825901a253a0dd06b390e8d76e8b95d1d84bb74e437eca85d82bdbe383e20ff555555555fc0129a8df62012d4947f812a0601f850089598f9e152df707db5deb2f026877e001171367c2472524dadddb91a9b5484e0511e7d71be862570d35efb0761d008d241a01230c930d21ccdc167fe228daab6f40bf4dd83d5722299504427e3b9f191fdee2a668812e20f528557dfb51a8abb63034883febb7ffe0fc4717d0deaf1c51d8d722fb14735470f1d836d355993f1651bfe601a5b393ec9dad707c5532e002d43910a211bd021d7fdee46d82e1d5980de3a56f5312a61b2366164b423be2fa10e335c6cc7d9f88c1c68b7fe739fc00d8ee2cd3897f268959cfd7e0a5978a6ea2974e377fc55f0bad1b3e8409e5d167061912c20886f2d44661fd0a67b9bc33"),
        "v9" to Reference(9, "1fc60637cf5c7f827fa8e966b20dd10d721546976e851e33098abb74ae6bff2fc5d8299868934e20ff5555555555fc0032e633eba012d40adf8d75a0e9d0adddd8107a19a1d1a94089050dc41d1b73c9a4c94499d7ab9a8ba9925b406c6158ad2e667637e57ee56ca63631656698958ff4d1935b2c8208b0728736689e63e579eb14c491b53e286e465d2d2489c0e109841607d62ad82bc9aab449b4cdd262d1f17bdfb3d1f508b07b893198b7d6d4dd51f0d4de2811a3bf16369f8099fd367f8950fedac5bd67c933f505420354e77198abbce97561b67a85f9cfb155c205010d2f190b1267af622a2d4b39023b4d2d920471df452509c7474a51c62dcabeaa86b1c46049478537ee36cf4fbf5d82763d8192dd9b9f66f1167203de9bd84250af381b5224fbe5fad15f000e356898448bff290e5780054a0bb3b620d563b74c5e5f7b7bf8ba9d4e40700dc5d0296f7e5697208ecb6a660015fd314bcffac7a"),
        "v10" to Reference(10, "1fdd2d357fe667f820aab0237a520dd5397b9a41f976e8c4321155d4bb7451cdfd55ca5d829fec6216a220ff55555555555fc0094fd8ae52a01460687fdf99a25c4cde34848c84a85ed6c5e14b2a95e145ac11c71c822d7d6e9d57515aec61795555dd1e675d47f33da32a601be2123e2bdb41f285bf3fdb8bda045ad244ccc4f039a3a5aac5ae567709982a72a2529445a21d575019b9f7823111d919a38ad0baa04a26ef69cd9125380760cc026d3dd989dbc7a4f4c488449cfd7e85f72c3fd763529f8de9d23235f207d535515b6894f3635dd88d5fc66f9f63d67e423f7072f4bf606ffa272a3dffadbbd7b3500cc45d573078e57794b112e5c0f3de7272912d393f4931574fc5f23d2759dc2523a254a9ea1ebf782df9ad4ba4cd6acd32ec9dfda48a03e84128cc0539532a355e78491ee766dd9f036ef1cd949111157797b153ad375994b3d38762ab36853f7e49fbd9d215d9607bd447dbddbfa00d99062c4418f7f4d9e352b1cd5609d7148e9a7e217492697f1335f0ba5aa17fddd4be5d4cb3be1e4d57e08e991f6b4f971fd84b8f1bdde65"),
    )

    private companion object {
        const val LINK = "litedash://link?v=1&id=0011223344556677&k=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8&n=Lancer%20Evolution%20X&s=SRNUBI"
    }
}
