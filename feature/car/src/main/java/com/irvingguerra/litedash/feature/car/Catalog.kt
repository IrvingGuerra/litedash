package com.irvingguerra.litedash.feature.car

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream
import kotlin.math.exp
import kotlin.math.roundToInt

/** Desde aquí los brillos de la pintura teñida se acercan a blanco sin cortarse. */
private const val KNEE = 0.78f

/** Brillo mínimo de la pintura (el selector libre permite negro puro). */
private const val PAINT_FLOOR = 0.07f

/** Un auto del catálogo, antes de bajarlo. */
@Immutable
data class CatalogEntry(
    val id: String,
    val name: String,
    val make: String,
    val year: Int,
    val author: String,
    val license: String,
    val source: String,
    val url: String,
    val bytes: Long,
    val sha256: String,
    /** Imagen chica (~15 KB) para ver el auto antes de bajarlo; vive en el catálogo, no en la app. */
    val thumbnail: String = "",
    /** Cuadros extra del giro, para que gire más fluido: [Smoothness.MEDIUM] y [Smoothness.MAX]. */
    val extras: List<SpinExtra> = emptyList(),
)

/** Un paquete de cuadros extra del giro; se baja encima del auto ya instalado. */
@Immutable
data class SpinExtra(val level: Int, val url: String, val bytes: Long, val sha256: String)

/**
 * Qué tan fluido gira el auto: cuántos cuadros de los 96 del giro completo
 * tiene. Cada nivel se baja sobre el anterior, sin volver a bajar lo que ya hay.
 */
object Smoothness {
    const val LIGHT = 0 // 24 cuadros, cada 15°: viene en el paquete
    const val MEDIUM = 1 // 48, cada 7.5°: los "mNN"
    const val MAX = 2 // 96, cada 3.75°: los "xNN"
}

/** Un auto ya instalado: sus imágenes viven en [folder]. */
@Immutable
data class CarPackage(
    val id: String,
    val name: String,
    val make: String,
    val year: Int,
    val author: String,
    val license: String,
    val source: String,
    /** Brillo medio de la pintura gris del render (0–1): color final = imagen × color / gris. */
    val gray: Float,
    val views: List<String>,
    val spin: Int,
    /** Hasta qué [Smoothness] tiene los cuadros del giro. */
    val smoothness: Int,
    /** Vistas con piso (sombra y reflejo): caja de esas capas en anchos del auto. */
    val floor: Map<String, FloorBox>,
    val folder: File,
    /** SHA-256 del paquete con que se instaló ("" si se bajó antes de guardarlo). */
    val sha256: String,
)

/** Dónde va la capa del piso respecto al auto, en anchos del auto. */
@Immutable
data class FloorBox(val x: Float, val y: Float, val width: Float, val height: Float)

/** Una imagen lista para dibujar: el auto pintado y, si trae piso, su sombra y su reflejo. */
class CarLayers(val car: ImageBitmap, val shadow: ImageBitmap?, val reflection: ImageBitmap?, val floor: FloorBox?)

/**
 * Autos con imágenes (ADR 0008): el catálogo se lee de [catalogUrl] y cada
 * paquete se baja, se revisa con SHA-256 y se descomprime en la memoria de la
 * app. Son solo imágenes, nunca código (por eso va igual en Play y en GitHub).
 * Con [catalogUrl] vacío no hay catálogo: solo el auto genérico y lo instalado.
 */
class CarRepository(context: Context, val catalogUrl: String) {
    private val root = File(context.filesDir, CarFiles.ROOT)
    // Caché de imágenes ya teñidas, con un octavo de la memoria de la app (de 24 a
    // 64 MB): caben los cuadros alrededor del que se ve; los lejanos se vuelven a pintar.
    private val images = object : LruCache<String, ImageBitmap>(
        (Runtime.getRuntime().maxMemory() / 8).coerceIn(24L shl 20, 64L shl 20).toInt(),
    ) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    // Ancho original de cada imagen (no cambia): decide cuánto se reduce al decodificarla.
    private val sourceWidths = ConcurrentHashMap<String, Int>()
    // Lo instalado se lee del disco una vez por cada cambio, no en cada recomposición.
    private var packages: List<CarPackage>? = null

