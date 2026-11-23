package com.irvingguerra.litedash.feature.music

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Un perfil de la radio FM de fábrica de una marca: se le habla como su propia app para ver la
 * estación, cambiarla y dar play o pausa SIN abrirla. TopWay ([TopWayRadio]), NWD ([NwdRadio]) y HCN ([HcnRadio]).
 */
internal interface FactoryRadio {
    /** Nombre corto para el registro de los reportes ("topway", "nwd", "hcn"). */
    val tag: String
    /** Conectado al sintonizador. */
    val open: Boolean
    /** "FM 91.1"; null si no ha dicho. */
    val station: String?
    /** Nombre RDS de la estación ("" si no hay). */
    val name: String
    /** La radio tiene el audio; null = no ha dicho. */
    val on: Boolean?
    /** Cuándo dijo la radio que tiene el audio (para confirmar un play). */
    val audioAt: Long
    /** Un play solo se puede confirmar por el audio de Android (la radio no dice si tiene el audio). */
    val confirmsByAudio: Boolean get() = false

    /** Se conecta; false si la pantalla no lo deja. */
    fun open(): Boolean
    fun close()
    /** Vuelve a pedir o leer la estación. */
    fun refresh()
    fun next()
    fun previous()
    /** Play sin abrir su app. */
    fun takeAudio()
    /** Pausa: suelta el audio de la radio. */
    fun releaseAudio()
    /** Se dio play abriendo su app: suena hasta que la radio diga otra cosa. */
    fun started()
}

/**
 * Lo que comparten los perfiles de radio de fábrica: nombre RDS, si tiene el audio y cuándo lo
 * dijo, y cómo se avisa a la música (título = nombre RDS o la estación; abajo, la estación).
 */
internal abstract class BaseFactoryRadio(private val onState: (BroadcastMeta) -> Unit) : FactoryRadio {
    override var name = ""
        protected set
    override var on: Boolean? = null
        protected set
    /**
     * Cuándo dijo el sintonizador que tiene el audio, para confirmar un play. No vale lo que se escribe uno
     * mismo: en TopWay el enlace reparte lo que se escribe, así que su "actividad" llegaría la nuestra.
     */
    override var audioAt = 0L
        protected set

    /** Avisa la estación a la música; nada si aún no dice ni frecuencia ni nombre. */
    protected fun publish() {
        val text = station
        if (text == null && name.isEmpty()) return
        onState(BroadcastMeta(title = name.ifEmpty { text }, artist = text.takeIf { name.isNotEmpty() }, playing = on ?: false, pkg = null, bluetooth = false))
    }
}

/**
 * Un perfil que le habla al servicio de la radio de su marca por Binder, como lo hace su AIDL (HCN y NWD): el
 * hilo propio para las llamadas (son IPC: nunca en el principal), conectar sin arrancarlo ([open]) o
 * arrancándolo ([wake], al tocar un botón: arrancarlo puede tomar el audio), el aviso de cada cambio de
 * estación (su callback, registrado al conectar y soltado al cerrar, también si se cerró a medio conectar) y
 * cerrarse si el servicio no contesta ([lost]). Cada marca pone su intent, sus códigos y su lectura.
 */
