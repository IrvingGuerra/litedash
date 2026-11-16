// Idiomas descargables: un paquete es un JSON con los textos de LiteDash por nombre (formato en translations/README.md).
// Aquí solo lo que no necesita Android (leer, revisar y elegir el texto), para probarlo en la JVM.
package com.irvingguerra.litedash

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Los idiomas que vienen en la APK; los demás se descargan. */
internal val BUILT_IN_LANGUAGES = setOf("es", "en")

/** Tope del archivo de un idioma: ~1 000 textos ocupan ~70 KB; algo más grande no es un paquete nuestro. */
internal const val PACK_MAX_BYTES = 512 * 1024

/** Tope de cada texto (el más largo en inglés anda en ~300 caracteres). */
internal const val PACK_MAX_TEXT = 2_000

/** Un idioma bajado. Los textos ya pasaron la revisión de forma; los marcadores se revisan contra el inglés al usarlos. */
class LanguagePack(
    val tag: String,
    val name: String,
    /** El versionCode de LiteDash con el que se hizo (los textos más nuevos salen en inglés). */
    val base: Long,
    val strings: Map<String, String>,
    val plurals: Map<String, Map<String, String>>,
    val arrays: Map<String, List<String>>,
)

/** Un paquete que no se puede usar entero, con el motivo (va al registro de Android). */
class PackException(reason: String) : Exception(reason)

/** Etiqueta de idioma BCP 47 sencilla: "tr", "pt-BR", "zh-Hant". */
private val LANGUAGE_TAG = Regex("[a-z]{2,3}(-[A-Za-z0-9]{2,8}){0,2}")

/** Nombre de recurso de Android: lo que se puede buscar con getIdentifier. */
private val RESOURCE_NAME = Regex("[a-z0-9_]{1,100}")

/** Las categorías de plural de Android (CLDR). */
private val PLURAL_CATEGORIES = setOf("zero", "one", "two", "few", "many", "other")

/**
 * Idiomas de derecha a izquierda: fuera por ahora (la interfaz no está revisada al espejo: flechas, barra lateral,
 * medidores). Un paquete de estos se rechaza entero.
 */
private val RIGHT_TO_LEFT = setOf("ar", "arc", "ckb", "dv", "fa", "he", "iw", "ks", "ps", "sd", "ug", "ur", "yi")

/** ¿[tag] puede ser un idioma descargable? (bien escrito, no incluido y no de derecha a izquierda). */
internal fun isDownloadableTag(tag: String): Boolean {
    val language = tag.substringBefore('-')
    return LANGUAGE_TAG.matches(tag) && language !in BUILT_IN_LANGUAGES && language !in RIGHT_TO_LEFT
}

/**
 * Lee un paquete. Lanza [PackException] o [JSONException] si no sirve entero (JSON ilegible, idioma mal escrito o de
 * derecha a izquierda, muy grande): entonces se queda el idioma incluido. Un texto suelto que no sirve (vacío, muy
 * largo, clave rara) solo se quita y sale en inglés.
 */
internal fun parseLanguagePack(text: String): LanguagePack {
    if (text.length > PACK_MAX_BYTES) throw PackException("too big: ${text.length}")
    val o = JSONObject(text)
    val tag = o.getString("lang")
    if (tag.substringBefore('-') in RIGHT_TO_LEFT) throw PackException("right-to-left languages are not supported yet: $tag")
    if (!isDownloadableTag(tag)) throw PackException("bad language tag: $tag")
    val name = o.getString("name").trim().takeIf { it.isNotEmpty() && it.length <= 40 } ?: throw PackException("bad name")
    val plurals = o.optJSONObject("plurals")?.entries { it as? JSONObject }?.mapValues { (_, forms) ->
        forms.entries { it as? String }.filter { (category, form) -> category in PLURAL_CATEGORIES && fitsLength(form) }
    }?.filterValues { it.isNotEmpty() }
    val arrays = o.optJSONObject("arrays")?.entries { it as? JSONArray }?.mapValues { (_, items) ->
        (0 until items.length()).map { items.opt(it) as? String }
    }?.filterValues { items -> items.all { it != null && fitsLength(it) } }?.mapValues { (_, items) -> items.filterNotNull() }
    return LanguagePack(
        tag = tag,
        name = name,
        base = o.optLong("base"),
        strings = o.optJSONObject("strings")?.entries { it as? String }?.filterValues(::fitsLength).orEmpty(),
        plurals = plurals.orEmpty(),
        arrays = arrays.orEmpty(),
    )
}

