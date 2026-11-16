package com.irvingguerra.litedash.feature.music

import android.app.Notification
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.view.KeyEvent
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.os.BundleCompat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
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

/** El store de la música (también lo lee [FactoryAudio] para las estadísticas). */
internal const val MUSIC_STORE = "music"
private const val KEY_LAST_APP = "last_app"
/** Ya se intentó adivinar la app de radio (una sola vez, la primera vez que se ve la pieza Radio). */
private const val KEY_SOURCES_GUESSED = "sources_guessed"
/** La pieza Radio prefiere la de internet (true) o la FM de la pantalla. */
private const val KEY_RADIO_INTERNET = "radio_internet"
/** Tu app de música (método App). */
private const val KEY_PREFERRED = "preferred_app"
/** El método de música elegido ([MusicMode]). */
private const val KEY_MODE = "music_mode"
/** El método que había antes de que el celular (LiteDash Connect) lo pasara a Bluetooth. */
private const val KEY_MODE_HELD = "music_mode_held"
/** Migraciones ([MusicRepository.upgrade]): ya se pasó la app de música de la app (1.4.116) y ya se revisó el Bluetooth (1.4.107). */
private const val KEY_PREFERRED_MOVED = "preferred_moved"
private const val KEY_BT_FIXED = "bt_link_fixed"

/** Lo que se espera tras irse el celular antes de regresar el método: Android cierra y reabre Connect a ratos. */
private const val PHONE_LEFT_MS = 20_000L
/** Lo que se espera la carátula que el celular anunció antes de buscarla por título y artista. */
private const val PHONE_ART_WAIT_MS = 3_000L
/** Palabras con las que se reconoce la app de radio de la pantalla (en el paquete o en el nombre). */
private val RADIO_WORDS = listOf("radio", "fm")

/**
 * Lo que tarda una app en contestar a un play: que suene la que se despertó (si no, se abre la de la pantalla), que suene
 * la de música (si no, se abre y se regresa) y que su servicio de música conecte.
 */
private const val APP_ANSWER_MS = 4_000L
/** Tras abrir una app de música porque no sonaba, cuánto se espera para regresar y darle play otra vez (Spotify tarda en despertar). */
private const val APP_RETURN_MS = 4_000L
/** Tras abrir la radio de fábrica, cuándo se revisa si Android tiene audio (el regreso es a los [FACTORY_RETURN_MS]). */
private const val RADIO_VERIFY_MS = 2 * FACTORY_RETURN_MS
/** Lo que tarda el sintonizador de fábrica en contestar (la estación o que ya tiene el audio). */
private const val FACTORY_ANSWER_MS = 2_000L
/** Lo que LiteDash supone de una app de fábrica (suena / en pausa, o que prendió la radio) deja de valer tras este rato (p. ej. apagaron el auto). */
private const val ASSUME_MAX_MS = 3 * 60 * 60_000L
/** Cuántos eventos guarda el registro de "Enviar un reporte". */
private const val TRACE_SIZE = 60
/** Un aviso (broadcast) de qué suena vale como respaldo durante este rato. */
private const val BROADCAST_FRESH_MS = 10 * 60_000L
/** Una carátula del Bluetooth que no se encontró se vuelve a pedir tras este rato (no en cada lectura de AVRCP). */
private const val COVER_RETRY_MS = 60_000L

/** Las acciones de play/pausa que una sesión debe anunciar para mandarle la orden por sus controles. */
private const val PLAY_ACTIONS = PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE

// Ofrecen su audio a Android pero no son apps de música (el asistente, video).
private val NOT_MUSIC_APPS = setOf(
    "com.google.android.googlequicksearchbox", "com.google.android.youtube", "com.google.android.gms",
)

/** Una orden de la música y su tecla multimedia (y lo que se le manda al celular). */
private enum class Command(val key: Int, val phone: String, val label: String) {
    PLAY(KeyEvent.KEYCODE_MEDIA_PLAY, "play", "play"),
    PAUSE(KeyEvent.KEYCODE_MEDIA_PAUSE, "pause", "pausa"),
    NEXT(KeyEvent.KEYCODE_MEDIA_NEXT, "next", "siguiente"),
    PREVIOUS(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "prev", "anterior"),
}

/** Cómo quedó una app al abrirla. */
private enum class Opened { NONE, BESIDE, OVER }

/** "Sin receptor" en la caché de receptores (el mapa concurrente no guarda nulos). */
private val NO_RECEIVER = ComponentName("", "")

/**
 * El motor de la música: lo que suena en cualquier app (Spotify, YouTube Music, la radio de la pantalla, el celular…)
 * vía las sesiones de medios de Android, los avisos de las apps y LiteDash Connect, y las órdenes que se les mandan.
 * Escucha solo mientras alguna pieza de música o de radio está en pantalla ([use]/[release] cuentan cada una); Ajustes →
 * Celular escucha solo los aparatos ([watchPhone]). Nada de polling.
 *
 * Una función por intención y por ahí pasa todo (widget, volante, la música en grande, el reproductor propio, el celular):
 * [play], [pause], [skip], despertar una app (el camino de [playPlan] con `wake`), abrir y regresar ([openOver]). Las
 * decisiones (a quién y por dónde, si se calla la radio, si suena) viven en MusicRules.kt, sin Android y con pruebas.
 */