    val hasCatalog get() = catalogUrl.isNotBlank()

    private val checks = context.getSharedPreferences(CarFiles.ROOT, Context.MODE_PRIVATE)

    /** El auto elegido tiene imágenes nuevas en el catálogo (lo llena [checkQuietly]). */
    var outdated by mutableStateOf<CatalogEntry?>(null)
        private set

    /** Sube cada vez que se instala algo, para que la UI vuelva a leer. */
    var changes by mutableStateOf(0)
        private set

    suspend fun catalog(): List<CatalogEntry> = withContext(Dispatchers.IO) {
        val json = JSONObject(readText(catalogUrl))
        val cars = json.getJSONArray(CarJson.CARS)
        (0 until cars.length()).map { i ->
            val a = cars.getJSONObject(i)
            CatalogEntry(
                id = a.getString("id"),
                name = a.getString(CarJson.NAME),
                make = a.getString(CarJson.MAKE),
                year = a.optInt(CarJson.YEAR),
                author = a.optString(CarJson.AUTHOR),
                license = a.optString(CarJson.LICENSE),
                source = a.optString(CarJson.SOURCE),
                url = a.getString("url"),
                bytes = a.optLong("bytes"),
                sha256 = a.getString("sha256"),
                thumbnail = a.optString(CarJson.THUMBNAIL),
                extras = a.optJSONArray(CarJson.EXTRAS)?.let { x ->
                    (0 until x.length()).map { j ->
                        val e = x.getJSONObject(j)
                        SpinExtra(e.getInt(CarJson.LEVEL), e.getString("url"), e.optLong("bytes"), e.getString("sha256"))
                    }
                }.orEmpty(),
            )
        }
    }

    /** Los autos bajados: solo sus carpetas (no la de miniaturas ni una a medio instalar, que llevan punto). */
    fun installed(): List<CarPackage> = packages
        ?: root.listFiles().orEmpty().filter { it.isDirectory && '.' !in it.name }
            .mapNotNull { readPackage(it) }.sortedBy { it.name }.also { packages = it }

    fun pkg(id: String?): CarPackage? = id?.let { wanted -> installed().firstOrNull { it.folder.name == folderOf(wanted) } }

    /** Baja, verifica y descomprime. [progress] va de 0 a 1. */
    suspend fun install(e: CatalogEntry, progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        root.mkdirs()
        val temp = fetch(e.url, e.bytes, e.sha256, progress)
        val target = File(root, folderOf(e.id))
        val staging = File(root, folderOf(e.id) + CarFiles.STAGING_SUFFIX)
        staging.deleteRecursively()
        unzip(temp, staging)
        File(staging, CarFiles.PACKAGE_HASH).writeText(e.sha256)
        temp.delete()
        target.deleteRecursively()
        staging.renameTo(target)
        changed()
    }

    /** El catálogo trae otras imágenes para [p] (mejores renders, piso nuevo…). */
    fun isOutdated(p: CarPackage, e: CatalogEntry) = p.id == e.id && p.sha256 != e.sha256

    /**
     * Baja las imágenes nuevas de [p] y vuelve a poner la fluidez que tenía (los
     * cuadros extra también cambian). [progress] va de 0 a 1 en todo el proceso.
     */
    suspend fun update(p: CarPackage, e: CatalogEntry, progress: (Float) -> Unit) {
        val level = p.smoothness
        val extra = e.extras.filter { it.level <= level }.sumOf { it.bytes }
        val total = (e.bytes + extra).coerceAtLeast(1).toFloat()
        install(e) { f -> progress(f * e.bytes / total) }
        val fresh = pkg(e.id) ?: return
        if (level > Smoothness.LIGHT) raiseSmoothness(fresh, e, level) { f -> progress((e.bytes + f * extra) / total) }
    }

