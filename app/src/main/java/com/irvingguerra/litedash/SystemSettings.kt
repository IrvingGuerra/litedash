// Ajustes → Acerca de y ayuda y Módulos: acerca de, Android, reportar un problema, módulos y permisos.
package com.irvingguerra.litedash

import com.irvingguerra.litedash.core.ui.LocalCardLook
import com.irvingguerra.litedash.core.ui.skinSurface
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.Formatter
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Radius
import com.irvingguerra.litedash.core.ui.Switch
import com.irvingguerra.litedash.core.ui.SwitchRow
import com.irvingguerra.litedash.core.ui.TextInput
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.feature.car.CAR_ASPECT
import com.irvingguerra.litedash.feature.car.CarStill
import com.irvingguerra.litedash.core.vehicle.LOCAL_NETWORK_PERMISSION
import com.irvingguerra.litedash.core.vehicle.LOCAL_NETWORK_SDK
import com.irvingguerra.litedash.feature.player.Library
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Pestañas de Acerca de y ayuda; Acerca de va primero (ahí abre el aviso de versión nueva). */
internal enum class SystemTab(val title: Int) {
    ABOUT(R.string.tab_about), ANDROID(R.string.android_settings), REPORT(R.string.tab_report_problem),
}

/**
 * Acerca de y ayuda, en pestañas: Acerca de (la versión con sus novedades, en la de GitHub buscar la siguiente, y las
 * estadísticas anónimas), atajos a Ajustes de Android y reportar un problema (tu pantalla, código y reporte).
 */
@Composable
internal fun SystemSettings(d: Dependencies) {
    when (SystemTab.entries[tabs(SystemTab.entries.map { it.title }, SYSTEM_TABS).coerceAtMost(SystemTab.entries.lastIndex)]) {
        SystemTab.ABOUT -> {
            About(d)
            d.updates?.Card()
            SettingsCard(stringResource(R.string.stats_title)) {
                SwitchRow(stringResource(R.string.stats_switch), d.prefs.stats, { d.prefs.stats = it }, stringResource(R.string.stats_help))
            }
        }
        SystemTab.ANDROID -> AndroidSettings()
        SystemTab.REPORT -> ScreenInfo(d)
    }
}

/**
 * La pantalla en unas líneas, «Código de desarrollador» ([ProCard]) y "Enviar un reporte" ([SendReport]). Si se
 * pidió un reporte ya elegido (sonido o mapa), la tarjeta del reporte va primero (abajo no se vería en pantallas bajitas).
 */
@Composable
private fun ScreenInfo(d: Dependencies) {
    val context = LocalContext.current
    val reportFirst = remember { reportPending() }
    if (reportFirst) SendReport(d)
    SettingsCard {
        val lines = remember { screenLines(context) }
        for (line in lines) Label(line, Typography.body, maxLines = 2)
    }
    ProCard(d.pro)
    if (!reportFirst) SendReport(d)
}

/** «Código de desarrollador»: activarlo con un código o, si ya está, quitarlo de esta pantalla (solo lo local). */
@Composable
private fun ProCard(pro: ProAccess) {
    val c = LocalColors.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    SettingsCard(stringResource(R.string.pro_title)) {
        if (pro.isPro) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.done, c.accent, 24.dp)
                Label(stringResource(R.string.pro_active), Typography.body)
            }
            Confirmed(stringResource(R.string.pro_remove), stringResource(R.string.pro_remove_question), stringResource(R.string.remove)) { scope.launch { pro.remove() } }
        } else {
            Label(stringResource(R.string.pro_help), Typography.small, c.textMuted)
            TextInput(code, { code = it.take(PRO_CODE_MAX); error = null }, stringResource(R.string.pro_code), Modifier.fillMaxWidth())
            val idle = !checking && ProCode.normalize(code).isNotEmpty()
            Button(
                stringResource(if (checking) R.string.pro_checking else R.string.pro_activate),
                {
                    if (!idle) return@Button
                    checking = true
                    error = null
                    scope.launch {
                        error = when (pro.activate(code)) {
                            ProResult.ACTIVE -> null.also { code = "" }
                            ProResult.INVALID -> R.string.pro_invalid
                            ProResult.TAKEN -> R.string.pro_taken
                            ProResult.OFFLINE -> R.string.pro_offline
                        }
                        checking = false
                    }
                },
                primary = true,
                enabled = idle,
            )
            (error ?: R.string.pro_expired.takeIf { pro.expired })?.let { Label(stringResource(it), Typography.small, c.danger) }
        }
    }
}

