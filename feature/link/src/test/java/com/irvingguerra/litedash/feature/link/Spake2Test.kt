package com.irvingguerra.litedash.feature.link

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class Spake2Test {
    private fun hex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    /** El primer vector del RFC 9382, apéndice B (P256-SHA256-HKDF-HMAC, A = «server», B = «client»). */
    @Test fun matchesTheRfcVector() {
        val w = BigInteger("2ee57912099d31560b3a44b1184b9b4866e904c49d12ac5042c97dca461b1a5f", 16)
        val x = BigInteger("43dd0fd7215bdcb482879fca3220c6a968e66d70b1356cac18bb26c84a78d729", 16)
        val y = BigInteger("dcb60106f276b02606d8ef0a328c02e4b629f84f89786af5befb0bc75b6e66be", 16)
        val a = Spake2(phone = true, w, "server".toByteArray(), "client".toByteArray(), x)
        val b = Spake2(phone = false, w, "server".toByteArray(), "client".toByteArray(), y)
        assertEquals(
            "04a56fa807caaa53a4d28dbb9853b9815c61a411118a6fe516a8798434751470f9010153ac33d0d5f2047ffdb1a3e42c9b4e6be662766e1eeb4116988ede5f912c",
            hex(a.share),
        )
        assertEquals(
            "0406557e482bd03097ad0cbaa5df82115460d951e3451962f1eaf4367a420676d09857ccbc522686c83d1852abfa8ed6e4a1155cf8f1543ceca528afb591a1e0b7",
            hex(b.share),
        )
        for (keys in listOf(a.keys(b.share)!!, b.keys(a.share)!!)) {
            assertEquals("0e0672dc86f8e45565d338b0540abe69", hex(keys.shared))
            assertEquals("58ad4aa88e0b60d5061eb6b5dd93e80d9c4f00d127c65b3b35b1b5281fee38f0", hex(keys.phoneProof))
            assertEquals("d3e2e547f1ae04f2dbdbf0fc4b79f8ecff2dff314b5d32fe9fcef2fb26dc459b", hex(keys.screenProof))
        }
    }

    @Test fun sameCodeGivesTheSameKeyAndAnotherCodeDoesNot() {
        val id = "0123456789abcdef".toByteArray()
        val who = "LD-LINK-1 phone".toByteArray()
        val bound = "Lancer\u0000SRNUBI".toByteArray()
        val phone = Spake2(true, Spake2.secretOf("483921"), who, id)
        val screen = Spake2(false, Spake2.secretOf("483921"), who, id)
        val p = phone.keys(screen.share, bound)!!
        val s = screen.keys(phone.share, bound)!!
        assertArrayEquals(p.shared, s.shared)
        assertArrayEquals(p.phoneProof, s.phoneProof)
        assertArrayEquals(p.screenProof, s.screenProof)
        assertEquals(32, Spake2.linkKey(p.shared).size)
        // Otro código: ninguna prueba coincide.
        val wrong = Spake2(true, Spake2.secretOf("483922"), who, id)
        val guess = wrong.keys(screen.share, bound)!!
        val seen = screen.keys(wrong.share, bound)!!
        assertFalse(guess.screenProof.contentEquals(seen.screenProof))
        assertFalse(guess.phoneProof.contentEquals(seen.phoneProof))
        // Lo que viajó en claro (auto y pantalla) cambiado en el camino: tampoco.
        assertFalse(phone.keys(screen.share, "Otro\u0000SRNUBI".toByteArray())!!.screenProof.contentEquals(s.screenProof))
    }

    @Test fun rejectsWhatIsNotAPointOfTheCurve() {
        val side = Spake2(true, Spake2.secretOf("000000"), ByteArray(0), ByteArray(0))
        assertNull(side.keys(ByteArray(65)))
        assertNull(side.keys(ByteArray(64) { 1 }))
        assertNull(side.keys(side.share.copyOf().also { it[64] = (it[64] + 1).toByte() }))
        assertNotNull(side.keys(Spake2(false, Spake2.secretOf("000000"), ByteArray(0), ByteArray(0)).share))
    }

    @Test fun codesAreSixDigits() {
        repeat(50) { assertTrue(Regex("[0-9]{6}").matches(Spake2.newCode())) }
    }
}
