package com.irvingguerra.litedash.feature.obd

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.irvingguerra.litedash.core.vehicle.Signal
import com.irvingguerra.litedash.core.vehicle.Source
import com.irvingguerra.litedash.core.vehicle.VehicleBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A qué adaptador conectarse. Se guarda para reconectar solo la próxima vez. */
sealed interface ObdTarget {
    val name: String
    data class Bluetooth(val address: String, override val name: String) : ObdTarget
    /** Bluetooth LE: no se empareja, se encuentra buscando ([ObdRepository.scanBle]). */
    data class Ble(val address: String, override val name: String) : ObdTarget
    data class Wifi(val host: String, val port: Int) : ObdTarget {
        override val name get() = "$host:$port"
    }
}

enum class ObdState { OFF, CONNECTING, ON, ERROR }

/** Lo que dice el auto del motor: luz de falla, códigos y monitores; VIN y batería. */
data class EngineReport(
    val readiness: Readiness?,
    val stored: List<String>,
    val pending: List<String>,
    val permanent: List<String>,
    val vin: String?,
    val voltage: Float?,
)

/** Nombres comunes de adaptadores ELM327 (para ponerlos primero en la lista). */
private val OBD_NAMES = Regex("obd|elm|vlink|vgate|icar|obdlink|konnwei|v-link", RegexOption.IGNORE_CASE)

/**
 * El escáner OBD. No existe hasta que algo lo pide (el Taller, o el velocímetro si ya
 * hay un adaptador guardado) y solo pregunta al auto lo que se está mostrando ([VehicleBus.wanted]):
 * lo rápido (velocidad, rpm) unas 10 veces por segundo y lo lento cada 2 s.
 */
class ObdRepository(private val context: Context, private val bus: VehicleBus) {
    var state by mutableStateOf(ObdState.OFF)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var adapter by mutableStateOf<String?>(null)
        private set
    /** PIDs que contesta este auto (vacío hasta conectar). */
    var supported by mutableStateOf<Set<Int>>(emptySet())
        private set

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var link: ElmLink? = null
    private var elm: Elm327? = null
    private var job: Job? = null

    var target: ObdTarget?
        get() = when (prefs.getString("tipo", null)) {
            "bt" -> ObdTarget.Bluetooth(prefs.getString("direccion", "")!!, prefs.getString("nombre", "")!!)
            "ble" -> ObdTarget.Ble(prefs.getString("direccion", "")!!, prefs.getString("nombre", "")!!)
            "wifi" -> ObdTarget.Wifi(prefs.getString("direccion", "")!!, prefs.getInt("puerto", 35000))
            else -> null
        }
        private set(t) {
            prefs.edit().apply {
                when (t) {
                    is ObdTarget.Bluetooth -> putString("tipo", "bt").putString("direccion", t.address).putString("nombre", t.name)
                    is ObdTarget.Ble -> putString("tipo", "ble").putString("direccion", t.address).putString("nombre", t.name)
                    is ObdTarget.Wifi -> putString("tipo", "wifi").putString("direccion", t.host).putInt("puerto", t.port)
                    null -> clear()
                }
            }.apply()
        }

    fun hasPermission() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Adaptadores emparejados en Ajustes de Android; los que parecen OBD primero. */
    @SuppressLint("MissingPermission")
    fun pairedDevices(): List<ObdTarget.Bluetooth> {
        if (!hasPermission()) return emptyList()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return runCatching { adapter.bondedDevices.orEmpty() }.getOrDefault(emptySet())
            .map { ObdTarget.Bluetooth(it.address, it.name ?: it.address) }
            .sortedByDescending { looksLikeObd(it.name) }
    }

    /** Adaptadores BLE que se encontraron en la última búsqueda (los que parecen OBD primero). */
    var found by mutableStateOf<List<ObdTarget.Ble>>(emptyList())
        private set
    var scanning by mutableStateOf(false)
        private set
    /** Ya se buscó al menos una vez (para decir "no se encontró ninguno"). */
    var searched by mutableStateOf(false)
        private set

    /**
     * Buscar requiere su propio permiso: "dispositivos cercanos" desde Android 12 (sin
     * ubicación) y, antes, la ubicación (así lo pide Android para Bluetooth LE).
     */
    fun scanPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION

    fun canScan() = context.checkSelfPermission(scanPermission()) == PackageManager.PERMISSION_GRANTED

