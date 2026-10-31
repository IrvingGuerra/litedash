package com.irvingguerra.litedash.feature.auto

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Un auto del catálogo, antes de bajarlo. */
@Immutable
data class EntradaCatalogo(
    val id: String,
    val nombre: String,
    val marca: String,
    val anio: Int,
    val autor: String,
    val licencia: String,
    val fuente: String,
    val url: String,
    val bytes: Long,
    val sha256: String,
)

/** Un auto ya instalado: sus imágenes viven en [carpeta]. */
@Immutable
data class PaqueteAuto(
    val id: String,
    val nombre: String,
    val marca: String,
    val anio: Int,
    val autor: String,
    val licencia: String,
    val fuente: String,
    /** Brillo medio de la pintura gris del render (0–1): color final = imagen × color / gris. */
    val gris: Float,
    val vistas: List<String>,
    val giro: Int,
    val carpeta: File,
)

/**
 * Autos con imágenes (ADR 0008): el catálogo se lee de [urlCatalogo] y cada
 * paquete se baja, se revisa con SHA-256 y se descomprime en la memoria de la
 * app. Son solo imágenes, nunca código. Con [urlCatalogo] vacío (versión de
 * Play) no hay catálogo: solo el auto genérico y lo ya instalado.
 */
class RepositorioAutos(contexto: Context, val urlCatalogo: String) {
    private val raiz = File(contexto.filesDir, "autos")
    private val imagenes = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    val hayCatalogo get() = urlCatalogo.isNotBlank()

    /** Sube cada vez que se instala algo, para que la UI vuelva a leer. */
    var cambios by mutableStateOf(0)
        private set

    suspend fun catalogo(): List<EntradaCatalogo> = withContext(Dispatchers.IO) {
        val json = JSONObject(leerTexto(urlCatalogo))
        val autos = json.getJSONArray("autos")
        (0 until autos.length()).map { i ->
            val a = autos.getJSONObject(i)
            EntradaCatalogo(
                id = a.getString("id"),
                nombre = a.getString("nombre"),
                marca = a.getString("marca"),
                anio = a.optInt("anio"),
                autor = a.optString("autor"),
                licencia = a.optString("licencia"),
                fuente = a.optString("fuente"),
                url = a.getString("url"),
                bytes = a.optLong("bytes"),
                sha256 = a.getString("sha256"),
            )
        }
    }

    fun instalados(): List<PaqueteAuto> =
        raiz.listFiles()?.mapNotNull { leerPaquete(it) }?.sortedBy { it.nombre } ?: emptyList()

    fun paquete(id: String?): PaqueteAuto? = id?.let { leerPaquete(File(raiz, carpetaDe(it))) }

    /** Baja, verifica y descomprime. [progreso] va de 0 a 1. */
    suspend fun instalar(e: EntradaCatalogo, progreso: (Float) -> Unit) = withContext(Dispatchers.IO) {
        raiz.mkdirs()
        val temporal = File(raiz, ".bajando.zip")
        val sha = MessageDigest.getInstance("SHA-256")
        abrir(e.url).inputStream.use { entrada ->
            temporal.outputStream().use { salida ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = entrada.read(buf)
                    if (n < 0) break
                    salida.write(buf, 0, n)
                    sha.update(buf, 0, n)
                    total += n
                    if (e.bytes > 0) progreso((total.toFloat() / e.bytes).coerceAtMost(1f))
                }
            }
        }
        val calculado = sha.digest().joinToString("") { "%02x".format(it) }
        if (!calculado.equals(e.sha256, ignoreCase = true)) {
            temporal.delete()
            error("El archivo llegó dañado")
        }
        val destino = File(raiz, carpetaDe(e.id))
        val nueva = File(raiz, carpetaDe(e.id) + ".nueva")
        nueva.deleteRecursively()
        ZipInputStream(temporal.inputStream().buffered()).use { zip ->
            while (true) {
                val item = zip.nextEntry ?: break
                val archivo = File(nueva, item.name)
                // Nada fuera de la carpeta del auto (zip con rutas "../").
                if (!archivo.canonicalPath.startsWith(nueva.canonicalPath + File.separator)) continue
                if (item.isDirectory) archivo.mkdirs() else {
                    archivo.parentFile?.mkdirs()
                    archivo.outputStream().use { zip.copyTo(it) }
                }
            }
        }
        temporal.delete()
        destino.deleteRecursively()
        nueva.renameTo(destino)
        imagenes.evictAll()
        withContext(Dispatchers.Main) { cambios++ }
    }

    /** Imagen del paquete reducida al ancho que se pinta (ahorra memoria en pantallas chicas). */
    suspend fun imagen(p: PaqueteAuto, nombre: String, anchoPx: Int): ImageBitmap? {
        val clave = "${p.id}/$nombre@$anchoPx"
        imagenes.get(clave)?.let { return it }
        return withContext(Dispatchers.IO) {
            val archivo = File(p.carpeta, "$nombre.webp")
            if (!archivo.exists()) return@withContext null
            val medidas = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(archivo.path, medidas)
            var reduccion = 1
            while (medidas.outWidth / (reduccion * 2) >= anchoPx) reduccion *= 2
            BitmapFactory.decodeFile(archivo.path, BitmapFactory.Options().apply { inSampleSize = reduccion })
                ?.asImageBitmap()
                ?.also { imagenes.put(clave, it) }
        }
    }

    private fun leerPaquete(carpeta: File): PaqueteAuto? {
        val json = File(carpeta, "auto.json").takeIf { it.exists() } ?: return null
        return runCatching {
            val a = JSONObject(json.readText())
            val vistas = a.optJSONArray("vistas")
            PaqueteAuto(
                id = a.getString("id"),
                nombre = a.getString("nombre"),
                marca = a.optString("marca"),
                anio = a.optInt("anio"),
                autor = a.optString("autor"),
                licencia = a.optString("licencia"),
                fuente = a.optString("fuente"),
                gris = a.optDouble("gris", 0.45).toFloat(),
                vistas = (0 until (vistas?.length() ?: 0)).map { vistas!!.getString(it) },
                giro = a.optInt("giro"),
                carpeta = carpeta,
            )
        }.getOrNull()
    }

    private fun carpetaDe(id: String) = id.replace(Regex("[^a-z0-9-]"), "-")

    private fun abrir(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "LiteDash")
            if (responseCode !in 200..299) error("HTTP $responseCode")
        }

    private fun leerTexto(url: String) = abrir(url).inputStream.bufferedReader().use { it.readText() }
}
