package com.irvingguerra.litedash

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Settings
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.SwitchRow
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.core.vehicle.Signal
import com.irvingguerra.litedash.core.vehicle.watch
import com.irvingguerra.litedash.feature.obd.DerivedSignals
import com.irvingguerra.litedash.feature.obd.EngineAlert
import com.irvingguerra.litedash.feature.obd.EngineReport
import com.irvingguerra.litedash.feature.obd.MonitorName
import com.irvingguerra.litedash.feature.obd.ObdRepository
import com.irvingguerra.litedash.feature.obd.ObdState
import com.irvingguerra.litedash.feature.obd.ObdTarget
import com.irvingguerra.litedash.feature.obd.Pids
import com.irvingguerra.litedash.feature.obd.ScanProblem
import com.irvingguerra.litedash.feature.obd.describe
import com.irvingguerra.litedash.feature.obd.engineAlert
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Good = Color(0xFF3FB27F)

/**
 * El Taller (sección de Ajustes): conexión con el adaptador OBD, estado del motor,
 * códigos de falla (leer y borrar), si pasarías la verificación y los sensores en vivo.
 * Mientras se ve, el OBD solo lee lo que hay aquí.
 */
@Composable
fun Workshop(d: Dependencies) {
    val obd = d.obd
    // Sin adaptador solo hay que conectarlo; ya conectado, cada parte en su pestaña.
    val alerts = @Composable {
        SettingsCard {
            SwitchRow(stringResource(R.string.engine_alerts), d.prefs.engineAlerts, { d.prefs.engineAlerts = it }, stringResource(R.string.engine_alerts_help))
        }
    }
    if (obd.state != ObdState.ON) {
        Connection(obd)
        alerts()
        // Los viajes guardados se ven aunque no haya adaptador conectado.
        return TripsCard(d)
    }
    val tab = tabs(
        listOf(R.string.tab_connection, R.string.obd_codes, R.string.tab_inspection, R.string.tab_sensors, R.string.tab_charts, R.string.tab_accel, R.string.tab_trips),
        "taller",
    )
    if (tab == 0) {
        Connection(obd)
        alerts()
    }
    // El reporte se lee una vez y se comparte entre las pestañas de motor, códigos y verificación.
    Engine(obd, tab)
    when (tab) {
        3 -> LiveSensors(d)
        4 -> LiveCharts(d)
        5 -> AccelTimerCard(d)
        6 -> TripsCard(d)
    }
}

/** Una alerta ya resuelta se olvida tras esto (un dato perdido no la "resuelve"). */
private const val ALERT_CLEAR_MS = 30_000L

/**
 * Aviso en el inicio si el motor se calienta de más o la batería no carga. Con un
 * adaptador guardado y las alertas activas, conecta el OBD aunque no haya medidores.
 */
@Composable
fun EngineAlertBanner(d: Dependencies) {
    val on = d.prefs.engineAlerts && d.hasObdAdapter
    LaunchedEffect(on) { if (on) d.obd.connect() }
    if (!on || d.obdIfCreated()?.state != ObdState.ON) return
    val coolant = d.bus.watch(Signal.COOLANT)
    val voltage = d.bus.watch(Signal.VOLTAGE)
    val alert = engineAlert(coolant, voltage, d.bus.watch(Signal.RPM))
    var shown by remember { mutableStateOf<EngineAlert?>(null) }
    var dismissed by remember { mutableStateOf<EngineAlert?>(null) }
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
    val imperial = imperialUnits(d.prefs.language)
    val volts = voltage?.let { String.format(locale, "%.1f V", it) } ?: "—"
    val text = when (a) {
        EngineAlert.HOT -> stringResource(R.string.alert_hot, coolant?.let { sensorText(Signal.COOLANT, it, imperial, locale) } ?: "—")
        EngineAlert.NOT_CHARGING -> stringResource(R.string.alert_not_charging, volts)
        EngineAlert.OVERCHARGING -> stringResource(R.string.alert_overcharging, volts)
        EngineAlert.LOW_BATTERY -> stringResource(R.string.alert_low_battery, volts)
    }
    Notice(text, Icons.warning, danger = true) {
        Button(stringResource(R.string.alert_ok), { dismissed = a }, primary = true)
    }
}

