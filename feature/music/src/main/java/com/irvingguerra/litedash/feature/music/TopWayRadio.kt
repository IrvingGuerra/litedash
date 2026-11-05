package com.irvingguerra.litedash.feature.music

import android.os.Handler
import android.os.Looper
import android.os.Message
import java.lang.reflect.Method
import java.util.Locale

/**
 * La radio FM de las pantallas TopWay (TS7, TS10, TS18; app de fábrica `com.tw.radio`), que no
 * publica sesión ni receptor del botón de play. Se le habla como su propia app: por
 * `android.tw.john.TWUtil`, el enlace del firmware con el sintonizador (por reflexión: en otras
 * pantallas no existe). Códigos de ivvlev/CarRadio y OpenRath (GPL). Da la estación (frecuencia y
 * nombre RDS) y siguiente/anterior; la radio solo suena con su app abierta, así que play la sigue
 * abriendo. Lo que dice el sintonizador va al registro para los reportes.
 */
internal class TopWayRadio(private val onState: (BroadcastMeta) -> Unit, private val trace: (String) -> Unit) {
    private var util: Any? = null
    private var write3: Method? = null
    private var write2: Method? = null

    /** FM en centésimas de MHz (9110 = 91.1), AM en kHz; 0 = no ha dicho. */
    private var frequency = 0
    private var band = 0
    /** "FM 91.1"; null si no ha dicho. */
    val station get() = stationText(band, frequency)
    var name = ""
        private set
    /** El sintonizador tiene el audio; null = no ha dicho. */
    var on: Boolean? = null
        private set
    private val seen = HashSet<Int>()

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(m: Message) {
            // La primera vez de cada mensaje (y lo del audio siempre) al registro: así se aprende qué manda cada pantalla.
            if (seen.add(m.what * 1000 + m.arg1) || m.what == AUDIO || m.what == ACTIVITY) trace("topway ${m.what} ${m.arg1} ${m.arg2}${(m.obj as? String)?.let { " \"$it\"" } ?: ""}")
            when {
                m.what == RADIO && m.arg1 == 1 -> band = m.arg2
                m.what == RADIO && m.arg1 == 2 -> frequency = m.arg2
                m.what == RDS -> name = (m.obj as? String)?.trim().orEmpty()
                m.what == AUDIO || m.what == ACTIVITY -> on = m.arg1 == 1
                else -> return
            }
            publish()
        }
    }

    val open get() = util != null

    /** Se conecta al sintonizador y le pide la estación. False si la pantalla no lo deja. */
    fun open(): Boolean {
        if (util != null) return true
        return runCatching {
            val cls = Class.forName(CLASS)
            val u = runCatching { cls.getConstructor(Int::class.javaPrimitiveType).newInstance(1) }.getOrElse { cls.getConstructor().newInstance() }
            // Pocos códigos por conexión: con muchos, la app de la pantalla se cae.
            val codes = intArrayOf(RADIO, STATION, RDS, AUDIO, ACTIVITY).map { it.toShort() }.toShortArray()
            val result = cls.getMethod("open", ShortArray::class.java).invoke(u, codes) as Int
            check(result == 0) { "open = $result" }
            cls.getMethod("start").invoke(u)
            // Algunas pantallas solo entregan los mensajes de la radio a quien se llame "radio".
            cls.getMethod("addHandler", String::class.java, Handler::class.java).invoke(u, "radio", handler)
            write3 = cls.getMethod("write", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            write2 = runCatching { cls.getMethod("write", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType) }.getOrNull()
            util = u
            trace("topway abierto")
            refresh()
            true
        }.getOrElse {
            trace("topway no abre: ${it.cause?.message ?: it.message ?: it.javaClass.simpleName}")
            close()
            false
        }
    }

    fun close() {
        val u = util ?: return
        util = null
        handler.removeCallbacksAndMessages(null)
        runCatching { u.javaClass.getMethod("stop").invoke(u) }
        runCatching { u.javaClass.getMethod("close").invoke(u) }
    }

    /** Pide banda, frecuencia y nombre (llegan como mensajes). */
    fun refresh() {
        write(RADIO, 255, 0)
        write(RDS, 255, 0)
    }

    fun next() = write(RADIO, 1, 0)
    fun previous() = write(RADIO, 1, 1)

    /** Suelta (pausa) o toma el audio para la radio, como su app al cerrarse o abrirse. */
    fun audio(take: Boolean) {
        val source = if (take) SOURCE_RADIO else SOURCE_RELEASE
        write2?.let { m -> util?.let { runCatching { m.invoke(it, ACTIVITY, source) } } } ?: write(ACTIVITY, source, 0)
        write(FOCUS, 0xC0, source)
        on = take
        publish()
    }

    /** Se dio play abriendo su app: suena hasta que el sintonizador diga otra cosa. */
    fun started() {
        on = true
        publish()
        refresh()
    }

    private fun write(what: Int, a1: Int, a2: Int) {
        val u = util ?: return
        runCatching { write3?.invoke(u, what, a1, a2) }.onFailure { trace("topway write $what falló") }
    }

    private fun publish() {
        val text = station
        if (text == null && name.isEmpty()) return
        onState(BroadcastMeta(title = name.ifEmpty { text }, artist = text.takeIf { name.isNotEmpty() }, album = null, playing = on ?: false, pkg = null, bluetooth = false))
    }

    companion object {
        private const val CLASS = "android.tw.john.TWUtil"
        private const val RADIO = 1025
        private const val STATION = 1026
        private const val RDS = 1028
        private const val AUDIO = 769
        private const val ACTIVITY = 0x9E00
        private const val FOCUS = 0x9E11
        private const val SOURCE_RADIO = 0x0A
        private const val SOURCE_RELEASE = 0x8A

        /** La pantalla trae el enlace de TopWay. */
        val available: Boolean by lazy { runCatching { Class.forName(CLASS) }.isSuccess }

        /** "FM 91.1" o "AM 1220"; null sin frecuencia. */
        internal fun stationText(band: Int, frequency: Int): String? = when {
            frequency <= 0 -> null
            band == 2 -> "AM $frequency"
            else -> "FM " + String.format(Locale.US, "%.1f", frequency / 100f)
        }
    }
}
