package com.irvingguerra.litedash.feature.tpms

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableStateListOf
import com.irvingguerra.litedash.core.vehicle.bleLocationOff
import com.irvingguerra.litedash.core.vehicle.canScanBle

/** Por qué no se puede escuchar a los tapones Bluetooth (la app lo dice en su idioma). */
enum class BleProblem { NO_PERMISSION, BLUETOOTH_OFF, LOCATION_OFF, UNSUPPORTED, FAILED }

/** Un anuncio con datos que no se pudo leer (para que el usuario reporte familias nuevas). */
data class UnknownAdvert(
    val address: String,
    val name: String?,
    val companyId: Int?,
    /** Datos de fabricante completos (con la compañía) en hexadecimal. */
    val manufacturerHex: String?,
    val serviceUuids: List<String>,
    /** Datos de servicio: "uuid=HEX" separados por espacio. */
    val serviceDataHex: String?,
    val rssi: Int,
    val atMillis: Long,
)

/**
 * Escucha anuncios Bluetooth LE sin conectarse a nada y los pasa a [onReading]. Ahorra pila
 * (LOW_POWER) salvo con la pantalla de llantas a la vista (BALANCED). Android bloquea a quien
 * llama `startScan` más de ~5 veces en 30 s: aquí nunca se reinicia en bucle; un cambio de modo
 * que no cabe en ese límite se aplaza.
 */
