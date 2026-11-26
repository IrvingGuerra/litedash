package com.irvingguerra.litedash.feature.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothExportTest {
    /** Paquetes reales de apps de Bluetooth de fábrica vistos en reportes (#49 y estadísticas). */
    private val factory = listOf("com.spd.bt", "com.imotor.btmusic", "com.carsyso.bluetooth", "com.qf.bluetooth", "android.car.app.bt", "com.autochips.bluetooth")
    private val noise = listOf("com.android.bluetooth", "com.android.bluetoothmidiservice", "com.google.android.gms", "com.google.android.bluetooth", "com.android.settings")

    private fun pick(installed: Map<String, Boolean>, known: List<String?> = emptyList(), includeAndroid: Boolean = false) =
        BluetoothExport.pickCandidates(installed, known, HeadUnit.bluetoothApps, includeAndroid)

    @Test
    fun picksRealFactoryBluetoothApps() {
        val installed = (factory + noise).associateWith { true } + ("com.spotify.music" to false)
        val picked = pick(installed)
        assertEquals(factory.toSet(), picked.toSet())
        for (p in noise + "com.spotify.music") assertFalse(p, p in picked)
    }

    @Test
    fun neverPicksUserAppsByName() {
        val picked = pick(mapOf("com.someone.btmusic" to false, "com.spd.bt" to true))
        assertEquals(listOf("com.spd.bt"), picked)
    }

    @Test
    fun knownBrandAppEntersEvenOutsideTheSystem() {
        // Hay pantallas que traen com.tw.bt en /data/app (ver HeadUnit.of).
        assertEquals(listOf("com.tw.bt"), pick(mapOf("com.tw.bt" to false)))
    }

    @Test
    fun linkedAppGoesFirstAndMusicBeforeServices() {
        val installed = mapOf("com.goodocom.gocsdk" to true, "com.qf.bluetooth" to true, "com.imotor.btmusic" to true, "com.vendor.a2dpsink" to true)
        val picked = pick(installed, known = listOf("com.qf.bluetooth", null))
        assertEquals("com.qf.bluetooth", picked.first())
        assertTrue(picked.indexOf("com.imotor.btmusic") < picked.indexOf("com.vendor.a2dpsink"))
        assertTrue("com.vendor.a2dpsink" in picked)
    }

    @Test
    fun linkedUserAppIsNotExported() {
        assertEquals(emptyList<String>(), pick(mapOf("com.spotify.music" to false), known = listOf("com.spotify.music")))
    }

    @Test
    fun atMostSixApps() {
        val installed = (1..10).associate { "com.vendor$it.bluetooth" to true }
        assertEquals(6, pick(installed).size)
    }

    @Test
    fun androidBluetoothOnlyForDebugTesting() {
        val installed = mapOf("com.android.bluetooth" to true)
        assertEquals(emptyList<String>(), pick(installed, known = listOf("com.android.bluetooth")))
        assertEquals(listOf("com.android.bluetooth"), pick(installed, includeAndroid = true))
    }
}
