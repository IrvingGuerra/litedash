package com.irvingguerra.litedash.feature.obd

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import java.util.Collections
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** El nombre de un aparato USB para mostrarlo: el de su fabricante o, sin él, sus números. Lo usan OBD y Llantas. */
fun UsbDevice.displayName(): String =
    productName?.takeIf { it.isNotBlank() } ?: "USB %04X:%04X".format(Locale.US, vendorId, productId)

/** Velocidades que usan los cables ELM327 por USB, de la más común a la menos. */
internal val USB_BAUDS = listOf(38_400, 115_200, 9_600)

/**
 * ELM327 por cable USB (la pantalla es el host). El cable trae un chip puente USB‑serie;
 * cada marca se prepara distinto (CH34x, CP210x, FTDI, PL2303 o el estándar CDC‑ACM) y
 * después todos son dos endpoints bulk: uno para leer y otro para escribir. Un hilo lee
 * sin parar a la cola de [QueuedLink], para que [Elm327] lo vea como un flujo igual que los demás.
 */
class UsbLink(manager: UsbManager, private val device: UsbDevice, baud: Int) : QueuedLink(ObdError.USB_UNPLUGGED) {
    /**
     * Un cable lo abre un solo enlace a la vez (OBD y Llantas usan el mismo chip y `claimInterface(force)` se lo
     * quitaría al otro): se aparta antes de abrirlo y se suelta al cerrar.
     */
    private val owned = device.deviceName.also { if (!busy.add(it)) throw ObdException(ObdError.ADAPTER_UNREACHABLE, "cable en uso") }
    /** El `deviceName` del cable (lo identifica mientras siga enchufado). */
    val deviceName: String get() = owned
    private val connection: UsbDeviceConnection = manager.openDevice(device) ?: run {
        release(owned)
        throw ObdException(ObdError.NO_USB_PERMISSION, "openDevice null")
    }
    private val claimed = mutableListOf<UsbInterface>()
    private val reader: UsbEndpoint
    private val writer: UsbEndpoint
    /** FTDI manda 2 bytes de estado al principio de cada paquete leído: se tiran. */
    private val statusBytes: Int
    /** Lo que cabe en un paquete del endpoint de escritura. */
    override val chunkSize get() = writer.maxPacketSize

