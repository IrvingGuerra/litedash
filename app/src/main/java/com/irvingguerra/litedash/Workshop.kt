package com.irvingguerra.litedash

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Settings
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Radius
import com.irvingguerra.litedash.core.ui.SwitchRow
import com.irvingguerra.litedash.core.ui.TextInput
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.core.vehicle.LOCAL_NETWORK_PERMISSION
import com.irvingguerra.litedash.core.vehicle.Signal
import com.irvingguerra.litedash.core.vehicle.bleScanPermission
import com.irvingguerra.litedash.core.vehicle.canScanBle
import com.irvingguerra.litedash.core.vehicle.localNetworkAllowed
import com.irvingguerra.litedash.core.vehicle.watch
import com.irvingguerra.litedash.feature.obd.EngineAlert
import com.irvingguerra.litedash.feature.obd.EngineReport
import com.irvingguerra.litedash.feature.obd.MonitorName
import com.irvingguerra.litedash.feature.obd.ObdError
import com.irvingguerra.litedash.feature.obd.ObdRepository
import com.irvingguerra.litedash.feature.obd.ObdState
import com.irvingguerra.litedash.feature.obd.ObdTarget
import com.irvingguerra.litedash.feature.obd.ScanProblem
import com.irvingguerra.litedash.feature.obd.describe
import com.irvingguerra.litedash.feature.obd.engineAlert
import com.irvingguerra.litedash.feature.obd.zoneOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * «Desconectar» del Taller: el usuario lo cortó y así se queda (aunque se vuelva a entrar al Taller, a las llantas por
 * OBD o aparezca un widget del motor) hasta que él mismo vuelva a conectar. Solo en memoria: al reiniciar LiteDash se conecta como siempre.
 */
internal var obdStoppedByUser = false

/** Las pestañas del Taller (para recordar la elegida), la de códigos y la del adaptador (la última). */
private const val WORKSHOP_TABS = "workshop"
private const val CODES_TAB = 1
private const val ADAPTER_TAB = 4

/**
 * El Taller (sección de Ajustes, "Salud del auto"), siempre con pestañas: el estado del auto por sistema,
 * códigos de falla (leer y borrar) con la verificación, sensores en vivo, gráficas y «Adaptador y viajes»
 * (conexión, alertas, 0–100 y viajes). Sin adaptador abre en esa última. Mientras se ve, quiere el escáner y el
 * OBD solo lee lo que hay aquí.
 */
