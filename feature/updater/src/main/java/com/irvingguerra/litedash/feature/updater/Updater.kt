package com.irvingguerra.litedash.feature.updater

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Una versión publicada en GitHub Releases, más nueva que la instalada. */
@Immutable
data class Version(
    val name: String,
    val code: Long,
    val notes: String,
    val apk: String,
    val checksums: String?,
    val bytes: Long,
)

/**
 * Busca, baja e instala versiones nuevas desde GitHub Releases. Solo existe en la
 * variante `github` (Play prohíbe que una app se actualice por fuera de la tienda):
 * con [apiUrl] vacío está apagado. Revisa solo al abrir LiteDash (a lo más cada
 * [QUIET_EVERY_MS], sin polling) y cuando el usuario toca "Buscar actualizaciones".
 */
class Updater(
    private val context: Context,
    private val apiUrl: String,
    private val currentCode: Long,
) {
    val available get() = apiUrl.isNotBlank()

    private val store by lazy { context.getSharedPreferences("actualizaciones", Context.MODE_PRIVATE) }

    /** La versión nueva que se encontró (en silencio o a mano); null = nada que instalar. */
    var found by mutableStateOf<Version?>(null)
        private set

    /** La versión que el usuario dejó para después: no se vuelve a avisar en el inicio. */
    var dismissed by mutableStateOf<String?>(null)
        private set

    /** ¿Avisar en el inicio? Solo si hay versión nueva que no dejó para después. */
    val notice get() = found?.takeIf { it.name != dismissed }

    fun dismiss() {
        val v = found ?: return
        dismissed = v.name
        store.edit().putString(KEY_DISMISSED, v.name).apply()
    }

    /**
     * Una sola revisión al abrir la app, si pasaron [QUIET_EVERY_MS] desde la última.
     * Sin red o con error no pasa nada: se intenta la próxima vez.
     */
    suspend fun checkQuietly() {
        if (!available) return
        dismissed = store.getString(KEY_DISMISSED, null)
        if (System.currentTimeMillis() - store.getLong(KEY_LAST, 0L) < QUIET_EVERY_MS) {
            // Sin pedir a GitHub otra vez: lo encontrado antes, si sigue siendo más nuevo.
            found = store.getString(KEY_FOUND, null)?.let { runCatching { fromJson(it) }.getOrNull() }
                ?.takeIf { it.code > currentCode }
            return
        }
        runCatching { checkForUpdate() }
    }

    /**
     * La versión publicada más nueva que la instalada, o null si ya tienes la última.
     * Se guarda lo encontrado (también al buscar a mano): el aviso del inicio no
     * vuelve a mostrar una versión vieja.
     */
    suspend fun checkForUpdate(): Version? {
        val v = newest()
        found = v
        store.edit().putLong(KEY_LAST, System.currentTimeMillis()).putString(KEY_FOUND, v?.let(::toJson)).apply()
        return v
    }

    private suspend fun newest(): Version? = withContext(Dispatchers.IO) {
        val releases = JSONArray(open(apiUrl).inputStream.bufferedReader().use { it.readText() })
        (0 until releases.length()).mapNotNull { i ->
            val r = releases.getJSONObject(i)
            if (r.optBoolean("draft")) return@mapNotNull null
            val tag = r.getString("tag_name")
            val code = versionCode(tag) ?: return@mapNotNull null
            // Solo versiones normales: nunca se ofrecen versiones de prueba.
            if (r.optBoolean("prerelease") || isPrerelease(tag)) return@mapNotNull null
            val assetList = r.optJSONArray("assets") ?: JSONArray()
            val assets = (0 until assetList.length()).map { assetList.getJSONObject(it) }
            val apk = assets.firstOrNull { it.getString("name").endsWith(".apk") } ?: return@mapNotNull null
            Version(
                name = tag.removePrefix("v"),
                code = code,
                notes = r.optString("body").trim(),
                apk = apk.getString("browser_download_url"),
                checksums = assets.firstOrNull { it.getString("name") == "SHA256SUMS.txt" }?.getString("browser_download_url"),
                bytes = apk.optLong("size"),
            )
        }.filter { it.code > currentCode }.maxByOrNull { it.code }
    }

    /** Baja el APK a la caché y lo verifica contra SHA256SUMS.txt. [progress] va de 0 a 1. */
    suspend fun download(v: Version, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val target = File(context.cacheDir, "actualizacion.apk")
        val sha = MessageDigest.getInstance("SHA-256")
        open(v.apk).inputStream.use { input ->
            target.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    sha.update(buf, 0, n)
                    total += n
                    if (v.bytes > 0) progress((total.toFloat() / v.bytes).coerceAtMost(1f))
                }
            }
        }
        val computed = sha.digest().joinToString("") { "%02x".format(it) }
        v.checksums?.let { url ->
            val expected = open(url).inputStream.bufferedReader().readLines()
                .firstOrNull { it.trim().endsWith(".apk") }?.trim()?.split(Regex("\\s+"))?.firstOrNull()
            if (expected != null && !expected.equals(computed, ignoreCase = true)) {
                target.delete()
                error("El archivo llegó dañado")
            }
        }
        target
    }

    /** ¿El usuario ya permitió que LiteDash instale apps? (Android 8+ lo pide una vez). */
    fun canInstall() = context.packageManager.canRequestPackageInstalls()

    fun requestInstallPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /**
     * Le pasa el APK al instalador de Android, que pide confirmación. Android
     * rechaza solo cualquier APK que no esté firmado con la misma llave.
     */
    suspend fun install(apk: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("litedash.apk", 0, apk.length()).use { output ->
                apk.inputStream().use { it.copyTo(output) }
                session.fsync(output)
            }
            val intent = Intent(context, InstallReceiver::class.java).setPackage(context.packageName)
            val flag = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or flag)
            session.commit(pending.intentSender)
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "LiteDash")
            setRequestProperty("Accept", "application/vnd.github+json")
            if (responseCode !in 200..299) error("HTTP $responseCode")
        }

    private fun toJson(v: Version) = JSONObject()
        .put("name", v.name).put("code", v.code).put("notes", v.notes).put("apk", v.apk)
        .put("checksums", v.checksums).put("bytes", v.bytes).toString()

    private fun fromJson(text: String) = JSONObject(text).run {
        Version(getString("name"), getLong("code"), optString("notes"), getString("apk"), optString("checksums").ifBlank { null }, optLong("bytes"))
    }

    companion object {
        /** Medio día entre revisiones en silencio: basta para enterarse el mismo día. */
        const val QUIET_EVERY_MS = 12 * 60 * 60 * 1000L
        private const val KEY_LAST = "ultima_revision"
        private const val KEY_FOUND = "encontrada"
        private const val KEY_DISMISSED = "omitida"

        /** Lo último que dijo el instalador de Android (para mostrarlo si falló). */
        var installError by mutableStateOf<String?>(null)
    }
}

/** Respuestas del instalador de Android: abre su confirmación o guarda el error. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { context.startActivity(it) } }
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.installError = null
            else -> Updater.installError = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "?"
        }
    }
}
