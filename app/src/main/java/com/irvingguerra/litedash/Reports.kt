package com.irvingguerra.litedash

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.TextInput
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.core.ui.storageReport
import com.irvingguerra.litedash.feature.map.MapPacks
import com.irvingguerra.litedash.feature.link.PhoneLink
import com.irvingguerra.litedash.feature.music.AppProbe
import com.irvingguerra.litedash.feature.music.SoundPiece
import com.irvingguerra.litedash.feature.music.SystemSource
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/*
 * "Enviar un reporte" (Ajustes → Acerca de y ayuda → Reportar un problema): el usuario elige con qué hay un problema,
 * cuál (si aplica) y qué pasa, y se escribe `reports/{id}` en Firestore por REST con eso, su nota
 * y los datos técnicos de la pantalla ([systemInfo]). Solo al tocar Enviar; nadie los lee desde la app.
 */

/** Variantes de un módulo; [key] es lo que se guarda. */
private enum class ReportDetail(val key: String, val title: Int) {
    USB_PLAYER("usb", R.string.report_music_usb),
    HEAD_BLUETOOTH("bluetooth", R.string.report_music_bt),
    MUSIC_APP("app", R.string.report_music_app),
    FM("fm", R.string.report_radio_fm),
    INTERNET("internet", R.string.report_radio_internet),
    USB_RECEIVER("usb", R.string.tires_source_usb),
    BLUETOOTH("bluetooth", R.string.sys_bluetooth),
    CAR_OBD("obd", R.string.tires_source_obd),
    WIFI("wifi", R.string.sys_wifi),
    USB("usb", R.string.report_obd_usb),
}

/** Qué pasa; [key] es lo que se guarda. */
private enum class ReportProblem(val key: String, val title: Int) {
    SILENT("silent", R.string.report_silent),
    NO_INFO("no_info", R.string.report_no_info),
    BUTTONS("buttons", R.string.report_buttons),
    BROKEN("broken", R.string.report_broken),
    LOOKS_WRONG("looks_wrong", R.string.report_looks_wrong),
    CRASH("crash", R.string.report_crash),
    // Solo en Mapa: pedir el mapa sin conexión de un país o región que aún no está (la nota dice cuál).
    WANT_MAP("want_map", R.string.report_want_map),
    OTHER("other", R.string.reminder_other),
}

private val AUDIO_PROBLEMS = listOf(ReportProblem.SILENT, ReportProblem.NO_INFO, ReportProblem.BUTTONS, ReportProblem.OTHER)
private val GENERIC_PROBLEMS = listOf(ReportProblem.BROKEN, ReportProblem.LOOKS_WRONG, ReportProblem.CRASH, ReportProblem.OTHER)

/** Con qué hay un problema; [audio] = Música y Radio (sus problemas y la ayuda de ponerla a sonar). */
private enum class ReportModule(val key: String, val title: Int, val details: List<ReportDetail> = emptyList(), val audio: Boolean = false) {
    MUSIC("music", R.string.tile_music, listOf(ReportDetail.USB_PLAYER, ReportDetail.HEAD_BLUETOOTH, ReportDetail.MUSIC_APP), audio = true),
    RADIO("radio", R.string.tile_radio, listOf(ReportDetail.FM, ReportDetail.INTERNET), audio = true),
    CAR("car", R.string.tile_car),
    // Con OBD: el aviso de Llantas por OBD pide justo este reporte para agregar el modelo.
    TIRES("tires", R.string.tires, listOf(ReportDetail.USB_RECEIVER, ReportDetail.BLUETOOTH, ReportDetail.CAR_OBD)),
    WORKSHOP("workshop", R.string.report_workshop, listOf(ReportDetail.BLUETOOTH, ReportDetail.WIFI, ReportDetail.USB)),
    MAP("map", R.string.templates_tab_map),
    SPLIT("split", R.string.tab_split),
    PROJECTION("projection", R.string.tile_projection),
    LIGHTS("lights", R.string.report_lights),
    UPDATES("updates", R.string.tab_updates),
    OTHER("other", R.string.reminder_other),
    ;

    val problems get() = when {
        audio -> AUDIO_PROBLEMS
        this == MAP -> listOf(ReportProblem.WANT_MAP) + GENERIC_PROBLEMS
        else -> GENERIC_PROBLEMS
    }
}

