package com.irvingguerra.litedash.feature.music

import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import java.lang.reflect.Method

/**
 * La radio FM de las pantallas TopWay (TS7, TS10, TS18; app de fábrica `com.tw.radio`), que no
 * publica sesión ni receptor del botón de play. Se le habla como su propia app: por
 * `android.tw.john.TWUtil`, el enlace del firmware con el sintonizador (por reflexión: en otras
 * pantallas no existe). Códigos de ivvlev/CarRadio y OpenRath (GPL). Da la estación (frecuencia y
 * nombre RDS), siguiente/anterior y play/pausa sin abrir su app: se le pide el audio como lo hace
 * ella al abrirse ("actividad" 0x9E00, fuente de audio 769 y foco 0x9E11, como ivvlev/CarRadio,
 * dipcore/Radio-MST768 y Orbit). Lo que dice el sintonizador va al registro para los reportes.
 */
internal class TopWayRadio(onState: (BroadcastMeta) -> Unit, private val trace: (String) -> Unit) : BaseFactoryRadio(onState) {
    override val tag = "topway"
    private var util: Any? = null
    private var write3: Method? = null
    private var write2: Method? = null

    /** FM en centésimas de MHz (9110 = 91.1), AM en kHz; 0 = no ha dicho. */
    private var frequency = 0
    private var band = 0
    /** "FM 91.1"; null si no ha dicho. */
    override val station get() = stationText(band == BAND_AM, frequency)
    // audioAt: cuándo dijo el sintonizador que tiene el audio (769 = 1), para confirmar un play. La
    // "actividad" no sirve: el enlace reparte lo que se escribe, así que llegaría la nuestra.
    /** El número de la radio en "actividad" y fuente: 1 en las TS18 (ivvlev/CarRadio); se aprende de lo que manda. */
    private var source = SOURCE_RADIO
    private val seen = HashSet<Int>()

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(m: Message) {
            // La primera vez de cada mensaje (y lo del audio siempre) al registro: así se aprende qué manda cada pantalla.
            // Las presintonías (1026, una por botón) no: llenaban el registro.
            if ((m.what != STATION && seen.add(m.what * 1000 + m.arg1)) || m.what == AUDIO || m.what == ACTIVITY) trace("topway ${m.what} ${m.arg1} ${m.arg2}${(m.obj as? String)?.let { " \"$it\"" } ?: ""}")
            when {
                m.what == RADIO && m.arg1 == 1 -> band = m.arg2
                m.what == RADIO && m.arg1 == 2 -> frequency = m.arg2
                m.what == RDS -> name = (m.obj as? String)?.trim().orEmpty()
                m.what == AUDIO -> {
                    on = m.arg1 == 1
                    if (on == true) audioAt = SystemClock.elapsedRealtime()
                }
                // Actividad: el número de la radio al entrar (1 o 0x0A) y con 0x80 al salir (0x81 o 0x8A).
                m.what == ACTIVITY -> {
                    val radio = m.arg1 == SOURCE_RADIO || m.arg1 == SOURCE_RADIO_ALT
                    if (radio) source = m.arg1
                    on = radio
                }
                else -> return
            }
            publish()
        }
    }

    override val open get() = util != null

    /** Se conecta al sintonizador y le pide la estación. False si la pantalla no lo deja. */
    override fun open(): Boolean {
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

    override fun close() {
        val u = util ?: return
        util = null
        handler.removeCallbacksAndMessages(null)
        runCatching { u.javaClass.getMethod("stop").invoke(u) }
        runCatching { u.javaClass.getMethod("close").invoke(u) }
    }

    /** Pide banda, frecuencia y nombre (llegan como mensajes). */
    override fun refresh() {
        write(RADIO, 255, 0)
        write(RDS, 255, 0)
    }

    override fun next() = write(RADIO, 1, 0)
    override fun previous() = write(RADIO, 1, 1)

    /** Play sin abrir su app: pide el audio para la radio, como ella al abrirse, y la estación. */
    override fun takeAudio() {
        activity(source)
        // Fuente de audio y foco, como dipcore/Radio-MST768 y Orbit (TS18).
        write(AUDIO, 0xC0, source)
        write(FOCUS, 0xC0, source)
        refresh()
    }

    /** Pausa: suelta el audio de la radio, como su app al cerrarse. */
    override fun releaseAudio() {
        activity(source or RELEASE)
        write(FOCUS, 0xC0, source or RELEASE)
        on = false
        publish()
    }

    /**
     * La música del Bluetooth de TopWay (`com.tw.bt`, con chip propio): el MCU solo la deja pasar a las bocinas
     * con su fuente (8), y eso lo pide la app de fábrica al entrar a su Música. Se pide igual que ella: actividad,
     * fuente de audio y foco (visto en el Lancer de Irving, 2026-11-13: `40448 8`, `769 8`).
     */
    fun takeBluetooth() {
        activity(SOURCE_BT)
        write(AUDIO, 0xC0, SOURCE_BT)
        write(FOCUS, 0xC0, SOURCE_BT)
        trace("topway bluetooth: fuente $SOURCE_BT")
    }

    /** Suelta la fuente del Bluetooth (como la radio al pausar): el MCU regresa al audio de Android. */
    fun releaseBluetooth() {
        activity(SOURCE_BT or RELEASE)
        write(FOCUS, 0xC0, SOURCE_BT or RELEASE)
        trace("topway bluetooth: suelta")
    }

    /** La "actividad" va con write de dos números (como la radio de fábrica); si no existe, con tres. */
    private fun activity(value: Int) {
        val u = util ?: return
        write2?.let { m -> runCatching { m.invoke(u, ACTIVITY, value) }.onFailure { trace("topway write $ACTIVITY falló") } } ?: write(ACTIVITY, value, 0)
    }

    /** Se dio play abriendo su app: suena hasta que el sintonizador diga otra cosa. */
    override fun started() {
        on = true
        publish()
        refresh()
    }

    private fun write(what: Int, a1: Int, a2: Int) {
        val u = util ?: return
        runCatching { write3?.invoke(u, what, a1, a2) }.onFailure { trace("topway write $what falló") }
    }

    companion object {
        private const val CLASS = "android.tw.john.TWUtil"
        private const val RADIO = 1025
        private const val STATION = 1026
        private const val RDS = 1028
        private const val AUDIO = 769
        private const val ACTIVITY = 0x9E00
        private const val FOCUS = 0x9E11
        private const val SOURCE_RADIO = 1
        /** El número de la radio en otras TopWay (OpenRath). */
        private const val SOURCE_RADIO_ALT = 0x0A
        /** La música del Bluetooth de fábrica (`com.tw.bt`). */
        private const val SOURCE_BT = 8
        private const val RELEASE = 0x80
        private const val BAND_AM = 2

        /** La pantalla trae el enlace de TopWay. */
        val available: Boolean by lazy { runCatching { Class.forName(CLASS) }.isSuccess }
    }
}