@Composable
internal fun Workshop(d: Dependencies, onTires: () -> Unit) {
    val obd = d.obd
    val alerts = @Composable {
        SettingsCard {
            SwitchRow(stringResource(R.string.engine_alerts), d.prefs.engineAlerts, { d.prefs.engineAlerts = it }, stringResource(R.string.engine_alerts_help))
        }
    }
    // El permiso de Bluetooth; al volver se revisa otra vez (pudo darse en Ajustes de Android).
    var permitted by remember { mutableStateOf(obd.hasPermission()) }
    LifecycleResumeEffect(Unit) {
        permitted = obd.hasPermission()
        onPauseOrDispose { }
    }
    // Al entrar al Taller conecta solo al último adaptador que funcionó (también si se había rendido o lo
    // desconectaron) y se queda conectado. Sin permiso de Bluetooth no (fallaría en rojo): se conecta al darlo.
    val needsBluetooth = obd.target is ObdTarget.Bluetooth || obd.target is ObdTarget.Ble
    val wants = permitted || !needsBluetooth
    LaunchedEffect(wants) { if (wants && !obdStoppedByUser) obd.keep() }
    val connected = obd.state == ObdState.ON
    // El reporte se lee una vez aquí y lo comparten Salud y Códigos; el último de esta conexión se muestra de
    // inmediato para que al volver a entrar no parpadee. [failed]: la última lectura no contestó.
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf(obd.lastReport) }
    var reading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    val job = remember { mutableStateOf<Job?>(null) }
    // Tocar "Leer otra vez" mientras ya se lee no lanza otra lectura.
    fun read() {
        if (job.value?.isActive == true) return
        reading = true
        job.value = scope.launch {
            val r = obd.report()
            // Si falla, se queda lo último de esta conexión y se dice que no se pudo leer.
            if (r != null) report = r
            failed = r == null
            reading = false
        }
    }
    // Cada conexión empieza de cero (otro adaptador u otro auto): lo leído en la anterior ya no vale.
    LaunchedEffect(connected) {
        job.value?.cancel()
        report = obd.lastReport
        failed = false
        if (connected) read() else reading = false
    }

    // Sin adaptador (ni conectando a uno) abre donde se conecta; lo demás se ve en gris.
    val tab = tabs(
        listOf(R.string.tab_health, R.string.obd_codes, R.string.tab_sensors, R.string.tab_charts, R.string.tab_adapter_trips),
        WORKSHOP_TABS,
        start = ADAPTER_TAB.takeIf { obd.state != ObdState.ON && obd.state != ObdState.CONNECTING },
    )
    when {
        // Los viajes guardados se ven aunque no haya adaptador conectado.
        tab == ADAPTER_TAB -> {
            Connection(d, permitted) { permitted = it }
            alerts()
            AccelTimerCard(d)
            TripsCard(d)
        }
        // Salud sin adaptador: la misma pantalla en gris (sin lo leído antes).
        tab == 0 -> if (connected) {
            HealthView(d, report, connected = true, reading, ::read, onCodes = { selectTab(WORKSHOP_TABS, CODES_TAB) }, onTires = onTires)
        } else {
            HealthView(d, report = null, connected = false, onTires = onTires)
        }
        !connected -> SettingsCard { Label(stringResource(R.string.health_connect), Typography.body, LocalColors.current.textMuted) }
        tab == CODES_TAB -> {
            // Borrados: lo leído antes ya no vale (si la lectura nueva falla, se dice que no se pudo, no "Sin códigos").
            CodesCard(obd, scope, report, reading, failed, ::read) { ok ->
                if (ok) report = null
                read()
            }
            InspectionCard(report, reading)
        }
        tab == 2 -> LiveSensors(d)
        else -> LiveCharts(d)
    }
}

/** Una alerta ya resuelta se olvida tras esto (un dato perdido no la "resuelve"). */
private const val ALERT_CLEAR_MS = 30_000L

/**
 * Lo que avisó el motor y lo que se descartó con "Entendido". Vive en el inicio (no en el aviso):
 * abrir Ajustes o acomodar quita el aviso de la pantalla, pero no lo vuelve a mostrar al regresar.
 */
@Stable
class EngineAlertState {
    var shown by mutableStateOf<EngineAlert?>(null)
    var dismissed by mutableStateOf<EngineAlert?>(null)
}

/**
 * Aviso en el inicio si el motor se calienta de más o la batería no carga. Con un
 * adaptador guardado y las alertas activas, conecta el OBD aunque no haya medidores.
 */
@Composable
fun EngineAlertBanner(d: Dependencies, state: EngineAlertState) {
    if (rememberObd(d, d.prefs.engineAlerts)?.state != ObdState.ON) return
    val coolant = d.bus.watch(Signal.COOLANT)
    val voltage = d.bus.watch(Signal.VOLTAGE)
    val alert = engineAlert(coolant, voltage, d.bus.watch(Signal.RPM))
    var shown by state::shown
    var dismissed by state::dismissed
    // Una falla tiene que durar ([EngineAlert.holdMs]) antes de avisar; y para volver a
    // avisar tras "Entendido", tiene que haberse resuelto un rato, no solo perder un dato.
    LaunchedEffect(alert) {
        if (alert == null) {
            delay(ALERT_CLEAR_MS)
            shown = null
            dismissed = null
        } else {
            delay(alert.holdMs)
            shown = alert
        }
    }
    val a = shown?.takeIf { it != dismissed } ?: return
    val locale = currentLocale()
    val units = d.prefs.units
    val volts = voltage?.let { stringResource(R.string.value_with_unit, String.format(locale, "%.1f", it), stringResource(R.string.unit_volts)) } ?: "—"
    val text = when (a) {
        EngineAlert.HOT -> stringResource(R.string.alert_hot, coolant?.let { sensorText(Signal.COOLANT, it, units, locale) } ?: "—")
        EngineAlert.NOT_CHARGING -> stringResource(R.string.alert_not_charging, volts)
        EngineAlert.OVERCHARGING -> stringResource(R.string.alert_overcharging, volts)
        EngineAlert.LOW_BATTERY -> stringResource(R.string.alert_low_battery, volts)
    }
    Notice(text, Icons.warning, LocalColors.current.danger) {
        Button(stringResource(R.string.alert_ok), { dismissed = a }, primary = true)
    }
}

