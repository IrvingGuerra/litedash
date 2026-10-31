package com.irvingguerra.litedash.feature.musica

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
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
import android.service.notification.StatusBarNotification
import android.view.KeyEvent
import java.lang.ref.WeakReference
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Con "acceso a notificaciones" Android deja leer qué suena en otras apps. Además
 * pasa los avisos al [oyente] (la app solo mira los de navegación, para la
 * siguiente vuelta); sin oyente no hace nada. El nombre se queda: cambiarlo
 * le quitaría el permiso a quien ya lo dio.
 */
class ServicioMusica : NotificationListenerService() {
    interface Oyente {
        fun alPublicar(aviso: StatusBarNotification)
        fun alQuitar(aviso: StatusBarNotification)
    }

    override fun onListenerConnected() {
        conectado = WeakReference(this)
        activas().forEach { oyente?.alPublicar(it) }
    }

    override fun onListenerDisconnected() {
        conectado = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null) oyente?.alPublicar(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn != null) oyente?.alQuitar(sbn)
    }

    companion object {
        @Volatile var oyente: Oyente? = null
        @Volatile private var conectado: WeakReference<ServicioMusica>? = null

        /** Avisos que hay ahora mismo (vacío si el servicio no está conectado). */
        fun activas(): List<StatusBarNotification> =
            runCatching { conectado?.get()?.activeNotifications?.toList() }.getOrNull().orEmpty()
    }
}

/** Botón propio de la app que suena (Spotify: me gusta, aleatorio, repetir…). */
@Immutable
data class AccionMedios(val id: String, val nombre: String, val icono: Bitmap?)

/** Una app con sesión de medios abierta. */
@Immutable
data class Fuente(val paquete: String, val nombre: String)

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
    val paquete: String,
    /** Si la app deja adelantar o regresar arrastrando la barra. */
    val adelantable: Boolean,
    val acciones: List<AccionMedios>,
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

    /** Apps con sesión abierta, para cambiar cuál se controla. */
    var fuentes by mutableStateOf(emptyList<Fuente>())
        private set

    // App que el usuario eligió controlar; manda mientras siga teniendo sesión.
    private var fijada: String? = null
    private val iconos = HashMap<String, Bitmap?>()

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

    // La elegida por el usuario; si no, la que está sonando; si ninguna, la más reciente (Android las da en ese orden).
    private fun elegir(lista: List<MediaController>) {
        fuentes = lista.distinctBy { it.packageName }.map { Fuente(it.packageName, etiqueta(it.packageName)) }
        val nuevo = lista.firstOrNull { it.packageName == fijada }
            ?: lista.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: lista.firstOrNull()
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
        val app = etiqueta(c.packageName)
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
            paquete = c.packageName,
            adelantable = (estado?.actions ?: 0L) and PlaybackState.ACTION_SEEK_TO != 0L,
            acciones = estado?.customActions.orEmpty().map {
                AccionMedios(it.action, it.name.toString(), icono(c.packageName, it.icon))
            },
        )
    }

    private fun etiqueta(paquete: String) = runCatching {
        val pm = contexto.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(paquete, 0)).toString()
    }.getOrDefault("")

    // Los íconos de las acciones viven en los recursos de la otra app; se leen una vez.
    private fun icono(paquete: String, id: Int): Bitmap? = iconos.getOrPut("$paquete:$id") {
        runCatching {
            val d = contexto.packageManager.getResourcesForApplication(paquete).getDrawable(id, null)
            val lado = (32 * contexto.resources.displayMetrics.density).toInt()
            Bitmap.createBitmap(lado, lado, Bitmap.Config.ARGB_8888).also { bmp ->
                d.setBounds(0, 0, lado, lado)
                d.draw(android.graphics.Canvas(bmp))
            }
        }.getOrNull()
    }

    fun alternar() {
        val t = controlador?.transportControls ?: return
        if (cancion?.sonando == true) t.pause() else t.play()
    }

    fun siguiente() = controlador?.transportControls?.skipToNext()
    fun anterior() = controlador?.transportControls?.skipToPrevious()
    fun adelantar(ms: Long) = controlador?.transportControls?.seekTo(ms)
    fun accion(a: AccionMedios) = controlador?.transportControls?.sendCustomAction(a.id, null)

    /** Controlar otra app de las que tienen sesión abierta. */
    fun elegirFuente(paquete: String) {
        fijada = paquete
        elegir(sesiones())
    }

    /**
     * Sin nada sonando: le pide a Android que reanude lo último que sonó (como el
     * botón de play del volante), sin abrir la app. Spotify sigue en segundo plano.
     */
    fun reanudar() {
        val audio = contexto.getSystemService(AudioManager::class.java)
        for (accion in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            runCatching { audio.dispatchMediaKeyEvent(KeyEvent(accion, KeyEvent.KEYCODE_MEDIA_PLAY)) }
        }
    }

    /** Abre la app que está sonando. */
    fun abrirApp() {
        val paquete = controlador?.packageName ?: return
        contexto.packageManager.getLaunchIntentForPackage(paquete)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?.let { runCatching { contexto.startActivity(it) } }
    }
}