private fun fitsLength(text: String) = text.isNotBlank() && text.length <= PACK_MAX_TEXT

/** Las parejas clave → valor de [this] cuyo valor [cast] acepta y cuya clave es un nombre de recurso. */
private fun <T> JSONObject.entries(cast: (Any?) -> T?): Map<String, T> {
    val out = HashMap<String, T>(length() * 2)
    for (key in keys()) {
        if (!RESOURCE_NAME.matches(key)) continue
        cast(opt(key))?.let { out[key] = it }
    }
    return out
}

/** Un marcador de String.format: %s, %d, %1$s, %.1f, %02d, %%… (grupo 1 = posición, grupo 2 = lo demás, que acaba en la conversión). */
private val MARKER = Regex("""%(\d+\$)?([-#+ 0,(<]*\d*(?:\.\d+)?[a-zA-Z%])""")

/**
 * Los marcadores de [text]: los que no dependen del orden (con posición, "1\$s", y los que no toman dato, "%%" y "%n"),
 * los que toman el siguiente dato ("d", en su orden) y si queda algún "%" suelto. Cada uno va completo (banderas, ancho,
 * precisión y conversión tal cual): "%#s", "%D" o "%.2d" no son "%s" ni "%d", y String.format truena con ellos.
 */
private data class Markers(val positional: List<String>, val ordered: List<String>, val stray: Boolean)

private fun markers(text: String): Markers {
    val positional = ArrayList<String>()
    val ordered = ArrayList<String>()
    for (m in MARKER.findAll(text)) {
        val spec = m.groupValues[2]
        if (m.groupValues[1].isEmpty() && spec != "%" && spec != "n") ordered += spec else positional += m.groupValues[1] + spec
    }
    positional.sort()
    return Markers(positional, ordered, '%' in MARKER.replace(text, ""))
}

/** ¿[text] trae exactamente los marcadores de [english]? Si no, String.format podría tronar o mostrar otro dato. */
internal fun sameMarkers(text: String, english: String) = markers(text) == markers(english)

/**
 * ¿La forma de plural [form] se puede usar con los datos de [englishOther] (la forma "other" del inglés)? Puede omitir
 * datos (el turco "1 gün" no lleva el número), pero no pedir uno que no llega: los de posición, de entre los del inglés;
 * los que van sin posición, solo los primeros (quitar uno de en medio correría los demás).
 */
internal fun pluralFits(form: String, englishOther: String): Boolean {
    val own = markers(form)
    val en = markers(englishOther)
    return own.stray == en.stray && en.positional.containsAll(own.positional) && en.ordered.take(own.ordered.size) == own.ordered
}

/** El texto de [name] en [pack] si sus marcadores son los del inglés; null = usar el inglés. */
internal fun LanguagePack.text(name: String, english: String): String? = strings[name]?.takeIf { sameMarkers(it, english) }

/** Las formas del plural [name] que se pueden usar con los datos del inglés ([englishOther]); vacío = inglés. */
internal fun LanguagePack.plural(name: String, englishOther: String): Map<String, String> =
    plurals[name]?.filterValues { pluralFits(it, englishOther) }.orEmpty()

/** La lista [name] si tiene los mismos renglones que la inglesa y cada uno sus marcadores; null = inglés. */
internal fun LanguagePack.array(name: String, english: Array<String>): Array<String>? =
    arrays[name]?.takeIf { it.size == english.size && it.indices.all { i -> sameMarkers(it[i], english[i]) } }?.toTypedArray()

/**
 * La categoría de plural sin las reglas de ICU (Android 6, que no las trae a las apps): "one" para 1 si el paquete la
 * tiene, y "other" para todo lo demás. Basta para los idiomas como el turco o el alemán; en los que tienen "few" o
 * "many" (ruso, polaco) esas formas salen como "other" en Android 6.
 */
internal fun simplePluralCategory(quantity: Int, available: Set<String>) =
    if (quantity == 1 && "one" in available) "one" else "other"