/**
 * El permiso de Bluetooth que pide «Permitir». BLUETOOTH_CONNECT solo existe desde Android 12; antes basta el de
 * siempre (se da al instalar), así que ahí el botón ni sale ([ObdRepository.hasPermission] ya es verdadero).
 */
private val BLUETOOTH_PERMISSION =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH

/** La dirección de fábrica de los adaptadores por Wi‑Fi (casi todos los ELM327 la traen). */
private const val OBD_WIFI_ADDRESS = "192.168.0.10:${ObdTarget.Wifi.DEFAULT_PORT}"

/**
 * El adaptador: cómo va la conexión (o por qué falló) y elegir uno. [permitted] es el permiso de Bluetooth (lo
 * guarda el Taller: con él quiere el escáner); [onPermitted] lo cambia al responder.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Connection(d: Dependencies, permitted: Boolean, onPermitted: (Boolean) -> Unit) {
    val c = LocalColors.current
    val obd = d.obd
    val bluetoothAsk = rememberPermissionAsk(BLUETOOTH_PERMISSION, onPermitted)
    // Buscar Bluetooth LE tiene su propio permiso; al darlo, busca de una vez.
    val askScan = rememberPermissionAsk(bleScanPermission()) { if (it) obd.scanBle() }
    var wifi by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf(OBD_WIFI_ADDRESS) }
    // Muchas pantallas esconden dónde prender el Bluetooth: aquí se prende y se apaga.
    val context = LocalContext.current
    // OBD por Wi‑Fi en Android 17+: la red local pide permiso; al darlo, conecta al que se estaba tratando.
    var wifiTarget by remember { mutableStateOf<ObdTarget.Wifi?>(null) }
    val lanAsk = rememberPermissionAsk(LOCAL_NETWORK_PERMISSION) { ok -> if (ok) obd.connect(wifiTarget ?: obd.target) }
    // Al volver de Ajustes de Android con el permiso de la red local ya dado, conecta sin tocar nada más.
    // Lo emparejado o conectado en Ajustes de Android se ve al volver.
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumed++
        if ((obd.needsLocalNetwork || lanAsk.denied) && localNetworkAllowed(context)) {
            lanAsk.denied = false
            obd.connect(wifiTarget ?: obd.target)
        }
        onPauseOrDispose { }
    }
    val bluetooth = rememberBluetoothOn()
    val askEnable = rememberBluetoothAsk()
    // Con el Taller abierto el módulo queda guardado como prendido: así «Usar otro» (que olvida el adaptador) no lo
    // esconde aunque nunca se haya tocado su interruptor (de fábrica se prende solo si hay adaptador guardado).
    LaunchedEffect(Unit) { if (d.prefs.obdModule == null) d.prefs.obdModule = true }
    // Volvió a conectar (aquí solo lo pide el usuario mientras está desconectado a mano): ya se mantiene otra vez.
    LaunchedEffect(obd.state) { if (obd.state == ObdState.CONNECTING || obd.state == ObdState.ON) obdStoppedByUser = false }
    SettingsCard(stringResource(R.string.obd_adapter), stringResource(R.string.obd_adapter_help)) {
        val name = obd.target?.name ?: ""
        val status = when (obd.state) {
            ObdState.OFF -> stringResource(R.string.obd_off)
            ObdState.CONNECTING -> stringResource(R.string.obd_connecting, name)
            // Conectado pero sin datos del auto en 75 s: contacto apagado (el viaje ya se cerró).
            ObdState.ON -> if (obd.carSilent) {
                stringResource(R.string.obd_on_car_off, name)
            } else {
                obd.adapter?.let { stringResource(R.string.obd_on_adapter, name, it) } ?: stringResource(R.string.obd_on, name)
            }
            ObdState.ERROR -> stringResource(obdErrorText(obd.errorKind))
        }
        val color = when {
            obd.state == ObdState.ERROR -> c.danger
            obd.state == ObdState.ON && obd.carSilent -> c.warning
            obd.state == ObdState.ON -> c.good
            else -> c.textMuted
        }
        Label(status, Typography.body, color)
        when {
            obd.state == ObdState.ON || obd.state == ObdState.CONNECTING -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(stringResource(R.string.obd_disconnect), {
                    obdStoppedByUser = true
                    obd.disconnect()
                })
                Button(stringResource(R.string.obd_other), { obd.forget() })
            }
            obd.needsLocalNetwork || wifi && lanAsk.denied -> {
                Label(stringResource(R.string.obd_local_network), Typography.body, c.textMuted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PermissionButtons(lanAsk, stringResource(R.string.local_network_allow))
                    Button(stringResource(R.string.obd_other), {
                        lanAsk.denied = false
                        wifi = false
                        obd.forget()
                    })
                }
            }
            // Si no cabe todo en un renglón (celular), los botones bajan y el campo conserva su ancho.
            wifi -> FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                TextInput(address, { address = it.trim() }, OBD_WIFI_ADDRESS, Modifier.widthIn(min = 220.dp, max = 260.dp))
                Button(stringResource(R.string.obd_connect), {
                    val host = address.substringBefore(':')
                    val port = address.substringAfter(':', "").toIntOrNull() ?: ObdTarget.Wifi.DEFAULT_PORT
                    if (host.isNotBlank()) {
                        val t = ObdTarget.Wifi(host, port)
                        wifiTarget = t
                        if (localNetworkAllowed(context)) obd.connect(t) else lanAsk.ask()
                    }
                }, primary = true)
                Button(stringResource(R.string.obd_bluetooth), { wifi = false })
            }
            // Antes de Android 12 el permiso viene con la app: aquí no se llega.
            !permitted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PermissionButtons(bluetoothAsk, stringResource(R.string.obd_allow_bluetooth))
                Button(stringResource(R.string.obd_wifi), { wifi = true })
            }
            bluetooth == false -> {
                // Si el estado ya dice que falló por el Bluetooth apagado, no se repite en rojo.
                if (obd.errorKind != ObdError.BLUETOOTH_OFF) Label(stringResource(R.string.bluetooth_off), Typography.body, c.danger)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(stringResource(R.string.bluetooth_turn_on), { requestBluetoothOn(context, askEnable) }, primary = true)
                    Button(stringResource(R.string.obd_wifi), { wifi = true })
                }
            }
            else -> {
                // Preguntarle a Android por los emparejados y los cables es entre procesos: fuera del hilo principal.
                val devices by produceState<List<ObdTarget.Bluetooth>?>(null, permitted, obd.state, bluetooth, resumed) {
                    value = withContext(Dispatchers.IO) { obd.pairedDevices() }
                }
                if (devices?.isEmpty() == true) Label(stringResource(R.string.obd_no_paired), Typography.small, c.textMuted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (dev in devices.orEmpty()) Button(dev.name, { obd.connect(dev) }, primary = dev == obd.target)
                    Button(stringResource(R.string.obd_wifi), { wifi = true })
                    Button(stringResource(if (obd.scanning) R.string.obd_ble_searching else R.string.obd_ble_search), {
                        if (canScanBle(context)) obd.scanBle() else askScan.ask()
                    })
                    // Negado para siempre, Android ya no pregunta: «Permisos» lleva a los ajustes de LiteDash.
                    if (askScan.denied) Button(stringResource(R.string.local_network_settings), { openAppSettings(context) })
                    // Con confirmación: está junto a los adaptadores y un toque sin querer dejaba al escáner sin Bluetooth.
                    if (bluetooth == true) {
                        val off = stringResource(R.string.bluetooth_turn_off)
                        Confirmed(off, off, off) { turnOffBluetooth(context) }
                    }
                }
                // Cables USB conectados: el permiso lo pide Android al tocar uno.
                val usb by produceState(emptyList<ObdTarget.Usb>(), obd.state, resumed) { value = withContext(Dispatchers.IO) { obd.usbDevices() } }
                if (usb.isNotEmpty()) {
                    Label(stringResource(R.string.obd_usb_found), Typography.label, c.textMuted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (dev in usb) Button(dev.name, { obd.connect(dev) }, primary = dev == obd.target)
                    }
                }
                // Los adaptadores Bluetooth LE no se emparejan: aparecen al buscar.
                if (obd.found.isNotEmpty()) {
                    Label(stringResource(R.string.obd_ble_found), Typography.label, c.textMuted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (dev in obd.found) Button(dev.name, { obd.connect(dev) }, primary = dev == obd.target)
                    }
                }
                val problem = obd.scanProblem
                if (problem != null) {
                    val text = when (problem) {
                        ScanProblem.BLUETOOTH_OFF -> R.string.obd_ble_bluetooth_off
                        ScanProblem.LOCATION_OFF -> R.string.obd_ble_location_off
                        ScanProblem.FAILED -> R.string.obd_ble_failed
                    }
                    Label(stringResource(text), Typography.small, c.danger)
                } else if (obd.found.isEmpty() && !obd.scanning && obd.searched) {
                    Label(stringResource(R.string.obd_ble_none), Typography.small, c.textMuted)
                }
            }
        }
    }
}

/** Por qué no hay conexión con el adaptador, en el idioma de la app (el detalle crudo solo va al reporte). */
internal fun obdErrorText(kind: ObdError?) = when (kind) {
    ObdError.BLUETOOTH_OFF -> R.string.obd_error_bluetooth_off
    ObdError.NO_BLUETOOTH_PERMISSION -> R.string.obd_error_no_bluetooth_permission
    ObdError.NO_USB -> R.string.obd_error_no_usb
    ObdError.NO_USB_PERMISSION -> R.string.obd_error_no_usb_permission
    ObdError.USB_UNPLUGGED -> R.string.obd_error_usb_unplugged
    ObdError.USB_NOT_SERIAL -> R.string.obd_error_usb_not_serial
    ObdError.USB_SILENT -> R.string.obd_error_usb_silent
    ObdError.NOT_OBD -> R.string.obd_error_not_obd
    ObdError.GATT_BUSY -> R.string.obd_error_gatt_busy
    ObdError.ADAPTER_UNREACHABLE -> R.string.obd_error_adapter_unreachable
    ObdError.ADAPTER_SILENT -> R.string.obd_error_adapter_silent
    ObdError.CAR_SILENT -> R.string.obd_error_car_silent
    ObdError.CONNECTION_LOST -> R.string.obd_error_connection_lost
    ObdError.UNKNOWN, null -> R.string.obd_error_unknown
}

