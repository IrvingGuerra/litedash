package com.irvingguerra.litedash.feature.tablero

import androidx.compose.runtime.Immutable

/** Lado mínimo de una celda: de él sale cuántas columnas y filas caben en la pantalla. */
const val CELDA_MIN_DP = 96

/** Tamaño de la cuadrícula en celdas. Más pantalla = más celdas = más libertad. */
@Immutable
data class Cuadricula(val columnas: Int, val filas: Int) {
    val vertical get() = filas >= columnas
    val clave get() = "${columnas}x$filas"

    companion object {
        fun para(anchoDp: Float, altoDp: Float) = Cuadricula(
            columnas = (anchoDp / CELDA_MIN_DP).toInt().coerceAtLeast(4),
            filas = (altoDp / CELDA_MIN_DP).toInt().coerceAtLeast(3),
        )
    }
}

/**
 * Una pieza del tablero. [tipo] dice qué se dibuja (lo registra la app) y
 * [dato] guarda lo que la pieza necesite, como el componente de una app.
 */
@Immutable
data class Pieza(
    val id: String,
    val tipo: String,
    val col: Int,
    val fila: Int,
    val ancho: Int,
    val alto: Int,
    val dato: String = "",
) {
    fun seEnciman(otra: Pieza) =
        col < otra.col + otra.ancho && otra.col < col + ancho &&
            fila < otra.fila + otra.alto && otra.fila < fila + alto
}

/** Lo que la app sabe de cada tipo de pieza: nombre y tamaños permitidos (en celdas). */
data class Medidas(val minAncho: Int, val minAlto: Int, val maxAncho: Int = 99, val maxAlto: Int = 99) {
    fun acotar(ancho: Int, alto: Int) =
        ancho.coerceIn(minAncho, maxAncho) to alto.coerceIn(minAlto, maxAlto)
}

/** ¿Cabe [p] en la cuadrícula sin encimarse con las demás (ignorando a ella misma)? */
fun List<Pieza>.cabe(p: Pieza, c: Cuadricula): Boolean =
    p.col >= 0 && p.fila >= 0 && p.ancho >= 1 && p.alto >= 1 &&
        p.col + p.ancho <= c.columnas && p.fila + p.alto <= c.filas &&
        none { it.id != p.id && it.seEnciman(p) }

/** Primer lugar libre (de arriba abajo, de izquierda a derecha) para una pieza de ese tamaño. */
fun List<Pieza>.lugarLibre(ancho: Int, alto: Int, c: Cuadricula): Pair<Int, Int>? {
    for (fila in 0..c.filas - alto) for (col in 0..c.columnas - ancho) {
        if (cabe(Pieza("?", "?", col, fila, ancho, alto), c)) return col to fila
    }
    return null
}

/** Reemplaza la pieza con el mismo id. */
fun List<Pieza>.con(p: Pieza) = map { if (it.id == p.id) p else it }

// Tipos de pieza que conocen las plantillas. La app registra cómo se dibuja cada uno.
object Tipos {
    const val AUTO = "auto"
    const val MUSICA = "musica"
    const val NAVEGAR = "navegar"
    const val RELOJ = "reloj"
    const val TELEFONO = "telefono"
    const val APP = "app"
}

/** Tamaños de pantalla, para agrupar los acomodos y sugerir el mejor. */
enum class TamanoPantalla { CHICA, MEDIANA, GRANDE, VERTICAL }

/** Qué tamaño es esta cuadrícula: de 7" (10×4) a 11" o más (13×6 y arriba). */
val Cuadricula.tamano: TamanoPantalla
    get() = when {
        vertical -> TamanoPantalla.VERTICAL
        columnas * filas >= 75 -> TamanoPantalla.GRANDE
        columnas * filas >= 55 -> TamanoPantalla.MEDIANA
        else -> TamanoPantalla.CHICA
    }

/** Los acomodos de inicio y para qué pantallas lucen mejor ([para]). */
enum class Plantilla(val para: Set<TamanoPantalla>) {
    /** Como el Model 3: el auto grande al centro y dos tarjetas abajo. */
    CENTRO(setOf(TamanoPantalla.CHICA, TamanoPantalla.MEDIANA)),
    /** Como DuduAuto: el auto a todo lo ancho y una fila de tarjetas abajo. */
    TARJETAS(setOf(TamanoPantalla.CHICA, TamanoPantalla.MEDIANA, TamanoPantalla.GRANDE)),
    /** El auto a la izquierda y las tarjetas a la derecha. */
    LATERAL(setOf(TamanoPantalla.MEDIANA, TamanoPantalla.GRANDE)),
    /** Tres columnas altas: navegar, música y el auto. */
    COLUMNAS(setOf(TamanoPantalla.MEDIANA, TamanoPantalla.GRANDE)),
    /** Tablero completo: tarjetas a los dos lados y el auto en medio. */
    PANEL(setOf(TamanoPantalla.GRANDE)),
    /** Pantallas paradas: auto arriba, tarjetas abajo. */
    VERTICAL(setOf(TamanoPantalla.VERTICAL)),
}

/**
 * Acomodos de inicio. Se calculan para cualquier cuadrícula: lo que no quepa se
 * omite, y nunca se enciman (lo revisan las pruebas).
 */
