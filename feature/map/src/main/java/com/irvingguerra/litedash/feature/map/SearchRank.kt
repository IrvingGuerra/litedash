package com.irvingguerra.litedash.feature.map

/*
 * El orden de los resultados de búsqueda (sin internet y con internet, la misma regla):
 *
 *  1. Nombre IGUAL a lo escrito (sin acentos ni mayúsculas). Dentro, primero los lugares destacados (ciudad, pueblo,
 *     colonia, monumento, aeropuerto, estación, parque; [isProminent]) y luego por distancia.
 *  2. El nombre EMPIEZA con lo escrito (palabras completas): solo por distancia, el tipo no cuenta.
 *  3. Contiene todas las palabras: solo por distancia.
 *  4. Empieza con lo escrito con la última palabra a medias (aún escribiendo): solo por distancia.
 *  5. Lo demás (un error de dedo): solo por distancia.
 *
 * La excepción, para cadenas de negocios y nombres comunes: un nombre igual que NO es una población ni un lugar
 * conocido ([isAnchor]) solo se queda en el escalón 1 si está a no más de [EXACT_REACH] veces la distancia del más
 * cercano del escalón 2; si no, baja al 2 y compite por distancia (una calle o una colonia «Estadio» a cientos de
 * kilómetros no va antes que el estadio de aquí).
 * «Starbucks» a 40 km contra «Starbucks Reforma 222» a 1 km → la de 1 km primero; «OXXO» a 30 m contra «Oxxo mirador» a
 * 115 km → el de 30 m; «Tepic» → la ciudad; «Colosseo» → el Coliseo antes que «Bar Colosseo».
 */

/** Los escalones del orden (menor = antes). */
internal const val TIER_EQUAL = 0
internal const val TIER_STARTS = 1
internal const val TIER_CONTAINS = 2
internal const val TIER_STARTS_PREFIX = 3
internal const val TIER_OTHER = 4

/** Un nombre igual corriente sigue delante si está a no más de estas veces la distancia del mejor que empieza igual. */
internal const val EXACT_REACH = 10.0

/** Lugares que se buscan por su nombre (categoría): estación, aeropuerto, monumento y parque. */
private val LANDMARKS = setOf(PlaceCategory.STATION, PlaceCategory.AIRPORT, PlaceCategory.SIGHT, PlaceCategory.PARK)

/** ¿Un lugar destacado? Ciudad, pueblo, villa, colonia, barrio o un lugar conocido ([LANDMARKS]). */
internal fun isProminent(kind: PlaceKind, category: PlaceCategory): Boolean = when (kind) {
    PlaceKind.CITY, PlaceKind.TOWN, PlaceKind.VILLAGE, PlaceKind.SUBURB, PlaceKind.NEIGHBOURHOOD, PlaceKind.QUARTER -> true
    PlaceKind.STREET, PlaceKind.HAMLET -> false
    PlaceKind.PLACE -> category in LANDMARKS
}

/**
 * ¿Un lugar al que se va por su nombre aunque quede lejos? Una población (ciudad, pueblo o villa: «Guadalajara» desde
 * Tepic) o un lugar conocido ([LANDMARKS]: «Colosseo»). Una colonia o una calle no: hay muchas con el mismo nombre.
 */
internal fun isAnchor(kind: PlaceKind, category: PlaceCategory): Boolean = when (kind) {
    PlaceKind.CITY, PlaceKind.TOWN, PlaceKind.VILLAGE -> true
    PlaceKind.PLACE -> category in LANDMARKS
    else -> false
}

/**
 * El escalón de un nombre ([name], ya normalizado) para lo escrito ([query], normalizado): igual (0) > empieza con lo
 * escrito, palabras completas (1) > las contiene completas (2) > empieza con lo escrito y la última palabra aún a medias
 * (3) > lo demás (4).
 */
internal fun tier(name: List<String>, query: List<String>): Int {
    if (query.isEmpty()) return TIER_OTHER
    if (name == query) return TIER_EQUAL
    val k = query.size
    val head = name.size >= k && (0 until k - 1).all { name[it] == query[it] }
    if (head && name[k - 1] == query[k - 1]) return TIER_STARTS
    if (query.all { it in name }) return TIER_CONTAINS
    if (head && name[k - 1].startsWith(query[k - 1])) return TIER_STARTS_PREFIX
    return TIER_OTHER
}

/**
 * El escalón con el que se ordena (la excepción de arriba): un nombre igual que no es de los que se buscan por su nombre ([anchor]) a [km] baja al escalón 2 si
 * queda a más de [EXACT_REACH] veces [bestStartsKm] (el más cercano de los que empiezan igual). Sin distancias, igual.
 */
internal fun rankTier(tier: Int, anchor: Boolean, km: Double?, bestStartsKm: Double?): Int =
    if (tier == TIER_EQUAL && !anchor && km != null && bestStartsKm != null && km > bestStartsKm * EXACT_REACH) TIER_STARTS
    else tier

/** La clase dentro del escalón (menor = antes): solo en el 1 los destacados van delante; en los demás no cuenta. */
internal fun rankClass(tier: Int, prominent: Boolean): Int = if (tier == TIER_EQUAL && !prominent) 1 else 0

/**
 * [hits] (de internet) en el orden de la regla para lo escrito ([query]). Sin distancias, dentro de cada escalón se
 * conserva el orden en que llegaron (el de Photon).
 */
fun orderHits(hits: List<SearchHit>, query: String): List<SearchHit> {
    val words = normalizeForSearch(query)
    if (words.isEmpty() || hits.size < 2) return hits
    val tiers = hits.map { tier(normalizeForSearch(it.name), words) }
    val bestStarts = hits.indices.filter { tiers[it] == TIER_STARTS }.mapNotNull { hits[it].distanceKm }.minOrNull()
    val keyed = hits.indices.map { i ->
        val h = hits[i]
        val p = isProminent(h.kind, h.category)
        val t = rankTier(tiers[i], isAnchor(h.kind, h.category), h.distanceKm, bestStarts)
        Triple(t, rankClass(t, p), h.distanceKm ?: 0.0) to h
    }
    return keyed.sortedWith(compareBy({ it.first.first }, { it.first.second }, { it.first.third })).map { it.second }
}
