package com.irvingguerra.litedash

import android.content.res.Resources
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Radius
import com.irvingguerra.litedash.core.ui.Segmented
import com.irvingguerra.litedash.core.ui.TextInput
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.feature.apps.normalize
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Inicio y apariencia → Idioma y unidades → Idioma: los incluidos (siempre, también sin internet) y debajo "Más idiomas", los de
 * la comunidad que se bajan de litedash.dev al elegirlos. Elegir uno reabre la pantalla, como siempre al cambiar idioma.
 */
@Composable
internal fun LanguageSettings(prefs: Preferences) {
    val activity = LocalActivity.current
    val choose = { code: String ->
        if (prefs.language != code) {
            prefs.language = code
            // Al volver se abre Ajustes justo aquí (la pestaña se recuerda en memoria).
            openLanguageSettings()
            activity?.let(MainActivity::restart)
        }
    }
    Segmented(
        listOf("" to R.string.language_system, "es" to R.string.language_es, "en" to R.string.language_en).map { (code, text) -> code to stringResource(text) },
        prefs.language,
        choose,
    )
    MoreLanguages(prefs.language, choose)
}

@Composable
private fun MoreLanguages(current: String, choose: (String) -> Unit) {
    val c = LocalColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var choices by remember { mutableStateOf<List<LanguageChoice>?>(null) }
    // Sube con «Reintentar»: la lista se vuelve a pedir sin salir de Ajustes.
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        loaded = false
        choices = withContext(Dispatchers.IO) { fetchLanguages(context) }
        loaded = true
    }
    // El que se está bajando y cuánto lleva; solo uno a la vez.
    var busy by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var failed by remember { mutableStateOf(false) }
    Label(stringResource(R.string.language_more), Typography.label, c.textMuted)
    val list = choices
    when {
        !loaded -> Label(stringResource(R.string.language_loading), Typography.small, c.textMuted)
        list == null -> {
            Label(downloadError(R.string.language_offline), Typography.small, c.textMuted)
            Button(stringResource(R.string.retry), { attempt++ })
        }
        list.isEmpty() -> Label(stringResource(R.string.language_none), Typography.small, c.textMuted)
        else -> {
            // Cerrada, solo unos cuantos ([featuredLanguages]) para que la tarjeta no crezca con cada idioma nuevo; «Ver todos»
            // la abre aquí mismo. Escribiendo se busca en todos, abierta o no: el elegido, luego los del sistema y luego por nombre.
            var query by rememberSaveable { mutableStateOf("") }
            var expanded by rememberSaveable { mutableStateOf(false) }
            val locale = currentLocale()
            // Su nombre en su idioma, en el de la app ("alemán", "German") y su código: "aleman" encuentra "Deutsch".
            val keys = remember(list, locale) {
                list.associate { it.tag to normalize("${it.name} ${Locale.forLanguageTag(it.tag).getDisplayName(locale)} ${it.tag}") }
            }
            val q = normalize(query.trim())
            val system = remember { systemLanguages() }
            val ordered = remember(list, current) {
                list.sortedWith(
                    compareBy<LanguageChoice> { if (it.tag == current) 0 else 1 }
                        .thenBy { choice -> system.indexOf(Locale.forLanguageTag(choice.tag).language).let { if (it < 0) system.size else it } }
                        .thenBy { it.name.lowercase(Locale.ROOT) },
                )
            }
            val featured = remember(list, current) { featuredLanguages(list, current, system) }
            val shown = when {
                q.isNotEmpty() -> ordered.filter { it.tag == current || q in keys[it.tag].orEmpty() || busy == it.tag }
                expanded -> ordered
                else -> ordered.filter { choice -> choice in featured || busy == choice.tag }
            }
            TextInput(query, { query = it }, stringResource(R.string.language_search), Modifier.fillMaxWidth(), icon = true)
            if (q.isNotEmpty() && shown.none { it.tag != current }) Label(stringResource(R.string.language_not_found), Typography.small, c.textMuted)
            // En columnas según el ancho (cada celda de al menos LANGUAGE_CELL_MIN): 21 renglones no alargan la pantalla.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val columns = ((maxWidth + LANGUAGE_GAP) / (LANGUAGE_CELL_MIN + LANGUAGE_GAP)).toInt().coerceAtLeast(1)
                val cell: Dp = (maxWidth - LANGUAGE_GAP * (columns - 1)) / columns
                FlowRow(horizontalArrangement = Arrangement.spacedBy(LANGUAGE_GAP), verticalArrangement = Arrangement.spacedBy(LANGUAGE_GAP)) {
                    for (choice in shown) {
                        LanguageRow(choice, Modifier.width(cell), selected = choice.tag == current, downloading = if (busy == choice.tag) progress else null) {
                            if (busy != null || choice.tag == current) return@LanguageRow
                            busy = choice.tag
                            progress = 0f
                            failed = false
                            scope.launch {
                                // Termina aunque se cierre Ajustes: el archivo queda listo para la próxima vez.
                                val ok = withContext(NonCancellable + Dispatchers.IO) { installLanguage(context, choice) { progress = it } }
                                busy = null
                                if (ok) choose(choice.tag) else failed = true
                            }
                        }
                    }
                }
            }
            // Sin búsqueda y con más de los que se ven: abre o cierra la lista completa en la misma tarjeta.
            if (q.isEmpty() && list.size > featured.size) {
                Button(
                    if (expanded) stringResource(R.string.language_show_less) else pluralStringResource(R.plurals.language_show_all, list.size, list.size),
                    { expanded = !expanded },
                )
            }
            Label(stringResource(R.string.language_more_help), Typography.small, c.textMuted)
            Label(stringResource(R.string.language_report), Typography.small, c.textMuted)
        }
    }
    if (failed) Label(downloadError(R.string.language_failed), Typography.small, c.danger)
}