fun Plantilla.piezas(c: Cuadricula): List<Pieza> {
    val cols = c.columnas
    val filas = c.filas
    val lista = mutableListOf<Pieza>()
    fun agrega(tipo: String, col: Int, fila: Int, ancho: Int, alto: Int) {
        val p = Pieza("$tipo-${lista.size}", tipo, col, fila, ancho, alto)
        if (ancho >= 1 && alto >= 1 && lista.cabe(p, c)) lista += p
    }
    when (this) {
        Plantilla.CENTRO -> {
            val abajo = if (filas >= 6) 2 else 1
            val anchoAuto = (cols * 0.6).toInt().coerceAtLeast(3)
            agrega(Tipos.AUTO, (cols - anchoAuto) / 2, 0, anchoAuto, filas - abajo)
            // Las tarjetas de abajo miden lo mismo que el auto, alineadas con él.
            val inicio = (cols - anchoAuto) / 2
            val anchoMusica = (anchoAuto * 0.6).toInt().coerceAtLeast(3)
            val anchoNav = anchoAuto - anchoMusica
            agrega(Tipos.MUSICA, inicio, filas - abajo, anchoMusica, abajo)
            agrega(Tipos.NAVEGAR, inicio + anchoMusica, filas - abajo, anchoNav, abajo)
        }
        Plantilla.TARJETAS -> {
            val abajo = if (filas >= 6) 2 else 1
            agrega(Tipos.AUTO, 0, 0, cols, filas - abajo)
            // Navegar, música (la más ancha) y lo que quepa: reloj y teléfono.
            val musica = maxOf(3, cols * 4 / 10)
            val resto = cols - musica
            val otras = when {
                resto >= 7 -> listOf(Tipos.RELOJ, Tipos.TELEFONO)
                resto >= 4 -> listOf(Tipos.RELOJ)
                else -> emptyList()
            }
            val anchoNav = if (otras.isEmpty()) resto else (resto / (otras.size + 1)).coerceAtLeast(2)
            agrega(Tipos.NAVEGAR, 0, filas - abajo, anchoNav, abajo)
            agrega(Tipos.MUSICA, anchoNav, filas - abajo, musica, abajo)
            var col = anchoNav + musica
            otras.forEachIndexed { i, tipo ->
                val ancho = if (i == otras.lastIndex) cols - col else (cols - col) / (otras.size - i)
                agrega(tipo, col, filas - abajo, ancho, abajo)
                col += ancho
            }
        }
        Plantilla.LATERAL -> {
            val anchoAuto = (cols * 0.4).toInt().coerceAtLeast(3)
            agrega(Tipos.AUTO, 0, 0, anchoAuto, filas)
            val resto = cols - anchoAuto
            val altoReloj = if (filas >= 5) 2 else 1
            agrega(Tipos.RELOJ, anchoAuto, 0, resto, altoReloj)
            val altoMusica = if (filas - altoReloj >= 3) 2 else 1
            agrega(Tipos.MUSICA, anchoAuto, altoReloj, resto, altoMusica)
            val fila = altoReloj + altoMusica
            val mitad = resto / 2
            agrega(Tipos.NAVEGAR, anchoAuto, fila, mitad, filas - fila)
            agrega(Tipos.TELEFONO, anchoAuto + mitad, fila, resto - mitad, filas - fila)
        }
        Plantilla.COLUMNAS -> {
            if (cols < 9) {
                // No caben tres columnas: el auto arriba y música abajo.
                agrega(Tipos.AUTO, 0, 0, cols, filas - 1)
                agrega(Tipos.MUSICA, 0, filas - 1, cols, 1)
            } else {
                val anchoNav = cols / 4
                val anchoMusica = maxOf(3, cols / 3)
                val inicioAuto = anchoNav + anchoMusica
                agrega(Tipos.AUTO, inicioAuto, 0, cols - inicioAuto, filas)
                agrega(Tipos.NAVEGAR, 0, 0, anchoNav, filas)
                val altoMusica = minOf(filas, 3)
                agrega(Tipos.MUSICA, anchoNav, 0, anchoMusica, altoMusica)
                if (filas > altoMusica) agrega(Tipos.RELOJ, anchoNav, altoMusica, anchoMusica, filas - altoMusica)
            }
        }
        Plantilla.PANEL -> {
            val lado = cols / 4
            if (lado < 3 || cols - 2 * lado < 3) {
                // Pantalla chica: el auto con la música abajo.
                agrega(Tipos.AUTO, 0, 0, cols, filas - 1)
                agrega(Tipos.MUSICA, 0, filas - 1, cols, 1)
            } else {
                agrega(Tipos.AUTO, lado, 0, cols - 2 * lado, filas)
                val mitad = (filas + 1) / 2
                agrega(Tipos.MUSICA, 0, 0, lado, minOf(mitad, 3))
                agrega(Tipos.NAVEGAR, 0, minOf(mitad, 3), lado, filas - minOf(mitad, 3))
                agrega(Tipos.RELOJ, cols - lado, 0, lado, mitad)
                agrega(Tipos.TELEFONO, cols - lado, mitad, lado, filas - mitad)
            }
        }
        Plantilla.VERTICAL -> {
            val altoAuto = (filas * 0.45).toInt().coerceAtLeast(3)
            agrega(Tipos.AUTO, 0, 0, cols, altoAuto)
            agrega(Tipos.MUSICA, 0, altoAuto, cols, 2)
            val fila = altoAuto + 2
            val mitad = cols / 2
            agrega(Tipos.NAVEGAR, 0, fila, mitad, 2)
            agrega(Tipos.TELEFONO, mitad, fila, cols - mitad, 2)
        }
    }
    return lista
}

/** El acomodo que mejor luce en esta pantalla. */
fun Cuadricula.plantillaSugerida() = when (tamano) {
    TamanoPantalla.CHICA -> Plantilla.CENTRO
    TamanoPantalla.MEDIANA -> Plantilla.TARJETAS
    TamanoPantalla.GRANDE -> Plantilla.PANEL
    TamanoPantalla.VERTICAL -> Plantilla.VERTICAL
}
