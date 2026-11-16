package com.irvingguerra.litedash.feature.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import com.irvingguerra.litedash.core.vehicle.bleLocationOff
import com.irvingguerra.litedash.core.vehicle.canScanBle
import java.util.UUID

/**
 * Ve por Bluetooth de baja energía si el celular con LiteDash Connect está cerca (PROTOCOLO_LINK.md §1d):
 * el celular anuncia su servicio mientras busca a la pantalla. Al verlo se avisa a [onSeen] (el enlace lo
 * busca de inmediato por la red). Solo escucha anuncios: nunca se conecta. No pide permisos: usa los
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

    /** Busca mientras [on]; se puede llamar seguido (no reinicia la búsqueda si ya corre). */
    @SuppressLint("MissingPermission") // [missing] se revisa antes
    fun watch(on: Boolean) {
        if (!on) return stop()
        // El Bluetooth se apagó con la búsqueda viva (muchas pantallas lo apagan al quitar el contacto): esa
        // búsqueda ya murió; se suelta para volver a arrancarla cuando prenda.
        if (scanner != null && missing() != null) scanner = null
        if (scanner != null) return
        val now = SystemClock.elapsedRealtime()
        // Android bloquea a quien arranca búsquedas muy seguido: a lo más una cada tanto; se intenta al cumplirse.
        if (startedAt != 0L && now - startedAt < RESTART_MS) {
            main.removeCallbacks(retry)
            main.postDelayed(retry, RESTART_MS - (now - startedAt))
            return
        }
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

    /** El arranque aplazado de [watch]; [stop] lo cancela. */
    private val retry = Runnable { watch(true) }

    @SuppressLint("MissingPermission")
    private fun stop() {
        main.removeCallbacks(retry)
        val le = scanner ?: return
        scanner = null
        runCatching { le.stopScan(found) }
    }

    /** Lo que impide buscar; null = se puede. */
    private fun missing(): String? {
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter ?: return "sin adaptador"
        if (!canScanBle(app)) return "sin permiso"
        if (!runCatching { adapter.isEnabled }.getOrDefault(false)) return "Bluetooth apagado"
        if (bleLocationOff(app)) return "ubicación apagada"
        return null
    }

    private val found = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post { seen(result) }
        }

        override fun onScanFailed(errorCode: Int) {
            // "Ya estaba buscando": la búsqueda sigue viva y hay que poder pararla.
            if (errorCode == SCAN_FAILED_ALREADY_STARTED) return
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
        // Los anuncios llegan varias veces por segundo: con avisar cada tanto basta.
        if (now - toldAt < TELL_MS) return
        toldAt = now
        onSeen()
    }

    /** Para "Enviar un reporte". */
    fun describe(): String {
        val seen = if (seenAt == 0L) "no" else "hace ${(SystemClock.elapsedRealtime() - seenAt) / 1000} s (señal $seenRssi)"
        return "Celular por Bluetooth: buscando=${if (scanner != null) "sí" else problem ?: "no"}, visto=$seen"
    }

    private companion object {
        /** El servicio que anuncia LiteDash Connect (el mismo en el celular). */
        val SERVICE: UUID = UUID.fromString("8c1f0001-2b6a-4f0e-9d4b-6c6974656461")
        const val RESTART_MS = 8_000L
        const val TELL_MS = 30_000L
    }
}
