// Ajustes → Apps → Música → Bluetooth → «Sonido por Bluetooth»: el Bluetooth de Android de la pantalla,
// sin app externa. Emparejar (u olvidar) el celular, hacerse visible y buscar celulares cerca.
package com.irvingguerra.litedash

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.feature.apps.rememberApps
import com.irvingguerra.litedash.feature.music.SourceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "BluetoothSettings"

/** A lo más tantos celulares en "Cerca:" (en el tráfico se ven decenas). */
private const val FOUND_MAX = 20

/** Un celular encontrado: su nombre se lee una vez, al llegar el aviso. */
private class Found(val device: BluetoothDevice, val label: String)

/**
 * Emparejar (u olvidar) el celular por el Bluetooth de Android de la pantalla. En pantallas con
 * chip propio (sin el Bluetooth de Android), se abre su propia app. Todo inline, sin ventanas.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BluetoothSettings(d: Dependencies) {
    val context = LocalContext.current
    val c = LocalColors.current
    val adapter = remember { context.getSystemService(BluetoothManager::class.java)?.adapter }

    // Sin el Bluetooth de Android (o sin el perfil de música AVRCP): su propia app.
    if (adapter == null || !d.music.bluetoothBuiltIn) {
        OwnBluetoothApp(d)
        return
    }

    // Lo que se dio o quitó en Ajustes de Android se nota al volver.
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumed++
        onPauseOrDispose {}
    }
    val permissions = remember { scanPermissions() }
    var denied by remember { mutableStateOf(false) }
    var asked by remember { mutableIntStateOf(0) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        denied = !result.values.all { it }
        asked++
    }
    val granted = remember(resumed, asked) { permissions.all { context.has(it) } }
    // Primero los permisos: desde Android 12 hasta pedir que se prenda el Bluetooth necesita BLUETOOTH_CONNECT.
    if (!granted) {
        val help = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) R.string.bt_permission_help else R.string.bt_permission_help_location
        Label(stringResource(help), Typography.small, c.textMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(stringResource(R.string.bt_permission), { ask.launch(permissions.toTypedArray()) }, primary = true)
            // Negado para siempre, Android ya no pregunta: se da en sus ajustes.
            if (denied) Button(stringResource(R.string.local_network_settings), { openAppSettings(context) })
        }
        return
    }

    // Prendido o apagado: el mismo vigilante que el Taller.
    val on = rememberBluetoothOn() == true
    var scanning by remember { mutableStateOf(false) }
    val found = remember { mutableStateListOf<Found>() }
    // El que se está emparejando ahora (dirección), para decir "Emparejando…".
    var bonding by remember { mutableStateOf<String?>(null) }
    // Sube con cada emparejamiento nuevo o perdido: recarga la lista de guardados.
    var bonds by remember { mutableIntStateOf(0) }

    LifecycleStartEffect(adapter) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                when (i.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val dev = IntentCompat.getParcelableExtra(i, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        if (dev != null && found.size < FOUND_MAX && found.none { it.device.address == dev.address }) found.add(Found(dev, deviceLabel(dev)))
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> scanning = false
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val dev = IntentCompat.getParcelableExtra(i, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        val state = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                        bonding = dev?.address?.takeIf { state == BluetoothDevice.BOND_BONDING }
                        bonds++
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        // Exportado: estos avisos los manda la app de Bluetooth del sistema, que hasta Android 12 no pasa por un
        // receptor no exportado (ContextCompat lo protege con un permiso que ella no tiene).
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        onStopOrDispose {
            context.unregisterReceiver(receiver)
            // Sin el receptor no llegaría el fin de la búsqueda: se corta aquí.
            cancelDiscovery(adapter)
            scanning = false
        }
    }

    if (!on) {
        val askEnable = rememberBluetoothAsk()
        Label(stringResource(R.string.bluetooth_off), Typography.body, c.danger)
        Button(stringResource(R.string.bluetooth_turn_on), { requestBluetoothOn(context, askEnable) }, primary = true)
        return
    }

    // Android 10 y 11 solo encuentran celulares con la ubicación prendida.
    if (Build.VERSION.SDK_INT in Build.VERSION_CODES.Q..Build.VERSION_CODES.R && !remember(resumed) { locationOn(context) }) {
        Label(stringResource(R.string.bt_location_off), Typography.small, c.textMuted)
        Button(stringResource(R.string.sys_location), { openSystemScreen(context, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) })
    }

    // El celular conectado.
    val connected = d.music.bluetoothDevice
    if (connected != null) Label(stringResource(R.string.bt_connected, connected), Typography.body)
    else Label(stringResource(R.string.bt_not_connected), Typography.body, c.textMuted)

    // Cómo se llama esta pantalla, para buscarla desde el celular.
    // Nombres y guardados se le preguntan al servicio de Bluetooth (entre procesos): fuera del hilo principal.
    val myName by produceState("", adapter) { value = withContext(Dispatchers.IO) { adapterName(adapter) } }
    if (myName.isNotEmpty()) Label(stringResource(R.string.bt_screen_name, myName), Typography.small, c.textMuted)

    DiscoverableButton()

    // Buscar celulares cerca y emparejar tocando uno.
    Button(stringResource(if (scanning) R.string.bt_searching else R.string.bt_search), {
        if (scanning) return@Button
        found.clear()
        // Si Android rechaza la búsqueda, el botón no se queda en "Buscando…".
        scanning = startDiscovery(adapter)
    })
    if (found.isNotEmpty()) {
        Label(stringResource(R.string.bt_found), Typography.label, c.textMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (f in found) {
                val pairing = bonding == f.device.address
                Button(if (pairing) stringResource(R.string.bt_pairing) else f.label, {
                    // Android empareja mejor sin la búsqueda corriendo.
                    cancelDiscovery(adapter)
                    bond(f.device)
                }, enabled = !pairing)
            }
        }
    }

    // Celulares ya guardados (solo celulares: el adaptador OBD o unos audífonos no se olvidan desde la música).
    val bonded by produceState(emptyList<Found>(), on, bonds) {
        value = withContext(Dispatchers.IO) { bondedPhones(adapter).map { Found(it, deviceLabel(it)) } }
    }
    if (bonded.isNotEmpty()) {
        Label(stringResource(R.string.bt_paired), Typography.label, c.textMuted)
        // Desde Android 13 olvidar pide un permiso de sistema: en vez de un botón que siempre falla, se dice dónde hacerlo.
        val canForget = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        var forgetFailed by remember { mutableStateOf(false) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (f in bonded) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Label(f.label, Typography.body, maxLines = 1)
                    if (canForget) {
                        val forgetLabel = stringResource(R.string.bt_forget)
                        Confirmed(forgetLabel, stringResource(R.string.bt_forget_question, f.label), forgetLabel, stringResource(R.string.bt_forget_message)) {
                            // Se quita de la lista cuando Android avisa que se olvidó (ACTION_BOND_STATE_CHANGED).
                            if (!forget(f.device)) {
                                forgetFailed = true
                                openSystemScreen(context, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                            }
                        }
                    }
                }
            }
        }
        if (!canForget || forgetFailed) Label(stringResource(R.string.bt_forget_failed), Typography.small, c.textMuted)
        if (!canForget) {
            Button(stringResource(R.string.sys_bluetooth), { openSystemScreen(context, Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) })
        }
    }
}

/** Pantalla con chip de Bluetooth propio: abrir (o elegir) su app de Bluetooth, con el mismo selector de apps. */
@Composable
private fun OwnBluetoothApp(d: Dependencies) {
    val c = LocalColors.current
    Label(stringResource(R.string.bt_own_pair), Typography.body, c.textMuted)
    val apps = rememberApps(d.apps).orEmpty()
    // La enlazada o, si no hay, la de Bluetooth de marca que trae la pantalla (TopWay, FYT…).
    val app = d.music.bluetoothApp()
    if (app == null) Label(stringResource(R.string.bt_pick_help), Typography.small, c.textMuted)
    else Button(stringResource(R.string.bt_open_app), { d.music.openBluetoothApp() }, primary = true)
    val linked = app?.let { p -> p to (apps.firstOrNull { it.pkg == p }?.name ?: d.music.appName(p).ifEmpty { p }) }
    AppPicker(
        apps, listOfNotNull(linked), app, {}, { d.music.setSource(SourceKind.BLUETOOTH, it.pkg) },
        other = stringResource(R.string.source_choose),
        searchHelp = stringResource(R.string.bt_pick_help).takeIf { app != null },
    )
    Label(stringResource(R.string.bt_own_help), Typography.small, c.textMuted)
}

