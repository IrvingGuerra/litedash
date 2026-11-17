package com.irvingguerra.litedash.feature.obd

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Cerrar algo que ya falló o se canceló: si también falla al cerrar no hay nada más que hacer. */
internal fun closeQuietly(c: Closeable) {
    try {
        c.close()
    } catch (_: IOException) {
    } catch (_: SecurityException) {
    }
}

/** Puerto serie de Bluetooth clásico (SPP): el de casi todos los ELM327. */
private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

/** Cómo se abre el socket de Bluetooth clásico; se recuerda el que sirvió con cada adaptador. */
enum class BtSocket(val key: String) {
    SECURE("secure"), INSECURE("insecure"), CHANNEL1("channel1");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key }
    }
}

/**
 * ELM327 por Bluetooth clásico, ya emparejado en Ajustes de Android. Muchos clones (y pantallas
 * MediaTek) fallan con "read failed, socket might closed": se para la búsqueda de aparatos (frena
 * la conexión) y se prueba, una vez cada uno, el canal seguro, el inseguro y el canal 1 directo; la
 * forma que ya sirvió con este adaptador ([remembered]) va primero. Una sola ronda por intento (los
 * reintentos, con su espera, son del repositorio): insistir seguido atora el Bluetooth de algunas
 * pantallas. [close] desde otro hilo corta el intento en curso (cerrar el socket suelta `connect()`).
 * Si nada conecta, se dice el primer fallo (el más útil para el reporte).
 */
@SuppressLint("MissingPermission") // el repositorio revisa BLUETOOTH_CONNECT antes
class BluetoothLink(
    private val context: Context,
    private val device: BluetoothDevice,
    private val remembered: BtSocket?,
) : ElmLink {
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var cancelled = false

    /** La forma que conectó (para recordarla). */
    var used: BtSocket? = null
        private set

    override val input: InputStream get() = connected().inputStream
    override val output: OutputStream get() = connected().outputStream

    private fun connected() = socket ?: throw ObdException(ObdError.CONNECTION_LOST, "sin socket")

    override fun open() {
        stopDiscovery()
        var first: Throwable? = null
        for (kind in listOfNotNull(remembered) + BtSocket.entries.filter { it != remembered }) {
            if (cancelled) throw CancellationException()
            val s = try {
                make(kind)
            } catch (x: Exception) {
                // Sin la API oculta del canal 1 (o sin permiso): se salta.
                first = first ?: x
                continue
            }
            socket = s
            try {
                // Se canceló mientras se creaba: [close] aún no lo veía.
                if (cancelled) throw CancellationException()
                s.connect()
                used = kind
                return
            } catch (x: CancellationException) {
                socket = null
                closeQuietly(s)
                throw x
            } catch (x: Exception) {
                // Un canal colgado hace fallar los intentos que siguen: se cierra.
                socket = null
                closeQuietly(s)
                if (cancelled) throw CancellationException()
                first = first ?: x
            }
        }
        val why = first
        throw ObdException(
            if (why is SecurityException) ObdError.NO_BLUETOOTH_PERMISSION else ObdError.ADAPTER_UNREACHABLE,
            why?.let(::detailOf),
            why,
        )
    }

    override fun close() {
        cancelled = true
        socket?.close()
    }

    /** Seguro, inseguro (varios clones solo aceptan ese) y, de último, el canal 1 sin buscar el servicio. */
    @SuppressLint("DiscouragedPrivateApi") // el respaldo de siempre para clones; si Android lo quita, se salta
    private fun make(kind: BtSocket): BluetoothSocket = when (kind) {
        BtSocket.SECURE -> device.createRfcommSocketToServiceRecord(SPP)
        BtSocket.INSECURE -> device.createInsecureRfcommSocketToServiceRecord(SPP)
        BtSocket.CHANNEL1 -> device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(device, 1) as BluetoothSocket
    }

    /** Buscar aparatos a la vez hace fallar la conexión; desde Android 12 pararla pide BLUETOOTH_SCAN. */
    private fun stopDiscovery() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
        ) return
        context.getSystemService(BluetoothManager::class.java)?.adapter?.cancelDiscovery()
    }
}

/** ELM327 por Wi-Fi (suelen ser 192.168.0.10:35000); también el simulador de pruebas. [close] corta el intento. */
class WifiLink(private val host: String, private val port: Int) : ElmLink {
    private val socket = Socket()
    override val input: InputStream get() = socket.getInputStream()
    override val output: OutputStream get() = socket.getOutputStream()

