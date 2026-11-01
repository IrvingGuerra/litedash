package com.irvingguerra.litedash

import android.app.DatePickerDialog
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

/** Trámites y cuidados del auto; [months] es cada cuánto se repite al marcarlo como hecho (0 = no se repite). */
enum class ReminderType(val stored: String, val title: Int, val months: Long) {
    SERVICE("servicio", R.string.reminder_service, 6),
    VERIFICATION("verificacion", R.string.reminder_verification, 6),
    TAX("tenencia", R.string.reminder_tax, 12),
    INSURANCE("seguro", R.string.reminder_insurance, 12),
    PLATES("placas", R.string.reminder_plates, 0),
    OTHER("otro", R.string.reminder_other, 0),
}

data class Reminder(val id: Long, val type: ReminderType, val date: LocalDate)

/** Recordatorios guardados en el dispositivo; nada corre en segundo plano: se revisan al ver el inicio. */
class ReminderRepository(context: Context) {
    private val store = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    var all by mutableStateOf(read())
        private set

    /** Los que vencen en los próximos [DAYS_BEFORE] días o ya vencieron, el más próximo primero. */
    fun due(today: LocalDate = LocalDate.now()) = all.filter { !it.date.isAfter(today.plusDays(DAYS_BEFORE)) }.sortedBy { it.date }

    fun add(type: ReminderType, date: LocalDate) = save(all + Reminder(System.currentTimeMillis(), type, date))

    fun remove(r: Reminder) = save(all - r)

    /** Hecho: si se repite, pasa a la siguiente fecha; si no, se quita. */
    fun done(r: Reminder) = save(all.map { if (it == r && r.type.months > 0) r.copy(date = r.date.plusMonths(r.type.months)) else it }.filter { it != r || r.type.months > 0 })

    private fun save(list: List<Reminder>) {
        all = list.sortedBy { it.date }
        store.edit {
            putString(KEY, JSONArray(all.map { JSONObject().put("id", it.id).put("tipo", it.type.stored).put("fecha", it.date.toString()) }).toString())
        }
    }

    private fun read(): List<Reminder> = runCatching {
        val a = JSONArray(store.getString(KEY, "[]"))
        (0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i)
            val type = ReminderType.entries.firstOrNull { it.stored == o.getString("tipo") } ?: return@mapNotNull null
            Reminder(o.getLong("id"), type, LocalDate.parse(o.getString("fecha")))
        }.sortedBy { it.date }
    }.getOrDefault(emptyList())

    private companion object {
        const val STORE = "recordatorios"
        const val KEY = "lista"
        const val DAYS_BEFORE = 7L
    }
}

@Composable
private fun formatDate(date: LocalDate): String {
    val locale = LocalConfiguration.current.locales[0]
    return DateFormat.getDateInstance(DateFormat.LONG, locale).format(Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant()))
}

/** "en 3 días", "hoy", "venció hace 2 días". */
@Composable
private fun whenText(date: LocalDate): String {
    val days = LocalDate.now().until(date).let { java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), date).toInt() }
    return when {
        days == 0 -> stringResource(R.string.reminder_today)
        days > 0 -> pluralStringResource(R.plurals.reminder_in_days, days, days)
        else -> pluralStringResource(R.plurals.reminder_overdue, -days, -days)
    }
}

/** Aviso en el inicio del recordatorio más próximo; "Hecho" lo pasa a la siguiente fecha. */
@Composable
fun ReminderBanner(repo: ReminderRepository) {
    val r = repo.due().firstOrNull() ?: return
    val c = LocalColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(c.surface)
            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.bell, c.accent, 22.dp)
        Label("${stringResource(r.type.title)} · ${whenText(r.date)}", Typography.body, maxLines = 1, modifier = Modifier.weight(1f))
        Button(stringResource(R.string.reminder_done), { repo.done(r) }, primary = true)
    }
}

/** Ajustes → Tu auto: la lista y cómo agregar uno (tipo y fecha). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReminderSettings(repo: ReminderRepository) {
    val c = LocalColors.current
    val context = LocalContext.current
    var type by remember { mutableStateOf<ReminderType?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (r in repo.all) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Label(stringResource(r.type.title), Typography.subtitle, maxLines = 1)
                    Label("${formatDate(r.date)} · ${whenText(r.date)}", Typography.small, c.textMuted, maxLines = 1)
                }
                Button(stringResource(R.string.remove), { repo.remove(r) })
            }
        }
        Label(stringResource(R.string.reminder_add), Typography.label, c.textMuted, modifier = Modifier.padding(top = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (t in ReminderType.entries) {
                Button(stringResource(t.title), {
                    type = t
                    // Selector de fecha del sistema (no agrega librerías).
                    val today = LocalDate.now()
                    DatePickerDialog(context, { _, y, m, d -> repo.add(t, LocalDate.of(y, m + 1, d)); type = null }, today.year, today.monthValue - 1, today.dayOfMonth).show()
                }, primary = type == t)
            }
        }
    }
}
