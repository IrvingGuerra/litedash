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

/**
 * ELM327 por Bluetooth clásico, ya emparejado en Ajustes de Android. Muchos clones (y pantallas
 * MediaTek) fallan con "read failed, socket might closed": se para la búsqueda de aparatos (frena
 * la conexión), se prueba el canal seguro, el inseguro y el canal 1 directo, y todo una segunda vez.
 * Si nada conecta, se dice el primer fallo (el más útil para el reporte).
 */
@SuppressLint("MissingPermission") // el repositorio revisa BLUETOOTH_CONNECT antes
class BluetoothLink(context: Context, device: BluetoothDevice) : ElmLink {
    private val socket: BluetoothSocket = open(context, device)

    override val input: InputStream = socket.inputStream
    override val output: OutputStream = socket.outputStream
    override fun close() = socket.close()

    private companion object {
        const val ROUNDS = 2
        const val RETRY_MS = 1_000L

        fun open(context: Context, device: BluetoothDevice): BluetoothSocket {
            stopDiscovery(context)
            var first: Exception? = null
            repeat(ROUNDS) { round ->
                if (round > 0) Thread.sleep(RETRY_MS)
                for (make in sockets(device)) {
                    val s = runCatching { make() }.getOrElse { first = first ?: (it as? Exception); null } ?: continue
                    try {
                        s.connect()
                        return s
                    } catch (e: Exception) {
                        // Un canal colgado hace fallar los intentos que siguen: se cierra.
                        runCatching { s.close() }
                        first = first ?: e
                    }
                }
            }
            throw first ?: IOException("No se pudo conectar por Bluetooth")
        }

        /** Seguro, inseguro (varios clones solo aceptan ese) y, de último, el canal 1 sin buscar el servicio. */
        @SuppressLint("DiscouragedPrivateApi") // el respaldo de siempre para clones; si Android lo quita, se salta
        fun sockets(device: BluetoothDevice): List<() -> BluetoothSocket> = listOf(
            { device.createRfcommSocketToServiceRecord(SPP) },
            { device.createInsecureRfcommSocketToServiceRecord(SPP) },
            { device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(device, 1) as BluetoothSocket },
        )

        /** Buscar aparatos a la vez hace fallar la conexión; desde Android 12 pararla pide BLUETOOTH_SCAN. */
        fun stopDiscovery(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
            ) return
            runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.cancelDiscovery() }
        }
    }
}

/** ELM327 por Wi-Fi (suelen ser 192.168.0.10:35000); también el simulador de pruebas. */
class WifiLink(host: String, port: Int) : ElmLink {
    private val socket = Socket().apply {
        try {
            connect(InetSocketAddress(host, port), 5_000)
            tcpNoDelay = true
        } catch (e: Exception) {
            runCatching { close() }
            throw e
        }
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