    /**
     * Revisa el catálogo a lo más cada [CHECK_EVERY_MS] (junto con la revisión de la
     * app al abrir) y avisa en [outdated] si el auto [id] tiene imágenes nuevas.
     */
    suspend fun checkQuietly(id: String?) {
        val p = pkg(id) ?: return
        if (!hasCatalog) return
        val now = System.currentTimeMillis()
        if (now - checks.getLong(KEY_LAST_CHECK, 0L) < CHECK_EVERY_MS && p.sha256.isNotEmpty()) return
        val entry = runCatching { catalog() }.getOrNull()?.firstOrNull { it.id == p.id } ?: return
        checks.edit().putLong(KEY_LAST_CHECK, now).apply()
        outdated = entry.takeIf { isOutdated(p, it) }
    }

    /** Sube el giro de [p] hasta [level], bajando solo los cuadros que le faltan. */
    suspend fun raiseSmoothness(p: CarPackage, e: CatalogEntry, level: Int, progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val missing = e.extras.filter { it.level in (p.smoothness + 1)..level }.sortedBy { it.level }
        val total = missing.sumOf { it.bytes }.coerceAtLeast(1)
        var done = 0L
        for (x in missing) {
            val temp = fetch(x.url, x.bytes, x.sha256) { f -> progress(((done + f * x.bytes) / total).coerceAtMost(1f)) }
            // Encima de lo instalado: solo agrega cuadros, no toca los que ya están.
            unzip(temp, p.folder)
            temp.delete()
            done += x.bytes
        }
        changed()
    }

