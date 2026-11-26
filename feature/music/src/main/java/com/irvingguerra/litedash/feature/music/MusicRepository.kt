package com.irvingguerra.litedash.feature.music

import android.app.Notification
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.StatusBarNotification
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** El store de la música (también lo lee [FactoryAudio] para las estadísticas). */
internal const val MUSIC_STORE = "music"
internal const val KEY_LAST_APP = "last_app"
/** Ya se intentó adivinar la app de radio (una sola vez, la primera vez que se ve la pieza Radio). */
internal const val KEY_SOURCES_GUESSED = "sources_guessed"
/** La pieza Radio prefiere la de internet (true) o la FM de la pantalla. */
private const val KEY_RADIO_INTERNET = "radio_internet"
/** Tu app de música (método App). */
private const val KEY_PREFERRED = "preferred_app"
/** El método de música elegido ([MusicMode]). */
internal const val KEY_MODE = "music_mode"
/** El método que había antes de que el celular (LiteDash Connect) lo pasara a Bluetooth. */
internal const val KEY_MODE_HELD = "music_mode_held"
/** Migraciones ([MusicRepository.upgrade]): ya se pasó la app de música de la app (1.4.116) y ya se revisó el Bluetooth (1.4.107). */
private const val KEY_PREFERRED_MOVED = "preferred_moved"
private const val KEY_BT_FIXED = "bt_link_fixed"

/** Lo que se espera tras irse el celular antes de regresar el método: Android cierra y reabre Connect a ratos. */
private const val PHONE_LEFT_MS = 20_000L
/** Lo que se espera la carátula que el celular anunció antes de buscarla por título y artista. */
private const val PHONE_ART_WAIT_MS = 3_000L

/** Tras abrir una app de música porque no sonaba, cuánto se espera para regresar y darle play otra vez (Spotify tarda en despertar). */
private const val APP_RETURN_MS = 4_000L
/** Lo que LiteDash supone de una app de fábrica (suena / en pausa, o que prendió la radio) deja de valer tras este rato (p. ej. apagaron el auto). */
internal const val ASSUME_MAX_MS = 3 * 60 * 60_000L
/** Cuántos eventos guarda el registro de "Enviar un reporte". */
private const val TRACE_SIZE = 60
/** Un aviso (broadcast) de qué suena vale como respaldo durante este rato. */
private const val BROADCAST_FRESH_MS = 10 * 60_000L
/** Una carátula del Bluetooth que no se encontró se vuelve a pedir tras este rato (no en cada lectura de AVRCP). */
private const val COVER_RETRY_MS = 60_000L

// Ofrecen su audio a Android pero no son apps de música (el asistente, video).
private val NOT_MUSIC_APPS = setOf(
    "com.google.android.googlequicksearchbox", "com.google.android.youtube", "com.google.android.gms",
)

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
class MusicRepository(internal val context: Context) {
    internal val sessionManager = context.getSystemService(MediaSessionManager::class.java)
    internal val component = ComponentName(context, NotificationService::class.java)
    internal val mainHandler = Handler(Looper.getMainLooper())

    /** Piezas de música (widget o en grande) y de radio a la vista: cada una arranca solo lo suyo. */
    internal var musicUsers = 0
    internal var radioUsers = 0
    internal val users get() = musicUsers + radioUsers

    /** Lo que ve la pieza Música (todo menos la radio). Atajo de [music]. */
    val song: Song? get() = music.song

    /** La app que la pieza Música está despertando ("Abriendo Spotify…"); null = nada. Atajo de [music]. */
    val starting: String? get() = music.starting

    /** Nombre del celular conectado por Bluetooth a la pantalla; null = ninguno (o sin permiso para verlo). */
    var bluetoothDevice by mutableStateOf<String?>(null)
        internal set

    /** Nombre de la memoria USB conectada; null = ninguna. */
    var usbDrive by mutableStateOf<String?>(null)
        private set

    // La última app que sonó (nunca LiteDash): sin app elegida, play la despierta aunque ya no esté abierta.
    internal val store = context.getSharedPreferences(MUSIC_STORE, Context.MODE_PRIVATE)
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

    internal var modeState by mutableStateOf(savedMode())

