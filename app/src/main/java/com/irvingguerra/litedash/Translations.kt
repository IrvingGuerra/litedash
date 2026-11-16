// El motor de los idiomas descargables: unos Resources que sacan los textos de LiteDash del paquete bajado.
package com.irvingguerra.litedash

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.icu.text.PluralRules
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File
import java.io.IOException
import java.util.IllegalFormatException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONException

/** Los paquetes bajados: `files/lang/<idioma>.json`. El activo se lee una vez por proceso. */
object LanguagePacks {
    fun folder(context: Context) = File(context.filesDir, "lang")

    fun file(context: Context, tag: String) = File(folder(context), "$tag.json")

    @Volatile private var loaded: Pair<String, LanguagePack?>? = null

    /** Sube cada vez que cambia un paquete en disco: quien guarda unos Resources traducidos los rehace. */
    @Volatile var generation = 0
        private set

    /**
     * El paquete de [tag] ya bajado; null si no está o no sirve. Se lee del disco la primera vez (en el hilo que
     * arma la primera ventana: ~70 KB, antes del primer cuadro para no pintarla en inglés y luego cambiar).
     */
    fun active(context: Context, tag: String): LanguagePack? {
        loaded?.let { (t, pack) -> if (t == tag) return pack }
        return read(file(context, tag), tag).also { loaded = tag to it }
    }

    /** El paquete en [file] si es de [tag] y se puede usar entero; si no, null (y el motivo al registro). */
    fun read(file: File, tag: String): LanguagePack? = try {
        when {
            !file.exists() -> null
            file.length() > PACK_MAX_BYTES -> throw PackException("too big: ${file.length()}")
            else -> parseLanguagePack(file.readText()).also { if (it.tag != tag) throw PackException("${it.tag} in $tag.json") }
        }
    } catch (e: IOException) {
        Log.w(LOG_TAG, "No se pudo leer el idioma $tag", e)
        null
    } catch (e: JSONException) {
        Log.w(LOG_TAG, "Idioma $tag ilegible", e)
        null
    } catch (e: PackException) {
        Log.w(LOG_TAG, "Idioma $tag rechazado: ${e.message}")
        null
    }

    /** Un paquete cambió en disco (se bajó o se actualizó): la próxima ventana lo vuelve a leer. */
    fun changed() {
        loaded = null
        generation++
    }
}

/**
 * [base] (ya con la configuración del idioma bajado: fechas, números y orden alfabético en ese idioma) con los textos
 * de [pack]; lo que el paquete no trae sale de [english].
 */
internal class TranslatedContext(base: Context, english: Resources, pack: LanguagePack, locale: Locale) : ContextWrapper(base) {
    private val translated = TranslatedResources(base.resources, english, pack, locale)

    override fun getResources(): Resources = translated
}

/**
 * Los mismos recursos de [base], pero los textos de LiteDash (id 0x7f…) se buscan por NOMBRE en [pack]: si falta o sus
 * marcadores no son los del inglés, salen de [english] (no de `values/`, que es español). Lo de Android pasa de largo,
 * y lo de las librerías sale igual que antes: la APK solo trae sus textos en inglés y español. Cada texto se resuelve
 * una vez (por id) y se recuerda. Por aquí pasan getString/getText/getQuantityString de los contextos y
 * stringResource/pluralStringResource/stringArrayResource de Compose.
 */
@Suppress("DEPRECATION") // El único constructor público; los que usa Android por dentro son ocultos.
internal class TranslatedResources(
    private val base: Resources,
    private val english: Resources,
    private val pack: LanguagePack,
    private val locale: Locale,
) : Resources(base.assets, base.displayMetrics, base.configuration) {
    private val texts = ConcurrentHashMap<Int, CharSequence>()
    private val plurals = ConcurrentHashMap<Int, Map<String, String>>()

    /** La categoría de plural de una cantidad: con las reglas del idioma (Android 7+); en Android 6, la sencilla. */
    private val category: ((Int, Set<String>) -> String) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) icuCategory(locale) else ::simplePluralCategory

    override fun getText(id: Int): CharSequence {
        if (!ours(id)) return super.getText(id)
        return texts.getOrPut(id) {
            val en = english.getText(id)
            pack.text(getResourceEntryName(id), en.toString()) ?: en
        }
    }

    override fun getText(id: Int, def: CharSequence?): CharSequence? = if (ours(id)) getText(id) else super.getText(id, def)

    override fun getQuantityText(id: Int, quantity: Int): CharSequence {
        if (!ours(id)) return super.getQuantityText(id, quantity)
        // "other" del inglés: la que trae todos los datos (con 0, el inglés siempre elige "other").
        val forms = plurals.getOrPut(id) { pack.plural(getResourceEntryName(id), english.getQuantityText(id, 0).toString()) }
        return forms[category(quantity, forms.keys)] ?: english.getQuantityText(id, quantity)
    }

    // Segunda red: si un texto traducido no se puede formatear con sus datos (un paquete con un marcador que la
    // validación no vio), sale el inglés en vez de cerrarse la app.
    override fun getString(id: Int, vararg formatArgs: Any?): String = try {
        super.getString(id, *formatArgs)
    } catch (e: IllegalFormatException) {
        if (ours(id)) english.getString(id, *formatArgs) else throw e
    }

    override fun getQuantityString(id: Int, quantity: Int, vararg formatArgs: Any?): String = try {
        super.getQuantityString(id, quantity, *formatArgs)
    } catch (e: IllegalFormatException) {
        if (ours(id)) english.getQuantityString(id, quantity, *formatArgs) else throw e
    }

    override fun getStringArray(id: Int): Array<String> {
        if (!ours(id)) return super.getStringArray(id)
        val en = english.getStringArray(id)
        return pack.array(getResourceEntryName(id), en) ?: en
    }

    /**
     * La ventana cambió (giro, modo noche, densidad; LiteDash los maneja sin rehacerse): Android pone al día sus
     * recursos, pero no estos (no los conoce). Va antes de que la ventana avise a sus vistas, que comparan contra ellos.
     */
    fun sync(config: Configuration) = updateConfiguration(Configuration(config).apply { setLocale(locale) }, base.displayMetrics)

    private companion object {
        /** Los recursos propios de la app (incluidos los de sus módulos y librerías) llevan el paquete 0x7f. */
        fun ours(id: Int) = id ushr 24 == 0x7f

        @RequiresApi(Build.VERSION_CODES.N)
        fun icuCategory(locale: Locale): (Int, Set<String>) -> String {
            val rules = PluralRules.forLocale(locale)
            return { quantity, _ -> rules.select(quantity.toDouble()) }
        }
    }
}

/** Pone al día [resources] tras un cambio de configuración si son los de un idioma bajado (si no, Android ya lo hizo). */
internal fun syncTranslated(resources: Resources, config: Configuration) {
    (resources as? TranslatedResources)?.sync(config)
}
