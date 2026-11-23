package com.irvingguerra.litedash.feature.music

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * El aleatorio del reproductor propio (Archivos): lo prende y apaga el botón junto a los controles de la música en
 * grande. El reproductor (feature:player, que depende de este módulo) lo conecta al arrancar ([attach]); sin él, no hay
 * botón. Se guarda en las preferencias del reproductor.
 */
object OwnShuffle {
    /** Prendido: lo que sigue en la cola va revuelto. */
    var on by mutableStateOf(false)
        private set

    private var change: ((Boolean) -> Unit)? = null

    /** Hay reproductor que lo atienda. */
    val available: Boolean get() = change != null

    /** Lo llama el reproductor: el estado guardado y qué hacer al cambiarlo. */
    fun attach(saved: Boolean, onChange: (Boolean) -> Unit) {
        on = saved
        change = onChange
    }

    fun toggle() {
        val c = change ?: return
        on = !on
        c(on)
    }
}
