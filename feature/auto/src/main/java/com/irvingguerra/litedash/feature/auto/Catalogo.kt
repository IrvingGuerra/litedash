package com.irvingguerra.litedash.feature.auto

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
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
import kotlin.math.exp
import kotlin.math.roundToInt

/** Desde aquí los brillos de la pintura teñida se acercan a blanco sin cortarse. */
private const val CODO = 0.78f

/** Brillo mínimo de la pintura (el selector libre permite negro puro). */
private const val PISO_PINTURA = 0.07f

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
    /** Imagen chica (~15 KB) para ver el auto antes de bajarlo; vive en el catálogo, no en la app. */
    val miniatura: String = "",
    /** Cuadros extra del giro, para que gire más fluido: [Fluidez.MEDIA] y [Fluidez.MAXIMA]. */
    val extras: List<ExtraGiro> = emptyList(),
)

/** Un paquete de cuadros extra del giro; se baja encima del auto ya instalado. */
@Immutable
data class ExtraGiro(val nivel: Int, val url: String, val bytes: Long, val sha256: String)

/**
 * Qué tan fluido gira el auto: cuántos cuadros de los 96 del giro completo
 * tiene. Cada nivel se baja sobre el anterior, sin volver a bajar lo que ya hay.
 */
object Fluidez {
    const val LIGERA = 0 // 24 cuadros, cada 15°: viene en el paquete
    const val MEDIA = 1 // 48, cada 7.5°: los "mNN"
    const val MAXIMA = 2 // 96, cada 3.75°: los "xNN"
}

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
    /** Hasta qué [Fluidez] tiene los cuadros del giro. */
    val fluidez: Int,
    /** Vistas con piso (sombra y reflejo): caja de esas capas en anchos del auto. */
    val piso: Map<String, CajaPiso>,
    val carpeta: File,
)

/** Dónde va la capa del piso respecto al auto, en anchos del auto. */
@Immutable
data class CajaPiso(val x: Float, val y: Float, val ancho: Float, val alto: Float)

/**
 * Autos con imágenes (ADR 0008): el catálogo se lee de [urlCatalogo] y cada
 * paquete se baja, se revisa con SHA-256 y se descomprime en la memoria de la
 * app. Son solo imágenes, nunca código (por eso va igual en Play y en GitHub).
 * Con [urlCatalogo] vacío no hay catálogo: solo el auto genérico y lo instalado.
 */