/** Los módulos que tienen sentido en esta pantalla: sin Taller con el OBD apagado, sin dividida donde no se puede, sin actualizaciones en Play. */
private fun offeredModules(d: Dependencies): List<ReportModule> = ReportModule.entries.filter {
    when (it) {
        ReportModule.WORKSHOP -> d.obdModule
        ReportModule.SPLIT -> SplitScreen.supported()
        ReportModule.UPDATES -> d.updates != null
        else -> true
    }
}

private sealed interface ReportState {
    data object Idle : ReportState
    data object Sending : ReportState
    data object Error : ReportState
    data object Wait : ReportState
    data class Sent(val code: String) : ReportState
}

/** El reporte que pidió el aviso "No encontramos cómo hacerlo sonar aquí"; lo toma la tarjeta al abrirse. Solo en memoria. */
private var pendingSound: SoundPiece? = null

/** "Enviar un reporte" ya en "Música · Bluetooth" (o "Radio · FM") con "No suena": Ajustes → Acerca de y ayuda → Reportar un problema. */
internal fun openSoundReport(piece: SoundPiece) {
    pendingSound = piece
    forgetLastResult()
    selectTab(SYSTEM_TABS, SystemTab.REPORT.ordinal)
}

/** El reporte que pidió «¿No está tu país? Pídelo» (Ajustes → Apps → Navegación → Sin conexión). Solo en memoria. */
private var pendingMapRequest = false

/** "Enviar un reporte" ya en "Mapa" con "Quiero el mapa de mi país": Ajustes → Acerca de y ayuda → Reportar un problema. */
internal fun openMapRequest() {
    pendingMapRequest = true
    forgetLastResult()
    selectTab(SYSTEM_TABS, SystemTab.REPORT.ordinal)
}

/** El reporte que pidió «Aprender luces» cuando no cambió nada (Ajustes → Tema y brillo). Solo en memoria. */
private var pendingLights = false

/** "Enviar reporte" ya en "Día y noche / faros" con "No funciona": Ajustes → Acerca de y ayuda → Reportar un problema. */
internal fun openLightsReport() {
    pendingLights = true
    forgetLastResult()
    selectTab(SYSTEM_TABS, SystemTab.REPORT.ordinal)
}

/** Hay un reporte esperando (sonido, mapa o luces): «Reportar un problema» pone la tarjeta arriba (si no, en pantallas bajitas queda fuera de la vista). */
internal fun reportPending() = pendingSound != null || pendingMapRequest || pendingLights

private const val NOTE_MAX = 500
private const val INFO_MAX = 20_000
private const val REPORT_GAP_MS = 60_000L

/** Cuándo se mandó el último (reloj desde el arranque): a lo más uno por minuto. */
private var lastReport = 0L

/**
 * El envío vive en la app, no en la tarjeta: si se cambia de pestaña a medio enviar, el reporte llega igual, el
 * resultado se ve al volver y no se puede mandar otro encima.
 */
private val reportScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
private var reportState by mutableStateOf<ReportState>(ReportState.Idle)

/**
 * Un reporte nuevo ya elegido (sonido, mapa o luces) no espera a que se toque «Listo» del anterior: el «Gracias», el error o
 * la espera de la vez pasada se olvidan (uno que aún se está mandando sigue y se ve en su botón).
 */
private fun forgetLastResult() {
    if (reportState != ReportState.Sending) reportState = ReportState.Idle
}

