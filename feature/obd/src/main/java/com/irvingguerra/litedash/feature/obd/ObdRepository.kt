package com.irvingguerra.litedash.feature.obd

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.irvingguerra.litedash.core.vehicle.Source
import com.irvingguerra.litedash.core.vehicle.VehicleBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A qué adaptador conectarse. Se guarda para reconectar solo la próxima vez. */
sealed interface ObdTarget {
    val name: String
    data class Bluetooth(val address: String, override val name: String) : ObdTarget
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

/**
 * El escáner OBD. No existe hasta que algo lo pide (el Taller o una pieza con datos
 * del motor) y solo pregunta al auto lo que se está mostrando ([VehicleBus.wanted]):
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

    private val prefs = context.getSharedPreferences("obd", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var link: ElmLink? = null
    private var elm: Elm327? = null
    private var job: Job? = null

    var target: ObdTarget?
        get() = when (prefs.getString("tipo", null)) {
            "bt" -> ObdTarget.Bluetooth(prefs.getString("direccion", "")!!, prefs.getString("nombre", "")!!)
            "wifi" -> ObdTarget.Wifi(prefs.getString("direccion", "")!!, prefs.getInt("puerto", 35000))
            else -> null
        }
        private set(t) {
            prefs.edit().apply {
                when (t) {
                    is ObdTarget.Bluetooth -> putString("tipo", "bt").putString("direccion", t.address).putString("nombre", t.name)
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

    /** Se conecta a [t] (o al último que funcionó) y empieza a leer. */
    fun connect(t: ObdTarget? = target) {
        if (t == null || state == ObdState.CONNECTING || state == ObdState.ON && target == t) return
        disconnect()
        state = ObdState.CONNECTING
        error = null
        job = scope.launch {
            try {
                val l = open(t)
                val e = Elm327(l)
                val version = lock.withLock { e.init() }
                val pids = lock.withLock { e.supportedPids() }
                link = l
                elm = e
                supported = pids
                adapter = version
                target = t
                state = ObdState.ON
                poll(e)
            } catch (x: Exception) {
                if (isActive) {
                    error = x.message ?: x.javaClass.simpleName
                    state = ObdState.ERROR
                }
                closeLink()
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

    private suspend fun poll(e: Elm327) {
        var lastSlow = 0L
        while (currentCoroutineContextActive()) {
            val wanted = bus.wanted
            val pids = Pids.filter { it.signal in wanted && it.code in supported }
            if (pids.isEmpty()) {
                delay(250)
                continue
            }
            val slow = System.currentTimeMillis() - lastSlow > 2_000
            if (slow) lastSlow = System.currentTimeMillis()
            for (p in pids) {
                if (!p.fast && !slow) continue
                val value = lock.withLock { e.pid(p.code) }?.let(p.decode)
                withContext(Dispatchers.Main) { bus.publish(p.signal, value, Source.OBD) }
            }
            delay(60)
        }
    }

    private suspend fun currentCoroutineContextActive() = kotlinx.coroutines.currentCoroutineContext().isActive

    @SuppressLint("MissingPermission")
    private fun open(t: ObdTarget): ElmLink = when (t) {
        is ObdTarget.Wifi -> WifiLink(t.host, t.port)
        is ObdTarget.Bluetooth -> {
            if (!hasPermission()) throw SecurityException("Sin permiso de Bluetooth")
            val device = context.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(t.address)
            BluetoothLink(device)
        }
    }

    private fun closeLink() {
        runCatching { link?.close() }
        link = null
        elm = null
        scope.launch(Dispatchers.Main) { bus.clear(Source.OBD) }
    }

    private fun looksLikeObd(name: String) = Regex("obd|elm|vlink|vgate|icar|obdlink|konnwei|v-link", RegexOption.IGNORE_CASE).containsMatchIn(name)
}
