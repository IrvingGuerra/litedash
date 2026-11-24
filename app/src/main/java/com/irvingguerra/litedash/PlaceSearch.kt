package com.irvingguerra.litedash

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Radius
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.feature.apps.App
import com.irvingguerra.litedash.feature.map.MapPacks
import com.irvingguerra.litedash.feature.map.OnlineSearch
import com.irvingguerra.litedash.feature.map.PlaceCategory
import com.irvingguerra.litedash.feature.map.PlaceKind
import com.irvingguerra.litedash.feature.map.SearchHit
import com.irvingguerra.litedash.feature.map.distanceKm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.text.Normalizer
import java.util.Locale

/**
 * Lo que se busca en el mapa en grande: lo escrito, lo que encontró el mapa de tu estado al instante ([local]) y, al
 * tocar «Buscar en internet», Photon ([online]); y el lugar elegido ([selected]), que se ve con un pin y su tarjeta.
 */
@Stable
internal class PlaceSearch {
    var query by mutableStateOf("")
    var focused by mutableStateOf(false)

    /** La lista de resultados a la vista; tocar fuera o atrás la cierra (lo escrito se queda) y escribir la vuelve a abrir. */
    var open by mutableStateOf(true)
    var local by mutableStateOf<List<SearchHit>>(emptyList())

    /** Buscando lo escrito en el mapa de tu estado (mientras, no se dice que no está). */
    var searching by mutableStateOf(false)
    var online by mutableStateOf<List<SearchHit>>(emptyList())
    var onlineState by mutableStateOf(OnlineState.IDLE)
    var selected by mutableStateOf<SearchHit?>(null)

    /** Escribir de nuevo olvida lo de internet y el lugar elegido. */
    fun type(text: String) {
        query = text
        open = true
        online = emptyList()
        onlineState = OnlineState.IDLE
        selected = null
    }

    fun clear() = type("")
}

internal enum class OnlineState { IDLE, LOADING, DONE, FAILED }

/**
 * Lo que otras partes piden al mapa en grande: un lugar que llegó de otra app (un enlace `geo:`), algo que buscar o
 * abrir el buscador (la lupa del widget). [MapExpanded] lo toma y lo limpia.
 */
internal object PlaceRequests {
    var place by mutableStateOf<SearchHit?>(null)
    var query by mutableStateOf<String?>(null)
    var focus by mutableStateOf(false)

    /** ¿Hay algo pendiente? (el inicio abre el mapa en grande para atenderlo). */
    val pending: Boolean get() = place != null || query != null || focus

    /** Un enlace `geo:` de otra app; false si no se entiende. */
    fun fromGeo(uri: Uri?): Boolean {
        // Lo de otras apps no es confiable: se lee sin decodificar (un "%26" no parte la búsqueda) y se recorta.
        val geo = uri?.takeIf { it.scheme.equals("geo", true) }?.let { parseGeo(it.encodedSchemeSpecificPart.orEmpty()) } ?: return false
        if (geo.place != null) place = geo.place else query = geo.query
        return true
    }

    /** Casa o Trabajo en el mapa de LiteDash: guardado como coordenadas va directo al lugar; como texto, al buscador. */
    fun fromAddress(address: String) {
        val geo = parseGeo("0,0?q=" + Uri.encode(address)) ?: return
        if (geo.place != null) place = geo.place else query = geo.query
    }
}

/** Lo que trae un enlace `geo:`: un punto (con su nombre si lo trae) o un texto que buscar. */
internal class GeoRequest(val place: SearchHit?, val query: String?)

/**
 * Lee la parte de un enlace `geo:` (sin "geo:" y sin decodificar): "19.41,-99.16", "19.41,-99.16?q=Plaza", "0,0?q=19.41,-99.16(Plaza)"
 * o "0,0?q=Av. Insurgentes 100". null si no trae nada útil.
 */
