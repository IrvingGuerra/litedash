package com.irvingguerra.litedash.feature.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** La Pila: cómo se guarda dentro del acomodo, qué cara se ve y sus límites. */
class StackTest {
    private fun face(id: String, type: String, data: String = "") = Tile(id, type, 0, 0, 1, 1, data)

    private fun stackTile(s: Stack, plain: Boolean = false) = Tile("stack-1", TileTypes.STACK, 2, 3, 8, 6, s.encode(), plain)

    @Test
    fun survivesTheLayoutFileWithAwkwardData() {
        // Datos con lo que separa campos y caras (tabulador, salto, coma, barra, punto y coma, igual, %, acentos).
        val tricky = "a=off;b|c,d\te\nf %41 ñ"
        val s = Stack(listOf(face("car-1", TileTypes.CAR), face("music-1", TileTypes.MUSIC, tricky)), current = "music-1")
        val saved = formatLayout(listOf(stackTile(s, plain = true)))
        val back = parseLayout(saved).single()
        assertEquals(TileTypes.STACK, back.type)
        val read = Stack.of(back)
        assertEquals("music-1", read.current)
        assertEquals(listOf("car-1", "music-1"), read.faces.map { it.id })
        assertEquals(tricky, read.faces[1].data)
        // Cada cara mide y está donde la Pila, con o sin tarjeta como ella.
        assertTrue(read.faces.all { it.col == 2 && it.row == 3 && it.width == 8 && it.height == 6 && it.plain })
    }

    @Test
    fun damagedOrNestedFacesAreSkipped() {
        val tile = Tile("s", TileTypes.STACK, 0, 0, 4, 4, "x|car-1,car,|bad|,music,|st-1,stack,|clock-1,clock,%ZZ|car-1,car,|music-1,music,a%3Doff")
        val s = Stack.of(tile)
        assertEquals(listOf("car-1", "music-1"), s.faces.map { it.id })
        assertEquals("a=off", s.faces[1].data)
        assertEquals(Stack(emptyList()), Stack.of(Tile("s", TileTypes.STACK, 0, 0, 4, 4)))
    }

    @Test
    fun facesOfASwitchedOffModuleAreNotShown() {
        val s = Stack(listOf(face("tires-1", TileTypes.TIRES), face("car-1", TileTypes.CAR), face("music-1", TileTypes.MUSIC)), current = "tires-1")
        val off = { t: String -> t == TileTypes.TIRES }
        assertEquals(listOf("car-1", "music-1"), s.visible(off).map { it.id })
        // La elegida está apagada: se ve la primera que sí; al prender el módulo, vuelve la elegida.
        assertEquals("car-1", s.shown(off)?.id)
        assertEquals("tires-1", s.shown { false }?.id)
        assertNull(Stack(listOf(face("tires-1", TileTypes.TIRES))).shown(off))
    }

    @Test
    fun keepsBetweenTwoAndFiveFaces() {
        val two = Stack.of(listOf(TileTypes.CAR, TileTypes.MUSIC), now = 100)
        assertEquals(2, two.faces.map { it.id }.distinct().size)
        assertEquals(two, two.remove(two.faces[0].id))
        var s = two
        for (t in listOf(TileTypes.CLOCK, TileTypes.WEATHER, TileTypes.TRIP, TileTypes.RADIO)) s = s.add(Stack.face(t, 200L + s.faces.size))
        assertEquals(STACK_MAX_FACES, s.faces.size)
        assertEquals(TileTypes.TRIP, s.faces.last().type)
        assertEquals(s, s.add(Stack.face(TileTypes.MAP, 999)))
        // Una Pila no va dentro de otra.
        assertEquals(two, two.add(Stack.face(TileTypes.STACK, 5)))
        val fewer = s.copy(current = s.faces[2].id).remove(s.faces[2].id)
        assertEquals(4, fewer.faces.size)
        assertNull(fewer.current)
    }

    @Test
    fun movesFacesOnePlace() {
        val s = Stack(listOf(face("a", TileTypes.CAR), face("b", TileTypes.TIRES), face("c", TileTypes.MUSIC)))
        assertEquals(listOf("b", "a", "c"), s.move("b", -1).faces.map { it.id })
        assertEquals(listOf("a", "c", "b"), s.move("b", 1).faces.map { it.id })
        assertEquals(s, s.move("a", -1))
        assertEquals(s, s.move("c", 1))
        assertEquals(s, s.move("z", 1))
    }

    @Test
    fun findsAndChangesFacesInsideTheLayout() {
        val s = Stack(listOf(face("car-1", TileTypes.CAR), face("clock-1", TileTypes.CLOCK)), current = "clock-1")
        val layout = listOf(Tile("music-9", TileTypes.MUSIC, 0, 0, 6, 2), stackTile(s))
        assertEquals(listOf("music-9", "stack-1", "car-1", "clock-1"), layout.withFaces().map { it.id })
        assertEquals(listOf("music-9", "clock-1"), layout.facesShown { false }.map { it.id })
        assertEquals("stack-1", layout.stackOf("clock-1")?.id)
        assertNull(layout.stackOf("music-9"))
        val changed = layout.withData(mapOf("clock-1" to "date=off", "music-9" to "art=off"))
        assertEquals("art=off", changed[0].data)
        assertEquals("date=off", Stack.of(changed[1]).faces[1].data)
        assertEquals("clock-1", Stack.of(changed[1]).current)
        assertEquals(layout, layout.withData(mapOf("nobody" to "x")))
        assertFalse(layout[0].isStack)
    }
}
