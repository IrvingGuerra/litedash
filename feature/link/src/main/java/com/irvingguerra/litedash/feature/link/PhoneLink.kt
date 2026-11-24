package com.irvingguerra.litedash.feature.link

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.irvingguerra.litedash.core.vehicle.localNetworkAllowed
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import org.json.JSONArray
import org.json.JSONObject

/** Lo que la pantalla dice de sí misma en el QR y en su `info`. */
class ScreenInfo(val app: String, val car: String, val carId: String, val screen: String)

/**
 * Tu auto para LiteDash Connect (mensaje `car`, PROTOCOLO_LINK.md §5d y §5e). [image] es la huella de su imagen:
 * la imagen solo viaja si el celular no la tiene ya ([PhoneLink.needsCarImage]).
 */
class ScreenCar(
    val id: String,
    val name: String,
    val model: String,
    val make: String,
    /** RGB, sin transparencia. */
    val color: Int,
    val finish: String,
    val gloss: Float,
    /** Este modelo puede verse Mate, Brillante o Metálico (trae sus capas o el catálogo se las ofrece). */
    val finishes: Boolean,
    /** Ids de los autos instalados. */
    val cars: List<String>,
    val image: String,
)

/** Lo que se dibuja para vincular: el texto del QR y el código de 4 dígitos de esa llave. */
class Pairing(val text: String, val code: String)

/**
 * Por dónde llega al auto el sonido del celular (mensaje `sound`, PROTOCOLO_LINK.md §5c): Bluetooth, Android
 * Auto (él lleva el audio), cable, el Bluetooth solo para llamadas (sin audio: suena en el celular; llega como
 * `route:"phone"` con `why:"calls"`), o solo la bocina del celular.
 */
enum class PhoneSound(val route: String) { BLUETOOTH("bt"), CAR("car"), WIRED("wired"), CALLS("calls"), PHONE("phone") }

/** Lo que suena en el celular (mensaje `music` con su `art`, PROTOCOLO_LINK.md §5b). */
data class PhoneSong(
    val title: String,
    val artist: String,
    /** La app que suena en el celular ("Spotify"). */
    val app: String,
    val playing: Boolean,
    val buffering: Boolean,
    val durationMs: Long,
    val positionMs: Long,
    /** Cuándo llegó (reloj de arranque), para avanzar la barra sin preguntar. */
    val readAt: Long,
    val speed: Float,
    val seekable: Boolean,
    /** El celular tiene carátula (llega aparte); sin ella, la pantalla puede buscarla. */
    val hasArt: Boolean,
    val art: Bitmap?,
)

/**
 * El enlace con el celular (LiteDash Connect), lado pantalla de Link v1 (PROTOCOLO_LINK.md): el
 * celular es el servidor y la pantalla lo busca en las puertas de enlace de sus redes, en la última
 * dirección que funcionó, donde lo anuncie su faro y, de respaldo, tocando a los vecinos de sus redes
 * locales (así lo encuentra también cuando el celular entra al hotspot de la pantalla).
 *
 * Cuándo busca lo decide [LinkSchedule]: con LiteDash al frente ([visible]) reintenta cada vez más espaciado
 * (5 s… hasta 1 min); en segundo plano solo oye el faro y los cambios de red. Con su panel abierto ([watch]) intenta cada 2 s. Sin celular vinculado y con el panel cerrado no deja
 * hilos, receptores ni sockets. Conectado, solo el hilo que lee (con su `ping`).
 *
 * El estado se maneja en el hilo principal; la red, en sus hilos (uno por intento, el oído del faro y
 * los que tocan a los vecinos).
 */
class PhoneLink(context: Context, private val screen: () -> ScreenInfo) {
    private val app = context.applicationContext
    private val store = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val random = SecureRandom()
    private val commands = Executors.newSingleThreadExecutor { Thread(it, "phone-link-cmd") }

    /** Hay un celular vinculado (su llave guardada). */
    var paired by mutableStateOf(store.contains(KEY))
        private set

    /** Hay conexión con el celular ahora. */
    var connected by mutableStateOf(false)
        private set

    /** Modelo del celular vinculado y versión de su app (de su último `info`). */
    var phoneName by mutableStateOf(store.getString(PHONE_NAME, null))
        private set
    var phoneApp by mutableStateOf(store.getString(PHONE_APP, null))
        private set

    /** El celular dijo que no puede leer su música (falta "Permitir la música" en LiteDash Connect). */
    var musicBlocked by mutableStateOf(false)
        private set

    /**
     * Por dónde llega al auto el sonido del celular, según LiteDash Connect; null si no lo ha dicho
     * (Connect anterior a la 0.4.1) o no hay conexión.
     */
    var phoneSound by mutableStateOf<PhoneSound?>(null)
        private set

    /** El celular conectado quiere ver tu auto (LiteDash Connect 0.5.0+): se le manda con [sendCar]. */
    var wantsCar by mutableStateOf(false)
        private set

    /** El celular conectado quiere el estado de tu auto (§5g): se le manda con [sendState]. */
    var wantsState by mutableStateOf(false)
        private set

    /** El celular pide cambiar tu auto (`setCar`, §5e); en el hilo principal. */
    var onSetCar: ((CarEdit) -> Unit)? = null

    /**
     * Llegó completo el paquete [kind] (`car` o `finish`) de [id] que mandó el celular (§5f), en un archivo temporal
     * que se queda quien lo recibe (lo instala o lo borra); sin archivo = llegó mal y ya se tiró. En el hilo principal.
     */
    var onPackage: ((id: String, kind: String, file: File?) -> Unit)? = null

    /** El celular pide el diagnóstico de la pantalla (`report`, §5h) con el id de su reporte; en el hilo principal. */
    var onReport: ((String) -> Unit)? = null

    /** El paquete que la pantalla le pidió al celular (id, kind y cuántos bytes mide); lo demás se rechaza sin escribirlo. */
    private class Expected(val id: String, val kind: String, val bytes: Long)
    @Volatile private var expected: Expected? = null

    /** La pantalla le pidió al celular el paquete [kind] de [id], de [bytes] (`need`, §5f); null = ya no espera nada. */
    fun expectPackage(id: String?, kind: String = "", bytes: Long = 0) {
        expected = id?.let { Expected(it, kind, bytes) }
    }

    /** Avisa (hilo principal) cuando el celular se conecta o se va. */
    var onLink: ((Boolean) -> Unit)? = null