internal abstract class BinderFactoryRadio(
    protected val context: Context,
    onState: (BroadcastMeta) -> Unit,
    protected val trace: (String) -> Unit,
    final override val tag: String,
    /** La interfaz del servicio y la del callback (las que pone su AIDL). */
    private val descriptor: String,
    private val callbackDescriptor: String,
    /** Los códigos para registrar y soltar el callback. */
    private val registerCode: Int,
    private val unregisterCode: Int,
) : BaseFactoryRadio(onState) {
    final override var open = false
        private set

    protected val main = Handler(Looper.getMainLooper())
    /** Las llamadas al servicio son IPC: nunca en el hilo principal. Null = cerrado. */
    @Volatile protected var worker: ExecutorService? = null
        private set
    /** El servicio conectado (null = no). */
    @Volatile protected var service: IBinder? = null
        private set
    /** El servicio avisa los cambios de estación (si no, se relee tras un botón: [rereadSoon]). */
    @Volatile var notified = false
        private set
    private var bound = false
    /** Ya se pidió arrancar el servicio (al tocar un botón). */
    private var started = false
    /** Veces que su servicio murió en esta apertura: uno que truena al arrancar no se rearranca sin fin. */
    private var deaths = 0
    /** La última conexión se pidió arrancando el servicio (recién creado puede tardar en sintonizar). */
    @Volatile protected var created = false
        private set

    /** Conectado a su servicio (para los reportes). */
    val connected: Boolean get() = service != null

    private val callback = object : Binder() {
        init { attachInterface(null, callbackDescriptor) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (!handlesCallback(code)) return super.onTransact(code, data, reply, flags)
            runCatching {
                data.enforceInterface(callbackDescriptor)
                readCallback(code, data)
            }
                .onSuccess { action -> if (action != null) main.post { if (open) action() } }
                // Un aviso que no se entiende queda en el registro: diría si cambió el contrato.
                .onFailure { trace("$tag aviso $code no se entiende: ${it.javaClass.simpleName}") }
            reply?.writeNoException()
            return true
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            main.removeCallbacks(connectTimeout)
            val w = worker ?: return
            w.execute {
                if (worker !== w || binder == null) return@execute
                if (runCatching { binder.interfaceDescriptor }.getOrNull() != descriptor) {
                    trace("$tag servicio con otra interfaz")
                    main.post(::unusable)
                    return@execute
                }
                if (!prepare(binder)) {
                    main.post(::lost)
                    return@execute
                }
                val registered = runCatching { call(registerCode, { it.writeStrongBinder(callback) }, binder) {} }.isSuccess
                // Se cerró mientras se conectaba: se suelta lo que se alcanzó a registrar.
                if (worker !== w) {
                    if (registered) runCatching { call(unregisterCode, { it.writeStrongBinder(callback) }, binder) {} }
                    return@execute
                }
                service = binder
                notified = registered
                trace("$tag conectado ${connectedDetail()} avisos=${yes(registered)}")
                ready()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            notified = false
            trace("$tag servicio desconectado")
        }

        override fun onBindingDied(name: ComponentName?) {
            service = null
            notified = false
            if (++deaths > MAX_DEATHS) {
                trace("$tag servicio murió $deaths veces: se deja")
                return lost()
            }
            trace("$tag servicio murió: se vuelve a conectar")
            bind(started)
        }

        override fun onNullBinding(name: ComponentName?) {
            trace("$tag servicio sin interfaz")
            unusable()
        }
    }

    /** Se pidió arrancar el servicio y no contestó a tiempo. */
    private val connectTimeout = Runnable {
        if (service == null) {
            trace("$tag servicio no contestó")
            lost()
        }
    }

    /** El intent para conectar con su servicio. */
    protected abstract fun serviceIntent(): Intent

    /** La pantalla trae su servicio (sin él no se conecta ni se arranca). */
    protected open val serviceInstalled: Boolean get() = true

    /** Los códigos del callback que se atienden (los demás van a [Binder.onTransact]). */
    protected abstract fun handlesCallback(code: Int): Boolean

    /** Lee un aviso del callback (en un hilo de Binder); lo que devuelve corre en el principal si sigue abierto. */
    protected abstract fun readCallback(code: Int, data: Parcel): (() -> Unit)?

    /** Recién conectado, antes de registrar el callback (en [worker]): false = no es un contrato conocido. */
    protected open fun prepare(binder: IBinder): Boolean = true

    /** Lo que se dice del servicio al conectar ("contrato=31", "tipo=2"). */
    protected abstract fun connectedDetail(): String

    /** Ya conectado y con el callback (en [worker]): leer la estación y lo que estuviera pendiente. */
    protected abstract fun ready()

    /** La conexión no sirve (no conecta, sin interfaz u otra interfaz): de fábrica el perfil se cierra. */
    protected open fun unusable() = lost()

    /** Empieza el hilo y conecta si el servicio ya corre (sin arrancarlo); false si ya se cerró. */
    protected fun connect(): Boolean {
        if (open) return true
        worker = Executors.newSingleThreadExecutor { Thread(it, "$tag-radio") }
        open = true
        if (serviceInstalled) bind(create = false)
        return open
    }

    override fun close() {
        if (!open) return
        open = false
        main.removeCallbacksAndMessages(null)
        val w = worker
        worker = null
        val s = service
        service = null
        if (s != null && notified) w?.execute { runCatching { call(unregisterCode, { it.writeStrongBinder(callback) }, s) {} } }
        notified = false
        w?.shutdown()
        if (bound) runCatching { context.unbindService(connection) }
        bound = false
    }

    /** Conecta con su servicio; [create] lo arranca si no corre (y espera [CONNECT_TIMEOUT_MS] a que conteste). */
    protected fun bind(create: Boolean) {
        if (!open) return
        if (bound) runCatching { context.unbindService(connection) }
        created = create
        bound = runCatching { context.bindService(serviceIntent(), connection, if (create) Context.BIND_AUTO_CREATE else 0) }.getOrDefault(false)
        if (!bound) {
            runCatching { context.unbindService(connection) }
            trace("$tag no se pudo conectar con su servicio")
            return unusable()
        }
        if (create) main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
    }

    /** Antes de un botón: si el servicio no está conectado, se arranca (una vez). */
    protected fun wake() {
        if (service != null || started || !serviceInstalled || !open) return
        started = true
        bind(create = true)
    }

    /** Sin servicio que conteste: el perfil se cierra y la radio se maneja como antes (teclas y abrir la app). */
    protected open fun lost() {
        if (!open) return
        trace("$tag sin servicio: como antes")
        close()
    }

    /** Sin el aviso del servicio, la estación nueva tarda: se vuelve a leer un par de veces. */
    protected fun rereadSoon() {
        if (!notified) for (delay in REREAD_MS) main.postDelayed({ if (open) refresh() }, delay)
    }

    /** Una llamada al servicio, como la hace su AIDL (token, argumentos, excepción, respuesta). */
    protected fun <T> call(code: Int, write: (Parcel) -> Unit = {}, binder: IBinder? = service, read: (Parcel) -> T): T {
        checkNotNull(binder) { "sin servicio" }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(descriptor)
            write(data)
            check(binder.transact(code, data, reply, 0)) { "rechazó $code" }
            reply.readException()
            return read(reply)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 6_000L
        /** Cuándo releer la estación tras un botón si el servicio no avisa (no hay otra señal). */
        val REREAD_MS = longArrayOf(1_500L, 4_000L)
    }
}

/**
 * "FM 91.1" o "AM 1220"; null sin frecuencia. FM en centésimas de MHz (= decenas de kHz: 9110 = 91.1),
 * AM en kHz. Cada marca dice cuándo es AM (TopWay: banda 2; NWD: banda 3 o más).
 */
internal fun stationText(am: Boolean, frequency: Int): String? = when {
    frequency <= 0 -> null
    am -> "AM $frequency"
    else -> "FM " + String.format(Locale.US, "%.1f", frequency / 100f)
}

/** Reconexiones tras morir su servicio, por cada apertura del perfil ([BinderFactoryRadio]). */
private const val MAX_DEATHS = 3
