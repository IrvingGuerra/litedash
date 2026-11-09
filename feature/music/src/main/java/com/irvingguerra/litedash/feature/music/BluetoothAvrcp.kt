package com.irvingguerra.litedash.feature.music

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.service.media.MediaBrowserService

/**
 * El Bluetooth de Android como receptor (A2DP *sink*): el propio sistema trae un
 * `MediaBrowserService` (`…avrcpcontroller.BluetoothMediaBrowserService`) exportado y sin permiso.
 * Al conectarse, LiteDash recibe título, artista, álbum, duración y estado, y manda
 * play/pausa/siguiente/anterior (AVRCP hacia el celular), sin app externa ni acceso a
 * notificaciones. Así lo hacen Car Media y FytBt.
 *
 * El paquete cambia con la versión de Android (desde la 13 el Bluetooth es módulo Mainline), así
 * que el servicio se busca por el nombre de la clase, no por el paquete.
 */
internal class BluetoothAvrcp(
    private val context: Context,
    /** El servicio AVRCP que encontró [find] (sin él no se crea). */
    val service: ServiceInfo,
    /** El controlador de la música del celular (o null si no se pudo conectar). */
    private val onController: (MediaController?) -> Unit,
) {
    private var browser: MediaBrowser? = null

    /** Conecta (si aún no) y avisa con el controlador; una vez conectado, no hace nada. */
    fun connect() {
        if (browser != null) return
        val component = ComponentName(service.packageName, service.name)
        lateinit var b: MediaBrowser
        b = MediaBrowser(context, component, object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                onController(runCatching { MediaController(context, b.sessionToken) }.getOrNull())
            }

            // Si falla o se cae, se suelta: el siguiente connect() vuelve a intentar.
            override fun onConnectionFailed() = lost(b)
            override fun onConnectionSuspended() = lost(b)
        }, null)
        browser = b
        if (runCatching { b.connect() }.isFailure) browser = null
    }

    private fun lost(b: MediaBrowser) {
        if (browser === b) browser = null
        onController(null)
    }

    /** Se desconecta (al esconderse la música). */
    fun disconnect() {
        val b = browser ?: return
        browser = null
        runCatching { b.disconnect() }
    }

    companion object {
        private const val AVRCP_CLASS = "avrcpcontroller.BluetoothMediaBrowserService"

        /** El servicio AVRCP del Bluetooth de Android, por la clase (el paquete cambia con la versión). */
        fun find(context: Context): ServiceInfo? = runCatching {
            context.packageManager.queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE), 0)
                .firstOrNull { it.serviceInfo.name.contains(AVRCP_CLASS) }
                ?.serviceInfo
        }.getOrNull()
    }
}
