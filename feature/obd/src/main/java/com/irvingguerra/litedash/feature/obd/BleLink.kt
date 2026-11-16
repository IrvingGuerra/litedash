package com.irvingguerra.litedash.feature.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Servicios de los ELM327 por Bluetooth LE que se conocen (se prueban primero, en orden). */
private val KNOWN_SERVICES = listOf(
    "0000fff0-0000-1000-8000-00805f9b34fb", // la mayoría de los clones (Veepeak, Konnwei…)
    "0000ffe0-0000-1000-8000-00805f9b34fb", // módulos tipo HM-10
    "000018f0-0000-1000-8000-00805f9b34fb", // Vgate y otros
    "e7810a71-73ae-499d-8c15-faa9aef0c3f2", // Vgate iCar Pro BLE
).map(UUID::fromString)

/** Servicios genéricos de todo aparato BLE (nombre, batería…): no son el puerto serie. */
private val GENERIC_SERVICES = setOf("00001800", "00001801", "0000180a", "0000180f")

/** Descriptor para pedir avisos de una característica (CCCD). */
private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/** Cuánto cabe en un envío con el MTU de fábrica (23 − 3 de encabezado). */
private const val CHUNK = 20

/**
 * ELM327 por Bluetooth LE: no se empareja, se conecta directo. Lo que manda el
 * adaptador llega por avisos de una característica y lo que se le manda se escribe en
 * otra, de 20 en 20 bytes. Se arma como un flujo para que [Elm327] no note diferencia.
 * [open] espera a que todo esté listo (llamarlo desde un hilo de fondo); [close] lo
 * cancela aunque siga a medias.
 */
@SuppressLint("MissingPermission") // el repositorio revisa BLUETOOTH_CONNECT antes
class BleLink(private val context: Context, private val device: BluetoothDevice) : QueuedLink() {
    override val chunkSize = CHUNK
    private val main = Handler(Looper.getMainLooper())
    private val written = Semaphore(0)
    @Volatile private var ready = CountDownLatch(1)
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var failure: String? = null
    /** El fallo fue de la conexión (vale otro intento); no, si el aparato no es un OBD. */
    @Volatile private var retryable = false
    @Volatile private var opened = false
    @Volatile private var writer: BluetoothGattCharacteristic? = null

    // Para el reporte: en qué paso va o se quedó, el último estado de GATT y cuántos intentos.
    @Volatile var stage = Stage.CONNECT
        private set
    @Volatile var gattStatus = BluetoothGatt.GATT_SUCCESS
        private set
    @Volatile var attempts = 0
        private set

