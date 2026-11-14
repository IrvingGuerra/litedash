package com.irvingguerra.litedash.feature.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadUnitTest {
    private fun detect(vararg installed: String) = HeadUnit.detect { it in installed }

    @Test
    fun familiesFromRealFingerprints() {
        // Lancer de Irving (SRNUBI) y los reportes 5ED058 / 6C069A.
        assertEquals(HeadUnit.TOPWAY, detect("com.tw.bt", "com.tw.radio"))
        assertEquals(HeadUnit.FYT, detect("com.syu.bt"))
        assertEquals(HeadUnit.NWD, detect("com.nwd.bt.music"))
        assertEquals(HeadUnit.JANCAR, detect("com.jancar.bluetooth"))
        // Solo el Bluetooth de Android (sin apps de marca).
        assertEquals(HeadUnit.GENERIC, detect("com.android.bluetooth", "com.android.settings"))
        assertEquals(HeadUnit.GENERIC, detect())
    }

    @Test
    fun factoryBluetoothWinsOverRadioAndProjection() {
        // Una TopWay con ZLink sigue siendo TopWay; el Bluetooth de marca le gana a la radio de otra familia.
        assertEquals(HeadUnit.TOPWAY, detect("com.zjinnova.zlink", "com.tw.bt"))
        assertEquals(HeadUnit.AUTOCHIPS, detect("com.autochips.bluetooth", "com.nwd.radio"))
    }

    @Test
    fun radioOnlyDecidesWithoutBrandBluetooth() {
        assertEquals(HeadUnit.HCN, detect("com.hcn.autoradio"))
        assertEquals(HeadUnit.NWD, detect("com.nwd.radio", "com.nwd.kernel"))
        assertEquals(HeadUnit.TOPWAY, detect("com.tw.radio"))
    }

    @Test
    fun goodocomSaysChipButNotFamily() {
        assertEquals(HeadUnit.GENERIC, detect("com.goodocom.gocsdk"))
        // Con el módulo Goodocom y la radio NWD, la familia la dice la radio.
        assertEquals(HeadUnit.NWD, detect("com.goodocom.gocsdk", NwdRadio.PACKAGE))
        assertTrue("com.goodocom.gocsdk" in HeadUnit.bluetoothApps)
        assertTrue("com.goodocom.gocsdk" !in HeadUnit.bluetoothMusicApps)
    }

    @Test
    fun brandRecipeFirstThenNothingThenOpeningTheApp() {
        assertEquals(listOf(Recipes.TW_SOURCE8, Recipes.PLAIN, Recipes.OPEN_FACTORY), HeadUnit.TOPWAY.bluetoothRecipes)
        assertEquals(listOf(Recipes.FYT_TOGGLE, Recipes.PLAIN, Recipes.OPEN_FACTORY), HeadUnit.FYT.bluetoothRecipes)
        assertEquals(listOf(Recipes.PLAIN, Recipes.OPEN_FACTORY), HeadUnit.NWD.bluetoothRecipes)
        assertTrue(HeadUnit.TOPWAY.trustsSignal && HeadUnit.FYT.trustsSignal && !HeadUnit.NWD.trustsSignal)
    }

    @Test
    fun oneListKeepsTheOldOrder() {
        // Las dos listas de antes (KNOWN_BT y BRAND_BT_APPS) siguen en el mismo orden dentro de la nueva.
        val oldMusic = listOf("com.syu.bt", "com.tw.bt", "com.microntek.bt", "com.clw.btmusic", "com.autochips.bluetooth")
        val oldBrand = listOf(
            "com.syu.bt", "com.syu.ms", "com.fyt.bt", "com.tw.bt", "com.tw.service", "com.ts.bt",
            "com.microntek.bt", "com.goodocom.gocsdk", "com.autochips.bluetooth", "com.yftech.service.bluetooth",
        )
        assertEquals(oldMusic, HeadUnit.bluetoothMusicApps.filter { it in oldMusic })
        assertEquals(oldBrand, HeadUnit.bluetoothApps.filter { it in oldBrand })
        assertTrue(HeadUnit.bluetoothMusicApps.containsAll(listOf("com.nwd.bt.music", "com.jancar.bluetooth", "com.reglink.apps.bluetooth")))
    }
}
