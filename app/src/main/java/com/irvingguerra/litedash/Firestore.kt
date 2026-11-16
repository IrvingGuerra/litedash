package com.irvingguerra.litedash

import android.content.Context
import com.irvingguerra.litedash.core.ui.Https
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.security.SecureRandom

/** Firestore por REST (documents:commit y lectura de un documento), sin SDK; lo comparten [Stats], los reportes y [ProAccess]. */
internal object Firestore {
    private const val PROJECT = "litedash"
    private const val DOCUMENTS = "projects/$PROJECT/databases/(default)/documents"
    private const val API = "https://firestore.googleapis.com/v1"
    private const val COMMIT_URL = "$API/$DOCUMENTS:commit"

    /** 32 hex al azar: el id de un documento. */
    fun newId() = hex(ByteArray(16).also { SecureRandom().nextBytes(it) })
    fun document(path: String) = "$DOCUMENTS/$path"
    fun int(value: Long) = JSONObject().put("integerValue", value.toString())
    fun text(value: String) = JSONObject().put("stringValue", value)
    fun bool(value: Boolean) = JSONObject().put("booleanValue", value)

    /** El campo [field] con la hora del servidor. */
    fun serverTime(field: String) = JSONArray().put(JSONObject().put("fieldPath", field).put("setToServerValue", "REQUEST_TIME"))

    /** Manda una escritura; bloquea, así que va en un hilo de IO. True si Firestore la aceptó. */
    fun commit(context: Context, write: JSONObject): Boolean {
        val body = JSONObject().put("writes", JSONArray().put(write)).toString().toByteArray()
        val connection = open(context, COMMIT_URL).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            connection.outputStream.use { it.write(body) }
            return connection.responseCode in 200..299
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Lee el documento [path]; bloquea, así que va en un hilo de IO. Null si no existe (404);
     * sin red o con cualquier otra respuesta lanza [IOException].
     */
    fun get(context: Context, path: String): JSONObject? {
        val connection = open(context, "$API/${document(path)}")
        try {
            return when (val code = connection.responseCode) {
                in 200..299 -> JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                404 -> null
                else -> throw IOException("HTTP $code")
            }
        } finally {
            connection.disconnect()
        }
    }

    /** La conexión con los tiempos de espera y el nombre que usan todas las peticiones. */
    private fun open(context: Context, url: String): HttpURLConnection = Https.open(context, url).apply {
        connectTimeout = 10_000
        readTimeout = 15_000
        setRequestProperty("User-Agent", "LiteDash")
    }
}

/** Bytes en hex con minúsculas (ids y huellas). */
internal fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