/**
 * Un permiso que se pide al tocar. [denied]: ya se negó; Android puede no volver a preguntar, así que junto a
 * «Permitir» se ofrece abrir sus ajustes ([PermissionButtons]).
 */
internal class PermissionAsk(private val deniedState: MutableState<Boolean>, val ask: () -> Unit) {
    var denied by deniedState
}

@Composable
internal fun rememberPermissionAsk(permission: String, onResult: (Boolean) -> Unit = {}): PermissionAsk {
    val denied = rememberSaveable { mutableStateOf(false) }
    val result by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        denied.value = !ok
        result(ok)
    }
    return remember(launcher, permission) { PermissionAsk(denied) { launcher.launch(permission) } }
}

/** «Permitir» ([allow]) y, ya negado, «Permisos» (los ajustes de LiteDash en Android). Van en la fila de quien llama. */
@Composable
internal fun PermissionButtons(p: PermissionAsk, allow: String) {
    val context = LocalContext.current
    Button(allow, p.ask, primary = true)
    if (p.denied) Button(stringResource(R.string.local_network_settings), { openAppSettings(context) })
}

/**
 * Para [requestBluetoothOn]: si al cerrar el diálogo el Bluetooth sigue apagado (hay pantallas donde ese diálogo no
 * hace nada), se abren los ajustes de Bluetooth de Android para prenderlo ahí.
 */
