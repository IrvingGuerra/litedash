package com.irvingguerra.litedash.feature.music

import java.util.Locale

/**
 * Un perfil de la radio FM de fábrica de una marca: se le habla como su propia app para ver la
 * estación, cambiarla y dar play o pausa SIN abrirla. TopWay ([TopWayRadio]) y NWD ([NwdRadio]).
 */
internal interface FactoryRadio {
    /** Nombre corto para el registro de los reportes ("topway", "nwd"). */
    val tag: String
    /** Conectado al sintonizador. */
    val open: Boolean
    /** "FM 91.1"; null si no ha dicho. */
    val station: String?
    /** Nombre RDS de la estación ("" si no hay). */
    val name: String
    /** La radio tiene el audio; null = no ha dicho. */
    val on: Boolean?
    /** Cuándo dijo la radio que tiene el audio (para confirmar un play). */
    val audioAt: Long

    /** Se conecta; false si la pantalla no lo deja. */
    fun open(): Boolean
    fun close()
    /** Vuelve a pedir o leer la estación. */
    fun refresh()
    fun next()
    fun previous()
    /** Play sin abrir su app. */
    fun takeAudio()
    /** Pausa: suelta el audio de la radio. */
    fun releaseAudio()
    /** Se dio play abriendo su app: suena hasta que la radio diga otra cosa. */
    fun started()
}

/**
 * Lo que comparten los perfiles de radio de fábrica: nombre RDS, si tiene el audio y cuándo lo
 * dijo, y cómo se avisa a la música (título = nombre RDS o la estación; abajo, la estación).
 */
internal abstract class BaseFactoryRadio(private val onState: (BroadcastMeta) -> Unit) : FactoryRadio {
    override var name = ""
        protected set
    override var on: Boolean? = null
        protected set
    override var audioAt = 0L
        protected set

    /** Avisa la estación a la música; nada si aún no dice ni frecuencia ni nombre. */
    protected fun publish() {
        val text = station
        if (text == null && name.isEmpty()) return
        onState(BroadcastMeta(title = name.ifEmpty { text }, artist = text.takeIf { name.isNotEmpty() }, album = null, playing = on ?: false, pkg = null, bluetooth = false))
    }
}

/**
 * "FM 91.1" o "AM 1220"; null sin frecuencia. FM en centésimas de MHz (= decenas de kHz: 9110 = 91.1),
 * AM en kHz. Cada marca dice cuándo es AM (TopWay: banda 2; NWD: banda 3 o más).
 */
internal fun stationText(am: Boolean, frequency: Int): String? = when {
    frequency <= 0 -> null
    am -> "AM $frequency"
    else -> "FM " + String.format(Locale.US, "%.1f", frequency / 100f)
}
