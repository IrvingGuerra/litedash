package com.irvingguerra.litedash

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.LifecycleStartEffect
import com.irvingguerra.litedash.core.vehicle.Signal
import com.irvingguerra.litedash.core.vehicle.Source
import com.irvingguerra.litedash.core.vehicle.VehicleBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * El auto en las pantallas con la pila SPD (`com.spd.carinfo`, p. ej. Qualcomm msm8953 con caja CAN): su servicio ya
 * lee la caja CAN y se le pregunta como lo hace su AIDL (`ICarInfoAidlInterface`), solo con lecturas. Nunca sus
 * setters: cambian ajustes del auto. Por eso los códigos de las lecturas se sacan de su app instalada ([codesOf]) y no
 * se fijan aquí. Publica en el bus, como [Source.CAN], la velocidad, la gasolina y el voltaje, solo lo que se está
 * mostrando y mientras LiteDash se ve ([KeepSpdRunning]). Cada perfil CAN trae sus propios campos y escalas: solo se lee
 * en los perfiles comprobados con el auto ([PROFILES]), y se vuelve a mirar en cada lectura.
 */
class SpdCar private constructor(private val context: Context, private val bus: VehicleBus) {
    /** El servicio conectado (null = no). */
    @Volatile private var service: IBinder? = null

    /** Los códigos de sus lecturas: se sacan la primera vez y de nuevo si su app cambia ([ServiceConnection.onBindingDied]). */
    @Volatile private var codes: Lazy<Codes?> = lazy { codesOf(context) }
    private var bound = false
    private var users = 0
    private var reading: Job? = null
    private val scope = MainScope()

    /** Despierta la lectura al conectar, sin esperar el segundo. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder
            wake.trySend(Unit)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }

        // Su app se actualizó o se apagó: Android ya no vuelve a conectar solo. Quizá trae otros códigos.
        override fun onBindingDied(name: ComponentName?) {
            service = null
            codes = lazy { codesOf(context) }
            if (users > 0) {
                unbind()
                bind()
            }
        }

        override fun onNullBinding(name: ComponentName?) {
            Log.w(TAG, "el servicio SPD no da su interfaz")
        }
    }

    /** Algo muestra lo que da: conecta con su servicio y lee cada segundo lo que se está mostrando. */
    fun acquire() {
        if (users++ > 0) return
        bind()
        reading = scope.launch {
            while (true) {
                val shown = bus.wanted.filter { gives(it) }
                val values = withContext(Dispatchers.IO) { readShown(shown) }
                // Lo que ya no se muestra (o no llegó) se quita: un dato viejo de la caja CAN le ganaría al GPS y al OBD.
                for (signal in SIGNALS) bus.publish(signal, values[signal]?.takeIf { trusted(signal, it) }, Source.CAN)
                withTimeoutOrNull(READ_EVERY_MS) { wake.receive() }
            }
        }
    }

    fun release() {
        if (users == 0 || --users > 0) return
        reading?.cancel()
        reading = null
        unbind()
        bus.clear(Source.CAN)
    }

    private fun bind() {
        bound = runCatching { context.bindService(Intent().setClassName(PACKAGE, SERVICE), connection, Context.BIND_AUTO_CREATE) }
            .onFailure { Log.w(TAG, "no se pudo conectar con el servicio SPD", it) }
            .getOrDefault(false)
    }

    private fun unbind() {
        if (bound) runCatching { context.unbindService(connection) }
        bound = false
        service = null
    }

    /** [shown] en la unidad del bus; nada si el perfil ya no es uno comprobado (lo cambiaron sin reiniciar). */
    private fun readShown(shown: List<String>): Map<String, Float?> =
        if (profile(context) in PROFILES) shown.associateWith { read(it) } else emptyMap()

    /** [signal] en la unidad del bus; null si no hay lectura creíble. Son IPC: fuera del hilo principal. */
    private fun read(signal: String): Float? = runCatching {
        when (signal) {
            Signal.SPEED -> call({ it.getFloat }, CURRENT_SPEED) { it.readFloat() }?.let(::spdSpeed)
            Signal.FUEL -> call({ it.getInt }, REMAIN_FUEL) { it.readInt() }?.let { spdFuel(it, setting(TANK_KEY)) }
            Signal.VOLTAGE -> setting(VOLTAGE_KEY)?.let(::spdVolts)
            else -> null
        }
    }.getOrNull()

    /**
     * Lo que dice la caja CAN, salvo que otra fuente en vivo lo desmienta: sin dato su servicio también responde 0, y su
     * voltaje es un ajuste guardado (puede ser el último que escribió), mientras que el del OBD conectado es en vivo.
     */
    private fun trusted(signal: String, value: Float): Boolean = when (signal) {
        Signal.SPEED -> spdSpeedTrusted(value, bus.value(Signal.SPEED, Source.GPS))
        Signal.VOLTAGE -> bus.value(Signal.VOLTAGE, Source.OBD) == null
        else -> true
    }