@Composable
internal fun rememberBluetoothAsk(): ManagedActivityResultLauncher<Intent, ActivityResult> {
    val context = LocalContext.current
    return rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val on = context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled
        if (on == false) openSystemScreen(context, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }
}

/** Pide prender el Bluetooth con el diálogo de Android; la pantalla que no lo tiene abre sus ajustes de Bluetooth. */
internal fun requestBluetoothOn(context: Context, launcher: ManagedActivityResultLauncher<Intent, ActivityResult>) {
    try {
        launcher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
    } catch (_: ActivityNotFoundException) {
        openSystemScreen(context, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }
}

/** Si el Bluetooth de la pantalla está encendido (null = no tiene); se actualiza al cambiar. */
@Composable
internal fun rememberBluetoothOn(): Boolean? {
    val context = LocalContext.current
    val adapter = remember { context.getSystemService(BluetoothManager::class.java)?.adapter } ?: return null
    var on by remember { mutableStateOf(adapter.isEnabled) }
    LifecycleStartEffect(adapter) {
        on = adapter.isEnabled
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                on = adapter.isEnabled
            }
        }
        // Es un aviso del sistema: el receptor debe aceptar avisos de fuera de la app.
        ContextCompat.registerReceiver(context, receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        onStopOrDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return on
}

/**
 * Apagar el Bluetooth: hasta Android 12 la app puede hacerlo directo; desde Android 13 solo
 * el sistema, así que se abren sus ajustes de Bluetooth.
 */
@SuppressLint("MissingPermission")
private fun turnOffBluetooth(context: Context) {
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    @Suppress("DEPRECATION")
    val done = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && adapter != null && runCatching { adapter.disable() }.getOrDefault(false)
    if (!done) openSystemScreen(context, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
}

/** Cómo está guardado un código: encendió la luz, el auto lo vigila o ya no se borra a mano. */
private enum class CodeKind(val label: Int) {
    STORED(R.string.code_stored),
    PENDING(R.string.code_pending),
    PERMANENT(R.string.code_permanent),
}

/**
 * Los códigos de falla, una tarjeta por código (qué significa y qué parte del auto señala), y borrarlos
 * (se confirma). [failed]: la última lectura no contestó (sin reporte no se dice "Sin códigos"). [onCleared]
 * recibe si el auto aceptó el borrado (quien llama vuelve a leer).
 */
@Composable
private fun CodesCard(obd: ObdRepository, scope: CoroutineScope, report: EngineReport?, reading: Boolean, failed: Boolean, onRead: () -> Unit, onCleared: (Boolean) -> Unit) {
    val c = LocalColors.current
    var cleared by remember { mutableStateOf<Boolean?>(null) }
    val r = report
    SettingsCard(stringResource(R.string.obd_codes), stringResource(R.string.obd_codes_help)) {
        if (reading && r == null) return@SettingsCard Label(stringResource(R.string.obd_reading), Typography.body, c.textMuted)
        if (r != null) {
            // Un código guardado suele ser también permanente: una sola tarjeta con los dos.
            val codes = (r.stored.map { code -> code to CodeKind.STORED } +
                r.permanent.map { code -> code to CodeKind.PERMANENT } +
                r.pending.map { code -> code to CodeKind.PENDING })
                .groupBy({ it.first }, { it.second }).toList()
            if (codes.isEmpty()) {
                StatTile(stringResource(R.string.obd_codes), stringResource(R.string.obd_no_codes), Modifier.fillMaxWidth(), valueColor = c.good, dot = c.good)
            } else {
                TileGrid(codes, minWidth = 280.dp, maxColumns = 2) { (code, kinds), modifier -> CodeTile(code, kinds, modifier) }
            }
        }
        if (failed && !reading) Label(stringResource(R.string.obd_read_failed), Typography.body, c.danger)
        cleared?.let { Label(stringResource(if (it) R.string.obd_cleared else R.string.obd_not_cleared), Typography.small, if (it) c.good else c.danger) }
        if (r != null && (r.stored.isNotEmpty() || r.pending.isNotEmpty() || r.readiness?.mil == true)) {
            // Se confirma con la advertencia a la vista: borrar también reinicia los monitores de la verificación.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Confirmed(
                    stringResource(R.string.obd_clear),
                    stringResource(R.string.obd_clear_question),
                    stringResource(R.string.obd_clear_confirm),
                    stringResource(R.string.obd_clear_warning),
                ) {
                    scope.launch {
                        val ok = obd.clearCodes()
                        cleared = ok
                        onCleared(ok)
                    }
                }
                Button(stringResource(R.string.obd_read_again), onRead)
            }
        } else {
            Button(stringResource(R.string.obd_read_again), onRead)
        }
    }
}