class BleTpmsScanner(
    private val context: Context,
    /** Juntar los anuncios desconocidos (solo con el diagnóstico a la vista). */
    private val collectUnknown: () -> Boolean,
    private val onReading: (TireReading) -> Unit,
) {
    /** Los últimos 60 anuncios desconocidos con datos, el más nuevo al final. */
    val unknown = mutableStateListOf<UnknownAdvert>()

    private val main = Handler(Looper.getMainLooper())
    private var scanner: BluetoothLeScanner? = null
    private var balanced = false
    private val limiter = StartLimiter(SystemClock::elapsedRealtime)
    private var pendingRestart: Runnable? = null
    /** Alguien pidió escuchar (aunque el arranque esté aplazado). */
    private var wanted = false

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)

        override fun onScanFailed(errorCode: Int) {
            scanner = null
            onFailure?.invoke(BleProblem.FAILED)
            // Sin Bluetooth LE no hay nada que reintentar; si no (registro fallido, demasiados arranques…),
            // se vuelve a intentar al pasar la ventana del límite de Android, sin dejar de avisar.
            if (errorCode == ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED) wanted = false else scheduleRestart()
        }
    }

    /** Se avisa si Android corta la búsqueda por su cuenta. */
    var onFailure: ((BleProblem) -> Unit)? = null

    /** Empieza (o cambia de modo); null = ya escucha, o el problema que lo impide. Hilo principal. */
    @SuppressLint("MissingPermission") // canScanBle() se revisa antes
    fun start(balanced: Boolean): BleProblem? {
        if (!canScanBle(context)) return BleProblem.NO_PERMISSION
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return BleProblem.UNSUPPORTED
        if (!adapter.isEnabled) return BleProblem.BLUETOOTH_OFF
        if (bleLocationOff(context)) return BleProblem.LOCATION_OFF
        val le = adapter.bluetoothLeScanner ?: return BleProblem.BLUETOOTH_OFF
        wanted = true
        if (scanner != null && this.balanced == balanced) return null
        this.balanced = balanced
        if (scanner != null) {
            // Ya busca en el otro modo: cambiarlo solo si cabe en el límite de Android.
            if (!limiter.canStart()) return run { scheduleRestart(); null }
            stopNow()
        }
        if (!limiter.canStart()) return run { scheduleRestart(); null }
        return startNow(le)
    }

    @SuppressLint("MissingPermission")
    private fun startNow(le: BluetoothLeScanner): BleProblem? {
        limiter.started()
        val mode = if (balanced) ScanSettings.SCAN_MODE_BALANCED else ScanSettings.SCAN_MODE_LOW_POWER
        return runCatching {
            le.startScan(null, ScanSettings.Builder().setScanMode(mode).build(), callback)
            scanner = le
            null
        }.getOrElse { BleProblem.FAILED }
    }

    private fun scheduleRestart() {
        if (pendingRestart != null) return
        val wait = limiter.waitMs()
        val r = Runnable {
            pendingRestart = null
            if (wanted) { stopNow(); start(balanced)?.let { onFailure?.invoke(it) } }
        }
        pendingRestart = r
        main.postDelayed(r, wait)
    }

    fun stop() {
        wanted = false
        pendingRestart?.let(main::removeCallbacks)
        pendingRestart = null
        stopNow()
    }

    @SuppressLint("MissingPermission")
    private fun stopNow() {
        val s = scanner ?: return
        scanner = null
        runCatching { s.stopScan(callback) }
    }

    private fun handle(result: ScanResult) {
        val record = result.scanRecord
        val mfr = record?.manufacturerSpecificData
        val companyId = if (mfr != null && mfr.size() > 0) mfr.keyAt(0) else null
        val uuids = record?.serviceUuids.orEmpty().map { shortUuid(it.uuid.toString()) }
        val name = record?.deviceName
        val adv = Advert(name, result.device.address, companyId, companyId?.let { mfr?.get(it) }, uuids, result.rssi)
        val now = System.currentTimeMillis()
        val reading = BleTpmsFormats.decode(adv, now)
        if (reading != null) return onReading(reading)
        if (!collectUnknown()) return
        val serviceData = record?.serviceData.orEmpty().entries.joinToString(" ") { (k, v) -> "${shortUuid(k.uuid.toString())}=${v.hex()}" }
        if (adv.manufacturerData == null && serviceData.isEmpty()) return
        val entry = UnknownAdvert(adv.address, name, companyId, adv.fullPayload?.hex(), uuids, serviceData.ifEmpty { null }, adv.rssi, now)
        val i = unknown.indexOfFirst { it.address == entry.address }
        if (i >= 0) {
            val old = unknown[i]
            // Mismo contenido hace poco: no se mueve la lista (menos trabajo para Compose).
            if (old.manufacturerHex == entry.manufacturerHex && old.serviceDataHex == entry.serviceDataHex && now - old.atMillis < 5_000) return
            unknown.removeAt(i)
        }
        unknown.add(entry)
        while (unknown.size > MAX_UNKNOWN) unknown.removeAt(0)
    }

    companion object {
        private const val MAX_UNKNOWN = 60
        private const val BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"

        /** "000027a5-0000-1000-8000-00805f9b34fb" → "27a5"; los de 128 bits completos. */
        fun shortUuid(uuid: String): String {
            val u = uuid.lowercase()
            return if (u.startsWith("0000") && u.endsWith(BASE_SUFFIX)) u.substring(4, 8) else u
        }
    }
}

/**
 * Android bloquea a quien llama `startScan` más de ~5 veces en 30 s: aquí máximo 4 (uno de margen).
 * [clock] = milisegundos desde el arranque.
 */
internal class StartLimiter(private val clock: () -> Long) {
    private val starts = ArrayDeque<Long>()

    fun canStart(): Boolean {
        val now = clock()
        while (starts.isNotEmpty() && now - starts.first() > WINDOW_MS) starts.removeFirst()
        return starts.size < MAX_STARTS
    }

    fun started() {
        starts.addLast(clock())
    }

    /** Cuánto esperar para que vuelva a caber un arranque (al menos medio segundo). */
    fun waitMs(): Long {
        val oldest = starts.firstOrNull() ?: return MIN_WAIT_MS
        return (WINDOW_MS - (clock() - oldest) + MIN_WAIT_MS).coerceAtLeast(MIN_WAIT_MS)
    }

    companion object {
        const val WINDOW_MS = 30_000L
        const val MAX_STARTS = 4
        private const val MIN_WAIT_MS = 500L
    }
}