    /** `getInt`/`getFloat(id, 0)` como los pide su AIDL; null sin servicio o sin los códigos de su app. */
    private fun <T> call(code: (Codes) -> Int, id: Int, read: (Parcel) -> T): T? {
        val binder = service ?: return null
        val transaction = codes.value?.let(code) ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(id)
            // El 2.º argumento no es un valor por omisión: sus propiedades se leen siempre con 0.
            data.writeInt(0)
            if (!binder.transact(transaction, data, reply, 0)) return null
            reply.readException()
            return read(reply)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun setting(key: String): Int? = setting(context, key)

    /** Los códigos Binder de las lecturas del servicio. */
    private class Codes(val getInt: Int, val getFloat: Int)

    companion object {
        private const val TAG = "SpdCar"
        private const val PACKAGE = "com.spd.carinfo"
        private const val SERVICE = "com.spd.carinfo.service.CarInfoService"
        private const val DESCRIPTOR = "com.spd.carinfo.ICarInfoAidlInterface"
        // Propiedades SPD (no son códigos de Binder).
        private const val CURRENT_SPEED = 140062 // ReverseAndAVM.CURRENT_SPEED, km/h
        private const val REMAIN_FUEL = 100045 // Instruments.REMAIN_FUEL, litros (en otros perfiles es otra cosa)
        // Ajustes del sistema que escribe la pila SPD.
        private const val PROFILE_KEY = "SETTING_FACTORY_CAR" // el perfil CAN (= Vehicles.CURRENT_MODEL)
        private const val VOLTAGE_KEY = "SETTING_CURRENT_VOLTAGE" // décimas de volt
        private const val TANK_KEY = "SETTING_FUEL_TANK_CAPACITY" // litros

        /** Perfiles CAN comprobados con el auto: zhongmayitong → BMW → BMW MINI. */
        private val PROFILES = setOf(135_725_059)
        private val SIGNALS = setOf(Signal.SPEED, Signal.FUEL, Signal.VOLTAGE)
        private const val READ_EVERY_MS = 1_000L

        /** Lo que da una pantalla SPD (sin averiguar si esta lo es). */
        fun gives(signal: String) = signal in SIGNALS

        /** La pantalla es SPD con un perfil comprobado; null en las demás. */
        fun detect(context: Context, bus: VehicleBus): SpdCar? =
            if (profile(context) in PROFILES && installed(context)) SpdCar(context, bus) else null

        /**
         * Para el reporte: el perfil de una pantalla SPD, también uno sin comprobar, y si se encontraron los códigos de sus
         * lecturas (sin ellos no se lee nada); null si no es SPD. Carga clases de su app: fuera del hilo principal.
         */
        fun describe(context: Context): String? {
            if (!installed(context)) return null
            val profile = profile(context)
            val codes = codesOf(context)?.let { "getInt ${it.getInt} · getFloat ${it.getFloat}" } ?: "sin códigos de lectura"
            return "SPD: perfil ${profile ?: "?"} ${if (profile in PROFILES) "comprobado" else "sin comprobar"} · $codes"
        }

        /**
         * Los códigos de `getInt` y `getFloat` en el `Stub` de su app instalada (`TRANSACTION_*`): una actualización que
         * agregue o mueva métodos no hace que se llame a un setter. null si no se pueden leer: entonces no se llama nada.
         */
        private fun codesOf(context: Context): Codes? = runCatching {
            val stub = context.createPackageContext(PACKAGE, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
                .classLoader.loadClass("$DESCRIPTOR\$Stub")
            fun code(method: String) = stub.getDeclaredField("TRANSACTION_$method").apply { isAccessible = true }.getInt(null)
            Codes(getInt = code("getInt"), getFloat = code("getFloat"))
        }.onFailure { Log.w(TAG, "sin los códigos de lectura del servicio SPD", it) }.getOrNull()

        private fun profile(context: Context) = setting(context, PROFILE_KEY)

        private fun installed(context: Context) = runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

        private fun setting(context: Context, key: String): Int? = runCatching { Settings.System.getInt(context.contentResolver, key) }.getOrNull()
    }
}

/** km/h de `CURRENT_SPEED`; null si no es creíble (0 sí vale: parado). */
internal fun spdSpeed(kmh: Float): Float? = kmh.takeIf { it in 0f..400f }

/**
 * Si la velocidad de la caja CAN ([can]) se cree junto a la del GPS ([gps], null sin él). Sin dato su servicio también
 * responde 0: un 0 mientras el GPS dice que vas andando no es "parado", y entonces gana el GPS.
 */
internal fun spdSpeedTrusted(can: Float, gps: Float?): Boolean = can > 0f || gps == null || gps <= SPD_STILL_GPS_KMH

/** Hasta aquí el GPS puede marcar algo con el auto parado (se mueve solo unos metros). */
private const val SPD_STILL_GPS_KMH = 10f

/** El % del tanque con los litros que quedan ([liters]) y su capacidad ([tank]); null si alguno no es creíble. */
internal fun spdFuel(liters: Int, tank: Int?): Float? =
    if (liters in 1..150 && tank != null && tank > 0) minOf(liters * 100f / tank, 100f) else null

/** Volts de `SETTING_CURRENT_VOLTAGE`, en décimas (123 = 12.3 V); null si no es creíble. */
internal fun spdVolts(tenths: Int): Float? = if (tenths in 1..320) tenths / 10f else null

/**
 * Lee el auto por SPD mientras esta ventana se ve y algo muestra lo que da. Hasta entonces no averigua si la pantalla
 * es SPD; en las demás eso cuesta leer un ajuste del sistema, una sola vez.
 */
@Composable
fun KeepSpdRunning(d: Dependencies) {
    val spd = (if (d.bus.wanted.any { SpdCar.gives(it) }) d.spd else null) ?: return
    LifecycleStartEffect(spd) {
        spd.acquire()
        onStopOrDispose { spd.release() }
    }
}
