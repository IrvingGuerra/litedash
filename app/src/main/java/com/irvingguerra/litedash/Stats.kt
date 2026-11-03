package com.irvingguerra.litedash

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.core.content.edit
import com.irvingguerra.litedash.core.ui.Https
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * Cuántas pantallas usan LiteDash, sin cuentas ni SDK de Firebase: a lo más una vez al día,
 * al abrir el inicio, escribe `screens/{id}` en Firestore por REST (nombres de la nube en inglés). El id es un número al
 * azar de esta instalación; se manda solo la versión, el canal, Android, el idioma de LiteDash y el tamaño
 * de pantalla (nada de ubicación ni del usuario). Las reglas de Firestore solo dejan escribir
 * esos campos y nadie puede leerlos desde la app. Se apaga en Ajustes → Acerca de.
 */
class Stats(private val context: Context, private val enabled: () -> Boolean) {
    private val store by lazy { context.getSharedPreferences("stats", Context.MODE_PRIVATE) }

    suspend fun pingDaily() {
        if (BuildConfig.DEBUG || !enabled()) return
        val today = Day.today().toString()
        if (store.getString(KEY_LAST, null) == today) return
        val id = store.getString(KEY_ID, null) ?: newId().also { store.edit { putString(KEY_ID, it) } }
        val since = store.getString(KEY_SINCE, null) ?: today.also { store.edit { putString(KEY_SINCE, it) } }
        // Sin red o con error no pasa nada: se intenta la próxima vez que se abra el inicio.
        if (runCatching { send(id, since) }.getOrDefault(false)) store.edit { putString(KEY_LAST, today) }
    }

    private suspend fun send(id: String, since: String): Boolean = withContext(Dispatchers.IO) {
        val config = context.resources.configuration
        val fields = JSONObject()
            .put("version", int(BuildConfig.VERSION_CODE.toLong()))
            .put("channel", text(BuildConfig.CHANNEL))
            .put("android", int(Build.VERSION.SDK_INT.toLong()))
            .put("language", text(Language.read(context).ifBlank { primaryLocale(config).language }.take(8)))
            .put("screen", text(screen(config)))
            .put("since", text(since))
        val write = JSONObject()
            .put("update", JSONObject().put("name", "$DOCUMENTS/screens/$id").put("fields", fields))
            .put("updateTransforms", JSONArray().put(JSONObject().put("fieldPath", "seen").put("setToServerValue", "REQUEST_TIME")))
        val body = JSONObject().put("writes", JSONArray().put(write)).toString().toByteArray()
        val connection = Https.open(context, COMMIT_URL).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "LiteDash")
        }
        try {
            connection.outputStream.use { it.write(body) }
            connection.responseCode in 200..299
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val PROJECT = "litedash"
        const val DOCUMENTS = "projects/$PROJECT/databases/(default)/documents"
        const val COMMIT_URL = "https://firestore.googleapis.com/v1/projects/$PROJECT/databases/(default)/documents:commit"
        const val KEY_ID = "id"
        const val KEY_SINCE = "since"
        const val KEY_LAST = "last_ping"

        fun newId() = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        fun int(value: Long) = JSONObject().put("integerValue", value.toString())
        fun text(value: String) = JSONObject().put("stringValue", value)

        /** En dp y acostada (ancho × alto): así se distingue una pantalla de auto de un celular. */
        fun screen(config: Configuration): String {
            val a = config.screenWidthDp
            val b = config.screenHeightDp
            return "${maxOf(a, b)}x${minOf(a, b)}"
        }
    }
}
