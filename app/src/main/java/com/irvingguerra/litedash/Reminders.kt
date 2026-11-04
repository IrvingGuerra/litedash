package com.irvingguerra.litedash

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import org.json.JSONArray
import org.json.JSONObject

/** Trámites y cuidados del auto; [months] es cada cuánto se repite al marcarlo como hecho (0 = no se repite). */
enum class ReminderType(val stored: String, val title: Int, val months: Long) {
    SERVICE("service", R.string.reminder_service, 6),
    INSPECTION("inspection", R.string.reminder_verification, 6),
    TAX("tax", R.string.reminder_tax, 12),
    INSURANCE("insurance", R.string.reminder_insurance, 12),
    PLATES("plates", R.string.reminder_plates, 0),
    OTHER("other", R.string.reminder_other, 0),
}

data class Reminder(val id: Long, val type: ReminderType, val date: Day)

/** Recordatorios guardados en el dispositivo; nada corre en segundo plano: se revisan al ver el inicio. */
class ReminderRepository(context: Context) {
    private val store = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    var all by mutableStateOf(read())
        private set

    /** Los que vencen en los próximos [DAYS_BEFORE] días o ya vencieron, el más próximo primero. */
    fun due(today: Day = Day.today()) = all.filter { !it.date.isAfter(today.plusDays(DAYS_BEFORE)) }.sortedBy { it.date }

    fun add(type: ReminderType, date: Day) = save(all + Reminder(System.currentTimeMillis(), type, date))

    fun remove(r: Reminder) = save(all - r)

    /** Hecho: si se repite, pasa a la siguiente fecha; si no, se quita. */
    fun done(r: Reminder) = save(
        if (r.type.months > 0) all.map { if (it == r) r.copy(date = r.date.plusMonths(r.type.months)) else it } else all - r,
    )

    private fun save(list: List<Reminder>) {
        all = list.sortedBy { it.date }
        store.edit {
            putString(KEY, JSONArray(all.map { JSONObject().put("id", it.id).put("type", it.type.stored).put("date", it.date.toString()) }).toString())
        }
    }

    private fun read(): List<Reminder> = runCatching {
        val a = JSONArray(store.getString(KEY, "[]"))
        (0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i)
            val type = ReminderType.entries.firstOrNull { it.stored == o.getString("type") } ?: return@mapNotNull null
            Reminder(o.getLong("id"), type, Day.parse(o.getString("date")))
        }.sortedBy { it.date }
    }.getOrDefault(emptyList())

    private companion object {
        const val STORE = "reminders"
        const val KEY = "list"
        const val DAYS_BEFORE = 7L
    }
}

@Composable
private fun formatDate(date: Day): String {
    val locale = currentLocale()
    return DateFormat.getDateInstance(DateFormat.LONG, locale).format(date.toDate())
}

/** "en 3 días", "hoy", "venció hace 2 días". */
@Composable
private fun whenText(date: Day): String {
    val days = Day.today().daysUntil(date).toInt()
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
    Notice("${stringResource(r.type.title)} · ${whenText(r.date)}", Icons.bell) {
        Button(stringResource(R.string.reminder_done), { repo.done(r) }, primary = true)
    }
}

/** Ajustes → Tu auto: la lista y cómo agregar uno (tipo y, abajo, su fecha en un calendario). */
@Composable
fun ReminderSettings(repo: ReminderRepository) {
    val c = LocalColors.current
    var picking by remember { mutableStateOf<ReminderType?>(null) }
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
        val type = picking
        if (type == null) {
            Label(stringResource(R.string.reminder_add), Typography.label, c.textMuted, modifier = Modifier.padding(top = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (t in ReminderType.entries) Button(stringResource(t.title), { picking = t })
            }
        } else {
            Label(stringResource(R.string.reminder_pick_date, stringResource(type.title)), Typography.label, c.textMuted, modifier = Modifier.padding(top = 6.dp))
            DatePicker(onPick = { repo.add(type, it); picking = null })
            Button(stringResource(R.string.cancel), { picking = null })
        }
    }
}

/** Calendario dentro de la tarjeta (sin ventanas emergentes): un mes a la vista, ‹ › para cambiarlo. */
@Composable
private fun DatePicker(onPick: (Day) -> Unit) {
    val c = LocalColors.current
    val locale = currentLocale()
    val today = remember { Day.today() }
    var month by remember { mutableStateOf(Day(today.year, today.month, 1)) }
    val title = remember(month, locale) { SimpleDateFormat("LLLL yyyy", locale).format(month.toDate()).replaceFirstChar { it.uppercase(locale) } }
    // Primer día de la semana según la región (domingo en México, lunes en España).
    val firstWeekday = remember(locale) { Calendar.getInstance(locale).firstDayOfWeek }
    val weekdays = remember(locale, firstWeekday) {
        val narrow = SimpleDateFormat("EEEEE", locale)
        // 1970-01-04 fue domingo: de ahí se cuentan los siete.
        (0 until 7).map { i -> narrow.format(Day.ofEpochDay(3L + (firstWeekday - 1 + i) % 7).toDate()) }
    }
    val length = (month.plusMonths(1).epochDay - month.epochDay).toInt()
    // Casillas vacías antes del día 1 (1970-01-01 fue jueves = 5 en Calendar).
    val lead = ((month.epochDay + 4).mod(7L).toInt() + 1 - firstWeekday).mod(7)
    Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(Icons.chevron, { month = month.plusMonths(-1) }, Modifier.graphicsLayer(scaleX = -1f), size = 48.dp)
            Label(title, Typography.subtitle, maxLines = 1, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
            IconButton(Icons.chevron, { month = month.plusMonths(1) }, size = 48.dp)
        }
        Row(Modifier.fillMaxWidth()) {
            for (w in weekdays) Label(w, Typography.small.copy(textAlign = TextAlign.Center), c.textMuted, 1, Modifier.weight(1f))
        }
        for (week in 0 until (lead + length + 6) / 7) {
            Row(Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val n = week * 7 + col - lead + 1
                    Box(Modifier.weight(1f).height(48.dp), contentAlignment = Alignment.Center) {
                        if (n in 1..length) {
                            val day = Day(month.year, month.month, n)
                            val isToday = day == today
                            Box(
                                Modifier.size(44.dp).clip(CircleShape)
                                    .background(if (isToday) c.surfaceHigh else Color.Transparent)
                                    .clickable { onPick(day) },
                                contentAlignment = Alignment.Center,
                            ) { Label("$n", Typography.body, if (day < today) c.textMuted else c.text, 1) }
                        }
                    }
                }
            }
        }
    }
}
