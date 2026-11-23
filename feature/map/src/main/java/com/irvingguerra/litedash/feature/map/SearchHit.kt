package com.irvingguerra.litedash.feature.map

/**
 * Un resultado de búsqueda (del índice de tu estado o de internet): su nombre, el otro nombre (inglés o en letras
 * latinas) si lo hay, dónde está (colonia o pueblo; con internet, la dirección si la trae), qué es y su posición.
 */
data class SearchHit(
    val name: String,
    val alt: String?,
    val locality: String?,
    val address: String?,
    val kind: PlaceKind,
    val category: PlaceCategory,
    val lat: Double,
    val lon: Double,
    /** En línea recta desde donde buscaste (km); null si no se sabía dónde estabas. */
    val distanceKm: Double?,
    /** true = de internet (Photon); false = del mapa de tu estado. */
    val online: Boolean,
    /** Para juntar los de varios estados en su orden (menor = antes): escalón, clase y la distancia (SearchRank.kt). */
    val order: Double = 0.0,
)

/** Qué tipo de cosa es (el `tipo` del índice SRCH1). */
enum class PlaceKind(val code: Int) {
    STREET(0), CITY(1), TOWN(2), VILLAGE(3), HAMLET(4), SUBURB(5), NEIGHBOURHOOD(6), QUARTER(7), PLACE(8);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: PLACE
    }
}

/** Qué es un lugar (la `categoría` del índice SRCH1: misma tabla que CATEGORY en armar_indice.py). */
enum class PlaceCategory(val code: Int) {
    OTHER(0), FUEL(1), HEALTH(2), PHARMACY(3), FOOD(4), SCHOOL(5), SHOP(6), HOTEL(7), BANK(8), PARKING(9), POLICE(10),
    WORSHIP(11), STATION(12), AIRPORT(13), SIGHT(14), PARK(15);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: OTHER
    }
}