/** La tarjeta con el flujo por pasos; siempre se puede regresar al anterior. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SendReport(d: Dependencies) {
    val c = LocalColors.current
    val context = LocalContext.current.applicationContext
    val sound = remember { pendingSound.also { pendingSound = null } }
    val mapRequest = remember { pendingMapRequest.also { pendingMapRequest = false } }
    val lightsRequest = remember { pendingLights.also { pendingLights = false } }
    var module by remember {
        mutableStateOf(
            when {
                mapRequest -> ReportModule.MAP
                lightsRequest -> ReportModule.LIGHTS
                else -> sound?.let { if (it == SoundPiece.MUSIC) ReportModule.MUSIC else ReportModule.RADIO }
            },
        )
    }
    var detail by remember { mutableStateOf(sound?.let { if (it == SoundPiece.MUSIC) ReportDetail.HEAD_BLUETOOTH else ReportDetail.FM }) }
    var problem by remember {
        mutableStateOf(
            when {
                mapRequest -> ReportProblem.WANT_MAP
                lightsRequest -> ReportProblem.BROKEN
                else -> sound?.let { ReportProblem.SILENT }
            },
        )
    }
    var note by remember { mutableStateOf("") }

    SettingsCard(stringResource(R.string.report_title), stringResource(R.string.report_help)) {
        val m = module
        val state = reportState
        when {
            state is ReportState.Sent -> {
                Label(stringResource(R.string.report_thanks), Typography.subtitle)
                Label(stringResource(R.string.report_code, state.code), Typography.body, c.textMuted)
                Button(stringResource(R.string.done), {
                    module = null; detail = null; problem = null; note = ""; reportState = ReportState.Idle
                }, primary = true)
            }
            // Se mandó desde otra visita a esta pestaña: se espera aquí lo que conteste.
            state == ReportState.Sending && m == null -> Label(stringResource(R.string.report_sending), Typography.body, c.textMuted)
            m == null -> {
                // El envío de otra visita falló: que se sepa antes de volver a empezar.
                if (state == ReportState.Error) Label(stringResource(R.string.report_error), Typography.small, c.danger)
                Label(stringResource(R.string.report_what), Typography.label, c.textMuted)
                Choices(offeredModules(d).map { it to it.title }) { module = it }
            }
            m.details.isNotEmpty() && detail == null -> {
                BackRow(listOf(m.title)) { module = null }
                Label(stringResource(R.string.report_which), Typography.label, c.textMuted)
                Choices(m.details.map { it to it.title }) { detail = it }
            }
            else -> {
                BackRow(listOfNotNull(m.title, detail?.title)) {
                    problem = null
                    if (reportState != ReportState.Sending) reportState = ReportState.Idle
                    if (detail != null) detail = null else module = null
                }
                Label(stringResource(R.string.report_problem), Typography.label, c.textMuted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (p in m.problems) Button(stringResource(p.title), { problem = p }, primary = p == problem)
                }
                // Al pedir un mapa, la nota pide el país y la región.
                val hint = if (problem == ReportProblem.WANT_MAP) R.string.report_want_map_hint else R.string.report_note
                TextInput(note, { note = it.take(NOTE_MAX) }, stringResource(hint), Modifier.fillMaxWidth())
                if (m.audio) Label(stringResource(R.string.report_play_first), Typography.small, c.textMuted)
                val chosen = problem
                val sending = state == ReportState.Sending
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        stringResource(if (sending) R.string.report_sending else R.string.report_send),
                        {
                            // Se lee el estado de ahora (dos toques en el mismo cuadro no mandan dos).
                            if (chosen == null || reportState == ReportState.Sending || (chosen == ReportProblem.WANT_MAP && note.isBlank())) return@Button
                            reportState = ReportState.Sending
                            val ask = Report(m, detail, chosen, note)
                            reportScope.launch { reportState = submitReport(context, d, ask) }
                        },
                        primary = true,
                        // Para pedir un mapa hace falta saber de dónde.
                        enabled = chosen != null && !sending && (chosen != ReportProblem.WANT_MAP || note.isNotBlank()),
                    )
                    when (state) {
                        ReportState.Error -> Label(stringResource(R.string.report_error), Typography.small, c.danger)
                        ReportState.Wait -> Label(stringResource(R.string.report_wait), Typography.small, c.textMuted)
                        else -> Unit
                    }
                }
            }
        }
    }
}

/** Lo que eligió el usuario. */
private class Report(val module: ReportModule, val detail: ReportDetail?, val problem: ReportProblem, val note: String)

/** Opciones de un paso: un botón por opción; tocar una pasa al siguiente. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choices(options: List<Pair<T, Int>>, onPick: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((value, title) in options) Button(stringResource(title), { onPick(value) })
    }
}

/** "Atrás" y lo elegido hasta ahora ("Música · Archivos…"); en dos renglones si no cabe (celular de pie). */
@Composable
private fun BackRow(picked: List<Int>, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Button(stringResource(R.string.back), onBack)
        Label(picked.map { stringResource(it) }.joinToString(" · "), Typography.body, maxLines = 2, modifier = Modifier.weight(1f, fill = false))
    }
}

/** Manda un reporte respetando el límite de uno por minuto: [ReportState.Sent], [ReportState.Error] o [ReportState.Wait]. */
private suspend fun submitReport(context: Context, d: Dependencies, report: Report): ReportState {
    if (lastReport != 0L && SystemClock.elapsedRealtime() - lastReport < REPORT_GAP_MS) return ReportState.Wait
    val id = sendReport(context, d, report) ?: return ReportState.Error
    lastReport = SystemClock.elapsedRealtime()
    return ReportState.Sent(id.take(6).uppercase())
}