internal fun parseGeo(part: String): GeoRequest? {
    val coords = part.substringBefore('?').substringBefore(';')
    // Se separa antes de decodificar (un "%26" es parte del texto) y "+" es espacio, como lo manda Maps.
    val raw = part.substringAfter('?', "").split('&').firstOrNull { it.startsWith("q=") }?.removePrefix("q=").orEmpty()
    val q = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw).trim().take(QUERY_MAX)
    // "lat,lon(Nombre)" dentro de q: el lugar con su nombre.
    Regex("""^\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*(?:\((.*)\))?\s*$""").find(q)?.let { m ->
        val lat = m.groupValues[1].toDouble()
        val lon = m.groupValues[2].toDouble()
        if (valid(lat, lon)) return GeoRequest(pointHit(m.groupValues[3].trim().ifEmpty { null }, lat, lon), null)
    }
    if (q.isNotEmpty()) return GeoRequest(null, q)
    val parts = coords.split(',')
    val lat = parts.getOrNull(0)?.trim()?.toDoubleOrNull() ?: return null
    val lon = parts.getOrNull(1)?.trim()?.toDoubleOrNull() ?: return null
    if (!valid(lat, lon) || (lat == 0.0 && lon == 0.0)) return null
    return GeoRequest(pointHit(null, lat, lon), null)
}

private fun valid(lat: Double, lon: Double) = lat in -90.0..90.0 && lon in -180.0..180.0

/** Un punto suelto (dejar presionado el mapa o un enlace sin nombre): sin nombre, la tarjeta dice «Punto en el mapa». */
internal fun pointHit(name: String?, lat: Double, lon: Double) =
    SearchHit(name.orEmpty(), null, null, null, PlaceKind.PLACE, PlaceCategory.OTHER, lat, lon, null, false)

/** Lugares guardados (favoritos y recientes) como JSON, en el orden de la lista. */
internal fun placesToJson(list: List<SearchHit>): String = JSONArray().apply {
    for (p in list) put(
        JSONObject().put("name", p.name).put("detail", p.locality ?: p.address).put("kind", p.kind.code)
            .put("category", p.category.code).put("lat", p.lat).put("lon", p.lon),
    )
}.toString()

internal fun placesFromJson(text: String?): List<SearchHit> {
    if (text.isNullOrEmpty()) return emptyList()
    val a = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
    return (0 until a.length()).mapNotNull { i ->
        val o = a.optJSONObject(i) ?: return@mapNotNull null
        val lat = o.optDouble("lat")
        val lon = o.optDouble("lon")
        if (lat.isNaN() || lon.isNaN()) return@mapNotNull null
        SearchHit(
            o.optString("name"), null, o.optString("detail").ifEmpty { null }, null, PlaceKind.of(o.optInt("kind", 8)),
            PlaceCategory.of(o.optInt("category")), lat, lon, null, false,
        )
    }
}

/** El mismo lugar (para no repetir favoritos, recientes ni lo de internet que ya salió sin conexión): a menos de 150 m. */
internal fun samePlace(a: SearchHit, b: SearchHit) =
    a.name.equals(b.name, ignoreCase = true) && distanceKm(a.lat, a.lon, b.lat, b.lon) < 0.15

/** Cuántos recientes se guardan. */
private const val MAX_RECENTS = 8

/** Cuántos favoritos (caben como atajos bajo el buscador). */
private const val MAX_FAVORITES = 12

/** El lugar elegido pasa al principio de los recientes. */
internal fun Preferences.addRecent(place: SearchHit) {
    if (place.name.isEmpty()) return
    val clean = place.copy(distanceKm = null, online = false)
    recentPlaces = placesToJson((listOf(clean) + placesFromJson(recentPlaces).filterNot { samePlace(it, clean) }).take(MAX_RECENTS))
}

internal fun Preferences.isFavorite(place: SearchHit) = placesFromJson(favoritePlaces).any { samePlace(it, place) }

internal fun Preferences.toggleFavorite(place: SearchHit) {
    val list = placesFromJson(favoritePlaces)
    favoritePlaces = placesToJson(
        if (list.any { samePlace(it, place) }) list.filterNot { samePlace(it, place) }
        else if (list.size >= MAX_FAVORITES) list
        else list + place.copy(name = place.name.ifEmpty { coordinates(place) }, distanceKm = null, online = false),
    )
}

