package com.irvingguerra.litedash.feature.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** P1 de los perfiles de Bluetooth: mover a perfiles no cambia la lista, su orden, sus familias ni las recetas. */
class FactoryBluetoothTest {

    /** La lista `BRAND_BLUETOOTH` tal como estaba en la 1.5.26 (paquete, familia, toca la música). */
    private val before = listOf(
        Triple("com.syu.bt", HeadUnit.FYT, true),
        Triple("com.syu.ms", HeadUnit.FYT, false),
        Triple("com.fyt.bt", HeadUnit.FYT, false),
        Triple("com.tw.bt", HeadUnit.TOPWAY, true),
        Triple("com.tw.service", HeadUnit.TOPWAY, false),
        Triple("com.ts.bt", HeadUnit.TOPWAY, false),
        Triple("com.microntek.bt", HeadUnit.MICRONTEK, true),
        Triple("com.clw.btmusic", HeadUnit.CLINKWAY, true),
        Triple("com.goodocom.gocsdk", HeadUnit.GENERIC, false),
        Triple("com.autochips.bluetooth", HeadUnit.AUTOCHIPS, true),
        Triple("com.yftech.service.bluetooth", HeadUnit.GENERIC, false),
        Triple("com.nwd.bt.music", HeadUnit.NWD, true),
        Triple("com.jancar.bluetooth", HeadUnit.JANCAR, true),
        Triple("com.reglink.apps.bluetooth", HeadUnit.REGLINK, true),
    )

    @Test
    fun theFlattenedProfilesAreTheOldListInTheSameOrder() {
        assertEquals(before, BRAND_BLUETOOTH.map { Triple(it.pkg, it.family, it.music) })
        assertEquals(before.map { it.first }, HeadUnit.bluetoothApps)
        assertEquals(before.filter { it.third }.map { it.first }, HeadUnit.bluetoothMusicApps)
    }

    @Test
    fun eachFamilyKeepsItsRecipes() {
        // El motor pedía headUnit.bluetoothRecipes; ahora pide el perfil de su familia: debe dar lo mismo en todas.
        for (family in HeadUnit.entries) assertEquals(family.name, family.bluetoothRecipes, FactoryBluetoothKind.forFamily(family).recipes)
    }

    @Test
    fun appsBelongToTheirProfile() {
        for (app in BRAND_BLUETOOTH) assertEquals(app.pkg, app.family, FactoryBluetoothKind.ofPackage(app.pkg)?.family)
        assertSame(AutochipsBluetooth, FactoryBluetoothKind.ofPackage("com.autochips.bluetooth"))
        assertSame(GenericBluetooth, FactoryBluetoothKind.forFamily(HeadUnit.HCN))
        assertNull(FactoryBluetoothKind.ofPackage("com.android.bluetooth"))
    }

    @Test
    fun androidAvrcpWinsThenTheLinkedAppThenTheBrandOne() {
        // Fijado antes de mover marcas (inventario P0, Q20): no cambiar sin OK de Irving.
        assertEquals("com.android.bluetooth", bluetoothAppOf("com.android.bluetooth", "com.spd.bt", "com.tw.bt"))
        assertEquals("com.spd.bt", bluetoothAppOf(null, "com.spd.bt", "com.tw.bt"))
        assertEquals("com.tw.bt", bluetoothAppOf(null, null, "com.tw.bt"))
        assertNull(bluetoothAppOf(null, null, null))
    }

    @Test
    fun recipesOfEveryFamilyAreTheOnesOfTheEnumBeforeP2() {
        // Fijado antes de pasar las recetas propias de TopWay y FYT del enum HeadUnit al perfil (P2): ids y orden de la
        // 1.5.26 (van en estadísticas y en el catálogo; FactoryAudio.countedRecipe los usa).
        val base = listOf(Recipes.PLAIN, Recipes.OPEN_FACTORY)
        val own = mapOf(HeadUnit.TOPWAY to listOf(Recipes.TW_SOURCE8), HeadUnit.FYT to listOf(Recipes.FYT_TOGGLE))
        for (family in HeadUnit.entries) assertEquals(family.name, own[family].orEmpty() + base, family.bluetoothRecipes)
        for (kind in FactoryBluetoothKind.all + GenericBluetooth) assertEquals(kind.id, own[kind.family].orEmpty() + base, kind.recipes)
    }
}