/** Un código: grande en su color (rojo si encendió la luz, ámbar si está pendiente), qué es y dónde. */
@Composable
private fun CodeTile(code: String, kinds: List<CodeKind>, modifier: Modifier) {
    val c = LocalColors.current
    val color = if (kinds.all { it == CodeKind.PENDING }) c.warning else c.danger
    Column(
        modifier.clip(RoundedCornerShape(Radius.inner)).background(c.surfaceHigh).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(code, Typography.title, color, maxLines = 1, modifier = Modifier.weight(1f))
            Label(kinds.map { stringResource(it.label) }.joinToString(" · "), Typography.small, color, maxLines = 1)
        }
        Label(describe(code, LocalResources.current), Typography.body, maxLines = 2)
        Label(stringResource(zoneName(zoneOf(code))), Typography.small, c.textMuted, maxLines = 1)
    }
}

/** Si pasaría la verificación: luz de falla y el estado de cada monitor. */
@Composable
private fun InspectionCard(report: EngineReport?, reading: Boolean) {
    val c = LocalColors.current
    val readiness = report?.readiness
    if (readiness == null) {
        if (!reading) SettingsCard { Label(stringResource(R.string.obd_no_readiness), Typography.body, c.textMuted) }
        return
    }
    SettingsCard(stringResource(R.string.obd_inspection), stringResource(R.string.obd_inspection_help)) {
        // Con luz de falla no pasa; con más de un monitor pendiente tampoco (la regla común).
        val pass = !readiness.mil && readiness.incomplete.size <= 1
        Label(
            stringResource(if (pass) R.string.obd_inspection_pass else R.string.obd_inspection_fail),
            Typography.subtitle,
            if (pass) c.good else c.danger,
        )
        TileGrid(readiness.monitors, minWidth = 240.dp, maxColumns = 2, gap = 6.dp) { m, modifier ->
            // Aire a la derecha: "Listo" no se pega al punto de la otra columna.
            Row(modifier.padding(end = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (m.complete) c.good else c.warning))
                Label(stringResource(monitorName(m.name)), Typography.body, maxLines = 1, modifier = Modifier.weight(1f))
                Label(stringResource(if (m.complete) R.string.obd_ready else R.string.obd_not_ready), Typography.small, c.textMuted, maxLines = 1)
            }
        }
    }
}

