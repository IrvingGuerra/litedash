package com.irvingguerra.litedash

import android.text.format.Formatter
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
 * Inicio y apariencia → Interfaz → Idioma: los incluidos (siempre, también sin internet) y debajo "Más idiomas", los de
 * la comunidad que se bajan de litedash.dev al elegirlos. Elegir uno reabre la pantalla, como siempre al cambiar idioma.
 */
@Composable
internal fun LanguageSettings(prefs: Preferences) {
    val activity = LocalActivity.current
    val choose = { code: String ->
        if (prefs.language != code) {
            prefs.language = code
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
    LaunchedEffect(Unit) {
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
        !loaded -> Unit
        list == null -> Label(stringResource(R.string.language_offline), Typography.small, c.textMuted)
        list.isEmpty() -> Label(stringResource(R.string.language_none), Typography.small, c.textMuted)
        else -> {
            // No la lista completa (crecerá): el idioma elegido arriba y un buscador para los demás.
            var query by rememberSaveable { mutableStateOf("") }
            val locale = currentLocale()
            // Su nombre en su idioma, en el de la app ("alemán", "German") y su código: "aleman" encuentra "Deutsch".
            val keys = remember(list, locale) {
                list.associate { it.tag to normalize("${it.name} ${Locale.forLanguageTag(it.tag).getDisplayName(locale)} ${it.tag}") }
            }
            val q = normalize(query.trim())
            val shown = list.filter { it.tag == current || (q.isNotEmpty() && q in keys[it.tag].orEmpty()) || busy == it.tag }
                .sortedBy { if (it.tag == current) 0 else 1 }
            TextInput(query, { query = it }, stringResource(R.string.language_search), Modifier.fillMaxWidth(), icon = true)
            if (q.isNotEmpty() && shown.none { it.tag != current }) Label(stringResource(R.string.language_not_found), Typography.small, c.textMuted)
            for (choice in shown) {
                LanguageRow(choice, selected = choice.tag == current, downloading = if (busy == choice.tag) progress else null) {
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
            Label(stringResource(R.string.language_more_help), Typography.small, c.textMuted)
            Label(stringResource(R.string.language_report), Typography.small, c.textMuted)
        }
    }
    if (failed) Label(stringResource(R.string.language_failed), Typography.small, c.danger)
}

/** Un idioma de la lista: su nombre en su idioma, cuánto está traducido y su tamaño; la palomita si es el elegido. */
@Composable
private fun LanguageRow(choice: LanguageChoice, selected: Boolean, downloading: Float?, onClick: () -> Unit) {
    val c = LocalColors.current
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.inner)).background(c.surfaceHigh).clickable(onClick = onClick)
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