/** Escribe `reports/{id}` (nuevo, nunca pisa otro); regresa el id o null si no se pudo. */
private suspend fun sendReport(context: Context, d: Dependencies, report: Report): String? {
    val info = try {
        reportInfo(context, d)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "no se pudo armar el reporte", e)
        return null
    }
    return withContext(Dispatchers.IO) {
        val id = Firestore.newId()
        val fields = JSONObject()
            .put("version", Firestore.int(BuildConfig.VERSION_CODE.toLong()))
            .put("channel", Firestore.text(BuildConfig.CHANNEL))
            .put("android", Firestore.int(Build.VERSION.SDK_INT.toLong()))
            .put("debug", Firestore.bool(BuildConfig.DEBUG))
            .put("module", Firestore.text(report.module.key))
            .put("detail", Firestore.text(report.detail?.key ?: ""))
            .put("problem", Firestore.text(report.problem.key))
            .put("note", Firestore.text(report.note.trim().take(NOTE_MAX)))
            .put("info", Firestore.text(info.take(INFO_MAX)))
        val write = JSONObject()
            .put("update", JSONObject().put("name", Firestore.document("reports/$id")).put("fields", fields))
            .put("currentDocument", JSONObject().put("exists", false))
            .put("updateTransforms", Firestore.serverTime("sent"))
        try {
            id.takeIf { Firestore.commit(context, write) }.also { if (it == null) Log.w(TAG, "Firestore no aceptó el reporte") }
        } catch (e: IOException) {
            Log.w(TAG, "no se pudo mandar el reporte", e)
            null
        }
    }
}

private const val TAG = "Reports"

/**
 * Las líneas técnicas del reporte (lo que va en "info"); también se las pide LiteDash Connect para el suyo
 * ([wirePhoneReport]). Se llama en el hilo principal; lo que lee del sistema, en IO.
 */
private suspend fun reportInfo(context: Context, d: Dependencies): String {
    val layout = d.dashboard.describe()
    // El escáner solo si ya existe (abrir el Taller lo crea); si no, no se conecta nada por el reporte.
    val obdInfo = (d.obdIfCreated()?.describe() ?: "OBD: sin abrir en esta sesión") + "\n" + reportExtras(d)
    val pro = d.pro.isPro
    val lights = d.prefs.lightsKey?.let { "$it (prendidas = ${d.prefs.lightsOnValue})" }
    val theme = d.prefs.theme
    val night = d.prefs.nightSource
    // Lo de la música que cambia en el hilo principal se copia aquí; lo que pregunta al sistema va en IO.
    val musicInfoLater = d.music.describeLater()
    val music = d.music
    return withContext(Dispatchers.IO) {
        // La música pregunta al PackageManager y a las sesiones: fuera del hilo principal. Su estado lo tocan los
        // avisos que llegan ahí; si cambia justo mientras se lee, el reporte va sin esa parte.
        val musicInfo = runCatching { musicInfoLater() }.getOrElse { "Música: no se pudo leer (${it.javaClass.simpleName})" }
        val silent = runCatching { music.silentSources() }.getOrDefault(emptyList())
        systemInfo(context, pro, layout, lightsReport(context, lights, theme, night), musicInfo + "\n" + obdInfo, silent)
    }
}

/**
 * LiteDash Connect pide el diagnóstico de la pantalla para su reporte (PROTOCOLO_LINK.md §5h): el mismo de aquí. Uno a
 * la vez y a lo más uno cada [PHONE_REPORT_GAP_MS] (lo demás se ignora: el celular manda el suyo sin el de la pantalla).
 */