/** Las coordenadas como texto ("19.412500,-99.165500"): lo que entienden Waze, Maps y cualquier app de mapas. */
internal fun coordinates(place: SearchHit) = String.format(Locale.US, "%.6f,%.6f", place.lat, place.lon)

/**
 * El buscador del mapa en grande: el campo «¿A dónde vas?» arriba y, debajo, según el momento, tus atajos (Casa,
 * Trabajo y favoritos), tus recientes o los resultados. Busca sin internet al escribir (en el mapa de tu estado) y en
 * internet solo al tocar «Buscar en internet» (o la tecla de buscar). [onPick] = se eligió un lugar; [onPlace] =
 * Casa o Trabajo (a tu app de mapas o al mapa de LiteDash, según «Navegar con»). La lista llega hasta [listSpace]
 * menos lo que tape el teclado.
 */
@Composable
internal fun PlaceSearchBox(
    d: Dependencies,
    search: PlaceSearch,
    focus: FocusRequester,
    /**
     * Cierra la lista y suelta el teclado (ver [MapExpanded]: el foco pasa al mapa; soltarlo sin más lo regresa al campo en
     * Android ≤ 8).
     */
    closeList: () -> Unit,
    onPick: (SearchHit) -> Unit,
    onPlace: (String) -> Unit,
    listSpace: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val c = LocalColors.current
    // El teclado tapa lo de abajo (la ventana no se encoge): la lista termina arriba de él. Se lee aquí (no en el mapa en
    // grande) para que su animación no recomponga el mapa en cada cuadro.
    val keyboard = with(LocalDensity.current) { WindowInsets.ime.getBottom(this).toDp() }
    val maxListHeight = (listSpace - keyboard).coerceAtLeast(MIN_LIST)
    // Atrás con el teclado abierto lo cierra Android sin avisar a la app: al esconderse, la lista se cierra también (si no
    // hacía falta un segundo atrás). Donde el teclado no se mide (algunas pantallas de auto), queda el segundo atrás.
    val keyboardUp = keyboard > 0.dp
    val sawKeyboard = remember { BooleanArray(1) }
    LaunchedEffect(keyboardUp) {
        if (keyboardUp) sawKeyboard[0] = true
        else if (sawKeyboard[0]) {
            sawKeyboard[0] = false
            if (search.focused) closeList()
        }
    }
    val context = LocalContext.current
    val packs = remember { MapPacks.get(context) }
    val scope = rememberCoroutineScope()
    val language = currentLocale().language
    val here = d.trip.position
    // Con un estado bajado, revisa el índice (a lo más una vez al día): si a su búsqueda le falta o es vieja, se baja, y
    // de paso trae el apagado remoto de «Buscar en internet». Sin mapas no se pide (son ~1.5 MB solo para eso): quien no
    // tiene ningún estado se entera del apagado hasta que la lista llegue por otro lado (Ajustes → Sin conexión).
    val hasPacks = packs.installed.isNotEmpty()
    LaunchedEffect(hasPacks) { if (hasPacks) packs.refresh() }
    val canSearch = packs.canSearch()
    // Al escribir: el mapa de tu estado contesta al instante (fuera del hilo principal; una pausa chica junta letras).
    // También al llegar la búsqueda de tu estado mientras escribías.
    LaunchedEffect(search.query, canSearch) {
        val q = search.query.trim()
        if (q.isEmpty()) {
            search.local = emptyList()
            search.searching = false
            return@LaunchedEffect
        }
        search.searching = true
        delay(TYPE_PAUSE_MS)
        search.local = withContext(Dispatchers.Default) { packs.searchLocal(q, here?.latitude, here?.longitude) }
        search.searching = false
    }
    val askOnline: () -> Unit = {
        val q = search.query.trim()
        if (q.isNotEmpty() && packs.onlineSearch && search.onlineState != OnlineState.LOADING) {
            search.onlineState = OnlineState.LOADING
            scope.launch {
                val found = withContext(Dispatchers.IO) { OnlineSearch.search(context, q, here?.latitude, here?.longitude, language) }
                // Si en el inter se escribió otra cosa, esto ya no sirve.
                if (search.query.trim() != q) return@launch
                if (found == null) search.onlineState = OnlineState.FAILED
                else {
                    search.online = found.filterNot { hit -> search.local.any { samePlace(it, hit) } }
                    search.onlineState = OnlineState.DONE
                }
            }
        }
    }
    // Atrás: primero se cierran la lista y el teclado, luego se borra lo escrito (el mapa en grande se cierra después).
    // Con la tarjeta de un lugar a la vista, atrás la cierra el mapa en grande (y regresa a ti).
    BackHandler((search.focused || search.query.isNotEmpty()) && (search.selected == null || search.focused)) {
        if (search.focused || search.open) closeList() else search.clear()
    }
    Column(modifier) {
        // Buscar en el teclado lo esconde y deja la lista a la vista.
        SearchField(search, focus, onSearch = {
            closeList()
            search.open = true
            if (search.local.isEmpty()) askOnline()
        })
        val q = search.query.trim()
        val recents = remember(d.prefs.recentPlaces) { placesFromJson(d.prefs.recentPlaces) }
        when {
            search.selected != null -> Unit
            q.isNotEmpty() -> if (search.open) Results(d, search, q, canSearch, packs.onlineSearch, maxListHeight, onPick, askOnline)
            search.focused && recents.isNotEmpty() -> Panel(maxListHeight) {
                Label(stringResource(R.string.search_recent).uppercase(currentLocale()), Typography.label, c.textMuted, modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 4.dp))
                for (p in recents) PlaceRow(d, p, emptyList(), Icons.clock) { onPick(p) }
            }
            !search.focused -> Shortcuts(d, onPick, onPlace)
        }
    }
}