class RepositorioAutos(contexto: Context, val urlCatalogo: String) {
    private val raiz = File(contexto.filesDir, "autos")
    // Caché de imágenes ya teñidas: con un octavo de la memoria de la app (de 24 a
    // 64 MB) cabe el giro completo en pantallas normales y no se repinta al girar.
    private val imagenes = object : LruCache<String, ImageBitmap>(
        (Runtime.getRuntime().maxMemory() / 8).coerceIn(24L shl 20, 64L shl 20).toInt(),
    ) {
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
                miniatura = a.optString("miniatura"),
                extras = a.optJSONArray("extras")?.let { x ->
                    (0 until x.length()).map { j ->
                        val e = x.getJSONObject(j)
                        ExtraGiro(e.getInt("nivel"), e.getString("url"), e.optLong("bytes"), e.getString("sha256"))
                    }
                }.orEmpty(),
            )
        }
    }

    fun instalados(): List<PaqueteAuto> =
        raiz.listFiles()?.mapNotNull { leerPaquete(it) }?.sortedBy { it.nombre } ?: emptyList()

    fun paquete(id: String?): PaqueteAuto? = id?.let { leerPaquete(File(raiz, carpetaDe(it))) }

    /** Baja, verifica y descomprime. [progreso] va de 0 a 1. */
    suspend fun instalar(e: EntradaCatalogo, progreso: (Float) -> Unit) = withContext(Dispatchers.IO) {
        raiz.mkdirs()
        val temporal = bajar(e.url, e.bytes, e.sha256, progreso)
        val destino = File(raiz, carpetaDe(e.id))
        val nueva = File(raiz, carpetaDe(e.id) + ".nueva")
        nueva.deleteRecursively()
        descomprimir(temporal, nueva)
        temporal.delete()
        destino.deleteRecursively()
        nueva.renameTo(destino)
        imagenes.evictAll()
        withContext(Dispatchers.Main) { cambios++ }
    }

    /** Sube el giro de [p] hasta [nivel], bajando solo los cuadros que le faltan. */
    suspend fun subirFluidez(p: PaqueteAuto, e: EntradaCatalogo, nivel: Int, progreso: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val faltan = e.extras.filter { it.nivel in (p.fluidez + 1)..nivel }.sortedBy { it.nivel }
        val total = faltan.sumOf { it.bytes }.coerceAtLeast(1)
        var hecho = 0L
        for (x in faltan) {
            val temporal = bajar(x.url, x.bytes, x.sha256) { f -> progreso(((hecho + f * x.bytes) / total).coerceAtMost(1f)) }
            // Encima de lo instalado: solo agrega cuadros, no toca los que ya están.
            descomprimir(temporal, p.carpeta)
            temporal.delete()
            hecho += x.bytes
        }
        withContext(Dispatchers.Main) { cambios++ }
    }

    /**
     * Miniatura de un auto del catálogo: se baja la primera vez que se ve la
     * lista y se guarda (unos KB por auto), así la app no carga con ninguna.
     */
    suspend fun miniatura(e: EntradaCatalogo): ImageBitmap? {
        if (e.miniatura.isBlank()) return null
        val clave = "mini/${e.id}"
        imagenes.get(clave)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val archivo = File(File(raiz, ".miniaturas").apply { mkdirs() }, carpetaDe(e.id) + ".webp")
                if (!archivo.exists()) {
                    val temporal = File(archivo.path + ".parcial")
                    abrir(e.miniatura).inputStream.use { entrada -> temporal.outputStream().use { entrada.copyTo(it) } }
                    temporal.renameTo(archivo)
                }
                BitmapFactory.decodeFile(archivo.path)?.asImageBitmap()?.also { imagenes.put(clave, it) }
            }.getOrNull()
        }
    }

    /** Baja el giro de [p] a [nivel] borrando los cuadros de más (libera espacio). */
    suspend fun bajarFluidez(p: PaqueteAuto, nivel: Int) = withContext(Dispatchers.IO) {
        val letras = buildList {
            if (nivel < Fluidez.MAXIMA) add("x")
            if (nivel < Fluidez.MEDIA) add("m")
        }
        File(p.carpeta, "giro").listFiles()?.filter { f -> letras.any { f.name.startsWith(it) } }?.forEach { it.delete() }
        imagenes.evictAll()
        withContext(Dispatchers.Main) { cambios++ }
    }

    /** Quita un auto descargado (sus imágenes); se puede volver a bajar del catálogo. */
    suspend fun eliminar(p: PaqueteAuto) = withContext(Dispatchers.IO) {
        p.carpeta.deleteRecursively()
        imagenes.evictAll()
        withContext(Dispatchers.Main) { cambios++ }
    }

    /** Lo que ocupa un auto descargado, en bytes. */
    fun ocupa(p: PaqueteAuto): Long = p.carpeta.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun bajar(url: String, bytes: Long, sha256: String, progreso: (Float) -> Unit): File {
        val temporal = File(raiz, ".bajando.zip")
        val sha = MessageDigest.getInstance("SHA-256")
        abrir(url).inputStream.use { entrada ->
            temporal.outputStream().use { salida ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = entrada.read(buf)
                    if (n < 0) break
                    salida.write(buf, 0, n)
                    sha.update(buf, 0, n)
                    total += n
                    if (bytes > 0) progreso((total.toFloat() / bytes).coerceAtMost(1f))
                }
            }
        }
        val calculado = sha.digest().joinToString("") { "%02x".format(it) }
        if (!calculado.equals(sha256, ignoreCase = true)) {
            temporal.delete()
            error("El archivo llegó dañado")
        }
        return temporal
    }

    private fun descomprimir(zip: File, destino: File) {
        ZipInputStream(zip.inputStream().buffered()).use { entrada ->
            while (true) {
                val item = entrada.nextEntry ?: break
                val archivo = File(destino, item.name)
                // Nada fuera de la carpeta del auto (zip con rutas "../").
                if (!archivo.canonicalPath.startsWith(destino.canonicalPath + File.separator)) continue
                if (item.isDirectory) archivo.mkdirs() else {
                    archivo.parentFile?.mkdirs()
                    archivo.outputStream().use { entrada.copyTo(it) }
                }
            }
        }
    }

    /** Imagen del paquete reducida al ancho que se pinta (ahorra memoria en pantallas chicas). */
    suspend fun imagen(p: PaqueteAuto, nombre: String, anchoPx: Int): ImageBitmap? {
        val clave = "${p.id}/$nombre@$anchoPx"
        imagenes.get(clave)?.let { return it }
        return withContext(Dispatchers.IO) {
            decodificar(p, nombre, anchoPx)?.asImageBitmap()?.also { imagenes.put(clave, it) }
        }
    }

    /**
     * La imagen [nombre] ya pintada del [color] (con su máscara "-pintura"). Se
     * tiñe una vez aquí y queda en caché: los brillos que se pasarían de blanco se
     * suavizan en vez de cortarse, así un auto blanco conserva su volumen.
     */
    suspend fun pintada(p: PaqueteAuto, nombre: String, color: Color, anchoPx: Int): ImageBitmap? {
        val clave = "${p.id}/$nombre#${color.toArgb()}@$anchoPx"
        imagenes.get(clave)?.let { return it }
        return withContext(Dispatchers.IO) {
            val base = decodificar(p, nombre, anchoPx) ?: return@withContext null
            val mascara = decodificar(p, "$nombre-pintura", anchoPx)
            if (mascara != null && mascara.width == base.width && mascara.height == base.height) pintar(base, mascara, color, p.gris)
            base.asImageBitmap().also { imagenes.put(clave, it) }
        }
    }

    private fun decodificar(p: PaqueteAuto, nombre: String, anchoPx: Int): Bitmap? {
        val archivo = File(p.carpeta, "$nombre.webp")
        if (!archivo.exists()) return null
        val medidas = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(archivo.path, medidas)
        var reduccion = 1
        while (medidas.outWidth / (reduccion * 2) >= anchoPx) reduccion *= 2
        return BitmapFactory.decodeFile(archivo.path, BitmapFactory.Options().apply { inSampleSize = reduccion; inMutable = true })
    }

    private fun leerPaquete(carpeta: File): PaqueteAuto? {
        val json = File(carpeta, "auto.json").takeIf { it.exists() } ?: return null
        return runCatching {
            val a = JSONObject(json.readText())
            val vistas = a.optJSONArray("vistas")
            val piso = a.optJSONObject("piso")
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
                fluidez = when {
                    File(carpeta, "giro/x00.webp").exists() -> Fluidez.MAXIMA
                    File(carpeta, "giro/m00.webp").exists() -> Fluidez.MEDIA
                    else -> Fluidez.LIGERA
                },
                piso = piso?.keys()?.asSequence()?.associateWith { v ->
                    val c = piso.getJSONArray(v)
                    CajaPiso(c.getDouble(0).toFloat(), c.getDouble(1).toFloat(), c.getDouble(2).toFloat(), c.getDouble(3).toFloat())
                }.orEmpty(),
                carpeta = carpeta,
            )
        }.getOrNull()
    }

    /** color = imagen × color / gris, con brillos suaves arriba de [CODO]; solo donde hay pintura. */
    private fun pintar(base: Bitmap, mascara: Bitmap, color: Color, gris: Float) {
        val tablas = floatArrayOf(color.red, color.green, color.blue).map { canal ->
            // Nunca negro puro: el auto perdería todo su volumen y quedaría como silueta.
            val base = PISO_PINTURA + canal * (1 - PISO_PINTURA)
            IntArray(256) { i ->
                val v = i / 255f * base / gris
                val r = if (v <= CODO) v else CODO + (1 - CODO) * (1 - exp(-(v - CODO) / (1 - CODO)))
                (r * 255).roundToInt().coerceIn(0, 255)
            }
        }
        val w = base.width
        val h = base.height
        val px = IntArray(w * h)
        val mp = IntArray(w * h)
        base.getPixels(px, 0, w, 0, 0, w, h)
        mascara.getPixels(mp, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val m = mp[i] ushr 24
            if (m == 0) continue
            val c = px[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val rr = r + (tablas[0][r] - r) * m / 255
            val gg = g + (tablas[1][g] - g) * m / 255
            val bb = b + (tablas[2][b] - b) * m / 255
            px[i] = (c and 0xFF000000.toInt()) or (rr shl 16) or (gg shl 8) or bb
        }
        base.setPixels(px, 0, w, 0, 0, w, h)
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