    /** Busca adaptadores Bluetooth LE unos segundos; solo cuando el usuario lo pide. */
    @SuppressLint("MissingPermission")
    fun scanBle() {
        if (scanning || !canScan()) return
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: return
        val seen = linkedMapOf<String, ObdTarget.Ble>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                // Sin nombre no hay cómo reconocerlo en la lista.
                val name = result.scanRecord?.deviceName ?: return
                if (seen.put(result.device.address, ObdTarget.Ble(result.device.address, name)) == null) {
                    found = seen.values.sortedByDescending { looksLikeObd(it.name) }
                }
            }
        }
        found = emptyList()
        scanning = true
        scope.launch(Dispatchers.Main) {
            runCatching { scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback) }
                .onFailure { scanning = false; return@launch }
            delay(SCAN_MS)
            runCatching { scanner.stopScan(callback) }
            scanning = false
            searched = true
        }
    }

    /** Se conecta a [t] (o al último que funcionó) y empieza a leer. */
    fun connect(t: ObdTarget? = target) {
        if (t == null || state == ObdState.CONNECTING || state == ObdState.ON && target == t) return
        disconnect()
        state = ObdState.CONNECTING
        error = null
        job = scope.launch {
            // El enlace de este intento: se cierra aquí si algo falla (aunque todavía no sea
            // el actual), y los campos compartidos solo se tocan mientras este job siga vivo.
            var l: ElmLink? = null
            try {
                l = open(t)
                val e = Elm327(l)
                val version = lock.withLock { e.init() }
                val pids = lock.withLock { e.supportedPids() }
                ensureActive()
                withContext(Dispatchers.Main) {
                    link = l
                    elm = e
                    supported = pids
                    adapter = version
                    target = t
                    state = ObdState.ON
                }
                poll(e)
            } catch (x: Exception) {
                runCatching { l?.close() }
                if (isActive) withContext(Dispatchers.Main + NonCancellable) {
                    error = x.message ?: x.javaClass.simpleName
                    state = ObdState.ERROR
                    link = null
                    elm = null
                    bus.clear(Source.OBD)
                }
            }
        }
    }

    fun disconnect() {
        job?.cancel()
        job = null
        closeLink()
        state = ObdState.OFF
    }

    /** Olvida el adaptador guardado (para elegir otro). */
    fun forget() {
        disconnect()
        target = null
    }

    /** Lee todo lo del motor de una vez para el Taller. */
    suspend fun report(): EngineReport? = withElm { e ->
        EngineReport(
            readiness = e.pid(0x01)?.let(::parseReadiness),
            stored = e.troubleCodes("03"),
            pending = e.troubleCodes("07"),
            permanent = runCatching { e.troubleCodes("0A") }.getOrDefault(emptyList()),
            vin = runCatching { e.vin() }.getOrNull(),
            voltage = runCatching { e.voltage() }.getOrNull(),
        )
    }

    /** Borra los códigos (el Taller pide confirmar dos veces antes). */
    suspend fun clearCodes(): Boolean = withElm { it.clearCodes() } ?: false

    private suspend fun <T> withElm(block: (Elm327) -> T): T? {
        val e = elm ?: return null
        return withContext(Dispatchers.IO) { runCatching { lock.withLock { block(e) } }.getOrNull() }
    }

    /**
     * ¿Se puede mostrar [signal] con este auto? Las calculadas, si da lo que necesitan;
     * el voltaje siempre (si no lo da el auto, lo mide el adaptador).
     */
    fun canRead(signal: String): Boolean {
        if (signal == Signal.VOLTAGE) return true
        DerivedSignals.firstOrNull { it.signal == signal }?.let { d -> return d.needs.all(::canRead) }
        return Pids.any { it.signal == signal && it.code in supported }
    }

    private suspend fun poll(e: Elm327) {
        var lastSlow = 0L
        // Lo último que se leyó de cada señal (para calcular las que dependen de varias).
        val last = mutableMapOf<String, Float>()
        val smoothed = mutableMapOf<String, Float>()
        while (true) {
            val shown = bus.wanted
            val derived = DerivedSignals.filter { it.signal in shown }
            val wanted = shown + derived.flatMap { it.needs + it.extras }
            val pids = Pids.filter { it.signal in wanted && it.code in supported }
            // Sin el PID 42 el voltaje lo mide el adaptador (ATRV), con lo lento.
            val adapterVoltage = Signal.VOLTAGE in wanted && VOLTAGE_PID !in supported
            if (pids.isEmpty() && !adapterVoltage) {
                delay(250)
                continue
            }
            val slow = System.currentTimeMillis() - lastSlow > 2_000
            if (slow) lastSlow = System.currentTimeMillis()
            // Se leen todos y se publican juntos: un solo salto al hilo principal por vuelta.
            val values = mutableListOf<Pair<String, Float?>>()
            for (p in pids) if (p.fast || slow) values += p.signal to lock.withLock { e.pid(p.code) }?.let(p.decode)
            if (adapterVoltage && slow) values += Signal.VOLTAGE to runCatching { lock.withLock { e.voltage() } }.getOrNull()
            for ((signal, value) in values) if (value == null) last.remove(signal) else last[signal] = value
            for (d in derived) {
                val raw = d.compute(last)
                val prev = smoothed[d.signal]
                val value = if (raw == null || prev == null) raw else prev + (raw - prev) * d.smoothing
                if (value == null) smoothed.remove(d.signal) else smoothed[d.signal] = value
                values += d.signal to value
            }
            withContext(Dispatchers.Main) { for ((signal, value) in values) bus.publish(signal, value, Source.OBD) }
            delay(60)
        }
    }

    @SuppressLint("MissingPermission")
    private fun open(t: ObdTarget): ElmLink = when (t) {
        is ObdTarget.Wifi -> WifiLink(t.host, t.port)
        is ObdTarget.Bluetooth -> BluetoothLink(device(t.address))
        is ObdTarget.Ble -> BleLink(context, device(t.address))
    }

    private fun device(address: String): BluetoothDevice {
        if (!hasPermission()) throw SecurityException("Sin permiso de Bluetooth")
        return context.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(address)
    }

    private fun closeLink() {
        runCatching { link?.close() }
        link = null
        elm = null
        scope.launch(Dispatchers.Main) { bus.clear(Source.OBD) }
    }

    private fun looksLikeObd(name: String) = OBD_NAMES.containsMatchIn(name)

    companion object {
        private const val PREFS = "obd"
        /** Cuánto dura una búsqueda de adaptadores Bluetooth LE. */
        private const val SCAN_MS = 10_000L
        /** Voltaje del módulo de control (el de la batería con el motor andando). */
        private const val VOLTAGE_PID = 0x42

        /** Hay un adaptador guardado (sin crear el escáner ni tocar Bluetooth). */
        fun hasSavedTarget(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains("tipo")
    }
}