/** Lo menos que mide la lista de resultados (con el teclado arriba en pantallas bajas). */
private val MIN_LIST = 120.dp

/** La pausa al escribir antes de buscar (junta varias letras seguidas en una búsqueda). */
private const val TYPE_PAUSE_MS = 90L

@Composable
private fun SearchField(search: PlaceSearch, focus: FocusRequester, onSearch: () -> Unit) {
    val c = LocalColors.current
    val shape = RoundedCornerShape(Radius.card)
    Row(
        Modifier.fillMaxWidth().height(52.dp).clip(shape).background(c.surface.copy(alpha = 0.96f)).border(1.dp, c.line, shape)
            .padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.search, c.textMuted, 20.dp)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            if (search.query.isEmpty()) Label(stringResource(R.string.search_hint), Typography.body, c.textMuted, maxLines = 1)
            BasicTextField(
                search.query,
                { search.type(it.take(QUERY_MAX)) },
                Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged {
                    search.focused = it.isFocused
                    if (it.isFocused) search.open = true
                },
                textStyle = Typography.body.copy(color = c.text),
                singleLine = true,
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            )
        }
        if (search.query.isNotEmpty()) IconButton(Icons.close, search::clear, size = 40.dp, color = c.textMuted, label = stringResource(R.string.search_clear))
    }
}

/** Lo más largo que se escribe (nadie busca un párrafo). */
internal const val QUERY_MAX = 120

/** Debajo del campo, sin escribir: Casa, Trabajo y tus favoritos. */
@Composable
private fun Shortcuts(d: Dependencies, onPick: (SearchHit) -> Unit, onPlace: (String) -> Unit) {
    val favorites = remember(d.prefs.favoritePlaces) { placesFromJson(d.prefs.favoritePlaces) }
    val home = d.prefs.homeAddress
    val work = d.prefs.workAddress
    if (home == null && work == null && favorites.isEmpty()) return
    Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (home != null) Chip(Icons.navigate, stringResource(R.string.place_home)) { onPlace(home) }
        if (work != null) Chip(Icons.navigate, stringResource(R.string.place_work)) { onPlace(work) }
        for (f in favorites) Chip(Icons.starFilled, f.name) { onPick(f) }
    }
}