    private fun linked(on: Boolean) {
        if (connected == on) return
        connected = on
        if (!on) {
            phoneSound = null
            wantsCar = false
            wantsState = false
        }
        onLink?.invoke(on)
    }

    /** Lo que suena en el celular; null = nada, o sin conexión. Avisa a [onMusic] en el hilo principal. */
    var song: PhoneSong? = null
        private set
    var onMusic: ((PhoneSong?) -> Unit)? = null

    /** Play, pausa, siguiente, anterior o salto ([ms]) a la música del celular. Falso si no se puede. */
    fun command(action: String, ms: Long = 0): Boolean {
        val open = session?.takeIf { it.music } ?: return false
        // En un solo hilo: play y pausa seguidos llegan en el orden en que se tocaron.
        send(open, JSONObject().put("t", "cmd").put("do", action).put("ms", ms))
        return true
    }

    /** El celular conectado no tiene la imagen [image] de tu auto (hay que mandársela con el `car`). */
    fun needsCarImage(image: String) = session?.carImage != image

    /** Manda tu auto al celular conectado, con su imagen [webp] si va. Falso si no hay a quién. */
    fun sendCar(car: ScreenCar, webp: ByteArray?): Boolean {
        val open = session?.takeIf { it.car } ?: return false
        val message = JSONObject().put("t", "car").put("id", car.id).put("name", car.name).put("model", car.model)
            .put("make", car.make).put("color", "%06X".format(car.color and 0xFFFFFF))
            .put("finish", car.finish).put("gloss", hundredths(car.gloss)).put("finishes", car.finishes)
            .put("cars", JSONArray(car.cars)).put("img", car.image)
        commands.execute {
            // La imagen (hasta 150 KB) se pasa a base64 aquí, no en el hilo principal.
            if (webp != null) message.put("webp", Base64.encode(webp))
            if (write(open, message) && webp != null) open.carImage = car.image
        }
        return true
    }

    /**
     * Cómo va lo que pidió el celular de su auto (`carJob`, §5e): [step] `download` (con [progress]), `need`,
     * `install`, `done` o `failed` (con [why], corto, para el usuario).
     */
    fun sendCarJob(id: String, kind: String, step: String, progress: Float? = null, why: String? = null) {
        val message = JSONObject().put("t", "carJob").put("id", id).put("kind", kind).put("step", step)
        if (progress != null) message.put("progress", hundredths(progress.coerceIn(0f, 1f)))
        if (why != null) message.put("why", why)
        session?.let { send(it, message) }
    }

    /** El estado de tu auto (§5g), si el celular lo pidió; [state] trae todo menos `t`. */
    fun sendState(state: JSONObject) {
        session?.takeIf { it.state }?.let { send(it, state.put("t", "state")) }
    }

    /** El diagnóstico de la pantalla para el reporte [id] del celular (§5h). */
    fun sendReport(id: String, text: String) {
        session?.let { send(it, JSONObject().put("t", "report").put("id", id).put("text", text.take(REPORT_MAX))) }
    }

    /** 0–1 con dos decimales (un Float tal cual sale como 0.30000001192092896). */
    private fun hundredths(value: Float) = Math.round(value * 100) / 100.0

    /** Manda [message] por [open] en orden con los demás. */
    private fun send(open: Session, message: JSONObject) {
        commands.execute { write(open, message) }
    }

    /**
     * Escribe en el hilo de los envíos; falso si no se pudo. La conexión caída la cierra el hilo que lee: aquí solo
     * se anota para el reporte.
     */
    private fun write(open: Session, message: JSONObject): Boolean = try {
        open.send(message)
        true
    } catch (e: Exception) {
        // También lo que lance el cifrado: una excepción suelta en este hilo cerraría la app.
        val why = "envío: ${e.message ?: e.javaClass.simpleName}"
        main.post { if (session === open) lastError = why }
        false
    }

    /**
     * La pantalla tiene por dónde hablar con un celular: una red local (Wi‑Fi, cable) o su propio hotspot.
     * Se revisa en hilos: en cada intento y, con el panel abierto, cada 2 s.
     */
    var localNetwork by mutableStateOf(true)
        private set
    /** Ya se revisó la red al menos una vez ([localNetwork] antes de eso es solo un supuesto). */
    var networkChecked by mutableStateOf(false)
        private set
    private var checkingNetwork = false

    /** "Buscar ahora": intenta ya, con los reintentos de vuelta al principio y tocando a los vecinos. */
    fun searchNow() {
        schedule.restart()
        connect()
    }

    /** El QR para vincular; solo con el panel abierto y sin celular vinculado. */
    var pairing by mutableStateOf<Pairing?>(null)
        private set

    private var running = false
    private var watching = false
    /** Alguna ventana de LiteDash está a la vista ([visible]; MainActivity ya cuenta las suyas). */
    private var shown = false
    /** LiteDash está al frente: busca seguido. Con el panel abierto lo está por fuerza. */
    private val foreground get() = shown || watching

    private val schedule = LinkSchedule()
    /** Cuándo corre el siguiente intento programado ([retry]); 0 = ninguno. */
    private var retryAt = 0L
    private val retry = Runnable {
        retryAt = 0L
        connect()
    }

    /** La llave del QR: provisional hasta que un saludo con ella termine bien. */
    private var offered: ByteArray? = null
    private var offeredAt = 0L

    private var attempt: Attempt? = null
    private var session: Session? = null
    private var networkWatched = false
    private val networks = NetworkMemory<Network>()
    /** El oído del faro del celular y de dónde llegó su último aviso ("host:puerto"). */
    private var ear: Ear? = null
    private var beaconed: String? = null
    /** El puerto del faro no se pudo abrir: no se reintenta hasta otra red o hasta volver al frente. */
    private var earFailed = false
    /** Llegó un aviso del faro mientras había un intento en curso: al cerrarse se intenta de una vez. */
    private var beaconFresh = false
    /** La llave que el oído reconoce (la lee su hilo). */
    @Volatile private var earKey: ByteArray? = null

    // Para el reporte.
    private var lastError: String? = null
    @Volatile private var lastGateways = ""
    @Volatile private var lastNeighbors = ""
    /** Cómo se encontró al celular la última vez que conectó. */
    private var lastFound: String? = null

