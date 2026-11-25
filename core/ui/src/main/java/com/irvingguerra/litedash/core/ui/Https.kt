package com.irvingguerra.litedash.core.ui

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

/**
 * Pasó menos de [every] desde [since] (con [now] de [System.currentTimeMillis]). Un reloj que regresó (pantalla sin pila
 * que arrancó en 1970 o 2015) da una diferencia negativa: cuenta como vieja (toca revisar o volver a bajar), no como
 * reciente hasta que el reloj alcance la fecha guardada. La usan todas las cachés de lo que se baja.
 */
fun isRecent(since: Long, now: Long, every: Long): Boolean = now - since in 0 until every

/**
 * Conexiones HTTPS que también sirven en Android 6 y 7.0. Algunos servidores que usa la app (como
 * el clima, Open-Meteo) usan Let's Encrypt, cuya raíz (ISRG Root X1/X2) Android trae desde la 7.1.1;
 * antes, y sin network_security_config (es de Android 7), se confía en las raíces del sistema
 * MÁS esas dos, que vienen en `res/raw/isrg_roots.pem` (sacadas de las raíces de macOS; huellas
 * SHA-256 96:BC:EC…:08:C6 y 69:72:9B…:14:70). De la API 26 en adelante no cambia nada.
 *
 * Vive en core/ui porque es el único módulo que comparten app, feature/car y feature/updater
 * (por eso también aquí la descarga verificada que usan los autos y las actualizaciones).
 */
object Https {
    @Volatile private var legacy: SSLSocketFactory? = null

    fun open(context: Context, url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        // Las redirecciones (github.com → release-assets) siguen con la misma configuración.
        // Hasta la API 25 inclusive: Android 7.1.0 también es 25 y no trae ISRG.
        if (connection is HttpsURLConnection && Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1) {
            connection.sslSocketFactory = legacyFactory(context.applicationContext)
        }
        return connection
    }

    /**
     * GET de una descarga de LiteDash (catálogo, autos, actualizaciones); truena si no contesta 2xx.
     * Si el servidor falla un momento (5xx, como los "504 Gateway Time-out" de GitHub) o se corta la
     * red, se reintenta solo ([retrying]); un 4xx no (no va a cambiar). Llamar fuera del hilo principal.
     */
    fun get(context: Context, url: String, accept: String? = null): HttpURLConnection = retrying { connect(context, url, accept) }

    /**
     * ¿La hora de la pantalla es anterior a esta versión de la app? Pasa en pantallas sin pila que arrancan sin red (1970,
     * 2015…): todo HTTPS falla («certificado aún no válido») y no es culpa del internet. Barato: solo compara la hora.
     */
    fun clockLooksWrong(): Boolean = clockLooksWrong(System.currentTimeMillis())

    internal fun clockLooksWrong(now: Long): Boolean = now < CLOCK_FLOOR_MS

    /** 2026-11-01 00:00 UTC: anterior a esta versión de la app; ninguna pantalla con la hora bien puede estar antes. */
    private const val CLOCK_FLOOR_MS = 1_793_491_200_000L

