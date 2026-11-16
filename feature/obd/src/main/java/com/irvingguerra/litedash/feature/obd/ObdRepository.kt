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
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.irvingguerra.litedash.core.vehicle.Signal
import com.irvingguerra.litedash.core.vehicle.Source
import com.irvingguerra.litedash.core.vehicle.VehicleBus
import com.irvingguerra.litedash.core.vehicle.bleLocationOff
import com.irvingguerra.litedash.core.vehicle.canScanBle
import com.irvingguerra.litedash.core.vehicle.localNetworkAllowed
import com.irvingguerra.litedash.core.vehicle.requestUsbPermission
import com.irvingguerra.litedash.core.vehicle.watchUsbDetached
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/** A qué adaptador conectarse. Se guarda para reconectar solo la próxima vez. */
sealed interface ObdTarget {
    val name: String
    /** Cómo se guarda y cómo sale en el reporte (fijo: en la release R8 cambia los nombres de las clases). */
    val key: String
    data class Bluetooth(val address: String, override val name: String) : ObdTarget {
        override val key get() = "bt"
    }
    /** Bluetooth LE: no se empareja, se encuentra buscando ([ObdRepository.scanBle]). */
    data class Ble(val address: String, override val name: String) : ObdTarget {
        override val key get() = "ble"
    }
    data class Wifi(val host: String, val port: Int) : ObdTarget {
        override val name get() = "$host:$port"
        override val key get() = "wifi"

        companion object {
            /** El puerto de casi todos los ELM327 por Wi-Fi (y del simulador). */
            const val DEFAULT_PORT = 35000
        }
    }
    /** Cable USB (la pantalla es el host); se reconoce por fabricante y producto. */
    data class Usb(val vendorId: Int, val productId: Int, override val name: String) : ObdTarget {
        override val key get() = "usb"
    }
}

enum class ObdState { OFF, CONNECTING, ON, ERROR }

/** Por qué una búsqueda de Bluetooth LE no pudo buscar (para decirlo claro). */
enum class ScanProblem { BLUETOOTH_OFF, LOCATION_OFF, FAILED }

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
 * Lo que se sabe de las llantas de fábrica en una conexión (se olvida al reconectar): si el protocolo
 * es CAN de 11 bits (las recetas son de ahí, y regresar al motor con "ATSH7DF" en otro dejaría de
 * leerlo), la receta que contestó aunque aún no haya presiones, y cuántas búsquedas van. Lo lee y
 * escribe la corrutina de Llantas: por eso cada campo es @Volatile y el objeto es de una sola conexión.
 */
private class TireSession(val elm: Elm327) {
    @Volatile var can: Boolean? = null
    @Volatile var candidate: TireProfile? = null
    @Volatile var searches = 0
    @Volatile var lastSearch = 0L
}

/**
 * El escáner OBD. No existe hasta que algo lo pide (el Taller, o el velocímetro si ya
 * hay un adaptador guardado) y solo pregunta al auto lo que se está mostrando ([VehicleBus.wanted]):
 * lo rápido (velocidad, rpm) unas 10 veces por segundo y lo lento cada 2 s.
 *
 * La política de conexión vive toda aquí: quien lo use dice [want] ("lo quiero") y [release] ("ya
 * no"); con el primero conecta al adaptador guardado y sin ninguno desconecta. Tras un fallo,
 * [retryDelay] decide si reintenta y cuándo: mientras alguien lo quiere no se rinde (con esperas
 * crecientes); sin nadie se rinde y vuelve a intentar cuando alguien nuevo lo quiere o el usuario toca
 * Conectar ([connect]).
 */
class ObdRepository(private val context: Context, private val bus: VehicleBus) {
    var state by mutableStateOf(ObdState.OFF)
        private set
    /** Por qué falló la última conexión (null mientras no haya error): la app lo dice con su propio texto. */
    var errorKind by mutableStateOf<ObdError?>(null)
        private set
    /** El detalle crudo del fallo: solo para el reporte ([describe]). */
    @Volatile private var errorDetail: String? = null