    override fun open() {
        try {
            socket.connect(InetSocketAddress(host, port), ObdTiming.WIFI_CONNECT_MS)
            socket.tcpNoDelay = true
        } catch (x: Exception) {
            closeQuietly(socket)
            if (x is SecurityException) throw ObdException(ObdError.UNKNOWN, detailOf(x), x)
            throw ObdException(ObdError.ADAPTER_UNREACHABLE, detailOf(x), x)
        }
    }

    /** Lectura bloqueante con plazo (sin despertar cada 5 ms). */
    override fun next(waitMs: Long): Int? {
        socket.soTimeout = waitMs.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
        return try {
            socket.getInputStream().read()
        } catch (_: SocketTimeoutException) {
            null
        }
    }

    override fun close() = socket.close()
}

/**
 * Base de los enlaces que no son un socket (cable USB y Bluetooth LE): lo que llega se
 * echa a [incoming] (un hilo o los avisos del adaptador) y lo que se manda se junta hasta
 * `flush()` y sale en trozos de [chunkSize] por [send]. Así [Elm327] los ve como un flujo
 * igual que los demás. [lost] es lo que se dice si se cae la conexión.
 */
abstract class QueuedLink(private val lost: ObdError = ObdError.CONNECTION_LOST) : ElmLink {
    protected val incoming = ByteQueue()
    @Volatile protected var closed = false

    /** Cuánto cabe en un envío (se lee hasta el primer `flush()`, ya armado el enlace). */
    protected abstract val chunkSize: Int

    /** Manda `length` bytes de [bytes] desde [offset] (nunca más de [chunkSize]). */
    protected abstract fun send(bytes: ByteArray, offset: Int, length: Int)

    final override val input: InputStream = object : InputStream() {
        // Caído y sin nada pendiente: que se note ya, no tras esperar la respuesta. Lo que
        // ya llegó se sigue entregando.
        override fun available(): Int {
            if (closed && incoming.isEmpty()) throw ObdException(lost)
            return incoming.size
        }
        override fun read(): Int {
            if (closed && incoming.isEmpty()) return -1
            return incoming.take(3_000) ?: -1
        }
    }

    /** Espera bloqueada con plazo (sin despertar cada 5 ms); al cerrarse lo nota al instante. */
    final override fun next(waitMs: Long): Int? {
        val b = incoming.take(waitMs)
        if (b == null && closed) throw ObdException(lost)
        return b
    }

    final override val output: OutputStream = object : OutputStream() {
        private val pending = ByteArrayOutputStream()
        override fun write(b: Int) = pending.write(b)
        override fun flush() {
            val bytes = pending.toByteArray()
            pending.reset()
            for (start in bytes.indices step chunkSize) {
                if (closed) throw ObdException(lost, "cerrado al mandar")
                send(bytes, start, minOf(chunkSize, bytes.size - start))
            }
        }
    }

    /** Ya no llega nada: quien espera bytes se entera de inmediato. */
    protected fun markClosed() {
        closed = true
        incoming.close()
    }
}

/** Cola de bytes sin un objeto por byte: un arreglo circular que crece si hace falta. */
class ByteQueue {
    private val lock = ReentrantLock()
    private val arrived = lock.newCondition()
    private var buf = ByteArray(256)
    private var head = 0
    private var count = 0
    private var closed = false

    val size: Int get() = lock.withLock { count }
    fun isEmpty() = size == 0

    fun put(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) = lock.withLock {
        if (count + length > buf.size) {
            val bigger = ByteArray(maxOf(buf.size * 2, count + length))
            for (i in 0 until count) bigger[i] = buf[(head + i) % buf.size]
            buf = bigger
            head = 0
        }
        for (i in 0 until length) buf[(head + count + i) % buf.size] = bytes[offset + i]
        count += length
        arrived.signalAll()
    }

    /** El siguiente byte (0–255), o null si en [waitMs] no llegó nada (o ya se cerró). */
    fun take(waitMs: Long): Int? = lock.withLock {
        var left = TimeUnit.MILLISECONDS.toNanos(waitMs)
        while (count == 0 && !closed && left > 0) left = arrived.awaitNanos(left)
        if (count == 0) return null
        val b = buf[head].toInt() and 0xFF
        head = (head + 1) % buf.size
        count--
        b
    }

    /** Lo que ya llegó se sigue entregando; después [take] regresa null sin esperar. */
    fun close() = lock.withLock {
        closed = true
        arrived.signalAll()
    }
}