    /**
     * Se puede usar la red local. Desde Android 17 (con targetSdk 37) pide el permiso de "Dispositivos
     * cercanos": sin él no se toca la red, porque cada intento hace que Android abra su selector y, al
     * volver LiteDash al frente, se volvería a abrir en bucle.
     */
    var networkAllowed by mutableStateOf(localNetworkAllowed(app))
        private set

    /** Se dio o se negó el permiso de la red local: si ya se puede, se busca de una vez. */
    fun permissionChanged() {
        networkAllowed = localNetworkAllowed(app)
        sync()
        wake()
    }

    private val active get() = running && networkAllowed && (paired || watching)

    /** LiteDash volvió al frente (onResume): si hay celular vinculado, lo busca. */
    fun start() {
        running = true
        networkAllowed = localNetworkAllowed(app)
        sync()
        wake()
    }

    /**
     * LiteDash se ve o se dejó de ver (MainActivity avisa al pasar de 0 a 1 ventanas y de 1 a 0). Al pasar al frente los
     * reintentos vuelven a ir seguidos; al irse al fondo se apagan Bluetooth y los reintentos por tiempo
     * (queda el oído del faro). Una conexión abierta no se toca.
     */
    fun visible(on: Boolean) {
        val was = foreground
        shown = on
        if (foreground == was) return
        if (foreground) {
            schedule.resetRetries()
            earFailed = false
            sync()
            wake()
        } else {
            cancelRetry()
            sync()
        }
    }

    /** Ajustes → Celular se abrió o se cerró: abierta, intenta cada 2 s y mantiene el QR al día. */
    fun watch(on: Boolean) {
        watching = on && running
        main.removeCallbacks(tick)
        if (watching) tick.run() else sync()
    }

    /** Olvida al celular (le avisa si hay conexión) y deja listo un QR con otra llave. */
    fun unlink() {
        drop(bye = true)
        forget()
    }

    /** Para "Enviar un reporte". */
    fun describe(): String = "Celular: vinculado=${yes(paired)}, conectado=${yes(connected)}, red local permitida=${yes(networkAllowed)}, " +
        "al frente=${yes(foreground)}, puerta de enlace=${lastGateways.ifEmpty { "—" }}, " +
        "última dirección=${store.getString(LAST_ADDRESS, null) ?: "—"}, " +
        "faro=${beaconed ?: if (earFailed) "sin puerto" else "—"}, " +
        "redes=${lastNeighbors.ifEmpty { "—" }}, encontrado por=${lastFound ?: "—"}, reintentos=${schedule.retries}, error=${lastError ?: "—"}, " +
        "sonido=${phoneSound?.route ?: "—"}, música=${when { session?.music != true -> "no" ; musicBlocked -> "sin permiso"; song == null -> "nada"; song?.playing == true -> "suena"; else -> "en pausa" }}"

    private fun yes(value: Boolean) = if (value) "sí" else "no"

    private val tick = object : Runnable {
        override fun run() {
            networkAllowed = localNetworkAllowed(app)
            if (!networkAllowed) {
                // Sin permiso no hay nada que buscar: se revisa de nuevo al volver de Ajustes de Android.
                sync()
                return
            }
            refreshPairing()
            checkNetwork()
            sync()
            wake()
            main.postDelayed(this, WATCH_RETRY_MS)
        }
    }

    /** Revisa la red en un hilo corto (uno a la vez) y la publica en el principal. */
    private fun checkNetwork() {
        if (checkingNetwork) return
        checkingNetwork = true
        io {
            val found = localNets().any
            main.post {
                checkingNetwork = false
                localNetwork = found
                networkChecked = true
            }
        }
    }

    /** Las redes locales de la pantalla en un momento: lo que se toca en un intento y si hay por dónde. */
    private class LocalNets(
        /** Las IPv4 de la pantalla en cada red de Android que no es de datos móviles, con su red. */
        val addresses: List<Pair<String, Network>>,
        /** Las puertas de enlace IPv4 de esas redes (en el hotspot del celular, es el celular). */
        val gateways: Map<String, Network>,
        /**
         * Las IPv4 privadas de las interfaces arriba que no son de datos móviles, loopback ni punto a punto (así cuenta
         * el hotspot de la propia pantalla, que no es una "red" para Android): Wi‑Fi primero, una por red.
         */
        val own: List<String>,
    ) {
        val any get() = addresses.isNotEmpty() || own.isNotEmpty()
    }

    /** Toma la foto de las redes. Enumera interfaces: nunca en el hilo principal. */
    private fun localNets(): LocalNets {
        val manager = app.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION") // el reemplazo pide un NetworkCallback por red; aquí basta una foto
        val all = runCatching { manager?.allNetworks?.toList() }.getOrNull().orEmpty()
        val addresses = ArrayList<Pair<String, Network>>()
        val gateways = HashMap<String, Network>()
        val mobile = HashSet<String>()
        for (network in all) {
            val properties = runCatching { manager?.getLinkProperties(network) }.getOrNull() ?: continue
            // Los datos de la SIM no son una red donde pueda estar el celular: ni su puerta de enlace ni sus vecinos.
            if (runCatching { manager?.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) }.getOrNull() == true) {
                properties.interfaceName?.let(mobile::add)
                continue
            }
            for (gateway in ipv4Gateways(properties)) gateways[gateway] = network
            for (own in ipv4Addresses(properties)) addresses += own to network
        }
        val own = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty()
            .filter { runCatching { it.isUp && !it.isLoopback && !it.isPointToPoint && it.name !in mobile }.getOrDefault(false) }
            // Primero el Wi‑Fi y el hotspot: muchas pantallas traen además redes internas (cámara, CarPlay).
            .sortedBy { face -> if (WIFI_NAMES.any { face.name.startsWith(it) }) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
            // Una sola vez por red, aunque la pantalla tenga dos direcciones en ella.
            .distinctBy { it.substringBeforeLast('.') }
        return LocalNets(addresses, gateways, own)
    }

    private fun ipv4Addresses(properties: LinkProperties) =
        properties.linkAddresses.mapNotNull { (it.address as? Inet4Address)?.hostAddress }

    private fun ipv4Gateways(properties: LinkProperties) = properties.routes.mapNotNull { route ->
        route.gateway?.takeIf { route.isDefaultRoute && it is Inet4Address && !it.isAnyLocalAddress }?.hostAddress
    }