/** Los sensores que más dicen del auto van primero; los demás, en el orden de siempre. */
private val SENSOR_ORDER = listOf(Signal.RPM, Signal.COOLANT, Signal.VOLTAGE, Signal.CONSUMPTION, Signal.SPEED, Signal.LOAD, Signal.OIL, Signal.BOOST)

/** Los sensores que da este auto, en vivo, en tarjetas con su línea de 90 s (solo se leen mientras se ven). */
@Composable
private fun LiveSensors(d: Dependencies) {
    val locale = currentLocale()
    val units = d.prefs.units
    // Los que da el auto, los calculados (consumo, turbo) y el voltaje (lo mide el adaptador si el auto no).
    val readable = readableSignals(d)
    val signals = remember(readable) { readable.sortedBy { SENSOR_ORDER.indexOf(it).let { i -> if (i < 0) SENSOR_ORDER.size else i } } }
    SettingsCard(stringResource(R.string.obd_live)) {
        TileGrid(signals, minWidth = 170.dp, maxColumns = 4) { signal, modifier -> SensorTile(d, signal, units, locale, modifier) }
    }
}

/** Una tarjeta por sensor: cada dato nuevo solo vuelve a dibujar la suya. */
@Composable
private fun SensorTile(d: Dependencies, signal: String, units: UnitChoice, locale: java.util.Locale, modifier: Modifier) {
    val value = d.bus.watch(signal)
    val series = rememberSeries(d, signal)
    StatTile(
        name = stringResource(sensorName(signal)),
        value = value?.let { sensorText(signal, it, units, locale) } ?: "—",
        modifier = modifier,
        series = series,
        seriesColor = LocalColors.current.accent,
    )
}