    var adapter by mutableStateOf<String?>(null)
        private set
    /** PIDs que contesta este auto (vacío hasta conectar). */
    var supported by mutableStateOf<Set<Int>>(emptySet())
        private set

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var link: ElmLink? = null
    /** El enlace que se está abriendo o preparando: [disconnect] lo cierra desde aquí (lo suelta aunque esté bloqueado). */
    @Volatile private var pending: ElmLink? = null
    private var elm: Elm327? = null
    @Volatile private var tires: TireSession? = null
    private var job: Job? = null
    /** El reintento programado (se cancela al desconectar). */
    private var retryJob: Job? = null
    /** El adaptador del intento en curso o del último que falló (aún no es [target] si nunca conectó). */
    private var attempting: ObdTarget? = null
    /** Reintentos seguidos sin lograr conectar (se reinicia al conectar bien y en cada intento del usuario). */
    private var retries = 0
    /** En qué intento conectó la última vez (para saber en el reporte si los tiempos sirven). */
    private var connectedOnAttempt = 0
    /** Quiénes quieren el escáner ([want]). */
    private val wanters = mutableSetOf<Any>()
    /** Dejar de escuchar el permiso USB pedido / el desconectado del cable. */
    private var cancelUsbPermission: (() -> Unit)? = null
    private var unwatchDetach: (() -> Unit)? = null

    // Se lee del disco una vez; como estado de Compose, el Taller se entera al cambiarlo.
    private var savedTarget by mutableStateOf(readTarget())

    var target: ObdTarget?
        get() = savedTarget
        private set(t) {
            savedTarget = t
            prefs.edit().apply {
                if (t == null) clear() else putString(KEY_TYPE, t.key)
                when (t) {
                    is ObdTarget.Bluetooth -> putString("address", t.address).putString("name", t.name)
                    is ObdTarget.Ble -> putString("address", t.address).putString("name", t.name)
                    is ObdTarget.Wifi -> putString("address", t.host).putInt("port", t.port)
                    is ObdTarget.Usb -> putInt("vendor_id", t.vendorId).putInt("product_id", t.productId).putString("name", t.name)
                    null -> Unit
                }
            }.apply()
        }

    private fun readTarget(): ObdTarget? = when (prefs.getString(KEY_TYPE, null)) {
        "bt" -> ObdTarget.Bluetooth(prefs.getString("address", "")!!, prefs.getString("name", "")!!)
        "ble" -> ObdTarget.Ble(prefs.getString("address", "")!!, prefs.getString("name", "")!!)
        "wifi" -> ObdTarget.Wifi(prefs.getString("address", "")!!, prefs.getInt("port", ObdTarget.Wifi.DEFAULT_PORT))
        "usb" -> ObdTarget.Usb(prefs.getInt("vendor_id", 0), prefs.getInt("product_id", 0), prefs.getString("name", "")!!)
        else -> null
    }

    /**
     * El adaptador es por Wi‑Fi y falta el permiso de la red local (Android 17+): no se intenta (fallaría sin decir
     * por qué) y el Taller lo pide.
     */
    var needsLocalNetwork by mutableStateOf(false)
        private set

    fun hasPermission() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Adaptadores emparejados en Ajustes de Android; los que parecen OBD primero. */
    @SuppressLint("MissingPermission")
    fun pairedDevices(): List<ObdTarget.Bluetooth> {
        if (!hasPermission()) return emptyList()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        // Con el Bluetooth apagado algunas pantallas lanzan en vez de dar la lista vacía.
        val bonded = try { adapter.bondedDevices.orEmpty() } catch (_: SecurityException) { emptySet() }
        return bonded.map { ObdTarget.Bluetooth(it.address, it.name ?: it.address) }.sortedByDescending { looksLikeObd(it.name) }
    }

    /** Adaptadores BLE que se encontraron en la última búsqueda (los que parecen OBD primero). */
    var found by mutableStateOf<List<ObdTarget.Ble>>(emptyList())
        private set
    var scanning by mutableStateOf(false)
        private set
    /** Ya se buscó al menos una vez (para decir "no se encontró ninguno"). */
    var searched by mutableStateOf(false)
        private set
    var scanProblem by mutableStateOf<ScanProblem?>(null)
        private set
    private var stopScan: (() -> Unit)? = null

