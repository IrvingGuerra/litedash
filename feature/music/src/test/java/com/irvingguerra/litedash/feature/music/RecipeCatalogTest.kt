package com.irvingguerra.litedash.feature.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecipeCatalogTest {
    private val json = """
        {"version":1,"recipes":[
          {"bluetooth":"com.tw.bt","bt":"tw.source8","fm":"open.factory"},
          {"bluetooth":"com.tw.bt","radio":"com.tw.radio","fm":"profile"},
          {"bluetooth":"com.nwd.bt.music","radio":"com.nwd.radio","bt":"open.factory"},
          {"bluetooth":"com.jancar.bluetooth","bt":"teleport","fm":"profile"}
        ]}
    """.trimIndent()

    @Test
    fun entryWithRadioWinsAndTheOtherFillsTheRest() {
        assertEquals(CatalogRecipe(Recipes.TW_SOURCE8, Recipes.PROFILE), RecipeCatalog.match(json, "com.tw.bt", "com.tw.radio"))
    }

    @Test
    fun entryWithoutRadioFitsAnyRadio() {
        assertEquals(CatalogRecipe(Recipes.TW_SOURCE8, Recipes.OPEN_FACTORY), RecipeCatalog.match(json, "com.tw.bt", FactoryAudio.NONE))
    }

    @Test
    fun radioMustMatchWhenGiven() {
        assertNull(RecipeCatalog.match(json, "com.nwd.bt.music", "com.hcn.autoradio"))
        assertEquals(CatalogRecipe(Recipes.OPEN_FACTORY, null), RecipeCatalog.match(json, "com.nwd.bt.music", "com.nwd.radio"))
    }

    @Test
    fun unknownIdsAreIgnored() {
        assertEquals(CatalogRecipe(null, Recipes.PROFILE), RecipeCatalog.match(json, "com.jancar.bluetooth", "com.jancar.radio"))
    }

    @Test
    fun otherScreensAndBadFilesGiveNothing() {
        assertNull(RecipeCatalog.match(json, FactoryAudio.ANDROID, FactoryAudio.NONE))
        assertNull(RecipeCatalog.match("""{"version":2,"recipes":[{"bluetooth":"com.tw.bt","bt":"plain"}]}""", "com.tw.bt", "x"))
        assertNull(RecipeCatalog.match("no es json", "com.tw.bt", "x"))
    }
}
