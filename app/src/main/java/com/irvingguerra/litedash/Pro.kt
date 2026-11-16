package com.irvingguerra.litedash

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest

/** Cómo terminó un intento de activar un código. */
enum class ProResult { ACTIVE, INVALID, TAKEN, OFFLINE }

/**
 * LiteDash Pro en prueba: se activa con un código que el dueño guarda en Firestore como
 * `pro_codes/{sha256}` (el código nunca sale de la pantalla, solo su huella). Un código se liga
 * a una sola pantalla (un id al azar propio, aparte del de estadísticas, que se puede apagar).
 * Se revisa a lo más cada 12 h; sin internet sigue hasta 30 días desde la última revisión.
 * Perezoso: si nunca se activó un código no hace red.
 */
class ProAccess(private val context: Context) {
    private val store by lazy { context.getSharedPreferences("pro", Context.MODE_PRIVATE) }
    private val lock = Mutex()

    /** Último intento de revisar por red en este arranque (reloj desde el encendido); 0 = ninguno. */
    private var lastAttempt = 0L

    /** Pro activo en esta pantalla: lo leen las funciones Pro. Empieza en false hasta [refresh]. */
    var isPro by mutableStateOf(false)
        private set

    /** Pro se quitó solo por pasar 30 días sin poder revisarlo (lo dice la tarjeta de Ajustes). */
    var expired by mutableStateOf(false)
        private set

    /** Al arrancar y al volver al inicio: lee lo guardado y, si toca, revisa el código en la nube. */
    suspend fun refresh() = lock.withLock {
        val now = SystemClock.elapsedRealtime()
        val canAttempt = lastAttempt == 0L || now - lastAttempt >= ProCheck.RECHECK_MS
        val state = withContext(Dispatchers.IO) { revalidate(canAttempt) }
        if (state.attempted) lastAttempt = now
        isPro = state.pro
        if (state.expired) expired = true
    }

    /** Activa [code] en esta pantalla (lo liga si está libre). */
    suspend fun activate(code: String): ProResult = lock.withLock {
        val result = withContext(Dispatchers.IO) { bind(code) }
        if (result == ProResult.ACTIVE) {
            isPro = true
            expired = false
        }
        result
    }

    /** "Quitar de esta pantalla": solo borra lo local; el código sigue ligado a esta pantalla en la nube. */
    suspend fun remove() = lock.withLock {
        withContext(Dispatchers.IO) { clear() }
        isPro = false
    }

    private class Refreshed(val pro: Boolean, val attempted: Boolean = false, val expired: Boolean = false)

    private fun revalidate(canAttempt: Boolean): Refreshed {
        val hash = store.getString(KEY_HASH, null) ?: return Refreshed(pro = false)
        val lastCheck = store.getLong(KEY_LAST_CHECK, 0L)
        val now = System.currentTimeMillis()
        val due = ProCheck.due(now, lastCheck)
        if (due && canAttempt) {
            // Sin red (o una respuesta rara) no cambia nada; solo cuenta el límite de 30 días.
            val answered = try {
                val doc = Firestore.get(context, "$COLLECTION/$hash")
                doc?.let { isActive(it) && boundTo(it) == screenId() } == true
            } catch (_: IOException) {
                null
            } catch (_: JSONException) {
                null
            }
            if (answered != null) {
                if (answered) store.edit { putLong(KEY_LAST_CHECK, now) } else clear()
                return Refreshed(pro = answered, attempted = true)
            }
        }
        if (ProCheck.expired(now, lastCheck)) {
            clear()
            return Refreshed(pro = false, attempted = due && canAttempt, expired = true)
        }
        return Refreshed(pro = true, attempted = due && canAttempt)
    }

    private fun bind(code: String): ProResult {
        val hash = ProCode.hash(code) ?: return ProResult.INVALID
        val me = screenId()
        return try {
            val doc = Firestore.get(context, "$COLLECTION/$hash")
            val result = when {
                doc == null || !isActive(doc) -> ProResult.INVALID
                boundTo(doc) == me -> ProResult.ACTIVE
                boundTo(doc) != null -> ProResult.TAKEN
                Firestore.commit(context, bindWrite(hash, me)) -> ProResult.ACTIVE
                // Otra pantalla lo ligó al mismo tiempo (o la nube no aceptó): se vuelve a leer.
                else -> when (Firestore.get(context, "$COLLECTION/$hash")?.let { boundTo(it) }) {
                    me -> ProResult.ACTIVE
                    null -> ProResult.OFFLINE
                    else -> ProResult.TAKEN
                }
            }
            if (result == ProResult.ACTIVE) store.edit {
                putString(KEY_HASH, hash)
                putLong(KEY_LAST_CHECK, System.currentTimeMillis())
            }
            result
        } catch (_: IOException) {
            ProResult.OFFLINE
        } catch (_: JSONException) {
            ProResult.OFFLINE
        }
    }

    /** Liga el código a [screen]: solo `screen` y `bound` (hora del servidor), y solo si el documento existe. */
    private fun bindWrite(hash: String, screen: String) = JSONObject()
        .put("update", JSONObject()
            .put("name", Firestore.document("$COLLECTION/$hash"))
            .put("fields", JSONObject().put("screen", Firestore.text(screen))))
        .put("updateMask", JSONObject().put("fieldPaths", JSONArray().put("screen")))
        .put("updateTransforms", Firestore.serverTime("bound"))
        .put("currentDocument", JSONObject().put("exists", true))

    private fun clear() = store.edit { remove(KEY_HASH); remove(KEY_LAST_CHECK) }

    /** El id de esta pantalla para Pro; se crea la primera vez. */
    private fun screenId(): String =
        store.getString(KEY_SCREEN, null) ?: Firestore.newId().also { store.edit { putString(KEY_SCREEN, it) } }

    private companion object {
        const val COLLECTION = "pro_codes"
        const val KEY_HASH = "code_hash"
        const val KEY_LAST_CHECK = "last_check"
        const val KEY_SCREEN = "screen_id"

        fun field(doc: JSONObject, name: String) = doc.optJSONObject("fields")?.optJSONObject(name)
        fun isActive(doc: JSONObject) = field(doc, "active")?.optBoolean("booleanValue") == true
        fun boundTo(doc: JSONObject) = field(doc, "screen")?.optString("stringValue")?.takeIf { it.isNotEmpty() }
    }
}

/** Cuándo se revisa Pro en la nube y cuándo vence sin red (horas de pared). JVM pura para probarla. */
internal object ProCheck {
    const val RECHECK_MS = 12 * 60 * 60 * 1000L
    const val OFFLINE_LIMIT_MS = 30 * 24 * 60 * 60 * 1000L

    /** Toca revisar: pasaron 12 h o el reloj se fue hacia atrás (pantallas sin pila de reloj). */
    fun due(now: Long, lastCheck: Long) = now - lastCheck >= RECHECK_MS || now < lastCheck

    /** Pasaron más de 30 días sin poder revisarlo. */
    fun expired(now: Long, lastCheck: Long) = now - lastCheck > OFFLINE_LIMIT_MS
}

/** El código como se escribe → su huella (el id del documento). JVM pura para probarla. */
internal object ProCode {
    /** Sin espacios ni guiones y en mayúsculas: "abcd-1234" = "ABCD 1234". */
    fun normalize(code: String) = code.filterNot { it.isWhitespace() || it == '-' }.uppercase()

    /** SHA-256 en hex (minúsculas) del código normalizado; null si queda vacío. */
    fun hash(code: String): String? {
        val normalized = normalize(code).ifEmpty { return null }
        return hex(MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray()))
    }
}