    enum class Stage(val key: String) { CONNECT("conectar"), SERVICES("servicios"), NOTIFY("avisos"), INIT("init") }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (stale(g)) return
            gattStatus = status
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                // Pedir los servicios en el mismo aviso falla en varias pantallas (MediaTek): un respiro antes.
                stage = Stage.SERVICES
                main.postDelayed({ if (g === gatt && !closed) g.discoverServices() }, DISCOVER_DELAY_MS)
            } else if (newState == BluetoothProfile.STATE_CONNECTED || newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (opened) {
                    closed = true
                    written.release()
                } else {
                    fail("Se desconectó el adaptador ($status)", retry = true)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (stale(g)) return
            gattStatus = status
            if (status != BluetoothGatt.GATT_SUCCESS) return fail("No se pudo leer el adaptador ($status)", retry = true)
            val (notify, write) = serialPair(g) ?: return fail("No parece un adaptador OBD")
            writer = write
            stage = Stage.NOTIFY
            g.setCharacteristicNotification(notify, true)
            val cccd = notify.getDescriptor(CCCD) ?: return fail("El adaptador no deja recibir sus respuestas")
            val value = if (notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }
            writeDescriptor(g, cccd, value)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (stale(g)) return
            gattStatus = status
            if (status != BluetoothGatt.GATT_SUCCESS) return fail("El adaptador no aceptó mandar respuestas ($status)")
            stage = Stage.INIT
            ready.countDown()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            for (b in value) incoming.put(b)
        }

        @Deprecated("Android 12 y anteriores")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            ch.value?.let { v -> for (b in v) incoming.put(b) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            written.release()
        }
    }

    /**
     * Aviso de un intento anterior. Un 133 puede llegar antes de que [open] guarde el GATT nuevo: sin
     * GATT guardado se toma como del intento actual (los cerrados ya no avisan).
     */
    private fun stale(g: BluetoothGatt): Boolean = gatt.let { it != null && it !== g }

    private fun fail(why: String, retry: Boolean = false) {
        if (failure == null) {
            failure = why
            retryable = retry
        }
        ready.countDown()
    }

    /**
     * Conecta y deja listo el canal. Un fallo de la conexión (el 133 de GATT, típico en MediaTek) se
     * reintenta hasta [ATTEMPTS] veces, cerrando el GATT anterior y con una pausa entre cada uno.
     */
    fun open() {
        while (true) {
            if (closed) throw IOException(CANCELLED)
            attempts++
            stage = Stage.CONNECT
            failure = null
            retryable = false
            writer = null
            ready = CountDownLatch(1)
            // null si el Bluetooth está apagado.
            val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                ?: throw IOException("El Bluetooth está apagado")
            gatt = g
            // Se canceló mientras se creaba: [close] aún no lo veía.
            if (closed) {
                close()
                throw IOException(CANCELLED)
            }
            val ok = ready.await(ATTEMPT_S, TimeUnit.SECONDS)
            if (closed) throw IOException(CANCELLED)
            if (ok && failure == null && writer != null) {
                opened = true
                return
            }
            val why = failure ?: "El adaptador no respondió por Bluetooth LE"
            gatt = null
            runCatching { g.disconnect() }
            runCatching { g.close() }
            // Sin respuesta también vale otro intento; que no sea un OBD, no.
            val again = retryable || !ok
            if (!again || attempts >= ATTEMPTS) {
                closed = true
                throw IOException(why)
            }
            Thread.sleep(RETRY_MS)
        }
    }

    /**
     * Manda un pedazo y espera a que el adaptador lo reciba (de uno en uno, si no se
     * pierden). GATT pide el arreglo exacto, así que aquí sí se copia el trozo.
     */
    override fun send(bytes: ByteArray, offset: Int, length: Int) {
        val chunk = bytes.copyOfRange(offset, offset + length)
        val ch = writer ?: throw IOException("Sin canal de escritura")
        val g = gatt ?: throw IOException("Se cerró la conexión")
        // Confirmaciones tardías de un envío anterior que ya se dio por perdido.
        written.drainPermits()
        val type = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, chunk, type) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            ch.writeType = type
            @Suppress("DEPRECATION")
            ch.value = chunk
            @Suppress("DEPRECATION")
            g.writeCharacteristic(ch)
        }
        if (!started || !written.tryAcquire(2, TimeUnit.SECONDS)) throw IOException("No se pudo mandar al adaptador")
    }

    /** Cierra; si [open] sigue esperando, lo suelta de inmediato. */
    override fun close() {
        closed = true
        ready.countDown()
        written.release()
        val g = gatt ?: return
        gatt = null
        runCatching { g.disconnect() }
        runCatching { g.close() }
    }

    companion object {
        /** El error genérico de GATT (no es público en Android): el que más dan las pantallas MediaTek. */
        const val GATT_ERROR = 133
        private const val ATTEMPTS = 3
        private const val ATTEMPT_S = 15L
        private const val RETRY_MS = 800L
        private const val DISCOVER_DELAY_MS = 600L
        private const val CANCELLED = "Se canceló la conexión"
    }
}

/**
 * La característica que avisa (lo que contesta el adaptador) y la que se escribe, del
 * mismo servicio: primero los servicios conocidos, luego cualquiera que tenga las dos.
 */
private fun serialPair(g: BluetoothGatt): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
    val services = g.services.orEmpty().filter { s -> GENERIC_SERVICES.none { s.uuid.toString().startsWith(it) } }
    val ordered = services.sortedBy { s -> KNOWN_SERVICES.indexOf(s.uuid).let { if (it < 0) Int.MAX_VALUE else it } }
    for (s in ordered) {
        val chars = s.characteristics.orEmpty()
        val notify = chars.firstOrNull { it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 }
        val write = chars.firstOrNull { it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0 }
        if (notify != null && write != null) return notify to write
    }
    return null
}

@SuppressLint("MissingPermission")
private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        g.writeDescriptor(d, value)
    } else {
        @Suppress("DEPRECATION")
        d.value = value
        @Suppress("DEPRECATION")
        g.writeDescriptor(d)
    }
}