@Composable
private fun Chip(icon: ImageVector, text: String, onClick: () -> Unit) {
    val c = LocalColors.current
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(c.surface.copy(alpha = 0.96f)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, c.accent, 15.dp)
        Label(text, Typography.small.copy(fontWeight = FontWeight.Medium), maxLines = 1, modifier = Modifier.padding(start = 7.dp).widthIn(max = 180.dp))
    }
}

/** Una tarjeta debajo del campo con lo que quepa (se desliza si hay más). */
@Composable
private fun Panel(maxHeight: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
    val c = LocalColors.current
    Column(
        Modifier.padding(top = 8.dp).fillMaxWidth().heightIn(max = maxHeight).clip(RoundedCornerShape(Radius.card))
            .background(c.surface.copy(alpha = 0.97f)).verticalScroll(rememberScrollState()).padding(bottom = 6.dp),
    ) { content() }
}

@Composable
private fun Results(
    d: Dependencies,
    search: PlaceSearch,
    q: String,
    canSearch: Boolean,
    onlineAllowed: Boolean,
    maxHeight: androidx.compose.ui.unit.Dp,
    onPick: (SearchHit) -> Unit,
    askOnline: () -> Unit,
) {
    val c = LocalColors.current
    val words = remember(q) { foldText(q).split(' ').filter { it.isNotEmpty() } }
    Panel(maxHeight) {
        for (hit in search.local) PlaceRow(d, hit, words, null) { onPick(hit) }
        // Sin el mapa de tu estado no hay búsqueda sin conexión: se dice una vez, con la salida (internet).
        if (search.local.isEmpty() && !search.searching && search.onlineState == OnlineState.IDLE) {
            Label(
                stringResource(if (canSearch) R.string.search_none_offline else R.string.search_no_pack),
                Typography.small, c.textMuted, modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            )
        }
        for (hit in search.online) PlaceRow(d, hit, words, null) { onPick(hit) }
        if (onlineAllowed) when (search.onlineState) {
            OnlineState.IDLE -> OnlineRow(Icons.search, stringResource(R.string.search_online), c.accent, askOnline)
            OnlineState.LOADING -> OnlineRow(Icons.search, stringResource(R.string.search_online_loading), c.textMuted, null)
            OnlineState.FAILED -> OnlineRow(Icons.offline, stringResource(R.string.search_online_failed), c.textMuted, askOnline)
            OnlineState.DONE -> if (search.online.isEmpty()) OnlineRow(Icons.search, stringResource(R.string.search_online_none), c.textMuted, null)
        }
    }
}

@Composable
private fun OnlineRow(icon: ImageVector, text: String, color: androidx.compose.ui.graphics.Color, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, color, 18.dp)
        Label(text, Typography.body.copy(fontWeight = FontWeight.Medium), color, maxLines = 1, modifier = Modifier.padding(start = 12.dp))
    }
}

/** Un renglón: ícono de lo que es, el nombre (lo escrito resaltado), qué es y dónde, y a cuánto queda. */
@Composable
private fun PlaceRow(d: Dependencies, hit: SearchHit, words: List<String>, icon: ImageVector?, onClick: () -> Unit) {
    val c = LocalColors.current
    val here = d.trip.position
    val km = hit.distanceKm ?: here?.let { distanceKm(it.latitude, it.longitude, hit.lat, hit.lon) }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(Radius.inner)).background(c.surfaceHigh), contentAlignment = Alignment.Center) {
            Icon(icon ?: placeIcon(hit), c.accent, 18.dp)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
            androidx.compose.foundation.text.BasicText(
                highlight(hit.name.ifEmpty { stringResource(R.string.search_point) }, words, c.accent),
                style = Typography.body.copy(color = c.text, fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val detail = placeDetail(hit)
            if (detail.isNotEmpty()) Label(detail, Typography.small, c.textMuted, maxLines = 1)
        }
        if (km != null) Label(distanceLabel(km, d.prefs.units.miles), Typography.small, c.textMuted, maxLines = 1)
    }
}

/**
 * "Calle · Colonia Roma" / "Gasolinera · Centro": su otro nombre si lo trae (en inglés o en letras latinas: «Mexico City»),
 * qué es y dónde (con internet, la dirección si no hay colonia).
 */
