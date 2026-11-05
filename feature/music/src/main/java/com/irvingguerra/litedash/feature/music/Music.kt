package com.irvingguerra.litedash.feature.music

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Environment
import android.os.Looper
import android.os.SystemClock
import android.os.storage.StorageManager
import android.provider.MediaStore
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
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.irvingguerra.litedash.feature.musica.ServicioMusica
import kotlinx.coroutines.delay
import java.io.File

/** Recibe los avisos que pasa el [NotificationService] (la app solo mira los de navegación). */
interface NotificationListener {
    /**
     * Alguien los quiere ahora (la app ya está armada). Si no, el servicio no pasa nada: Android
     * lo conecta solo (también tras forzar el cierre) y no hay por qué armar la app por un aviso.
     */
    val wanted: Boolean
    fun onPosted(notification: StatusBarNotification)
    fun onRemoved(notification: StatusBarNotification)
}

/** Nombre en inglés del servicio de avisos, que por compatibilidad conserva su nombre original. */
typealias NotificationService = ServicioMusica

private const val STORE = "music"
private const val KEY_LAST_APP = "last_app"
/** Ya se intentó adivinar las apps de las fuentes (una sola vez, al estrenar la pieza). */
private const val KEY_SOURCES_GUESSED = "sources_guessed"
/** La pieza Radio prefiere la de internet (true) o la FM de la pantalla. */
private const val KEY_RADIO_INTERNET = "radio_internet"

/** Palabras con las que se reconoce la app de cada fuente (en el paquete o en el nombre). */
private val SOURCE_WORDS = mapOf(
    SourceKind.BLUETOOTH to listOf("bluetooth", "bt", "a2dp"),
    SourceKind.RADIO to listOf("radio", "fm"),
)

/** Cuánto se espera a que la app despertada empiece a sonar antes de abrirla. */
private const val WAKE_TIMEOUT_MS = 4_000L
/** Tras despertar una app sin sesión, play/pausa es tecla de volante durante este rato. */
private const val KEYS_WINDOW_MS = 30 * 60_000L
/** Un aviso (broadcast) de qué suena vale como respaldo durante este rato. */
private const val BROADCAST_FRESH_MS = 10 * 60_000L

/** Perfil Bluetooth "A2DP sink" (la pantalla recibe la música del celular); no es público en el SDK. */
private const val A2DP_SINK = 11

// Ofrecen su audio a Android pero no son apps de música (el asistente, video).
private val NOT_MUSIC_APPS = setOf(
    "com.google.android.googlequicksearchbox", "com.google.android.youtube", "com.google.android.gms",
)

/**
 * Fuentes de música, como en un estéreo: USB es siempre el reproductor propio de LiteDash;
 * Bluetooth y la radio FM son apps del sistema de la pantalla que el usuario enlaza en Ajustes
 * (los paquetes cambian con la marca: `com.microntek.bt`, `com.ts.radio`…). La radio también
 * puede ser la de internet de LiteDash.
 */
