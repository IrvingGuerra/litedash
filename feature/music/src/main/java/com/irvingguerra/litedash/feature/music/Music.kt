package com.irvingguerra.litedash.feature.music

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.media.MediaBrowserService
import android.service.notification.StatusBarNotification
import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import com.irvingguerra.litedash.feature.musica.ServicioMusica
import kotlinx.coroutines.delay

/** Recibe los avisos que pasa el [NotificationService] (la app solo mira los de navegación). */
interface NotificationListener {
    fun onPosted(notification: StatusBarNotification)
    fun onRemoved(notification: StatusBarNotification)
}

/** Nombre en inglés del servicio de avisos, que por compatibilidad conserva su nombre original. */
typealias NotificationService = ServicioMusica

private const val STORE = "musica"
private const val KEY_LAST_APP = "ultima_app"

/** Cuánto se espera a que la app despertada empiece a sonar antes de abrirla. */
private const val WAKE_TIMEOUT_MS = 4_000L

// Ofrecen su audio a Android pero no son apps de música (el asistente, video).
private val NOT_MUSIC_APPS = setOf(
    "com.google.android.googlequicksearchbox", "com.google.android.youtube", "com.google.android.gms",
)

/** Botón propio de la app que suena (Spotify: me gusta, aleatorio, repetir…). */
@Immutable
data class MediaAction(val id: String, val name: String, val icon: Bitmap?)


@Immutable
data class Song(
    val title: String,
    val artist: String,
    val app: String,
    val artwork: Bitmap?,
    val playing: Boolean,
    val durationMs: Long,
    val positionMs: Long,
    /** Momento (reloj de arranque) en que se leyó [positionMs], para calcular el avance sin preguntar. */
    val readAt: Long,
    val speed: Float,
    val pkg: String,
    /** Si la app deja adelantar o regresar arrastrando la barra. */
    val seekable: Boolean,
    val actions: List<MediaAction>,
) {
    fun currentPosition(): Long {
        if (!playing) return positionMs
        val progress = ((SystemClock.elapsedRealtime() - readAt) * speed).toLong()
        return (positionMs + progress).coerceAtMost(if (durationMs > 0) durationMs else Long.MAX_VALUE)
    }
}

/**
 * Lo que suena en cualquier app (Spotify, YouTube Music, la radio de la pantalla…)
 * vía las sesiones de medios de Android. Solo escucha mientras alguna pieza de
 * música está en pantalla ([use]/[release] cuentan cuántas): nada de polling.
 */
