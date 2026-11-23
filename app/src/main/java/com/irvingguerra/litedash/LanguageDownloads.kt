// Más idiomas: la lista y cada idioma se bajan de litedash.dev al elegirlos (la APK no crece). Ver tools/lang.py.
package com.irvingguerra.litedash

import android.content.Context
import android.util.Log
import com.irvingguerra.litedash.core.ui.Https
import com.irvingguerra.litedash.core.ui.isRecent
import java.io.File
import java.io.IOException
import org.json.JSONException
import org.json.JSONObject

/**
 * Un idioma de la lista: [progress] = % de textos traducidos; [sha256] = su versión (cambia con cada arreglo);
 * [reviewed] = lo revisó un hablante nativo (si no, es traducción automática).
 */
class LanguageChoice(val tag: String, val name: String, val progress: Int, val bytes: Long, val sha256: String, val reviewed: Boolean = false)

private val SHA256 = Regex("[0-9a-f]{64}")

/** Los idiomas de un `index.json` que la app puede usar; null si el texto no es esa lista. */
internal fun parseLanguageIndex(text: String): List<LanguageChoice>? = try {
    val list = JSONObject(text).getJSONArray("languages")
    (0 until list.length()).mapNotNull { i ->
        val o = list.getJSONObject(i)
        LanguageChoice(o.getString("lang"), o.getString("name"), o.optInt("progress"), o.optLong("bytes"), o.getString("sha256"), o.optBoolean("reviewed"))
            .takeIf { isDownloadableTag(it.tag) && it.sha256.matches(SHA256) && it.bytes in 1..PACK_MAX_BYTES && it.name.isNotBlank() }
    }
} catch (e: JSONException) {
    null
}

/**
 * La lista de idiomas para bajar (en IO): se pide a lo más una vez al día y se guarda junto a los paquetes, así sin
 * internet se siguen viendo (y se pueden elegir) los ya bajados. null si nunca se ha podido bajar.
 */
fun fetchLanguages(context: Context): List<LanguageChoice>? {
    val file = File(LanguagePacks.folder(context), "index.json")
    // Al día, y de esta versión de la app: tras actualizarla se vuelve a pedir (si no, quien abrió la lista con una
    // versión vieja seguía viendo la de entonces hasta un día después). Con el reloj atrás (guardada «en el futuro»), no.
    val fresh = file.exists() && isRecent(file.lastModified(), System.currentTimeMillis(), LIST_MAX_AGE_MS) &&
        file.lastModified() > appUpdatedAt(context)
    // Una lista ilegible (p. ej. la página de un wifi con registro) no reemplaza a la buena.
    val downloaded = if (fresh) null else try {
        replaceFile(file) { temp ->
            Https.get(context, BuildConfig.LANGUAGES_URL + "index.json").inputStream.use { input -> temp.outputStream().use { input.copyTo(it) } }
            parseLanguageIndex(temp.readText()) ?: throw IOException("unreadable index.json")
        }
    } catch (e: IOException) {
        Log.w(LOG_TAG, "No se pudo bajar la lista de idiomas", e)
        null
    }
    return downloaded ?: try {
        if (file.exists()) parseLanguageIndex(file.readText()) else null
    } catch (e: IOException) {
        null
    }
}

/**
 * Deja listo el idioma [choice] (en IO): si el bajado ya es esa versión, nada; si no, lo baja, revisa su SHA-256 y que
 * se pueda usar, y solo entonces reemplaza al anterior. Sin internet sirve el que ya estaba. false = no hay ninguno.
 */
fun installLanguage(context: Context, choice: LanguageChoice, progress: (Float) -> Unit): Boolean = synchronized(LanguagePacks) {
    // Uno a la vez: la revisión al arrancar y Ajustes podrían bajar el mismo idioma al mismo temporal.
    val file = LanguagePacks.file(context, choice.tag)
    if (file.exists() && Https.sha256(file) == choice.sha256) return@synchronized true
    try {
        replaceFile(file) { temp ->
            val sha = Https.download(context, BuildConfig.LANGUAGES_URL + "${choice.tag}.json", temp, choice.bytes, null, progress)
            if (sha != choice.sha256) throw IOException("${choice.tag}.json: sha256 $sha")
            LanguagePacks.read(temp, choice.tag) ?: throw IOException("${choice.tag}.json unusable")
        }
        LanguagePacks.changed()
        true
    } catch (e: IOException) {
        Log.w(LOG_TAG, "No se pudo bajar el idioma ${choice.tag}", e)
        file.exists()
    }
}

/**
 * Al abrir la app con un idioma bajado: si la lista trae una versión nueva, se baja. Los textos de la aplicación (avisos,
 * servicios) la toman al momento; la ventana abierta, cuando se vuelve a crear.
 */
fun updateLanguage(context: Context, tag: String) {
    val choice = fetchLanguages(context)?.firstOrNull { it.tag == tag } ?: return
    installLanguage(context, choice) {}
}

/** Cuándo se instaló o actualizó esta app por última vez (0 si no se sabe). */
internal fun appUpdatedAt(context: Context): Long =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(0L)