enum class SourceKind { USB, BLUETOOTH, RADIO }

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
    /** Fuente de la pantalla a la que pertenece la app ([pkg]); null = una app normal. */
    val source: SourceKind?,
    /** Conectando o cargando (la radio tarda unos segundos). */
    val buffering: Boolean,
    /** Lo que la app dice que falló; null si nada. */
    val error: String?,
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

    /** Lo que ve la pieza Música (todo menos la radio). Atajo de [music]. */
    val song: Song? get() = music.song

    /** La app que la pieza Música está despertando ("Abriendo Spotify…"); null = nada. Atajo de [music]. */
    val starting: String? get() = music.starting

    /** Nombre del celular conectado por Bluetooth a la pantalla; null = ninguno (o sin permiso para verlo). */
    var bluetoothDevice by mutableStateOf<String?>(null)
        private set

    /** Nombre de la memoria USB conectada; null = ninguna. */
    var usbDrive by mutableStateOf<String?>(null)
        private set

    // La última app que sonó (nunca LiteDash): sin app elegida, play la despierta aunque ya no esté abierta.
    private val store = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
    // Spotify avisa a cada rato: solo se escribe al disco cuando cambia la app.
    private var lastSavedApp: String? = store.getString(KEY_LAST_APP, null)

    /**
     * Tu app de música, elegida en Ajustes (paquete); null = ninguna: la pieza muestra lo que
     * suene. Los botones de la música en grande no la cambian ([active] es lo del momento).
     */
    var preferred: String?
        get() = preferredState
        set(v) {
            preferredState = v
            if (users > 0) choose(sessions())
        }
    // Estado de Compose: la fila de fuentes resalta la elegida al momento.
    private var preferredState by mutableStateOf<String?>(null)

    /** Guarda la elección de [choose] (la pone el app: va a sus preferencias). */
    var onPreferredChanged: ((String?) -> Unit)? = null

    /**
     * La fuente que el usuario tocó en la música en grande (paquete; el de LiteDash = USB);
     * null = la app elegida o lo que suene. No se guarda: si otra app empieza a sonar sola, se suelta.
     */
    var active by mutableStateOf<String?>(null)
        private set

    /** Tocaron USB y el reproductor propio aún busca qué tocar: no soltar [active] mientras. */
    private var ownPending = false

    /** Sube con cada toque a USB o a otra fuente: la respuesta de un toque viejo se ignora. */
    private var ownToken = 0

    /**
     * Apps de la pantalla enlazadas a Bluetooth y radio (Ajustes → Música); se guardan en [store].
     * USB no se enlaza: es siempre el reproductor de LiteDash.
     */
    var sourceApps by mutableStateOf(SourceKind.entries.filter { it != SourceKind.USB }.mapNotNull { k -> store.getString(k.key, null)?.let { k to it } }.toMap())
        private set

    /**
     * Quien tenía una "app de USB" enlazada (antes de la 1.4.57) la conserva como su app de música,
     * pero solo si la eligió él: las del sistema eran adivinadas y no se suben. Una sola vez.
     */
    fun migrateUsbApp() {
        val old = store.getString(SourceKind.USB.key, null) ?: return
        store.edit().remove(SourceKind.USB.key).apply()
        val system = runCatching { context.packageManager.getApplicationInfo(old, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0 }.getOrDefault(true)
        if (preferred == null && !system) choose(old)
    }

    /** Enlaza (o con null desenlaza) la app de Bluetooth o la de radio. */
    fun setSource(kind: SourceKind, pkg: String?) {
        sourceApps = if (pkg == null) sourceApps - kind else sourceApps + (kind to pkg)
        // Al enlazar otra vez (quizá tras actualizar la app de la pantalla) se vuelve a preguntar su receptor.
        if (pkg != null) receivers.remove(pkg)
        store.edit().putString(kind.key, pkg).apply()
        // Enlazar o quitar la radio cambia qué pieza se queda con cada sesión.
        if (users > 0) choose(sessions())
    }

    private val SourceKind.key get() = "source_" + name.lowercase()

    /** La radio por internet de LiteDash está prendida (la pone el app según su ajuste). */
    var ownRadio by mutableStateOf(false)

    /**
     * Pide al reproductor propio que suene esa fuente y avisa en el hilo principal: true = ya
     * suena (o va a sonar), false = no hay nada que tocar (la vista abre la biblioteca).
     */
    var ownPlay: ((SourceKind, (Boolean) -> Unit) -> Unit)? = null

    /** Pausa la USB del reproductor propio y cancela un arranque pendiente (lo pone el app). */
    var ownPause: (() -> Unit)? = null

    /** Qué fuente propia está sonando ahora; null si el reproductor propio no suena. */
    var ownSourceNow: (() -> SourceKind?)? = null

    /** El paquete de LiteDash: el "app" de las fuentes que toca el reproductor propio. */
    val ownPackage: String get() = context.packageName

    fun isOwn(pkg: String) = pkg == ownPackage

    /**
     * La app que reproduce [kind]: USB es siempre LiteDash; Bluetooth, la enlazada. La radio: la
     * de internet si está prendida y se prefiere (o no hay FM enlazada), si no la FM enlazada.
     */
    fun appFor(kind: SourceKind): String? = when (kind) {
        SourceKind.USB -> ownPackage
        SourceKind.BLUETOOTH -> sourceApps[kind]
        SourceKind.RADIO -> if (ownRadio && (radioInternet || sourceApps[kind] == null)) ownPackage else sourceApps[kind]
    }

    /** La pieza Radio usa la radio por internet de LiteDash (si no, la FM de la pantalla). Se guarda. */
    var radioInternet: Boolean
        get() = radioInternetState
        set(v) {
            radioInternetState = v
            store.edit().putBoolean(KEY_RADIO_INTERNET, v).apply()
            // Al pasar a la FM, la de internet se calla (si no, su sesión en pausa seguiría mandando).
            if (!v && ownSourceNow?.invoke() == SourceKind.RADIO) ownStop?.invoke()
        }

    /** Detiene el reproductor propio (lo pone el app). */
    var ownStop: (() -> Unit)? = null
    private var radioInternetState by mutableStateOf(store.getBoolean(KEY_RADIO_INTERNET, false))

    /** Hay FM de la pantalla enlazada y también internet: la pieza Radio deja escoger. */
    val radioHasBoth: Boolean get() = ownRadio && sourceApps[SourceKind.RADIO] != null

    /**
     * Tocaron USB: que suene el reproductor propio (sin cambiar la app elegida). Busca qué tocar
     * en segundo plano; si no hay nada, [onEmpty] (la vista abre la biblioteca para escoger).
     */
    fun playOwn(onEmpty: () -> Unit) {
        val play = ownPlay ?: return
        val token = ++ownToken
        active = ownPackage
        ownPending = true
        play(SourceKind.USB) { ok ->
            if (token != ownToken) return@play
            ownPending = false
            if (!ok) onEmpty()
            if (users > 0) choose(sessions())
        }
    }

    private val icons = HashMap<String, Bitmap?>()
    // Nombres de las apps: se piden una vez a Android (es una llamada entre procesos).
    private val labels = HashMap<String, String>()

    fun hasPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
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

    // Celular por Bluetooth y memoria USB: solo se escuchan mientras la música está en pantalla.
    private val devices = DeviceWatcher(context, onBluetooth = { bluetoothDevice = it }, onUsb = { usbDrive = it })

    fun use() {
        users++
        if (users == 1) start()
    }

    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) stop()
    }

    /** Llamar al volver a la app: si se acaba de dar el permiso, empieza a escuchar las sesiones. */
    fun recheck() {
        if (users > 0 && !listening) listen()
    }

    // Ya escucha las sesiones de medios (con el permiso de avisos).
    private var listening = false

    /**
     * Respaldo de "qué suena" para apps sin sesión de medios (la música Bluetooth de muchas
     * pantallas chinas): sus avisos (broadcasts). La radio enlazada va a su pieza; lo demás, a Música.
     */
    private val broadcasts = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val meta = BroadcastMeta.parse(i.action ?: return, i.extras.toMap()) ?: return
            (if (meta.pkg != null && meta.pkg == sourceApps[SourceKind.RADIO]) radio else music).heard(meta)
        }
    }

    private fun start() {
        devices.start()
        // Son avisos de otras apps: el receptor debe aceptar avisos de fuera. Cualquier app podría mandar
        // un título falso; solo se muestra (sin sesión de por medio) y la sesión real siempre manda.
        val filter = IntentFilter().apply { BroadcastMeta.ACTIONS.forEach(::addAction) }
        ContextCompat.registerReceiver(context, broadcasts, filter, ContextCompat.RECEIVER_EXPORTED)
        if (!store.getBoolean(KEY_SOURCES_GUESSED, false)) {
            store.edit().putBoolean(KEY_SOURCES_GUESSED, true).apply()
            Thread(::guessSources, "music-sources").start()
        }
        listen()
        // Sin permiso de avisos no hay sesiones: se muestra lo último avisado por broadcast.
        if (!listening) {
            music.read()
            radio.read()
        }
    }

    private fun listen() {
        if (!hasPermission()) return
        runCatching {
            sessionManager.addOnActiveSessionsChangedListener(onSessionsChanged, component, mainHandler)
            listening = true
            choose(sessions())
        }
    }

    private fun stop() {
        devices.stop()
        runCatching { context.unregisterReceiver(broadcasts) }
        if (listening) runCatching { sessionManager.removeOnActiveSessionsChangedListener(onSessionsChanged) }
        listening = false
        music.detach()
        radio.detach()
    }

    private fun sessions(): List<MediaController> =
        runCatching { sessionManager.getActiveSessions(component) }.getOrDefault(emptyList())

    /** ¿[pkg] es la radio: la app FM enlazada de la pantalla o LiteDash tocando radio por internet? */
    fun isRadio(pkg: String): Boolean =
        pkg == sourceApps[SourceKind.RADIO] || (isOwn(pkg) && ownSourceNow?.invoke() == SourceKind.RADIO)

    /** La pieza Música: todo menos la radio; sigue lo que tocó el usuario ([active]), la app elegida o lo que suene. */
    val music = Lens(accepts = { !isRadio(it) }, fixed = { active ?: preferred }, remembersLast = true)

    /** La pieza Radio: solo la radio de la pantalla (FM) o la de LiteDash (internet). */
    val radio = Lens(accepts = { isRadio(it) }, fixed = { null }, remembersLast = false)

    init {
        music.onWake = ::resume
        radio.onWake = { resumeRadio() }
    }

    /** Cada pieza elige su sesión de la misma lista. */
    private fun choose(list: List<MediaController>) {
        release(list)
        music.choose(list)
        radio.choose(list)
    }

    /**
     * Suelta [active] cuando ya no manda: el reproductor propio se fue, o lo tocado no suena y
     * otra app empezó a sonar sola (p. ej. abrieron Spotify a mano). Nunca mientras USB arranca.
     */
    private fun release(list: List<MediaController>) {
        val pick = active ?: return
        if (ownPending) return
        val mine = list.filter { !isRadio(it.packageName) }
        if (isOwn(pick) && mine.none { isOwn(it.packageName) }) {
            active = null
            return
        }
        if (mine.none { it.packageName == pick && (it.busy() || it.playbackState?.state == PlaybackState.STATE_ERROR) } && mine.any { it.packageName != pick && it.playbackState?.state == PlaybackState.STATE_PLAYING }) active = null
    }

    /**
     * Una "vista" de las sesiones de medios: la pieza Música y la pieza Radio miran la misma
     * lista pero cada una se queda con lo suyo ([accepts]); [fixed] es la app fija (la elegida
     * en Ajustes) y [remembersLast] si guarda la última app que sonó (para que play la despierte).
     */
    inner class Lens internal constructor(
        private val accepts: (String) -> Boolean,
        private val fixed: () -> String?,
        private val remembersLast: Boolean,
    ) {
        var song by mutableStateOf<Song?>(null)
            private set

        /** La app que esta pieza está despertando ("Abriendo Spotify…"); null = nada. */
        var starting by mutableStateOf<String?>(null)
            internal set

        internal var controller: MediaController? = null

        /** Cuándo se despertó [wokePkg] por última vez ([SystemClock.elapsedRealtime]); 0 = nunca. */
        internal var wokeAt = 0L

        /** La app que se despertó por última vez en esta pieza. */
        internal var wokePkg: String? = null

        /** Lo último que avisó una app por broadcast ([BroadcastMeta]) y cuándo; respaldo sin sesión. */
        private var heard: BroadcastMeta? = null
        private var heardAt = 0L
        private val expire = Runnable { if (users > 0 && controller == null) read() }

        internal fun heard(meta: BroadcastMeta) {
            heard = meta.over(heard)
            heardAt = SystemClock.elapsedRealtime()
            // Pasado el rato, deja de mostrarse aunque no llegue otro aviso.
            mainHandler.removeCallbacks(expire)
            mainHandler.postDelayed(expire, BROADCAST_FRESH_MS)
            if (controller != null) return
            read()
            if (song != null) starting = null
        }

        private val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) = read()
            override fun onPlaybackStateChanged(state: PlaybackState?) = read()
            override fun onSessionDestroyed() = this@MusicRepository.choose(sessions())
        }

        // Con app fija, solo esa (salvo el reproductor propio sonando). Si no: la que suena; si ninguna, la más reciente con canción
        // (Android las da en ese orden), para no quedarse con sesiones vacías.
        internal fun choose(all: List<MediaController>) {
            val list = all.filter { accepts(it.packageName) }
            val p = fixed()
            val withTitle = list.filter { it.metadata.text(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE).isNotEmpty() }
            // El reproductor propio sonando (o cargando) siempre se ve: lo puso a sonar el usuario.
            val own = list.firstOrNull { isOwn(it.packageName) && it.busy() }
            val chosen = if (own != null) {
                own
            } else if (p != null) {
                list.firstOrNull { it.packageName == p }
            } else {
                withTitle.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                    ?: list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                    ?: withTitle.firstOrNull()
                    ?: list.firstOrNull()
            }
            if (chosen?.sessionToken != controller?.sessionToken) {
                controller?.unregisterCallback(callback)
                controller = chosen
                chosen?.registerCallback(callback, mainHandler)
            }
            read()
        }

        internal fun detach() {
            mainHandler.removeCallbacks(expire)
            controller?.unregisterCallback(callback)
            controller = null
            song = null
        }

        internal fun read() {
            val c = controller
            val m = c?.metadata
            if (c == null) {
                song = fromBroadcast()
                return
            }
            // La sesión de LiteDash cambia de USB a radio (o al revés): que la tome la otra pieza.
            if (!accepts(c.packageName)) return this@MusicRepository.choose(sessions())
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
        if (remembersLast && playing && !isOwn(c.packageName) && c.packageName != lastSavedApp) {
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
            source = sourceOf(c.packageName),
            buffering = state?.state == PlaybackState.STATE_BUFFERING || state?.state == PlaybackState.STATE_CONNECTING,
            error = state?.takeIf { it.state == PlaybackState.STATE_ERROR }?.errorMessage?.toString(),
        )
        }

        /**
         * Sin sesión, lo último avisado por broadcast si es reciente: título, artista y estado
         * (sin carátula ni avance). Sin título, el nombre de la app (o "Bluetooth"), como la sesión.
         */
        private fun fromBroadcast(): Song? {
            val h = heard?.takeIf { SystemClock.elapsedRealtime() - heardAt < BROADCAST_FRESH_MS } ?: return null
            val pkg = h.pkg ?: sourceApps[SourceKind.BLUETOOTH]?.takeIf { h.bluetooth }
            val app = pkg?.let(::appLabel).orEmpty()
            // Llegó la canción sin estado: suena.
            val playing = h.playing ?: true
            if (h.title == null && !playing) return null
            val title = h.title ?: app.ifBlank { if (h.bluetooth) context.getString(R.string.source_bluetooth) else return null }
            return Song(
                title = title,
                artist = h.artist.orEmpty(),
                app = app,
                artwork = null,
                playing = playing,
                durationMs = 0,
                positionMs = 0,
                readAt = SystemClock.elapsedRealtime(),
                speed = 1f,
                pkg = pkg.orEmpty(),
                seekable = false,
                actions = emptyList(),
                source = pkg?.let(::sourceOf) ?: SourceKind.BLUETOOTH.takeIf { h.bluetooth },
                buffering = false,
                error = null,
            )
        }

        /**
         * Play o pausa; sin nada sonando, se despierta lo que toque ([onWake]). Si ya se despertó
         * hace poco y su app no comparte sesión (la música Bluetooth y la radio FM de muchas
         * pantallas), es play/pausa como el botón del volante: si no, cada toque volvía a
         * "Abriendo…". Una radio sin receptor no tiene a quién mandarle la tecla: se despierta.
         */
        fun toggle() {
            val t = controller?.transportControls?.takeUnless { keysOnly(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE) }
            val woke = wokePkg
            when {
                controller != null && t == null -> key(this, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                // Lo que suena se supo por broadcast: play/pausa como el volante.
                controller == null && song != null && canKey(this) -> key(this, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                t == null && woke != null && woke == target(this) && SystemClock.elapsedRealtime() - wokeAt < KEYS_WINDOW_MS && canKey(this) ->
                    key(this, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                t == null || song == null -> onWake()
                song?.playing == true -> t.pause()
                else -> t.play()
            }
        }

        /**
         * Sin sesión, como los botones del volante (a la app de la pieza o al sistema de audio).
         * En la radio FM, siguiente/anterior es lo que la app haga con esas teclas (casi siempre
         * la estación guardada que sigue o buscar la siguiente).
         */
        fun next() {
            controller?.transportControls?.takeUnless { keysOnly(PlaybackState.ACTION_SKIP_TO_NEXT) }?.skipToNext() ?: key(this, KeyEvent.KEYCODE_MEDIA_NEXT)
        }

        fun previous() {
            controller?.transportControls?.takeUnless { keysOnly(PlaybackState.ACTION_SKIP_TO_PREVIOUS) }?.skipToPrevious() ?: key(this, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        }

        /**
         * La radio FM tiene sesión pero no anuncia [action] (varias FM de pantalla publican la
         * sesión solo para mostrar la estación): mejor la tecla a su receptor, como el volante.
         * La música no: sus apps sí atienden la sesión.
         */
        private fun keysOnly(action: Long): Boolean {
            val c = controller ?: return false
            if (this !== radio || isOwn(c.packageName)) return false
            return (c.playbackState?.actions ?: 0L) and action == 0L && canKey(this)
        }
        fun seekTo(ms: Long) = controller?.transportControls?.seekTo(ms)
        fun action(a: MediaAction) = controller?.transportControls?.sendCustomAction(a.id, null)

        /** Qué hacer con play sin sesión: la música despierta la última app; la radio, la FM o la de internet. */
        internal var onWake: () -> Unit = {}
    }

    /** La fuente a la que está enlazada [pkg] (o la que toca LiteDash); null = una app normal. */
    fun sourceOf(pkg: String): SourceKind? =
        if (isOwn(pkg)) ownSourceNow?.invoke() else sourceApps.entries.firstOrNull { it.value == pkg }?.key

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

    // Atajos de la pieza Música (el volante y la pieza usan estos).
    fun toggle() = music.toggle()
    fun next() = music.next()
    fun previous() = music.previous()
    fun seekTo(ms: Long) = music.seekTo(ms)
    fun action(a: MediaAction) = music.action(a)

    /** Nombre de la app elegida en Ajustes ("" si no hay). */
    fun preferredName(): String = preferred?.let(::appLabel).orEmpty()

    /** Nombre de la app [pkg] como lo muestra Android ("" si no está). */
    fun appName(pkg: String): String = appLabel(pkg)

    /** Nombre de la app enlazada a [kind] ("" si no hay). */
    fun sourceName(kind: SourceKind): String = sourceApps[kind]?.let(::appLabel).orEmpty()

    /** Elige tu app de música (o ninguna con null) y la guarda. */
    fun choose(pkg: String?) {
        preferred = pkg
        onPreferredChanged?.invoke(pkg)
    }

    /**
     * Tocaron la app elegida o Bluetooth en la música en grande: se calla la USB propia (y se
     * cancela si iba arrancando) y suena [pkg]. La app elegida en Ajustes no cambia.
     */
    fun switchTo(pkg: String) {
        ownToken++
        ownPending = false
        ownPause?.invoke()
        active = pkg
        if (users > 0) choose(sessions())
        resume()
    }

    /** La app a la que va el play (o una tecla) de [lens] cuando no hay sesión que lo reciba. */
    private fun target(lens: Lens): String? =
        lens.song?.pkg?.takeIf { lens.controller == null && it.isNotEmpty() } ?: if (lens === radio) appFor(SourceKind.RADIO) else active ?: preferred ?: store.getString(KEY_LAST_APP, null)

    /**
     * Que suene lo tocado, la app elegida o, sin ninguna, la última que sonó. Con una canción
     * cargada (en pausa), play directo. Si no, en este orden: (1) conectarse a su
     * servicio de música, como Android Auto (algunas apps solo lo aceptan de Android
     * Auto); (2) el botón de play a su receptor, como el del volante (así arrancan los
     * reproductores de Bluetooth y radio de las pantallas); (3) el botón de play por
     * el sistema de audio, que Android lleva a la última app de música (mandárselo directo
     * a una sesión vieja lo bloquea desde Android 12); (4) si en [WAKE_TIMEOUT_MS] no
     * suena, se abre la app, que es lo que habría que hacer a mano. Mientras, [starting]
     * dice cuál se está abriendo.
     */
    fun resume() {
        val pkg = active ?: preferred ?: music.controller?.packageName ?: store.getString(KEY_LAST_APP, null)
        val session = music.controller?.takeIf { song != null && (pkg == null || it.packageName == pkg) }
        if (session != null) return session.transportControls.play()
        if (pkg == null) return systemPlay()
        if (isOwn(pkg)) return ownPlay?.invoke(SourceKind.USB) {} ?: Unit
        wake(pkg, music)
    }

    /**
     * Play de la pieza Radio: sigue la radio que suena; si no, despierta la FM de la pantalla
     * o la radio por internet de LiteDash. Sin con qué (sin app enlazada ni estación), [onIdle]:
     * la pieza abre sus ajustes o la lista de estaciones.
     */
    fun resumeRadio(onIdle: () -> Unit = {}) {
        val session = radio.controller?.takeIf { radio.song != null }
        if (session != null) return session.transportControls.play()
        val pkg = appFor(SourceKind.RADIO) ?: return onIdle()
        if (isOwn(pkg)) return ownPlay?.invoke(SourceKind.RADIO) { ok -> if (!ok) onIdle() } ?: Unit
        wake(pkg, radio)
    }

    /** Abre la app de radio de la pantalla (si hay una enlazada). */
    fun openRadioApp() {
        sourceApps[SourceKind.RADIO]?.let(::open)
    }

    /**
     * Despierta a [pkg] para que suene en [lens]: su servicio de música y el botón de play a su
     * receptor (los dos: varias apps de Bluetooth tienen servicio pero no hacen caso por ahí); si
     * no tiene ninguno, el botón del sistema. Solo se abre la app si no hubo a quién mandarle el
     * botón: las de Bluetooth de las pantallas no comparten qué suena, y abrirlas en cada toque
     * hacía "Abriendo…" y regresaba sin que nada cambiara.
     */
    private fun wake(pkg: String, lens: Lens) {
        lens.starting = appLabel(pkg)
        lens.wokeAt = SystemClock.elapsedRealtime()
        lens.wokePkg = pkg
        val service = connectAndPlay(pkg)
        val button = buttonPlay(pkg)
        if (!service && !button) systemPlay()
        mainHandler.postDelayed({
            if (lens.song == null && lens.starting != null && !button) open(pkg)
            lens.starting = null
        }, WAKE_TIMEOUT_MS)
    }

    /**
     * Una tecla multimedia a la app de [lens] (a su receptor, como el volante). Sin receptor, al
     * sistema de audio, salvo la radio y el reproductor propio: ahí el sistema la llevaría a
     * otra app (y podría arrancar Spotify).
     */
    private fun key(lens: Lens, code: Int) {
        val pkg = target(lens)
        val receiver = keyReceiver(pkg)
        if (receiver == null && (lens === radio || pkg != null && isOwn(pkg))) return
        sendMediaKey(context, code, receiver)
    }

    /** ¿Hay a quién mandarle una tecla de [lens]? La música siempre (al sistema); la radio, solo a su receptor. */
    private fun canKey(lens: Lens): Boolean = lens !== radio || keyReceiver(target(lens)) != null

    /**
     * El receptor del botón de play de [pkg] (nunca el de LiteDash: su reproductor tiene sesión).
     * Se pregunta una vez por app (es una llamada al sistema y la pieza lo consulta al pintarse).
     */
    private fun keyReceiver(pkg: String?): ComponentName? =
        pkg?.takeUnless(::isOwn)?.let { p -> receivers.getOrPut(p) { mediaButtonReceivers(p).firstOrNull() } }
    private val receivers = HashMap<String, ComponentName?>()

    /** La pieza Radio sin sesión pero con una FM de la pantalla que atiende teclas: lleva anterior/siguiente. */
    fun radioHasKeys(): Boolean {
        val pkg = appFor(SourceKind.RADIO) ?: return false
        return !isOwn(pkg) && keyReceiver(pkg) != null
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
        // Si el servicio nunca contesta, la conexión no se queda colgada para siempre.
        mainHandler.postDelayed({ runCatching { browser.disconnect() } }, WAKE_TIMEOUT_MS)
        return runCatching { browser.connect() }.isSuccess
    }

    /** El botón de play directo al receptor de [pkg] (las apps de Bluetooth/radio lo atienden así). */
    private fun buttonPlay(pkg: String): Boolean {
        val receiver = keyReceiver(pkg) ?: return false
        sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY, receiver)
        return true
    }

    /** El botón de play como el del volante: Android lo lleva a la última app de música. */
    private fun systemPlay() = sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY)

    /** Receptores del botón de play de [pkg] (o de todas las apps con null). */
    private fun mediaButtonReceivers(pkg: String? = null): List<ComponentName> = runCatching {
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply { if (pkg != null) setPackage(pkg) }
        context.packageManager.queryBroadcastReceivers(intent, 0)
            .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
    }.getOrDefault(emptyList())

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
     * Apps del sistema de la pantalla que atienden el botón de play: ahí están sus
     * reproductores de USB, Bluetooth y radio (para adivinar las fuentes en Ajustes).
     */
    fun systemPlayers(): Set<String> = runCatching {
        val pm = context.packageManager
        mediaButtonReceivers().map { it.packageName }.distinct()
            .filter { pkg -> pkg != context.packageName && runCatching { pm.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0 }.getOrDefault(false) }
            .toSet()
    }.getOrDefault(emptySet())

    /**
     * Adivina, una vez, qué app de la pantalla reproduce cada fuente: entre las del sistema
     * que atienden el botón de play y se pueden abrir, la que lleva "usb", "bt"/"bluetooth"
     * o "radio"/"fm" en el paquete o en el nombre. Solo si hay una sola candidata (si hay
     * dudas, mejor nada: el usuario la elige en Ajustes). Corre en un hilo aparte (son
     * varias consultas al sistema) y guarda el resultado en el hilo principal.
     */
    private fun guessSources() {
        val pm = context.packageManager
        val launchable = runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName }.toSet()
        }.getOrDefault(emptySet())
        val candidates = systemPlayers().filter { it in launchable }.map { pkg -> pkg to tokens(pkg, appLabel(pkg)) }
        val found = SOURCE_WORDS.mapNotNull { (kind, words) ->
            candidates.filter { (_, t) -> words.any { it in t } }.singleOrNull()?.let { kind to it.first }
        }.toMap()
        if (found.isEmpty()) return
        mainHandler.post { for ((kind, pkg) in found) if (kind !in sourceApps) setSource(kind, pkg) }
    }

    /** Palabras sueltas del paquete y del nombre, en minúsculas ("com.syu.bt" → com, syu, bt). */
    private fun tokens(pkg: String, label: String): Set<String> =
        (pkg.split('.') + label.split(Regex("[^\\p{L}\\p{N}]+"))).map { it.lowercase() }.filter { it.isNotEmpty() }.toSet()

    /**
     * Lo que LiteDash ve de la música en esta pantalla, para "Copiar info": las apps
     * con servicio de música, las que aceptan el botón de play (ahí suelen estar los
     * reproductores de USB y Bluetooth), las fuentes enlazadas y las sesiones abiertas.
     */
    fun describe(): String = buildString {
        val buttons = mediaButtonReceivers().map { it.packageName }.distinct()
        val sessions = if (!hasPermission()) null else sessions().map { "${it.packageName} (${it.playbackState?.state ?: "-"})" }
        appendLine("Música elegida: ${preferred ?: "ninguna"}")
        appendLine("Fuentes: ${SourceKind.entries.joinToString { "${it.name.lowercase()}=${appFor(it) ?: "-"}" }}")
        appendLine("Bluetooth: ${bluetoothDevice ?: "-"}; USB: ${usbDrive ?: "-"}")
        appendLine("Apps de música: ${musicApps().joinToString().ifBlank { "ninguna" }}")
        appendLine("Botón de play: ${buttons.joinToString().ifBlank { "ninguna" }}")
        append("Sesiones: ${sessions?.joinToString()?.ifBlank { "ninguna" } ?: "sin acceso a notificaciones"}")
    }

    /** Abre la app que está sonando o, si no hay, la tocada o la elegida en Ajustes. */
    fun openApp() {
        open(music.controller?.packageName ?: song?.pkg?.ifEmpty { null } ?: active ?: preferred ?: return)
    }

    /** Si devuelve true, ya abrió la app a su manera (p. ej. en pantalla dividida). */
    var opener: ((Context, Intent, String) -> Boolean)? = null

    private fun open(pkg: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
        if (opener?.invoke(context, intent, pkg) == true) return
        runCatching { context.startActivity(intent) }
    }
}

