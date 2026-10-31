package com.irvingguerra.litedash.feature.actualizar

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
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Una versión publicada en GitHub Releases, más nueva que la instalada. */
@Immutable
data class Version(
    val nombre: String,
    val codigo: Long,
    val prueba: Boolean,
    val notas: String,
    val apk: String,
    val sumas: String?,
    val bytes: Long,
    val pagina: String,
)

/**
 * Busca, baja e instala versiones nuevas desde GitHub Releases. Solo existe en la
 * variante `github` (Play prohíbe que una app se actualice por fuera de la tienda):
 * con [urlApi] vacío está apagado. No revisa solo: únicamente cuando el usuario toca
 * "Buscar actualizaciones".
 */
class Actualizador(
    private val contexto: Context,
    private val urlApi: String,
    private val codigoActual: Long,
) {
    val disponible get() = urlApi.isNotBlank()

    /** La versión publicada más nueva que la instalada, o null si ya tienes la última. */
    suspend fun buscar(): Version? = withContext(Dispatchers.IO) {
        val lista = JSONArray(abrir(urlApi).inputStream.bufferedReader().use { it.readText() })
        (0 until lista.length()).mapNotNull { i ->
            val r = lista.getJSONObject(i)
            if (r.optBoolean("draft")) return@mapNotNull null
            val etiqueta = r.getString("tag_name")
            val codigo = codigoDeVersion(etiqueta) ?: return@mapNotNull null
            // Solo versiones normales: nunca se ofrecen versiones de prueba.
            val prueba = r.optBoolean("prerelease") || esPrueba(etiqueta)
            if (prueba) return@mapNotNull null
            val archivos = r.optJSONArray("assets") ?: JSONArray()
            val activos = (0 until archivos.length()).map { archivos.getJSONObject(it) }
            val apk = activos.firstOrNull { it.getString("name").endsWith(".apk") } ?: return@mapNotNull null
            Version(
                nombre = etiqueta.removePrefix("v"),
                codigo = codigo,
                prueba = prueba,
                notas = r.optString("body").trim(),
                apk = apk.getString("browser_download_url"),
                sumas = activos.firstOrNull { it.getString("name") == "SHA256SUMS.txt" }?.getString("browser_download_url"),
                bytes = apk.optLong("size"),
                pagina = r.optString("html_url"),
            )
        }.filter { it.codigo > codigoActual }.maxByOrNull { it.codigo }
    }

    /** Baja el APK a la caché y lo verifica contra SHA256SUMS.txt. [progreso] va de 0 a 1. */
    suspend fun descargar(v: Version, progreso: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val destino = File(contexto.cacheDir, "actualizacion.apk")
        val sha = MessageDigest.getInstance("SHA-256")
        abrir(v.apk).inputStream.use { entrada ->
            destino.outputStream().use { salida ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = entrada.read(buf)
                    if (n < 0) break
                    salida.write(buf, 0, n)
                    sha.update(buf, 0, n)
                    total += n
                    if (v.bytes > 0) progreso((total.toFloat() / v.bytes).coerceAtMost(1f))
                }
            }
        }
        val calculado = sha.digest().joinToString("") { "%02x".format(it) }
        v.sumas?.let { url ->
            val esperado = abrir(url).inputStream.bufferedReader().readLines()
                .firstOrNull { it.trim().endsWith(".apk") }?.trim()?.split(Regex("\\s+"))?.firstOrNull()
            if (esperado != null && !esperado.equals(calculado, ignoreCase = true)) {
                destino.delete()
                error("El archivo llegó dañado")
            }
        }
        destino
    }

    /** ¿El usuario ya permitió que LiteDash instale apps? (Android 8+ lo pide una vez). */
    fun puedeInstalar() = contexto.packageManager.canRequestPackageInstalls()

    fun pedirPermisoInstalar() {
        val intento = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${contexto.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { contexto.startActivity(intento) }
    }

    /**
     * Le pasa el APK al instalador de Android, que pide confirmación. Android
     * rechaza solo cualquier APK que no esté firmado con la misma llave.
     */
    suspend fun instalar(apk: File) = withContext(Dispatchers.IO) {
        val instalador = contexto.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(contexto.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = instalador.createSession(params)
        instalador.openSession(id).use { sesion ->
            sesion.openWrite("litedash.apk", 0, apk.length()).use { salida ->
                apk.inputStream().use { it.copyTo(salida) }
                sesion.fsync(salida)
            }
            val intento = Intent(contexto, ReceptorInstalacion::class.java).setPackage(contexto.packageName)
            val bandera = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pendiente = PendingIntent.getBroadcast(contexto, id, intento, PendingIntent.FLAG_UPDATE_CURRENT or bandera)
            sesion.commit(pendiente.intentSender)
        }
    }

    private fun abrir(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "LiteDash")
            setRequestProperty("Accept", "application/vnd.github+json")
            if (responseCode !in 200..299) error("HTTP $responseCode")
        }

    companion object {
        /** Lo último que dijo el instalador de Android (para mostrarlo si falló). */
        var errorInstalacion by mutableStateOf<String?>(null)
    }
}

/** Respuestas del instalador de Android: abre su confirmación o guarda el error. */
class ReceptorInstalacion : BroadcastReceiver() {
    override fun onReceive(contexto: Context, intento: Intent) {
        when (intento.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmar = if (Build.VERSION.SDK_INT >= 33) {
                    intento.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intento.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirmar?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { contexto.startActivity(it) } }
            }
            PackageInstaller.STATUS_SUCCESS -> Actualizador.errorInstalacion = null
            else -> Actualizador.errorInstalacion = intento.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "?"
        }
    }
}