/** Un idioma de la lista: su nombre en su idioma, cuánto está traducido y su tamaño; la palomita si es el elegido. */
@Composable
private fun LanguageRow(choice: LanguageChoice, modifier: Modifier, selected: Boolean, downloading: Float?, onClick: () -> Unit) {
    val c = LocalColors.current
    val context = LocalContext.current
    Row(
        modifier.clip(RoundedCornerShape(Radius.inner)).background(c.surfaceHigh).clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Label(choice.name, Typography.body.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal), maxLines = 1)
            val details = if (downloading != null) {
                stringResource(R.string.downloading, (downloading * 100).roundToInt())
            } else {
                stringResource(R.string.language_details, choice.progress, Formatter.formatShortFileSize(context, choice.bytes)) +
                    " · " + stringResource(if (choice.reviewed) R.string.language_reviewed else R.string.language_machine)
            }
            Label(details, Typography.small, c.textMuted, 2)
        }
        if (selected) Icon(Icons.done, c.primary, 22.dp)
    }
}

/**
 * Los que se ven con la lista cerrada: el elegido, los del sistema que tengan paquete y luego los más usados
 * ([POPULAR_LANGUAGES]) hasta juntar [FEATURED_LANGUAGES], sin repetir.
 */
internal fun featuredLanguages(list: List<LanguageChoice>, current: String, system: List<String>): List<LanguageChoice> {
    val byLanguage = list.groupBy { Locale.forLanguageTag(it.tag).language }
    val picked = LinkedHashSet<LanguageChoice>()
    list.firstOrNull { it.tag == current }?.let(picked::add)
    for (language in system + POPULAR_LANGUAGES) {
        if (picked.size >= FEATURED_LANGUAGES) break
        byLanguage[language]?.firstOrNull { it !in picked }?.let(picked::add)
    }
    return picked.toList()
}

/** Cuántos se ven con la lista cerrada, y los más usados con que se completan, en orden. */
private const val FEATURED_LANGUAGES = 6
private val POPULAR_LANGUAGES = listOf("pt", "tr", "fr", "de", "ru", "it")

/** Lo menos que mide de ancho cada idioma de la lista, y el espacio entre ellos. */
private val LANGUAGE_CELL_MIN = 260.dp
private val LANGUAGE_GAP = 8.dp

/** Los idiomas del sistema (no los de la app, que puede ir en otro), en su orden: "de-AT" → "de". */
internal fun systemLanguages(): List<String> {
    val config = Resources.getSystem().configuration
    val locales = if (Build.VERSION.SDK_INT >= 24) {
        val list = config.locales
        List(list.size()) { list[it] }
    } else {
        @Suppress("DEPRECATION")
        listOf(config.locale)
    }
    return locales.map { it.language }.distinct()
}
