package com.irvingguerra.litedash.feature.music

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