/** Acceso directo al Taller en el inicio; dice si el adaptador está conectado. */
@Composable
fun WorkshopTile(d: Dependencies, onClick: () -> Unit) {
    val detail = when (d.obdIfCreated()?.state) {
        ObdState.ON -> stringResource(R.string.obd_connected_short)
        ObdState.CONNECTING -> stringResource(R.string.obd_connecting_short)
        else -> null
    }
    ShortcutTile(Icons.engine, stringResource(R.string.workshop), detail, onClick)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Connection(obd: ObdRepository) {
    val c = LocalColors.current
    var permitted by remember { mutableStateOf(obd.hasPermission()) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permitted = it }
    // Buscar Bluetooth LE tiene su propio permiso; al darlo, busca de una vez.
    val askScan = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) obd.scanBle() }
    var wifi by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("192.168.0.10:35000") }
    // Muchas pantallas esconden dónde prender el Bluetooth: aquí se prende y se apaga.
    val context = LocalContext.current
    val bluetooth = rememberBluetoothOn()
    val askEnable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    // Al abrir el Taller se reconecta solo al último adaptador que funcionó.
    LaunchedEffect(Unit) { if (obd.state == ObdState.OFF) obd.connect() }
    SettingsCard(stringResource(R.string.obd_adapter), stringResource(R.string.obd_adapter_help)) {
        val status = when (obd.state) {
            ObdState.OFF -> stringResource(R.string.obd_off)
            ObdState.CONNECTING -> stringResource(R.string.obd_connecting, obd.target?.name ?: "")
            ObdState.ON -> stringResource(R.string.obd_on, obd.target?.name ?: "") + (obd.adapter?.let { " · $it" } ?: "")
            ObdState.ERROR -> stringResource(R.string.obd_error, obd.error ?: "")
        }
        Label(status, Typography.body, if (obd.state == ObdState.ERROR) c.danger else if (obd.state == ObdState.ON) Good else c.textMuted)
        when {
            obd.state == ObdState.ON || obd.state == ObdState.CONNECTING -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(stringResource(R.string.obd_disconnect), { obd.disconnect() })
                Button(stringResource(R.string.obd_other), { obd.forget() })
            }
            wifi -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                TextInput(address, { address = it.trim() }, "192.168.0.10:35000", Modifier.width(260.dp))
                Button(stringResource(R.string.obd_connect), {
                    val host = address.substringBefore(':')
                    val port = address.substringAfter(':', "35000").toIntOrNull() ?: 35000
                    if (host.isNotBlank()) obd.connect(ObdTarget.Wifi(host, port))
                }, primary = true)
                Button(stringResource(R.string.obd_bluetooth), { wifi = false })
            }
            !permitted -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(stringResource(R.string.obd_allow_bluetooth), {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ask.launch(Manifest.permission.BLUETOOTH_CONNECT)
                }, primary = true)
                Button(stringResource(R.string.obd_wifi), { wifi = true })
            }
            bluetooth == false -> {
                Label(stringResource(R.string.bluetooth_off), Typography.body, c.danger)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(stringResource(R.string.bluetooth_turn_on), { runCatching { askEnable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } }, primary = true)
                    Button(stringResource(R.string.obd_wifi), { wifi = true })
                }
            }
            else -> {
                val devices = remember(permitted, obd.state, bluetooth) { obd.pairedDevices() }
                if (devices.isEmpty()) Label(stringResource(R.string.obd_no_paired), Typography.small, c.textMuted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (dev in devices) Button(dev.name, { obd.connect(dev) }, primary = dev == obd.target)
                    Button(stringResource(R.string.obd_wifi), { wifi = true })
                    Button(stringResource(if (obd.scanning) R.string.obd_ble_searching else R.string.obd_ble_search), {
                        if (obd.canScan()) obd.scanBle() else askScan.launch(obd.scanPermission())
                    })
                    if (bluetooth == true) Button(stringResource(R.string.bluetooth_turn_off), { turnOffBluetooth(context) })
                }
                // Cables USB conectados: el permiso lo pide Android al tocar uno.
                val usb = remember(obd.state) { obd.usbDevices() }
                if (usb.isNotEmpty()) {
                    Label(stringResource(R.string.obd_usb_found), Typography.label, c.textMuted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (dev in usb) Button(dev.name, { obd.connectUsb(dev) }, primary = dev == obd.target)
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

/** Si el Bluetooth de la pantalla está encendido (null = no tiene); se actualiza al cambiar. */
@Composable
private fun rememberBluetoothOn(): Boolean? {
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
    if (!done) runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
private fun Engine(obd: ObdRepository, tab: Int) {
    val c = LocalColors.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<EngineReport?>(null) }
    var reading by remember { mutableStateOf(true) }
    var confirm by remember { mutableIntStateOf(0) }
    var cleared by remember { mutableStateOf<Boolean?>(null) }
    val spanish = currentLocale().language == "es"
    fun read() = scope.launch {
        reading = true
        report = obd.report()
        reading = false
    }
    LaunchedEffect(Unit) { read() }
    val r = report
    val readiness = r?.readiness

    if (tab == 0) SettingsCard(stringResource(R.string.obd_engine)) {
        if (reading && r == null) return@SettingsCard Label(stringResource(R.string.obd_reading), Typography.body, c.textMuted)
        val mil = readiness?.mil == true
        Label(
            stringResource(if (mil) R.string.obd_mil_on else R.string.obd_mil_off),
            Typography.subtitle,
            if (mil) c.danger else Good,
        )
        val facts = listOfNotNull(
            r?.voltage?.let { stringResource(R.string.obd_battery, it) },
            r?.vin?.let { "VIN $it" },
        )
        if (facts.isNotEmpty()) Label(facts.joinToString(" · "), Typography.small, c.textMuted)
        Button(stringResource(R.string.obd_read_again), { read() })
    }

    if (tab == 1) SettingsCard(stringResource(R.string.obd_codes), stringResource(R.string.obd_codes_help)) {
        val groups = listOf(
            R.string.obd_codes_stored to r?.stored.orEmpty(),
            R.string.obd_codes_pending to r?.pending.orEmpty(),
            R.string.obd_codes_permanent to r?.permanent.orEmpty(),
        ).filter { it.second.isNotEmpty() }
        if (groups.isEmpty()) Label(stringResource(R.string.obd_no_codes), Typography.body, Good)
        for ((title, codes) in groups) {
            Label(stringResource(title), Typography.label, c.textMuted)
            for (code in codes) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Label(code, Typography.subtitle, c.accent, maxLines = 1)
                Label(describe(code, spanish), Typography.body, modifier = Modifier.weight(1f))
            }
        }
        cleared?.let { Label(stringResource(if (it) R.string.obd_cleared else R.string.obd_not_cleared), Typography.small, if (it) Good else c.danger) }
        if (r != null && (r.stored.isNotEmpty() || r.pending.isNotEmpty() || readiness?.mil == true)) {
            // Doble confirmación: borrar también reinicia los monitores de la verificación.
            when (confirm) {
                0 -> Button(stringResource(R.string.obd_clear), { confirm = 1 })
                1 -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Label(stringResource(R.string.obd_clear_warning), Typography.small, c.danger)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(stringResource(R.string.obd_clear_sure), { confirm = 2 }, primary = true)
                        Button(stringResource(R.string.cancel), { confirm = 0 })
                    }
                }
                else -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(stringResource(R.string.obd_clear_final), {
                        confirm = 0
                        scope.launch {
                            cleared = obd.clearCodes()
                            report = obd.report()
                        }
                    }, primary = true)
                    Button(stringResource(R.string.cancel), { confirm = 0 })
                }
            }
        }
    }

    if (tab == 2 && readiness == null && !reading) SettingsCard {
        Label(stringResource(R.string.obd_no_readiness), Typography.body, c.textMuted)
    }
    if (tab == 2 && readiness != null) SettingsCard(stringResource(R.string.obd_inspection), stringResource(R.string.obd_inspection_help)) {
        // Con luz de falla no pasa; con más de un monitor pendiente tampoco (la regla común).
        val pass = !readiness.mil && readiness.incomplete.size <= 1
        Label(
            stringResource(if (pass) R.string.obd_inspection_pass else R.string.obd_inspection_fail),
            Typography.subtitle,
            if (pass) Good else c.danger,
        )
        for (m in readiness.monitors) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Label(if (m.complete) "✓" else "…", Typography.body, if (m.complete) Good else c.accent, maxLines = 1)
            Label(stringResource(monitorName(m.name)), Typography.body, maxLines = 1, modifier = Modifier.weight(1f))
            Label(stringResource(if (m.complete) R.string.obd_ready else R.string.obd_not_ready), Typography.small, c.textMuted, maxLines = 1)
        }
    }
}

