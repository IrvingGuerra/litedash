// El paquete y el nombre de la clase NO se traducen: Android guarda el acceso a
// notificaciones con el nombre completo del componente
// (com.irvingguerra.litedash/com.irvingguerra.litedash.feature.musica.ServicioMusica).
// Cambiarlo le quitaría el permiso a quien ya lo dio. En el código se usa como
// [com.irvingguerra.litedash.feature.music.NotificationService].
package com.irvingguerra.litedash.feature.musica

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.irvingguerra.litedash.feature.music.NotificationListener
import java.lang.ref.WeakReference

/**
 * Con "acceso a notificaciones" Android deja leer qué suena en otras apps. Además
 * pasa los avisos al [listener] (la app solo mira los de navegación, para la
 * siguiente vuelta); sin listener, o si no los quiere ([NotificationListener.wanted]),
 * no hace nada: Android lo conecta aunque la app no esté abierta y ahí no debe armarla.
 * La pieza de navegación pide los avisos que ya hay ([active]) al aparecer.
 */
class ServicioMusica : NotificationListenerService() {
    override fun onListenerConnected() {
        connected = WeakReference(this)
        val l = listener?.takeIf { it.wanted } ?: return
        active().forEach(l::onPosted)
    }

    override fun onListenerDisconnected() {
        connected = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val l = listener?.takeIf { it.wanted } ?: return
        if (sbn != null) l.onPosted(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val l = listener?.takeIf { it.wanted } ?: return
        if (sbn != null) l.onRemoved(sbn)
    }

    companion object {
        @Volatile var listener: NotificationListener? = null
        @Volatile private var connected: WeakReference<ServicioMusica>? = null

        /** Avisos que hay ahora mismo (vacío si el servicio no está conectado). */
        fun active(): List<StatusBarNotification> =
            runCatching { connected?.get()?.activeNotifications?.toList() }.getOrNull().orEmpty()
    }
}
