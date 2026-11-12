package com.irvingguerra.litedash.feature.link

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import java.util.UUID

/**
 * Ve por Bluetooth de baja energía si el celular con LiteDash Connect está cerca (PROTOCOLO_LINK.md §1d):
 * el celular anuncia su servicio mientras busca a la pantalla. Al verlo se avisa a [onSeen] (el enlace lo
 * busca de inmediato por la red) y, una vez por sesión, se conecta un momento a leer su característica
 * para medir si por aquí podrá viajar el enlace completo (queda en el reporte). No pide permisos: usa los
 * que la pantalla ya haya dado (Llantas, OBD o música por Bluetooth). Todo en el hilo principal.
 */
internal class BlePresence(private val app: Context, private val onSeen: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var scanner: BluetoothLeScanner? = null
    private var startedAt = 0L
    private var toldAt = 0L

    // Para el reporte.
    private var problem: String? = null
    private var seenAt = 0L
    private var seenRssi = 0
    private var probe: String? = null
    private var probing: BluetoothGatt? = null

    /** Busca mientras [on]; se puede llamar seguido (no reinicia la búsqueda si ya corre). */
    @SuppressLint("MissingPermission") // [missing] se revisa antes
    fun watch(on: Boolean) {
        if (!on) return stop()
        if (scanner != null) return
        val now = SystemClock.elapsedRealtime()
        // Android bloquea a quien arranca búsquedas muy seguido: a lo más una cada tanto.
        if (startedAt != 0L && now - startedAt < RESTART_MS) return
        problem = missing()
        if (problem != null) return
        val le = app.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: return run { problem = "sin buscador" }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
        startedAt = now
        runCatching {
            le.startScan(listOf(filter), settings, found)
            scanner = le
        }.onFailure { problem = "no arrancó: ${it.message ?: it.javaClass.simpleName}" }
    }

    @SuppressLint("MissingPermission")
    private fun stop() {
        val le = scanner ?: return
        scanner = null
        runCatching { le.stopScan(found) }
    }

    /** Lo que impide buscar; null = se puede. */
    private fun missing(): String? {
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter ?: return "sin adaptador"
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION
        if (app.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return "sin permiso"
        return runCatching { if (adapter.isEnabled) null else "Bluetooth apagado" }.getOrElse { "sin permiso" }
    }

    private val found = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post { seen(result) }
        }

        override fun onScanFailed(errorCode: Int) {
            main.post {
                scanner = null
                problem = "falló ($errorCode)"
            }
        }
    }

    private fun seen(result: ScanResult) {
        if (scanner == null) return
        val now = SystemClock.elapsedRealtime()
        seenAt = now
        seenRssi = result.rssi
        if (probe == null && probing == null) measure(result.device)
        // Los anuncios llegan varias veces por segundo: con avisar cada tanto basta.
        if (now - toldAt < TELL_MS) return
        toldAt = now
        onSeen()
    }

    /** Se conecta un momento, pide el paquete más grande y lee la característica del celular. */
    @SuppressLint("MissingPermission")
    private fun measure(device: BluetoothDevice) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            probe = "sin permiso para conectarse"
            return
        }
        val gatt = runCatching { device.connectGatt(app, false, answers, BluetoothDevice.TRANSPORT_LE) }.getOrNull()
        if (gatt == null) {
            probe = "no se pudo conectar"
            return
        }
        probing = gatt
        main.postDelayed({ if (probing === gatt) finish(gatt, "sin respuesta") }, PROBE_MS)
    }

    @SuppressLint("MissingPermission")
    private fun finish(gatt: BluetoothGatt, result: String) {
        if (probing !== gatt) return
        probing = null
        probe = result
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
    }

    private val answers = object : BluetoothGattCallback() {
        private var mtu = DEFAULT_MTU

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // Sin paquete grande también sirve; si no deja pedirlo, se sigue con el de fábrica.
                if (!runCatching { gatt.requestMtu(WANTED_MTU) }.getOrDefault(false)) gatt.discoverServices()
            } else {
                main.post { finish(gatt, "se desconectó (estado $status)") }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) this.mtu = mtu
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val info = gatt.getService(SERVICE)?.getCharacteristic(INFO)
            if (info == null || !runCatching { gatt.readCharacteristic(info) }.getOrDefault(false)) {
                main.post { finish(gatt, "sin el servicio de Connect (estado $status)") }
            }
        }

        @Deprecated("La variante nueva (Android 13) no existe en las pantallas viejas")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val text = characteristic.value?.toString(Charsets.US_ASCII)
            main.post { finish(gatt, if (status == BluetoothGatt.GATT_SUCCESS && text == INFO_TEXT) "sí, paquete $mtu" else "leyó mal (estado $status)") }
        }
    }

    /** Para "Enviar un reporte". */
    fun describe(): String {
        val seen = if (seenAt == 0L) "no" else "hace ${(SystemClock.elapsedRealtime() - seenAt) / 1000} s (señal $seenRssi)"
        return "Celular por Bluetooth: buscando=${if (scanner != null) "sí" else problem ?: "no"}, visto=$seen, conexión=${probe ?: if (probing != null) "probando" else "sin probar"}"
    }

    private companion object {
        /** El servicio de LiteDash Connect y su característica de lectura (los mismos en el celular). */
        val SERVICE: UUID = UUID.fromString("8c1f0001-2b6a-4f0e-9d4b-6c6974656461")
        val INFO: UUID = UUID.fromString("8c1f0002-2b6a-4f0e-9d4b-6c6974656461")
        const val INFO_TEXT = "LD-LINK-1 ble"
        const val DEFAULT_MTU = 23
        const val WANTED_MTU = 517
        const val RESTART_MS = 8_000L
        const val TELL_MS = 10_000L
        const val PROBE_MS = 12_000L
    }
}