/**
 * Celular conectado por Bluetooth y memoria USB puesta en la pantalla. Avisa con el
 * nombre (o null al quitarse) y solo escucha entre [start] y [stop]. Del Bluetooth solo
 * cuentan los celulares y lo que se conecta para música (A2DP): un adaptador OBD no.
 */
private class DeviceWatcher(
    private val context: Context,
    private val onBluetooth: (String?) -> Unit,
    private val onUsb: (String?) -> Unit,
) {
    // El celular que se avisó (para no borrarlo cuando se desconecta otro aparato).
    private var phone: String? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val d = IntentCompat.getParcelableExtra(i, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    if (isPhone(d)) setPhone(deviceName(d))
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> left(i)
                A2DP_CHANGED, A2DP_SINK_CHANGED -> {
                    val d = IntentCompat.getParcelableExtra(i, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    if (i.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothProfile.STATE_CONNECTED) setPhone(deviceName(d)) else left(i)
                }
                else -> {
                    Drives.changed()
                    onUsb(Drives.mounted(context))
                }
            }
        }
    }

    private fun setPhone(name: String?) {
        if (name == null) return
        phone = name
        onBluetooth(name)
    }

    // Se fue un aparato: si era el celular, ya no hay.
    private fun left(i: Intent) {
        val name = deviceName(IntentCompat.getParcelableExtra(i, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java))
        if (name != null && name == phone) {
            phone = null
            onBluetooth(null)
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(A2DP_CHANGED)
            addAction(A2DP_SINK_CHANGED)
        }
        // Son avisos del sistema: los receptores deben aceptar avisos de fuera de la app.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        // También cuando Android termina de indexar la memoria: la biblioteca que se leyó a medias se relee.
        ContextCompat.registerReceiver(context, receiver, Drives.mediaFilter(scanner = true), ContextCompat.RECEIVER_EXPORTED)
        // Sin [Drives.changed]: si mientras no se escuchaba cambió la memoria, la biblioteca lo nota por
        // su identificador (va en la llave de su caché); abrir o cerrar la música no la hace releer.
        onUsb(Drives.mounted(context))
        connectedPhone()
    }

    fun stop() {
        // Está registrado dos veces (dos filtros): se quita dos veces.
        repeat(2) { runCatching { context.unregisterReceiver(receiver) } }
    }

    /**
     * El celular ya conectado al abrir la pieza: se pregunta al perfil de música
     * (A2DP; en las pantallas, el "sink"). Desde Android 12 hace falta el permiso
     * BLUETOOTH_CONNECT (se da en el Taller); sin él, solo se sabrá al reconectarse.
     */
    private fun connectedPhone() {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        if (!adapter.isEnabled || !canSeeDevices()) return
        for (profile in intArrayOf(A2DP_SINK, BluetoothProfile.A2DP)) {
            runCatching {
                adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                        setPhone(runCatching { proxy.connectedDevices.firstOrNull() }.getOrNull()?.let(::deviceName))
                        adapter.closeProfileProxy(p, proxy)
                    }

                    override fun onServiceDisconnected(p: Int) = Unit
                }, profile)
            }
        }
    }

    /** Desde Android 12, el nombre del celular (y los avisos de conexión) piden BLUETOOTH_CONNECT. */
    private fun canSeeDevices(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // se revisa con canSeeDevices()
    private fun isPhone(d: BluetoothDevice?): Boolean =
        d != null && canSeeDevices() && runCatching { d.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PHONE }.getOrDefault(false)

    @SuppressLint("MissingPermission") // se revisa con canSeeDevices()
    private fun deviceName(d: BluetoothDevice?): String? {
        if (d == null || !canSeeDevices()) return null
        return runCatching { d.name?.takeIf { it.isNotBlank() } ?: d.address }.getOrNull()
    }

    private companion object {
        /** El celular se conectó (o se fue) para música. */
        const val A2DP_CHANGED = "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED"
        /** Lo mismo en las pantallas que reciben la música del celular (no es público en el SDK). */
        const val A2DP_SINK_CHANGED = "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED"
    }
}