/** Largo máximo de lo que se escribe como código. */
private const val PRO_CODE_MAX = 64

/** La versión en grande, sus novedades y tu auto a la derecha (como DuduAuto). */
@Composable
private fun About(d: Dependencies) {
    val c = LocalColors.current
    Card(Modifier.fillMaxWidth().widthIn(max = 860.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wide = maxWidth > 560.dp
            val pkg = d.cars.pkg(d.prefs.carModel)
            if (pkg != null && wide) Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
                CarStill(d.cars, pkg, d.prefs.carPaint, Modifier.fillMaxWidth(0.46f).aspectRatio(CAR_ASPECT))
            }
            Column(Modifier.padding(22.dp).fillMaxWidth(if (pkg != null && wide) 0.56f else 1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Label("LiteDash ${BuildConfig.VERSION_NAME}", Typography.title.copy(fontSize = 30.sp), maxLines = 1)
                Label(stringResource(if (BuildConfig.CHANNEL == "play") R.string.channel_play else R.string.channel_github), Typography.small, c.textMuted)
                val notes = localReleaseNotes(BuildConfig.RELEASE_NOTES, BuildConfig.RELEASE_NOTES_EN)
                if (notes.isNotBlank()) {
                    Label(stringResource(R.string.release_notes), Typography.label, c.textMuted)
                    for (line in remember(notes) { releaseNotes(notes) }) {
                        if (line.startsWith("###")) Label(line.removePrefix("###").trim(), Typography.subtitle, maxLines = 1)
                        else Label("• $line", Typography.small)
                    }
                }
            }
        }
    }
}

/**
 * Módulos, en dos pestañas. Módulos: cada uno con su interruptor y, prendido, una flecha a sus
 * ajustes (el clima no tiene); apagado en gris, no corre ni pide nada y desaparece de todo. Permisos: los que se dan
 * con Android, con el mismo interruptor.
 */
@Composable
internal fun Modules(d: Dependencies, revision: Int, open: (SettingsSection) -> Unit) {
    val permissions = tabs(listOf(R.string.modules, R.string.tab_permissions), MODULES_TABS) == 1
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (permissions) return@Column Permissions(d, revision)
        ModuleRow(Icons.engine, stringResource(R.string.module_obd), d.obdModule, { d.obdModule = it }) { open(SettingsSection.WORKSHOP) }
        // Llantas: prendido = hay una fuente (sin crear el módulo para saberlo). Al prender vuelve
        // la última que se usó; si nunca hubo, a elegirla en Ajustes → Llantas.
        ModuleRow(Icons.tire, stringResource(R.string.module_tpms), d.hasTpmsSource, { on ->
            val tpms = d.tpms
            if (!on) tpms.source = null
            else tpms.lastSource?.let { tpms.source = it } ?: open(SettingsSection.TIRES)
        }) { open(SettingsSection.TIRES) }
        // Clima: al prenderlo pide la ubicación aproximada (y lo trae al momento); apagado no usa ni red ni ubicación.
        val enableWeather = rememberEnableWeather(d.weather)
        ModuleRow(Icons.sun, stringResource(R.string.weather_switch), d.weather.enabled, { on -> if (on) enableWeather() else d.weather.disable() })
    }
}

/** Las pestañas de Módulos (Módulos 0, Permisos 1). */
private const val MODULES_TABS = "modules"

/**
 * Módulos → Permisos: prender los pide; apagar solo se puede en Ajustes de Android. [revision]
 * cambia al volver a la app: lo que se dio o quitó allá se nota al regresar. Solo los que existen en esta versión de
 * Android, y nunca se piden al abrir: solo al tocar su renglón.
 */