class MusicRepository(private val context: Context) {
    private val sessionManager = context.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(context, NotificationService::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Piezas de música (widget o en grande) y de radio a la vista: cada una arranca solo lo suyo. */
    private var musicUsers = 0
    private var radioUsers = 0
    private val users get() = musicUsers + radioUsers

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
    private val store = context.getSharedPreferences(MUSIC_STORE, Context.MODE_PRIVATE)
    // Spotify avisa a cada rato: solo se escribe al disco cuando cambia la app.
    private var lastSavedApp: String? = store.getString(KEY_LAST_APP, null)

    /**
     * Tu app de música, elegida en Ajustes ([choose]); null = ninguna: la pieza muestra lo que
     * suene. Es lo que sigue el método App ([mode]). Se guarda aquí (una sola verdad).
     */
    val preferred: String? get() = preferredState
    // Estado de Compose: Ajustes resalta la elegida al momento.
    private var preferredState by mutableStateOf(store.getString(KEY_PREFERRED, null))

    // —— Migraciones (SE QUEDAN: ~35 % de las pantallas siguen en versiones anteriores y las necesitan al actualizar) ——

    /**
     * Lo que se pasa una sola vez de versiones viejas; lo llama la app al armar sus dependencias, en este orden:
     * 1. `preferred_moved` (1.4.116): la app de música vivía en las preferencias de la app ([musicApp]).
     * 2. `source_usb` (1.4.57): la "app de USB" enlazada pasa a ser tu app de música si la eligió el usuario.
     * 3. `bt_link_fixed` (1.4.107): la app de Bluetooth adivinada por palabras (p. ej. ZLink en una TopWay) se cambia
     *    por la de marca instalada.
     * 4. `radio_unified` (1.4.111): la radio por internet tenía además su interruptor en la app ([internetRadio]; null =
     *    ya se pasó): queda la que sonaba en el widget.
     * No son migraciones (se quedan siempre): `music_mode_held` ([savedMode]) y `sources_guessed` ([guessSources]).
     */
    fun upgrade(musicApp: String?, internetRadio: Boolean?) {
        if (!store.getBoolean(KEY_PREFERRED_MOVED, false)) {
            store.edit().putBoolean(KEY_PREFERRED_MOVED, true).apply()
            if (musicApp != null && preferredState == null) choose(musicApp)
        }
        store.getString(SourceKind.USB.key, null)?.let { old ->
            store.edit().remove(SourceKind.USB.key).apply()
            // Las del sistema eran adivinadas y no se suben.
            if (preferred == null && !isSystemApp(context, old, unknown = true)) choose(old)
        }
        if (!store.getBoolean(KEY_BT_FIXED, false)) {
            store.edit().putBoolean(KEY_BT_FIXED, true).apply()
            val known = knownBluetooth
            val linked = sourceApps[SourceKind.BLUETOOTH]
            if (known != null && linked != null && linked != known && linked !in HeadUnit.bluetoothApps) setSource(SourceKind.BLUETOOTH, known)
        }
        if (internetRadio != null) radioInternet = internetRadio && (radioInternet || sourceApps[SourceKind.RADIO] == null)
    }

    // —— Método de música y el celular ——

    /**
     * El método de música, uno solo a la vez (Ajustes → Apps → Música): Archivos (el reproductor de
     * LiteDash), Bluetooth (el celular) o App (tu app de música; sin elegir, la que suene). Se guarda.
     */
    var mode: MusicMode
        get() = modeState
        set(v) {
            // Lo eligió el usuario: eso gana (también si eligió el Bluetooth que estaba prestado), y mientras el
            // celular siga aquí ya no se le vuelve a prestar ni se regresa al de antes.
            if (phoneState.here) phoneState = phoneState.copy(userChose = true)
            releaseHold()
            switchMode(v)
        }

    /**
     * Cambia el método. Con [silence] (lo de siempre) solo suena el nuevo: los demás se callan y, al dejar
     * Archivos, el reproductor propio se detiene (en pausa su sesión seguiría recibiendo el play del sistema y
     * sonaría la USB). Sin [silence] solo cambia qué muestra la pieza: es para devolver el método prestado.
     */
    private fun switchMode(v: MusicMode, silence: Boolean = true) {
        if (v == modeState) return
        if (silence) {
            stopOthers(pkgFor(v))
            if (modeState == MusicMode.FILES && ownSourceNow?.invoke() == SourceKind.USB) ownStop?.invoke()
        }
        modeState = v
        store.edit().putString(KEY_MODE, v.name).apply()
        // Al pasar a Bluetooth con el celular sonando, su música empieza aquí.
        if (v == MusicMode.BLUETOOTH) { if (phoneState.onBluetooth) phoneMusicStarted() } else releaseBluetoothRecipe()
        // Lo que se supo del método anterior (avisos, supuestos) ya no vale.
        music.clear()
        if (users > 0) choose(sessions())
    }

    private var modeState by mutableStateOf(savedMode())

    /**
     * El método guardado. Si LiteDash se cerró con el celular conectado, lo guardado es el Bluetooth
     * prestado: se regresa el de antes (al reconectarse el celular, se vuelve a prestar).
     */
    private fun savedMode(): MusicMode {
        fun read(key: String) = store.getString(key, null)?.let { m -> MusicMode.entries.firstOrNull { it.name == m } }
        val held = read(KEY_MODE_HELD)
        if (held != null) store.edit().putString(KEY_MODE, held.name).remove(KEY_MODE_HELD).apply()
        return held ?: read(KEY_MODE) ?: MusicMode.APP
    }

    /** El método que el usuario tenía mientras el celular conectado presta el Bluetooth; null = nada prestado. */
    private var heldMode by mutableStateOf<MusicMode?>(null)

    /** El método es Bluetooth porque el celular (LiteDash Connect) está conectado, no porque se eligiera. */
    val phoneHolds: Boolean get() = heldMode != null

    /** El celular con LiteDash Connect ([phoneStep]). */
    private var phoneState = PhoneState()

    /**
     * Cómo va el celular con LiteDash Connect: [connected], su música suena ([playing]) y llega por Bluetooth
     * ([bluetooth], no por su bocina). Lo que hace con eso lo decide [phoneStep] (pasar la música a Bluetooth mientras
     * suena y regresarla al irse sin callar nada).
     */
    fun phone(connected: Boolean, playing: Boolean, bluetooth: Boolean) {
        val step = phoneStep(phoneState, connected, playing, bluetooth, modeState, heldMode != null, btApplied != null)
        phoneState = step.state
        mainHandler.removeCallbacks(phoneLeft)
        if (step.leaveLater) mainHandler.postDelayed(phoneLeft, PHONE_LEFT_MS)
        if (step.hold) {
            heldMode = modeState
            store.edit().putString(KEY_MODE_HELD, modeState.name).apply()
            // Al pasar a Bluetooth, si suena por ahí, su música empieza ([switchMode]).
            switchMode(MusicMode.BLUETOOTH)
        }
        if (step.started && !step.hold) phoneMusicStarted()
    }

    private val phoneLeft = Runnable {
        releaseBluetoothRecipe()
        val back = heldMode ?: return@Runnable
        releaseHold()
        switchMode(back, silence = false)
    }

    private fun releaseHold() {
        mainHandler.removeCallbacks(phoneLeft)
        if (heldMode == null) return
        heldMode = null
        store.edit().remove(KEY_MODE_HELD).apply()
    }

    /** La música del celular empezó a sonar por Bluetooth sin un play de LiteDash: la radio se calla si suena, y su receta. */
    private fun phoneMusicStarted() {
        quietRadio(bluetoothApp())
        calibrateBluetooth()
    }

    /** La app que toca [m]: LiteDash, la del Bluetooth o tu app de música (null = la que suene). */
    private fun pkgFor(m: MusicMode): String? = when (m) {
        MusicMode.FILES -> ownPackage
        MusicMode.BLUETOOTH -> bluetoothApp()
        MusicMode.APP -> preferred
    }

    /** La app del método elegido (la que la pieza Música sigue y despierta); null = la que suene. */
    val modeApp: String? get() = pkgFor(mode)

    /** Archivos sin nada que tocar: la vista abre la biblioteca (lo pone la música en grande mientras se ve). */
    var onFilesEmpty: (() -> Unit)? = null

    /**
     * Apps de la pantalla enlazadas: la de radio FM (Ajustes → Apps → Radio) y, en pantallas con chip de
     * Bluetooth propio, la de Bluetooth; se guardan en [store]. Archivos no se enlaza: es el reproductor de LiteDash.
     * (`source_usb` solo lo lee la migración de [upgrade].)
     */
    var sourceApps by mutableStateOf(SourceKind.entries.filter { it != SourceKind.USB }.mapNotNull { k -> store.getString(k.key, null)?.let { k to it } }.toMap())
        private set

    /** Enlaza (o con null desenlaza) la app de Bluetooth o la de radio. */
    fun setSource(kind: SourceKind, pkg: String?) {
        sourceApps = if (pkg == null) sourceApps - kind else sourceApps + (kind to pkg)
        // Al enlazar otra vez (quizá tras actualizar la app de la pantalla) se vuelve a preguntar su receptor y su pantalla.
        if (pkg != null) receivers.remove(pkg)
        launches.clear()
        // Lo que se suponía de la app anterior ya no vale (la radio lo olvida en radioChanged()).
        if (kind == SourceKind.BLUETOOTH) music.forget()
        store.edit().putString(kind.key, pkg).apply()
        if (users > 0) loadSourceNotes()
        if (kind == SourceKind.RADIO) radioChanged()
        // Enlazar o quitar la radio cambia qué pieza se queda con cada sesión.
        if (users > 0) choose(sessions())
    }

    private val SourceKind.key get() = "source_" + name.lowercase()

    /**
     * Cambió con qué suena la pieza Radio (se enlazó o quitó la FM, o se pasó entre la FM y la de internet): lo que se supo
     * de la anterior (su estación, el play que se le mandó o se le confirmaba) ya no vale, y su perfil solo se abre si la FM
     * es la radio escogida y la pieza Radio está a la vista.
     */
    private fun radioChanged() {
        mainHandler.removeCallbacks(profileAnswer)
        fmCalibration.abort()
        radioClaimedAt = null
        radio.clear()
        if (radioUsers > 0) startFactory()
    }

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

    /** La app que toca la radio escogida: LiteDash si es la de internet, si no la FM enlazada (null = ninguna). */
    val radioApp: String? get() = if (radioInternet) ownPackage else sourceApps[SourceKind.RADIO]

    /**
     * La pieza Música necesita el acceso a notificaciones para ver qué suena: con App sí (otras apps)
     * y con Bluetooth de chip propio también; con Archivos o el Bluetooth de Android, no.
     */
    val needsAccess: Boolean
        get() = mode == MusicMode.APP || (mode == MusicMode.BLUETOOTH && !bluetoothBuiltIn)

    /** La pantalla trae el Bluetooth de Android (AVRCP): la fuente Bluetooth no pide app. */
    val bluetoothBuiltIn: Boolean get() = bluetoothService != null

    /** La app que toca el Bluetooth: el propio Android (AVRCP) si lo trae, si no la enlazada. */
    fun bluetoothApp(): String? = bluetoothService?.packageName ?: sourceApps[SourceKind.BLUETOOTH] ?: knownBluetooth

    /** La app de Bluetooth de marca instalada (chip propio), si hay. */
    private val knownBluetooth: String? by lazy {
        HeadUnit.bluetoothMusicApps.firstOrNull(context::hasPackage)
    }

    /** La familia de la pantalla (por sus paquetes): sus recetas de sonido. */
    private val headUnit: HeadUnit by lazy { HeadUnit.detect(context::hasPackage) }

    /**
     * La radio escogida (Ajustes → Apps → Radio): la de internet de LiteDash (true) o la FM de la
     * pantalla. Una sola a la vez; se guarda.
     */
    var radioInternet: Boolean
        get() = radioInternetState
        set(v) {
            if (v == radioInternetState) return
            // Al pasar a internet, la FM de fábrica se calla si se sabe que suena (si no, sonarían las dos).
            if (v) quietRadio(ownPackage)
            radioInternetState = v
            store.edit().putBoolean(KEY_RADIO_INTERNET, v).apply()
            radioChanged()
            // Al pasar a la FM, la de internet se calla (si no, su sesión en pausa seguiría mandando).
            if (!v && ownSourceNow?.invoke() == SourceKind.RADIO) ownStop?.invoke()
        }

    /** La sesión del reproductor propio (lo pone el app): se lee directo, sin el permiso de avisos. */
    var ownSession: (() -> MediaSession.Token?)? = null

    /** El reproductor propio creó su sesión: la pieza la toma (sin el permiso de avisos nadie más avisaría). */
    fun ownSessionChanged() {
        mainHandler.post { if (users > 0) choose(sessions()) }
    }

    /**
     * El reproductor propio empezó a tocar la USB (también con el volante a su sesión, sin pasar por [play]): la música va
     * a sonar y la radio de fábrica se calla si se sabe que suena.
     */
    fun ownMusicStarted() = quietRadio(ownPackage)

    /** Detiene el reproductor propio (lo pone el app). */
    var ownStop: (() -> Unit)? = null
    private var radioInternetState by mutableStateOf(store.getBoolean(KEY_RADIO_INTERNET, false))

    /** Lo pone el app: ¿ese paquete es una app de proyección (Android Auto / CarPlay en la pantalla)? */
    var isProjection: ((String) -> Boolean)? = null

    /**
     * Deja una sola fuente sonando: calla la USB propia (salvo [keep]) y toda sesión de la pieza
     * Música que esté sonando (salvo [keep] y la radio, que vive en su propia pieza). La música
     * "a ciegas" (Bluetooth/FM sin sesión que LiteDash da por sonando) se pausa como el botón ([pause]).
     */
    private fun stopOthers(keep: String?) {
        if (keep != ownPackage) ownPause?.invoke()
        for (c in sessions()) {
            val p = c.packageName
            // Android Auto / CarPlay de la pantalla (ZLink…) llevan su propio audio: nunca se les pausa.
            if (p == keep || isRadio(p) || isProjection?.invoke(p) == true) continue
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) {
                runCatching { c.transportControls.pause() }.onFailure { trace("pausa a $p falló (${it.javaClass.simpleName})") }
            }
        }
        val blind = music.song?.takeIf { it.blind && it.playing && it.pkg.isNotBlank() }
        if (blind != null && blind.pkg != keep) pause(music)
    }

    // Íconos de las acciones de otras apps (se sueltan al esconderse la música).
    private val icons = HashMap<String, Bitmap?>()
    // Nombres de las apps: se piden una vez a Android (es una llamada entre procesos). Concurrente: el reporte los lee en otro hilo.
    private val labels = ConcurrentHashMap<String, String>()