/** Una memoria USB (o tarjeta) conectada a la pantalla. */
class Drive(
    /** Nombre que muestra Android ("Memoria USB SanDisk"); genérico en Android 6. */
    val name: String,
    /** Nombre del volumen en MediaStore (API 29+) o null. */
    val volume: String?,
    /** Carpeta raíz de la memoria (para filtrar por ruta antes de la API 29); null si no se sabe. */
    val path: String?,
    /** Identificador estable de la memoria (para recordar lo último que sonó). */
    val id: String,
)

/** Memorias USB (y tarjetas) conectadas a la pantalla: las usan la pieza y el reproductor propio. */
object Drives {
    /** Sube cada vez que se pone o se quita una memoria (la biblioteca la usa para no releerla). */
    @Volatile
    var revision = 0
        private set

    /** Avisar que se puso, quitó o terminó de indexarse una memoria. */
    fun changed() {
        revision++
    }

    /**
     * Los avisos de Android al poner o quitar una memoria; con [scanner], también cuando
     * termina de indexarla (al reconectar tarda unos segundos).
     */
    fun mediaFilter(scanner: Boolean = false) = IntentFilter().apply {
        addAction(Intent.ACTION_MEDIA_MOUNTED)
        addAction(Intent.ACTION_MEDIA_UNMOUNTED)
        addAction(Intent.ACTION_MEDIA_EJECT)
        addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
        if (scanner) addAction(Intent.ACTION_MEDIA_SCANNER_FINISHED)
        addDataScheme("file")
    }

