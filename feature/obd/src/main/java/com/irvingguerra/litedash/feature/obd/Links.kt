package com.irvingguerra.litedash.feature.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Puerto serie de Bluetooth clásico (SPP): el de casi todos los ELM327. */
private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

/** ELM327 por Bluetooth clásico, ya emparejado en Ajustes de Android. */
@SuppressLint("MissingPermission") // el repositorio revisa BLUETOOTH_CONNECT antes
class BluetoothLink(device: BluetoothDevice) : ElmLink {
    private val socket: BluetoothSocket = try {
        device.createRfcommSocketToServiceRecord(SPP).also { it.connect() }
    } catch (e: Exception) {
        // Varios clones solo aceptan la conexión "insegura".
        device.createInsecureRfcommSocketToServiceRecord(SPP).also { it.connect() }
    }
    override val input: InputStream = socket.inputStream
    override val output: OutputStream = socket.outputStream
    override fun close() = socket.close()
}

/** ELM327 por Wi-Fi (suelen ser 192.168.0.10:35000); también el simulador de pruebas. */
class WifiLink(host: String, port: Int) : ElmLink {
    private val socket = Socket().apply {
        connect(InetSocketAddress(host, port), 5_000)
        tcpNoDelay = true
    }
    override val input: InputStream = socket.getInputStream()
    override val output: OutputStream = socket.getOutputStream()
    override fun close() = socket.close()
}

/**
 * Base de los enlaces que no son un socket (cable USB y Bluetooth LE): lo que llega se
 * echa a [incoming] (un hilo o los avisos del adaptador) y lo que se manda se junta hasta
 * `flush()` y sale en trozos de [chunkSize] por [send]. Así [Elm327] los ve como un flujo
 * igual que los demás. [lost] es lo que se dice si se cae la conexión.
 */
abstract class QueuedLink(private val lost: String = "Se desconectó el adaptador") : ElmLink {
    protected val incoming = LinkedBlockingQueue<Byte>()
    @Volatile protected var closed = false

    /** Cuánto cabe en un envío (se lee hasta el primer `flush()`, ya armado el enlace). */
    protected abstract val chunkSize: Int

    /** Manda `length` bytes de [bytes] desde [offset] (nunca más de [chunkSize]). */
    protected abstract fun send(bytes: ByteArray, offset: Int, length: Int)

    final override val input: InputStream = object : InputStream() {
        // Caído y sin nada pendiente: que se note ya, no tras esperar la respuesta. Lo que
        // ya llegó se sigue entregando.
        override fun available(): Int {
            if (closed && incoming.isEmpty()) throw IOException(lost)
            return incoming.size
        }
        override fun read(): Int {
            if (closed && incoming.isEmpty()) return -1
            return (incoming.poll(3, TimeUnit.SECONDS) ?: return -1).toInt() and 0xFF
        }
    }

    final override val output: OutputStream = object : OutputStream() {
        private val pending = ByteArrayOutputStream()
        override fun write(b: Int) = pending.write(b)
        override fun flush() {
            val bytes = pending.toByteArray()
            pending.reset()
            for (start in bytes.indices step chunkSize) {
                if (closed) throw IOException("Se cerró la conexión")
                send(bytes, start, minOf(chunkSize, bytes.size - start))
            }
        }
    }
}