    /** Busca adaptadores Bluetooth LE unos segundos; solo cuando el usuario lo pide (con [canScanBle]). */
    @SuppressLint("MissingPermission")
    fun scanBle() {
        if (scanning || !canScanBle(context)) return
        scanProblem = null
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
            ?: return run { scanProblem = ScanProblem.BLUETOOTH_OFF }
        if (bleLocationOff(context)) return run { scanProblem = ScanProblem.LOCATION_OFF }
        val seen = linkedMapOf<String, ObdTarget.Ble>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                // Sin nombre no hay cómo reconocerlo en la lista.
                val name = result.scanRecord?.deviceName ?: result.device.name ?: return
                if (seen.put(result.device.address, ObdTarget.Ble(result.device.address, name)) == null) {
                    found = seen.values.sortedByDescending { looksLikeObd(it.name) }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                note("búsqueda BLE falló: $errorCode")
                scanProblem = ScanProblem.FAILED
                stopScan?.invoke()
            }
        }
        found = emptyList()
        scanning = true
        val job = scope.launch(Dispatchers.Main) {
            try {
                scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            } catch (x: RuntimeException) {
                // Bluetooth apagado a media búsqueda o sin permiso: se dice "no se pudo buscar".
                note("búsqueda BLE: ${detailOf(x)}")
                scanProblem = ScanProblem.FAILED
                stopScan?.invoke()
                return@launch
            }
            delay(ObdTiming.SCAN_MS)
            stopScan?.invoke()
        }
        stopScan = {
            try { scanner.stopScan(callback) } catch (_: RuntimeException) { /* ya no hay búsqueda que parar */ }
            job.cancel()
            scanning = false
            searched = true
            stopScan = null
        }
    }

    /** Cables USB conectados a la pantalla (cualquier puerto serie; el que parezca OBD primero). */
    fun usbDevices(): List<ObdTarget.Usb> {
        val manager = context.getSystemService(UsbManager::class.java) ?: return emptyList()
        return manager.deviceList.values
            .map { ObdTarget.Usb(it.vendorId, it.productId, it.displayName()) }
            .sortedByDescending { looksLikeObd(it.name) }
    }

    private fun usbDevice(t: ObdTarget.Usb): UsbDevice? =
        context.getSystemService(UsbManager::class.java)?.deviceList?.values
            ?.firstOrNull { it.vendorId == t.vendorId && it.productId == t.productId }

    /**
     * Quien muestra algo del OBD (una pieza, el Taller, Llantas por OBD) dice que lo quiere; con
     * adaptador guardado se conecta solo si no lo está (o si se había rendido). [owner] es cualquier
     * llave estable del que lo pide; se suelta con [release].
     */
    fun want(owner: Any) {
        if (!wanters.add(owner)) return
        if (state == ObdState.OFF || state == ObdState.ERROR && retryJob?.isActive != true) connect()
    }

    /** [owner] ya no lo quiere; sin nadie que lo quiera se desconecta (módulo sin uso = nada corriendo). */
    fun release(owner: Any) {
        if (wanters.remove(owner) && wanters.isEmpty()) disconnect()
    }

    /** El usuario (o quien lo quiere) pide conectarse a [t] o al último que funcionó: racha nueva de reintentos. */
    fun connect(t: ObdTarget? = target) {
        if (t == null || state == ObdState.CONNECTING || state == ObdState.ON && target == t) return
        retries = 0
        attempt(t)
    }

    /** Un intento de conexión (del usuario o un reintento de [retryDelay]). */
    private fun attempt(t: ObdTarget) {
        needsLocalNetwork = t is ObdTarget.Wifi && !localNetworkAllowed(context)
        if (needsLocalNetwork) return
        // También para la búsqueda BLE: buscar y conectar a la vez hace fallar la conexión en varios Android.
        disconnect()
        attempting = t
        state = ObdState.CONNECTING
        errorKind = null
        errorDetail = null
        // Android olvida el permiso del cable al desenchufarlo: si no lo hay, se pide (con su diálogo) y al darlo
        // se sigue. Sin cable, sigue y dice que no está conectado.
        if (t is ObdTarget.Usb) {
            val manager = context.getSystemService(UsbManager::class.java)
            val device = usbDevice(t)
            if (manager != null && device != null && !manager.hasPermission(device)) {
                cancelUsbPermission = requestUsbPermission(context, manager, device, ACTION_USB_PERMISSION, USB_REQUEST) { granted ->
                    cancelUsbPermission = null
                    // Solo si el pedido sigue vigente ([disconnect] lo cancela).
                    if (state != ObdState.CONNECTING || attempting != t) return@requestUsbPermission
                    if (granted) attempt(t) else fail(t, ObdException(ObdError.NO_USB_PERMISSION, "permiso negado"), dropped = false)
                }
                return
            }
        }
        job = scope.launch {
            // El enlace de este intento: se cierra aquí si algo falla (aunque todavía no sea el actual).
            var opened: ElmLink? = null
            try {
                val (l, e, version) = openAndInit(t)
                opened = l
                val pids = lock.withLock { e.supportedPids() }
                ensureActive()
                withContext(Dispatchers.Main) {
                    pending = null
                    link = l
                    elm = e
                    tires = TireSession(e)
                    supported = pids
                    adapter = version
                    target = t
                    if (l is BluetoothLink && t is ObdTarget.Bluetooth) l.used?.let { rememberSocket(t, it) }
                    // Conexión nueva: lo leído en otra ya no vale (el Taller y su pieza vuelven a leer).
                    lastReport = null
                    state = ObdState.ON
                    connectedOnAttempt = retries + 1
                    retries = 0
                    if (t is ObdTarget.Usb) watchUsbDetach(t)
                }
                poll(e)
            } catch (c: CancellationException) {
                opened?.let(::closeQuietly)
                throw c
            } catch (x: Exception) {
                opened?.let(::closeQuietly)
                if (isActive) withContext(Dispatchers.Main + NonCancellable) { fail(t, x, dropped = state == ObdState.ON) }
            }
        }
    }

    /** Falló o se cayó: se dice por qué y [retryDelay] decide si se reintenta. */
    private fun fail(t: ObdTarget, x: Throwable, dropped: Boolean) {
        val kind = classify(x, dropped)
        errorKind = kind
        errorDetail = detailOf(x)
        note("${t.key} ${kind.name.lowercase()}: $errorDetail")
        state = ObdState.ERROR
        pending = null
        link = null
        elm = null
        tires = null
        unwatchUsbDetach()
        bus.clear(Source.OBD)
        val wait = retryDelay(kind, dropped, retries, wanted = wanters.isNotEmpty()) ?: return
        retries++
        retryJob = scope.launch(Dispatchers.Main) {
            delay(wait)
            retryJob = null
            if (state == ObdState.ERROR && attempting == t) attempt(t)
        }
    }

    /**
     * Mientras se lee por cable, si Android avisa que se desenchufó ese cable se corta ya
     * (sin esperar a que dejen de llegar respuestas) y sin reintentos: sin cable no hay a qué.
     */
    private fun watchUsbDetach(t: ObdTarget.Usb) {
        unwatchUsbDetach()
        unwatchDetach = watchUsbDetached(context) { d ->
            if (d.vendorId != t.vendorId || d.productId != t.productId || target != t || state != ObdState.ON) return@watchUsbDetached
            // Cancelar el job antes de cerrar: así su catch no programa reconexiones.
            job?.cancel()
            job = null
            closeLink()
            errorKind = ObdError.USB_UNPLUGGED
            errorDetail = "desenchufado"
            state = ObdState.ERROR
        }
    }

    private fun unwatchUsbDetach() {
        unwatchDetach?.invoke()
        unwatchDetach = null
    }

    /** Corta todo de inmediato: la conexión (también la que va a medias), los reintentos, la búsqueda y el permiso USB. */
    fun disconnect() {
        job?.cancel()
        job = null
        retryJob?.cancel()
        retryJob = null
        cancelUsbPermission?.invoke()
        cancelUsbPermission = null
        stopScan?.invoke()
        // Cerrar el enlace a medias suelta su conexión bloqueada (Bluetooth clásico incluido): no quedan dos.
        pending?.let(::closeQuietly)
        pending = null
        closeLink()
        state = ObdState.OFF
    }

    /** Olvida el adaptador guardado (para elegir otro). */
    fun forget() {
        disconnect()
        // Sin adaptador guardado (se borra su archivo con la receta de llantas y el socket recordado): otro auto empieza de cero.
        target = null
        attempting = null
        tireProfile = null
        needsLocalNetwork = false
    }

    /** El último reporte del motor que se leyó (la pieza Taller y Salud lo muestran sin volver a leer). */
    var lastReport by mutableStateOf<EngineReport?>(null)
        private set

    /**
     * Lee todo lo del motor de una vez para el Taller. Si ya se está leyendo (el Taller y la pieza
     * Salud piden a la vez al conectar), espera esa misma lectura: el auto no contesta dos veces.
     */
    suspend fun report(): EngineReport? {
        val running = reading?.takeIf { it.isActive }
            ?: scope.async { readReport().also { if (it != null) lastReport = it } }.also { reading = it }
        return running.await()
    }
    private var reading: Deferred<EngineReport?>? = null

    private suspend fun readReport(): EngineReport? = withElm("reporte") { e ->
        EngineReport(
            readiness = e.pid(0x01)?.let(::parseReadiness),
            stored = e.troubleCodes("03"),
            pending = e.troubleCodes("07"),
            // Lo que no todos los autos contestan: si no, se sigue sin eso.
            permanent = optional("0A") { e.troubleCodes("0A") } ?: emptyList(),
            vin = optional("VIN") { e.vin() },
            voltage = optional("ATRV") { e.voltage() },
        )
    }

    /** [read] puede no contestar (el auto no lo da): se anota y se sigue; si se perdió el adaptador, no. */
    private fun <T> optional(what: String, read: () -> T?): T? = try {
        read()
    } catch (x: ObdException) {
        if (x.kind != ObdError.ADAPTER_SILENT) throw x
        note("$what sin respuesta")
        null
    }

    /** La receta de llantas que dio presiones con este auto (se guarda: la próxima vez se pregunta directo). */
    private var tireProfile = prefs.getString(KEY_TIRE_PROFILE, null)

    /**
     * La presión de fábrica ([FactoryTires]): la receta de esta conexión o la guardada; si no, se
     * prueban todas (las de la marca del VIN primero), cada petición con el candado por separado para
     * no detener los medidores. Si ninguna contesta (el módulo de llantas puede tardar en despertar tras
     * dar contacto) se vuelve a buscar cada [ObdTiming.TIRE_SEARCH_GAP_MS], hasta [ObdTiming.TIRE_SEARCHES]
     * veces por conexión. Una receta se guarda hasta que da una presión creíble (Ford y Mazda comparten módulo).
     */
    suspend fun factoryTires(): FactoryTires {
        val s = tires?.takeIf { state == ObdState.ON } ?: return FactoryTires.NotConnected
        val can = s.can ?: (withElm("ATDPN", s.elm) { it.command("ATDPN").firstOrNull()?.removePrefix("A") in CAN_11_BIT } ?: return FactoryTires.NotConnected)
            .also { s.can = it }
        if (!can) return FactoryTires.Unsupported
        val known = s.candidate ?: TireProfiles.firstOrNull { it.id == tireProfile }
        if (known != null) readTires(s, known)?.let { return keep(s, known, it) }
        val now = SystemClock.elapsedRealtime()
        if (s.searches >= ObdTiming.TIRE_SEARCHES || s.searches > 0 && now - s.lastSearch < ObdTiming.TIRE_SEARCH_GAP_MS) {
            return FactoryTires.Unsupported
        }
        s.searches++
        s.lastSearch = now
        val vin = lastReport?.vin ?: withElm("VIN", s.elm) { optional("VIN") { it.vin() } }
        for (p in ordered(vin)) {
            if (tires !== s) return FactoryTires.NotConnected
            if (p === known) continue
            val found = readTires(s, p) ?: continue
            return keep(s, p, found)
        }
        // Si se cayó a media búsqueda no cuenta: la conexión nueva empieza de cero.
        return if (tires !== s) FactoryTires.NotConnected else FactoryTires.Unsupported
    }

    /** [p] contestó: se usa en esta conexión y se guarda en cuanto da una presión creíble. */
    private fun keep(s: TireSession, p: TireProfile, v: FactoryTires.Values): FactoryTires.Values {
        s.candidate = p
        if (tireProfile != p.id && v.kpa.any { it != null && it in CREDIBLE_KPA }) {
            tireProfile = p.id
            prefs.edit().putString(KEY_TIRE_PROFILE, p.id).apply()
        }
        return v
    }

    /**
     * Una receta en la conexión [s] (null si ya es otra o no contestó): es de este auto si su módulo
     * contestó que sí a todas sus peticiones (aunque los sensores aún no digan nada: parado y recién
     * encendido regresan 0 o FF).
     */
    private suspend fun readTires(s: TireSession, p: TireProfile): FactoryTires.Values? {
        val replies = withElm(p.id, s.elm) { it.module(p.setup, p.requests, RESTORE_ENGINE) } ?: return null
        if (replies.size < p.requests.size || replies.any { it == null }) return null
        return p.decode(replies.filterNotNull()).copy(profile = p.id)
    }

    /** Borra los códigos (el Taller pide confirmar antes con una alerta). */
    suspend fun clearCodes(): Boolean = withElm("borrar códigos") { it.clearCodes() } ?: false

    /** Para los reportes: estado, adaptador, protocolo, qué datos da el auto, su primera respuesta y los últimos fallos. */
    suspend fun describe(): String {
        val protocol = withElm("ATDPN") { it.command("ATDPN").firstOrNull() } ?: "-"
        val pids = supported.sorted().joinToString(" ") { "%02X".format(it) }
        val error = errorKind?.let { "${it.name.lowercase()} ($errorDetail)" } ?: "-"
        val log = synchronized(events) { events.joinToString(" | ") }.ifEmpty { "-" }
        return "OBD: estado=${state.name.lowercase()}, por=${target?.key ?: "-"}, adaptador=${adapter ?: "-"}, " +
            "protocolo=$protocol, datos=${supported.size} [$pids], 0100=${elm?.firstReply?.joinToString("|") ?: "-"}, " +
            "error=$error, conectó en el intento=$connectedOnAttempt, reintentos=$retries, " +
            "socket=${(target as? ObdTarget.Bluetooth)?.let(::rememberedSocket)?.key ?: "-"}, eventos=$log"
    }

    /**
     * [block] con el adaptador (el de ahora o [e], si sigue siendo el de ahora) y el candado. Un fallo
     * aquí no corta la conexión (eso lo nota la lectura del motor): se anota para el reporte y da null.
     */
    private suspend fun <T> withElm(what: String, e: Elm327? = elm, block: (Elm327) -> T): T? {
        e ?: return null
        return withContext(Dispatchers.IO) {
            try {
                lock.withLock { if (elm === e) block(e) else null }
            } catch (x: IOException) {
                note("$what: ${detailOf(x)}")
                null
            }
        }
    }

    /**
     * ¿Se puede mostrar [signal] con este auto? Las calculadas, si da lo que necesitan;
     * el voltaje siempre (si no lo da el auto, lo mide el adaptador).
     */
    fun canRead(signal: String): Boolean {
        if (signal == Signal.VOLTAGE) return true
        DerivedSignals.firstOrNull { it.signal == signal }?.let { d ->
            return d.needs.all(::canRead) && (d.anyOf.isEmpty() || d.anyOf.any(::canRead))
        }
        return Pids.any { it.signal == signal && it.code in supported }
    }

    /**
     * Lee lo que se está mostrando mientras dure la conexión. El plan (qué PIDs) se arma solo cuando
     * cambia lo que se muestra; sin nada que leer, espera ese cambio sin dar vueltas.
     */
    private suspend fun poll(e: Elm327) {
        val reader = Reader(e)
        snapshotFlow { bus.wanted }.distinctUntilChanged().collectLatest { shown -> reader.read(shown) }
    }

    private inner class Reader(private val e: Elm327) {
        private var lastSlow: Long? = null
        // Consultas seguidas que no contestó: una suelta pasa (adaptador lento, el auto
        // ocupado); varias seguidas sí es que se perdió la conexión.
        private var failures = 0
        // Lo último que se leyó de cada señal (para calcular las que dependen de varias).
        private val last = mutableMapOf<String, Float>()
        private val smoothed = mutableMapOf<String, Float>()

        private fun <T> ask(read: () -> T?): T? = try {
            read().also { failures = 0 }
        } catch (x: IOException) {
            if (++failures >= ObdTiming.MAX_FAILURES) throw x
            null
        }

        suspend fun read(shown: Set<String>) {
            val derived = DerivedSignals.filter { it.signal in shown }
            // De anyOf solo el primero que dé el auto: compute ignora los demás si está ese.
            val wanted = shown + derived.flatMap { d -> d.needs + listOfNotNull(d.anyOf.firstOrNull(::canRead)) + d.extras }
            val pids = Pids.filter { it.signal in wanted && it.code in supported }
            // Sin el PID 42 el voltaje lo mide el adaptador (ATRV), con lo lento.
            val adapterVoltage = Signal.VOLTAGE in wanted && VOLTAGE_PID !in supported
            // Lo que dejó de mostrarse no suaviza con un valor viejo al volver.
            smoothed.keys.retainAll(derived.map { it.signal }.toSet())
            if (pids.isEmpty() && !adapterVoltage) return
            while (true) {
                currentCoroutineContext().ensureActive()
                val now = SystemClock.elapsedRealtime()
                val slow = lastSlow.let { it == null || now - it >= ObdTiming.SLOW_EVERY_MS }
                if (slow) lastSlow = now
                // Se leen todos y se publican juntos: un solo salto al hilo principal por vuelta.
                val values = mutableListOf<Pair<String, Float?>>()
                for (p in pids) if (p.fast || slow) values += p.signal to lock.withLock { ask { e.pid(p.code) } }?.let(p.decode)
                // Sin envolver: si el adaptador se perdió, también cuenta como falla (y se nota la caída).
                if (adapterVoltage && slow) values += Signal.VOLTAGE to lock.withLock { ask { e.voltage() } }
                for ((signal, value) in values) if (value == null) last.remove(signal) else last[signal] = value
                for (d in derived) {
                    val raw = d.compute(last)
                    val prev = smoothed[d.signal]
                    val value = if (raw == null || prev == null) raw else prev + (raw - prev) * d.smoothing
                    if (value == null) smoothed.remove(d.signal) else smoothed[d.signal] = value
                    values += d.signal to value
                }
                withContext(Dispatchers.Main) { for ((signal, value) in values) bus.publish(signal, value, Source.OBD) }
                delay(ObdTiming.POLL_GAP_MS)
            }
        }
    }

    /** Abre el enlace (cancelable desde [disconnect]) y prepara el adaptador. */
    @SuppressLint("MissingPermission") // device() revisa BLUETOOTH_CONNECT
    private suspend fun openAndInit(t: ObdTarget): Triple<ElmLink, Elm327, String> {
        val l = when (t) {
            is ObdTarget.Wifi -> WifiLink(t.host, t.port)
            is ObdTarget.Bluetooth -> BluetoothLink(context, device(t.address), rememberedSocket(t))
            is ObdTarget.Ble -> {
                delay(ObdTiming.BLE_SETTLE_MS)
                BleLink(context, device(t.address))
            }
            is ObdTarget.Usb -> return openUsb(t)
        }
        return prepare(l) { it.open() }
    }

    /**
     * [l] queda como el pendiente (así [disconnect] lo corta aunque esté bloqueado), se abre con [open] y
     * se prepara el ELM327; si algo falla, se cierra.
     */
    private suspend fun prepare(l: ElmLink, open: (ElmLink) -> Unit): Triple<ElmLink, Elm327, String> {
        pending = l
        try {
            // Si [disconnect] llegó antes de guardarlo, ya no lo vio: se corta aquí.
            currentCoroutineContext().ensureActive()
            open(l)
            val e = Elm327(l, ::note)
            return Triple(l, e, lock.withLock { e.init() })
        } catch (x: Throwable) {
            closeQuietly(l)
            throw x
        }
    }

    /**
     * Por USB no se sabe a qué velocidad habla el cable: se prueban las comunes
     * ([USB_BAUDS]) hasta que el ELM327 conteste.
     */
    private suspend fun openUsb(t: ObdTarget.Usb): Triple<ElmLink, Elm327, String> {
        val manager = context.getSystemService(UsbManager::class.java) ?: throw ObdException(ObdError.NO_USB, "sin UsbManager")
        val device = usbDevice(t) ?: throw ObdException(ObdError.USB_UNPLUGGED, "no está en la lista")
        var last: ObdException? = null
        for (baud in USB_BAUDS) {
            try {
                return prepare(UsbLink(manager, device, baud)) {}
            } catch (x: ObdException) {
                // Sin permiso o sin ser puerto serie, otra velocidad no lo arregla; y si el ELM ya contestó
                // (el auto no), la velocidad era la buena.
                if (!x.kind.retry || x.kind == ObdError.CAR_SILENT) throw x
                last = x
                note("usb $baud: ${x.message}")
            }
        }
        throw last ?: ObdException(ObdError.USB_SILENT)
    }

    private fun device(address: String): BluetoothDevice {
        if (!hasPermission()) throw ObdException(ObdError.NO_BLUETOOTH_PERMISSION, "sin BLUETOOTH_CONNECT")
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw ObdException(ObdError.BLUETOOTH_OFF, "sin adaptador de Bluetooth")
        return try {
            adapter.getRemoteDevice(address)
        } catch (x: IllegalArgumentException) {
            throw ObdException(ObdError.UNKNOWN, "dirección inválida", x)
        }
    }

    /** La forma de socket que conectó con este adaptador de Bluetooth clásico (se prueba primero). */
    private fun rememberedSocket(t: ObdTarget.Bluetooth): BtSocket? {
        val saved = prefs.getString(KEY_BT_SOCKET, null) ?: return null
        return BtSocket.of(saved.substringAfter(' ')).takeIf { saved.substringBefore(' ') == t.address }
    }

    private fun rememberSocket(t: ObdTarget.Bluetooth, used: BtSocket) {
        prefs.edit().putString(KEY_BT_SOCKET, "${t.address} ${used.key}").apply()
    }

    private fun closeLink() {
        unwatchUsbDetach()
        link?.let(::closeQuietly)
        link = null
        elm = null
        tires = null
        scope.launch(Dispatchers.Main) { bus.clear(Source.OBD) }
    }

    /** Los últimos fallos que no cortan (para el reporte). */
    private val events = ArrayDeque<String>()

    private fun note(text: String) = synchronized(events) {
        if (events.size >= MAX_EVENTS) events.removeFirst()
        events.addLast(text)
    }

    private fun looksLikeObd(name: String) = OBD_NAMES.containsMatchIn(name)

    companion object {
        /** Archivo de ajustes (la app lo escucha para ofrecer LiteDash al conectar el cable). */
        const val PREFS = "obd"
        private const val KEY_TYPE = "type"
        private const val KEY_BT_SOCKET = "bt_socket"
        private const val ACTION_USB_PERMISSION = "com.irvingguerra.litedash.USB_PERMISSION"
        private const val USB_REQUEST = 0
        /** Voltaje del módulo de control (el de la batería con el motor andando). */
        private const val VOLTAGE_PID = 0x42
        private const val KEY_TIRE_PROFILE = "tire_profile"
        private const val MAX_EVENTS = 12
        /** Una presión de llanta creíble (kPa): con eso se guarda la receta. */
        private val CREDIBLE_KPA = 100f..450f
        /** "ATDPN": CAN de 11 bits a 500 o 250 kbps. */
        private val CAN_11_BIT = setOf("6", "8")

        /** Hay un adaptador guardado (sin crear el escáner ni tocar Bluetooth). */
        fun hasSavedTarget(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY_TYPE)

        /** El adaptador guardado es un cable USB. */
        fun hasUsbTarget(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TYPE, null) == "usb"
    }
}