internal fun sensorName(signal: String) = when (signal) {
    Signal.SPEED -> R.string.sensor_speed
    Signal.RPM -> R.string.sensor_rpm
    Signal.LOAD -> R.string.sensor_load
    Signal.MAP -> R.string.sensor_map
    Signal.MAF -> R.string.sensor_maf
    Signal.COOLANT -> R.string.sensor_coolant
    Signal.INTAKE -> R.string.sensor_intake
    Signal.FUEL -> R.string.sensor_fuel
    Signal.VOLTAGE -> R.string.sensor_voltage
    Signal.BARO -> R.string.sensor_baro
    Signal.CONSUMPTION -> R.string.sensor_consumption
    Signal.BOOST -> R.string.sensor_boost
    Signal.FUEL_TRIM_SHORT -> R.string.sensor_fuel_trim_short
    Signal.FUEL_TRIM_LONG -> R.string.sensor_fuel_trim_long
    Signal.FUEL_PRESSURE -> R.string.sensor_fuel_pressure
    Signal.TIMING -> R.string.sensor_timing
    Signal.THROTTLE -> R.string.sensor_throttle
    Signal.RUN_TIME -> R.string.sensor_run_time
    Signal.DISTANCE_MIL -> R.string.sensor_distance_mil
    Signal.OIL -> R.string.sensor_oil
    Signal.FUEL_RATE -> R.string.sensor_fuel_rate
    else -> R.string.sensor_ambient
}

/**
 * El valor con su unidad; en millas y °F donde se usan (como el velocímetro). Lo que ya
 * es medidor se escribe igual que en su tarjeta ([GaugeSpecs]); aquí solo va lo demás.
 */
@Composable
fun sensorText(signal: String, v: Float, units: UnitChoice, locale: java.util.Locale): String {
    @Composable fun with(number: String, unit: Int) = stringResource(R.string.value_with_unit, number, stringResource(unit))
    gaugeOf(signal)?.let { spec -> return with(gaugeText(spec, v, units, locale), spec.unit(units)) }
    return when (signal) {
        Signal.SPEED -> if (units.miles) with(String.format(locale, "%.0f", v.toMiles(true)), R.string.unit_mph) else with(String.format(locale, "%.0f", v), R.string.unit_kmh)
        Signal.LOAD -> with(String.format(locale, "%.0f", v), R.string.unit_percent)
        Signal.MAP, Signal.BARO, Signal.FUEL_PRESSURE -> with(String.format(locale, "%.0f", v), R.string.unit_kpa)
        Signal.MAF -> with(String.format(locale, "%.1f", v), R.string.unit_gs)
        Signal.FUEL_TRIM_SHORT, Signal.FUEL_TRIM_LONG -> with(String.format(locale, "%+.1f", v), R.string.unit_percent)
        Signal.TIMING -> String.format(locale, "%.1f°", v)
        // Horas y minutos (h:mm).
        Signal.RUN_TIME -> (v / 60).toInt().let { m -> String.format(locale, "%d:%02d", m / 60, m % 60) }
        Signal.DISTANCE_MIL -> if (units.miles) with(String.format(locale, "%.0f", v.toMiles(true)), R.string.unit_mi) else with(String.format(locale, "%.0f", v), R.string.unit_km)
        // Admisión y ambiente.
        else -> tempText(v, units.fahrenheit)
    }
}

private fun monitorName(m: MonitorName) = when (m) {
    MonitorName.MISFIRE -> R.string.monitor_misfire
    MonitorName.FUEL -> R.string.monitor_fuel
    MonitorName.COMPONENTS -> R.string.monitor_components
    MonitorName.CATALYST -> R.string.monitor_catalyst
    MonitorName.HEATED_CATALYST -> R.string.monitor_heated_catalyst
    MonitorName.EVAP -> R.string.monitor_evap
    MonitorName.SECONDARY_AIR -> R.string.monitor_secondary_air
    MonitorName.O2_SENSOR -> R.string.monitor_o2
    MonitorName.O2_HEATER -> R.string.monitor_o2_heater
    MonitorName.EGR -> R.string.monitor_egr
}
