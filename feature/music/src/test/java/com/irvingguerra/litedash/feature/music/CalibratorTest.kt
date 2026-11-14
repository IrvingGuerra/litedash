package com.irvingguerra.litedash.feature.music

import com.irvingguerra.litedash.feature.music.Calibrator.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibratorTest {
    private class Memory(override var saved: SavedRecipe? = null) : RecipeStore {
        override fun save(recipe: SavedRecipe?) {
            saved = recipe
        }
    }

    private val topWay = listOf(Recipes.PLAIN, Recipes.TW_SOURCE8, Recipes.OPEN_FACTORY)
    private val store = Memory()
    private var moving = false

    private fun calibrator(trusts: Boolean = false) = Calibrator(store, trusts) { moving }

    @Test
    fun plainThatSoundsIsSavedBySignal() {
        val c = calibrator()
        assertEquals(Step.Apply(Recipes.PLAIN), c.play(topWay, null))
        assertTrue(c.pending)
        assertEquals(Step.Done, c.signal(heard = true))
        assertEquals(SavedRecipe(Recipes.PLAIN, SavedBy.SIGNAL), store.saved)
        assertFalse(c.pending)
    }

    @Test
    fun askThenNextRecipeThenSavedBySignal() {
        val c = calibrator()
        c.play(topWay, null)
        assertEquals(Step.Ask, c.signal(heard = false))
        assertTrue(c.asking)
        assertEquals(Step.Apply(Recipes.TW_SOURCE8), c.answer(yes = false))
        assertEquals(Step.Done, c.signal(heard = true))
        assertEquals(SavedRecipe(Recipes.TW_SOURCE8, SavedBy.SIGNAL), store.saved)
    }

    @Test
    fun yesSavesByUser() {
        val c = calibrator()
        c.play(topWay, null)
        c.signal(heard = false)
        assertEquals(Step.Done, c.answer(yes = true))
        assertEquals(SavedRecipe(Recipes.PLAIN, SavedBy.USER), store.saved)
    }

    @Test
    fun nothingWorksSavesFailed() {
        val c = calibrator()
        c.play(topWay, null)
        for (expected in listOf(Recipes.TW_SOURCE8, Recipes.OPEN_FACTORY)) {
            assertEquals(Step.Ask, c.signal(heard = false))
            assertEquals(Step.Apply(expected), c.answer(yes = false))
        }
        assertEquals(Step.Ask, c.signal(heard = false))
        assertEquals(Step.Failed, c.answer(yes = false))
        assertEquals(Recipes.FAILED, store.saved?.id)
        // Ya no se prueba nada.
        assertEquals(Step.Done, c.play(topWay, null))
    }

    @Test
    fun trustedSignalOnlyAsksAtTheLastRecipe() {
        val c = calibrator(trusts = true)
        c.play(topWay, null)
        assertEquals(Step.Apply(Recipes.TW_SOURCE8), c.signal(heard = false))
        assertEquals(Step.Done, c.signal(heard = true))
        assertEquals(SavedRecipe(Recipes.TW_SOURCE8, SavedBy.SIGNAL), store.saved)

        val again = Calibrator(Memory(), trustsSignal = true) { false }
        again.play(topWay, null)
        again.signal(heard = false)
        assertEquals(Step.Apply(Recipes.OPEN_FACTORY), again.signal(heard = false))
        assertEquals(Step.Ask, again.signal(heard = false))
    }

    @Test
    fun savedRecipeIsAppliedEveryTime() {
        store.saved = SavedRecipe(Recipes.TW_SOURCE8, SavedBy.SIGNAL)
        val c = calibrator()
        repeat(3) {
            assertEquals(Step.Apply(Recipes.TW_SOURCE8), c.play(topWay, null))
            assertEquals(Step.Done, c.signal(heard = true))
        }
    }

    @Test
    fun threeSignalFailsForgetAndRecalibrate() {
        store.saved = SavedRecipe(Recipes.TW_SOURCE8, SavedBy.SIGNAL)
        val c = calibrator()
        c.play(topWay, null)
        assertEquals(Step.Done, c.signal(heard = false))
        assertEquals(1, store.saved?.fails)
        // Un "sí" en medio reinicia la cuenta: tienen que ser seguidos.
        c.play(topWay, null)
        c.signal(heard = true)
        assertEquals(0, store.saved?.fails)
        repeat(2) {
            c.play(topWay, null)
            assertEquals(Step.Done, c.signal(heard = false))
        }
        c.play(topWay, null)
        assertEquals(Step.Apply(Recipes.PLAIN), c.signal(heard = false))
        assertNull(store.saved)
    }

    @Test
    fun userRecipeDoesNotCountFails() {
        store.saved = SavedRecipe(Recipes.OPEN_FACTORY, SavedBy.USER)
        val c = calibrator()
        repeat(5) {
            assertEquals(Step.Apply(Recipes.OPEN_FACTORY), c.play(topWay, null))
            assertEquals(Step.Done, c.signal(heard = false))
        }
        assertEquals(SavedRecipe(Recipes.OPEN_FACTORY, SavedBy.USER), store.saved)
    }

    @Test
    fun noAnswerSavesNothingAndAsksAgainNextTime() {
        val c = calibrator()
        c.play(topWay, null)
        c.signal(heard = false)
        c.abort()
        assertFalse(c.pending)
        assertNull(store.saved)
        assertEquals(Step.Apply(Recipes.PLAIN), c.play(topWay, null))
    }

    @Test
    fun movingPostponesOpeningTheApp() {
        val c = calibrator()
        c.play(topWay, null)
        c.signal(heard = false)
        c.answer(yes = false)
        c.signal(heard = false)
        moving = true
        assertEquals(Step.Wait, c.answer(yes = false))
        assertNull(store.saved)
        assertFalse(c.pending)
        // Sigue moviéndose: se vuelve a posponer, sin repetir lo que ya rechazó.
        assertEquals(Step.Wait, c.play(topWay, null))
        moving = false
        assertEquals(Step.Apply(Recipes.OPEN_FACTORY), c.play(topWay, null))
        assertEquals(Step.Done, c.signal(heard = true))
        assertEquals(SavedRecipe(Recipes.OPEN_FACTORY, SavedBy.SIGNAL), store.saved)
    }

    @Test
    fun movingDoesNotStopOtherRecipes() {
        moving = true
        val c = calibrator()
        assertEquals(Step.Apply(Recipes.PLAIN), c.play(topWay, null))
    }

    @Test
    fun catalogRecipeFirstAndSavedByCatalog() {
        val c = calibrator()
        assertEquals(Step.Apply(Recipes.TW_SOURCE8), c.play(topWay, Recipes.TW_SOURCE8))
        assertEquals(Step.Done, c.signal(heard = true))
        assertEquals(SavedRecipe(Recipes.TW_SOURCE8, SavedBy.CATALOG), store.saved)
    }

    @Test
    fun catalogRecipeRejectedCalibratesFully() {
        val c = calibrator()
        c.play(topWay, Recipes.TW_SOURCE8)
        assertEquals(Step.Ask, c.signal(heard = false))
        assertEquals(Step.Apply(Recipes.PLAIN), c.answer(yes = false))
        c.signal(heard = false)
        // La del catálogo ya se rechazó: no se repite.
        assertEquals(Step.Apply(Recipes.OPEN_FACTORY), c.answer(yes = false))
    }

    @Test
    fun catalogRecipeThatIsNotPossibleHereIsIgnored() {
        val c = calibrator()
        assertEquals(Step.Apply(Recipes.PLAIN), c.play(listOf(Recipes.PLAIN, Recipes.OPEN_FACTORY), Recipes.TW_SOURCE8))
    }

    @Test
    fun savedRecipeNotPossibleNowIsKept() {
        // P. ej. el perfil de la radio aún no abre: esta vez lo de siempre, sin borrar lo guardado.
        val saved = SavedRecipe(Recipes.TW_SOURCE8, SavedBy.SIGNAL)
        store.saved = saved
        val c = calibrator()
        assertEquals(Step.Done, c.play(listOf(Recipes.PLAIN, Recipes.OPEN_FACTORY), null))
        assertEquals(saved, store.saved)
        assertFalse(c.pending)
    }

    @Test
    fun anotherPlayWhilePendingDoesNothing() {
        val c = calibrator()
        c.play(topWay, null)
        assertEquals(Step.Done, c.play(topWay, null))
        assertEquals(Recipes.PLAIN, c.current)
    }

    @Test
    fun radioProfileThenOpeningTheApp() {
        val radio = listOf(Recipes.PROFILE, Recipes.OPEN_FACTORY)
        val c = calibrator()
        assertEquals(Step.Apply(Recipes.PROFILE), c.play(radio, null))
        assertEquals(Step.Ask, c.signal(heard = false))
        assertEquals(Step.Apply(Recipes.OPEN_FACTORY), c.answer(yes = false))
        assertEquals(Step.Ask, c.signal(heard = false))
        assertEquals(Step.Done, c.answer(yes = true))
        assertEquals(SavedRecipe(Recipes.OPEN_FACTORY, SavedBy.USER), store.saved)
    }

    @Test
    fun resetForgetsSavedAndRejected() {
        val c = calibrator()
        c.play(topWay, null)
        c.signal(heard = false)
        c.answer(yes = false)
        c.signal(heard = true)
        c.reset()
        assertNull(store.saved)
        assertEquals(Step.Apply(Recipes.PLAIN), c.play(topWay, null))
    }
}