    /** Con el panel abierto y sin celular: una llave nueva si no hay o si la del QR ya venció. */
    private fun refreshPairing() {
        if (paired) {
            pairing = null
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (offered != null && now - offeredAt < OFFER_MS) return
        val key = ByteArray(LinkProtocol.KEY_BYTES).also(random::nextBytes)
        offered = key
        offeredAt = now
        val info = screen()
        pairing = Pairing(LinkProtocol.qrText(screenId(), key, info.car, info.screen), LinkProtocol.code(key))
    }

    /** La identidad de esta pantalla: 8 bytes al azar, fijos desde la primera vez. */
    private fun screenId(): String = store.getString(SCREEN_ID, null)
        ?: ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) }.also { store.edit().putString(SCREEN_ID, it).apply() }

    /** Prende o apaga lo que escucha según haga falta: la red, el faro y el Bluetooth. */
    private fun sync() {
        val manager = app.getSystemService(ConnectivityManager::class.java)
        if (active != networkWatched && manager != null) {
            try {
                if (active) {
                    // Solo Wi‑Fi y cable: los datos móviles van y vienen en un auto en movimiento y ahí no está el celular.
                    val request = NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                        .build()
                    manager.registerNetworkCallback(request, onNetwork)
                } else {
                    manager.unregisterNetworkCallback(onNetwork)
                    networks.clear()
                }
                networkWatched = active
            } catch (e: RuntimeException) {
                // Android limita los callbacks por app: queda anotado y se intenta en el siguiente cambio de estado.
                lastError = "red: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        earKey = currentKey()
        listen(active && !connected && !earFailed)
    }

    /** Prende o apaga el oído del faro. */
    private fun listen(on: Boolean) {
        if (on == (ear != null)) return
        val current = ear
        if (current != null) {
            ear = null
            io(current::close)
        } else {
            ear = Ear().also { Thread(it, "phone-link-ear").start() }
        }
    }

    /** Oye los avisos UDP del celular (su faro) mientras se le busca. */
    private inner class Ear : Runnable {
        @Volatile private var socket: DatagramSocket? = null
        @Volatile private var closed = false

        fun close() {
            closed = true
            socket?.close()
        }

        override fun run() {
            try {
                val open = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(LinkProtocol.PORT))
                }
                socket = open
                if (closed) open.close()
                val packet = DatagramPacket(ByteArray(BEACON_MAX), BEACON_MAX)
                var lastTold = 0L
                while (!closed) {
                    packet.length = BEACON_MAX
                    open.receive(packet)
                    // Todo se decide aquí: el ruido de la red (o alguien que inunde el puerto) no llega al hilo principal.
                    if (packet.length != LinkProtocol.BEACON_LENGTH) continue
                    val key = earKey ?: continue
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastTold < BEACON_EVERY_MS) continue
                    if (!LinkProtocol.isBeacon(key, String(packet.data, 0, packet.length, Charsets.US_ASCII))) continue
                    val from = packet.address?.hostAddress ?: continue
                    lastTold = now
                    main.post { if (ear === this) heardBeacon(from) }
                }
            } catch (e: Exception) {
                // Cerrado al apagar, o el puerto no se pudo abrir: no se reintenta hasta otra red o volver al frente.
                main.post {
                    if (ear === this && !closed) {
                        ear = null
                        earFailed = true
                        lastError = "faro: ${e.message ?: e.javaClass.simpleName}"
                    }
                }
            } finally {
                socket?.close()
            }
        }
    }

    /** Un aviso del faro del celular de esta pantalla: se le busca ahí. */
    private fun heardBeacon(from: String) {
        beaconed = "$from:${LinkProtocol.PORT}"
        if (attempt != null) beaconFresh = true else wake()
    }

    /** La llave con la que toca intentar: la guardada o, con el panel abierto, la del QR. */
    private fun currentKey(): ByteArray? {
        val stored = store.getString(KEY, null)
        val key = if (stored != null) runCatching { Base64.decode(stored) }.getOrNull() else offered.takeIf { watching }
        return key?.takeIf { it.size == LinkProtocol.KEY_BYTES }
    }

    /**
     * Los avisos de red llegan en el hilo de Android; cuenta como otra red solo si cambiaron sus IPv4 o su puerta de
     * enlace (en algunas pantallas `onLinkPropertiesChanged` llega seguido por IPv6 o DNS).
     */
    private val onNetwork = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // Antes de Android 8 no siempre le sigue onLinkPropertiesChanged: se leen aquí.
            app.getSystemService(ConnectivityManager::class.java)?.getLinkProperties(network)?.let { heardNetwork(network, it) }
        }

        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = heardNetwork(network, properties)

        override fun onLost(network: Network) {
            main.post { networks.lost(network) }
        }
    }

    private fun heardNetwork(network: Network, properties: LinkProperties) {
        val addresses = ipv4Addresses(properties)
        val gateways = ipv4Gateways(properties)
        main.post { if (networkWatched && networks.changed(network, addresses, gateways)) networkChanged() }
    }

    /** Otra red: se busca de inmediato, los reintentos vuelven a empezar seguidos y lo oído en la red anterior ya no vale. */
    private fun networkChanged() {
        if (!connected) {
            schedule.restart()
            beaconed = null
        }
        earFailed = false
        sync()
        wake()
    }

    /** Pide un intento: ya, o en cuanto se cumpla el espacio mínimo ([LinkSchedule]); nunca atrasa uno programado. */
    private fun wake() {
        if (!active || attempt != null) return
        val wait = schedule.waitFor(SystemClock.elapsedRealtime())
        if (wait == 0L) connect() else retryIn(wait)
    }

    private fun retryIn(delay: Long) {
        val at = SystemClock.elapsedRealtime() + delay
        if (retryAt != 0L && retryAt <= at) return
        main.removeCallbacks(retry)
        retryAt = at
        main.postDelayed(retry, delay)
    }

    private fun cancelRetry() {
        main.removeCallbacks(retry)
        retryAt = 0L
    }

    /** Un intento de conexión ya, si toca y no hay otro en curso (los avisos pasan por [wake]). */
    private fun connect() {
        if (!active || attempt != null) return
        val key = currentKey() ?: return
        cancelRetry()
        val sweep = schedule.attempt(SystemClock.elapsedRealtime(), foreground, watching)
        val known = listOfNotNull(
            beaconed?.let { it to "faro" }, store.getString(LAST_ADDRESS, null)?.let { it to "última" },
        )
        val next = Attempt(key, fresh = !store.contains(KEY), screenId(), screen(), known, sweep)
        attempt = next
        Thread(next, "phone-link").start()
    }

    /** Suelta la conexión o el intento en curso; con [bye], antes le avisa al celular que se desvincula. */
    private fun drop(bye: Boolean = false) {
        val current = attempt ?: return
        val open = session
        // El hilo del intento ya no avisará al cerrar (se le soltó): la música del celular se quita aquí.
        played(current, null, blocked = false)
        attempt = null
        session = null
        linked(false)
        current.cancelled = true
        io {
            if (bye && open != null) runCatching { open.send(JSONObject().put("t", "bye").put("reason", "unlink")) }
            current.close()
        }
    }

    /** Borra la llave y lo que se sabía del celular; con el panel abierto sale un QR nuevo. */
    private fun forget() {
        store.edit().remove(KEY).remove(PHONE_NAME).remove(PHONE_APP).remove(LAST_ADDRESS).apply()
        beaconed = null
        lastFound = null
        paired = false
        schedule.resetRetries()
        cancelRetry()
        phoneName = null
        phoneApp = null
        offered = null
        if (watching) refreshPairing()
        sync()
    }

    // Lo que sigue llega del hilo del intento, ya en el hilo principal.

    private fun opened(from: Attempt, open: Session, address: String, how: String) {
        if (attempt !== from) return
        lastFound = how
        val edit = store.edit().putString(LAST_ADDRESS, address)
        // El saludo con la llave del QR terminó bien: desde ahora es la definitiva.
        if (from.fresh) edit.putString(KEY, Base64.encode(from.key))
        edit.apply()
        if (from.fresh) {
            paired = true
            offered = null
            pairing = null
        }
        session = open
        linked(true)
        schedule.resetRetries()
        lastError = null
        sync()
    }

    private fun heard(from: Attempt, name: String, version: String, car: Boolean, state: Boolean) {
        if (attempt !== from) return
        wantsCar = car
        wantsState = state
        phoneName = name
        phoneApp = version
        store.edit().putString(PHONE_NAME, name).putString(PHONE_APP, version).apply()
    }

    /** Llegó la música del celular (ya en el hilo principal). */
    private fun played(from: Attempt, next: PhoneSong?, blocked: Boolean) {
        if (attempt !== from) return
        musicBlocked = blocked
        song = next
        onMusic?.invoke(next)
    }

    /** El celular dijo por dónde sale su sonido: la canción a la vista se vuelve a publicar (lleva el aviso). */
    private fun heardSound(from: Attempt, route: String, why: String) {
        if (attempt !== from) return
        // "calls" llega como motivo de `phone`: así las pantallas anteriores siguen avisando.
        phoneSound = if (route == PhoneSound.PHONE.route && why == PhoneSound.CALLS.route) PhoneSound.CALLS
        else PhoneSound.entries.firstOrNull { it.route == route }
        song?.let { onMusic?.invoke(it) }
    }

    private fun closed(from: Attempt, error: String?, bye: Boolean) {
        if (attempt !== from) return
        played(from, null, blocked = false)
        val wasConnected = connected
        attempt = null
        session = null
        linked(false)
        if (error != null) lastError = error
        if (bye) return forget()
        sync()
        if (!active) return
        val fresh = beaconFresh
        beaconFresh = false
        // Lo que se oyó antes de este intento ya se probó y no sirvió: que no se pruebe primero para siempre.
        if (!fresh && !wasConnected) beaconed = null
        if (fresh) {
            // El celular avisó dónde está mientras se buscaba en otro lado: ahí, de una vez.
            wake()
        } else if (paired) {
            // Con celular vinculado y sin conexión: otro intento según [LinkSchedule] (también si nunca conectó en esta
            // red: Connect pudo no estar despierto todavía). Sin vincular, el panel marca el paso.
            schedule.failed(foreground)?.let(::retryIn)
        }
    }

    /** Una conexión ya saludada: escribe de un mensaje a la vez (el contador del cifrado va en orden). */
    private class Session(private val socket: Socket, private val channel: SecureChannel) {
        private val output = socket.getOutputStream()
        private val writing = ReentrantLock()
        /** El celular declaró la música en su `info`. */
        @Volatile var music = false
        /** El celular declaró tu auto en su `info` ([CAP_CAR]). */
        @Volatile var car = false
        /** La huella de la imagen de tu auto que el celular ya tiene. */
        @Volatile var carImage: String? = null
        /** El celular declaró el estado de tu auto ([CAP_STATE]). */
        @Volatile var state = false

        fun send(message: JSONObject) {
            writing.lock()
            try {
                write(message)
            } finally {
                writing.unlock()
            }
        }

        /**
         * Para el hilo que lee (`ping`, `pong`): si otro está escribiendo (un `car` grande a un celular que se fue sin
         * cerrar), no espera: se salta este y el lector sigue contando el silencio. Falso si no se mandó.
         */
        fun trySend(message: JSONObject): Boolean {
            if (!writing.tryLock()) return false
            try {
                write(message)
            } finally {
                writing.unlock()
            }
            return true
        }

        private fun write(message: JSONObject) {
            output.write((channel.seal(message.toString()) + "\n").toByteArray(Charsets.US_ASCII))
            output.flush()
        }

        fun close() {
            runCatching { socket.close() }
        }
    }

    /** Un intento en su hilo: busca al celular, saluda y, si conecta, atiende hasta que se cierre. */
    private inner class Attempt(
        val key: ByteArray,
        /** La llave es la del QR (todavía no guardada). */
        val fresh: Boolean,
        private val id: String,
        private val info: ScreenInfo,
        /** Direcciones donde ya se sabe del celular, cada una con el nombre de cómo se supo (para el reporte). */
        private val known: List<Pair<String, String>>,
        /** Si nadie contesta en las direcciones conocidas, tocar a los vecinos de las redes locales. */
        private val sweep: Boolean,
    ) : Runnable {
        @Volatile var cancelled = false
        @Volatile private var socket: Socket? = null
        private val music = MusicReader()
        /** El paquete que manda el celular: el orden se revisa y se escribe aquí, en el hilo de la conexión. */
        private val parts = PackageParts()
        // La carpeta se pide al llegar la primera parte (en este hilo): leer cacheDir toca el disco.
        private val writer = PackageWriter { File(app.cacheDir, PACKAGE_FOLDER) }

        @Volatile private var sweepers: ExecutorService? = null

        /** Las redes de la pantalla al empezar el intento: un socket hacia una de ellas se amarra a esa red. */
        private var nets = LocalNets(emptyList(), emptyMap(), emptyList())

        fun close() {
            runCatching { socket?.close() }
            sweepers?.shutdownNow()
        }

        private var error: String? = "sin celular en la red"
        private var bye = false
        private var served = false

        override fun run() {
            val found = localNets()
            nets = found
            main.post {
                localNetwork = found.any
                networkChecked = true
            }
            lastGateways = found.gateways.keys.joinToString()
            val tried = HashSet<String>()
            fun pass(addresses: List<Pair<String, String>>) {
                for ((address, how) in addresses) {
                    if (cancelled || served) return
                    if (tried.add(address)) reach(address, how)
                }
            }
            pass(found.gateways.keys.map { "$it:${LinkProtocol.PORT}" to "puerta de enlace" } + known)
            if (sweep && !served && !cancelled) pass(neighbors(found.own.take(SWEEP_NETWORKS)).map { it to "vecinos" })
            main.post { closed(this, error.takeIf { !cancelled }, bye) }
        }

        /** Prueba una dirección: saluda y, si es el celular, atiende hasta que la conexión se cierre. */
        private fun reach(address: String, how: String) {
            val host = address.substringBeforeLast(':')
            val port = address.substringAfterLast(':').toIntOrNull() ?: return
            val open = Socket()
            socket = open
            try {
                // Cancelado justo antes de guardar el socket: nadie más lo cerraría.
                if (cancelled) return
                bind(open, host)
                open.connect(InetSocketAddress(host, port), CONNECT_MS)
                val reader = LineReader(BufferedInputStream(open.getInputStream()))
                val channel = greet(open, reader)
                val ready = Session(open, channel)
                ready.send(
                    JSONObject().put("t", "info").put("role", "screen").put("app", info.app).put("car", info.car)
                        .put("carId", info.carId).put("screen", info.screen).put("caps", JSONArray(SCREEN_CAPS)),
                )
                // Ya hubo conexión: cuando se cierre o se caiga, este intento termina aquí.
                served = true
                main.post { opened(this, ready, address, how) }
                bye = serve(open, reader, channel, ready)
                error = null
            } catch (e: Exception) {
                // Nadie escucha en esa dirección: no es un error que contar, se prueba la siguiente.
                if (open.isConnected) error = "$address: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                runCatching { open.close() }
                // Un paquete a medias ya no se va a completar.
                writer.drop()
                parts.cancel()
            }
        }

        /**
         * Amarra el socket a la red donde vive [host]. Sin esto saldría por la red por defecto (los datos de
         * la SIM, por ejemplo) y nunca llegaría a un Wi‑Fi sin internet. El hotspot de la propia pantalla no
         * es una "red" de Android: no se amarra y sale bien solo.
         */
        private fun bind(open: Socket, host: String) {
            val prefix = host.substringBeforeLast('.')
            val network = nets.gateways[host] ?: nets.addresses.firstOrNull { it.first.substringBeforeLast('.') == prefix }?.second ?: return
            runCatching { network.bindSocket(open) }
        }

        /**
         * Los vecinos que escuchan en el puerto de Link en las redes [own] de la pantalla (su /24), incluida la de su
         * propio hotspot (que no es una "red" para Android y no tiene puerta de enlace).
         */
        private fun neighbors(own: List<String>): List<String> {
            lastNeighbors = own.joinToString()
            if (own.isEmpty() || cancelled) return emptyList()
            val found = Collections.synchronizedList(ArrayList<String>())
            val pool = Executors.newFixedThreadPool(SWEEP_THREADS) { Thread(it, "phone-link-sweep") }
            sweepers = pool
            for (address in own) {
                val prefix = address.substringBeforeLast('.')
                for (last in 1..254) {
                    val host = "$prefix.$last"
                    if (host == address) continue
                    pool.execute {
                        if (cancelled) return@execute
                        val probe = Socket()
                        try {
                            bind(probe, host)
                            probe.connect(InetSocketAddress(host, LinkProtocol.PORT), SWEEP_CONNECT_MS)
                            found += "$host:${LinkProtocol.PORT}"
                        } catch (_: Exception) {
                            // Ahí no hay nadie.
                        } finally {
                            runCatching { probe.close() }
                        }
                    }
                }
            }
            pool.shutdown()
            runCatching { pool.awaitTermination(SWEEP_WAIT_MS, TimeUnit.MILLISECONDS) }
            pool.shutdownNow()
            sweepers = null
            return found.toList()
        }

        /** Una línea en claro (solo en el saludo). */
        private fun writePlain(open: Socket, message: JSONObject) {
            open.getOutputStream().run {
                write((message.toString() + "\n").toByteArray(Charsets.UTF_8))
                flush()
            }
        }

        /** El saludo: cada lado prueba que tiene la llave. Regresa el canal cifrado o lanza. */
        private fun greet(open: Socket, reader: LineReader): SecureChannel {
            open.soTimeout = HELLO_MS
            val deadline = SystemClock.elapsedRealtime() + HELLO_MS
            val nc = ByteArray(LinkProtocol.NONCE_BYTES).also(random::nextBytes)
            writePlain(open, JSONObject().put("t", "hello").put("v", LinkProtocol.VERSION).put("id", id).put("nc", Base64.encode(nc)))
            val answer = JSONObject(reader.readLine(LinkProtocol.HELLO_LINE_MAX) ?: throw IOException("el celular no conoce esta pantalla"))
            if (answer.optString("t") == "error") throw IOException("el celular contestó: ${answer.optString("code")}")
            if (answer.optString("t") != "hello" || answer.optInt("v") != LinkProtocol.VERSION) throw IOException("saludo desconocido")
            val ns = Base64.decode(answer.getString("ns"))
            if (ns.size != LinkProtocol.NONCE_BYTES) throw IOException("saludo desconocido")
            if (!LinkProtocol.sameMac(LinkProtocol.phoneMac(key, nc, ns), Base64.decode(answer.getString("mac")))) throw IOException("la llave no coincide")
            if (SystemClock.elapsedRealtime() > deadline) throw IOException("saludo lento")
            writePlain(open, JSONObject().put("t", "auth").put("mac", Base64.encode(LinkProtocol.screenMac(key, nc, ns))))
            return SecureChannel(LinkProtocol.screenToPhoneKey(key, nc, ns), LinkProtocol.phoneToScreenKey(key, nc, ns))
        }

        /**
         * Una parte del paquete que manda el celular (§5f): se revisa el orden y se escribe aquí mismo (ya vienen en
         * orden y son de 64 KiB: nada se junta en memoria). Solo se acepta el paquete que la pantalla pidió y hasta su
         * tamaño; si no, se tira y se avisa una vez.
         */
        private fun receive(message: JSONObject) {
            val id = message.optString("id")
            val kind = message.optString("kind")
            val n = message.optInt("n", -1)
            // Datos que no son base64: como una parte fuera de orden.
            val data = runCatching { Base64.decode(message.optString("data")) }.getOrNull()
            val step = parts.take(id, kind, if (data == null) -1 else n, message.optInt("of", -1), data?.size ?: 0)
            if (step == PackageParts.Step.IGNORE) return
            val wanted = expected?.takeIf { it.id == id && it.kind == kind }
            if (step == PackageParts.Step.BAD || wanted == null || data == null || !writer.part(n, data, wanted.bytes)) {
                // Lo que siga de este paquete se ignora (un solo aviso).
                if (step != PackageParts.Step.BAD) parts.cancel()
                writer.drop()
                main.post { delivered(id, kind, null) }
            } else if (step == PackageParts.Step.LAST) {
                val done = writer.finish(wanted.bytes)
                main.post { delivered(id, kind, done) }
            }
        }

        /** Atiende la conexión hasta que se cierre; true si el celular se despidió (`bye`). Lanza si se cae. */
        private fun serve(open: Socket, reader: LineReader, channel: SecureChannel, ready: Session): Boolean {
            var lastHeard = SystemClock.elapsedRealtime()
            var lastPing = lastHeard
            while (true) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastHeard >= SILENCE_MS) throw IOException("sin respuesta del celular")
                // Se mide desde lo último que se OYÓ (no desde lo que se mandó): con el `state` cada 5 s la pantalla
                // nunca callaba, no pedía `pong` y daba la conexión por caída a los 45 s si el celular no hablaba.
                val quiet = now - maxOf(lastHeard, lastPing)
                if (quiet >= PING_MS) {
                    ready.trySend(JSONObject().put("t", "ping"))
                    lastPing = now
                    continue
                }
                // Despierta solo cuando toca mandar un ping o dar la conexión por caída.
                open.soTimeout = minOf(PING_MS - quiet, SILENCE_MS - (now - lastHeard)).toInt().coerceAtLeast(1)
                val line = try {
                    reader.readLine(LinkProtocol.LINE_MAX) ?: throw IOException("el celular cerró")
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val message = JSONObject(channel.open(line))
                lastHeard = SystemClock.elapsedRealtime()
                when (message.optString("t")) {
                    "info" -> {
                        val name = message.optString("name").take(INFO_MAX)
                        val version = message.optString("app").take(INFO_MAX)
                        val list = message.optJSONArray("caps")
                        val caps = if (list == null) emptySet() else (0 until list.length()).mapTo(HashSet()) { list.optString(it) }
                        // Ya se puede mandar `cmd`, `car` y `state` (antes de que el hilo principal se entere).
                        ready.music = CAP_MUSIC in caps
                        ready.carImage = message.optString("carImg")
                        ready.car = CAP_CAR in caps
                        ready.state = CAP_STATE in caps
                        main.post { heard(this, name, version, ready.car, ready.state) }
                    }
                    "setCar" -> {
                        val edit = CarEdit.parse(message)
                        // Otro auto cancela el paquete que venía del anterior (sin avisar que falló).
                        val receiving = parts.current
                        if (edit.id != null && receiving != null && receiving.first != edit.id) {
                            parts.cancel()
                            writer.drop()
                        }
                        main.post { if (attempt === this) onSetCar?.invoke(edit) }
                    }
                    "pkg" -> receive(message)
                    "report" -> {
                        val id = message.optString("id")
                        if (REPORT_ID.matches(id)) main.post { if (attempt === this) onReport?.invoke(id) }
                    }
                    "music" -> {
                        val next = music.heard(message)
                        val blocked = !message.optBoolean("access", true)
                        main.post { played(this, next, blocked) }
                    }
                    "art" -> music.art(message)?.let { next -> main.post { played(this, next, blocked = false) } }
                    "sound" -> {
                        val route = message.optString("route")
                        val why = message.optString("why")
                        main.post { heardSound(this, route, why) }
                    }
                    "ping" -> ready.trySend(JSONObject().put("t", "pong"))
                    "bye" -> return true
                    // "pong" y lo que no se conoce: nada (compatibilidad hacia adelante).
                }
            }
        }
    }

    /** Un paquete del celular llegó completo ([file]) o mal (null); sin quién lo reciba, se borra. */
    private fun delivered(id: String, kind: String, file: File?) {
        val take = onPackage
        if (take != null) take(id, kind, file) else if (file != null) io { file.delete() }
    }

    /**
     * Arma la canción con los mensajes `music` y `art` (en el hilo de la conexión): la carátula se guarda
     * por canción (`track`) y se pega a cada `music` de esa misma canción. Lo que llega del celular se acota
     * (textos, velocidad, tamaño de la carátula): se pinta tal cual en la pantalla.
     */
    private class MusicReader {
        private var last: PhoneSong? = null
        private var track: String? = null
        private var artTrack: String? = null
        private var art: Bitmap? = null

        /** null = no suena nada (o el celular no tiene permiso). */
        fun heard(m: JSONObject): PhoneSong? {
            val title = m.optString("title").take(TEXT_MAX)
            if (title.isEmpty()) {
                last = null
                track = null
                return null
            }
            track = m.optString("track")
            val hasArt = m.optBoolean("art")
            return PhoneSong(
                title = title,
                artist = m.optString("artist").take(TEXT_MAX),
                app = m.optString("app").take(INFO_MAX),
                playing = m.optBoolean("playing"),
                buffering = m.optBoolean("buffering"),
                durationMs = m.optLong("duration").coerceAtLeast(0),
                positionMs = m.optLong("position").coerceAtLeast(0),
                readAt = SystemClock.elapsedRealtime(),
                speed = m.optDouble("speed", 1.0).toFloat().takeIf { it > 0 }?.coerceAtMost(SPEED_MAX) ?: 1f,
                seekable = m.optBoolean("seek"),
                hasArt = hasArt,
                art = art.takeIf { hasArt && artTrack == track },
            ).also { last = it }
        }

        /**
         * La carátula de una canción; regresa la canción actual con ella si es la suya. Vacía o dañada (el
         * celular no pudo comprimirla): la canción queda sin carátula y la pantalla la busca por su cuenta.
         */
        fun art(m: JSONObject): PhoneSong? {
            val bytes = runCatching { Base64.decode(m.optString("jpg")) }.getOrNull()
            val decoded = bytes?.takeIf { it.isNotEmpty() }?.let(::decodeArt)
            artTrack = m.optString("track")
            art = decoded
            val current = last?.takeIf { artTrack == track && it.hasArt } ?: return null
            return current.copy(hasArt = decoded != null, art = decoded).also { last = it }
        }

        /** Primero mide la imagen y la decodifica reducida ([artSampleSize]): una enorme no tumba la pantalla. */
        private fun decodeArt(bytes: ByteArray): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options = BitmapFactory.Options().apply { inSampleSize = artSampleSize(bounds.outWidth, bounds.outHeight) }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }
    }

    private fun io(block: () -> Unit) = Thread(block, "phone-link-io").start()

    companion object {
        /** Si hay un celular vinculado, leído del disco sin crear el enlace (sin vínculo, LiteDash ni lo arma). */
        fun isPaired(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY)

        private const val PREFS = "link"
        private const val SCREEN_ID = "screen_id"
        private const val KEY = "key"
        private const val LAST_ADDRESS = "last_address"
        private const val PHONE_NAME = "phone_name"
        private const val PHONE_APP = "phone_app"
        private const val CAP_MUSIC = "music"
        private const val CAP_CAR = "car"
        private const val CAP_STATE = "state"
        /** Lo que entiende esta pantalla (§5, §5b, §5e–§5h). */
        private val SCREEN_CAPS = listOf(CAP_MUSIC, "carEdit", "carPkg", CAP_STATE, "report")
        /** El id de un reporte del celular: 32 hex. */
        private val REPORT_ID = Regex("[0-9a-f]{32}")
        /** El diagnóstico que se le pasa al celular para su reporte. */
        private const val REPORT_MAX = 15_000
        /** Topes de lo que manda el celular y se pinta o se guarda: nombres cortos (modelo, app) y textos de canción. */
        private const val INFO_MAX = 64
        private const val TEXT_MAX = 200
        private const val SPEED_MAX = 4f
        /** Carpeta (en la caché de la app) de los paquetes que llegan del celular. */
        private const val PACKAGE_FOLDER = "link-packages"

        /** Lo que vive la llave de un QR sin vincular (Ajustes → Celular lo dice). */
        const val OFFER_MS = 5 * 60_000L
        private const val WATCH_RETRY_MS = 2_000L
        private const val CONNECT_MS = 1_500

        /** Un aviso del faro mide 50 bytes; lo que no quepa aquí no es uno. Se atiende a lo más uno por segundo. */
        private const val BEACON_MAX = 64
        private const val BEACON_EVERY_MS = 1_000L
        private val WIFI_NAMES = listOf("wlan", "ap", "swlan", "softap")
        /** Tocar a los vecinos: pocas redes, muchos a la vez y con poca espera. */
        private const val SWEEP_NETWORKS = 2
        private const val SWEEP_THREADS = 32
        private const val SWEEP_CONNECT_MS = 400
        private const val SWEEP_WAIT_MS = 9_000L
        private const val HELLO_MS = 5_000
        private const val PING_MS = 20_000L
        private const val SILENCE_MS = 45_000L
    }
}

