// El paquete y el nombre de la clase NO se traducen: Android guarda el acceso a
// notificaciones con el nombre completo del componente
// (com.irvingguerra.litedash/com.irvingguerra.litedash.feature.musica.ServicioMusica).
// Cambiarlo le quitaría el permiso a quien ya lo dio. En el código se usa como
// [com.irvingguerra.litedash.feature.music.NotificationService].
package com.irvingguerra.litedash.feature.musica

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.irvingguerra.litedash.feature.music.NotificationListener
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Lo que se le da a Android para conectar el servicio por su cuenta antes de pedírselo ([ServicioMusica.rebindIfNeeded]). */
private const val REBIND_GRACE_MS = 5_000L
/** Entre dos peticiones de reconectar: si el firmware lo suelta y lo reconecta en bucle, no se le pide sin freno. */
private const val REBIND_MIN_GAP_MS = 60_000L

/**
 * Con "acceso a notificaciones" Android deja leer qué suena en otras apps. Además
 * pasa los avisos al [listener] (la app solo mira los de navegación, para la
 * siguiente vuelta) y a [media] (los de las apps de Bluetooth y radio); si nadie
 * los quiere ([NotificationListener.wanted]), no hace nada: Android lo conecta aunque la app no esté abierta y ahí no debe armarla.
 * La pieza de navegación y la música piden los avisos que ya hay ([active]).
 */
class ServicioMusica : NotificationListenerService() {
    override fun onListenerConnected() {
        connected = WeakReference(this)
        media?.takeIf { it.wanted }?.onConnected()
        val l = listener?.takeIf { it.wanted } ?: return
        active().forEach(l::onPosted)
    }

    override fun onListenerDisconnected() {
        connected = null
        // Android lo soltó con el acceso dado: se le pide que lo vuelva a conectar (sin acceso, Android lo ignora).
        rebind(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        for (l in wanted()) l.onPosted(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        for (l in wanted()) l.onRemoved(sbn)
    }

    private fun wanted() = listOfNotNull(listener, media).filter { it.wanted }

    companion object {
        @Volatile var listener: NotificationListener? = null
        /** La música: mira los avisos de las apps de Bluetooth y radio enlazadas (mientras hay una pieza en pantalla). */
        @Volatile var media: NotificationListener? = null
        @Volatile private var connected: WeakReference<ServicioMusica>? = null
        private val rebindAsked = AtomicBoolean(false)
        /** Cuándo se pidió reconectar la última vez (reloj de arranque; 0 = nunca). */
        private val rebindAt = AtomicLong(0L)

        /** ¿Android tiene conectado el servicio ahora? */
        val isConnected: Boolean get() = connected?.get() != null

        /**
         * Con el acceso dado, algunas pantallas no conectan el servicio: se le pide a Android una vez por
         * arranque, cuando la música empieza a escuchar, y solo si unos segundos después sigue sin conectar (al arrancar el
         * proceso Android lo conecta solo: pedirlo antes era de más).
         */
        fun rebindIfNeeded(context: Context) {
            if (isConnected || !rebindAsked.compareAndSet(false, true)) return
            val app = context.applicationContext
            Handler(Looper.getMainLooper()).postDelayed({ if (!isConnected) rebind(app) }, REBIND_GRACE_MS)
        }

        /** Pide reconectar, a lo más una vez por minuto. */
        private fun rebind(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
            val now = SystemClock.elapsedRealtime()
            val last = rebindAt.get()
            if (last != 0L && now - last < REBIND_MIN_GAP_MS || !rebindAt.compareAndSet(last, now)) return
            runCatching { requestRebind(ComponentName(context, ServicioMusica::class.java)) }
        }

        /** Avisos que hay ahora mismo (vacío si el servicio no está conectado). */
        fun active(): List<StatusBarNotification> =
            runCatching { connected?.get()?.activeNotifications?.toList() }.getOrNull().orEmpty()
    }
}
