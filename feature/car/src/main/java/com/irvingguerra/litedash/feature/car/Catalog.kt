package com.irvingguerra.litedash.feature.car

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
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
    val url: String,
    val bytes: Long,
    val sha256: String,
    /** Imagen chica (~15 KB) para ver el auto antes de bajarlo; vive en el catálogo, no en la app. */
    val thumbnail: String = "",
    /** Cuadros de en medio del giro (calidad alta); se bajan encima del paquete. */
    val high: SpinExtra? = null,
)

/** Un paquete de cuadros extra del giro; se baja encima del auto ya instalado. */
@Immutable
data class SpinExtra(val url: String, val bytes: Long, val sha256: String)

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
    /** Cuadros del giro en el paquete (cada uno con su "-pintura"). */
    val spin: Int,
    /** Calidad alta del giro: tiene también los cuadros de en medio ("mNN"). */
    val high: Boolean,
    /** Imágenes con piso (sombra y reflejo): caja de esas capas en anchos del auto. */
    val floor: Map<String, FloorBox>,
    val folder: File,
    /** SHA-256 del paquete con que se instaló ("" si se bajó antes de guardarlo). */
    val sha256: String,
) {
    val hasFloor get() = floor.isNotEmpty()
}

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
    private val checks = context.getSharedPreferences(CarFiles.ROOT, Context.MODE_PRIVATE)
    // Lo que se baja o se arregla vive aquí y no en la pantalla que lo pidió: si se cierra
    // Ajustes a media descarga, termina igual.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // Caché de imágenes ya teñidas: la cuarta parte de la memoria de la app (de 32 a
    // 160 MB), para que quepa el giro completo en calidad alta a tamaño real en
    // pantallas grandes. Si no cupiera, al girar en círculo siempre se borraría justo
    // el cuadro que sigue y el giro se iría trabando.
    private val images = object : LruCache<String, ImageBitmap>(
        (Runtime.getRuntime().maxMemory() / 4).coerceIn(32L shl 20, 160L shl 20).toInt(),
    ) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    // Imágenes que ya se están cargando: si la vista y la precarga piden la misma, se
    // decodifica una vez. La carga no se interrumpe aunque quien la pidió ya no la quiera.
    private val loading = ConcurrentHashMap<String, Deferred<ImageBitmap?>>()
    // Ancho original de cada imagen (no cambia): decide cuánto se reduce al decodificarla.
    private val sourceWidths = ConcurrentHashMap<String, Int>()

    val hasCatalog get() = catalogUrl.isNotBlank()

    /** Los autos bajados (se leen del disco al empezar y tras cada cambio, en IO). */
    var installed by mutableStateOf(scan())
        private set

    /** El auto elegido tiene un modelo 3D nuevo en el catálogo (lo llena [checkQuietly]). */
    var outdated by mutableStateOf<CatalogEntry?>(null)
        private set

    /** Lo que se está bajando ([ALL], el id de un auto o [HIGH]) y cuánto va (0–1); null = nada. */
    var downloading by mutableStateOf<Pair<String, Float>?>(null)
        private set

    /** La última descarga falló (sin internet, archivo dañado…). */
    var failed by mutableStateOf(false)
        private set

    fun pkg(id: String?): CarPackage? {
        val folder = folderOf(id ?: return null)
        return installed.firstOrNull { it.folder.name == folder }
    }

    suspend fun catalog(): List<CatalogEntry> = withContext(Dispatchers.IO) {
        val cars = JSONObject(readText(catalogUrl)).getJSONArray(CarJson.CARS)
        (0 until cars.length()).map { i ->
            val a = cars.getJSONObject(i)
            val extras = a.optJSONArray(CarJson.EXTRAS)
            CatalogEntry(
                id = a.getString("id"),
                name = a.getString(CarJson.NAME),
                make = a.getString(CarJson.MAKE),
                year = a.optInt(CarJson.YEAR),
                author = a.optString(CarJson.AUTHOR),
                license = a.optString(CarJson.LICENSE),
                url = a.getString("url"),
                bytes = a.optLong("bytes"),
                sha256 = a.getString("sha256"),
                thumbnail = a.optString(CarJson.THUMBNAIL),
                high = (0 until (extras?.length() ?: 0)).map { extras!!.getJSONObject(it) }
                    .firstOrNull { it.optInt(CarJson.LEVEL) == HIGH_LEVEL }
                    ?.let { SpinExtra(it.getString("url"), it.optLong("bytes"), it.getString("sha256")) },
            )
        }
    }

    /** El catálogo trae otro modelo 3D para [p] (mejores renders, piso nuevo…). */
    fun isOutdated(p: CarPackage, e: CatalogEntry) = p.id == e.id && p.sha256 != e.sha256

    /** Lo que se baja al actualizar [p]: el paquete y, si la tenía, la calidad alta. */
    fun updateSize(p: CarPackage, e: CatalogEntry) = e.bytes + (if (p.high) e.high?.bytes ?: 0 else 0)

    /** Baja e instala [e]; al terminar, [then] (por ejemplo, elegirlo). */
    fun install(e: CatalogEntry, then: () -> Unit = {}) = download(e.id) { progress ->
        installPackage(e, progress)
        then()
    }

    /** Baja el modelo 3D nuevo de [p] y vuelve a poner su calidad. */
    fun update(p: CarPackage, e: CatalogEntry) = download(p.id) { progress -> updatePackage(p, e, progress) }

    /** Actualiza todos los de [list] (auto instalado y su entrada nueva), uno tras otro. */
    fun updateAll(list: List<Pair<CarPackage, CatalogEntry>>) = download(ALL) { progress ->
        list.forEachIndexed { i, (p, e) -> updatePackage(p, e) { f -> progress((i + f) / list.size) } }
    }

    /** Baja los cuadros de en medio de [p] (calidad alta). */
    fun raiseQuality(p: CarPackage, e: CatalogEntry) {
        val extra = e.high ?: return
        download(HIGH) { progress -> addHigh(p, extra, progress) }
    }

    /** Calidad baja: borra los cuadros de en medio (libera espacio; no necesita internet). */
    fun lowerQuality(p: CarPackage) = scope.launch {
        withContext(Dispatchers.IO) { spinFiles(p.folder) { it.startsWith(HIGH_PREFIX) }.forEach { it.delete() } }
        changed()
    }

    /** Quita un auto descargado (sus imágenes); se puede volver a bajar del catálogo. */
    fun delete(p: CarPackage) = scope.launch {
        withContext(Dispatchers.IO) { p.folder.deleteRecursively() }
        changed()
    }

    /** Quita todos los autos descargados (sus imágenes); se pueden volver a bajar del catálogo. */
    fun deleteAll() = scope.launch {
        val all = installed
        withContext(Dispatchers.IO) { all.forEach { it.folder.deleteRecursively() } }
        changed()
    }

    /**
     * Revisa el catálogo al abrir, a lo más cada [CHECK_EVERY_MS] por auto (es un
     * archivo de unos KB), y avisa en [outdated] si el auto [id] tiene un modelo 3D nuevo.
     */
    suspend fun checkQuietly(id: String?) {
        val p = pkg(id) ?: return
        if (!hasCatalog) return
        val key = KEY_LAST_CHECK + p.id
        val now = System.currentTimeMillis()
        if (now - checks.getLong(key, 0L) < CHECK_EVERY_MS && p.sha256.isNotEmpty()) return
        val entry = runCatching { catalog() }.getOrNull()?.firstOrNull { it.id == p.id } ?: return
        checks.edit().putLong(key, now).apply()
        outdated = entry.takeIf { isOutdated(p, it) }
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

    /** Lo que ocupa un auto descargado, en bytes. */
    suspend fun sizeOnDisk(p: CarPackage): Long = withContext(Dispatchers.IO) {
        p.folder.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /** Una descarga a la vez, en el ámbito del repositorio, con su avance en [downloading]. */
    private fun download(what: String, work: suspend ((Float) -> Unit) -> Unit) {
        if (downloading != null) return
        downloading = what to 0f
        failed = false
        scope.launch {
            runCatching { work { f -> downloading = what to f } }.onFailure { failed = true }
            downloading = null
        }
    }

    private suspend fun installPackage(e: CatalogEntry, progress: (Float) -> Unit) {
        withContext(Dispatchers.IO) {
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
        }
        changed()
    }

    private suspend fun updatePackage(p: CarPackage, e: CatalogEntry, progress: (Float) -> Unit) {
        val extra = e.high?.takeIf { p.high }
        val total = (e.bytes + (extra?.bytes ?: 0)).coerceAtLeast(1).toFloat()
        installPackage(e) { f -> progress(f * e.bytes / total) }
        val fresh = pkg(e.id) ?: return
        if (extra != null) addHigh(fresh, extra) { f -> progress((e.bytes + f * extra.bytes) / total) }
    }

    /**
     * Los cuadros de en medio, encima de lo instalado. Se descomprimen aparte y se
     * mueven al final, el primero ("m00", el que dice que hay calidad alta) al último:
     * si algo se corta a la mitad, el auto sigue completo en calidad baja.
     */
    private suspend fun addHigh(p: CarPackage, extra: SpinExtra, progress: (Float) -> Unit) {
        withContext(Dispatchers.IO) {
            val temp = fetch(extra.url, extra.bytes, extra.sha256, progress)
            val staging = File(p.folder, CarFiles.SPIN + CarFiles.STAGING_SUFFIX)
            staging.deleteRecursively()
            unzip(temp, staging)
            temp.delete()
            val spin = File(p.folder, CarFiles.SPIN)
            val files = File(staging, CarFiles.SPIN).listFiles().orEmpty().sortedBy { it.name.startsWith(HIGH_MARK) }
            files.forEach { it.renameTo(File(spin, it.name)) }
            staging.deleteRecursively()
        }
        changed()
    }

    private fun spinFiles(folder: File, accept: (String) -> Boolean) =
        File(folder, CarFiles.SPIN).listFiles().orEmpty().filter { accept(it.name) }

    /** Después de instalar, quitar o cambiar un auto: lo de memoria ya no sirve y se vuelve a leer. */
    private suspend fun changed() {
        images.evictAll()
        sourceWidths.clear()
        val fresh = withContext(Dispatchers.IO) { scan() }
        installed = fresh
        outdated = outdated?.takeIf { e -> pkg(e.id)?.let { isOutdated(it, e) } == true }
    }

    /** Los autos en el disco: solo sus carpetas (no la de miniaturas ni una a medio instalar, que llevan punto). */
    private fun scan(): List<CarPackage> =
        root.listFiles().orEmpty().filter { it.isDirectory && '.' !in it.name }.mapNotNull(::readPackage).sortedBy { it.name }

    private fun fetch(url: String, bytes: Long, sha256: String, progress: (Float) -> Unit): File {
        // Cada paquete con su archivo: dos descargas nunca escriben en el mismo.
        val temp = File(root, CarFiles.DOWNLOAD_PREFIX + sha256.take(12) + ".zip")
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
     * De la caché, de la carga que ya va en camino, o se carga una sola vez en IO. Si
     * decodificar falla (sin memoria en una pantalla chica, archivo dañado) devuelve
     * null y vacía la caché para que quepa el siguiente: el auto se queda con el cuadro
     * anterior en vez de tumbar la app (la excepción llegaría a la composición).
     */
    private suspend fun cached(key: String, load: () -> Bitmap?): ImageBitmap? {
        images.get(key)?.let { return it }
        val job = loading.computeIfAbsent(key) {
            // Perezosa: arranca ya guardada en [loading], así nunca se quita antes de entrar.
            scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
                try {
                    load()?.asImageBitmap()?.also { images.put(key, it) }
                } catch (e: OutOfMemoryError) {
                    images.evictAll()
                    null
                } catch (e: Exception) {
                    null
                } finally {
                    loading.remove(key)
                }
            }
        }
        job.start()
        return job.await()
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

    /**
     * Decodifica ya escalada a [width]. Primero se salta píxeles al leer (potencias de 2,
     * que el decodificador hace sin leer la imagen completa) y luego ajusta lo que falta:
     * así un cuadro de 1400 px que se dibuja a 400 no se decodifica entero. Solo en IO.
     */
    private fun decode(p: CarPackage, name: String, width: Int): Bitmap? {
        val file = File(p.folder, "$name.webp")
        val source = sourceWidth(file, read = true) ?: return null
        var sample = 1
        while (source / (sample * 2) >= width) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inScaled = true
            inDensity = source / sample
            inTargetDensity = width
            inMutable = true
        })
    }

    private fun readPackage(folder: File): CarPackage? {
        val json = File(folder, CarFiles.PACKAGE_INFO).takeIf { it.exists() } ?: return null
        return runCatching {
            val a = JSONObject(json.readText())
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
                // Todos los paquetes traen giro; uno sin giro no sirve en esta versión.
                spin = a.optInt(CarJson.SPIN).takeIf { it > 0 } ?: return null,
                high = File(folder, "${CarFiles.SPIN}/$HIGH_MARK.webp").exists(),
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
        // Renglón por renglón: dos copias enteras de la imagen eran ~8 MB de basura por
        // cuadro (48 cuadros por color) y el recolector trababa el giro.
        val px = IntArray(w)
        val mp = IntArray(w)
        for (y in 0 until h) {
            base.getPixels(px, 0, w, 0, y, w, 1)
            mask.getPixels(mp, 0, w, 0, y, w, 1)
            var touched = false
            for (i in 0 until w) {
                val m = mp[i] ushr 24
                if (m == 0) continue
                touched = true
                val c = px[i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val rr = r + (tables[0][r] - r) * m / 255
                val gg = g + (tables[1][g] - g) * m / 255
                val bb = b + (tables[2][b] - b) * m / 255
                px[i] = (c and 0xFF000000.toInt()) or (rr shl 16) or (gg shl 8) or bb
            }
            if (touched) base.setPixels(px, 0, w, 0, y, w, 1)
        }
    }

    private fun folderOf(id: String) = id.replace(FOLDER_CHARS, "-")

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "LiteDash")
            if (responseCode !in 200..299) error("HTTP $responseCode")
        }

    private fun readText(url: String) = open(url).inputStream.bufferedReader().use { it.readText() }

    companion object {
        /** [downloading] mientras se actualizan todos. */
        const val ALL = "todos"
        /** [downloading] mientras se baja la calidad alta. */
        const val HIGH = "calidad"
        /** Nivel de la calidad alta en "extras" del catálogo. */
        private const val HIGH_LEVEL = 1
        /** Cuadros de en medio ("mNN"); el primero dice que el auto tiene calidad alta. */
        private const val HIGH_PREFIX = "m"
        private const val HIGH_MARK = "m00"
        private const val CHECK_EVERY_MS = 60 * 60 * 1000L
        private const val KEY_LAST_CHECK = "ultima_revision:"
        /** Lo que no va en el nombre de la carpeta de un auto. */
        private val FOLDER_CHARS = Regex("[^a-z0-9-]")
    }
}
