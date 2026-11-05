package android.tw.john

import android.os.Handler

/**
 * SOLO EN DEBUG: imitación del enlace de las pantallas TopWay para probar el perfil de la radio en
 * el emulador (frecuencia, nombre RDS, siguiente/anterior y audio). En la APK publicada no existe.
 */
@Suppress("unused", "UNUSED_PARAMETER") // se llama por reflexión, con la firma del firmware
class TWUtil(type: Int) {
    private var handler: Handler? = null
    private var frequency = 9110
    private var on = false

    fun open(codes: ShortArray): Int = 0
    fun start() = Unit
    fun stop() = Unit
    fun close() = Unit

    fun addHandler(tag: String, h: Handler) {
        handler = h
    }

    fun write(what: Int, a1: Int): Int = write(what, a1, 0)

    fun write(what: Int, a1: Int, a2: Int): Int {
        when {
            what == 1025 && a1 == 255 -> {
                send(1025, 1, 0)
                send(1025, 2, frequency)
            }
            what == 1028 && a1 == 255 -> send(1028, 0, 0, "LiteDash FM $frequency")
            what == 1025 && a1 == 1 -> {
                frequency += if (a2 == 0) 20 else -20
                send(1025, 2, frequency)
                send(1028, 0, 0, "LiteDash FM $frequency")
            }
            what == 0x9E00 -> {
                on = a1 == 0x0A
                send(769, if (on) 1 else 0, 0)
            }
        }
        return 0
    }

    private fun send(what: Int, a1: Int, a2: Int, obj: Any? = null) {
        handler?.let { it.sendMessage(it.obtainMessage(what, a1, a2, obj)) }
    }
}