internal fun wirePhoneReport(link: PhoneLink, context: Context, deps: () -> Dependencies) {
    var busy = false
    var lastAt = 0L
    link.onReport = report@{ id ->
        val now = SystemClock.elapsedRealtime()
        if (busy || (lastAt != 0L && now - lastAt < PHONE_REPORT_GAP_MS)) return@report
        busy = true
        lastAt = now
        phoneScope.launch {
            try {
                link.sendReport(id, reportInfo(context, deps()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "no se pudo armar el reporte para el celular", e)
            } finally {
                busy = false
            }
        }
    }
}

private const val PHONE_REPORT_GAP_MS = 10_000L

/**
 * Lo que va después de la línea del OBD: el celular (Ajustes → Celular; sin vínculo dice solo que no hay, sin armar el
 * enlace), el GPS (qué
 * proveedores escucha y su última falla), el clima guardado (de cuándo y por qué falló), las llantas (solo si su módulo
 * ya corre: el reporte no prende nada), Android Auto / CarPlay, las últimas teclas del volante y los autos instalados. Se arma en el hilo
 * principal (todo es memoria).
 */
private fun reportExtras(d: Dependencies): String = listOfNotNull(
    d.phoneIfCreated()?.describe() ?: "Celular: sin vincular", projectionInfo(d), d.trip.describe(), MapPacks.describe(), d.weather.describe(), d.tpmsIfCreated()?.describe(), d.wheel.describe(), d.cars.describe(), d.updates?.describe(),
).joinToString("\n")

/** Android Auto / CarPlay: la elegida en Ajustes → Apps (o automática) y la que se abre ahora (de la lista ya cargada). */
private fun projectionInfo(d: Dependencies): String {
    val chosen = d.prefs.projectionApp?.substringBefore('/') ?: "automática"
    val apps = d.apps.apps ?: return "Proyección: elegida=$chosen, apps sin cargar"
    return "Proyección: elegida=$chosen, se abre=${projectionChoice(d, apps)?.pkg ?: "ninguna"}"
}

/** El volumen de la música en Android y si está fijo (la pantalla lo maneja con su propio chip). */
private fun volumeInfo(context: Context): String = runCatching {
    val audio = context.getSystemService(AudioManager::class.java)
    "Volumen: música ${audio.getStreamVolume(AudioManager.STREAM_MUSIC)}/${audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}, fijo=${if (audio.isVolumeFixed) "sí" else "no"}"
}.getOrDefault("Volumen: ?")

/** Datos técnicos de la pantalla para los reportes (equipo, Android, pantalla, acomodo [layout], luces y noche [lights], ubicación y si tiene Pro) más [musicInfo] y las apps [silent]. */
private fun systemInfo(context: Context, pro: Boolean, layout: String, lights: String, musicInfo: String, silent: List<String>): String {
    val m = context.resources.displayMetrics
    return buildString {
        appendLine("LiteDash ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.CHANNEL}")
        appendLine("Equipo: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("Plataforma: ${Build.HARDWARE} · ${Build.BOARD}")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Pantalla: ${m.widthPixels}×${m.heightPixels} px · ${m.densityDpi} dpi")
        appendLine(MainActivity.windowInfo)
        appendLine(layout)
        appendLine(BootLaunch.describe(context))
        appendLine("Memoria: ${memoryBytes(context) / (1024 * 1024)} MB")
        appendLine("CPU: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("Pantalla dividida: ${if (SplitScreen.supported()) "sí" else "no"}")
        appendLine(volumeInfo(context))
        appendLine(SystemVolume.describe())
        appendLine("Código de desarrollador: ${if (pro) "sí" else "no"}")
        appendLine(lights)
        appendLine(locationInfo(context))
        appendLine(storageReport(context))
        append(musicInfo)
        SystemSource.describe(context)?.let { append("\n").append(it) }
        // Apps de fábrica sin receptor ni servicio: lo que dejan ver, para aprender a hablarles.
        for (pkg in silent) append("\nSin control $pkg:").append(AppProbe.describe(context, pkg).ifEmpty { " nada legible" })
    }
}

/**
 * Ubicación: si está prendida, qué proveedores tiene la pantalla (muchas no traen antena GPS) y la
 * última posición de cada uno, solo su edad y precisión (nunca dónde está).
 */
@SuppressLint("MissingPermission") // se revisa el permiso antes de leer la última posición
private fun locationInfo(context: Context): String {
    val manager = context.getSystemService(LocationManager::class.java) ?: return "Ubicación: sin servicio"
    val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val on = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) manager.isLocationEnabled else manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }.getOrDefault(false)
    // Edad con el reloj desde el arranque: muchas pantallas sin pila arrancan con la fecha en 1970 o 2015.
    val now = SystemClock.elapsedRealtimeNanos()
    val providers = runCatching { manager.allProviders }.getOrDefault(emptyList()).joinToString { p ->
        val enabled = runCatching { manager.isProviderEnabled(p) }.getOrDefault(false)
        val last = if (fine || coarse) runCatching { manager.getLastKnownLocation(p) }.getOrNull() else null
        "$p(${if (enabled) "on" else "off"}" + (last?.let { ", hace ${(now - it.elapsedRealtimeNanos) / 60_000_000_000L} min ±${it.accuracy.toInt()} m" } ?: "") + ")"
    }
    val permission = if (fine) "precisa" else if (coarse) "aproximada" else "sin permiso"
    return "Ubicación: ${if (on) "prendida" else "apagada"}, $permission; proveedores: ${providers.ifEmpty { "ninguno" }}"
}

/** La memoria (RAM) de la pantalla en bytes. */
internal fun memoryBytes(context: Context): Long =
    ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.totalMem
