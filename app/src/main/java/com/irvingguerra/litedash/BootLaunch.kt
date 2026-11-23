// Abrir LiteDash al encender el auto (Ajustes → Inicio y apariencia → Hazlo tu inicio).
package com.irvingguerra.litedash

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Varias pantallas de auto regresan su launcher de fábrica cada vez que encienden (aunque LiteDash
 * sea el inicio elegido) o lo abren solas al dar contacto. Con "Abrir LiteDash al encender", al
 * arrancar o despertar la pantalla LiteDash se pone al frente unos segundos después.
 *
 * Avisos de encendido: el de Android (BOOT_COMPLETED), el encendido rápido de MediaTek y los de
 * cada marca (FYT, GLSX, Microntek) según la guía de arranque de HeadUnit Revived
 * (gist.github.com/andrecuellar/3c39b4f1bdc03a5c4e97336217f13c38). Desde Android 10 una app solo
 * puede abrirse sola con el permiso "Mostrar sobre otras apps". El receptor está apagado (no corre)
 * mientras la opción esté apagada. Desde Android 8 los avisos de marca (ACC) solo llegan al
 * manifiesto si el sistema los manda a apps en segundo plano; el que siempre llega es el de arranque.
 * Por eso, con la opción prendida, el mismo receptor también se registra mientras LiteDash esté
 * vivo ([BootLaunch.watch]); si llega por los dos caminos, [repeated] abre una sola vez.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in BootLaunch.ACTIONS) return
        val app = context.applicationContext
        if (!BootLaunch.enabled(app)) return
        val log = app.getSharedPreferences(BootLaunch.LOG, Context.MODE_PRIVATE)
        val up = SystemClock.elapsedRealtime()
        val boot = bootCount(app)
        val last = Stamp(log.getLong(KEY_UPTIME, -1L), log.getInt(KEY_BOOT, NO_BOOT))
        if (repeated(last, Stamp(up, boot), memory)) return
        // El sello va antes de esperar: los avisos que llegan juntos en esos segundos ya no pasan.
        memory = up
        log.edit { putLong(KEY_UPTIME, up).putInt(KEY_BOOT, boot) }
        val pending = goAsync()
        // Se espera a que el sistema termine de abrir su launcher, para quedar encima.
        Handler(Looper.getMainLooper()).postDelayed({
            // Desde Android 10, sin el permiso el sistema bloquea la apertura sin avisar: se anota.
            val ok = BootLaunch.allowed(app) && BootLaunch.open(app)
            log.edit { putString(KEY_ACTION, action).putLong(KEY_OPENED_AT, System.currentTimeMillis()).putBoolean(KEY_OK, ok) }
            pending.finish()
        }, DELAY_MS)
    }

    companion object {
        /** Cuándo se abrió ([up], tiempo desde el arranque; -1 = nunca) y en qué arranque ([boot]; [NO_BOOT] = no se sabe). */
        internal class Stamp(val up: Long, val boot: Int)

        /** NO_BOOT: Android 6 no lleva la cuenta de arranques ([bootCount]). */
        internal const val NO_BOOT = -1

        /**
         * El sello de este proceso (tiempo desde el arranque; -1 = ninguno): tras reiniciar la pantalla el proceso es
         * otro, así que nunca es de otro arranque. Lo usa Android 6, donde el sello guardado no dice de qué arranque es.
         */
        @Volatile private var memory = -1L

        /**
         * Algunas marcas mandan varios avisos seguidos al encender: se abre una sola vez. Se mide con el tiempo
         * desde el arranque, no con la hora: muchas pantallas no tienen pila de reloj y prenden en 1970 o 2015 hasta
         * tener red. Solo cuenta un sello del MISMO arranque: las pantallas tardan casi lo mismo en arrancar cada
         * vez, y un sello de 25 s del arranque anterior callaba el aviso de los 31 s de este. Con la cuenta de
         * arranques se compara el guardado ([last]; uno sin cuenta, de antes, es de otro arranque); sin ella
         * (Android 6), solo el de este proceso ([memory]).
         */
        internal fun repeated(last: Stamp, now: Stamp, memory: Long): Boolean {
            val stamp = when {
                now.boot == NO_BOOT -> memory
                last.boot == now.boot -> last.up
                else -> -1L
            }
            return stamp in 0..now.up && now.up - stamp < DEBOUNCE_MS
        }

        /** Cuántas veces ha arrancado la pantalla (Android 7+); [NO_BOOT] si no se sabe. */
        private fun bootCount(context: Context): Int =
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) NO_BOOT
            else runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, NO_BOOT) }.getOrDefault(NO_BOOT)

        private const val DELAY_MS = 3_000L
        private const val DEBOUNCE_MS = 30_000L
        private const val KEY_UPTIME = "opened_uptime"
        private const val KEY_BOOT = "opened_boot"
        internal const val KEY_ACTION = "last_action"
        internal const val KEY_OPENED_AT = "opened_at"
        internal const val KEY_OK = "opened_ok"
    }
}

object BootLaunch {
    /** Lo último que pasó al encender (para los reportes). */
    internal const val LOG = "boot"