    /** Memorias montadas ahora (sin la del propio equipo). */
    fun list(context: Context): List<Drive> = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val volumes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.getExternalVolumeNames(context) else emptySet()
            context.getSystemService(StorageManager::class.java).storageVolumes
                .filter { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
                .map { v ->
                    val uuid = v.uuid
                    Drive(
                        name = v.getDescription(context),
                        volume = uuid?.lowercase()?.takeIf { it in volumes },
                        path = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) v.directory?.path else uuid?.let { "/storage/$it" },
                        id = uuid ?: v.getDescription(context),
                    )
                }
        } else {
            // Android 6 no lista los volúmenes: las memorias se montan como carpetas de /storage
            // (solo las removibles: ahí también hay enlaces a la memoria interna).
            File("/storage").listFiles().orEmpty()
                .filter { it.name != "emulated" && it.name != "self" && Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }
                .filter { runCatching { Environment.isExternalStorageRemovable(it) }.getOrDefault(false) }
                .map { Drive(name = context.getString(R.string.usb_drive), volume = null, path = it.path, id = it.name) }
        }
    }.getOrDefault(emptyList())

    /** Nombre de la primera memoria conectada; null si no hay. */
    fun mounted(context: Context): String? = list(context).firstOrNull()?.name
}

/** Sonando o cargando. */
private fun MediaController.busy(): Boolean = when (playbackState?.state) {
    PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> true
    else -> false
}

/**
 * Una tecla multimedia (abajo y arriba) como la del volante: a [receiver] (el receptor de una
 * app) o, sin él, al sistema de audio, que la lleva a la app de música que toca.
 */
fun sendMediaKey(context: Context, code: Int, receiver: ComponentName? = null) {
    val audio = context.getSystemService(AudioManager::class.java)
    for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
        val event = KeyEvent(action, code)
        runCatching {
            if (receiver != null) context.sendBroadcast(Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(receiver).putExtra(Intent.EXTRA_KEY_EVENT, event))
            else audio.dispatchMediaKeyEvent(event)
        }
    }
}

/** Los extras de un aviso como mapa (un extra que no se puede leer deja el mapa vacío). */
@Suppress("DEPRECATION") // Bundle.get: los avisos traen tipos variados
private fun Bundle?.toMap(): Map<String, Any?> {
    val b = this ?: return emptyMap()
    return runCatching { b.keySet().associateWith { b.get(it) } }.getOrDefault(emptyMap())
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