@Composable
private fun Permissions(d: Dependencies, revision: Int) {
    val context = LocalContext.current
    val notifications = remember(revision) { d.music.hasPermission() }
    val (location, askLocation) = rememberGpsPermission(d, revision)
    // El acceso a notificaciones no está en la información de la app: se da y se quita en su propia pantalla de Android.
    ModuleRow(Icons.bell, stringResource(R.string.module_notifications), notifications, { d.music.requestPermission() })
    ModuleRow(Icons.location, stringResource(R.string.module_location), location, { on ->
        if (on) askLocation() else openAppSettings(context)
    })
    // Bluetooth (dispositivos cercanos): desde Android 12 es un permiso que se pide; antes viene dado al instalar.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val nearby = listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        PermissionRow(Icons.bluetooth, stringResource(R.string.module_bluetooth), nearby, revision)
    }
    // La red local (celular con Connect, OBD por Wi‑Fi): desde Android 17. Al darla, el enlace con el celular busca de una vez.
    if (Build.VERSION.SDK_INT >= LOCAL_NETWORK_SDK) {
        PermissionRow(Icons.wifi, stringResource(R.string.module_local_network), listOf(LOCAL_NETWORK_PERMISSION), revision) {
            d.phoneIfCreated()?.permissionChanged()
        }
    }
    // Tu música en la memoria o el USB (y, hasta Android 12, las fotos del explorador propio).
    PermissionRow(Icons.music, stringResource(R.string.module_files), listOf(Library.permission), revision)
}

/**
 * Un permiso de Android ([permissions], todos juntos) como renglón: dice si está dado y solo lo pide al tocarlo; quitarlo
 * se hace en Ajustes de Android. [onGranted]: quien lo usaba se entera al momento.
 */