    /** El método que el usuario tenía mientras el celular conectado presta el Bluetooth; null = nada prestado. */
    internal var heldMode by mutableStateOf<MusicMode?>(null)

    /** El método es Bluetooth porque el celular (LiteDash Connect) está conectado, no porque se eligiera. */
    val phoneHolds: Boolean get() = heldMode != null

    /** El celular con LiteDash Connect ([phoneStep]). */
    internal var phoneState by mutableStateOf(PhoneState())

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

    internal val phoneLeft = Runnable {
        releaseBluetoothRecipe()
        val back = heldMode ?: return@Runnable
        releaseHold()
        switchMode(back, silence = false)
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
        if (kind == SourceKind.BLUETOOTH && users > 0) warmUp()
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
    internal val knownBluetooth: String? by lazy { HeadUnit.bluetoothMusicApp(context) }

    /** La familia de la pantalla (por sus paquetes instalados): sus recetas de sonido. */
    internal val headUnit: HeadUnit by lazy { HeadUnit.of(context) }

    /**
     * La app de Bluetooth de marca sin receptor del botón de play ni servicio de música (se pregunta en otro hilo al
     * aparecer la música y al enlazar otra); null = tiene por dónde o no hay. Ver [phoneOnly].
     */
    private var silentBluetoothApp by mutableStateOf<String?>(null)

    /** Ya se pidió la entrada de audio en esta vuelta de [phoneOnly] ([phoneOnlyShown]). */
    internal var phoneOnlyAsked = false

    /** Veces que se volvió a pedir porque quedó pospuesta ([retryPostponed]); se cuenta por vuelta de [phoneOnly]. */
    internal var phoneOnlyRetries = 0
    internal val phoneOnlyRetry = Runnable { retryPostponed() }

    /**
     * Cuándo tocó la persona el aviso «Controla la música desde tu celular» ([askBluetoothSound]) o el ▶ de la pieza
     * Música en la pantalla ([Lens.toggle]), en [SystemClock.elapsedRealtime]. El volante y la barra no cuentan. El ▶
     * volvió a contar en la 1.5.22: sin él, una pantalla sin dato de velocidad cuyo celular ya sonaba (sin aviso que
     * tocar) nunca pasaba su sonido a Bluetooth (reporte D677C4, Essgoo).
     */
    internal var askedAt = 0L

    /** La calibración en curso empezó con ese toque al aviso ([calibrateBluetooth]); ver [holdsForMotion]. */
    internal var calibrationAsked = false

    /**
     * Lo que preguntan al sistema [bluetoothService], [knownBluetooth] y [headUnit] (decenas de consultas al instalador en
     * pantallas de 1 GB) se hace en el hilo de los perfiles al aparecer la primera pieza, no en el primer play ni al
     * pintarse; y de paso, si la app de Bluetooth tiene por dónde recibir play.
     */
    private fun warmUp() {
        factoryThread.execute {
            bluetoothService
            headUnit
            val pkg = bluetoothApp()?.takeIf { it != avrcpPackage && sourceOf(it) == SourceKind.BLUETOOTH }
            val silent = pkg?.takeIf { isSilent(it) }
            pkg?.let { keyReceiver(it); appLabel(it) }
            listOfNotNull(preferred, radioApp?.takeUnless(::isOwn)).forEach { keyReceiver(it); appLabel(it) }
            mainHandler.post {
                silentBluetoothApp = silent
                if (users > 0) startAvrcp()
            }
        }
    }

    /**
     * Con el método Bluetooth, la app de marca no tiene por dónde recibir play (ni receptor, sesión ni servicio) y el
     * celular no está con LiteDash Connect (reporte E48170): la pieza no pone un ▶ que no hace nada, dice que la música se
     * controla desde el celular.
     */
    val phoneOnly: Boolean
        get() = mode == MusicMode.BLUETOOTH && silentBluetoothApp != null && !phoneState.here && music.song == null && !music.hasSession

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

    // Íconos de las acciones de otras apps (se sueltan al esconderse la música).
    internal val icons = HashMap<String, Bitmap?>()
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

    internal val onSessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { choose(it.orEmpty()) }

    // Celular por Bluetooth y memoria USB: solo se escuchan mientras la música (o Ajustes → Celular) está en pantalla.
    internal val devices = DeviceWatcher(context, onBluetooth = ::phoneChanged, onUsb = { usbDrive = it })
    internal var devicesOn = false
    internal var phoneWatchers = 0

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

    // El Bluetooth de Android como receptor (A2DP sink): si la pantalla trae el servicio AVRCP,
    // LiteDash lee y controla la música del celular sin app externa. Conectado mientras la música
    // esté a la vista (sin celular, su sesión simplemente no trae canción). No depende de poder ver
    // el nombre del celular: sin el permiso BLUETOOTH_CONNECT nunca se conectaba.
    internal var avrcp: BluetoothAvrcp? = null
    // Lo lee también el reporte en otro hilo.
    @Volatile internal var avrcpController: MediaController? = null
    /** El servicio AVRCP del Bluetooth de Android; se busca una vez, sin importar si la música está a la vista. */
    internal val bluetoothService: ServiceInfo? by lazy { BluetoothAvrcp.find(context) }

    internal var avrcpLast: Int? = null
    internal val avrcpWatch = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            if (state?.state == avrcpLast) return
            avrcpLast = state?.state
            avrcpRechoose()
        }
        override fun onMetadataChanged(metadata: MediaMetadata?) = avrcpRechoose()
    }

    // —— Piezas a la vista: cada una enciende lo suyo y lo suelta al irse ——

    /** Una pieza de [piece] (widget o en grande) entra a la pantalla. */
    fun use(piece: SoundPiece) {
        if (piece == SoundPiece.MUSIC) musicUsers++ else radioUsers++
        if (users == 1) {
            warmUp()
            start()
            startRadio()
        }
        loadCatalog()
    }

    /** La pieza de [piece] sale de la pantalla. */
    fun release(piece: SoundPiece) {
        if (piece == SoundPiece.MUSIC) musicUsers = (musicUsers - 1).coerceAtLeast(0) else radioUsers = (radioUsers - 1).coerceAtLeast(0)
        if (users == 0) {
            stopRadio()
            stop()
        }
    }

    /** Llamar al volver a la app: si se acaba de dar el permiso, empieza a escuchar las sesiones. */
    fun recheck() {
        if (users > 0 && !listening) listen()
    }

    // Ya escucha las sesiones de medios (con el permiso de avisos).
    internal var listening = false

    /** El último aviso anotado en el registro (los repetidos no se anotan). */
    private var lastBroadcastLine: String? = null

    /**
     * Respaldo de "qué suena" para apps sin sesión de medios (la música Bluetooth de muchas
     * pantallas chinas): sus avisos (broadcasts). La radio enlazada va a su pieza; lo demás, a Música.
     */
    internal val broadcasts = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            // Al registrar el receptor Android entrega el último aviso pegajoso de cada acción (la Música de AOSP, Poweramp):
            // puede ser de hace horas y se tomaba como "suena ahora".
            if (isInitialStickyBroadcast) return
            val extras = i.extras.toMap()
            // Algunos (el AVRCP de Android) llegan cada dos segundos: repetido no se anota, o llena el registro del reporte.
            val line = "aviso ${i.action} [${extras.keys.joinToString()}]"
            if (line != lastBroadcastLine) trace(line)
            lastBroadcastLine = line
            val meta = BroadcastMeta.parse(i.action ?: return, extras) ?: return
            val fm = meta.pkg != null && meta.pkg == sourceApps[SourceKind.RADIO]
            // La FM avisando mientras la pieza Radio está en internet: no es lo que se escogió.
            if (fm && radioInternet) return
            // A la música, solo lo del método escogido: con Archivos no se cuela el Bluetooth de la pantalla.
            if (!fm && !musicHears(meta)) return
            (if (fm) radio else music).heard(meta)
        }
    }

    // —— Radio de fábrica: su perfil ([FactoryRadio]) ——

    /** El perfil de la radio de fábrica de la FM enlazada, si su marca lo tiene (ver [FactoryRadioKind]). */
    internal var factory: FactoryRadio? = null
    /** Sube en cada arranque o parada: un perfil que termina de abrir tarde se descarta. */
    internal var factoryRun = 0
    /**
     * Los perfiles se abren de uno en uno: si se piden dos seguidos (la radio se esconde y vuelve), el
     * segundo espera al primero en vez de ver la marca de seguridad a medio abrir y creer que la app se cerró.
     */
    internal val factoryThread = Executors.newSingleThreadExecutor { Thread(it, "factory-radio") }

    /**
     * El aviso de música de cada app enlazada (paquete → aviso), al día con lo que pasa el servicio:
     * así no se le pide a Android la lista de todos los avisos (con sus imágenes) en cada lectura.
     * Se escribe en el hilo principal; concurrente porque el reporte lo lee ([sessions]) en otro hilo.
     */
    internal val sourceNotes = ConcurrentHashMap<String, StatusBarNotification>()

    /**
     * Si una app enlazada cambia su aviso de música (otra sesión u otra canción), se vuelve a mirar.
     * Los avisos que no cambian eso (algunas apps lo actualizan cada segundo con el avance) no hacen nada.
     */
    internal val notifications = object : NotificationListener {
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
            // Los avisos de cualquier otra app (descargas, mensajes) ni llegan al hilo principal.
            if (n.packageName !in sourceApps.values) return
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
    val music = Lens(
        accepts = { !isRadio(it) }, fixed = { modeApp }, remembersLast = true, anyApp = { mode == MusicMode.APP },
        // La app de Bluetooth de marca suele ser solo una pantalla: la música del celular la toca el Bluetooth de Android.
        standIns = { if (mode == MusicMode.BLUETOOTH) BluetoothAvrcp.ANDROID_PACKAGES else emptyList() },
    )

    /** La pieza Radio: solo la radio de la pantalla (FM) o la de LiteDash (internet). */
    val radio = Lens(accepts = { isRadio(it) }, fixed = { null }, remembersLast = false, anyApp = { true })

    /**
     * Lo que suena en el celular por LiteDash Connect (Link); null = nada o sin conexión. Entra en el método
     * Bluetooth (y en App sin app elegida o con la de Bluetooth, si no suena nada en la pantalla) y manda
     * sobre AVRCP y los avisos de la marca: el dato sale del celular, igual en todas las pantallas.
     */
    internal var phoneSong: Song? = null
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

    /** Cada pieza elige su sesión de la misma lista. */
    internal fun choose(list: List<MediaController>) {
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
        /** Las apps cuya sesión vale por la de la app fija cuando esta no tiene ninguna. */
        private val standIns: () -> List<String> = { emptyList() },
    ) {
        var song by mutableStateOf<Song?>(null)
            private set

        /** La app que esta pieza está despertando ("Abriendo Spotify…"); null = nada. */
        var starting by mutableStateOf<String?>(null)
            internal set

        /** El plazo del último despertar: al acabarse se quita el "Abriendo…" (y, si toca, se abre la app). */
        internal var wakeTimeout = Runnable {}

        internal var controller: MediaController? = null

        /** La pieza tiene una sesión (aunque esté vacía y no se muestre nada): la ve la interfaz ([phoneOnly]). */
        internal var hasSession by mutableStateOf(false)
            private set

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
            val chosen = pickSession(views, { it.second }, ownPackage, fixed(), anyApp(), avrcpPackage, standIns())?.first
            if (chosen?.sessionToken != controller?.sessionToken) {
                controller?.unregisterCallback(callback)
                controller = chosen
                chosen?.registerCallback(callback, mainHandler)
            }
            hasSession = chosen != null
            read()
        }

        internal fun detach() {
            mainHandler.removeCallbacks(expire)
            mainHandler.removeCallbacks(stationAnswer)
            controller?.unregisterCallback(callback)
            controller = null
            hasSession = false
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
            // La sesión dice si suena pero no trae canción (el Bluetooth de varias pantallas, reporte E3F104): el título y el
            // artista del aviso de esa misma música, si llegó; el estado, el de la sesión.
            val told = if (named.isBlank() && reported) heardFor(c.packageName) else null
            // Sin título y en pausa = apps que solo dejan la sesión abierta: "nada sonando".
            // Sin título pero sonando = la música por Bluetooth de muchas pantallas (el
            // celular no manda la canción): se muestra con el nombre de la app y sus controles,
            // y sigue en pantalla al pausarla para poder darle play otra vez.
            if (named.isBlank() && !playing && song?.pkg != c.packageName) {
                song = null
                return
            }
            val title = named.ifBlank { told?.title ?: app }
            starting = null
            if (remembersLast && playing && !isOwn(c.packageName) && c.packageName != avrcpPackage && c.packageName != lastSavedApp) {
                lastSavedApp = c.packageName
                store.edit().putString(KEY_LAST_APP, c.packageName).apply()
            }
            song = withCover(Song(
                title = title,
                artist = m.text(MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST).ifBlank { shown?.second ?: told?.artist.orEmpty() },
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
            // Si LiteDash le mandó play o pausa después del último aviso, eso manda. Si el aviso no dice, suena solo el del
            // Bluetooth (sus avisos de canción no traen estado); el de una app (p. ej. `fm.last.android.metachanged`) no dice
            // nada de si suena: darlo por sonando 10 min ponía ▮▮ y el primer toque era pausa.
            val sent = assumedFor(pkg ?: assumedPkg)?.takeIf { assumedAt > heardAt }
            // La app de Bluetooth de marca cuenta como Bluetooth aunque su acción no lo diga (reusa la de la Música de AOSP).
            val playing = sent ?: h.playing ?: (h.bluetooth || pkg?.let(::sourceOf) == SourceKind.BLUETOOTH)
            if (h.title == null && !playing) return null
            val title = h.title ?: app.ifBlank { if (h.bluetooth) context.getString(R.string.source_bluetooth) else return null }
            return withCover(blindSong(title, h.artist.orEmpty(), app, playing, pkg.orEmpty(), pkg?.let(::sourceOf) ?: SourceKind.BLUETOOTH.takeIf { h.bluetooth }))
        }

        /**
         * El aviso reciente con canción de la misma música que la sesión [pkg]: de esa app, o del Bluetooth si la sesión es
         * del grupo del Bluetooth. Null = no hay.
         */
        private fun heardFor(pkg: String): BroadcastMeta? {
            val h = heard?.takeIf { it.title != null && SystemClock.elapsedRealtime() - heardAt < BROADCAST_FRESH_MS } ?: return null
            val same = h.pkg == pkg || h.bluetooth && pkg in bluetoothGroup(avrcpPackage, knownBluetooth, sourceApps[SourceKind.BLUETOOTH])
            return h.takeIf { same }
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

        /**
         * Play o pausa ([play]/[pause]); sin nada a la vista, play despierta lo que toque. [shownPlaying] = lo que muestra el
         * botón de la pantalla (▮▮ = true): hace exactamente eso. El volante y la barra no lo ven (null): [playingNow].
         */
        fun toggle(shownPlaying: Boolean? = null) {
            if (!pressPlays(shownPlaying, ::playingNow)) return pause(this)
            // El ▶ de la pantalla es la persona viéndola y pidiéndolo, igual que el toque al aviso ([askedAt]); el volante
            // y la barra no cuentan.
            if (shownPlaying != null && this === music) askedAt = SystemClock.elapsedRealtime()
            play(this)
        }

        /**
         * ¿Suena, para el volante (también elige entre música y radio)? Lo que se ve, si es de verdad. Sin eso (la pieza no está en pantalla y no escucha, o su
         * app aún no tiene sesión que se vea) se pregunta en el momento ([soundsNow]): si no, el volante mandaba play una y
         * otra vez a algo que ya sonaba y no había cómo pausarlo.
         */
        fun playingNow(): Boolean {
            val s = song
            if (s != null && !s.blind) return s.playing
            val pkg = target(this)
            val state = pkg?.let { sessionOf(it, this) }?.playbackState?.state
            return soundsNow(
                shown = s?.playing,
                session = state?.takeIf { it == PlaybackState.STATE_PLAYING || it == PlaybackState.STATE_PAUSED || it == PlaybackState.STATE_STOPPED || it == PlaybackState.STATE_BUFFERING }
                    ?.let { it == PlaybackState.STATE_PLAYING || it == PlaybackState.STATE_BUFFERING },
                // El audio de Android solo dice algo de una app de música normal: el Bluetooth y la FM de fábrica suenan por fuera.
                audio = if (this === music && pkg != null && !isOwn(pkg) && sourceOf(pkg) == null) musicActive() else null,
                sessionsReadable = hasPermission(),
            )
        }

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
    internal val avrcpPackage: String? get() = bluetoothService?.packageName

    // Un nombre que no se encontró no se guarda: la app pudo instalarse después con LiteDash abierto.
    internal fun appLabel(pkg: String): String = labels[pkg] ?: runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault("").also { if (it.isNotEmpty()) labels[pkg] = it }

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

    /** Play de la pieza Radio: sigue la radio que suena; si no, despierta la FM de la pantalla o la radio por internet ([play]). */
    fun resumeRadio(onIdle: () -> Unit = {}) = play(radio, onIdle)

    internal var playPkg: String? = null
    internal val playCheck = Runnable {
        val pkg = playPkg ?: return@Runnable
        // En pausa = la pausaron (solo si la pieza muestra esa misma app).
        if (users == 0 || musicActive() || music.song?.takeIf { it.pkg == pkg }?.playing == false) return@Runnable trace("play $pkg: audio=${yes(musicActive())}")
        // Su sesión dice que suena: hay pantallas donde Android no ve el audio (va por el MCU) y se abría encima cada vez.
        if (sessionOf(pkg, music)?.busy() == true) return@Runnable trace("play $pkg: su sesión suena")
        music.starting = null
        val over = openOver(pkg, APP_RETURN_MS) {
            if (musicActive()) return@openOver trace("regreso: $pkg ya suena")
            // Ya abierta, su sesión existe: primero por ahí y luego su receptor.
            deliver(pkg, music, Command.PLAY, listOf(Channel.SESSION, Channel.RECEIVER))
        }
        trace("play $pkg: no suena, se abre${if (over) " y se regresa" else ""}")
    }

    /** LiteDash le dio play a la FM (y cuándo); se olvida al pausarla, callarla o cambiar de radio. */
    internal var radioClaimedAt: Long? = null
    /** La última vez que se calló la radio: cada señal de que suena vale para callarla una sola vez ([radioSounds]). */
    internal var radioQuietAt = 0L

    /** Abre la app de radio de la pantalla (si hay una enlazada). */
    fun openRadioApp() {
        sourceApps[SourceKind.RADIO]?.let(::open)
    }

    /**
     * Abre la app de Bluetooth en su pantalla de emparejar (la enlazada o la de marca; para vincular en pantallas
     * con chip propio); falso si no hay o no tiene pantalla que abrir.
     */
    fun openBluetoothApp(): Boolean {
        val intent = bluetoothApp()?.let { pairingIntent(context, it) } ?: return false
        return runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { trace("no se abrió el Bluetooth (${it.javaClass.simpleName})") }.isSuccess
    }

    /** Abre la app que está sonando o, si no hay, la tocada o la elegida en Ajustes. */
    fun openApp() {
        open(music.controller?.packageName ?: song?.pkg?.ifEmpty { null } ?: modeApp ?: return)
    }

    /** Si devuelve true, ya abrió la app a su manera (p. ej. en pantalla dividida). */
    var opener: ((Context, Intent, String) -> Boolean)? = null

    /** Trae LiteDash al frente si otra app lo tapa (lo pone la app). */
    var returnHome: (() -> Unit)? = null

    internal var pendingReturn = Runnable {}

    /**
     * LiteDash volvió al frente por su cuenta (el usuario regresó o abrió otra app desde el inicio):
     * ya no se regresa solo. No va en [stop]: al abrir la app, LiteDash se detiene y eso lo cancelaría.
     */
    fun cancelReturn() = mainHandler.removeCallbacks(pendingReturn)

    /** Cómo abrir cada app ([launchIntent] consulta al sistema y lee etiquetas): se pregunta una vez; se olvida al enlazar otra. */
    internal val launches = ConcurrentHashMap<String, Intent>()

    /**
     * El play de la radio de fábrica en curso: a qué app, y si fue por su perfil ([profile]), cuándo se pidió, en qué
     * arranque y si la confirmación va a la calibración; tras abrir su app, si la revisión va a la calibración.
     */
    internal class FmPlay(val pkg: String) {
        var profile: FactoryRadio? = null
        var askedAt = 0L
        var run = 0
        var verifyProfile = false
        var verifyOpen = false
    }

    internal var fmPlay: FmPlay? = null

    /** La radio ya debió contestar al play por su perfil ([profileConfirmed]); se cancela al pausar, callarla o cambiar de radio. */
    internal val profileAnswer = Runnable {
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
    internal val radioCheck = Runnable {
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

    // —— Calibración del sonido (PLAN_AUDIO §3): recetas por familia, revisión y "¿Se escucha?" en el widget ——

    /** El auto va a más de 10 km/h (lo pone la app con el GPS o el OBD): abrir una app encima espera al alto. */
    var moving: () -> Boolean = { false }

    /** No hay dato de velocidad (ningún widget usa el GPS ni hay OBD): [moving] dice que sí se mueve, por si acaso. */
    var speedUnknown: () -> Boolean = { false }

    /** Lo que pregunta o avisa la calibración en su widget; null = nada. */
    var soundCheck by mutableStateOf<SoundCheck?>(null)
        internal set

    /** "Enviar un reporte" desde el aviso de que no se encontró cómo (lo pone la app: abre Ajustes → Sistema). */
    var onSoundReport: ((SoundPiece) -> Unit)? = null

    /**
     * El botón «LiteDash Connect» de «Controla la música desde tu celular» (lo pone la app: abre Ajustes → Celular, donde se
     * vincula el celular); null = sin botón.
     */
    var onPhoneLink by mutableStateOf<(() -> Unit)?>(null)

    /**
     * El volumen de la música de Android está en 0 (y se puede mover): por más receta, no se oiría. En una FYT el
     * Bluetooth del celular suena por ahí (reporte AA6C26: 0/15, "no se escucha" y se guardó que nada funcionaba).
     */
    private var mutedAnswerSkipped = false

    val musicMuted: Boolean
        get() = context.getSystemService(AudioManager::class.java)
            ?.let { !it.isVolumeFixed && it.getStreamVolume(AudioManager.STREAM_MUSIC) == 0 } == true

    /** La respuesta a "¿Se escucha?". En la música, un "No" con el volumen en 0 no cuenta: se deja la prueba sin guardar. */
    fun answerSound(yes: Boolean) {
        val piece = soundCheck?.takeUnless { it.failed }?.piece ?: return
        val calibration = if (piece == SoundPiece.MUSIC) btCalibration else fmCalibration
        // Una sola vez por sesión: hay pantallas que siempre dicen 0 (el volumen real lo lleva su MCU).
        if (!yes && piece == SoundPiece.MUSIC && !mutedAnswerSkipped && musicMuted) {
            mutedAnswerSkipped = true
            trace("calibración ${piece.key}: no se escucha con el volumen en 0, no cuenta")
            calibration.abort()
        } else {
            calibration.answer(yes)
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

    /** "Calibrar otra vez" de [piece]: se olvida solo lo suyo; se vuelve a probar la próxima vez que suene. */
    fun recalibrate(piece: SoundPiece) {
        (if (piece == SoundPiece.MUSIC) btCalibration else fmCalibration).reset()
        if (soundCheck?.piece == piece) soundCheck = null
        trace("calibración ${piece.key}: otra vez")
    }

    internal val btStore by lazy { PrefsRecipeStore(store, SoundPiece.MUSIC) }
    internal val fmStore by lazy { PrefsRecipeStore(store, SoundPiece.RADIO) }

    /**
     * El Bluetooth se calibra solo en pantallas con chip propio (su app de Bluetooth de marca, o sin el Bluetooth de
     * Android y con una app enlazada): con el de Android (la mayoría) la música ya pasa por Android y no hay nada que
     * probar. La familia por la radio (una HCN con el Bluetooth de Android) no cuenta.
     */
    internal val bluetoothCalibrates: Boolean get() = knownBluetooth != null || (!bluetoothBuiltIn && bluetoothApp() != null)

    internal val btRecipes by lazy { bluetoothRecipes(soundHost) }
    /** La receta de Bluetooth aplicada ahora; null = ninguna. */
    internal var btApplied: BluetoothRecipe? = null

    internal val btCalibration by lazy {
        SoundCalibration(
            SoundPiece.MUSIC, btStore, headUnit.trustsSignal,
            { users == 0 || holdsForMotion(moving(), speedUnknown(), calibrationAsked) }, mainHandler, ::trace, { showCheck(SoundPiece.MUSIC, it) },
            use = ::useBluetooth, check = ::bluetoothHeard, knowable = ::bluetoothKnowable, audible = ::bluetoothAudible,
        )
    }

    internal val fmCalibration by lazy {
        // La radio la pide la persona (tocó play): abrir su app no espera a que el auto se detenga, como siempre. Si el
        // perfil no confirma, se abre la app sin preguntar (como antes): la pregunta solo va al final.
        SoundCalibration(
            SoundPiece.RADIO, fmStore, trustsSignal = true, moving = { false }, mainHandler, ::trace, { showCheck(SoundPiece.RADIO, it) },
            use = ::useRadio, settled = ::radioSettled,
        )
    }

    /** Lo que el catálogo compartido dice para esta pantalla (se baja solo si una pieza a la vista tiene algo que calibrar). */
    internal var catalog: CatalogRecipe? = null
    internal var catalogAsked = false

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

    internal val receivers = ConcurrentHashMap<String, ComponentName>()

    /** La pieza Radio sin sesión pero con una FM de la pantalla que atiende teclas: lleva anterior/siguiente. */
    fun radioHasKeys(): Boolean {
        val pkg = radioApp ?: return false
        return !isOwn(pkg) && keyReceiver(pkg) != null
    }

    /**
     * Apps de música instaladas: las que ofrecen su biblioteca a Android (Spotify, Amazon Music, YouTube Music, Deezer,
     * el reproductor de la pantalla…) y las que se declaran app de música (algunas no ofrecen biblioteca).
     */
    fun musicApps(): Set<String> = (browserServices(context).map { it.packageName } + declaredMusicApps(context))
        .filter { it != context.packageName && it !in NOT_MUSIC_APPS }
        .toSet()

    // —— Registro y reporte ——

    // Lo último que pasó con las órdenes, los avisos y el despertar (solo en memoria, para los reportes).
    // Con candado: se escribe en el hilo principal y el reporte lo copia en otro.
    private val traceLog = ArrayDeque<String>()

    internal fun trace(event: String) {
        val line = "${SystemClock.elapsedRealtime() / 1000 % 100_000}s $event"
        synchronized(traceLog) {
            traceLog.addLast(line)
            while (traceLog.size > TRACE_SIZE) traceLog.removeFirst()
        }
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
                // Sin radio reconocida: las apps de la pantalla que parecen una, para poder agregarla (reportes 9FFE47, EE5B4C).
                if (radioNow == null) appendLine("  Radios que trae la pantalla: ${FactoryAudio.radioCandidates(context).joinToString().ifEmpty { "ninguna" }}")
                val notes = (sources.values.toSet() + listOfNotNull(knownBluetooth)).let { apps -> NotificationService.active().filter { it.packageName in apps } }
                // La de Bluetooth de marca también si no se enlazó (se usa igual: [bluetoothApp]).
                val listed = sources.entries.map { it.key to it.value } +
                    listOfNotNull(knownBluetooth?.takeIf { it !in sources.values }?.let { SourceKind.BLUETOOTH to it })
                for ((kind, pkg) in listed) {
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
                    appendLine("Sesiones (lector=${if (NotificationService.isConnected) "conectado" else "no"}): ${if (list.isEmpty()) "ninguna" else ""}")
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

    /**
     * Fuentes sin receptor ni servicio: no hay cómo hablarles (el reporte las describe con [AppProbe]). Las enlazadas y la
     * de Bluetooth de marca aunque no se haya enlazado (reporte E48170: `com.tw.bt` adivinada no salía).
     */
    fun silentSources(): List<String> = (sourceApps.values + listOfNotNull(knownBluetooth)).distinct().filter { !isOwn(it) && isSilent(it) }
}
