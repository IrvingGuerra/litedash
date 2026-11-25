package com.irvingguerra.litedash.feature.dashboard

import java.net.URLDecoder
import java.net.URLEncoder

/** Cuántos widgets lleva una Pila: menos no tiene caso, más ya no se encuentra uno. */
const val STACK_MIN_FACES = 2
const val STACK_MAX_FACES = 5

/**
 * La Pila: varios widgets ([faces]) en el mismo lugar, uno a la vista ([current], su id; null = el primero). Cada cara es
 * un widget como cualquier otro (su tipo, su id y sus opciones en [Tile.data]) que mide lo que la Pila.
 *
 * Se guarda en los datos de la Pila, en un solo campo del acomodo: `actual|id,tipo,datos|id,tipo,datos…` con los datos
 * en formato de URL (sin tabulador, salto, coma ni barra). Una versión sin Pila no conoce el tipo y deja el hueco vacío,
 * pero guarda la pieza tal cual (al volver a una con Pila, regresa igual).
 */
data class Stack(val faces: List<Tile>, val current: String? = null) {
    fun encode(): String = (listOf(current.orEmpty()) + faces.map { "${it.id},${it.type},${URLEncoder.encode(it.data, UTF8)}" }).joinToString("|")

    /** Las que se ven (sin las de un módulo apagado, [hidden]). */
    fun visible(hidden: (String) -> Boolean): List<Tile> = faces.filterNot { hidden(it.type) }

    /** La que está a la vista: la elegida si se ve; si no, la primera que se vea; null = ninguna. */
    fun shown(hidden: (String) -> Boolean): Tile? = visible(hidden).let { v -> v.firstOrNull { it.id == current } ?: v.firstOrNull() }

    /** Con [face] al final (si cabe otra). */
    fun add(face: Tile): Stack = if (faces.size >= STACK_MAX_FACES || face.type == TileTypes.STACK) this else copy(faces = faces + face)

    /** Sin la cara [id] (si quedan las suficientes). */
    fun remove(id: String): Stack =
        if (faces.size <= STACK_MIN_FACES) this else copy(faces = faces.filterNot { it.id == id }, current = current.takeIf { it != id })

    /** La cara [id] un lugar antes (−1) o después (+1). */
    fun move(id: String, by: Int): Stack {
        val from = faces.indexOfFirst { it.id == id }
        val to = from + by
        if (from < 0 || to !in faces.indices) return this
        return copy(faces = faces.toMutableList().apply { add(to, removeAt(from)) })
    }

    companion object {
        /** La Pila de la pieza [stack]: sus caras con el lugar y el tamaño de ella. Lo dañado se salta. */
        fun of(stack: Tile): Stack {
            val parts = stack.data.split('|')
            val faces = parts.drop(1).mapNotNull { part ->
                val f = part.split(',')
                if (f.size != 3 || f[0].isEmpty() || f[1].isEmpty() || f[1] == TileTypes.STACK) return@mapNotNull null
                val data = runCatching { URLDecoder.decode(f[2], UTF8) }.getOrNull() ?: return@mapNotNull null
                Tile(f[0], f[1], stack.col, stack.row, stack.width, stack.height, data, stack.plain)
            }.distinctBy { it.id }.take(STACK_MAX_FACES)
            return Stack(faces, parts.first().takeIf { it.isNotEmpty() })
        }

        /** Una Pila nueva con esos tipos (ids propios, para que ninguno choque con otro widget). */
        fun of(types: List<String>, now: Long = System.currentTimeMillis()): Stack =
            Stack(types.take(STACK_MAX_FACES).mapIndexed { i, t -> face(t, now + i) })

        /** Una cara nueva de [type] (sin lugar: lo toma de su Pila al leerla). */
        fun face(type: String, now: Long = System.currentTimeMillis()) = Tile("$type-${now.toString(36)}", type, 0, 0, 1, 1)
    }
}

private const val UTF8 = "UTF-8"

val Tile.isStack get() = type == TileTypes.STACK

/** Las piezas y, de cada Pila, también sus caras (para buscar un widget por su id o ver qué fotos se usan). */
fun List<Tile>.withFaces(): List<Tile> = flatMap { if (it.isStack) listOf(it) + Stack.of(it).faces else listOf(it) }

/** Las piezas como se ven: cada Pila cambiada por la cara que está a la vista (o fuera, si no se ve ninguna). */
fun List<Tile>.facesShown(hidden: (String) -> Boolean): List<Tile> = mapNotNull { if (it.isStack) Stack.of(it).shown(hidden) else it }

/** La Pila de [face] (por id), o null si no es cara de ninguna. */
fun List<Tile>.stackOf(face: String): Tile? = firstOrNull { it.isStack && Stack.of(it).faces.any { f -> f.id == face } }

/** Cambia los datos de las piezas y de las caras ([updates]: id → datos). */
internal fun List<Tile>.withData(updates: Map<String, String>): List<Tile> = map { t ->
    updates[t.id]?.let { return@map t.copy(data = it) }
    if (!t.isStack) return@map t
    val s = Stack.of(t)
    if (s.faces.none { it.id in updates }) t
    else t.copy(data = s.copy(faces = s.faces.map { f -> updates[f.id]?.let { f.copy(data = it) } ?: f }).encode())
}