    fun hasPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component)
        } else {
            Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                ?.contains(component.flattenToString()) == true
        }

    fun requestPermission() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure { trace("no se abrió el permiso de avisos (${it.javaClass.simpleName})") }
    }

    private val onSessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { choose(it.orEmpty()) }

    // Celular por Bluetooth y memoria USB: solo se escuchan mientras la música (o Ajustes → Celular) está en pantalla.
    private val devices = DeviceWatcher(context, onBluetooth = ::phoneChanged, onUsb = { usbDrive = it })
    private var devicesOn = false
    private var phoneWatchers = 0

    /**
     * Se puede saber si hay celular por Bluetooth: la pantalla usa el Bluetooth de Android (AVRCP) y deja
     * ver sus aparatos. Con chip propio, o sin el permiso de ver su nombre (Android 12+), no se sabe.
     */
    val phoneKnowable: Boolean
        get() = bluetoothBuiltIn && context.getSystemService(BluetoothManager::class.java)?.adapter != null && devices.canSeeDevices()

    /** Ajustes → Celular a la vista: escucha solo los aparatos (no la música) para decir si hay celular. */
    fun watchPhone(on: Boolean) {
        phoneWatchers = (phoneWatchers + if (on) 1 else -1).coerceAtLeast(0)
        // Sin la música a la vista, lo de antes pudo quedar viejo: se vuelve a preguntar.
        if (on && !devicesOn) bluetoothDevice = null
        syncDevices()
    }

    private fun syncDevices() {
        val want = users > 0 || phoneWatchers > 0
        if (want == devicesOn) return
        devicesOn = want
        if (want) devices.start() else devices.stop()
    }

    // El Bluetooth de Android como receptor (A2DP sink): si la pantalla trae el servicio AVRCP,
    // LiteDash lee y controla la música del celular sin app externa. Conectado mientras la música
    // esté a la vista (sin celular, su sesión simplemente no trae canción). No depende de poder ver
    // el nombre del celular: sin el permiso BLUETOOTH_CONNECT nunca se conectaba.
    private var avrcp: BluetoothAvrcp? = null
    // Lo lee también el reporte en otro hilo.
    @Volatile private var avrcpController: MediaController? = null
    /** El servicio AVRCP del Bluetooth de Android; se busca una vez, sin importar si la música está a la vista. */
    private val bluetoothService: ServiceInfo? by lazy { BluetoothAvrcp.find(context) }

    // El celular llegó o se fue: la música Bluetooth que se suponía sonando ya no suena.
    private fun phoneChanged(name: String?) {
        bluetoothDevice = name
        // Solo Ajustes → Celular escuchando: nada de la música se toca.
        if (users == 0) return
        if (name != null) {
            avrcp?.connect()
            return
        }
        val app = bluetoothApp() ?: return
        for (lens in listOf(music, radio)) if (lens.assumedPkg == app) lens.forget()
        choose(sessions())
    }

    /** Si la pantalla trae el Bluetooth de Android (AVRCP), se prepara para leer la música del celular. */
    private fun startAvrcp() {
        // Ya existe (la música se escondió y volvió): stop() solo lo desconectó, se vuelve a conectar.
        avrcp?.let { it.connect(); return }
        val service = bluetoothService ?: return
        avrcp = BluetoothAvrcp(context, service, ::onAvrcpController)
        avrcp?.connect()
    }

    /**
     * El controlador AVRCP ya (des)conectó. Su sesión no siempre llega por las sesiones activas (sin
     * el permiso de avisos, nunca): cuando cambia de estado y ninguna pieza la tiene, se vuelve a repartir.
     */
    private fun onAvrcpController(controller: MediaController?) {
        mainHandler.post {
            avrcpController?.unregisterCallback(avrcpWatch)
            avrcpController = controller
            avrcpLast = controller?.playbackState?.state
            controller?.registerCallback(avrcpWatch, mainHandler)
            if (users > 0) choose(sessions())
        }
    }

    private var avrcpLast: Int? = null
    private val avrcpWatch = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            if (state?.state == avrcpLast) return
            avrcpLast = state?.state
            avrcpRechoose()
        }
        override fun onMetadataChanged(metadata: MediaMetadata?) = avrcpRechoose()
    }

    /** Solo si la pieza podría tomarla (Bluetooth, o App sin app elegida) y aún no la tiene: cada vuelta es IPC. */
    private fun avrcpRechoose() {
        if (users == 0 || !(mode == MusicMode.BLUETOOTH || (mode == MusicMode.APP && preferred == null))) return
        val token = avrcpController?.sessionToken
        if (listOf(music, radio).none { it.controller?.sessionToken == token }) choose(sessions())
    }

    // —— Piezas a la vista: cada una enciende lo suyo y lo suelta al irse ——

    /** Una pieza de [piece] (widget o en grande) entra a la pantalla. */
    fun use(piece: SoundPiece) {
        if (piece == SoundPiece.MUSIC) musicUsers++ else radioUsers++
        if (users == 1) start()
        if (piece == SoundPiece.RADIO && radioUsers == 1) startRadio()
        loadCatalog()
    }

    /** La pieza de [piece] sale de la pantalla. */
    fun release(piece: SoundPiece) {
        if (piece == SoundPiece.MUSIC) musicUsers = (musicUsers - 1).coerceAtLeast(0) else radioUsers = (radioUsers - 1).coerceAtLeast(0)
        if (piece == SoundPiece.RADIO && radioUsers == 0) stopRadio()
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
            val extras = i.extras.toMap()
            trace("aviso ${i.action} [${extras.keys.joinToString()}]")
            val meta = BroadcastMeta.parse(i.action ?: return, extras) ?: return
            val fm = meta.pkg != null && meta.pkg == sourceApps[SourceKind.RADIO]
            // La FM avisando mientras la pieza Radio está en internet: no es lo que se escogió.
            if (fm && radioInternet) return
            // A la música, solo lo del método escogido: con Archivos no se cuela el Bluetooth de la pantalla.
            if (!fm && !musicHears(meta)) return
            (if (fm) radio else music).heard(meta)
        }
    }

    /** ¿El aviso [meta] es del método de música escogido? Bluetooth: los del celular; App: la elegida o cualquiera. */
    private fun musicHears(meta: BroadcastMeta): Boolean = when (mode) {
        MusicMode.FILES -> false
        MusicMode.BLUETOOTH -> meta.bluetooth || meta.pkg == bluetoothApp()
        MusicMode.APP -> modeApp == null || meta.pkg == modeApp
    }

    /** La primera pieza (de música o de radio) entró: lo que las dos usan (sesiones, avisos, AVRCP, aparatos). */
    private fun start() {
        syncDevices()
        startAvrcp()
        // Son avisos de otras apps: el receptor debe aceptar avisos de fuera. Cualquier app podría mandar
        // un título falso; solo se muestra (sin sesión de por medio) y la sesión real siempre manda.
        val filter = IntentFilter().apply { BroadcastMeta.ACTIONS.forEach(::addAction) }
        ContextCompat.registerReceiver(context, broadcasts, filter, ContextCompat.RECEIVER_EXPORTED)
        NotificationService.media = notifications
        loadSourceNotes()
        listen()
        // Sin permiso de avisos, igual se ven la sesión propia (Archivos) y la del Bluetooth de Android;
        // lo demás, lo último avisado por broadcast.
        if (!listening) choose(sessions())
    }

    /** La pieza Radio entró: el perfil de la radio de fábrica y, la primera vez, adivinar su app. */
    private fun startRadio() {
        startFactory()
        if (!store.getBoolean(KEY_SOURCES_GUESSED, false)) {
            store.edit().putBoolean(KEY_SOURCES_GUESSED, true).apply()
            Thread(::guessSources, "music-sources").start()
        }
    }

    /** La pieza Radio salió: su perfil se cierra (se queda, con la última estación, para el reporte). */
    private fun stopRadio() {
        factoryRun++
        factory?.close()
    }

    private fun listen() {
        if (!hasPermission()) return
        runCatching {
            sessionManager.addOnActiveSessionsChangedListener(onSessionsChanged, component, mainHandler)
            listening = true
            choose(sessions())
        }.onFailure { trace("no se pudo escuchar las sesiones (${it.javaClass.simpleName})") }
    }

    /**
     * Ya no hay piezas a la vista. Regla de los plazos: los que esperan que algo suene para hacer algo en pantalla (el
     * "Abriendo…" de un despertar, abrir la app que no suena) se cancelan; el regreso tras abrir una app encima y su
     * revisión no (abrirla es justo lo que esconde la música; se cancelan si LiteDash vuelve al frente: [cancelReturn]).
     */
    private fun stop() {
        syncDevices()
        avrcp?.disconnect()
        avrcpController?.unregisterCallback(avrcpWatch)
        avrcpController = null
        runCatching { context.unregisterReceiver(broadcasts) }
        if (NotificationService.media === notifications) NotificationService.media = null
        if (listening) runCatching { sessionManager.removeOnActiveSessionsChangedListener(onSessionsChanged) }
        listening = false
        stopRadio()
        mainHandler.removeCallbacks(playCheck)
        for (lens in listOf(music, radio)) {
            mainHandler.removeCallbacks(lens.wakeTimeout)
            lens.starting = null
            lens.detach()
        }
        icons.clear()
        // El aviso de que no se encontró cómo es del momento; una pregunta pendiente se quita sola sin respuesta.
        dismissSoundCheck()
    }

    // —— Radio de fábrica: su perfil ([FactoryRadio]) ——

    /** El perfil de la radio de fábrica de la FM enlazada, si su marca lo tiene (ver [FactoryRadioKind]). */
    private var factory: FactoryRadio? = null
    /** Sube en cada arranque o parada: un perfil que termina de abrir tarde se descarta. */
    private var factoryRun = 0
    /**
     * Los perfiles se abren de uno en uno: si se piden dos seguidos (la radio se esconde y vuelve), el
     * segundo espera al primero en vez de ver la marca de seguridad a medio abrir y creer que la app se cerró.
     */
    private val factoryThread = Executors.newSingleThreadExecutor { Thread(it, "factory-radio") }

    /** Se arma en otro hilo (reflexión, el driver y el disco) y se abre en el principal. Solo si la FM es la radio escogida. */
    private fun startFactory() {
        factory?.close()
        factory = null
        val run = ++factoryRun
        val pkg = radioApp?.takeUnless(::isOwn) ?: return
        factoryThread.execute {
            if (run != factoryRun) return@execute
            val p = profileFor(pkg) ?: return@execute
            mainHandler.post { if (run == factoryRun && p.open()) factory = p else p.close() }
        }
    }

    /** El perfil de la marca de [pkg], armado pero sin abrir; null si ninguna marca la reconoce. En el hilo de los perfiles. */
    private fun profileFor(pkg: String): FactoryRadio? {
        val kind = radioKind(pkg) ?: return null
        return kind.create(context, store, { radio.heard(it.copy(pkg = pkg)) }, { e -> mainHandler.post { trace(e) } })
    }

    /** La marca de la FM [pkg]; las que solo se usan sin receptor ni servicio lo revisan aquí (sin caché: puede ir en otro hilo). */
    private fun radioKind(pkg: String): FactoryRadioKind? {
        val silent by lazy { isSilent(pkg) }
        return HeadUnit.factoryRadios.firstOrNull { it.matches(pkg) && (!it.needsSilent || silent) }
    }

    /** Sin receptor del botón de play ni servicio de música: no hay cómo hablarle (sin caché: puede ir en otro hilo). */
    private fun isSilent(pkg: String) = mediaButtonReceivers(pkg).isEmpty() && browserServices(context, pkg).isEmpty()

    /** La radio de la pieza se controla por el perfil de su marca. */
    private fun factoryFor(lens: Lens): FactoryRadio? = factory?.takeIf { lens === radio && it.open }

    /**
     * Las sesiones activas y, de las apps enlazadas (Bluetooth, FM), la de su aviso de música si
     * Android no la cuenta como activa (algunas pantallas solo la dejan ver por ahí).
     */
    private fun sessions(): List<MediaController> {
        val active = runCatching { sessionManager.getActiveSessions(component) }.getOrDefault(emptyList())
        val extra = sourceNotes.values.filter { n -> active.none { it.packageName == n.packageName } }.mapNotNull { n ->
            n.sessionToken()?.let { runCatching { MediaController(context, it) }.getOrNull() }
        }
        val own = ownSession?.invoke()?.takeIf { t -> (active + extra).none { it.sessionToken == t } }
            ?.let { runCatching { MediaController(context, it) }.getOrNull() }
        val all = active + extra + listOfNotNull(own)
        // El Bluetooth de Android (AVRCP) no sale entre las sesiones activas: se agrega.
        val av = avrcpController ?: return all
        return if (all.any { it.packageName == av.packageName }) all else all + av
    }

    /** La sesión de [pkg]: la que ve [lens] si es suya, si no la primera de las activas. */
    private fun sessionOf(pkg: String, lens: Lens): MediaController? =
        lens.controller?.takeIf { it.packageName == pkg } ?: sessions().firstOrNull { it.packageName == pkg }

    /**
     * El aviso de música de cada app enlazada (paquete → aviso), al día con lo que pasa el servicio:
     * así no se le pide a Android la lista de todos los avisos (con sus imágenes) en cada lectura.
     * Se escribe en el hilo principal; concurrente porque el reporte lo lee ([sessions]) en otro hilo.
     */
    private val sourceNotes = ConcurrentHashMap<String, StatusBarNotification>()

    /** Vuelve a leer los avisos de las apps enlazadas (al empezar, al conectarse el servicio o al enlazar otra). */
    private fun loadSourceNotes() {
        sourceNotes.clear()
        val apps = sourceApps.values.toSet()
        if (apps.isEmpty()) return
        NotificationService.active().filter { it.packageName in apps && it.isMedia() }.forEach { sourceNotes[it.packageName] = it }
    }

    /** Título y texto del aviso de música de [pkg]; null si no tiene. */
    private fun notified(pkg: String): Pair<String, String>? {
        val extras = sourceNotes[pkg]?.notification?.extras ?: return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        return if (title.isEmpty()) null else title to extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
    }

    /**
     * Si una app enlazada cambia su aviso de música (otra sesión u otra canción), se vuelve a mirar.
     * Los avisos que no cambian eso (algunas apps lo actualizan cada segundo con el avance) no hacen nada.
     */
    private val notifications = object : NotificationListener {
        override val wanted: Boolean get() = users > 0
        override fun onPosted(notification: StatusBarNotification) = changed(notification, removed = false)
        override fun onRemoved(notification: StatusBarNotification) = changed(notification, removed = true)
        override fun onConnected() {
            mainHandler.post {
                if (users == 0) return@post
                loadSourceNotes()
                choose(sessions())
            }
        }

        private fun changed(n: StatusBarNotification, removed: Boolean) {
            mainHandler.post {
                val pkg = n.packageName
                if (users == 0 || pkg !in sourceApps.values) return@post
                val before = sourceNotes[pkg]?.let { it.sessionToken() to notifiedTitle(it) }
                when {
                    !removed && n.isMedia() -> sourceNotes[pkg] = n
                    sourceNotes[pkg]?.key == n.key -> sourceNotes.remove(pkg)
                }
                val after = sourceNotes[pkg]?.let { it.sessionToken() to notifiedTitle(it) }
                if (before == after) return@post
                trace("aviso de música ${if (after == null) "quitado" else "nuevo"} $pkg")
                choose(sessions())
            }
        }

        private fun notifiedTitle(n: StatusBarNotification) = n.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    }

    /** ¿[pkg] es la radio: la app FM enlazada de la pantalla o LiteDash tocando radio por internet? */
    fun isRadio(pkg: String): Boolean =
        pkg == sourceApps[SourceKind.RADIO] || (isOwn(pkg) && ownSourceNow?.invoke() == SourceKind.RADIO)

    /** La pieza Música: todo menos la radio; sigue la app del método elegido ([modeApp]) o, sin ella, lo que suene. */
    val music = Lens(accepts = { !isRadio(it) }, fixed = { modeApp }, remembersLast = true, anyApp = { mode == MusicMode.APP })

    /** La pieza Radio: solo la radio de la pantalla (FM) o la de LiteDash (internet). */
    val radio = Lens(accepts = { isRadio(it) }, fixed = { null }, remembersLast = false, anyApp = { true })

    /**
     * Lo que suena en el celular por LiteDash Connect (Link); null = nada o sin conexión. Entra en el método
     * Bluetooth (y en App sin app elegida o con la de Bluetooth, si no suena nada en la pantalla) y manda
     * sobre AVRCP y los avisos de la marca: el dato sale del celular, igual en todas las pantallas.
     */
    private var phoneSong: Song? = null
    /** El celular tiene carátula y llega aparte: mientras, no se busca en iTunes. */
    private var phoneArtComing = false

    /** Manda play, pausa, siguiente, anterior o salto (ms) al celular; falso si ya no hay enlace. */
    var phoneControl: ((String, Long) -> Boolean)? = null

    fun fromPhone(song: Song?, artComing: Boolean) {
        phoneSong = song
        phoneArtComing = artComing
        // Si la carátula anunciada no llega pronto, se busca como cualquier canción por Bluetooth.
        mainHandler.removeCallbacks(artLate)
        if (artComing) mainHandler.postDelayed(artLate, PHONE_ART_WAIT_MS)
        if (users > 0) music.read()
    }

    private val artLate = Runnable {
        phoneArtComing = false
        if (users > 0) music.read()
    }

    /** La canción del celular, si toca mostrarla en [lens]. */
    private fun phoneFor(lens: Lens): Song? {
        val s = phoneSong?.takeIf { lens === music } ?: return null
        return when (mode) {
            MusicMode.FILES -> null
            // El celular en pausa no le gana a otra música por Bluetooth que sí suena (otro celular).
            MusicMode.BLUETOOTH -> s.takeIf { it.playing || lens.controller?.playbackState?.state != PlaybackState.STATE_PLAYING }
            MusicMode.APP -> {
                val app = modeApp
                if (app != null && app != bluetoothApp()) return null
                // Algo de la pantalla suena (que no sea el Bluetooth): eso es lo que se escucha.
                val local = lens.controller?.takeIf { it.packageName != avrcpPackage && it.packageName != bluetoothApp() }
                s.takeIf { local?.playbackState?.state != PlaybackState.STATE_PLAYING && (s.playing || local == null) }
            }
        }
    }

    /** Cada pieza elige su sesión de la misma lista. */
    private fun choose(list: List<MediaController>) {
        // Otra app empezó a sonar: lo que LiteDash suponía de la de Bluetooth o FM ya no vale.
        val playing = list.filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }.map { it.packageName }
        for (lens in listOf(music, radio)) if (lens.assumedPkg != null && playing.any { it != lens.assumedPkg }) lens.forget()
        music.choose(list)
        radio.choose(list)
    }

    /**
     * Una "vista" de las sesiones de medios: la pieza Música y la pieza Radio miran la misma
     * lista pero cada una se queda con lo suyo ([accepts]); [fixed] es la app fija (la elegida
     * en Ajustes) y [remembersLast] si guarda la última app que sonó (para que play la despierte).
     * Sus botones van a [play], [pause] y [skip] del motor.
     */
    @Stable
    inner class Lens internal constructor(
        private val accepts: (String) -> Boolean,
        private val fixed: () -> String?,
        private val remembersLast: Boolean,
        /** Sin app fija, ¿vale cualquiera que suene? Solo con el método App (Archivos y Bluetooth no muestran otras). */
        private val anyApp: () -> Boolean,
    ) {
        var song by mutableStateOf<Song?>(null)
            private set

        /** La app que esta pieza está despertando ("Abriendo Spotify…"); null = nada. */
        var starting by mutableStateOf<String?>(null)
            internal set

        /** El plazo del último despertar: al acabarse se quita el "Abriendo…" (y, si toca, se abre la app). */
        internal var wakeTimeout = Runnable {}

        internal var controller: MediaController? = null

        /**
         * La app de la pantalla (Bluetooth, FM) a la que LiteDash le mandó play o pausa sin que
         * una sesión diga qué hace: se supone que obedeció ([assumedPlaying]) hasta que otra app
         * suene o se cambie de fuente. Así el botón pasa a pausa y la pieza muestra la fuente.
         */
        internal var assumedPkg: String? = null
        private var assumedPlaying = false
        private var assumedAt = 0L

        internal fun assume(pkg: String, playing: Boolean) {
            assumedPkg = pkg
            assumedPlaying = playing
            assumedAt = SystemClock.elapsedRealtime()
            read()
            if (song != null) starting = null
        }

        /** Lo que se supone de [pkg] (suena o no), si se le mandó play o pausa hace menos de [ASSUME_MAX_MS]. */
        private fun assumedFor(pkg: String?): Boolean? =
            assumedPlaying.takeIf { pkg != null && pkg == assumedPkg && SystemClock.elapsedRealtime() - assumedAt < ASSUME_MAX_MS }

        internal fun forget() {
            if (assumedPkg == null) return
            assumedPkg = null
            read()
        }

        /** Olvida todo lo que se supo sin sesión (avisos y supuestos): cambió la app de la pieza. */
        internal fun clear() {
            mainHandler.removeCallbacks(expire)
            mainHandler.removeCallbacks(stationAnswer)
            heard = null
            assumedPkg = null
            starting = null
            read()
        }

        /** Lo último que avisó una app por broadcast ([BroadcastMeta]) y cuándo; respaldo sin sesión. */
        private var heard: BroadcastMeta? = null
        private var heardAt = 0L

        /** Cuándo avisó [pkg] (o su perfil) que suena, si es reciente; null si no. Una de las señales para callar la radio. */
        internal fun heardPlayingAt(pkg: String): Long? =
            heardAt.takeIf { heard?.playing == true && (heard?.pkg == null || heard?.pkg == pkg) && SystemClock.elapsedRealtime() - heardAt < BROADCAST_FRESH_MS }

        /**
         * El aviso dejó de valer. La estación de una radio con perfil solo llega al cambiar: se le vuelve a pedir y se lee
         * cuando contesta ([stationAnswer]; si contesta, el aviso nuevo ya la dejó vigente y no parpadea).
         */
        private val expire = Runnable {
            if (users == 0) return@Runnable
            val f = factoryFor(this) ?: return@Runnable read()
            f.refresh()
            mainHandler.removeCallbacks(stationAnswer)
            mainHandler.postDelayed(stationAnswer, FACTORY_ANSWER_MS)
        }

        /** La estación que se le volvió a pedir al perfil ya debió llegar. */
        private val stationAnswer = Runnable { if (users > 0) read() }

        internal fun heard(meta: BroadcastMeta) {
            heard = meta.over(heard)
            heardAt = SystemClock.elapsedRealtime()
            // Pasado el rato, deja de mostrarse aunque no llegue otro aviso.
            mainHandler.removeCallbacks(expire)
            mainHandler.postDelayed(expire, BROADCAST_FRESH_MS)
            // Una sesión con canción manda; una vacía (Bluetooth de fábrica) no dice nada: el aviso sí.
            if (controller?.metadata.text(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE).isNotEmpty()) return
            read()
            if (song != null) starting = null
        }

        private val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) = read()
            override fun onPlaybackStateChanged(state: PlaybackState?) = read()
            override fun onSessionDestroyed() = this@MusicRepository.choose(sessions())
        }

        /** Toma su sesión de la lista ([pickSession]). */
        internal fun choose(all: List<MediaController>) {
            val views = all.filter { accepts(it.packageName) }.map { c ->
                c to SessionView(
                    pkg = c.packageName,
                    titled = c.metadata.text(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE).isNotEmpty(),
                    playing = c.playbackState?.state == PlaybackState.STATE_PLAYING,
                    busy = c.busy(),
                )
            }
            val chosen = pickSession(views, { it.second }, ownPackage, fixed(), anyApp(), avrcpPackage)?.first
            if (chosen?.sessionToken != controller?.sessionToken) {
                controller?.unregisterCallback(callback)
                controller = chosen
                chosen?.registerCallback(callback, mainHandler)
            }
            read()
        }

        internal fun detach() {
            mainHandler.removeCallbacks(expire)
            mainHandler.removeCallbacks(stationAnswer)
            controller?.unregisterCallback(callback)
            controller = null
            song = null
        }

        internal fun read() {
            phoneFor(this)?.let { phone ->
                song = if (phone.artwork != null || phoneArtComing) phone else withCover(phone)
                starting = null
                return
            }
            val c = controller
            val m = c?.metadata
            if (c == null) {
                song = fromBroadcast() ?: fromAssumed()
                return
            }
            // La sesión de LiteDash cambia de USB a radio (o al revés): que la tome la otra pieza.
            if (!accepts(c.packageName)) return this@MusicRepository.choose(sessions())
            val state = c.playbackState
            val reported = state?.state.let { it == PlaybackState.STATE_PLAYING || it == PlaybackState.STATE_PAUSED || it == PlaybackState.STATE_BUFFERING || it == PlaybackState.STATE_CONNECTING }
            // Una sesión que no dice si suena (con o sin título): lo que LiteDash le mandó.
            val assumed = if (reported) null else assumedFor(c.packageName)
            val playing = assumed ?: (state?.state == PlaybackState.STATE_PLAYING)
            val app = appLabel(c.packageName)
            val shown = notified(c.packageName)
            val named = m.text(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE).ifBlank { shown?.first.orEmpty() }
            // Sesión vacía que ni siquiera dice si suena (la abren las apps de Bluetooth y FM de fábrica
            // solo para atrapar el volante): manda lo que avisó la app o lo que LiteDash le pidió.
            if (named.isBlank() && !reported) {
                val blind = fromBroadcast() ?: fromAssumed()
                if (blind != null) {
                    song = blind
                    return
                }
            }
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
            if (remembersLast && playing && !isOwn(c.packageName) && c.packageName != avrcpPackage && c.packageName != lastSavedApp) {
                lastSavedApp = c.packageName
                store.edit().putString(KEY_LAST_APP, c.packageName).apply()
            }
            song = withCover(Song(
                title = title,
                artist = m.text(MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST).ifBlank { shown?.second.orEmpty() },
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
                blind = assumed != null,
            ))
        }

        /**
         * Sin sesión, lo último avisado por broadcast si es reciente: título, artista y estado
         * (sin carátula ni avance). Sin título, el nombre de la app (o "Bluetooth"), como la sesión.
         */
        private fun fromBroadcast(): Song? {
            val h = heard?.takeIf { SystemClock.elapsedRealtime() - heardAt < BROADCAST_FRESH_MS } ?: return null
            val pkg = h.pkg ?: sourceApps[SourceKind.BLUETOOTH]?.takeIf { h.bluetooth }
            val app = pkg?.let(::appLabel).orEmpty()
            // Si LiteDash le mandó play o pausa después del último aviso, eso manda; si el aviso no dice, suena.
            val sent = assumedFor(pkg ?: assumedPkg)?.takeIf { assumedAt > heardAt }
            val playing = sent ?: h.playing ?: true
            if (h.title == null && !playing) return null
            val title = h.title ?: app.ifBlank { if (h.bluetooth) context.getString(R.string.source_bluetooth) else return null }
            return withCover(blindSong(title, h.artist.orEmpty(), app, playing, pkg.orEmpty(), pkg?.let(::sourceOf) ?: SourceKind.BLUETOOTH.takeIf { h.bluetooth }))
        }

        /** Lo que LiteDash supone de la app a la que le mandó play o pausa ([assume]). */
        private fun fromAssumed(): Song? {
            val pkg = assumedPkg ?: return null
            val playing = assumedFor(pkg) ?: return null
            val kind = sourceOf(pkg)
            val app = appLabel(pkg)
            val fallback = context.getString(if (kind == SourceKind.RADIO) R.string.source_radio else R.string.source_bluetooth)
            return blindSong(app.ifBlank { fallback }, "", app, playing, pkg, kind)
        }

        /** Canción "a ciegas" (sin sesión que la describa): sin carátula, avance ni acciones. */
        private fun blindSong(title: String, artist: String, app: String, playing: Boolean, pkg: String, source: SourceKind?) = Song(
            title = title,
            artist = artist,
            app = app,
            artwork = null,
            playing = playing,
            durationMs = 0,
            positionMs = 0,
            readAt = SystemClock.elapsedRealtime(),
            speed = 1f,
            pkg = pkg,
            seekable = false,
            actions = emptyList(),
            source = source,
            buffering = false,
            error = null,
            blind = true,
        )

        /** La canción cuya carátula se pidió ("título|artista"), la que se encontró y cuándo se pidió. */
        private var coverKey: String? = null
        private var cover: Bitmap? = null
        private var coverAskedAt = 0L

        /**
         * Carátula de respaldo para la música por Bluetooth: si la sesión (o el aviso) no trajo
         * carátula pero sí título y artista, se toma la ya resuelta ([CoverArt.cached], en memoria)
         * o se busca en iTunes ([CoverArt.fetch], que avisa en el hilo principal solo si sigue
         * siendo la misma canción). Fuera de Bluetooth no se busca: su carátula ya llega sola. La
         * misma canción no se vuelve a preguntar en cada lectura (AVRCP manda el avance cada segundo).
         */
        private fun withCover(s: Song): Song {
            if (s.artwork != null || s.source != SourceKind.BLUETOOTH) return s
            val title = s.title
            val artist = s.artist
            if (title.isBlank() || artist.isBlank()) return s
            val key = "$title|$artist"
            val now = SystemClock.elapsedRealtime()
            if (key == coverKey) {
                cover?.let { return s.copy(artwork = it) }
                if (now - coverAskedAt < COVER_RETRY_MS) return s
            }
            coverKey = key
            coverAskedAt = now
            cover = CoverArt.cached(title, artist)
            cover?.let { return s.copy(artwork = it) }
            CoverArt.fetch(context, title, artist) { bmp ->
                if (bmp != null && coverKey == key) cover = bmp
                val cur = song
                if (bmp != null && cur != null && cur.artwork == null && cur.title == title && cur.artist == artist) {
                    song = cur.copy(artwork = bmp)
                }
            }
            return s
        }

        /** El celular ya recibió play o pausa: el botón cambia sin esperar su aviso (que lo confirma). */
        internal fun phoneToggled(playing: Boolean) {
            val s = song ?: return
            val now = SystemClock.elapsedRealtime()
            phoneSong = phoneSong?.copy(playing = playing, positionMs = s.currentPosition(), readAt = now)
            song = s.copy(playing = playing, positionMs = s.currentPosition(), readAt = now)
        }

        /** Play o pausa ([play]/[pause]). Sin nada a la vista, play despierta lo que toque. */
        fun toggle() = if (song?.playing == true) pause(this) else play(this)

        fun next() = skip(this, forward = true)

        fun previous() = skip(this, forward = false)

        /**
         * La radio FM tiene sesión pero no anuncia [action] (varias FM de pantalla publican la
         * sesión solo para mostrar la estación): mejor la tecla a su receptor, como el volante.
         * La música no: sus apps sí atienden la sesión.
         */
        internal fun keysOnly(action: Long): Boolean {
            val c = controller ?: return false
            if (this !== radio || isOwn(c.packageName)) return false
            return (c.playbackState?.actions ?: 0L) and action == 0L && keyReceiver(target(this)) != null
        }

        /** Su sesión a la vista obedece [action]: trae canción de verdad (no supuesta) y la anuncia. */
        internal fun obeys(action: Long): Boolean = controller != null && song?.blind != true && !keysOnly(action)

        fun seekTo(ms: Long) {
            val s = song
            if (s?.phone == true && phoneControl?.invoke("seek", ms) == true) {
                val now = SystemClock.elapsedRealtime()
                phoneSong = phoneSong?.copy(positionMs = ms, readAt = now)
                song = s.copy(positionMs = ms, readAt = now)
                return
            }
            controller?.transportControls?.seekTo(ms)
        }

        fun action(a: MediaAction) = controller?.transportControls?.sendCustomAction(a.id, null)
    }

    /** La fuente a la que está enlazada [pkg] (o la que toca LiteDash); null = una app normal. */
    fun sourceOf(pkg: String): SourceKind? = when {
        isOwn(pkg) -> ownSourceNow?.invoke()
        pkg == avrcpPackage || pkg == knownBluetooth -> SourceKind.BLUETOOTH
        else -> sourceApps.entries.firstOrNull { it.value == pkg }?.key
    }

    /** Paquete del servicio AVRCP del Bluetooth de Android; null si la pantalla no lo trae. */
    private val avrcpPackage: String? get() = bluetoothService?.packageName

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
        // Lo que se supo de la app anterior (avisos, supuestos) no es de la nueva.
        if (pkg != preferredState) music.clear()
        preferredState = pkg
        store.edit().putString(KEY_PREFERRED, pkg).apply()
        if (users > 0) choose(sessions())
    }

    /** La última app que sonó, solo con el método App (con Archivos o Bluetooth no se despierta otra). */
    private fun lastApp(): String? = if (mode == MusicMode.APP) store.getString(KEY_LAST_APP, null) else null

    /** La app a la que va una orden de [lens] que ya muestra algo (o una tecla cuando no hay sesión que la reciba). */
    private fun target(lens: Lens): String? =
        // Lo que se supo sin sesión (aviso o supuesto) manda: la sesión elegida puede ser otra app vacía.
        lens.song?.takeIf { it.blind }?.pkg?.ifEmpty { null } ?: lens.controller?.packageName ?: lens.song?.pkg?.ifEmpty { null }
            ?: if (lens === radio) radioApp else modeApp ?: lastApp()

    // —— Las órdenes: una función por intención ——

    /**
     * Play en [lens], venga de donde venga (widget, volante, la música en grande, la radio). Si se ve algo, a esa app;
     * si no, a la del método elegido, la que se ve en su sesión o la última que sonó (la radio: la escogida). El camino lo
     * decide [playPlan]. Lo que sigue al play también se hace solo aquí: callar la radio si se sabe que suena (antes del
     * play), suponer que la app de fábrica obedeció, el plazo del despertar, vigilar que la app de música suene y la
     * receta del Bluetooth. [onIdle] = la radio sin nada con qué sonar (la pieza abre sus ajustes o la lista).
     */
    private fun play(lens: Lens, onIdle: () -> Unit = {}) {
        val radioLens = lens === radio
        val s = lens.song
        val pkg = if (s != null) target(lens) else if (radioLens) radioApp else modeApp ?: lens.controller?.packageName ?: lastApp()
        val plan = playPlan(
            Target(
                radio = radioLens,
                phone = s?.phone == true,
                own = pkg != null && isOwn(pkg),
                app = pkg != null,
                shown = s != null,
                obeys = s != null && lens.obeys(PLAY_ACTIONS),
                sessionShown = s != null && lens.controller != null,
                profile = factoryFor(lens) != null,
            ),
        )
        if (plan.route.isEmpty()) return onIdle()
        if (plan.route.first() == Channel.OWN) {
            // Al tocar, el reproductor propio avisa que empieza ([ownMusicStarted]): ahí se calla la radio.
            val kind = if (radioLens) SourceKind.RADIO else SourceKind.USB
            ownPlay?.invoke(kind) { ok ->
                if (!ok) if (radioLens) onIdle() else onFilesEmpty?.invoke()
                if (!radioLens && users > 0) choose(sessions())
            }
            return
        }
        if (!radioLens) quietRadio(pkg)
        if (plan.wake && pkg != null) lens.starting = appLabel(pkg)
        val via = deliver(pkg, lens, Command.PLAY, plan.route)
        // El celular confirma con su aviso; si no hubo a quién, nada más que hacer.
        if (via == null) lens.starting = null
        if (via == null || via == Channel.PHONE || pkg == null) return
        // Su servicio además del receptor o la sesión (ver [Plan.serviceToo]); si fue lo único, ya se llamó.
        val hasService = when (via) {
            Channel.SERVICE -> true
            Channel.RECEIVER, Channel.SESSION -> plan.serviceToo && connectAndPlay(pkg, fallback = false)
            else -> false
        }
        val source = sourceOf(pkg) != null
        if (!isOwn(pkg) && assumesPlay(plan, via, source, hasService)) lens.assume(pkg, true)
        if (radioLens) {
            // LiteDash prendió la radio: una de las señales para callarla después.
            radioClaimedAt = SystemClock.elapsedRealtime()
        } else {
            if (via == Channel.SESSION || plan.wake) watchPlay(pkg)
            if (sourceOf(pkg) == SourceKind.BLUETOOTH) calibrateBluetooth()
        }
        // La radio por su perfil o abriéndola tiene su propia confirmación.
        if (plan.wake && via != Channel.PROFILE && via != Channel.OPEN) armWake(lens, pkg, opensAfterWake(plan, via, source))
    }

    /** Pausa en [lens] ([pausePlan]). Sin a quién, nada (nunca despierta otra cosa). */
    private fun pause(lens: Lens) {
        val radioLens = lens === radio
        val s = lens.song
        if (radioLens) {
            // Pausar la radio a media calibración: lo pendiente se deja (no es que no suene).
            fmCalibration.abort()
            mainHandler.removeCallbacks(profileAnswer)
            radioClaimedAt = null
        }
        val pkg = target(lens)
        val plan = pausePlan(
            Target(
                radio = radioLens,
                phone = s?.phone == true,
                own = pkg != null && isOwn(pkg),
                app = pkg != null,
                shown = s != null,
                obeys = lens.obeys(PLAY_ACTIONS),
                profile = factoryFor(lens) != null,
            ),
        )
        val via = deliver(pkg, lens, Command.PAUSE, plan.route)
        if (pkg != null && !isOwn(pkg) && (via == Channel.RECEIVER || via == Channel.SESSION_KEY || via == Channel.SYSTEM)) lens.assume(pkg, false)
    }

    /** Siguiente o anterior en [lens] ([skipPlan]). */
    private fun skip(lens: Lens, forward: Boolean) {
        val action = if (forward) PlaybackState.ACTION_SKIP_TO_NEXT else PlaybackState.ACTION_SKIP_TO_PREVIOUS
        val pkg = target(lens)
        val plan = skipPlan(
            Target(
                radio = lens === radio,
                phone = lens.song?.phone == true,
                own = pkg != null && isOwn(pkg),
                app = pkg != null,
                shown = lens.song != null,
                obeys = lens.obeys(action),
                profile = factoryFor(lens) != null,
            ),
        )
        deliver(pkg, lens, if (forward) Command.NEXT else Command.PREVIOUS, plan.route)
    }

    /**
     * La única cadena para mandar una orden: prueba cada canal de [route] en orden y se queda en el primero que tiene a
     * quién; lo anota en el registro. Devuelve por dónde se mandó (null = por ninguno).
     */
    private fun deliver(pkg: String?, lens: Lens, command: Command, route: List<Channel>): Channel? {
        val via = route.firstOrNull { through(it, pkg, lens, command, fallbackToSystem = Channel.SYSTEM in route) }
        trace("${command.label} ${pkg ?: "-"}${if (lens === radio) " (radio)" else ""}: ${via?.label ?: "sin a quién"}")
        return via
    }

    /** Manda [command] por [channel]; false = por ahí no hay a quién (se prueba el siguiente). */
    private fun through(channel: Channel, pkg: String?, lens: Lens, command: Command, fallbackToSystem: Boolean): Boolean = when (channel) {
        Channel.PHONE -> (phoneControl?.invoke(command.phone, 0) == true).also { sent ->
            if (sent && (command == Command.PLAY || command == Command.PAUSE)) lens.phoneToggled(command == Command.PLAY)
        }
        // Lo atiende [play] antes de llegar aquí.
        Channel.OWN -> false
        Channel.PROFILE -> factoryFor(lens)?.let { f ->
            when (command) {
                Command.PLAY -> playRadio(pkg ?: return false, f)
                Command.PAUSE -> f.releaseAudio()
                Command.NEXT -> f.next()
                Command.PREVIOUS -> f.previous()
            }
            true
        } ?: false
        Channel.SESSION -> pkg?.let { sessionOf(it, lens) }?.let { c ->
            runCatching {
                val t = c.transportControls
                when (command) {
                    Command.PLAY -> t.play()
                    Command.PAUSE -> t.pause()
                    Command.NEXT -> t.skipToNext()
                    Command.PREVIOUS -> t.skipToPrevious()
                }
            }.onFailure { trace("sesión de $pkg: ${command.label} falló (${it.javaClass.simpleName})") }.isSuccess
        } ?: false
        Channel.RECEIVER -> keyReceiver(pkg)?.let { sendMediaKey(context, command.key, it); true } ?: false
        Channel.SESSION_KEY -> pkg?.takeUnless(::isOwn)?.let { sessionOf(it, lens) }?.let { c ->
            for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) runCatching { c.dispatchMediaButtonEvent(KeyEvent(action, command.key)) }
            true
        } ?: false
        Channel.SERVICE -> command == Command.PLAY && pkg != null && connectAndPlay(pkg, fallback = fallbackToSystem)
        // La radio: solo la pausa y solo si nada más suena (si no, el sistema se la daría a esa otra app).
        Channel.SYSTEM -> if (lens === radio && othersPlaying(listOfNotNull(pkg))) false else { sendMediaKey(context, command.key); true }
        Channel.OPEN -> command == Command.PLAY && pkg != null && run { playRadio(pkg, null); true }
    }

    /** El plazo del despertar de [lens]: se quita el "Abriendo…" y, con [opens], se abre [pkg] si sigue sin nada. */
    private fun armWake(lens: Lens, pkg: String, opens: Boolean) {
        // Uno solo por pieza: el de un despertar anterior no debe quitar el "Abriendo…" de este.
        mainHandler.removeCallbacks(lens.wakeTimeout)
        lens.wakeTimeout = Runnable {
            if (lens.song == null && lens.starting != null && opens) open(pkg)
            lens.starting = null
        }
        mainHandler.postDelayed(lens.wakeTimeout, APP_ANSWER_MS)
    }

    /** Play de la pieza Radio: sigue la radio que suena; si no, despierta la FM de la pantalla o la radio por internet ([play]). */
    fun resumeRadio(onIdle: () -> Unit = {}) = play(radio, onIdle)

    /**
     * Apps como Spotify, dormidas en segundo plano, a veces dicen que suenan y no suenan hasta abrirlas. Tras un play a una
     * app de música normal (no Bluetooth ni FM: esas suenan por fuera de Android), si en [APP_ANSWER_MS] Android no tiene
     * audio y la pieza no está en pausa, se abre la app, se regresa al inicio y se le vuelve a dar play ([playCheck]).
     */
    private fun watchPlay(pkg: String) {
        if (isOwn(pkg) || sourceOf(pkg) != null) return
        playPkg = pkg
        mainHandler.removeCallbacks(playCheck)
        mainHandler.postDelayed(playCheck, APP_ANSWER_MS)
    }

    private var playPkg: String? = null
    private val playCheck = Runnable {
        val pkg = playPkg ?: return@Runnable
        // En pausa = la pausaron (solo si la pieza muestra esa misma app).
        if (users == 0 || musicActive() || music.song?.takeIf { it.pkg == pkg }?.playing == false) return@Runnable trace("play $pkg: audio=${yes(musicActive())}")
        music.starting = null
        val over = openOver(pkg, APP_RETURN_MS) {
            if (musicActive()) return@openOver trace("regreso: $pkg ya suena")
            // Ya abierta, su sesión existe: primero por ahí y luego su receptor.
            deliver(pkg, music, Command.PLAY, listOf(Channel.SESSION, Channel.RECEIVER))
        }
        trace("play $pkg: no suena, se abre${if (over) " y se regresa" else ""}")
    }

    // —— Callar la radio de fábrica (issue #17): solo si se SABE que suena ——

    /** LiteDash le dio play a la FM (y cuándo); se olvida al pausarla, callarla o cambiar de radio. */
    private var radioClaimedAt: Long? = null
    /** La última vez que se calló la radio: cada señal de que suena vale para callarla una sola vez ([radioSounds]). */
    private var radioQuietAt = 0L

    /**
     * La música de [target] va a sonar: la FM de fábrica se calla (su audio va por fuera de Android y tapaba la música,
     * issue #17), pero SOLO si se sabe que suena ([radioSounds]: su perfil dice que sí, su sesión o su aviso dicen que
     * suena, o LiteDash la prendió y nadie la pausó). Sin saberlo no se manda nada: en radios que toman cualquier tecla
     * como alternar, una pausa a ciegas la prendería. Por dónde, [quietBy]; nunca al sistema ni despertando una app.
     * Lo llaman los tres momentos en que la música empieza: [play], el reproductor propio ([ownMusicStarted]) y el celular
     * ([phoneMusicStarted]); también al pasar la radio a la de internet.
     */
    private fun quietRadio(target: String?) {
        val fm = sourceApps[SourceKind.RADIO]
        if (fm == null || !quietsFor(fm, radioInternet, target)) return
        val now = SystemClock.elapsedRealtime()
        val f = factoryFor(radio)
        val session = sessions().firstOrNull { it.packageName == fm }
        val signals = RadioSignals(
            profileOn = f?.on,
            // Una sesión que no dice cuándo cambió cuenta como de ahora (su estado es lo que hay).
            sessionAt = session?.takeIf { it.busy() }?.let { it.playbackState?.lastPositionUpdateTime?.takeIf { t -> t > 0 } ?: now },
            heardAt = radio.heardPlayingAt(fm),
            claimedAt = radioClaimedAt?.takeIf { now - it < ASSUME_MAX_MS },
        )
        if (!radioSounds(signals, radioQuietAt)) return trace("radio sin señal de que suena: no se toca $fm")
        val receiver = if (f == null) keyReceiver(fm) else null
        val by = quietBy(f != null, receiver != null, session != null, f == null && receiver == null && session == null && radioKind(fm) != null)
        when (by) {
            Quiet.PROFILE -> f?.releaseAudio()
            Quiet.RECEIVER -> receiver?.let { sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PAUSE, it) }
            Quiet.SESSION -> runCatching { session?.transportControls?.pause() }.onFailure { trace("pausa a $fm falló (${it.javaClass.simpleName})") }
            Quiet.PROFILE_LATER -> releaseWithProfile(fm)
            null -> Unit
        }
        radioQuietAt = now
        radioClaimedAt = null
        mainHandler.removeCallbacks(profileAnswer)
        fmCalibration.abort()
        radio.forget()
        trace("radio callada: $fm por ${by?.label ?: "nada (sin a quién)"}")
    }

    /** La radio que LiteDash prendió se calla con su perfil aunque la pieza Radio no esté a la vista: se abre solo para eso. */
    private fun releaseWithProfile(pkg: String) {
        factoryThread.execute {
            val p = profileFor(pkg) ?: return@execute
            mainHandler.post {
                if (p.open()) p.releaseAudio()
                p.close()
            }
        }
    }

    // —— Abrir apps y regresar ——

    /** Abre la app de radio de la pantalla (si hay una enlazada). */
    fun openRadioApp() {
        sourceApps[SourceKind.RADIO]?.let(::open)
    }

    /**
     * Abre la app de Bluetooth (la enlazada o la de marca; para vincular en pantallas con chip propio); falso si no
     * hay o no tiene pantalla que abrir.
     */
    fun openBluetoothApp(): Boolean = bluetoothApp()?.let { open(it) != Opened.NONE } == true

    /** Abre la app que está sonando o, si no hay, la tocada o la elegida en Ajustes. */
    fun openApp() {
        open(music.controller?.packageName ?: song?.pkg?.ifEmpty { null } ?: modeApp ?: return)
    }

    /** Si devuelve true, ya abrió la app a su manera (p. ej. en pantalla dividida). */
    var opener: ((Context, Intent, String) -> Boolean)? = null

    /** Trae LiteDash al frente si otra app lo tapa (lo pone la app). */
    var returnHome: (() -> Unit)? = null

    /**
     * La única forma de abrir una app, encima o a un lado: si es la del Bluetooth, su pantalla de música ([launchIntent]).
     * Es la del Bluetooth si es la fuente Bluetooth, una de marca conocida o una del sistema que lo parece por su paquete
     * (las de la pantalla que aún no se conocen; una app del usuario con "bt" en el paquete no cuenta).
     */
    private fun open(pkg: String): Opened {
        val bluetooth = sourceOf(pkg) == SourceKind.BLUETOOTH || pkg in HeadUnit.bluetoothApps || looksBluetooth(pkg) && isSystemApp(context, pkg)
        val intent = launchIntentFor(pkg, bluetooth)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return Opened.NONE
        if (opener?.invoke(context, intent, pkg) == true) return Opened.BESIDE
        return if (runCatching { context.startActivity(intent) }.onFailure { trace("no se abrió $pkg (${it.javaClass.simpleName})") }.isSuccess) Opened.OVER else Opened.NONE
    }

    /**
     * Abre [pkg] encima de LiteDash y, si quedó encima, regresa a los [afterMs] y hace [then]. Un solo regreso pendiente:
     * el nuevo reemplaza al anterior, y se cancela si LiteDash vuelve al frente por su cuenta ([cancelReturn]). True = quedó
     * encima (a un lado, en pantalla dividida, no se regresa).
     */
    private fun openOver(pkg: String, afterMs: Long, then: () -> Unit): Boolean {
        val over = open(pkg) == Opened.OVER
        mainHandler.removeCallbacks(pendingReturn)
        if (over) {
            pendingReturn = Runnable {
                trace("regreso a LiteDash: audio=${yes(musicActive())}")
                returnHome?.invoke()
                then()
            }
            mainHandler.postDelayed(pendingReturn, afterMs)
        }
        return over
    }

    private var pendingReturn = Runnable {}

    /**
     * LiteDash volvió al frente por su cuenta (el usuario regresó o abrió otra app desde el inicio):
     * ya no se regresa solo. No va en [stop]: al abrir la app, LiteDash se detiene y eso lo cancelaría.
     */
    fun cancelReturn() = mainHandler.removeCallbacks(pendingReturn)

    /** Cómo abrir cada app ([launchIntent] consulta al sistema y lee etiquetas): se pregunta una vez; se olvida al enlazar otra. */
    private val launches = ConcurrentHashMap<String, Intent>()

    private fun launchIntentFor(pkg: String, bluetooth: Boolean): Intent? {
        val key = "$pkg|$bluetooth"
        val cached = launches[key] ?: (launchIntent(context, pkg, bluetooth) ?: NO_LAUNCH).also { launches[key] = it }
        // Copia: quien la usa le agrega banderas.
        return cached.takeUnless { it === NO_LAUNCH }?.let(::Intent)
    }

    // —— Radio de fábrica: su play, su confirmación y su calibración ——

    /**
     * El play de la radio de fábrica en curso: a qué app, y si fue por su perfil ([profile]), cuándo se pidió, en qué
     * arranque y si la confirmación va a la calibración; tras abrir su app, si la revisión va a la calibración.
     */
    private class FmPlay(val pkg: String) {
        var profile: FactoryRadio? = null
        var askedAt = 0L
        var run = 0
        var verifyProfile = false
        var verifyOpen = false
    }

    private var fmPlay: FmPlay? = null

    /**
     * Play de la radio de fábrica: con perfil de su marca ([f]) o sin control (solo suena al abrirla). La
     * calibración dice cómo ([fmCalibration]: el perfil sin abrir nada o abrir su app); si ya se está calibrando o
     * nada funcionó antes, lo de siempre: el perfil y, si no confirma, abrir la app.
     */
    private fun playRadio(pkg: String, f: FactoryRadio?) {
        fmPlay = FmPlay(pkg)
        val available = listOfNotNull(
            Recipes.PROFILE.takeIf { f != null },
            Recipes.OPEN_FACTORY.takeIf { launchIntentFor(pkg, bluetooth = false) != null },
        )
        if (fmCalibration.play(available, catalog?.radio)) return
        if (f != null) playFactory(f, verify = false) else openAndReturn(verify = false)
    }

    /** Aplica la receta de la radio; la revisión llega sola (la confirmación del perfil o tras regresar de la app). */
    private fun useRadio(id: String?): Long? {
        if (fmPlay == null) return null
        when (id) {
            Recipes.PROFILE -> factoryFor(radio)?.let { playFactory(it, verify = true) } ?: fmCalibration.abort()
            Recipes.OPEN_FACTORY -> openAndReturn(verify = true)
        }
        return null
    }

    /**
     * La radio quedó así tras revisar o tras un "Sí". Sonando, se da por hecho. Sin sonar con la receta guardada:
     * si la eligió la persona (Android no ve su audio), el perfil se da por sonando y ya no se abre la app; si la
     * guardó la señal, esta vez se abre la app como antes (tres veces seguidas y se calibra otra vez).
     */
    private fun radioSettled(heard: Boolean) {
        val f = factoryFor(radio)
        val profile = fmCalibration.current == Recipes.PROFILE
        if (heard || fmCalibration.store.saved?.by == SavedBy.USER) {
            if (profile) f?.started()
            return
        }
        if (profile && fmPlay != null) openAndReturn(verify = false)
    }

    /**
     * Radios de fábrica que no dejan nada por fuera: solo suenan al abrirlas. Se abre la radio y, si quedó encima de
     * LiteDash, se regresa al inicio en [FACTORY_RETURN_MS] (Android deja a la app volver al frente unos segundos después
     * de abrir otra). Después se anota si hay audio ([radioCheck]) y, con [verify], se lo dice a la calibración.
     */
    private fun openAndReturn(verify: Boolean) {
        val play = fmPlay ?: return
        radio.starting = null
        val over = openOver(play.pkg, FACTORY_RETURN_MS) { factoryFor(radio)?.started() }
        trace("radio sin control: se abre ${play.pkg}${if (over) " y se regresa" else ""}")
        play.verifyOpen = verify
        mainHandler.removeCallbacks(radioCheck)
        mainHandler.postDelayed(radioCheck, RADIO_VERIFY_MS)
    }

    /**
     * FM de fábrica: play sin abrir su app (se le pide el audio a la radio). Si en [FACTORY_ANSWER_MS] la radio no dice que
     * tiene el audio ([profileAnswer]): calibrando ([verify]), se le dice a la calibración; si no, se abre como antes.
     */
    private fun playFactory(f: FactoryRadio, verify: Boolean) {
        val play = fmPlay ?: return
        play.profile = f
        play.askedAt = SystemClock.elapsedRealtime()
        play.run = factoryRun
        play.verifyProfile = verify
        trace("${f.tag} play sin abrir")
        f.takeAudio()
        mainHandler.removeCallbacks(profileAnswer)
        mainHandler.postDelayed(profileAnswer, FACTORY_ANSWER_MS)
    }

    /** La radio ya debió contestar al play por su perfil ([profileConfirmed]); se cancela al pausar, callarla o cambiar de radio. */
    private val profileAnswer = Runnable {
        radio.starting = null
        val play = fmPlay ?: return@Runnable
        val f = play.profile ?: return@Runnable
        // Cambiaron de radio o la pieza Radio se fue: ya no toca.
        if (play.run != factoryRun || factory !== f) {
            if (play.verifyProfile) fmCalibration.abort()
            return@Runnable
        }
        // El perfil se cerró solo (su servicio no contestó): la radio se abre como antes.
        if (!f.open) {
            trace("${f.tag} se cerró: se abre ${play.pkg}")
            if (play.verifyProfile) fmCalibration.abort()
            return@Runnable openAndReturn(verify = false)
        }
        val androidAudio = musicActive() && !othersPlaying(listOf(play.pkg))
        val confirmed = profileConfirmed(f.audioAt, play.askedAt, f.confirmsByAudio, androidAudio)
        if (confirmed) {
            f.started()
            trace("${f.tag} suena sin abrir")
        }
        if (play.verifyProfile) return@Runnable fmCalibration.heard(confirmed || androidAudio)
        if (confirmed) return@Runnable
        trace("${f.tag} no confirmó: se abre ${play.pkg}")
        openAndReturn(verify = false)
    }

    /** La revisión tras abrir la radio: si Android tiene audio y no es de otra app, se supone que es ella. */
    private val radioCheck = Runnable {
        val active = musicActive()
        trace("radio tras abrirla: audio=${yes(active)}")
        // Sin perfil de su marca nadie dice que la radio suena: si Android tiene audio tras abrirla, se
        // supone que es ella (el botón pasa a pausa y la pieza muestra la radio en vez de quedarse en nada).
        val pkg = sourceApps[SourceKind.RADIO]?.takeUnless { radioInternet }
        val heard = active && pkg != null && !othersPlaying(listOf(pkg))
        if (heard && factoryFor(radio) == null) radio.assume(pkg, true)
        val play = fmPlay
        if (play?.verifyOpen == true) {
            play.verifyOpen = false
            // Si la pausaron antes, el botón ya dejó la calibración y esto no cuenta. El estado que dice la radio no sirve
            // aquí: varias de fábrica dicen "en pausa" aunque suenen.
            fmCalibration.heard(heard)
        }
    }

    private fun musicActive() = context.getSystemService(AudioManager::class.java).isMusicActive

    /**
     * ¿Suena una app que no está en [pkgs]? (una sesión ocupada o el reproductor propio). isMusicActive es de todo
     * Android: con esto se sabe si el audio puede ser de la radio (o del Bluetooth) o es de otra app.
     */
    private fun othersPlaying(pkgs: Collection<String>): Boolean = sessions().any { it.packageName !in pkgs && it.busy() }

    // —— Calibración del sonido (PLAN_AUDIO §3): recetas por familia, revisión y "¿Se escucha?" en el widget ——

    /** El auto va a más de 10 km/h (lo pone la app con el GPS o el OBD): abrir una app encima espera al alto. */
    var moving: () -> Boolean = { false }

    /** Lo que pregunta o avisa la calibración en su widget; null = nada. */
    var soundCheck by mutableStateOf<SoundCheck?>(null)
        private set

    /** "Enviar un reporte" desde el aviso de que no se encontró cómo (lo pone la app: abre Ajustes → Sistema). */
    var onSoundReport: ((SoundPiece) -> Unit)? = null

    /** La respuesta a "¿Se escucha?". */
    fun answerSound(yes: Boolean) {
        when (soundCheck?.takeUnless { it.failed }?.piece) {
            SoundPiece.MUSIC -> btCalibration.answer(yes)
            SoundPiece.RADIO -> fmCalibration.answer(yes)
            null -> Unit
        }
    }

    /** Quita el aviso de que no se encontró cómo. */
    fun dismissSoundCheck() {
        if (soundCheck?.failed == true) soundCheck = null
    }

    /** Del aviso a "Enviar un reporte" (con el registro de lo que se probó en el reporte). */
    fun reportSound() {
        val piece = soundCheck?.piece ?: return
        soundCheck = null
        onSoundReport?.invoke(piece)
    }

    /** Ajustes → Música → "Calibrar sonido": ¿[piece] tiene algo que calibrar? (Bluetooth de marca o la FM de la pantalla). */
    fun calibrates(piece: SoundPiece): Boolean =
        if (piece == SoundPiece.MUSIC) bluetoothCalibrates else radioApp?.takeUnless(::isOwn) != null

    /** La receta guardada de [piece] ([Recipes]); null = aún sin calibrar. */
    fun soundRecipe(piece: SoundPiece): String? = (if (piece == SoundPiece.MUSIC) btStore else fmStore).saved?.id

    /** "Calibrar otra vez": se olvida lo guardado de las dos piezas; se vuelve a probar la próxima vez que suenen. */
    fun recalibrate() {
        btCalibration.reset()
        fmCalibration.reset()
        soundCheck = null
        trace("calibración: otra vez")
    }

    private fun showCheck(piece: SoundPiece, check: SoundCheck?) {
        if (check != null || soundCheck?.piece == piece) soundCheck = check
    }

    private val btStore by lazy { PrefsRecipeStore(store, SoundPiece.MUSIC) }
    private val fmStore by lazy { PrefsRecipeStore(store, SoundPiece.RADIO) }

    /**
     * El Bluetooth se calibra solo en pantallas con chip propio (su app de Bluetooth de marca, o sin el Bluetooth de
     * Android y con una app enlazada): con el de Android (la mayoría) la música ya pasa por Android y no hay nada que
     * probar. La familia por la radio (una HCN con el Bluetooth de Android) no cuenta.
     */
    private val bluetoothCalibrates: Boolean get() = knownBluetooth != null || (!bluetoothBuiltIn && bluetoothApp() != null)

    private val btRecipes by lazy { bluetoothRecipes(soundHost) }
    /** La receta de Bluetooth aplicada ahora; null = ninguna. */
    private var btApplied: BluetoothRecipe? = null

    private val btCalibration by lazy {
        SoundCalibration(
            SoundPiece.MUSIC, btStore, headUnit.trustsSignal, { moving() || users == 0 }, mainHandler, ::trace, { showCheck(SoundPiece.MUSIC, it) },
            use = ::useBluetooth, check = ::bluetoothHeard,
        )
    }

    private val fmCalibration by lazy {
        // La radio la pide la persona (tocó play): abrir su app no espera a que el auto se detenga, como siempre. Si el
        // perfil no confirma, se abre la app sin preguntar (como antes): la pregunta solo va al final.
        SoundCalibration(
            SoundPiece.RADIO, fmStore, trustsSignal = true, moving = { false }, mainHandler, ::trace, { showCheck(SoundPiece.RADIO, it) },
            use = ::useRadio, settled = ::radioSettled,
        )
    }

    /** La música Bluetooth empezó a sonar (un play de LiteDash, el celular por Connect o AVRCP): su receta y la revisión. */
    private fun calibrateBluetooth() {
        if (!bluetoothCalibrates) return
        // El celular ya suena por el Bluetooth de Android y Android tiene el audio: no hay nada que calibrar
        // (reporte E3F104: "plain no suena" con la sesión de com.android.bluetooth sonando).
        if (avrcpController?.playbackState?.state == PlaybackState.STATE_PLAYING && musicActive()) return trace("calibración bt: ya suena por Android")
        val available = headUnit.bluetoothRecipes.filter { btRecipes[it]?.available() == true }
        if (btCalibration.play(available, catalog?.bluetooth)) return
        // Nada funcionó antes: la receta de la marca de todos modos (lo que se hacía antes de calibrar);
        // sin receta de marca, nada que probar.
        if (btStore.saved?.id == Recipes.FAILED && !btCalibration.pending) {
            headUnit.bluetoothRecipes.firstOrNull { it in Recipes.BRAND && btRecipes[it]?.available() == true }?.let(::useBluetooth)
        }
    }

    /** Al dejar el método Bluetooth o irse el celular: se suelta la receta (y lo pendiente se deja). */
    private fun releaseBluetoothRecipe() {
        btCalibration.abort()
        useBluetooth(null)
    }

    private fun useBluetooth(id: String?): Long? {
        val recipe = id?.let(btRecipes::get)
        if (btApplied !== recipe) btApplied?.release()
        btApplied = recipe
        return recipe?.apply()
    }

    /**
     * ¿Suena el Bluetooth de verdad? ([bluetoothSounds]). Sin la prueba de que el celular suena, un celular apagado
     * llevaría a guardar [Recipes.FAILED] con un "No" que no era culpa de la pantalla.
     */
    private fun bluetoothHeard(): Boolean? {
        val song = music.song
        val playing = phoneState.onBluetooth && modeState == MusicMode.BLUETOOTH ||
            avrcpController?.playbackState?.state == PlaybackState.STATE_PLAYING ||
            song?.playing == true && !song.blind
        if (!playing) return null
        return bluetoothSounds(true, musicActive(), othersPlaying(bluetoothGroup(avrcpPackage, knownBluetooth, sourceApps[SourceKind.BLUETOOTH])))
    }

    /** Lo que el catálogo compartido dice para esta pantalla (se baja solo si una pieza a la vista tiene algo que calibrar). */
    private var catalog: CatalogRecipe? = null
    private var catalogAsked = false

    private fun loadCatalog() {
        if (catalogAsked) return
        fun needs(piece: SoundPiece, users: Int, saved: SavedRecipe?) = users > 0 && saved == null && calibrates(piece)
        if (!needs(SoundPiece.MUSIC, musicUsers, btStore.saved) && !needs(SoundPiece.RADIO, radioUsers, fmStore.saved)) return
        catalogAsked = true
        Thread({
            val found = loadRecipeCatalog(context)
            val retry = found == null && !recipeCatalogCached(context)
            mainHandler.post {
                if (retry) catalogAsked = false
                catalog = found
                trace("catálogo de recetas: ${found?.let { "bt=${it.bluetooth ?: "-"} fm=${it.radio ?: "-"}" } ?: "nada para esta pantalla"}")
            }
        }, "audio-recipes").start()
    }

    private val soundHost = object : SoundHost {
        override val context get() = this@MusicRepository.context
        override val store get() = this@MusicRepository.store
        override val main get() = mainHandler
        override val worker get() = factoryThread
        override fun log(event: String) { mainHandler.post { trace(event) } }
        override fun bluetoothApp() = this@MusicRepository.bluetoothApp()
        override fun factoryBluetooth() = sourceApps[SourceKind.BLUETOOTH] ?: knownBluetooth
        override fun openBriefly(pkg: String) {
            val over = openOver(pkg, FACTORY_RETURN_MS) {}
            trace("se abre $pkg un momento${if (over) " y se regresa" else ""}")
        }
        override fun musicActive() = this@MusicRepository.musicActive()
        override fun avrcpPlaying() = avrcpController?.playbackState?.state == PlaybackState.STATE_PLAYING
        override fun avrcpPlay() {
            runCatching { avrcpController?.transportControls?.play() }.onFailure { trace("play por AVRCP falló (${it.javaClass.simpleName})") }
        }
    }

    /** Para el reporte: la familia, la receta de cada pieza (y quién la confirmó) y los "no" seguidos de la señal. */
    private fun describeCalibration(): String {
        fun line(s: SavedRecipe?) = s?.let {
            val by = when (it.by) { SavedBy.SIGNAL -> "señal"; SavedBy.USER -> "usuario"; SavedBy.CATALOG -> "catálogo" }
            "${it.id} ($by)"
        } ?: "-"
        val fails = (btStore.saved?.fails ?: 0) + (fmStore.saved?.fails ?: 0)
        return "Calibración: familia=${headUnit.id}, bluetooth=${line(btStore.saved)}, radio=${line(fmStore.saved)}, fallos=$fails"
    }

    // —— Cómo se le habla a una app ——

    /**
     * El receptor del botón de play de [pkg] (nunca el de LiteDash: su reproductor tiene sesión). Se pregunta una vez por
     * app (es una llamada al sistema y la pieza lo consulta al pintarse), fuera de cualquier candado: el reporte lo lee en
     * otro hilo.
     */
    private fun keyReceiver(pkg: String?): ComponentName? {
        val p = pkg?.takeUnless(::isOwn) ?: return null
        val found = receivers[p] ?: (mediaButtonReceivers(p).firstOrNull() ?: NO_RECEIVER).also { receivers.putIfAbsent(p, it) }
        return found.takeUnless { it == NO_RECEIVER }
    }
    private val receivers = ConcurrentHashMap<String, ComponentName>()

    /** La pieza Radio sin sesión pero con una FM de la pantalla que atiende teclas: lleva anterior/siguiente. */
    fun radioHasKeys(): Boolean {
        val pkg = radioApp ?: return false
        return !isOwn(pkg) && keyReceiver(pkg) != null
    }

    /**
     * Se conecta al servicio de música de [pkg] y le pide play; si no lo acepta y [fallback], el botón
     * del sistema. False = no tiene servicio.
     */
    private fun connectAndPlay(pkg: String, fallback: Boolean): Boolean {
        val service = browserServices(context, pkg).firstOrNull() ?: return false
        lateinit var browser: MediaBrowser
        browser = MediaBrowser(context, ComponentName(service.packageName, service.name), object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                runCatching { MediaController(context, browser.sessionToken).transportControls.play() }
                    .onFailure { trace("servicio de $pkg: play falló (${it.javaClass.simpleName})") }
                browser.disconnect()
            }

            override fun onConnectionFailed() {
                trace("servicio de $pkg: no conectó${if (fallback) ", play al sistema" else ""}")
                if (fallback) sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY)
            }
        }, null)
        // Si el servicio nunca contesta, la conexión no se queda colgada para siempre.
        mainHandler.postDelayed({ runCatching { browser.disconnect() } }, APP_ANSWER_MS)
        return runCatching { browser.connect() }.onFailure { trace("servicio de $pkg: no se pudo conectar") }.isSuccess
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
    fun musicApps(): Set<String> = browserServices(context)
        .map { it.packageName }
        .filter { it != context.packageName && it !in NOT_MUSIC_APPS }
        .toSet()

    /**
     * Adivina, una vez, la app de radio de la pantalla: entre las del sistema que atienden el botón de
     * play y se pueden abrir, la que lleva "radio" o "fm" en el paquete o en el nombre. Solo si hay una
     * sola candidata (si hay dudas, mejor nada: se elige en Ajustes → Apps → Radio). El Bluetooth no se
     * adivina: [bluetoothApp] ya usa la app de marca instalada (adivinar por nombre enlazó ZLink en una
     * TopWay). Corre en un hilo aparte (son varias consultas al sistema); el resultado se guarda en el principal.
     */
    private fun guessSources() {
        val launchable = runCatching {
            context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName }.toSet()
        }.getOrDefault(emptySet())
        val players = mediaButtonReceivers().map { it.packageName }.distinct()
            .filter { it != context.packageName && it in launchable && isSystemApp(context, it) }
        val radio = players.filter { pkg -> RADIO_WORDS.any { it in tokens(pkg, appLabel(pkg)) } }.singleOrNull() ?: return
        mainHandler.post { if (SourceKind.RADIO !in sourceApps) setSource(SourceKind.RADIO, radio) }
    }

    /** Palabras sueltas del paquete y del nombre, en minúsculas ("com.syu.bt" → com, syu, bt). */
    private fun tokens(pkg: String, label: String): Set<String> =
        (pkg.split('.') + label.split(Regex("[^\\p{L}\\p{N}]+"))).map { it.lowercase() }.filter { it.isNotEmpty() }.toSet()

    // —— Registro y reporte ——

    // Lo último que pasó con las órdenes, los avisos y el despertar (solo en memoria, para los reportes).
    // Con candado: se escribe en el hilo principal y el reporte lo copia en otro.
    private val traceLog = ArrayDeque<String>()

    private fun trace(event: String) {
        val line = "${SystemClock.elapsedRealtime() / 1000 % 100_000}s $event"
        synchronized(traceLog) {
            traceLog.addLast(line)
            while (traceLog.size > TRACE_SIZE) traceLog.removeFirst()
        }
    }

    /** Para los reportes: qué Bluetooth trae la pantalla y qué apps de marca, para saber su familia. */
    private fun describeBluetooth(): String = buildString {
        val adapter = runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter }.getOrNull()
        val service = bluetoothService
        appendLine("Bluetooth de Android: AVRCP=${service?.let { "${it.packageName}/${it.name}" } ?: "no"}; adaptador=${adapter?.let { if (it.isEnabled) "encendido" else "apagado" } ?: "no"}")
        val brand = HeadUnit.bluetoothApps.filter(context::hasPackage)
        appendLine("Apps de Bluetooth de marca: ${brand.joinToString().ifBlank { "ninguna" }}")
    }

    /**
     * Lo que LiteDash ve de la música en esta pantalla, para los reportes: las fuentes enlazadas (con su receptor,
     * servicio y aviso), cada sesión con su estado, acciones y si trae canción, qué suponen las piezas, si Android tiene
     * audio sonando y lo último que pasó. Sin nombres de canciones ni del celular. Se llama en el hilo principal y copia
     * ahí lo que cambia en él; lo que devuelve pregunta al sistema y va en otro hilo.
     */
    fun describeLater(): () -> String {
        val preferredNow = preferred
        val sources = sourceApps
        val radioNow = radioApp
        val phoneLine = "Bluetooth: ${if (bluetoothDevice != null) "celular conectado" else "-"}; USB: ${if (usbDrive != null) "memoria conectada" else "-"}"
        val pieces = listOf("Pieza música" to music, "Pieza radio" to radio).map { (name, lens) ->
            val s = lens.song
            "$name: ${s?.let { "${it.pkg.ifEmpty { "?" }} ${if (it.playing) "suena" else "en pausa"}${if (it.blind) " (supuesto)" else ""}" } ?: "nada"}" +
                (lens.assumedPkg?.let { "; supone $it" } ?: "")
        }
        val profile = factory
        val btRecipe = btApplied?.id
        val calibration = describeCalibration()
        return {
            buildString {
                // Solo apps del sistema y las que el usuario enlazó o eligió: no la lista de todo lo instalado.
                fun relevant(pkg: String) = pkg == preferredNow || pkg in sources.values || isSystemApp(context, pkg)
                val buttons = mediaButtonReceivers().map { it.packageName }.distinct().filter(::relevant)
                val browsers = musicApps().filter(::relevant)
                appendLine("Música elegida: ${preferredNow ?: "ninguna"}")
                appendLine("Fuentes: radio=${radioNow ?: "-"}, bluetooth=${bluetoothApp() ?: "-"}")
                val notes = sources.values.toSet().let { apps -> NotificationService.active().filter { it.packageName in apps } }
                for ((kind, pkg) in sources) {
                    val own = notes.filter { it.packageName == pkg }
                    appendLine(
                        "  ${kind.name.lowercase()} $pkg: receptor=${yes(keyReceiver(pkg) != null)} servicio=${yes(browserServices(context, pkg).isNotEmpty())} " +
                            "avisos=${own.size} música=${yes(own.any { it.isMedia() })}",
                    )
                }
                appendLine(phoneLine)
                appendLine("Archivos: ${ownDescribe?.invoke() ?: "-"}; memorias=${Drives.describe(context)}")
                append(describeBluetooth())
                // Qué pantalla abre LiteDash en las apps con varias (issue #13: "Bluetooth" y "BT Music" en la misma).
                (sources[SourceKind.BLUETOOTH] ?: knownBluetooth)?.let { appendLine("Inicio de Bluetooth: ${describeLaunch(context, it, bluetooth = true)}") }
                preferredNow?.let { appendLine("Inicio de la elegida: ${describeLaunch(context, it, bluetooth = sourceOf(it) == SourceKind.BLUETOOTH)}") }
                appendLine("Apps de música: ${browsers.joinToString().ifBlank { "ninguna" }}")
                appendLine("Botón de play: ${buttons.joinToString().ifBlank { "ninguna" }}")
                if (!hasPermission()) appendLine("Sesiones: sin acceso a notificaciones")
                else {
                    val list = sessions()
                    appendLine("Sesiones: ${if (list.isEmpty()) "ninguna" else ""}")
                    for (c in list) {
                        val m = c.metadata
                        appendLine(
                            "  ${c.packageName} estado=${c.playbackState?.state ?: "-"} acciones=${actionNames(c.playbackState?.actions ?: 0L)} " +
                                "título=${yes(m.text(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE).isNotEmpty())} " +
                                "artista=${yes(m.text(MediaMetadata.METADATA_KEY_ARTIST).isNotEmpty())}",
                        )
                    }
                }
                pieces.forEach(::appendLine)
                appendLine("Audio sonando en Android: ${yes(musicActive())}")
                // Los perfiles de radio de cada marca (y lo de su Bluetooth): cada uno dice si esta pantalla es suya.
                for (kind in HeadUnit.factoryRadios) kind.describe(context, store, radioNow, profile, btRecipe)?.let(::appendLine)
                appendLine(calibration)
                append("Últimos eventos:")
                val events = synchronized(traceLog) { traceLog.toList() }
                if (events.isEmpty()) append(" ninguno") else events.forEach { append("\n  ").append(it) }
            }
        }
    }

    /** Lo pone el app: permiso, canciones y estado del reproductor propio (Archivos), para el reporte. */
    var ownDescribe: (() -> String)? = null

    /** Fuentes enlazadas sin receptor ni servicio: no hay cómo hablarles (el reporte las describe con [AppProbe]). */
    fun silentSources(): List<String> = sourceApps.values.distinct().filter { !isOwn(it) && isSilent(it) }

    private fun actionNames(actions: Long): String = listOf(
        PlaybackState.ACTION_PLAY to "play", PlaybackState.ACTION_PAUSE to "pause", PlaybackState.ACTION_PLAY_PAUSE to "play_pause",
        PlaybackState.ACTION_SKIP_TO_NEXT to "next", PlaybackState.ACTION_SKIP_TO_PREVIOUS to "prev", PlaybackState.ACTION_STOP to "stop",
        PlaybackState.ACTION_SEEK_TO to "seek",
    ).filter { actions and it.first != 0L }.joinToString(",", "[", "]") { it.second }
}

/** "Sin pantalla que abrir" en la caché de [MusicRepository.launchIntentFor] (el mapa concurrente no guarda nulos). */
private val NO_LAUNCH = Intent()

/** Sonando o cargando. */
private fun MediaController.busy(): Boolean = when (playbackState?.state) {
    PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> true
    else -> false
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

/** La sesión que comparte un aviso de música; null si no trae. */
private fun StatusBarNotification.sessionToken(): MediaSession.Token? =
    BundleCompat.getParcelable(notification.extras, Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)

/** Aviso de música: con su sesión o con el estilo multimedia ("android.template"; la constante es de la API 24). */
private fun StatusBarNotification.isMedia(): Boolean {
    val extras = notification.extras
    return extras.containsKey(Notification.EXTRA_MEDIA_SESSION) || extras.getString("android.template").orEmpty().endsWith("MediaStyle")
}
