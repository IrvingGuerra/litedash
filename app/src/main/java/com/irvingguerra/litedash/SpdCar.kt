package com.irvingguerra.litedash

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.LifecycleStartEffect
import com.irvingguerra.litedash.core.vehicle.Signal
import com.irvingguerra.litedash.core.vehicle.Source
import com.irvingguerra.litedash.core.vehicle.VehicleBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * El auto en las pantallas con la pila SPD (`com.spd.carinfo`, p. ej. Qualcomm msm8953 con caja CAN): su servicio ya
 * lee la caja CAN y se le pregunta como lo hace su AIDL (`ICarInfoAidlInterface`), solo con lecturas. Nunca sus
 * setters: cambian ajustes del auto. Publica en el bus, como [Source.CAN], la velocidad, la gasolina y el voltaje,
 * solo lo que se está mostrando y mientras LiteDash se ve ([KeepSpdRunning]). Cada perfil CAN trae sus propios campos
 * y escalas: solo se lee en los perfiles comprobados con el auto ([PROFILES]).
 */
class SpdCar private constructor(private val context: Context, private val bus: VehicleBus) {
    /** El servicio conectado (null = no). */
    @Volatile private var service: IBinder? = null
    private var users = 0
    private var reading: Job? = null
    private val scope = MainScope()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    /** Algo muestra lo que da: conecta con su servicio y lee cada segundo lo que se está mostrando. */
    fun acquire() {
        if (users++ > 0) return
        runCatching { context.bindService(Intent().setClassName(PACKAGE, SERVICE), connection, Context.BIND_AUTO_CREATE) }
        reading = scope.launch {
            while (true) {
                val shown = bus.wanted.filter { gives(it) }
                val values = withContext(Dispatchers.IO) { shown.map { it to read(it) } }
                for ((signal, value) in values) bus.publish(signal, value, Source.CAN)
                delay(READ_EVERY_MS)
            }
        }
    }

    fun release() {
        if (users == 0 || --users > 0) return
        reading?.cancel()
        reading = null
        runCatching { context.unbindService(connection) }
        service = null
        bus.clear(Source.CAN)
    }

    /** [signal] en la unidad del bus; null si no hay lectura creíble. Son IPC: fuera del hilo principal. */
    private fun read(signal: String): Float? = runCatching {
        when (signal) {
            Signal.SPEED -> call(GET_FLOAT, CURRENT_SPEED) { it.readFloat() }?.let(::spdSpeed)
            Signal.FUEL -> call(GET_INT, REMAIN_FUEL) { it.readInt() }?.let { spdFuel(it, setting(TANK_KEY)) }
            Signal.VOLTAGE -> setting(VOLTAGE_KEY)?.let(::spdVolts)
            else -> null
        }
    }.getOrNull()

    /** `getInt`/`getFloat(id, 0)` como los pide su AIDL; null sin servicio. */
    private fun <T> call(code: Int, id: Int, read: (Parcel) -> T): T? {
        val binder = service ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(id)
            data.writeInt(0)
            if (!binder.transact(code, data, reply, 0)) return null
            reply.readException()
            return read(reply)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun setting(key: String): Int? = setting(context, key)

    companion object {
        private const val PACKAGE = "com.spd.carinfo"
        private const val SERVICE = "com.spd.carinfo.service.CarInfoService"
        private const val DESCRIPTOR = "com.spd.carinfo.ICarInfoAidlInterface"
        // Códigos de `ICarInfoAidlInterface.Stub`. Del 5 al 8 son los setters: nunca se llaman.
        private const val GET_INT = 9
        private const val GET_FLOAT = 10
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

        /** Para el reporte: el perfil de una pantalla SPD, también uno sin comprobar; null si no es SPD. */
        fun describe(context: Context): String? {
            if (!installed(context)) return null
            val profile = profile(context)
            return "SPD: perfil ${profile ?: "?"} ${if (profile in PROFILES) "comprobado" else "sin comprobar"}"
        }

        private fun profile(context: Context) = setting(context, PROFILE_KEY)

        private fun installed(context: Context) = runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

        private fun setting(context: Context, key: String): Int? = runCatching { Settings.System.getInt(context.contentResolver, key) }.getOrNull()
    }
}

/** km/h de `CURRENT_SPEED`; null si no es creíble (0 sí vale: parado). */
internal fun spdSpeed(kmh: Float): Float? = kmh.takeIf { it in 0f..400f }

/** El % del tanque con los litros que quedan ([liters]) y su capacidad ([tank]); null si alguno no es creíble. */
internal fun spdFuel(liters: Int, tank: Int?): Float? =
    if (liters in 1..150 && tank != null && tank > 0) minOf(liters * 100f / tank, 100f) else null

/** Volts de `SETTING_CURRENT_VOLTAGE`, en décimas (123 = 12.3 V); null si no es creíble. */
internal fun spdVolts(tenths: Int): Float? = if (tenths in 1..320) tenths / 10f else null

/**
 * Lee el auto por SPD mientras esta ventana se ve y algo muestra lo que da. Hasta entonces no averigua si la pantalla
 * es SPD: en las demás no cuesta nada.
 */
@Composable
fun KeepSpdRunning(d: Dependencies) {
    val spd = (if (d.bus.wanted.any { SpdCar.gives(it) }) d.spd else null) ?: return
    LifecycleStartEffect(spd) {
        spd.acquire()
        onStopOrDispose { spd.release() }
    }
}