@Composable
private fun PermissionRow(icon: ImageVector, title: String, permissions: List<String>, revision: Int, onGranted: () -> Unit = {}) {
    val context = LocalContext.current
    fun granted() = permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    var on by remember(revision) { mutableStateOf(granted()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        on = granted()
        if (on) onGranted()
    }
    ModuleRow(icon, title, on, { want -> if (want) launcher.launch(permissions.toTypedArray()) else openAppSettings(context) })
}

/**
 * Atajos a los ajustes del sistema: muchas pantallas de auto esconden sus ajustes detrás
 * del launcher de la marca, y al poner LiteDash de inicio ya no se encuentran.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AndroidSettings() {
    val c = LocalColors.current
    val context = LocalContext.current
    // Preguntar a Android cuáles abren es una consulta entre procesos: fuera del hilo principal.
    val shortcuts by produceState<List<Pair<Int, String>>?>(null) { value = withContext(Dispatchers.IO) { systemShortcuts(context) } }
    SettingsCard(stringResource(R.string.android_settings), stringResource(R.string.android_settings_help)) {
        val list = shortcuts ?: return@SettingsCard
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((name, action) in list) {
                Button(stringResource(name), { openSystemScreen(context, Intent(action)) }, primary = action == Settings.ACTION_SETTINGS)
            }
        }
        if (list.isEmpty()) Label(stringResource(R.string.android_settings_none), Typography.small, c.textMuted)
    }
}

/** Los atajos que esta pantalla sí abre. */
private fun systemShortcuts(context: Context): List<Pair<Int, String>> =
    listOf(
        R.string.sys_all to Settings.ACTION_SETTINGS,
        R.string.sys_bluetooth to Settings.ACTION_BLUETOOTH_SETTINGS,
        R.string.sys_wifi to Settings.ACTION_WIFI_SETTINGS,
        R.string.sys_location to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
        R.string.sys_display to Settings.ACTION_DISPLAY_SETTINGS,
        R.string.sys_sound to Settings.ACTION_SOUND_SETTINGS,
        R.string.installed_apps to Settings.ACTION_APPLICATION_SETTINGS,
        R.string.sys_date to Settings.ACTION_DATE_SETTINGS,
        R.string.sys_language to Settings.ACTION_LOCALE_SETTINGS,
        R.string.sys_home to Settings.ACTION_HOME_SETTINGS,
    ).filter { (_, action) -> Intent(action).resolveActivity(context.packageManager) != null }

/** Abre una pantalla de los ajustes de Android; falso si la pantalla no la tiene (o no deja abrirla). */
internal fun openSystemScreen(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: ActivityNotFoundException) {
    Log.w("SystemSettings", "sin pantalla para ${intent.action}", e)
    false
} catch (e: SecurityException) {
    Log.w("SystemSettings", "no deja abrir ${intent.action}", e)
    false
}

/** Una pantalla de Ajustes de Android sobre una app ([action], p. ej. sus permisos): de LiteDash si no se dice [pkg]. */
internal fun appSettingsIntent(context: Context, action: String, pkg: String = context.packageName): Intent =
    Intent(action, Uri.fromParts("package", pkg, null))

/** Los permisos de LiteDash en Ajustes de Android (ubicación, red local…). */
internal fun openAppSettings(context: Context) {
    openSystemScreen(context, appSettingsIntent(context, Settings.ACTION_APPLICATION_DETAILS_SETTINGS))
}

/**
 * Las notas en el idioma de la app: en español si la app está en español; si no, en inglés ([english]) cuando las hay.
 * Las del Release de GitHub traen las dos, separadas por [RELEASE_NOTES_EN_MARK] ([splitReleaseNotes]).
 */
@Composable
internal fun localReleaseNotes(spanish: String, english: String): String {
    val inSpanish = currentLocale().language == "es"
    return if (inSpanish || english.isBlank()) spanish else english
}

/** Lo que separa las notas en español de las de inglés en un Release (comentario HTML: no se ve en GitHub). */
internal const val RELEASE_NOTES_EN_MARK = "<!-- en -->"

/** (español, inglés) de las notas de un Release; sin la marca, todo es español. El pie (tras "---") no es parte de ellas. */
internal fun splitReleaseNotes(notes: String): Pair<String, String> {
    val body = notes.substringBefore("\n---")
    return body.substringBefore(RELEASE_NOTES_EN_MARK) to body.substringAfter(RELEASE_NOTES_EN_MARK, "")
}

/** Notas de una versión (sección del CHANGELOG) en renglones: títulos ("### Agregado") y viñetas sin formato. */
internal fun releaseNotes(notes: String = BuildConfig.RELEASE_NOTES): List<String> {
    val items = mutableListOf<String>()
    for (raw in notes.lines()) {
        val line = raw.trim()
        when {
            line.isEmpty() -> Unit
            line.startsWith("###") -> items += line
            line.startsWith("- ") -> items += line.removePrefix("- ")
            items.isNotEmpty() && !items.last().startsWith("###") -> items[items.lastIndex] = items.last() + " " + line
        }
    }
    return items.map { it.replace("**", "") }
}

/** Lo básico de la pantalla, sin jerga: equipo, Android, resolución y memoria (lo técnico va solo en el reporte). */
private fun screenLines(context: Context): List<String> {
    val m = context.resources.displayMetrics
    return listOf(
        "${Build.MANUFACTURER} ${Build.MODEL}",
        "Android ${Build.VERSION.RELEASE}",
        "${m.widthPixels}×${m.heightPixels} · ${Formatter.formatShortFileSize(context, memoryBytes(context))}",
    )
}

/**
 * Un módulo: ícono, nombre e interruptor (todo eso se toca para prender o apagar) y, prendido y
 * con [onOpen], una flecha redonda aparte que entra a sus ajustes. Apagado, en gris.
 */
@Composable
private fun ModuleRow(
    icon: ImageVector,
    title: String,
    on: Boolean,
    onChange: (Boolean) -> Unit,
    onOpen: (() -> Unit)? = null,
) {
    val c = LocalColors.current
    val shape = RoundedCornerShape(Radius.card)
    val dim = if (on) 1f else 0.45f
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    // Angosto (celular): todo más junto y el nombre en dos renglones, para que no se corte.
    val narrow = maxWidth < MODULE_NARROW
    Row(
        Modifier.fillMaxWidth().heightIn(min = 76.dp).height(IntrinsicSize.Min).clip(shape).skinSurface(LocalCardLook.current, c, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clickable { onChange(!on) }
                .padding(start = if (narrow) 14.dp else 20.dp, end = if (narrow) 10.dp else 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (narrow) 12.dp else 16.dp),
        ) {
            Icon(icon, if (on) c.accent else c.textMuted.copy(alpha = dim), if (narrow) 24.dp else 28.dp)
            Label(title, Typography.subtitle, if (on) c.text else c.textMuted.copy(alpha = dim), maxLines = if (narrow) 2 else 1, modifier = Modifier.weight(1f))
            Switch(on)
        }
        // La flecha va aparte (con su línea): entrar no prende ni apaga nada.
        Box(Modifier.width(if (narrow) 60.dp else 76.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            if (on && onOpen != null) {
                Box(Modifier.align(Alignment.CenterStart).width(1.dp).fillMaxHeight(0.5f).background(c.line))
                IconButton(Icons.chevron, onOpen, size = if (narrow) 40.dp else 48.dp, background = c.surfaceHigh, color = c.text)
            }
        }
    }
    }
}

/** Menos ancho que esto (celular de pie), el renglón de un módulo se aprieta. */
private val MODULE_NARROW = 420.dp
