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
import java.util.UUID
import java.util.concurrent.CancellationException
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

/**
 * ELM327 por Bluetooth LE: no se empareja, se conecta directo. Lo que manda el
 * adaptador llega por avisos de una característica y lo que se le manda se escribe en
 * otra, de 20 en 20 bytes. Se arma como un flujo para que [Elm327] no note diferencia.
 * [open] hace UN intento y espera a que todo esté listo (llamarlo desde un hilo de fondo); si
 * falla dice por qué con [ObdError] y el repositorio decide si reintenta (con un enlace nuevo:
 * así los avisos de un GATT viejo nunca se confunden con los del intento actual). [close] lo
 * cancela aunque siga a medias.
 */
@SuppressLint("MissingPermission") // el repositorio revisa BLUETOOTH_CONNECT antes
class BleLink(private val context: Context, private val device: BluetoothDevice) : QueuedLink() {
    override val chunkSize = CHUNK
    private val main = Handler(Looper.getMainLooper())
    private val written = Semaphore(0)
    private val ready = CountDownLatch(1)
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var failure: ObdException? = null
    @Volatile private var opened = false
    @Volatile private var writer: BluetoothGattCharacteristic? = null

    // Para el reporte: en qué paso va o se quedó y el último estado de GATT.
    @Volatile private var stage = Stage.CONNECT
    @Volatile private var gattStatus = BluetoothGatt.GATT_SUCCESS

    private enum class Stage(val key: String) { CONNECT("conectar"), SERVICES("servicios"), NOTIFY("avisos"), READY("listo") }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            gattStatus = status
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                stage = Stage.SERVICES
                main.postDelayed({ if (!closed) g.discoverServices() }, ObdTiming.BLE_DISCOVER_DELAY_MS)
            } else if (newState == BluetoothProfile.STATE_CONNECTED || newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (opened) {
                    markClosed()
                    written.release()
                } else {
                    // El 133 y las desconexiones al abrir: Bluetooth ocupado, suele conectar al rato.
                    fail(ObdError.GATT_BUSY)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            gattStatus = status
            if (status != BluetoothGatt.GATT_SUCCESS) return fail(ObdError.GATT_BUSY)
            val (notify, write) = serialPair(g) ?: return fail(noChannel())
            writer = write
            stage = Stage.NOTIFY
            g.setCharacteristicNotification(notify, true)
            // Sin descriptor para pedir avisos, o si no lo acepta, no se le pueden leer respuestas.
            val cccd = notify.getDescriptor(CCCD) ?: return fail(noChannel())
            val value = if (notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }
            writeDescriptor(g, cccd, value)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            gattStatus = status
            // Un estado de error aquí es la pila de Bluetooth (pasa de vez en cuando en MediaTek/8227L), no el adaptador.
            if (status != BluetoothGatt.GATT_SUCCESS) return fail(ObdError.GATT_BUSY)
            noChannelAt = null
            stage = Stage.READY
            ready.countDown()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            incoming.put(value)
        }

        @Deprecated("Android 12 y anteriores")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            ch.value?.let { incoming.put(it) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            written.release()
        }
    }

    /** Sin canal serie: la primera vez puede ser la caché de GATT incompleta (se reintenta); dos seguidas, no es OBD. */
    private fun noChannel(): ObdError = noChannelError(device.address, noChannelAt).also { noChannelAt = device.address }

    /** El primer fallo es el que vale; el detalle dice el paso y el estado de GATT. */
    private fun fail(kind: ObdError) {
        if (failure == null) failure = ObdException(kind, "ble paso:${stage.key} estado:$gattStatus")
        ready.countDown()
    }

    /** Conecta y deja listo el canal (un solo intento). */
    override fun open() {
        if (closed) throw CancellationException()
        // null si el Bluetooth está apagado.
        val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw ObdException(ObdError.BLUETOOTH_OFF, "connectGatt null")
        gatt = g
        // Se canceló mientras se creaba: [close] aún no lo veía.
        if (closed) {
            close()
            throw CancellationException()
        }
        val ok = ready.await(ObdTiming.BLE_OPEN_S, TimeUnit.SECONDS)
        if (closed) throw CancellationException()
        // Sin respuesta en el plazo es lo mismo que el 133: la pila de Bluetooth atorada, suele conectar al rato.
        val why = failure ?: if (!ok) ObdException(ObdError.GATT_BUSY, "ble paso:${stage.key} sin respuesta") else null
        if (why == null && writer != null) {
            opened = true
            return
        }
        close()
        throw why ?: ObdException(noChannel(), "ble sin canal de escritura")
    }

    /**
     * Manda un pedazo y espera a que el adaptador lo reciba (de uno en uno, si no se
     * pierden). GATT pide el arreglo exacto, así que aquí sí se copia el trozo.
     */
    override fun send(bytes: ByteArray, offset: Int, length: Int) {
        val chunk = bytes.copyOfRange(offset, offset + length)
        val ch = writer ?: throw ObdException(ObdError.CONNECTION_LOST, "ble sin canal de escritura")
        val g = gatt ?: throw ObdException(ObdError.CONNECTION_LOST, "ble cerrado")
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
        if (!started || !written.tryAcquire(2, TimeUnit.SECONDS)) throw ObdException(ObdError.CONNECTION_LOST, "ble no se pudo mandar")
    }

    /** Cierra; si [open] sigue esperando, lo suelta de inmediato. */
    override fun close() {
        markClosed()
        ready.countDown()
        written.release()
        main.removeCallbacksAndMessages(null)
        val g = gatt ?: return
        gatt = null
        try {
            g.disconnect()
            g.close()
        } catch (_: SecurityException) {
            // Quitaron el permiso de Bluetooth: Android ya soltó el GATT.
        }
    }

    private companion object {
        /** El aparato que la última vez no tuvo canal serie (ver [noChannel]). */
        @Volatile var noChannelAt: String? = null
        /** Cuánto cabe en un envío con el MTU de fábrica (23 − 3 de encabezado). */
        const val CHUNK = 20
    }
}

/**
 * La característica que avisa (lo que contesta el adaptador) y la que se escribe, del
 * mismo servicio: primero los servicios conocidos, luego cualquiera que tenga las dos.
 */
/**
 * El error de un aparato sin canal serie: [ObdError.NOT_OBD] solo si también le faltó la vez anterior ([previous] es su
 * dirección); la primera vez [ObdError.GATT_BUSY] (se reintenta solo), porque una caché de GATT incompleta se ve igual.
 */
internal fun noChannelError(address: String, previous: String?): ObdError =
    if (address == previous) ObdError.NOT_OBD else ObdError.GATT_BUSY

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
