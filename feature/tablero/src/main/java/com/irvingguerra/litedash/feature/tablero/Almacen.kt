package com.irvingguerra.litedash.feature.tablero

import android.content.Context

/**
 * Guarda un acomodo por tamaño de cuadrícula: la misma app en otra pantalla (u
 * otra escala) no rompe el acomodo de la primera. Formato de texto simple, sin
 * librerías: una pieza por línea, campos separados por tabulador.
 */
class AlmacenTablero(contexto: Context) {
    private val prefs = contexto.getSharedPreferences("tablero", Context.MODE_PRIVATE)

    fun leer(c: Cuadricula): List<Pieza>? {
        val texto = prefs.getString("acomodo_${c.clave}", null) ?: return null
        return texto.lineSequence().filter { it.isNotBlank() }.mapNotNull { linea ->
            val f = linea.split('\t')
            if (f.size < 6) return@mapNotNull null
            Pieza(f[0], TIPOS_VIEJOS[f[1]] ?: f[1], f[2].toInt(), f[3].toInt(), f[4].toInt(), f[5].toInt(), f.getOrElse(6) { "" })
        }.filter { p -> p.col >= 0 && p.fila >= 0 }.toList()
    }

    fun guardar(c: Cuadricula, piezas: List<Pieza>) {
        val texto = piezas.joinToString("\n") {
            listOf(it.id, it.tipo, it.col, it.fila, it.ancho, it.alto, it.dato.replace('\t', ' ').replace('\n', ' '))
                .joinToString("\t")
        }
        prefs.edit().putString("acomodo_${c.clave}", texto).apply()
    }
}

// Piezas que ya no existen y en qué se convierten (la del mapa de la 1.3.0 es ahora Navegar).
private val TIPOS_VIEJOS = mapOf("mapa" to Tipos.NAVEGAR)
