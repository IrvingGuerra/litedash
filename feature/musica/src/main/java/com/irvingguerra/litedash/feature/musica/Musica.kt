package com.irvingguerra.litedash.feature.musica

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Vacío a propósito: solo sirve para que el usuario dé "acceso a notificaciones". */
class ServicioMusica : NotificationListenerService()

@Immutable
data class Cancion(
    val titulo: String,
    val artista: String,
    val app: String,
    val caratula: Bitmap?,
    val sonando: Boolean,
    val duracionMs: Long,
    val posicionMs: Long,
    /** Momento (reloj de arranque) en que se leyó [posicionMs], para calcular el avance sin preguntar. */
    val leidaEn: Long,
    val velocidad: Float,
) {
    fun posicionAhora(): Long {
        if (!sonando) return posicionMs
        val avance = ((SystemClock.elapsedRealtime() - leidaEn) * velocidad).toLong()
        return (posicionMs + avance).coerceAtMost(if (duracionMs > 0) duracionMs else Long.MAX_VALUE)
    }
}

/**
 * Lo que suena en cualquier app (Spotify, YouTube Music, la radio de la pantalla…)
 * vía las sesiones de medios de Android. Solo escucha mientras alguna pieza de
 * música está en pantalla ([usar]/[soltar] cuentan cuántas): nada de polling.
 */
class RepositorioMusica(private val contexto: Context) {
    private val gestor = contexto.getSystemService(MediaSessionManager::class.java)
    private val componente = ComponentName(contexto, ServicioMusica::class.java)
    private val principal = Handler(Looper.getMainLooper())
    private var usuarios = 0
    private var controlador: MediaController? = null

    var cancion by mutableStateOf<Cancion?>(null)
        private set

    fun tienePermiso(): Boolean =
        if (Build.VERSION.SDK_INT >= 27) {
            contexto.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(componente)
        } else {
            Settings.Secure.getString(contexto.contentResolver, "enabled_notification_listeners")
                ?.contains(componente.flattenToString()) == true
        }

    fun pedirPermiso() {
        val intento = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { contexto.startActivity(intento) }
    }

    private val alCambiarSesiones = MediaSessionManager.OnActiveSessionsChangedListener { elegir(it.orEmpty()) }

    private val alCambiarControlador = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = leer()
        override fun onPlaybackStateChanged(state: PlaybackState?) = leer()
        override fun onSessionDestroyed() = elegir(sesiones())
    }

    fun usar() {
        usuarios++
        if (usuarios == 1) iniciar()
    }

    fun soltar() {
        usuarios = (usuarios - 1).coerceAtLeast(0)
        if (usuarios == 0) detener()
    }

    /** Llamar al volver a la app: el permiso pudo haber cambiado en Ajustes. */
    fun revisar() {
        if (usuarios > 0 && controlador == null) {
            detener()
            iniciar()
        }
    }

    private fun iniciar() {
        if (!tienePermiso()) return
        runCatching {
            gestor.addOnActiveSessionsChangedListener(alCambiarSesiones, componente, principal)
            elegir(sesiones())
        }
    }

    private fun detener() {
        runCatching { gestor.removeOnActiveSessionsChangedListener(alCambiarSesiones) }
        controlador?.unregisterCallback(alCambiarControlador)
        controlador = null
    }

    private fun sesiones(): List<MediaController> =
        runCatching { gestor.getActiveSessions(componente) }.getOrDefault(emptyList())

    // La que está sonando; si ninguna, la más reciente (Android las da en ese orden).
    private fun elegir(lista: List<MediaController>) {
        val nuevo = lista.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: lista.firstOrNull()
        if (nuevo?.sessionToken != controlador?.sessionToken) {
            controlador?.unregisterCallback(alCambiarControlador)
            controlador = nuevo
            nuevo?.registerCallback(alCambiarControlador, principal)
        }
        leer()
    }

    private fun leer() {
        val c = controlador
        val m = c?.metadata
        if (c == null || m == null) {
            cancion = null
            return
        }
        val titulo = m.getString(MediaMetadata.METADATA_KEY_TITLE) ?: m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).orEmpty()
        // Sesiones sin título (apps que solo dejan la sesión abierta) cuentan como "nada sonando".
        if (titulo.isBlank()) {
            cancion = null
            return
        }
        val estado = c.playbackState
        val app = runCatching {
            val pm = contexto.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(c.packageName, 0)).toString()
        }.getOrDefault("")
        cancion = Cancion(
            titulo = titulo,
            artista = m.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty(),
            app = app,
            caratula = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: m.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
            sonando = estado?.state == PlaybackState.STATE_PLAYING,
            duracionMs = m.getLong(MediaMetadata.METADATA_KEY_DURATION),
            posicionMs = estado?.position ?: 0,
            leidaEn = estado?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
            velocidad = estado?.playbackSpeed ?: 1f,
        )
    }

    fun alternar() {
        val t = controlador?.transportControls ?: return
        if (cancion?.sonando == true) t.pause() else t.play()
    }

    fun siguiente() = controlador?.transportControls?.skipToNext()
    fun anterior() = controlador?.transportControls?.skipToPrevious()

    /** Abre la app que está sonando. */
    fun abrirApp() {
        val paquete = controlador?.packageName ?: return
        contexto.packageManager.getLaunchIntentForPackage(paquete)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?.let { runCatching { contexto.startActivity(it) } }
    }
}
