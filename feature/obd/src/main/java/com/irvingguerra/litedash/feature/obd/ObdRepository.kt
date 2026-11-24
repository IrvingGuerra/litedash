package com.irvingguerra.litedash.feature.obd

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import com.irvingguerra.litedash.core.vehicle.watchUsbAttached
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
 * La política de conexión vive toda aquí: en cuanto algo lo pide ([keep]) conecta al adaptador guardado y
 * **se queda conectado** aunque ya no se vea nada suyo (cortar y reabrir el Bluetooth hacía fallar a los
 * ELM327 baratos, issue #23); solo lo corta el usuario o apagar el módulo ([disconnect]). Tras un fallo,
 * [retryDelay] decide cuándo reintenta: mientras se mantiene no se rinde (con esperas crecientes), y al
 * prender el Bluetooth de la pantalla conecta de inmediato.
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
    /**
     * Conectado pero el auto no da ningún dato desde hace [ObdTiming.CAR_SILENT_MS] (contacto apagado: el conector
     * OBD tiene corriente siempre y el ELM contesta "NO DATA"). El enlace NO se corta; quien lo use (el registro de
     * viajes) puede tomar el motor como apagado. Vuelve a false con el primer dato.
     */
    var carSilent by mutableStateOf(false)
        private set

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var link: ElmLink? = null
    /** El enlace que se está abriendo o preparando: [drop] lo cierra desde aquí (lo suelta aunque esté bloqueado). */
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
    /** Se mantiene conectado ([keep]) hasta que el usuario lo corte o apague el módulo ([disconnect]). */
    private var kept = false
    /** Dejar de escuchar el encendido del Bluetooth de la pantalla ([watchBluetooth]). */
    private var unwatchBluetooth: (() -> Unit)? = null
    /** Dejar de escuchar que se enchufe el cable elegido ([watchUsbAttach]). */
    private var unwatchAttach: (() -> Unit)? = null
    /** Cuándo se cerró el último enlace (reloj de arranque): Bluetooth necesita un respiro antes de reabrir. */
    @Volatile private var closedAt = 0L
    /** Dejar de escuchar el permiso USB pedido / el desconectado del cable. */
    private var cancelUsbPermission: (() -> Unit)? = null
    private var unwatchDetach: (() -> Unit)? = null

    // Se lee del disco una vez; como estado de Compose, el Taller se entera al cambiarlo.
    private var savedTarget by mutableStateOf(readTarget())

    var target: ObdTarget?
        get() = savedTarget
        private set(t) {
            val changed = t != savedTarget
            savedTarget = t
            prefs.edit().apply {
                if (t == null) clear() else putString(KEY_TYPE, t.key)
                // Otro adaptador: el cable que contestó antes ya no cuenta.
                if (changed) remove(KEY_USB_DEVICE)
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
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        var retry: Job? = null
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
                // La pila de Bluetooth se atoró al registrar la búsqueda (NWD T3, 8227L): parar, esperar y otra vez, una sola.
                if (retryScan(errorCode, retried = retry != null)) {
                    val self = this
                    try { scanner.stopScan(self) } catch (_: RuntimeException) { /* no había búsqueda */ }
                    retry = scope.launch(Dispatchers.Main) {
                        delay(ObdTiming.SCAN_RETRY_MS)
                        start(scanner, settings, self)
                    }
                    return
                }
                scanProblem = ScanProblem.FAILED
                stopScan?.invoke()
            }
        }
        found = emptyList()
        scanning = true
        val job = scope.launch(Dispatchers.Main) {
            if (!start(scanner, settings, callback)) return@launch
            delay(ObdTiming.SCAN_MS)
            stopScan?.invoke()
        }
        stopScan = {
            try { scanner.stopScan(callback) } catch (_: RuntimeException) { /* ya no hay búsqueda que parar */ }
            job.cancel()
            retry?.cancel()
            scanning = false
            searched = true
            stopScan = null
        }
    }

    /** Arranca la búsqueda; si truena (Bluetooth apagado a media búsqueda o sin permiso) se dice "no se pudo buscar". */
    @SuppressLint("MissingPermission")
    private fun start(scanner: BluetoothLeScanner, settings: ScanSettings, callback: ScanCallback): Boolean = try {
        scanner.startScan(null, settings, callback)
        true
    } catch (x: RuntimeException) {
        note("búsqueda BLE: ${detailOf(x)}")
        scanProblem = ScanProblem.FAILED
        stopScan?.invoke()
        false
    }

    /** Cables USB conectados a la pantalla (cualquier puerto serie; el que parezca OBD primero). */
    fun usbDevices(): List<ObdTarget.Usb> {
        val manager = context.getSystemService(UsbManager::class.java) ?: return emptyList()
        return manager.deviceList.values
            .map { ObdTarget.Usb(it.vendorId, it.productId, it.displayName()) }
            .sortedByDescending { looksLikeObd(it.name) }
    }

    /**
     * Los cables enchufados que pueden ser [t] (mismo fabricante y producto), sin el que ya tiene abierto otro módulo
     * ni el que Llantas reconoció como su receptor (suele traer el mismo chip): abrirlo se lo quitaría. El último que
     * contestó como ELM327 va primero.
     */
    private fun usbCandidates(t: ObdTarget.Usb): List<UsbDevice> {
        val last = prefs.getString(KEY_USB_DEVICE, null)
        return context.getSystemService(UsbManager::class.java)?.deviceList?.values
            ?.filter { it.vendorId == t.vendorId && it.productId == t.productId && !UsbLink.inUse(it) && !UsbLink.isReceiver(it) }
            ?.sortedByDescending { it.deviceName == last }
            .orEmpty()
    }

    private fun usbDevice(t: ObdTarget.Usb): UsbDevice? = usbCandidates(t).firstOrNull()

    /**
     * Algo del OBD se usa (una pieza, el Taller, Llantas por OBD): con adaptador guardado se conecta solo si no
     * lo está (o si se había rendido) y desde aquí se mantiene conectado. Llamarlo de más no repite nada. Tras un
     * error que pide algo del usuario ([keepReconnects]: el permiso del cable negado…) no vuelve a intentar solo.
     */
    fun keep() {
        kept = true
        if (state == ObdState.OFF) return connect()
        if (state != ObdState.ERROR || retryJob?.isActive == true) return
        val kind = errorKind
        if (kind != ObdError.USB_UNPLUGGED && kind != ObdError.NO_USB_PERMISSION) {
            // Los demás no dependen del cable: sin preguntarle nada a UsbManager.
            if (keepReconnects(kind, hasPermission(), cablePresent = false, cableAllowed = false)) connect()
            return
        }
        // Preguntar por el cable es una llamada entre procesos: fuera del hilo principal.
        scope.launch {
            val d = cable()
            val allowed = cableAllowed(d)
            withContext(Dispatchers.Main) {
                val still = state == ObdState.ERROR && retryJob?.isActive != true && errorKind == kind
                if (still && keepReconnects(kind, hasPermission(), d != null, allowed)) connect()
            }
        }
    }

    /** El cable del adaptador guardado (o del que se intentó), si está enchufado. */
    private fun cable(): UsbDevice? = (attempting ?: target).let { if (it is ObdTarget.Usb) usbDevice(it) else null }

    /** Android ya dio permiso para ese cable (al negarlo se pide de nuevo solo con «Conectar»). */
    private fun cableAllowed(d: UsbDevice? = cable()): Boolean =
        d != null && context.getSystemService(UsbManager::class.java)?.hasPermission(d) == true

    /**
     * LiteDash volvió a la vista: si esperaba un reintento largo (el adaptador dormido), intenta ya.
     */
    fun wake() {
        val t = attempting ?: target
        if (t == null || !kept || state != ObdState.ERROR) return
        if (errorKind?.retry != true) return
        retryJob?.cancel()
        retryJob = null
        // Sin reiniciar la cuenta: si sigue sin contestar, la espera sigue creciendo.
        attempt(t)
    }

    /** El usuario (o [keep]) pide conectarse a [t] o al último que funcionó: racha nueva de reintentos, y se mantiene. */
    fun connect(t: ObdTarget? = target) {
        if (t == null || state == ObdState.CONNECTING || state == ObdState.ON && target == t) return
        kept = true
        retries = 0
        attempt(t)
    }

    /** Un intento de conexión (del usuario o un reintento de [retryDelay]). */
    private fun attempt(t: ObdTarget) {
        needsLocalNetwork = t is ObdTarget.Wifi && !localNetworkAllowed(context)
        if (needsLocalNetwork) return
        // También para la búsqueda BLE: buscar y conectar a la vez hace fallar la conexión en varios Android.
        drop()
        attempting = t
        state = ObdState.CONNECTING
        errorKind = null
        errorDetail = null
        // Por Bluetooth: al prender el de la pantalla (hay pantallas que tardan tras arrancar) conecta de inmediato.
        if (t is ObdTarget.Bluetooth || t is ObdTarget.Ble) watchBluetooth() else stopWatchingBluetooth()
        // Por cable: al volver a enchufarlo (o si la pantalla lo vuelve a ver al despertar) conecta solo.
        if (t is ObdTarget.Usb) watchUsbAttach(t) else stopWatchingUsbAttach()
        // Android olvida el permiso del cable al desenchufarlo: si no lo hay, se pide (con su diálogo) y al darlo
        // se sigue. Sin cable, sigue y dice que no está conectado.
        if (t is ObdTarget.Usb) {
            val manager = context.getSystemService(UsbManager::class.java)
            val device = usbDevice(t)
            if (manager != null && device != null && !manager.hasPermission(device)) {
                cancelUsbPermission = requestUsbPermission(context, manager, device, ACTION_USB_PERMISSION, USB_REQUEST) { granted ->
                    cancelUsbPermission = null
                    // Solo si el pedido sigue vigente ([drop] lo cancela).
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
                // Reabrir Bluetooth justo después de cerrarlo falla seguido ("read failed", GATT 133): un respiro.
                if (t is ObdTarget.Bluetooth || t is ObdTarget.Ble) {
                    val since = SystemClock.elapsedRealtime() - closedAt
                    if (since < ObdTiming.REOPEN_GAP_MS) delay(ObdTiming.REOPEN_GAP_MS - since)
                }
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
                    if (l is UsbLink) rememberCable(l.deviceName)
                    // Conexión nueva: lo leído en otra ya no vale (el Taller y su pieza vuelven a leer).
                    lastReport = null
                    carSilent = false
                    state = ObdState.ON
                    connectedOnAttempt = retries + 1
                    note("conectó ${t.key} en el intento $connectedOnAttempt" + ((l as? BluetoothLink)?.used?.let { " socket=$it" } ?: ""))
                    retries = 0
                    if (t is ObdTarget.Usb) watchUsbDetach(t)
                }
                poll(e)
            } catch (c: CancellationException) {
                opened?.let(::closeQuietly)
                throw c
            } catch (x: Exception) {
                // También cuenta para el respiro antes de reabrir (fail no pasa por closeLink).
                closedAt = SystemClock.elapsedRealtime()
                opened?.let(::closeQuietly)
                if (isActive) withContext(Dispatchers.Main + NonCancellable) { fail(t, x, dropped = state == ObdState.ON) }
            }
        }
    }

    /** Falló o se cayó: se dice por qué y [retryDelay] decide si se reintenta. */
    private fun fail(t: ObdTarget, x: Throwable, dropped: Boolean) {
        var kind = classify(x, dropped)
        // Por Bluetooth se dice lo que pasa de verdad: con el de la pantalla apagado, eso (y no "no se alcanzó el
        // adaptador"); y un "apagado" con el Bluetooth prendido es la pila ocupada (connectGatt nulo): se reintenta.
        if (!dropped && (t is ObdTarget.Bluetooth || t is ObdTarget.Ble)) {
            val on = bluetoothOn()
            if (!on && kind.retry) kind = ObdError.BLUETOOTH_OFF else if (on && kind == ObdError.BLUETOOTH_OFF) kind = ObdError.GATT_BUSY
        }
        errorKind = kind
        errorDetail = detailOf(x)
        note("${t.key} ${kind.name.lowercase()}: $errorDetail")
        state = ObdState.ERROR
        carSilent = false
        pending = null
        link = null
        elm = null
        tires = null
        unwatchUsbDetach()
        bus.clear(Source.OBD)
        val wait = retryDelay(kind, dropped, retries, kept) ?: return
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
            carSilent = false
        }
    }

    /**
     * Mientras se mantiene un adaptador por cable: si se vuelve a enchufar ese cable (mismo fabricante y producto) y
     * está en error ([replugReconnects]: tras negar el permiso, solo si ya se dio), conecta tras un respiro.
     */
    private fun watchUsbAttach(t: ObdTarget.Usb) {
        stopWatchingUsbAttach()
        unwatchAttach = watchUsbAttached(context) { d ->
            if (d.vendorId != t.vendorId || d.productId != t.productId) return@watchUsbAttached
            if (!kept || state != ObdState.ERROR || attempting != t || !replugReconnects(errorKind, cableAllowed(d))) return@watchUsbAttached
            note("cable enchufado")
            retryJob?.cancel()
            retryJob = scope.launch(Dispatchers.Main) {
                delay(ObdTiming.USB_ATTACH_SETTLE_MS)
                retryJob = null
                if (kept && state == ObdState.ERROR && attempting == t) attempt(t)
            }
        }
    }

    private fun stopWatchingUsbAttach() {
        unwatchAttach?.invoke()
        unwatchAttach = null
    }

    private fun unwatchUsbDetach() {
        unwatchDetach?.invoke()
        unwatchDetach = null
    }

    /** El usuario lo corta (o apaga el módulo): ya no se mantiene conectado hasta que algo lo vuelva a pedir. */
    fun disconnect() {
        kept = false
        stopWatchingBluetooth()
        stopWatchingUsbAttach()
        drop()
    }

    private fun stopWatchingBluetooth() {
        unwatchBluetooth?.invoke()
        unwatchBluetooth = null
    }

    /**
     * Mientras se mantiene un adaptador Bluetooth: cuando el Bluetooth de la pantalla termina de prender (hay
     * pantallas que lo prenden un rato después de arrancar) se conecta, tras un respiro para que quede listo.
     */
    private fun watchBluetooth() {
        if (unwatchBluetooth != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) != BluetoothAdapter.STATE_ON) return
                if (!kept || state != ObdState.ERROR || errorKind != ObdError.BLUETOOTH_OFF) return
                note("bluetooth prendió")
                retryJob?.cancel()
                retryJob = scope.launch(Dispatchers.Main) {
                    delay(ObdTiming.BLUETOOTH_ON_SETTLE_MS)
                    retryJob = null
                    val t = attempting
                    if (t != null && kept && state == ObdState.ERROR) attempt(t)
                }
            }
        }
        // Es un aviso del sistema (protegido: otra app no lo puede imitar); exportado para recibirlo en cualquier pantalla.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        }
        unwatchBluetooth = { context.unregisterReceiver(receiver) }
    }

    /** El Bluetooth de Android de la pantalla está encendido (sin él no hay ni clásico ni LE). */
    private fun bluetoothOn(): Boolean =
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

    /** Corta todo de inmediato: la conexión (también la que va a medias), los reintentos, la búsqueda y el permiso USB. */
    private fun drop() {
        job?.cancel()
        job = null
        retryJob?.cancel()
        retryJob = null
        cancelUsbPermission?.invoke()
        cancelUsbPermission = null
        stopScan?.invoke()
        // Cerrar el enlace a medias suelta su conexión bloqueada (Bluetooth clásico incluido): no quedan dos.
        pending?.let {
            closedAt = SystemClock.elapsedRealtime()
            closeQuietly(it)
        }
        pending = null
        closeLink()
        state = ObdState.OFF
        carSilent = false
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
            permanent = optional("0A", e) { e.troubleCodes("0A") } ?: emptyList(),
            vin = optional("VIN", e) { e.vin() },
            voltage = optional("ATRV", e) { e.voltage() },
        )
    }

    /**
     * [read] puede no contestar (el auto no lo da): se anota y se sigue, tras resincronizar [e] para que lo siguiente
     * no lea su respuesta tardía; si se perdió el adaptador, no.
     */
    private fun <T> optional(what: String, e: Elm327, read: () -> T?): T? = try {
        read()
    } catch (x: ObdException) {
        if (x.kind != ObdError.ADAPTER_SILENT) throw x
        note("$what sin respuesta")
        e.resync()
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
        if (state == ObdState.CONNECTING) return FactoryTires.Waiting
        val s = tires?.takeIf { state == ObdState.ON } ?: return FactoryTires.NotConnected
        val can = s.can ?: (withElm("ATDPN", s.elm) { it.command("ATDPN").firstOrNull()?.removePrefix("A") in CAN_11_BIT } ?: return FactoryTires.NotConnected)
            .also { s.can = it }
        if (!can) return FactoryTires.Unsupported
        val known = s.candidate ?: TireProfiles.firstOrNull { it.id == tireProfile }
        if (known != null) readTires(s, known)?.let { return keep(s, known, it) }
        val now = SystemClock.elapsedRealtime()
        if (s.searches >= ObdTiming.TIRE_SEARCHES) return FactoryTires.Unsupported
        // Entre búsquedas aún no se sabe: el módulo de llantas puede despertar al rato.
        if (s.searches > 0 && now - s.lastSearch < ObdTiming.TIRE_SEARCH_GAP_MS) return FactoryTires.Waiting
        s.searches++
        s.lastSearch = now
        val vin = lastReport?.vin ?: withElm("VIN", s.elm) { optional("VIN", it) { it.vin() } }
        for (p in ordered(vin)) {
            if (tires !== s) return FactoryTires.NotConnected
            if (p === known) continue
            val found = readTires(s, p) ?: continue
            return keep(s, p, found)
        }
        // Si se cayó a media búsqueda no cuenta: la conexión nueva empieza de cero.
        return when {
            tires !== s -> FactoryTires.NotConnected
            s.searches < ObdTiming.TIRE_SEARCHES -> FactoryTires.Waiting
            else -> FactoryTires.Unsupported
        }
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
            "resincronizó=${elm?.resyncs ?: 0}, sin datos del auto=${if (carSilent) "sí" else "no"}, " +
            "error=$error, conectó en el intento=$connectedOnAttempt, reintentos=$retries, se mantiene=${if (kept) "sí" else "no"}, " +
            "bluetooth de Android=${context.getSystemService(BluetoothManager::class.java)?.adapter?.state ?: "no hay"} (12 = encendido), " +
            "socket=${(target as? ObdTarget.Bluetooth)?.let(::rememberedSocket)?.key ?: "-"}, eventos=$log"
    }

    /**
     * [block] con el adaptador (el de ahora o [e], si sigue siendo el de ahora) y el candado. Un fallo
     * aquí no corta la conexión (eso lo nota la lectura del motor): se anota para el reporte y da null. Si
     * expiró, se resincroniza antes de soltar el candado: si no, los medidores leerían su respuesta tardía.
     */
    private suspend fun <T> withElm(what: String, e: Elm327? = elm, block: (Elm327) -> T): T? {
        e ?: return null
        return withContext(Dispatchers.IO) {
            try {
                lock.withLock {
                    if (elm !== e) return@withLock null
                    try {
                        block(e)
                    } catch (x: ObdException) {
                        if (x.kind == ObdError.ADAPTER_SILENT) runCatching { e.resync() }
                        throw x
                    }
                }
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
        private val guard = ReadGuard(e)
        // Lo último que se leyó de cada señal (para calcular las que dependen de varias).
        private val last = mutableMapOf<String, Float>()
        private val smoothed = mutableMapOf<String, Float>()
        /** Cuánto lleva el auto sin dar datos (reloj de arranque): da [carSilent]. */
        private val silence = CarSilence(SystemClock.elapsedRealtime())

        /** Veces seguidas que ATRV no contestó: a la segunda se deja de pedir en esta conexión (cada una espera 3 s). */
        private var voltageSilent = 0

        /** ATRV que no contesta: sin valor y sin contar falla (ni anotarlo cada 2 s); si se perdió el adaptador, sí corta. */
        private fun optionalVoltage(e: Elm327): Float? {
            if (voltageSilent >= 2) return null
            return try {
                e.voltage().also { voltageSilent = 0 }
            } catch (x: ObdException) {
                if (x.kind != ObdError.ADAPTER_SILENT) throw x
                if (++voltageSilent == 2) note("ATRV sin respuesta: se deja de pedir")
                // Sin su ">" la entrada puede quedar desfasada: que el siguiente PID no lea lo de ATRV.
                e.resync()
                null
            }
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
            // Sin PIDs no se le pregunta nada al auto: no se sabe si está apagado (el Taller no lo dice con algo viejo).
            if (pids.isEmpty()) {
                silence.unknown()
                withContext(Dispatchers.Main) { carSilent = false }
            }
            if (pids.isEmpty() && !adapterVoltage) return
            silence.restart(SystemClock.elapsedRealtime())
            while (true) {
                currentCoroutineContext().ensureActive()
                val now = SystemClock.elapsedRealtime()
                val slow = lastSlow.let { it == null || now - it >= ObdTiming.SLOW_EVERY_MS }
                if (slow) lastSlow = now
                // Se leen todos y se publican juntos: un solo salto al hilo principal por vuelta.
                val values = mutableListOf<Pair<String, Float?>>()
                for (p in pids) if (p.fast || slow) values += p.signal to lock.withLock { guard.ask { e.pid(p.code) } }?.let(p.decode)
                // Solo cuenta lo que contesta el auto (no el voltaje del adaptador, que da aunque el contacto esté apagado).
                if (values.isNotEmpty() && silence.lap(now, values.any { it.second != null })) note("sin datos del auto")
                // Si el voltaje es lo único que se lee, su falla cuenta (así se nota la caída). Con PIDs, no: hay clones que
                // no contestan ATRV y sumaban fallas hasta tirar la conexión; si el adaptador se perdió, los PIDs lo dicen.
                if (adapterVoltage && slow) {
                    values += Signal.VOLTAGE to lock.withLock { if (pids.isEmpty()) guard.ask { e.voltage() } else optionalVoltage(e) }
                }
                for ((signal, value) in values) if (value == null) last.remove(signal) else last[signal] = value
                for (d in derived) {
                    val raw = d.compute(last)
                    val prev = smoothed[d.signal]
                    val value = if (raw == null || prev == null) raw else prev + (raw - prev) * d.smoothing
                    if (value == null) smoothed.remove(d.signal) else smoothed[d.signal] = value
                    values += d.signal to value
                }
                val quiet = silence.silent
                withContext(Dispatchers.Main) {
                    for ((signal, value) in values) bus.publish(signal, value, Source.OBD)
                    if (carSilent != quiet) carSilent = quiet
                }
                delay(ObdTiming.POLL_GAP_MS)
            }
        }
    }

    /** Abre el enlace (cancelable desde [drop]) y prepara el adaptador. */
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
     * [l] queda como el pendiente (así [drop] lo corta aunque esté bloqueado), se abre con [open] y
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
        val devices = usbCandidates(t).ifEmpty { throw ObdException(ObdError.USB_UNPLUGGED, "no está en la lista") }
        var last: ObdException? = null
        // Con dos cables del mismo chip (el de Llantas suele serlo) se prueba el siguiente si el primero no es el ELM;
        // solo los que ya tienen permiso (el diálogo se pidió para el primero).
        for ((i, device) in devices.withIndex()) {
            if (i > 0 && !manager.hasPermission(device)) continue
            for (baud in USB_BAUDS) {
                try {
                    return prepare(UsbLink(manager, device, baud)) {}
                } catch (x: ObdException) {
                    if (x.kind == ObdError.CAR_SILENT) rememberCable(device.deviceName)
                    // Sin permiso o sin ser puerto serie, otra velocidad no lo arregla; y si el ELM ya contestó
                    // (el auto no), la velocidad y el cable eran los buenos.
                    if (!x.kind.retry || x.kind == ObdError.CAR_SILENT) throw x
                    last = x
                    note("usb $baud: ${x.message}")
                }
            }
        }
        throw last ?: ObdException(ObdError.USB_SILENT)
    }

    /** El cable [deviceName] contestó como ELM327: va primero la próxima vez y Llantas no lo toma. */
    private fun rememberCable(deviceName: String) = prefs.edit().putString(KEY_USB_DEVICE, deviceName).apply()

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
        if (link != null) closedAt = SystemClock.elapsedRealtime()
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
        /** El `deviceName` del último cable que contestó como ELM327 (Llantas no lo toma). */
        private const val KEY_USB_DEVICE = "usb_device"
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

        /** Fabricante y producto del cable guardado para el OBD (null si no es por cable): Llantas lo deja al final. */
        fun usbTargetIds(context: Context): Pair<Int, Int>? {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (p.getString(KEY_TYPE, null) != "usb") return null
            return p.getInt("vendor_id", 0) to p.getInt("product_id", 0)
        }

        /** El `deviceName` del cable que contestó como ELM327 (null si el OBD no es por cable): Llantas no lo toma. */
        fun usbDeviceName(context: Context): String? {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return if (p.getString(KEY_TYPE, null) == "usb") p.getString(KEY_USB_DEVICE, null) else null
        }
    }
}