    private fun connect(context: Context, url: String, accept: String?, from: Long = -1): HttpURLConnection =
        open(context, url).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "LiteDash")
            accept?.let { setRequestProperty("Accept", it) }
            if (from >= 0) {
                // Una descarga a archivo: los bytes tal cual (con gzip transparente las posiciones no cuadrarían al
                // reanudar) y, si ya hay un pedazo, solo lo que falta (GitHub Releases y jsDelivr aceptan rangos).
                setRequestProperty("Accept-Encoding", "identity")
                if (from > 0) setRequestProperty("Range", "bytes=$from-")
            }
            val code = responseCode
            if (code in 500..599) {
                disconnect()
                throw IOException("HTTP $code")
            }
            if (code !in 200..299) {
                disconnect()
                throw HttpStatusException(code)
            }
        }

    /** Hace [block] hasta [RETRY_WAIT_MS].size + 1 veces mientras falle la red o el servidor (IOException). */
    private fun <T> retrying(block: () -> T): T {
        for (wait in RETRY_WAIT_MS) {
            try {
                return block()
            } catch (e: HttpStatusException) {
                throw e
            } catch (e: IOException) {
                Thread.sleep(wait)
            }
        }
        return block()
    }

    /** Esperas entre intentos: tres intentos en total, en unos 7 segundos. */
    private val RETRY_WAIT_MS = longArrayOf(2_000, 5_000)

    /**
     * Baja [url] a [target] y devuelve su SHA-256 en hexadecimal (el que llama lo compara; si no coincide, borra
     * [target]). [progress] va de 0 a 1 cuando se sabe el tamaño ([bytes] > 0). Si se corta a medias, se pide solo lo
     * que falta (`Range`); si el servidor no lo acepta, desde el principio. Un intento que avanzó no gasta reintentos.
     * Con [resume], lo que ya haya en [target] (de una descarga anterior del MISMO archivo) se continúa; sin él se
     * empieza de cero. El disco lleno no se reintenta ([NoSpaceException]).
     */
    fun download(
        context: Context, url: String, target: File, bytes: Long, accept: String?, progress: (Float) -> Unit, resume: Boolean = false,
    ): String {
        if (!resume) target.delete()
        var failures = 0
        while (true) {
            val have = resumeFrom(if (target.isFile) target.length() else 0L, bytes)
            if (have == 0L) target.delete()
            // Ya estaba completo: solo falta su huella.
            if (bytes > 0 && have == bytes) return sha256(target)
            try {
                // Si continuó un pedazo, la huella se calcula leyendo el archivo completo.
                return downloadOnce(context, url, target, bytes, accept, have, progress) ?: sha256(target)
            } catch (e: HttpStatusException) {
                // 416: el pedazo guardado no sirve (el archivo cambió): de cero, sin gastar intento.
                if (e.code != 416 || have == 0L) throw e
                target.delete()
                continue
            } catch (e: IOException) {
                if (noSpace(e, target)) throw NoSpaceException(e)
                val advanced = target.isFile && target.length() > have
                if (advanced) failures = 0 else failures++
                if (failures > RETRY_WAIT_MS.size) throw e
                Thread.sleep(RETRY_WAIT_MS[(failures - 1).coerceAtLeast(0)])
            }
        }
    }

    /**
     * Un intento desde el byte [have]. Devuelve el SHA-256 si bajó todo desde 0, o null si continuó un pedazo.
     */
    private fun downloadOnce(
        context: Context, url: String, target: File, bytes: Long, accept: String?, have: Long, progress: (Float) -> Unit,
    ): String? {
        val connection = connect(context, url, accept, have)
        val append = have > 0 && appendsAt(connection.responseCode, connection.getHeaderField("Content-Range"), have)
        return connection.inputStream.use { input ->
            FileOutputStream(target, append).use { output ->
                var total = if (append) have else 0L
                val sha = sha256Of(input) { buf, n ->
                    output.write(buf, 0, n)
                    total += n
                    if (bytes > 0) progress((total.toFloat() / bytes).coerceAtMost(1f))
                }
                sha.takeUnless { append }
            }
        }
    }

    /** Desde qué byte seguir con [have] bytes guardados de un archivo de [bytes] (0 = de cero: no hay o sobra). */
    internal fun resumeFrom(have: Long, bytes: Long): Long = if (have <= 0 || (bytes > 0 && have > bytes)) 0L else have

    /** ¿El servidor contestó el pedazo que falta (206 desde [have])? Si no (200 = todo), se escribe desde el principio. */
    internal fun appendsAt(code: Int, contentRange: String?, have: Long): Boolean =
        code == 206 && contentRange?.trim()?.startsWith("bytes $have-") == true

    /** ¿Falló por disco lleno al escribir [target]? (ENOSPC llega como IOException; si queda muy poco libre, también.) */
    fun noSpace(e: IOException, target: File): Boolean =
        generateSequence<Throwable>(e) { it.cause }.any { it.message?.contains("ENOSPC") == true || it.message?.contains("No space") == true } ||
            (target.parentFile?.usableSpace ?: Long.MAX_VALUE) < 64 * 1024

    /** El SHA-256 de [file] en hexadecimal (el de un archivo que ya llegó por otro camino). Solo en IO. */
    fun sha256(file: File): String = file.inputStream().use { sha256Of(it) { _, _ -> } }

    /** Lee [input] completo en bloques, pasa cada uno a [each] y devuelve su SHA-256 en hexadecimal. */
    private inline fun sha256Of(input: InputStream, each: (ByteArray, Int) -> Unit): String {
        val sha = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            each(buf, n)
            sha.update(buf, 0, n)
        }
        return sha.digest().joinToString("") { "%02x".format(it) }
    }

    /** Un almacén de certificados raro (Android 6–7.1) es, para quien llama, una falla de red más. */
    private fun legacyFactory(context: Context): SSLSocketFactory = try {
        legacy ?: synchronized(this) { legacy ?: build(context).also { legacy = it } }
    } catch (e: GeneralSecurityException) {
        throw IOException("TLS: ${e.message}", e)
    }

    private fun build(context: Context): SSLSocketFactory {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        // Solo las raíces del sistema ("system:…"; las que instala el usuario, "user:…", no:
        // desde Android 7 las apps no confían en ellas)…
        val system = KeyStore.getInstance("AndroidCAStore").apply { load(null, null) }
        for (alias in system.aliases()) if (alias.startsWith("system:")) store.setCertificateEntry(alias, system.getCertificate(alias))
        // …más las de Let's Encrypt.
        context.resources.openRawResource(R.raw.isrg_roots).use { input ->
            CertificateFactory.getInstance("X.509").generateCertificates(input)
                .forEachIndexed { i, cert -> store.setCertificateEntry("isrg-$i", cert) }
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        return SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }.socketFactory
    }
}

/** El servidor contestó que no (4xx: no existe, no se puede): es una falla de red más para quien llama, pero no se reintenta. */
class HttpStatusException(val code: Int) : IOException("HTTP $code")

/** El disco se llenó: no se reintenta (volvería a llenarse); quien llama puede decir «falta espacio». */
class NoSpaceException(cause: IOException) : IOException("ENOSPC", cause)
