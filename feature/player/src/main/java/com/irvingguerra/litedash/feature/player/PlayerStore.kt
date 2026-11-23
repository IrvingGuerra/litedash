package com.irvingguerra.litedash.feature.player

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

private const val STORE = "player"
private const val KEY_LAST_USB = "last_usb"
private const val KEY_LAST_RADIO = "last_radio"
private const val KEY_FAVORITES = "radio_favorites"
private const val KEY_NEARBY = "radio_nearby"
/** Cuántas estaciones de "Cerca de ti" se guardan para cuando no hay servidor. */
private const val NEARBY_KEEP = 60

/** Lo que el reproductor recuerda: lo último que sonó de cada fuente y las estaciones favoritas. */
class PlayerStore(context: Context) {
    private val p = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    fun rememberLast(source: PlayerSource, key: String) = p.edit { putString(keyFor(source), key) }

    fun last(source: PlayerSource): String? = p.getString(keyFor(source), null)

    /** La última estación completa (para volver a sintonizarla sin buscar). */
    fun lastStation(): Station? = p.getString(KEY_LAST_RADIO_STATION, null)?.let { runCatching { Station.fromJson(JSONObject(it)) }.getOrNull() }

    fun rememberStation(station: Station) = p.edit { putString(KEY_LAST_RADIO_STATION, station.toJson().toString()) }

    /** La última lista de "Cerca de ti" que llegó: se muestra si los servidores no contestan. */
    fun nearbyCache(): List<Station> = stations(KEY_NEARBY)

    fun setNearbyCache(list: List<Station>) = putStations(KEY_NEARBY, list.take(NEARBY_KEEP))

    fun favorites(): List<Station> = stations(KEY_FAVORITES)

    fun setFavorites(list: List<Station>) = putStations(KEY_FAVORITES, list)

    private fun stations(key: String) = stationsFromJson(p.getString(key, null))

    private fun putStations(key: String, list: List<Station>) = p.edit { putString(key, stationsToJson(list)) }

    /** La carpeta de música que eligió el usuario con el selector de Android (null = ninguna). */
    var musicFolder: String?
        get() = p.getString(KEY_MUSIC_FOLDER, null)
        set(v) = p.edit { if (v == null) remove(KEY_MUSIC_FOLDER) else putString(KEY_MUSIC_FOLDER, v) }

    /** El aleatorio de Archivos ([com.irvingguerra.litedash.feature.music.OwnShuffle]). */
    var shuffle: Boolean
        get() = p.getBoolean(KEY_SHUFFLE, false)
        set(v) = p.edit { putBoolean(KEY_SHUFFLE, v) }

    private fun keyFor(source: PlayerSource) = if (source == PlayerSource.USB) KEY_LAST_USB else KEY_LAST_RADIO

    private companion object {
        const val KEY_LAST_RADIO_STATION = "last_radio_station"
        const val KEY_MUSIC_FOLDER = "music_folder"
        const val KEY_SHUFFLE = "shuffle"
    }
}

/** Una lista de estaciones guardada; vacía si no hay o está dañada (las que no tienen dirección se saltan). */
internal fun stationsFromJson(text: String?): List<Station> = runCatching {
    val array = JSONArray(text ?: return emptyList())
    (0 until array.length()).mapNotNull { Station.fromJson(array.getJSONObject(it)) }
}.getOrDefault(emptyList())

internal fun stationsToJson(list: List<Station>): String = JSONArray(list.map { it.toJson() }).toString()