    init {
        val chip = Chip.of(device)
        // CDC: el control va en la interfaz de clase 2 y los datos en la de clase 10; en los
        // demás chips todo está en la primera (o única) interfaz.
        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            val useful = chip != Chip.CDC || iface.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA ||
                iface.interfaceClass == UsbConstants.USB_CLASS_COMM
            if (!useful) continue
            if (!connection.claimInterface(iface, true)) continue
            claimed += iface
            for (k in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(k)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN && inEp == null) inEp = ep
                if (ep.direction == UsbConstants.USB_DIR_OUT && outEp == null) outEp = ep
            }
        }
        if (inEp == null || outEp == null) {
            close()
            throw ObdException(ObdError.USB_NOT_SERIAL, "sin endpoints bulk")
        }
        reader = inEp
        writer = outEp
        statusBytes = if (chip == Chip.FTDI) 2 else 0
        try {
            chip.setup(connection, claimed, baud)
        } catch (x: Exception) {
            // Cualquier falla suelta el cable (si no, quedaría apartado hasta reiniciar la app).
            close()
            throw x
        }
        Thread({ pump() }, "obd-usb").apply { isDaemon = true }.start()
    }

    private fun pump() {
        val buf = ByteArray(reader.maxPacketSize.coerceAtLeast(64))
        var fastFails = 0
        while (!closed) {
            val start = SystemClock.uptimeMillis()
            val n = connection.bulkTransfer(reader, buf, buf.size, 200)
            if (n >= 0) {
                fastFails = 0
                if (n > statusBytes) incoming.put(buf, statusBytes, n - statusBytes)
                continue
            }
            // −1 tras esperar = sin datos; −1 al instante = el cable falló (desconectado): no girar en vacío.
            if (SystemClock.uptimeMillis() - start >= 150) {
                fastFails = 0
                continue
            }
            if (++fastFails >= 10) {
                markClosed()
                break
            }
            Thread.sleep(50)
        }
    }

    /** Escribe directo del arreglo, sin copiar; el chip puede aceptar menos de lo pedido. */
    override fun send(bytes: ByteArray, offset: Int, length: Int) {
        var sent = 0
        while (sent < length) {
            val n = connection.bulkTransfer(writer, bytes, offset + sent, length - sent, 1_000)
            if (n < 0) throw ObdException(ObdError.USB_UNPLUGGED, "bulkTransfer $n")
            sent += n
        }
    }

    override fun close() {
        markClosed()
        claimed.forEach { connection.releaseInterface(it) }
        connection.close()
        release(owned)
    }

    /** El chip puente del cable y cómo se le pide 8N1 a cierta velocidad. */
    private enum class Chip {
        CH34X, CP210X, FTDI, PL2303, CDC;

        fun setup(c: UsbDeviceConnection, ifaces: List<UsbInterface>, baud: Int) {
            when (this) {
                CH34X -> {
                    // Secuencia de los CH340/CH341 (la misma que usa Linux): reinicio, velocidad, 8N1 y líneas DTR/RTS.
                    val (factor, divisor) = ch34xBaud(baud)
                    c.vendorIn(0x5F, 0, 0, 8)
                    c.vendorOut(0xA1, 0, 0)
                    c.vendorOut(0x9A, 0x1312, factor)
                    c.vendorOut(0x9A, 0x0F2C, divisor)
                    c.vendorIn(0x95, 0x2518, 0, 8)
                    c.vendorOut(0x9A, 0x2518, 0x0050)
                    c.vendorIn(0x95, 0x0706, 0, 8)
                    c.vendorOut(0xA1, 0x501F, 0xD90A)
                    c.vendorOut(0x9A, 0x1312, factor)
                    c.vendorOut(0x9A, 0x0F2C, divisor)
                    c.vendorOut(0xA4, (0x20 or 0x40).inv() and 0xFF, 0) // DTR y RTS activos
                }
                CP210X -> {
                    c.vendorOut(0x00, 0x0001, 0)                  // IFC_ENABLE
                    c.vendorOut(0x1E, 0, 0, le32(baud))           // SET_BAUDRATE
                    c.vendorOut(0x03, 0x0800, 0)                  // SET_LINE_CTL: 8 bits, sin paridad, 1 stop
                    c.vendorOut(0x07, 0x0303, 0)                  // SET_MHS: DTR y RTS
                }
                FTDI -> {
                    c.vendorOut(0x00, 0, 0)                       // reinicio
                    val (value, index) = ftdiBaud(baud)
                    c.vendorOut(0x03, value, index)               // velocidad
                    c.vendorOut(0x04, 0x0008, 0)                  // 8N1
                    c.vendorOut(0x01, 0x0101, 0)                  // DTR
                    c.vendorOut(0x01, 0x0202, 0)                  // RTS
                }
                PL2303 -> {
                    // Los PL2303 piden esta "charla" antes de aceptar la velocidad (así lo hace Linux).
                    c.vendorIn(0x01, 0x8484, 0, 1); c.vendorOut(0x01, 0x0404, 0)
                    c.vendorIn(0x01, 0x8484, 0, 1); c.vendorIn(0x01, 0x8383, 0, 1)
                    c.vendorIn(0x01, 0x8484, 0, 1); c.vendorOut(0x01, 0x0404, 1)
                    c.vendorIn(0x01, 0x8484, 0, 1); c.vendorIn(0x01, 0x8383, 0, 1)
                    c.vendorOut(0x01, 0, 1); c.vendorOut(0x01, 1, 0); c.vendorOut(0x01, 2, 0x44)
                    c.lineCoding(0, baud)
                }
                CDC -> {
                    val control = ifaces.firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_COMM }?.id ?: 0
                    c.lineCoding(control, baud)
                }
            }
        }

        companion object {
            fun of(d: UsbDevice): Chip = when (d.vendorId) {
                0x1A86 -> CH34X
                0x10C4 -> CP210X
                0x0403 -> FTDI
                0x067B -> PL2303
                else -> CDC
            }
        }
    }

    companion object {
        /** Los cables abiertos ahora (por su `deviceName`), de cualquier módulo. */
        private val busy: MutableSet<String> = Collections.synchronizedSet(HashSet())

        /** [d] ya lo tiene abierto un enlace (del OBD o de Llantas): no se toca. */
        fun inUse(d: UsbDevice): Boolean = d.deviceName in busy

        /** Cables en los que Llantas ya leyó tramas de su receptor (mientras viva la app): el OBD no los prueba. */
        private val receivers: MutableSet<String> = Collections.synchronizedSet(HashSet())

        fun markReceiver(d: UsbDevice) { receivers += d.deviceName }

        fun isReceiver(d: UsbDevice): Boolean = d.deviceName in receivers

        private val releaseListeners = CopyOnWriteArrayList<(String) -> Unit>()

        /**
         * Avisa (desde cualquier hilo, con su `deviceName`) cada vez que un enlace suelta un cable: quien lo quería y lo
         * encontró apartado puede intentar otra vez. Devuelve cómo dejar de escuchar.
         */
        fun onRelease(listener: (String) -> Unit): () -> Unit {
            releaseListeners += listener
            return { releaseListeners -= listener }
        }

        private fun release(name: String) {
            if (busy.remove(name)) releaseListeners.forEach { it(name) }
        }

        private const val TIMEOUT = 1_000
        /** Peticiones de fabricante hacia el chip (bmRequestType 0x40) y de vuelta (0xC0). */
        internal fun UsbDeviceConnection.vendorOut(request: Int, value: Int, index: Int, data: ByteArray? = null) {
            val n = controlTransfer(0x40, request, value, index, data, data?.size ?: 0, TIMEOUT)
            if (n < 0) throw ObdException(ObdError.USB_SILENT, "control out $request")
        }

        internal fun UsbDeviceConnection.vendorIn(request: Int, value: Int, index: Int, length: Int) {
            val buf = ByteArray(length)
            if (controlTransfer(0xC0, request, value, index, buf, length, TIMEOUT) < 0) throw ObdException(ObdError.USB_SILENT, "control in $request")
        }

        /** CDC: SET_LINE_CODING (8N1 a [baud]) y SET_CONTROL_LINE_STATE (DTR y RTS). */
        internal fun UsbDeviceConnection.lineCoding(iface: Int, baud: Int) {
            val coding = le32(baud) + byteArrayOf(0, 0, 8)
            if (controlTransfer(0x21, 0x20, 0, iface, coding, coding.size, TIMEOUT) < 0) throw ObdException(ObdError.USB_SILENT, "line coding $baud")
            controlTransfer(0x21, 0x22, 0x03, iface, null, 0, TIMEOUT)
        }

        /** [n] en 4 bytes, el menos significativo primero (como lo piden los chips). */
        internal fun le32(n: Int) = ByteArray(4) { (n shr (8 * it)).toByte() }

        /** Registros del CH34x para cada velocidad (reloj de 12 MHz, prescaler y divisor). */
        internal fun ch34xBaud(baud: Int): Pair<Int, Int> = when (baud) {
            9_600 -> 0xB202 to 0x0013
            19_200 -> 0xD902 to 0x000D
            38_400 -> 0x6403 to 0x000A
            57_600 -> 0xF302 to 0x0008
            115_200 -> 0xCC03 to 0x0008
            else -> 0x6403 to 0x000A
        }

        /** Divisor del FTDI: reloj de 3 MHz, parte entera y fracción codificada en los bits altos. */
        internal fun ftdiBaud(baud: Int): Pair<Int, Int> {
            val divisor = 3_000_000.0 / baud
            val whole = divisor.toInt()
            val frac = when (((divisor - whole) * 8).toInt()) {
                0 -> 0; 1 -> 3; 2 -> 2; 3 -> 4; 4 -> 1; 5 -> 5; 6 -> 6; else -> 7
            }
            return (whole or (frac shl 14)) to 0
        }
    }
}
