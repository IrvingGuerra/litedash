package com.irvingguerra.litedash.feature.music

import android.service.notification.StatusBarNotification
import com.irvingguerra.litedash.feature.musica.ServicioMusica

/** Recibe los avisos que pasa el [NotificationService] (la app solo mira los de navegación). */
interface NotificationListener {
    /**
     * Alguien los quiere ahora (la app ya está armada). Si no, el servicio no pasa nada: Android
     * lo conecta solo (también tras forzar el cierre) y no hay por qué armar la app por un aviso.
     */
    val wanted: Boolean
    fun onPosted(notification: StatusBarNotification)
    fun onRemoved(notification: StatusBarNotification)

    /** Android acaba de conectar el servicio: ya se pueden pedir los avisos que hay ([NotificationService.active]). */
    fun onConnected() = Unit
}

/** Nombre en inglés del servicio de avisos, que por compatibilidad conserva su nombre original. */
typealias NotificationService = ServicioMusica
