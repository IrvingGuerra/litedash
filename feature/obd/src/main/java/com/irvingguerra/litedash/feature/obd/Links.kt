package com.irvingguerra.litedash.feature.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

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
