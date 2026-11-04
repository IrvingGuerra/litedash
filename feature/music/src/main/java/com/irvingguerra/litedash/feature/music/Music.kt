package com.irvingguerra.litedash.feature.music

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
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
    SourceKind.USB to listOf("usb"),
    SourceKind.BLUETOOTH to listOf("bluetooth", "bt", "a2dp"),
    SourceKind.RADIO to listOf("radio", "fm"),
)

/** Cuánto se espera a que la app despertada empiece a sonar antes de abrirla. */
private const val WAKE_TIMEOUT_MS = 4_000L
/** Tras despertar una app sin sesión, play/pausa es tecla de volante durante este rato. */
private const val KEYS_WINDOW_MS = 30 * 60_000L

/** Perfil Bluetooth "A2DP sink" (la pantalla recibe la música del celular); no es público en el SDK. */
private const val A2DP_SINK = 11

// Ofrecen su audio a Android pero no son apps de música (el asistente, video).
private val NOT_MUSIC_APPS = setOf(
    "com.google.android.googlequicksearchbox", "com.google.android.youtube", "com.google.android.gms",
)

/**
 * Fuentes de música de la pantalla, como en un estéreo: cada una es una app del
 * sistema de la pantalla que el usuario enlaza en Ajustes (los paquetes cambian con
 * la marca: `com.syu.music`, `com.microntek.bt`, `com.ts.radio`…).
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

    // La última app que sonó: en "Sistema", play la despierta aunque ya no esté abierta.
    private val store = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
    // Spotify avisa a cada rato: solo se escribe al disco cuando cambia la app.
    private var lastSavedApp: String? = store.getString(KEY_LAST_APP, null)

    /**
     * App de música elegida en Ajustes (paquete). null = "Sistema": lo que suene
     * en la pantalla, venga de donde venga (radio, USB, Bluetooth o una app).
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

    /** Apps de la pantalla enlazadas a cada fuente (Ajustes → Música); se guardan en [store]. */
    var sourceApps by mutableStateOf(SourceKind.entries.mapNotNull { k -> store.getString(k.key, null)?.let { k to it } }.toMap())
        private set

    /** Enlaza (o con null desenlaza) la app de una fuente. */
    fun setSource(kind: SourceKind, pkg: String?) {
        sourceApps = if (pkg == null) sourceApps - kind else sourceApps + (kind to pkg)
        store.edit().putString(kind.key, pkg).apply()
        // Enlazar o quitar la radio cambia qué pieza se queda con cada sesión.
        if (users > 0) choose(sessions())
    }

    private val SourceKind.key get() = "source_" + name.lowercase()

    /** Fuentes que toca el reproductor propio de LiteDash (USB, radio): las pone el app según sus módulos. */
    var ownSources by mutableStateOf(emptySet<SourceKind>())

    /** Pide al reproductor propio que suene esa fuente; false = no hay nada que tocar aún (se abre la biblioteca). */
    var ownPlay: ((SourceKind) -> Boolean)? = null

    /** Qué fuente propia está sonando ahora; null si el reproductor propio no suena. */
    var ownSourceNow: (() -> SourceKind?)? = null

    /** El paquete de LiteDash: el "app" de las fuentes que toca el reproductor propio. */
    val ownPackage: String get() = context.packageName

    fun isOwn(pkg: String) = pkg == ownPackage

    /**
     * La app que reproduce [kind]: LiteDash mismo si su módulo está prendido, si no la enlazada.
     * La radio es la excepción: con FM enlazada e internet prendido, manda [radioInternet].
     */
    fun appFor(kind: SourceKind): String? = when {
        kind == SourceKind.RADIO -> if (SourceKind.RADIO in ownSources && (radioInternet || sourceApps[kind] == null)) ownPackage else sourceApps[kind]
        kind in ownSources -> ownPackage
        else -> sourceApps[kind]
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
    val radioHasBoth: Boolean get() = SourceKind.RADIO in ownSources && sourceApps[SourceKind.RADIO] != null

    /**
     * Que suene el reproductor propio con [kind]; false = abrir la biblioteca para escoger.
     * Pone "Sistema": así la pieza sigue la sesión de LiteDash (y las demás) sin fijar una app.
     */
    fun playOwn(kind: SourceKind): Boolean {
        choose(null)
        return ownPlay?.invoke(kind) == true
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

    /** Llamar al volver a la app: el permiso pudo haber cambiado en Ajustes. */
    fun recheck() {
        if (users > 0 && music.controller == null && radio.controller == null) {
            stop()
            start()
        }
    }

    private fun start() {
        devices.start()
        if (!store.getBoolean(KEY_SOURCES_GUESSED, false)) {
            store.edit().putBoolean(KEY_SOURCES_GUESSED, true).apply()
            Thread(::guessSources, "music-sources").start()
        }
        if (!hasPermission()) return
        runCatching {
            sessionManager.addOnActiveSessionsChangedListener(onSessionsChanged, component, mainHandler)
            choose(sessions())
        }
    }

    private fun stop() {
        devices.stop()
        runCatching { sessionManager.removeOnActiveSessionsChangedListener(onSessionsChanged) }
        music.detach()
        radio.detach()
    }

    private fun sessions(): List<MediaController> =
        runCatching { sessionManager.getActiveSessions(component) }.getOrDefault(emptyList())

    /** ¿[pkg] es la radio: la app FM enlazada de la pantalla o LiteDash tocando radio por internet? */
    fun isRadio(pkg: String): Boolean =
        pkg == sourceApps[SourceKind.RADIO] || (isOwn(pkg) && ownSourceNow?.invoke() == SourceKind.RADIO)

    /** La pieza Música: todo menos la radio; sigue la app elegida ([preferred]) o lo que suene. */
    val music = Lens(accepts = { !isRadio(it) }, fixed = { preferred }, remembersLast = true)

    /** La pieza Radio: solo la radio de la pantalla (FM) o la de LiteDash (internet). */
    val radio = Lens(accepts = { isRadio(it) }, fixed = { null }, remembersLast = false)

    init {
        music.onWake = ::resume
        radio.onWake = { resumeRadio() }
    }

    /** Cada pieza elige su sesión de la misma lista. */
    private fun choose(list: List<MediaController>) {
        music.choose(list)
        radio.choose(list)
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

        /** Cuándo se despertó su app por última vez ([SystemClock.elapsedRealtime]); 0 = nunca. */
        internal var wokeAt = 0L

        private val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) = read()
            override fun onPlaybackStateChanged(state: PlaybackState?) = read()
            override fun onSessionDestroyed() = this@MusicRepository.choose(sessions())
        }

        // Con app fija, solo esa. Si no: la que suena; si ninguna, la más reciente con canción
        // (Android las da en ese orden), para no quedarse con sesiones vacías.
        internal fun choose(all: List<MediaController>) {
            val list = all.filter { accepts(it.packageName) }
            val p = fixed()
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
                controller?.unregisterCallback(callback)
                controller = chosen
                chosen?.registerCallback(callback, mainHandler)
            }
            read()
        }

        internal fun detach() {
            controller?.unregisterCallback(callback)
            controller = null
            song = null
        }

        internal fun read() {
            val c = controller
            val m = c?.metadata
            if (c == null) {
                song = null
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
        if (remembersLast && playing && c.packageName != lastSavedApp) {
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
         * Play o pausa; sin nada sonando, se despierta lo que toque ([onWake]). Si ya se despertó
         * hace poco y su app no comparte sesión (la música Bluetooth de muchas pantallas), es
         * play/pausa como el botón del volante: si no, cada toque volvía a "Abriendo…".
         */
        fun toggle() {
            val t = controller?.transportControls
            when {
                t == null && SystemClock.elapsedRealtime() - wokeAt < KEYS_WINDOW_MS && wokeAt != 0L -> key(this, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                t == null || song == null -> onWake()
                song?.playing == true -> t.pause()
                else -> t.play()
            }
        }

        /** Sin sesión, como los botones del volante (a la app de la pieza o al sistema de audio). */
        fun next() {
            controller?.transportControls?.skipToNext() ?: key(this, KeyEvent.KEYCODE_MEDIA_NEXT)
        }

        fun previous() {
            controller?.transportControls?.skipToPrevious() ?: key(this, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
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

    /** Nombre de la app elegida en Ajustes ("" en "Sistema"). */
    fun preferredName(): String = preferred?.let(::appLabel).orEmpty()

    /** Nombre de la app [pkg] como lo muestra Android ("" si no está). */
    fun appName(pkg: String): String = appLabel(pkg)

    /** Nombre de la app enlazada a [kind] ("" si no hay). */
    fun sourceName(kind: SourceKind): String = sourceApps[kind]?.let(::appLabel).orEmpty()

    /** Elige la app (o "Sistema" con null) y la guarda; como el botón de fuente de un estéreo. */
    fun choose(pkg: String?) {
        preferred = pkg
        onPreferredChanged?.invoke(pkg)
    }

    /** Cambia a [pkg] y que suene: tocar "USB" en la pieza arranca la USB. */
    fun switchTo(pkg: String) {
        choose(pkg)
        resume()
    }

    /**
     * Que suene: la elegida o, en "Sistema", la última que sonó. Con una canción
     * cargada (en pausa), play directo. Si no, en este orden: (1) conectarse a su
     * servicio de música, como Android Auto (algunas apps solo lo aceptan de Android
     * Auto); (2) el botón de play a su receptor, como el del volante (así arrancan los
     * reproductores de USB, Bluetooth y radio de las pantallas); (3) el botón de play por
     * el sistema de audio, que Android lleva a la última app de música (mandárselo directo
     * a una sesión vieja lo bloquea desde Android 12); (4) si en [WAKE_TIMEOUT_MS] no
     * suena, se abre la app, que es lo que habría que hacer a mano. Mientras, [starting]
     * dice cuál se está abriendo.
     */
    fun resume() {
        val pkg = preferred ?: music.controller?.packageName ?: store.getString(KEY_LAST_APP, null)
        val session = music.controller?.takeIf { song != null && (pkg == null || it.packageName == pkg) }
        if (session != null) return session.transportControls.play()
        if (pkg == null) return systemPlay()
        if (isOwn(pkg)) {
            ownPlay?.invoke(ownSourceNow?.invoke() ?: ownSources.firstOrNull { it != SourceKind.RADIO } ?: return)
            return
        }
        wake(pkg, music)
    }

    /**
     * Play de la pieza Radio: sigue la radio que suena; si no, despierta la FM de la pantalla
     * o la radio por internet de LiteDash. false = no hay con qué (sin app enlazada ni
     * estación: la pieza abre sus ajustes o la lista de estaciones).
     */
    fun resumeRadio(): Boolean {
        val session = radio.controller?.takeIf { radio.song != null }
        if (session != null) {
            session.transportControls.play()
            return true
        }
        val pkg = appFor(SourceKind.RADIO) ?: return false
        if (isOwn(pkg)) return ownPlay?.invoke(SourceKind.RADIO) == true
        wake(pkg, radio)
        return true
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
        val service = connectAndPlay(pkg)
        val button = buttonPlay(pkg)
        if (!service && !button) systemPlay()
        mainHandler.postDelayed({
            if (lens.song == null && lens.starting != null && !button) open(pkg)
            lens.starting = null
        }, WAKE_TIMEOUT_MS)
    }

    /** Una tecla multimedia a la app de [lens] (a su receptor, como el volante) o, si no tiene, al sistema de audio. */
    private fun key(lens: Lens, code: Int) {
        val pkg = if (lens === radio) appFor(SourceKind.RADIO) else preferred ?: store.getString(KEY_LAST_APP, null)
        val receiver = pkg?.takeUnless(::isOwn)?.let { mediaButtonReceivers(it).firstOrNull() }
        val audio = context.getSystemService(AudioManager::class.java)
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val event = KeyEvent(action, code)
            if (receiver != null) {
                runCatching { context.sendBroadcast(Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(receiver).putExtra(Intent.EXTRA_KEY_EVENT, event)) }
            } else {
                runCatching { audio.dispatchMediaKeyEvent(event) }
            }
        }
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

    /** El botón de play directo al receptor de [pkg] (las apps de USB/Bluetooth/radio lo atienden así). */
    private fun buttonPlay(pkg: String): Boolean {
        val receiver = mediaButtonReceivers(pkg).firstOrNull() ?: return false
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
                .setComponent(receiver)
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(action, KeyEvent.KEYCODE_MEDIA_PLAY))
            runCatching { context.sendBroadcast(intent) }
        }
        return true
    }

    /** El botón de play como el del volante: Android lo lleva a la última app de música. */
    private fun systemPlay() {
        val audio = context.getSystemService(AudioManager::class.java)
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            runCatching { audio.dispatchMediaKeyEvent(KeyEvent(action, KeyEvent.KEYCODE_MEDIA_PLAY)) }
        }
    }

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
        appendLine("Música elegida: ${preferred ?: "Sistema"}")
        appendLine("Fuentes: ${SourceKind.entries.joinToString { "${it.name.lowercase()}=${appFor(it) ?: "-"}" }}")
        appendLine("Bluetooth: ${bluetoothDevice ?: "-"}; USB: ${usbDrive ?: "-"}")
        appendLine("Apps de música: ${musicApps().joinToString().ifBlank { "ninguna" }}")
        appendLine("Botón de play: ${buttons.joinToString().ifBlank { "ninguna" }}")
        append("Sesiones: ${sessions?.joinToString()?.ifBlank { "ninguna" } ?: "sin acceso a notificaciones"}")
    }

    /** Abre la app que está sonando o, si no hay, la elegida en Ajustes. */
    fun openApp() {
        open(music.controller?.packageName ?: preferred ?: return)
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
 * nombre (o null al quitarse) y solo escucha entre [start] y [stop].
 */
private class DeviceWatcher(
    private val context: Context,
    private val onBluetooth: (String?) -> Unit,
    private val onUsb: (String?) -> Unit,
) {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> onBluetooth(deviceName(IntentCompat.getParcelableExtra(i, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)))
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> onBluetooth(null)
                Intent.ACTION_MEDIA_MOUNTED -> onUsb(Drives.mounted(context))
                else -> onUsb(Drives.mounted(context))
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        val media = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }
        // Son avisos del sistema: los receptores deben aceptar avisos de fuera de la app.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        ContextCompat.registerReceiver(context, receiver, media, ContextCompat.RECEIVER_EXPORTED)
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
                        val name = runCatching { proxy.connectedDevices.firstOrNull() }.getOrNull()?.let(::deviceName)
                        if (name != null) onBluetooth(name)
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
    private fun deviceName(d: BluetoothDevice?): String? {
        if (d == null || !canSeeDevices()) return null
        return runCatching { d.name?.takeIf { it.isNotBlank() } ?: d.address }.getOrNull()
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
            // Android 6 no lista los volúmenes: las memorias se montan como carpetas de /storage.
            File("/storage").listFiles().orEmpty()
                .filter { it.name != "emulated" && it.name != "self" && Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }
                .map { Drive(name = context.getString(R.string.usb_drive), volume = null, path = it.path, id = it.name) }
        }
    }.getOrDefault(emptyList())

    /** Nombre de la primera memoria conectada; null si no hay. */
    fun mounted(context: Context): String? = list(context).firstOrNull()?.name
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