class MusicRepository(private val context: Context) {
    private val sessionManager = context.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(context, NotificationService::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var users = 0
    private var controller: MediaController? = null

    var song by mutableStateOf<Song?>(null)
        private set

    /** La app que se está despertando para que suene ("Abriendo Spotify…"); null = nada. */
    var starting by mutableStateOf<String?>(null)
        private set

    // La última app que sonó: en "Sistema", play la despierta aunque ya no esté abierta.
    private val store = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
    // Spotify avisa a cada rato: solo se escribe al disco cuando cambia la app.
    private var lastSavedApp: String? = store.getString(KEY_LAST_APP, null)

    /**
     * App de música elegida en Ajustes (paquete). null = "Sistema": lo que suene
     * en la pantalla, venga de donde venga (radio, USB, Bluetooth o una app).
     */
    var preferred: String? = null
        set(v) {
            field = v
            if (users > 0) choose(sessions())
        }

    private val icons = HashMap<String, Bitmap?>()
    // Nombres de las apps: se piden una vez a Android (es una llamada entre procesos).
    private val labels = HashMap<String, String>()

    fun hasPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= 27) {
            context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component)
        } else {
            Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                ?.contains(component.flattenToString()) == true
        }

    fun requestPermission() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private val onSessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { choose(it.orEmpty()) }

    private val onControllerChanged = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = read()
        override fun onPlaybackStateChanged(state: PlaybackState?) = read()
        override fun onSessionDestroyed() = choose(sessions())
    }

    fun use() {
        users++
        if (users == 1) start()
    }

    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) stop()
    }

    /** Llamar al volver a la app: el permiso pudo haber cambiado en Ajustes. */
    fun recheck() {
        if (users > 0 && controller == null) {
            stop()
            start()
        }
    }

    private fun start() {
        if (!hasPermission()) return
        runCatching {
            sessionManager.addOnActiveSessionsChangedListener(onSessionsChanged, component, mainHandler)
            choose(sessions())
        }
    }

    private fun stop() {
        runCatching { sessionManager.removeOnActiveSessionsChangedListener(onSessionsChanged) }
        controller?.unregisterCallback(onControllerChanged)
        controller = null
    }

    private fun sessions(): List<MediaController> =
        runCatching { sessionManager.getActiveSessions(component) }.getOrDefault(emptyList())

    // Con app elegida, solo esa. En "Sistema": la que suena; si ninguna, la más reciente
    // con canción (Android las da en ese orden), para no quedarse con sesiones vacías.
    private fun choose(list: List<MediaController>) {
        val p = preferred
        val withTitle = list.filter { it.metadata.text(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE).isNotEmpty() }
        val chosen = if (p != null) {
            list.firstOrNull { it.packageName == p }
        } else {
            withTitle.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: withTitle.firstOrNull()
                ?: list.firstOrNull()
        }
        if (chosen?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(onControllerChanged)
            controller = chosen
            chosen?.registerCallback(onControllerChanged, mainHandler)
        }
        read()
    }

    private fun read() {
        val c = controller
        val m = c?.metadata
        if (c == null) {
            song = null
            return
        }
        val state = c.playbackState
        val playing = state?.state == PlaybackState.STATE_PLAYING
        val app = appLabel(c.packageName)
        val named = m.text(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        // Sin título y en pausa = apps que solo dejan la sesión abierta: "nada sonando".
        // Sin título pero sonando = la música por Bluetooth de muchas pantallas (el
        // celular no manda la canción): se muestra con el nombre de la app y sus controles,
        // y sigue en pantalla al pausarla para poder darle play otra vez.
        if (named.isBlank() && !playing && song?.pkg != c.packageName) {
            song = null
            return
        }
        val title = named.ifBlank { app }
        starting = null
        if (playing && c.packageName != lastSavedApp) {
            lastSavedApp = c.packageName
            store.edit().putString(KEY_LAST_APP, c.packageName).apply()
        }
        song = Song(
            title = title,
            artist = m.text(MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            app = app,
            artwork = m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: m?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
            playing = playing,
            durationMs = m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0,
            positionMs = state?.position ?: 0,
            readAt = state?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
            speed = state?.playbackSpeed ?: 1f,
            pkg = c.packageName,
            seekable = (state?.actions ?: 0L) and PlaybackState.ACTION_SEEK_TO != 0L,
            actions = state?.customActions.orEmpty().map {
                MediaAction(it.action, it.name.toString(), icon(c.packageName, it.icon))
            },
        )
    }

    private fun appLabel(pkg: String) = labels.getOrPut(pkg) {
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault("")
    }

    // Los íconos de las acciones viven en los recursos de la otra app; se leen una vez.
    private fun icon(pkg: String, id: Int): Bitmap? = icons.getOrPut("$pkg:$id") {
        runCatching {
            val d = context.packageManager.getResourcesForApplication(pkg).getDrawable(id, null)
            val side = (32 * context.resources.displayMetrics.density).toInt()
            Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).also { bmp ->
                d.setBounds(0, 0, side, side)
                d.draw(Canvas(bmp))
            }
        }.getOrNull()
    }

    /** Play o pausa; sin nada sonando, despierta la app de música ([resume]). */
    fun toggle() {
        val t = controller?.transportControls
        when {
            t == null || song == null -> resume()
            song?.playing == true -> t.pause()
            else -> t.play()
        }
    }

    fun next() = controller?.transportControls?.skipToNext()
    fun previous() = controller?.transportControls?.skipToPrevious()
    fun seekTo(ms: Long) = controller?.transportControls?.seekTo(ms)
    fun action(a: MediaAction) = controller?.transportControls?.sendCustomAction(a.id, null)

    /** Nombre de la app elegida en Ajustes ("" en "Sistema"). */
    fun preferredName(): String = preferred?.let(::appLabel).orEmpty()

    /**
     * Que suene: la elegida o, en "Sistema", la última que sonó. Con una canción
     * cargada (en pausa), play directo. Si no, en este orden: (1) conectarse a su
     * servicio de música, como Android Auto (algunas apps solo lo aceptan de Android
     * Auto); (2) el botón de play por el sistema de audio, como el del volante: así
     * Android le deja arrancar la música en segundo plano (mandárselo directo o a una
     * sesión vieja lo bloquea desde Android 12); (3) si en [WAKE_TIMEOUT_MS] no suena,
     * se abre la app, que es lo que habría que hacer a mano. Mientras, [starting] dice
     * cuál se está abriendo.
     */
    fun resume() {
        val pkg = preferred ?: controller?.packageName ?: store.getString(KEY_LAST_APP, null)
        val session = controller?.takeIf { song != null && (pkg == null || it.packageName == pkg) }
        if (session != null) return session.transportControls.play()
        if (pkg == null) return systemPlay()
        starting = appLabel(pkg)
        if (!connectAndPlay(pkg)) systemPlay()
        mainHandler.postDelayed({
            if (song == null && starting != null) open(pkg)
            starting = null
        }, WAKE_TIMEOUT_MS)
    }

    /** Se conecta al servicio de música de [pkg] y le pide play; si no lo acepta, el botón del sistema. */
    private fun connectAndPlay(pkg: String): Boolean {
        val service = context.packageManager
            .queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(pkg), 0)
            .firstOrNull()?.serviceInfo ?: return false
        lateinit var browser: MediaBrowser
        browser = MediaBrowser(context, ComponentName(service.packageName, service.name), object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                runCatching { MediaController(context, browser.sessionToken).transportControls.play() }
                browser.disconnect()
            }

            override fun onConnectionFailed() = systemPlay()
        }, null)
        return runCatching { browser.connect() }.isSuccess
    }

    /** El botón de play como el del volante: Android lo lleva a la última app de música. */
    private fun systemPlay() {
        val audio = context.getSystemService(AudioManager::class.java)
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            runCatching { audio.dispatchMediaKeyEvent(KeyEvent(action, KeyEvent.KEYCODE_MEDIA_PLAY)) }
        }
    }

    /**
     * Apps de música instaladas: las que ofrecen su biblioteca a Android
     * (Spotify, Amazon Music, YouTube Music, Deezer, el reproductor de la pantalla…).
     */
    fun musicApps(): Set<String> = runCatching {
        context.packageManager.queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE), 0)
            .map { it.serviceInfo.packageName }
            .filter { it != context.packageName && it !in NOT_MUSIC_APPS }
            .toSet()
    }.getOrDefault(emptySet())

    /**
     * Lo que LiteDash ve de la música en esta pantalla, para "Copiar info": las apps
     * con servicio de música, las que aceptan el botón de play (ahí suelen estar los
     * reproductores de USB y Bluetooth) y las sesiones abiertas con su estado.
     */
    fun describe(): String = buildString {
        val pm = context.packageManager
        val buttons = runCatching {
            pm.queryBroadcastReceivers(Intent(Intent.ACTION_MEDIA_BUTTON), 0).map { it.activityInfo.packageName }.distinct()
        }.getOrDefault(emptyList())
        val sessions = if (!hasPermission()) null else sessions().map { "${it.packageName} (${it.playbackState?.state ?: "-"})" }
        appendLine("Música elegida: ${preferred ?: "Sistema"}")
        appendLine("Apps de música: ${musicApps().joinToString().ifBlank { "ninguna" }}")
        appendLine("Botón de play: ${buttons.joinToString().ifBlank { "ninguna" }}")
        append("Sesiones: ${sessions?.joinToString()?.ifBlank { "ninguna" } ?: "sin acceso a notificaciones"}")
    }

    /** Abre la app que está sonando o, si no hay, la elegida en Ajustes. */
    fun openApp() {
        open(controller?.packageName ?: preferred ?: return)
    }

    private fun open(pkg: String) {
        context.packageManager.getLaunchIntentForPackage(pkg)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?.let { runCatching { context.startActivity(it) } }
    }
}

/** El primer texto no vacío de [keys] (algunas apps mandan el título vacío y el bueno en otro campo). */
private fun MediaMetadata?.text(vararg keys: String): String =
    keys.firstNotNullOfOrNull { this?.getString(it)?.takeIf(String::isNotBlank) }.orEmpty()

/** Dónde va la canción, calculado con la hora: se actualiza una vez por segundo y solo mientras suena. */
@Composable
fun rememberPosition(song: Song): State<Long> = produceState(song.currentPosition(), song) {
    value = song.currentPosition()
    while (song.playing) {
        delay(1000)
        value = song.currentPosition()
    }
}