/** La carátula más grande que se guarda (lado mayor, en píxeles); Connect manda ≤ 320 (§5b). */
internal const val ART_MAX_PX = 512

/** Cuánto reducir al decodificar una carátula de [width]×[height] (potencia de 2) para que quepa en [ART_MAX_PX]. */
internal fun artSampleSize(width: Int, height: Int): Int {
    var sample = 1
    while (maxOf(width, height) / sample > ART_MAX_PX) sample *= 2
    return sample
}

/**
 * Escribe en orden, en el hilo de la conexión, el paquete que manda el celular (§5f), en un archivo temporal dentro
 * de la app ([folder]); completo, se lo pasa a quien lo pidió ([finish]).
 */
internal class PackageWriter(private val folder: () -> File) {
    private var file: File? = null
    private var out: OutputStream? = null
    private var written = 0L

    /** La parte [n]; falso (y no escribe) si no se pudo o si con ella pasaría de [max] bytes. */
    fun part(n: Int, data: ByteArray, max: Long): Boolean = try {
        if (n == 0) {
            drop()
            val folder = folder().apply { mkdirs() }
            // Lo que haya quedado de una vez que se cortó la app (lo entregado ya lo borró quien lo instaló); con fecha
            // «del futuro» (el reloj regresó, pantalla sin pila) también es viejo.
            val now = System.currentTimeMillis()
            folder.listFiles()?.filter { now - it.lastModified() !in 0 until STALE_MS }?.forEach { it.delete() }
            file = File.createTempFile("pkg-", ".zip", folder).also { out = FileOutputStream(it) }
        }
        val o = out
        written += data.size
        if (o == null || written > max) {
            false
        } else {
            o.write(data)
            true
        }
    } catch (_: IOException) {
        // Sin espacio o sin poder escribir.
        false
    }

    /**
     * El paquete completo: el archivo pasa a quien lo pidió (ya no es de aquí). Null (y se borra) si no se pudo
     * cerrar o no mide los [bytes] que se pidieron: llegó incompleto.
     */
    fun finish(bytes: Long): File? {
        val closed = try {
            out?.close()
            true
        } catch (_: IOException) {
            false
        }
        val complete = closed && written == bytes
        out = null
        val f = file
        file = null
        written = 0
        if (!complete) f?.delete()
        return f.takeIf { complete }
    }

    /** Tira lo que se estaba escribiendo. */
    fun drop() {
        runCatching { out?.close() }
        out = null
        file?.delete()
        file = null
        written = 0
    }

    private companion object {
        /** Cuándo un paquete a medias quedó abandonado (la app se cortó mientras llegaba). */
        const val STALE_MS = 60 * 60_000L
    }
}