/**
 * «Hacer visible esta pantalla» 5 minutos. Desde Android 12 eso pide BLUETOOTH_ADVERTISE; sin él (o si la
 * pantalla no trae esa ventana) se abren los ajustes de Bluetooth de Android, que la hacen visible mientras están abiertos.
 */
@Composable
private fun DiscoverableButton() {
    val context = LocalContext.current
    val discover = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    fun show() {
        try {
            discover.launch(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "sin ventana para hacerse visible", e)
            openSystemScreen(context, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
    }
    val askAdvertise = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) show() else openSystemScreen(context, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }
    Button(stringResource(R.string.bt_discover), {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !context.has(Manifest.permission.BLUETOOTH_ADVERTISE)) {
            askAdvertise.launch(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else show()
    })
}

/** Lo que pide buscar y emparejar: Bluetooth cercano desde Android 12; antes, la ubicación. */
private fun scanPermissions(): List<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
    else listOf(Manifest.permission.ACCESS_FINE_LOCATION)

private fun Context.has(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun locationOn(context: Context): Boolean =
    context.getSystemService(LocationManager::class.java)?.let(LocationManagerCompat::isLocationEnabled) ?: false

@SuppressLint("MissingPermission")
private fun adapterName(adapter: BluetoothAdapter): String = adapter.name.orEmpty()

@SuppressLint("MissingPermission")
private fun deviceLabel(dev: BluetoothDevice): String = dev.name?.takeIf { it.isNotBlank() } ?: dev.address

@SuppressLint("MissingPermission")
private fun bondedPhones(adapter: BluetoothAdapter): List<BluetoothDevice> =
    adapter.bondedDevices.orEmpty().filter { it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PHONE }

@SuppressLint("MissingPermission")
private fun startDiscovery(adapter: BluetoothAdapter): Boolean = adapter.startDiscovery()

@SuppressLint("MissingPermission")
private fun cancelDiscovery(adapter: BluetoothAdapter) {
    adapter.cancelDiscovery()
}

@SuppressLint("MissingPermission")
private fun bond(dev: BluetoothDevice) {
    if (!dev.createBond()) Log.w(TAG, "Android no empezó a emparejar ${dev.address}")
}

/**
 * Quitar el vínculo guardado (hasta Android 12): `removeBond` no es pública y va por reflexión; false = no se pudo (se
 * olvida en los ajustes de Android).
 */
private fun forget(dev: BluetoothDevice): Boolean = try {
    dev.javaClass.getMethod("removeBond").invoke(dev) == true
} catch (e: ReflectiveOperationException) {
    Log.w(TAG, "removeBond falló", e)
    false
} catch (e: SecurityException) {
    Log.w(TAG, "removeBond sin permiso", e)
    false
}
