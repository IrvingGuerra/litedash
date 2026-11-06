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
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import com.irvingguerra.litedash.core.ui.Https

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
 * con [apiUrl] vacío está apagado. Revisa solo al volver a LiteDash (a lo más cada
 * [QUIET_EVERY_MS], sin polling) y cuando el usuario toca "Buscar actualizaciones".
 * La descarga corre en su propio scope: sigue aunque se cierre Ajustes ([progress]).
 */
class Updater(
    private val context: Context,
    private val apiUrl: String,
    private val currentCode: Long,
) {
    val available get() = apiUrl.isNotBlank()

    private val store by lazy { context.getSharedPreferences("updates", Context.MODE_PRIVATE) }

    // Vive lo que la app: la descarga no se corta al cerrar Ajustes.
    private val scope = MainScope()

    /** Descarga o instalación en curso (o la última que falló); null = nada. */
    sealed interface Progress {
        data class Downloading(val v: Version, val percent: Int) : Progress
        data class Installing(val v: Version, val apk: File) : Progress
        data object Failed : Progress
    }

    var progress by mutableStateOf<Progress?>(null)
        private set

    /** Baja [v], la verifica y se la pasa al instalador de Android; una a la vez. */
    fun downloadAndInstall(v: Version) {
        if (progress is Progress.Downloading) return
        progress = Progress.Downloading(v, 0)
        scope.launch {
            runCatching {
                val apk = download(v) { f ->
                    // Solo cuando cambia el porcentaje (no en cada bloque de 64 KB).
                    val percent = (f * 100).toInt()
                    if ((progress as? Progress.Downloading)?.percent != percent) progress = Progress.Downloading(v, percent)
                }
                progress = Progress.Installing(v, apk)
                install(apk)
            }.onFailure { progress = Progress.Failed }
        }
    }

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
     * Revisión al volver a LiteDash, si pasaron [QUIET_EVERY_MS] desde la última.
     * Sin red o con error no pasa nada: se intenta la próxima vez.
     */
    suspend fun checkQuietly() {
        if (!available) return
        // El APK de una instalación anterior ya no sirve (si se instaló, esta es la nueva): fuera de la caché.
        if (progress == null) withContext(Dispatchers.IO) {
            for (dir in listOfNotNull(context.cacheDir, context.externalCacheDir)) File(dir, APK_FILE).delete()
        }
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
        val target = File(context.cacheDir, APK_FILE)
        val computed = Https.download(context, v.apk, target, v.bytes, GITHUB_JSON, progress)
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

    /**
     * ¿El usuario ya permitió que LiteDash instale apps? Android 8+ lo pide por app; en
     * Android 6 y 7 es un solo interruptor para todo el equipo ("Orígenes desconocidos").
     */
    @Suppress("DEPRECATION")
    fun canInstall() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.packageManager.canRequestPackageInstalls()
        else Settings.Secure.getInt(context.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS, 0) == 1

    fun requestInstallPermission() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        else Intent(Settings.ACTION_SECURITY_SETTINGS)
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /**
     * Le pasa el APK al instalador de Android, que pide confirmación. Android
     * rechaza solo cualquier APK que no esté firmado con la misma llave.
     */
    suspend fun install(apk: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("litedash.apk", 0, apk.length()).use { output ->
                apk.inputStream().use { it.copyTo(output) }
                session.fsync(output)
            }
            val intent = Intent(context, InstallReceiver::class.java).setPackage(context.packageName)
            val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or flag)
            session.commit(pending.intentSender)
        }
    }

    private fun open(url: String) = Https.get(context, url, GITHUB_JSON)

    private fun toJson(v: Version) = JSONObject()
        .put("name", v.name).put("code", v.code).put("notes", v.notes).put("apk", v.apk)
        .put("checksums", v.checksums).put("bytes", v.bytes).toString()

    private fun fromJson(text: String) = JSONObject(text).run {
        Version(getString("name"), getLong("code"), optString("notes"), getString("apk"), optString("checksums").ifBlank { null }, optLong("bytes"))
    }

    companion object {
        /** Medio día entre revisiones en silencio: basta para enterarse el mismo día. */
        const val QUIET_EVERY_MS = 12 * 60 * 60 * 1000L
        private const val KEY_LAST = "last_check"
        private const val KEY_FOUND = "found"
        private const val KEY_DISMISSED = "dismissed"
        private const val APK_FILE = "update.apk"
        // Encabezado que pide la API de GitHub (también va en las descargas, que siguen de ahí).
        private const val GITHUB_JSON = "application/vnd.github+json"

        /** Lo último que dijo el instalador de Android (para mostrarlo si falló). */
        var installError by mutableStateOf<String?>(null)

        /**
         * La confirmación de Android ("¿Instalar?"), para que la abra la pantalla que
         * está al frente: abierta desde aquí (en segundo plano) algunas pantallas la
         * bloquean sin decir nada y se quedaba en "Abriendo instalador".
         */
        var confirm by mutableStateOf<Intent?>(null)
    }

    /**
     * Respaldo: abre el APK descargado con el instalador de siempre (el de "abrir
     * archivo"), por si el instalador por sesiones no responde en esta pantalla.
     */
    fun openWithInstaller(apk: File) {
        // El instalador de Android 6 solo abre archivos (content:// llegó en Android 7): se le pasa
        // una copia en la carpeta externa de la app, que puede leer sin permisos.
        val uri = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            val copy = File(context.externalCacheDir ?: return, apk.name)
            runCatching { apk.copyTo(copy, overwrite = true) }.getOrElse { return }
            Uri.fromFile(copy)
        } else {
            FileProvider.getUriForFile(context, context.packageName + FILE_AUTHORITY_SUFFIX, apk)
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_TYPE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(intent) }
    }
}

/** Debe coincidir con el `provider` del manifiesto de la versión de GitHub. */
private const val FILE_AUTHORITY_SUFFIX = ".updates"
private const val APK_TYPE = "application/vnd.android.package-archive"

/** Respuestas del instalador de Android: deja su confirmación lista para la pantalla o guarda el error. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                Updater.confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
            PackageInstaller.STATUS_SUCCESS -> Updater.installError = null
            else -> Updater.installError = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "?"
        }
    }
}