    /**
     * Miniatura de un auto del catálogo: se baja la primera vez que se ve la
     * lista y se guarda (unos KB por auto), así la app no carga con ninguna.
     */
    suspend fun thumbnail(e: CatalogEntry): ImageBitmap? {
        if (e.thumbnail.isBlank()) return null
        val key = "mini/${e.id}"
        images.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val file = File(File(root, CarFiles.THUMBNAILS).apply { mkdirs() }, folderOf(e.id) + ".webp")
                if (!file.exists()) {
                    val temp = File(file.path + CarFiles.PARTIAL_SUFFIX)
                    open(e.thumbnail).inputStream.use { input -> temp.outputStream().use { input.copyTo(it) } }
                    temp.renameTo(file)
                }
                BitmapFactory.decodeFile(file.path)?.asImageBitmap()?.also { images.put(key, it) }
            }.getOrNull()
        }
    }

    /** Baja el giro de [p] a [level] borrando los cuadros de más (libera espacio). */
    suspend fun lowerSmoothness(p: CarPackage, level: Int) = withContext(Dispatchers.IO) {
        val letters = buildList {
            if (level < Smoothness.MAX) add("x")
            if (level < Smoothness.MEDIUM) add("m")
        }
        File(p.folder, CarFiles.SPIN).listFiles()?.filter { f -> letters.any { f.name.startsWith(it) } }?.forEach { it.delete() }
        changed()
    }

    /** Quita un auto descargado (sus imágenes); se puede volver a bajar del catálogo. */
    suspend fun delete(p: CarPackage) = withContext(Dispatchers.IO) {
        p.folder.deleteRecursively()
        changed()
    }

    /** Lo que ocupa un auto descargado, en bytes. */
    suspend fun sizeOnDisk(p: CarPackage): Long = withContext(Dispatchers.IO) {
        p.folder.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /** Después de instalar, quitar o cambiar un auto: lo de memoria ya no sirve. */
    private suspend fun changed() {
        images.evictAll()
        sourceWidths.clear()
        withContext(Dispatchers.Main) {
            packages = null
            outdated = outdated?.takeIf { e -> pkg(e.id)?.let { isOutdated(it, e) } == true }
            changes++
        }
    }

    private fun fetch(url: String, bytes: Long, sha256: String, progress: (Float) -> Unit): File {
        val temp = File(root, CarFiles.DOWNLOAD)
        val sha = MessageDigest.getInstance("SHA-256")
        open(url).inputStream.use { input ->
            temp.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    sha.update(buf, 0, n)
                    total += n
                    if (bytes > 0) progress((total.toFloat() / bytes).coerceAtMost(1f))
                }
            }
        }
        val computed = sha.digest().joinToString("") { "%02x".format(it) }
        if (!computed.equals(sha256, ignoreCase = true)) {
            temp.delete()
            error("El archivo llegó dañado")
        }
        return temp
    }

    private fun unzip(zip: File, target: File) {
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val item = input.nextEntry ?: break
                val file = File(target, item.name)
                // Nada fuera de la carpeta del auto (zip con rutas "../").
                if (!file.canonicalPath.startsWith(target.canonicalPath + File.separator)) continue
                if (item.isDirectory) file.mkdirs() else {
                    file.parentFile?.mkdirs()
                    file.outputStream().use { input.copyTo(it) }
                }
            }
        }
    }

    /**
     * La imagen [name] lista para dibujar a [widthPx]: el auto pintado del [color] y,
     * si esa imagen trae piso, su sombra y su reflejo (a la mitad: son suaves). La
     * usan la vista y la precarga del giro, así cada cuadro llega con todas sus capas.
     */
    suspend fun layers(p: CarPackage, name: String, color: Color, widthPx: Int): CarLayers? {
        val car = painted(p, name, color, widthPx) ?: return null
        val floor = p.floor[name] ?: return CarLayers(car, null, null, null)
        // La sombra y el reflejo son suaves: a la mitad del ancho se ven igual y ocupan
        // la cuarta parte de memoria.
        val floorWidth = (widthPx * floor.width / 2).roundToInt()
        return CarLayers(
            car,
            image(p, name + CarViews.SHADOW_SUFFIX, floorWidth),
            painted(p, name + CarViews.REFLECTION_SUFFIX, color, floorWidth),
            floor,
        )
    }

    private suspend fun image(p: CarPackage, name: String, widthPx: Int): ImageBitmap? {
        val width = decodeWidth(p, name, widthPx) ?: return null
        return cached("${p.id}/$name@$width") { decode(p, name, width) }
    }

    /**
     * La imagen [name] ya pintada del [color] (con su máscara "-pintura"). Se
     * tiñe una vez aquí y queda en caché: los brillos que se pasarían de blanco se
     * suavizan en vez de cortarse, así un auto blanco conserva su volumen.
     */
    private suspend fun painted(p: CarPackage, name: String, color: Color, widthPx: Int): ImageBitmap? {
        val width = decodeWidth(p, name, widthPx) ?: return null
        return cached("${p.id}/$name#${color.toArgb()}@$width") {
            decode(p, name, width)?.also { base ->
                val mask = decode(p, name + CarViews.PAINT_MASK_SUFFIX, width)
                if (mask != null && mask.width == base.width && mask.height == base.height) tint(base, mask, color, p.gray)
            }
        }
    }

    /**
     * De la caché o se carga en IO. La carga se cancela con quien la pidió: al girar
     * rápido, los cuadros que ya pasaron no se siguen pintando ni le quitan tiempo al que se ve.
     */
    private suspend fun cached(key: String, load: () -> Bitmap?): ImageBitmap? {
        images.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            load()?.asImageBitmap()?.also { images.put(key, it) }
        }
    }

    /**
     * El ancho al que se decodifica: el que se dibuja (redondeado hacia arriba a 32 px,
     * así tamaños casi iguales comparten el mismo bitmap en la caché), nunca más que el
     * original. Menos píxeles = se tiñe más rápido y caben más cuadros. null si no existe.
     */
    private suspend fun decodeWidth(p: CarPackage, name: String, widthPx: Int): Int? {
        val source = sourceWidth(File(p.folder, "$name.webp")) ?: withContext(Dispatchers.IO) {
            sourceWidth(File(p.folder, "$name.webp"), read = true)
        } ?: return null
        return ((widthPx + 31) / 32 * 32).coerceIn(32, source)
    }

    /** Ancho original de la imagen; con [read] lo lee del archivo (solo en IO) si aún no se sabe. */
    private fun sourceWidth(file: File, read: Boolean = false): Int? {
        sourceWidths[file.path]?.let { return it }
        if (!read || !file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        return bounds.outWidth.also { sourceWidths[file.path] = it }
    }

    /** Decodifica ya escalada a [width] (Android la reduce al leerla, sin un bitmap grande de paso). Solo en IO. */
    private fun decode(p: CarPackage, name: String, width: Int): Bitmap? {
        val file = File(p.folder, "$name.webp")
        val source = sourceWidth(file, read = true) ?: return null
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inScaled = true
            inDensity = source
            inTargetDensity = width
            inMutable = true
        })
    }

    private fun readPackage(folder: File): CarPackage? {
        val json = File(folder, CarFiles.PACKAGE_INFO).takeIf { it.exists() } ?: return null
        return runCatching {
            val a = JSONObject(json.readText())
            val views = a.optJSONArray(CarJson.VIEWS)
            val floor = a.optJSONObject(CarJson.FLOOR)
            CarPackage(
                id = a.getString("id"),
                name = a.getString(CarJson.NAME),
                make = a.optString(CarJson.MAKE),
                year = a.optInt(CarJson.YEAR),
                author = a.optString(CarJson.AUTHOR),
                license = a.optString(CarJson.LICENSE),
                source = a.optString(CarJson.SOURCE),
                gray = a.optDouble(CarJson.GRAY, 0.45).toFloat(),
                views = (0 until (views?.length() ?: 0)).map { views!!.getString(it) },
                spin = a.optInt(CarJson.SPIN),
                smoothness = when {
                    File(folder, "${CarFiles.SPIN}/x00.webp").exists() -> Smoothness.MAX
                    File(folder, "${CarFiles.SPIN}/m00.webp").exists() -> Smoothness.MEDIUM
                    else -> Smoothness.LIGHT
                },
                floor = floor?.keys()?.asSequence()?.associateWith { v ->
                    val c = floor.getJSONArray(v)
                    FloorBox(c.getDouble(0).toFloat(), c.getDouble(1).toFloat(), c.getDouble(2).toFloat(), c.getDouble(3).toFloat())
                }.orEmpty(),
                folder = folder,
                sha256 = File(folder, CarFiles.PACKAGE_HASH).takeIf { it.exists() }?.readText()?.trim().orEmpty(),
            )
        }.getOrNull()
    }

    /** color = imagen × color / gris, con brillos suaves arriba de [KNEE]; solo donde hay pintura. */
    private fun tint(base: Bitmap, mask: Bitmap, color: Color, gray: Float) {
        val tables = floatArrayOf(color.red, color.green, color.blue).map { channel ->
            // Nunca negro puro: el auto perdería todo su volumen y quedaría como silueta.
            val base = PAINT_FLOOR + channel * (1 - PAINT_FLOOR)
            IntArray(256) { i ->
                val v = i / 255f * base / gray
                val r = if (v <= KNEE) v else KNEE + (1 - KNEE) * (1 - exp(-(v - KNEE) / (1 - KNEE)))
                (r * 255).roundToInt().coerceIn(0, 255)
            }
        }
        val w = base.width
        val h = base.height
        val px = IntArray(w * h)
        val mp = IntArray(w * h)
        base.getPixels(px, 0, w, 0, 0, w, h)
        mask.getPixels(mp, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val m = mp[i] ushr 24
            if (m == 0) continue
            val c = px[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val rr = r + (tables[0][r] - r) * m / 255
            val gg = g + (tables[1][g] - g) * m / 255
            val bb = b + (tables[2][b] - b) * m / 255
            px[i] = (c and 0xFF000000.toInt()) or (rr shl 16) or (gg shl 8) or bb
        }
        base.setPixels(px, 0, w, 0, 0, w, h)
    }

    private fun folderOf(id: String) = id.replace(Regex("[^a-z0-9-]"), "-")

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "LiteDash")
            if (responseCode !in 200..299) error("HTTP $responseCode")
        }

    private fun readText(url: String) = open(url).inputStream.bufferedReader().use { it.readText() }

    private companion object {
        const val CHECK_EVERY_MS = 12 * 60 * 60 * 1000L
        const val KEY_LAST_CHECK = "ultima_revision"
    }
}