    /** Lo mismo que el `<intent-filter>` de `.BootReceiver` en el manifiesto: cambiar los dos juntos. */
    val ACTIONS = setOf(
        Intent.ACTION_BOOT_COMPLETED,
        "android.intent.action.QUICKBOOT_POWERON", "com.htc.intent.action.QUICKBOOT_POWERON", // encendido rápido
        "android.intent.action.ACTION_BOOT_IPO",                                               // MediaTek IPO
        "com.fyt.boot.ACCON",                                                                  // FYT
        "com.glsx.boot.ACCON",                                                                 // GLSX
        "com.cayboy.action.ACC_ON", "com.carboy.action.ACC_ON",                                // Microntek/HCT
        "com.qf.action.ACC_ON",                                                                // QF / TS10
        "autochips.intent.action.QB_POWERON",                                                  // Autochips/Jancar
    )

    // El receptor en tiempo de ejecución (null = no registrado). Sin BOOT_COMPLETED: ese llega antes de que haya proceso.
    private var live: BootReceiver? = null

    /** La opción "Abrir LiteDash al encender", leída del disco (el receptor y el arranque corren sin [Preferences]). */
    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PrefKeys.FILE, Context.MODE_PRIVATE).getBoolean(PrefKeys.OPEN_ON_BOOT, false)

    /** Desde Android 10, abrirse sola necesita "Mostrar sobre otras apps". */
    fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(context)

    /** La pantalla de Android para dar "Mostrar sobre otras apps" a LiteDash. */
    fun permissionIntent(context: Context): Intent =
        appSettingsIntent(context, Settings.ACTION_MANAGE_OVERLAY_PERMISSION)

    fun open(context: Context): Boolean = runCatching {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    /**
     * Los cambios del receptor, uno tras otro y fuera del hilo principal (son consultas al PackageManager). Un solo
     * hilo que se cierra a los pocos segundos sin trabajo: prender y apagar rápido queda en el orden en que se pidió.
     */
    private val worker = ThreadPoolExecutor(0, 1, 5, TimeUnit.SECONDS, LinkedBlockingQueue())

    /** Prende o apaga el receptor según la opción (apagado no se despierta con cada encendido). En otro hilo. */
    fun sync(context: Context, on: Boolean) {
        val app = context.applicationContext
        worker.execute {
            watch(app, on)
            val pm = app.packageManager
            val component = ComponentName(app, BootReceiver::class.java)
            val wanted = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            try {
                if (pm.getComponentEnabledSetting(component) != wanted) pm.setComponentEnabledSetting(component, wanted, PackageManager.DONT_KILL_APP)
            } catch (e: RuntimeException) {
                // Una pantalla que no deja cambiarlo no debe cerrar la app.
                Log.w(LOG_TAG, "No se pudo cambiar el receptor de encendido", e)
            }
        }
    }

    /**
     * Con la opción prendida, el receptor también escucha mientras el proceso viva (los avisos de marca
     * que el sistema ya no entrega al manifiesto); apagada, nada registrado. Una sola vez aunque se llame seguido.
     */
    @Synchronized
    private fun watch(app: Context, on: Boolean) {
        val current = live
        if (on && current == null) {
            val receiver = BootReceiver()
            val filter = IntentFilter().apply { ACTIONS.filter { it != Intent.ACTION_BOOT_COMPLETED }.forEach(::addAction) }
            // Exportado: los manda el sistema de la pantalla, no LiteDash.
            runCatching { ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED) }
                .onSuccess { live = receiver }
                .onFailure { Log.w(LOG_TAG, "No se pudo escuchar el encendido", it) }
        } else if (!on && current != null) {
            runCatching { app.unregisterReceiver(current) }
            live = null
        }
    }

    /**
     * Para los reportes: cuál es el inicio predeterminado (si una marca lo cambia al encender, aquí se
     * ve), qué otros inicios hay, y cómo va "Abrir al encender" (permiso y lo último que pasó).
     */
    fun describe(context: Context): String = buildString {
        val pm = context.packageManager
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val default = runCatching { pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName }.getOrNull()
        val all = runCatching { pm.queryIntentActivities(home, 0).map { it.activityInfo.packageName }.distinct() }.getOrDefault(emptyList())
        // "android" = no hay uno elegido (Android pregunta cuál usar).
        appendLine("Inicio: ${if (isHomeApp(context)) "LiteDash" else "otro"} (predeterminado=${default ?: "-"}; inicios=${all.joinToString().ifEmpty { "-" }})")
        val log = context.getSharedPreferences(LOG, Context.MODE_PRIVATE)
        val at = log.getLong(BootReceiver.KEY_OPENED_AT, 0L)
        // "hace" puede salir raro si el reloj de la pantalla se movió desde entonces (sin pila de reloj).
        val last = if (at == 0L) "nunca" else "${log.getString(BootReceiver.KEY_ACTION, "?")} hace ${(System.currentTimeMillis() - at) / 60_000} min, abrió=${if (log.getBoolean(BootReceiver.KEY_OK, false)) "sí" else "no"}"
        append("Abrir al encender: ${if (enabled(context)) "sí" else "no"}; permiso=${if (allowed(context)) "sí" else "no"}; último=$last")
    }
}