@Composable
private fun placeDetail(hit: SearchHit): String =
    listOfNotNull(otherName(hit), placeKind(hit), hit.locality ?: hit.address).filter { it.isNotEmpty() }.joinToString(" · ")

/** El otro nombre del lugar ([SearchHit.alt]) si dice algo distinto del nombre. */
private fun otherName(hit: SearchHit): String? = hit.alt?.takeIf { it.isNotBlank() && !it.equals(hit.name, ignoreCase = true) }

/** Qué es un lugar en palabras, o null si no se sabe (un lugar cualquiera). */
@Composable
private fun placeKind(hit: SearchHit): String? = when (hit.category) {
    PlaceCategory.FUEL -> stringResource(R.string.place_fuel)
    PlaceCategory.HEALTH -> stringResource(R.string.place_health)
    PlaceCategory.PHARMACY -> stringResource(R.string.place_pharmacy)
    PlaceCategory.FOOD -> stringResource(R.string.place_food)
    PlaceCategory.SCHOOL -> stringResource(R.string.place_school)
    PlaceCategory.SHOP -> stringResource(R.string.place_shop)
    PlaceCategory.HOTEL -> stringResource(R.string.place_hotel)
    PlaceCategory.BANK -> stringResource(R.string.place_bank)
    PlaceCategory.PARKING -> stringResource(R.string.place_parking)
    PlaceCategory.POLICE -> stringResource(R.string.place_police)
    PlaceCategory.WORSHIP -> stringResource(R.string.place_worship)
    PlaceCategory.STATION -> stringResource(R.string.place_station)
    PlaceCategory.AIRPORT -> stringResource(R.string.place_airport)
    PlaceCategory.SIGHT -> stringResource(R.string.place_sight)
    PlaceCategory.PARK -> stringResource(R.string.place_park)
    PlaceCategory.OTHER -> when (hit.kind) {
        PlaceKind.STREET -> stringResource(R.string.place_street)
        PlaceKind.CITY -> stringResource(R.string.place_city)
        PlaceKind.TOWN, PlaceKind.VILLAGE, PlaceKind.HAMLET -> stringResource(R.string.place_town)
        PlaceKind.SUBURB, PlaceKind.NEIGHBOURHOOD, PlaceKind.QUARTER -> stringResource(R.string.place_neighbourhood)
        PlaceKind.PLACE -> null
    }
}

private fun placeIcon(hit: SearchHit): ImageVector = when {
    hit.category == PlaceCategory.FUEL -> Icons.fuel
    hit.category == PlaceCategory.PARKING -> Icons.car
    hit.category != PlaceCategory.OTHER -> Icons.location
    hit.kind == PlaceKind.STREET -> Icons.road
    hit.kind == PlaceKind.PLACE -> Icons.location
    else -> Icons.map
}

/** La distancia corta: "850 m", "1.2 km", "14 km" (o en millas). */
@Composable
internal fun distanceLabel(km: Double, miles: Boolean): String {
    val value = if (miles) km * 0.621371 else km
    return when {
        miles && value < 0.1 -> stringResource(R.string.search_ft, ((km * 3280.84).toInt() / 50 * 50).coerceAtLeast(50))
        !miles && km < 1 -> stringResource(R.string.search_m, (km * 1000).toInt().coerceAtLeast(10) / 10 * 10)
        value < 10 -> stringResource(if (miles) R.string.search_mi_tenths else R.string.search_km_tenths, value)
        else -> stringResource(if (miles) R.string.radio_mi else R.string.map_offline_radius, value.toInt())
    }
}

/** Sin acentos ni mayúsculas, letra por letra (mismo largo: así se ubica lo escrito dentro del nombre). */
internal fun foldText(text: String): String = buildString(text.length) {
    for (ch in text) {
        val base = Normalizer.normalize(ch.toString(), Normalizer.Form.NFD).firstOrNull() ?: ch
        append(base.lowercaseChar())
    }
}

