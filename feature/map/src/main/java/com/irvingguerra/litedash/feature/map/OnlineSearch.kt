package com.irvingguerra.litedash.feature.map

import android.content.Context
import com.irvingguerra.litedash.core.ui.Https
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale

/**
 * Búsqueda con internet en Photon de komoot (gratis con uso razonable): una consulta por búsqueda que el usuario
 * pide, nunca por cada letra, y las últimas [CACHE_SIZE] se guardan [CACHE_MS] en memoria.
 */
object OnlineSearch {
    private const val ENDPOINT = "https://photon.komoot.io/api/"
    private const val CACHE_SIZE = 20
    private const val CACHE_MS = 10 * 60_000L

    /** Los únicos idiomas que Photon acepta (con otro contesta 400); si no, va sin `lang`. */
    private val LANGUAGES = setOf("de", "en", "fr")

    private class Entry(val atMs: Long, val hits: List<SearchHit>)

    // En orden de uso: la más vieja sale primero al pasar de CACHE_SIZE.
    private val cache = object : LinkedHashMap<String, Entry>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) = size > CACHE_SIZE
    }

    /**
     * Busca [query] cerca de ([nearLat], [nearLon]) si se sabe. Bloqueante: llamar fuera del hilo principal.
     * null = no hubo red o falló; lista vacía = no encontró nada.
     */
    fun search(context: Context, query: String, nearLat: Double?, nearLon: Double?, language: String, limit: Int = 10): List<SearchHit>? {
        val q = query.trim().replace(Regex("\\s+"), " ")
        if (q.isEmpty()) return emptyList()
        val lang = language.lowercase(Locale.ROOT).takeIf { it in LANGUAGES }
        // Privacidad: la posición va redondeada a 2 decimales (~1 km), nunca la exacta.
        val near = if (nearLat != null && nearLon != null) round2(nearLat) to round2(nearLon) else null
        val key = "${q.lowercase(Locale.ROOT)}|$lang|${near?.first}|${near?.second}|$limit"
        val now = System.currentTimeMillis()
        synchronized(cache) {
            cache[key]?.let { if (now - it.atMs < CACHE_MS) return it.hits }
        }
        val url = buildString {
            append(ENDPOINT).append("?q=").append(URLEncoder.encode(q, "UTF-8")).append("&limit=").append(limit)
            lang?.let { append("&lang=").append(it) }
            near?.let { append("&lat=").append(it.first).append("&lon=").append(it.second) }
        }
        val body = try {
            val connection = Https.get(context, url, "application/json")
            try {
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        } catch (e: IOException) {
            return null
        }
        // Photon pone delante calles que contienen la palabra; se reordena con la misma regla que sin internet.
        val hits = parsePhoton(body, nearLat, nearLon)?.let { orderHits(it, q) } ?: return null
        synchronized(cache) { cache[key] = Entry(now, hits) }
        return hits
    }

    private fun round2(value: Double) = Math.round(value * 100) / 100.0
}

private val PLACE_KINDS = mapOf(
    "city" to PlaceKind.CITY, "town" to PlaceKind.TOWN, "village" to PlaceKind.VILLAGE, "hamlet" to PlaceKind.HAMLET,
    "suburb" to PlaceKind.SUBURB, "neighbourhood" to PlaceKind.NEIGHBOURHOOD, "quarter" to PlaceKind.QUARTER,
)

/** La misma tabla que CATEGORY en armar_indice.py, aplicada a `osm_value`. */
private val CATEGORIES = mapOf(
    "fuel" to PlaceCategory.FUEL, "hospital" to PlaceCategory.HEALTH, "clinic" to PlaceCategory.HEALTH,
    "doctors" to PlaceCategory.HEALTH, "pharmacy" to PlaceCategory.PHARMACY, "restaurant" to PlaceCategory.FOOD,
    "fast_food" to PlaceCategory.FOOD, "cafe" to PlaceCategory.FOOD, "bar" to PlaceCategory.FOOD,
    "school" to PlaceCategory.SCHOOL, "university" to PlaceCategory.SCHOOL, "college" to PlaceCategory.SCHOOL,
    "supermarket" to PlaceCategory.SHOP, "convenience" to PlaceCategory.SHOP, "mall" to PlaceCategory.SHOP,
    "marketplace" to PlaceCategory.SHOP, "hotel" to PlaceCategory.HOTEL, "motel" to PlaceCategory.HOTEL,
    "guest_house" to PlaceCategory.HOTEL, "bank" to PlaceCategory.BANK, "atm" to PlaceCategory.BANK,
    "parking" to PlaceCategory.PARKING, "police" to PlaceCategory.POLICE, "place_of_worship" to PlaceCategory.WORSHIP,
    "station" to PlaceCategory.STATION, "aerodrome" to PlaceCategory.AIRPORT, "attraction" to PlaceCategory.SIGHT,
    "museum" to PlaceCategory.SIGHT, "viewpoint" to PlaceCategory.SIGHT, "beach_resort" to PlaceCategory.SIGHT,
    "park" to PlaceCategory.PARK,
)

/**
 * Convierte la respuesta GeoJSON de Photon en resultados, en su orden (ya viene por relevancia y cercanía).
 * null si no es el JSON esperado; se saltan los que no traen nombre ni calle o no tienen coordenadas.
 */
internal fun parsePhoton(json: String, nearLat: Double?, nearLon: Double?): List<SearchHit>? {
    val features = try {
        JSONObject(json).getJSONArray("features")
    } catch (e: JSONException) {
        return null
    }
    val hits = ArrayList<SearchHit>(features.length())
    for (i in 0 until features.length()) {
        val feature = features.optJSONObject(i) ?: continue
        val p = feature.optJSONObject("properties") ?: continue
        val coords = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
        val lon = coords.optDouble(0)
        val lat = coords.optDouble(1)
        if (lat.isNaN() || lon.isNaN()) continue
        fun text(key: String) = p.optString(key).trim().takeIf { it.isNotEmpty() }
        val street = text("street")
        val streetLine = listOfNotNull(street, text("housenumber")).joinToString(" ").takeIf { it.isNotEmpty() }
        val name = text("name") ?: streetLine ?: continue
        val osmKey = text("osm_key")
        val osmValue = text("osm_value")
        val kind = when {
            osmKey == "highway" -> PlaceKind.STREET
            osmKey == "place" -> PLACE_KINDS[osmValue] ?: PlaceKind.PLACE
            else -> PlaceKind.PLACE
        }
        val city = text("city")
        val locality = listOfNotNull(text("district"), text("locality"), city).firstOrNull { it != name }
        // "calle número, ciudad, estado" con lo que haya, sin repetir el nombre ni partes iguales.
        val address = listOfNotNull(streetLine, city, text("state")).filter { it != name }.distinct()
            .joinToString(", ").takeIf { it.isNotEmpty() }
        hits += SearchHit(
            name = name,
            alt = null,
            locality = locality,
            address = address,
            kind = kind,
            category = CATEGORIES[osmValue] ?: PlaceCategory.OTHER,
            lat = lat,
            lon = lon,
            distanceKm = if (nearLat != null && nearLon != null) distanceKm(nearLat, nearLon, lat, lon) else null,
            online = true,
        )
    }
    return hits
}