/** Los sensores que da este auto, en vivo (solo se leen mientras se ven). */
@Composable
private fun LiveSensors(d: Dependencies) {
    val locale = currentLocale()
    val imperial = imperialUnits(d.prefs.language)
    SettingsCard(stringResource(R.string.obd_live)) {
        // Los que da el auto, los calculados (consumo, turbo) y el voltaje (lo mide el adaptador si el auto no).
        val signals = remember(d.obd.supported) { (Pids.map { it.signal } + DerivedSignals.map { it.signal }).distinct().filter(d.obd::canRead) }
        for (signal in signals) SensorRow(d, signal, imperial, locale)
    }
}

/** Una fila por sensor: cada dato nuevo solo vuelve a dibujar su fila. */
@Composable
private fun SensorRow(d: Dependencies, signal: String, imperial: Boolean, locale: java.util.Locale) {
    val value = d.bus.watch(signal)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Label(stringResource(sensorName(signal)), Typography.body, maxLines = 1, modifier = Modifier.weight(1f))
        Label(value?.let { sensorText(signal, it, imperial, locale) } ?: "—", Typography.subtitle, LocalColors.current.accent, maxLines = 1)
    }
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
fun sensorText(signal: String, v: Float, imperial: Boolean, locale: java.util.Locale): String {
    gaugeOf(signal)?.let { spec -> return gaugeText(spec, v, imperial, locale) + " " + stringResource(spec.unit(imperial)) }
    return when (signal) {
        Signal.SPEED -> if (imperial) String.format(locale, "%.0f mph", v / 1.609f) else String.format(locale, "%.0f km/h", v)
        Signal.LOAD -> String.format(locale, "%.0f %%", v)
        Signal.MAP, Signal.BARO -> String.format(locale, "%.0f kPa", v)
        Signal.MAF -> String.format(locale, "%.1f g/s", v)
        Signal.FUEL_TRIM_SHORT, Signal.FUEL_TRIM_LONG -> String.format(locale, "%+.1f %%", v)
        Signal.FUEL_PRESSURE -> String.format(locale, "%.0f kPa", v)
        Signal.TIMING -> String.format(locale, "%.1f°", v)
        // Horas y minutos (h:mm).
        Signal.RUN_TIME -> (v / 60).toInt().let { m -> String.format(locale, "%d:%02d", m / 60, m % 60) }
        Signal.DISTANCE_MIL -> if (imperial) String.format(locale, "%.0f mi", v / 1.609f) else String.format(locale, "%.0f km", v)
        // Admisión y ambiente.
        else -> if (imperial) String.format(locale, "%.0f °F", v * 9 / 5 + 32) else String.format(locale, "%.0f °C", v)
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