/** El nombre con lo escrito ([words], ya sin acentos) resaltado al principio de cada palabra. */
internal fun highlight(name: String, words: List<String>, color: androidx.compose.ui.graphics.Color): AnnotatedString {
    val b = AnnotatedString.Builder(name)
    if (words.isEmpty()) return b.toAnnotatedString()
    val folded = foldText(name)
    var i = 0
    while (i < folded.length) {
        val start = i == 0 || !folded[i - 1].isLetterOrDigit()
        val match = if (start && folded[i].isLetterOrDigit()) words.filter { folded.startsWith(it, i) }.maxOfOrNull { it.length } ?: 0 else 0
        if (match > 0) {
            b.addStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold), i, i + match)
            i += match
        } else i++
    }
    return b.toAnnotatedString()
}

/**
 * La tarjeta del lugar elegido: su nombre, qué es y dónde, a cuánto queda en línea recta (y si salió de internet o
 * del mapa de tu estado), y qué hacer: ver la ruta, abrirlo en tu app de mapas, guardarlo de favorito, o como Casa o
 * Trabajo.
 * [appName] = tu app de mapas (null = no usas una: sin botón para abrirla).
 */
@Composable
internal fun PlaceCard(
    d: Dependencies, hit: SearchHit, appName: String?, onRoute: () -> Unit, onOpen: () -> Unit, close: () -> Unit, modifier: Modifier = Modifier,
) {
    val c = LocalColors.current
    val here = d.trip.position
    val km = here?.let { distanceKm(it.latitude, it.longitude, hit.lat, hit.lon) }
    val favorite = remember(d.prefs.favoritePlaces, hit) { d.prefs.isFavorite(hit) }
    val coords = coordinates(hit)
    Column(modifier.clip(RoundedCornerShape(Radius.card)).background(c.surface.copy(alpha = 0.97f)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Label(hit.name.ifEmpty { stringResource(R.string.search_point) }, Typography.title, maxLines = 2)
                val detail = if (hit.name.isEmpty()) coords else listOfNotNull(otherName(hit), placeKind(hit), hit.locality, hit.address).distinct().joinToString(" · ")
                if (detail.isNotEmpty()) Label(detail, Typography.small, c.textMuted, maxLines = 2, modifier = Modifier.padding(top = 2.dp))
            }
            IconButton(Icons.close, close, size = 36.dp, color = c.textMuted, label = stringResource(R.string.search_clear))
        }
        if (km != null) {
            val source = if (hit.online) R.string.search_from_online else R.string.search_from_offline
            Label(
                stringResource(R.string.search_straight, distanceLabel(km, d.prefs.units.miles)) +
                    if (hit.name.isNotEmpty()) " · " + stringResource(source) else "",
                Typography.small, c.textMuted, maxLines = 1, modifier = Modifier.padding(top = 8.dp),
            )
        }
        // Con la lista llena no se agrega (antes se borraba el más viejo sin decir nada): el botón se apaga y abajo se dice por qué.
        val full = remember(d.prefs.favoritePlaces) { placesFromJson(d.prefs.favoritePlaces).size >= MAX_FAVORITES }
        FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(stringResource(R.string.route_button), onRoute, primary = true)
            if (appName != null) Button(stringResource(R.string.search_open_in, appName), onOpen)
            Button(stringResource(if (favorite) R.string.search_unfavorite else R.string.search_favorite), { d.prefs.toggleFavorite(hit) }, enabled = favorite || !full)
            // Casa y Trabajo con sus coordenadas: el punto exacto (también los de internet) y lo entiende cualquier app de mapas.
            if (d.prefs.homeAddress != coords) Button(stringResource(R.string.search_set_home), { d.prefs.homeAddress = coords })
            if (d.prefs.workAddress != coords) Button(stringResource(R.string.search_set_work), { d.prefs.workAddress = coords })
        }
        if (full && !favorite) {
            Label(stringResource(R.string.search_favorites_full, MAX_FAVORITES), Typography.small, c.textMuted, maxLines = 2, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** Abre el lugar en tu app de mapas (con sus coordenadas: cualquier app las entiende). */
internal fun openPlace(context: Context, app: App?, hit: SearchHit) = navigateTo(context, app, coordinates(hit))
