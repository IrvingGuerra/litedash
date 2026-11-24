package com.irvingguerra.litedash

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.SystemClock
import androidx.core.content.edit
import com.irvingguerra.litedash.feature.music.FactoryAudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

/**
 * Cuántas pantallas usan LiteDash, sin cuentas ni SDK de Firebase: a lo más una vez al día,
 * al abrir el inicio, escribe `screens/{id}` en Firestore por REST (nombres de la nube en inglés). El id es un número al
 * azar de esta instalación; se manda solo la versión, el canal (GitHub o Play), Android, el idioma en que se ve LiteDash,
 * el tamaño de pantalla, desde qué día se usa, la radio y el Bluetooth DE FÁBRICA de la pantalla ([FactoryAudio]: solo
 * apps del sistema, para saber qué familias soportar; nada de ubicación ni del usuario) y, si ya se calibró, la receta de
 * sonido que le funcionó a cada uno (`btRecipe`, `fmRecipe`: para el catálogo compartido de recetas). Las reglas de Firestore solo dejan escribir
 * esos campos y nadie puede leerlos desde la app. Se apaga en Ajustes → Acerca de y ayuda → Acerca de → Estadísticas.
 */
class Stats(private val context: Context, private val enabled: () -> Boolean) {
    private val store by lazy { context.getSharedPreferences("stats", Context.MODE_PRIVATE) }

    /** Último intento fallido en este arranque (reloj desde el encendido); 0 = ninguno. */
    @Volatile private var failedAt = 0L

    /** Todo en IO: lee su archivo y la red. */
    suspend fun pingDaily() = withContext(Dispatchers.IO) {
        if (BuildConfig.DEBUG || !enabled()) return@withContext
        val today = Day.today().toString()
        if (store.getString(KEY_LAST, null) == today) return@withContext
        // Sin red no se reintenta en cada regreso al inicio (cada intento espera hasta 25 s): a lo más uno por hora.
        val now = SystemClock.elapsedRealtime()
        if (failedAt != 0L && now - failedAt < RETRY_MS) return@withContext
        val id = store.getString(KEY_ID, null) ?: Firestore.newId().also { store.edit { putString(KEY_ID, it) } }
        // «Desde» se guarda solo cuando la nube lo aceptó: sin pila de reloj la primera vez puede ser 2015 (y ese envío
        // falla), y se quedaba para siempre.
        val saved = store.getString(KEY_SINCE, null)
        val since = saved ?: today
        val sent = try {
            send(id, since)
        } catch (_: IOException) {
            false
        }
        if (sent) {
            failedAt = 0L
            store.edit {
                putString(KEY_LAST, today)
                if (saved == null) putString(KEY_SINCE, since)
            }
        } else {
            failedAt = now
        }
    }

    private fun send(id: String, since: String): Boolean {
        val config = context.resources.configuration
        val fields = JSONObject()
            .put("version", Firestore.int(BuildConfig.VERSION_CODE.toLong()))
            .put("channel", Firestore.text(BuildConfig.CHANNEL))
            .put("android", Firestore.int(Build.VERSION.SDK_INT.toLong()))
            // El elegido a mano o, con «Como el sistema», el del SISTEMA (no el que se ve: un sistema en alemán sin paquete
            // se ve en inglés, pero es la señal de qué idiomas faltan). Como en la 1.5.14.
            .put("language", Firestore.text(Language.read(context).ifBlank { primaryLocale(Resources.getSystem().configuration).language }.take(8)))
            .put("screen", Firestore.text(screen(config)))
            .put("since", Firestore.text(since))
            .put("radio", Firestore.text(FactoryAudio.radio(context).take(64)))
            .put("bluetooth", Firestore.text(FactoryAudio.bluetooth(context).take(64)))
            .put("ble", Firestore.bool(FactoryAudio.ble(context)))
        // Solo si hay receta guardada: sin calibrar, el campo no va.
        FactoryAudio.bluetoothRecipe(context)?.let { fields.put("btRecipe", Firestore.text(it.take(RECIPE_MAX))) }
        FactoryAudio.radioRecipe(context)?.let { fields.put("fmRecipe", Firestore.text(it.take(RECIPE_MAX))) }
        val write = JSONObject()
            .put("update", JSONObject().put("name", Firestore.document("screens/$id")).put("fields", fields))
            .put("updateTransforms", Firestore.serverTime("seen"))
        return Firestore.commit(context, write)
    }

    private companion object {
        const val KEY_ID = "id"
        const val KEY_SINCE = "since"
        const val KEY_LAST = "last_ping"
        /** Lo más largo de un id de receta (lo mismo dicen las reglas de Firestore). */
        const val RECIPE_MAX = 32
        const val RETRY_MS = 60 * 60_000L

        /** En dp y acostada (ancho × alto): así se distingue una pantalla de auto de un celular. */
        fun screen(config: Configuration): String {
            val a = config.screenWidthDp
            val b = config.screenHeightDp
            return "${maxOf(a, b)}x${minOf(a, b)}"
        }
    }
}
