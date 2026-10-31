package com.irvingguerra.litedash.feature.tablero

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** El acomodo actual y sus cambios; cada cambio se guarda para esta cuadrícula. */
class EstadoTablero(private val almacen: AlmacenTablero) {
    var cuadricula by mutableStateOf<Cuadricula?>(null)
        private set
    var piezas by mutableStateOf(emptyList<Pieza>())
        private set

    /** La pantalla (o la escala) cambió: carga el acomodo de ese tamaño o una plantilla. */
    fun usar(c: Cuadricula): List<Pieza> {
        if (c != cuadricula) {
            cuadricula = c
            piezas = almacen.leer(c) ?: c.plantillaSugerida().piezas(c)
        }
        return piezas
    }

    fun cambiar(nuevas: List<Pieza>) {
        piezas = nuevas
        cuadricula?.let { almacen.guardar(it, nuevas) }
    }

    fun plantilla(p: Plantilla) {
        cuadricula?.let { cambiar(p.piezas(it)) }
    }

    /**
     * Agrega una pieza del [tamano] pedido en el primer hueco; si no cabe, prueba
     * con su tamaño mínimo. Devuelve false si ya no hay lugar.
     */
    fun agregar(tipo: String, tamano: Pair<Int, Int>, medidas: Medidas, dato: String = ""): Boolean {
        val c = cuadricula ?: return false
        for ((ancho, alto) in listOf(tamano, medidas.minAncho to medidas.minAlto)) {
            val lugar = piezas.lugarLibre(ancho, alto, c) ?: continue
            val id = "$tipo-${System.currentTimeMillis().toString(36)}"
            cambiar(piezas + Pieza(id, tipo, lugar.first, lugar.second, ancho, alto, dato))
            return true
        }
        return false
    }

    fun ponerDato(id: String, dato: String) {
        piezas.firstOrNull { it.id == id }?.let { cambiar(piezas.con(it.copy(dato = dato))) }
    }
}
