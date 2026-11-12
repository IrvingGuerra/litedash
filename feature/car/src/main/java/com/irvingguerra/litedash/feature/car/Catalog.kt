package com.irvingguerra.litedash.feature.car

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.LruCache
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import com.irvingguerra.litedash.core.ui.Https
import com.irvingguerra.litedash.core.ui.scaledDecode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt

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
    val high: PackageExtra? = null,
    /** El auto desde arriba para el Taller (unos KB); se baja aparte, la primera vez que se necesita. */
    val topView: PackageExtra? = null,
    /** Dónde pisa cada rueda en el giro (unos KB, para las pastillas de las llantas); se baja aparte. */
    val wheels: PackageExtra? = null,
    /** Capas del acabado de la pintura (brillo y espejo de cada cuadro); se bajan al elegir un acabado. */
    val finish: PackageExtra? = null,
    /** Años en que el auto se ve igual (su generación), p. ej. "2016–2018"; sin rango, el año. */
    val years: String = year.toString(),
) {
    /** El auto se ve igual en [y]: está dentro de su rango de años. */
    fun covers(y: Int): Boolean {
        val n = YEAR_NUMBER.findAll(years).map { it.value.toInt() }.toList()
        return n.isNotEmpty() && y in n.min()..n.max()
    }

    /**
     * Una entrada que mandó otro (el celular, §5e) solo se usa si todos sus archivos son de la release del catálogo
     * de LiteDash en GitHub, con su SHA-256 y un tamaño de auto. El SHA-256 lo da quien manda la entrada: solo prueba
     * que llegó el archivo que se anunció, no que sea bueno; lo que lo contiene son los topes al descomprimir.
     */
    val isSafe: Boolean
        get() = listOfNotNull(PackageExtra(url, bytes, sha256), high, topView, wheels, finish).all { it.isSafe }

    companion object {
        /** Una entrada de catalogo.json (lanza si le falta algo). */
        fun fromJson(a: JSONObject): CatalogEntry {
            val extras = a.optJSONArray(CarJson.EXTRAS)
            return CatalogEntry(
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
                    ?.let(::extraOf),
                topView = a.optJSONObject(CarJson.TOP_VIEW)?.let(::extraOf),
                wheels = a.optJSONObject(CarJson.WHEELS)?.let(::extraOf),
                finish = a.optJSONObject(CarJson.FINISH)?.let(::extraOf),
                years = a.optString(CarJson.YEARS).ifBlank { a.optInt(CarJson.YEAR).toString() },
            )
        }

        private fun extraOf(o: JSONObject) = PackageExtra(o.getString("url"), o.optLong("bytes"), o.getString("sha256"))

        /** Nivel de la calidad alta en "extras" del catálogo. */
        private const val HIGH_LEVEL = 1
    }
}

private val YEAR_NUMBER = Regex("\\d{4}")

/** Un archivo extra (cuadros del giro, vista desde arriba) que se baja encima del auto ya instalado. */
@Immutable
data class PackageExtra(val url: String, val bytes: Long, val sha256: String) {
    internal val isSafe get() = url.startsWith(TRUSTED_FILES) && bytes in 1..MAX_PACKAGE_BYTES && SHA256.matches(sha256)
}

/** De dónde salen los archivos de los autos (la release `catalogo`): lo de otro lado que mande el celular no se baja. */
private const val TRUSTED_FILES = "https://github.com/IrvingGuerra/litedash/releases/download/"

/** Por qué no se pudo bajar o instalar algo del catálogo. */
enum class InstallError {
    /** Sin internet o el servidor no contestó: otro (el celular) lo puede pasar. */
    OFFLINE,
    /** Llegó otro archivo (SHA-256 o tamaño), o es un zip roto o que se expande de más. */
    DAMAGED,
    /** Lo demás: sin espacio, el auto cambió mientras tanto, el servidor dijo que no existe… */
    OTHER,
}

/** Sin red o el servidor falló (lo que lanza [Https] como IOException). */
private class Offline(cause: Throwable) : IOException(cause)

/** El archivo no es el que se anunció o no se puede descomprimir como un auto. */
private class Damaged(message: String) : IllegalStateException(message)

private val SHA256 = Regex("[0-9a-fA-F]{64}")
/** Ningún archivo de un auto pesa tanto (los paquetes son de ~2 MB). */
private const val MAX_PACKAGE_BYTES = 64L shl 20

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
    /** Trae la vista desde arriba ([CarViews.TOP_VIEW]) para el Taller. */
    val topView: Boolean,
    val folder: File,
    /** SHA-256 del paquete con que se instaló ("" si se bajó antes de guardarlo). */
    val sha256: String,
    /** Dónde pisa cada rueda en el giro, si ya se bajó ([CarRepository.fetchWheels]). */
    val wheels: CarWheels? = null,
    /** Trae las capas del acabado ([CarRepository.fetchFinish]) de todos sus cuadros. */
    val finish: Boolean = false,
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
class CarRepository(private val context: Context, val catalogUrl: String) {
    private val root = File(context.filesDir, CarFiles.ROOT)
    private val checks = context.getSharedPreferences(CarFiles.ROOT, Context.MODE_PRIVATE)
    // Lo que se baja o se arregla vive aquí y no en la pantalla que lo pidió: si se cierra
    // Ajustes a media descarga, termina igual.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // Caché de imágenes ya teñidas: la cuarta parte de la memoria de la app (de 32 a
    // 160 MB), para que quepa el giro completo en calidad alta a tamaño real en
    // pantallas grandes. Si no cupiera, al girar en círculo siempre se borraría justo
    // el cuadro que sigue y el giro se iría trabando. En Android 6 y 7 las imágenes viven en
    // esa misma memoria (desde la 8 van aparte) y suele ser poca (48 MB): ahí 3/8, sin el
    // mínimo (con 1/4 el giro bajaba a 5 cuadros/s en el emulador de Android 6; con 1/2 no
    // quedaba aire para la pantalla).
    private val images = object : LruCache<String, ImageBitmap>(
        Runtime.getRuntime().maxMemory().let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) (it / 4).coerceIn(32L shl 20, 160L shl 20) else it * 3 / 8
        }.toInt(),
    ) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    // Imágenes que ya se están cargando: si la vista y la precarga piden la misma, se
    // decodifica una vez. La carga no se interrumpe aunque quien la pidió ya no la quiera.
    private val loading = ConcurrentHashMap<String, Deferred<ImageBitmap?>>()
    // Instalar, actualizar, borrar y la vista desde arriba tocan las mismas carpetas: uno a la vez.
    private val disk = Mutex()
    private val extraJobs = HashMap<Extra, Job>()
    /** El último catálogo que pidieron los extras y cuándo (ver [recentCatalog]). */
    @Volatile private var catalogCache: Pair<Long, List<CatalogEntry>>? = null
    // Ancho original de cada imagen (no cambia): decide cuánto se reduce al decodificarla.
    private val sourceWidths = ConcurrentHashMap<String, Int>()

    val hasCatalog get() = catalogUrl.isNotBlank()

    /** Los autos bajados (se leen del disco al empezar y tras cada cambio, en IO). */
    var installed by mutableStateOf(takeEarlyScan() ?: scan(root))
        private set

    /** El auto elegido tiene un modelo 3D nuevo en el catálogo (lo llena [checkQuietly]). */
    var outdated by mutableStateOf<CatalogEntry?>(null)
        private set

    /** Lo que se está bajando ([ALL], el id de un auto, [HIGH] o [FINISH]) y cuánto va (0–1); null = nada. */
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
        // Una entrada mal escrita se salta: no tumba el catálogo de todos.
        (0 until cars.length()).mapNotNull { i -> runCatching { CatalogEntry.fromJson(cars.getJSONObject(i)) }.getOrNull() }
    }.also { catalogCache = System.currentTimeMillis() to it }

    /** La entrada de [id] en el último catálogo que se bajó (sin ir a internet); null si no se ha bajado. */
    fun knownEntry(id: String): CatalogEntry? = catalogCache?.second?.firstOrNull { it.id == id }

    /** El catálogo trae otro modelo 3D para [p] (mejores renders, piso nuevo…). */
    fun isOutdated(p: CarPackage, e: CatalogEntry) = p.id == e.id && p.sha256 != e.sha256

    /** [e] ofrece las capas del acabado para este mismo modelo 3D de [p] (las de otro no le sirven). */
    fun offersFinish(p: CarPackage, e: CatalogEntry?) = e != null && e.finish != null && !isOutdated(p, e)

    /** [p] puede verse Mate, Brillante o Metálico: trae sus capas o [e] se las ofrece. */
    fun hasFinishes(p: CarPackage, e: CatalogEntry?) = p.finish || offersFinish(p, e)

    /** Lo que se baja al actualizar [p]: el paquete y, si los tenía, la calidad alta y el acabado. */
    fun updateSize(p: CarPackage, e: CatalogEntry) =
        e.bytes + (if (p.high) e.high?.bytes ?: 0 else 0) + (if (p.finish) e.finish?.bytes ?: 0 else 0)

    /**
     * Baja e instala [e]; al terminar, [then] (por ejemplo, elegirlo) y si falla, [failed] con el motivo. Falso si
     * no empezó (ya se baja otra cosa).
     */
    fun install(e: CatalogEntry, failed: (InstallError) -> Unit = {}, then: () -> Unit = {}) = download(e.id, failed) { progress ->
        installPackage(e, progress)
        then()
    }

    /**
     * Instala [e] desde [file], que llegó por otro camino (el celular, §5f): se revisan su tamaño y SHA-256 contra
     * [e] y se acomoda igual que una descarga; el archivo se borra al terminar. Como [install].
     */
    fun install(e: CatalogEntry, file: File, failed: (InstallError) -> Unit, then: () -> Unit) = download(e.id, failed) {
        placePackage(e) { verified(file, e.bytes, e.sha256) }
        then()
    }.also { started -> if (!started) scope.launch(Dispatchers.IO) { file.delete() } }

    /** Baja el modelo 3D nuevo de [p] y vuelve a poner su calidad. */
    fun update(p: CarPackage, e: CatalogEntry) = download(p.id) { progress -> updatePackage(p, e, progress) }

    /** Actualiza todos los de [list] (auto instalado y su entrada nueva), uno tras otro. */
    fun updateAll(list: List<Pair<CarPackage, CatalogEntry>>) = download(ALL) { progress ->
        list.forEachIndexed { i, (p, e) -> updatePackage(p, e) { f -> progress((i + f) / list.size) } }
    }

    /** Baja los cuadros de en medio de [p] (calidad alta). */
    fun raiseQuality(p: CarPackage, e: CatalogEntry) {
        val extra = e.high ?: return
        download(HIGH) { progress ->
            // Los cuadros de en medio son del modelo 3D del catálogo: si el instalado es otro, primero se actualiza.
            if (isOutdated(p, e)) {
                val total = (e.bytes + extra.bytes).coerceAtLeast(1).toFloat()
                installPackage(e) { f -> progress(f * e.bytes / total) }
                addHigh(pkg(e.id) ?: return@download, extra) { f -> progress((e.bytes + f * extra.bytes) / total) }
            } else addHigh(p, extra, progress)
        }
    }

    /**
     * Baja las capas del acabado de [p] (al elegir Mate, Brillante o Metálico), con su avance en
     * [downloading]; si ya las tiene o el catálogo trae otro modelo 3D (las capas son del nuevo), nada. [then]
     * solo si quedaron puestas; si el auto cambió mientras tanto, [failed] con [InstallError.OTHER].
     */
    fun fetchFinish(p: CarPackage, e: CatalogEntry, failed: (InstallError) -> Unit = {}, then: () -> Unit = {}): Boolean {
        val extra = e.finish ?: return false
        if (p.finish || isOutdated(p, e)) return false
        return download(FINISH, failed) { progress ->
            if (addFinish(p) { fetch(extra.url, extra.bytes, extra.sha256, progress) }) then() else failed(InstallError.OTHER)
        }
    }

    /**
     * Las capas del acabado de [p] desde [file] (llegó del celular), revisadas contra [e]. Como [fetchFinish]; falso
     * si no empezó (ya se baja otra cosa: quien llama revisa antes [offersFinish]).
     */
    fun installFinish(p: CarPackage, e: CatalogEntry, file: File, failed: (InstallError) -> Unit, then: () -> Unit): Boolean {
        val extra = e.finish?.takeIf { offersFinish(p, e) }
        val started = extra != null && download(FINISH, failed) {
            val done = try {
                addFinish(p) { verified(file, extra.bytes, extra.sha256) }
            } finally {
                // Si el auto cambió mientras tanto, no se usó: igual se quita.
                withContext(Dispatchers.IO) { file.delete() }
            }
            if (done) then() else failed(InstallError.OTHER)
        }
        if (!started) scope.launch(Dispatchers.IO) { file.delete() }
        return started
    }

    /** Calidad baja: borra los cuadros de en medio (libera espacio; no necesita internet). */
    fun lowerQuality(p: CarPackage) = scope.launch {
        disk.withLock {
            withContext(Dispatchers.IO) { spinFiles(p.folder) { it.startsWith(HIGH_PREFIX) }.forEach { it.delete() } }
            changed()
        }
    }

    /** Quita un auto descargado (sus imágenes); se puede volver a bajar del catálogo. */
    fun delete(p: CarPackage) = scope.launch {
        disk.withLock {
            withContext(Dispatchers.IO) { p.folder.deleteRecursively() }
            changed()
        }
    }

    /** Quita todos los autos descargados (sus imágenes); se pueden volver a bajar del catálogo. */
    fun deleteAll() = scope.launch {
        disk.withLock {
            val all = installed
            withContext(Dispatchers.IO) { all.forEach { it.folder.deleteRecursively() } }
            changed()
        }
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
        val entry = recentCatalog()?.firstOrNull { it.id == p.id } ?: return
        outdated = entry.takeIf { isOutdated(p, it) }
        // Con modelo nuevo no se anota: el aviso vive en memoria y debe volver al reabrir la app.
        if (outdated == null) checks.edit().putLong(key, now).apply()
    }

    /**
     * La vista desde arriba del auto [p] para el Taller: un archivo chico aparte del paquete
     * (así nadie vuelve a bajar su auto por ella); mientras no la haya, el Taller dibuja la silueta.
     */
    fun fetchTopView(p: CarPackage?) = fetchExtra(p, Extra.TOP_VIEW)

    /** Dónde pisa cada rueda de [p] en el giro (las pastillas de las llantas); como la planta, aparte del paquete. */
    fun fetchWheels(p: CarPackage?) = fetchExtra(p, Extra.WHEELS)

    /** Autos que el catálogo no trae con [CarPackage.wheels] (la moto): sus pastillas van en las esquinas. */
    var withoutWheels by mutableStateOf<Set<String>>(emptySet())
        private set

    /**
     * Baja un [Extra] de [p] si el catálogo lo trae y el auto aún no. Corre en el ámbito del
     * repositorio (si se sale de la pantalla a media descarga, termina igual), uno a la vez por
     * tipo y con el candado [disk] de instalar o borrar. Se pregunta a lo más cada
     * [CHECK_EVERY_MS] por auto (solo cuenta si contestó) y con el catálogo de [recentCatalog].
     * No toca la huella del paquete.
     */
    private fun fetchExtra(p: CarPackage?, kind: Extra) {
        if (p == null || kind.has(p) || !hasCatalog || extraJobs[kind]?.isActive == true) return
        val key = kind.checkKey + p.id
        if (System.currentTimeMillis() - checks.getLong(key, 0L) < CHECK_EVERY_MS) return
        extraJobs[kind] = scope.launch {
            val catalog = recentCatalog() ?: return@launch
            checks.edit().putLong(key, System.currentTimeMillis()).apply()
            val entry = catalog.firstOrNull { it.id == p.id }
            // El extra del catálogo es del modelo 3D nuevo: no va sobre el instalado (llega al actualizar).
            if (entry != null && p.sha256.isNotEmpty() && isOutdated(p, entry)) return@launch
            val extra = entry?.let(kind.pick)
            if (extra == null) {
                if (kind == Extra.WHEELS) withoutWheels += p.id
                return@launch
            }
            disk.withLock {
                // Pudo cambiar mientras tanto (actualizado, borrado o ya con el extra).
                val now = pkg(p.id)?.takeIf { it.sha256 == p.sha256 && !kind.has(it) } ?: return@withLock
                val done = withContext(Dispatchers.IO) {
                    runCatching { installExtra(now, kind.folder, kind.marker) { fetch(extra.url, extra.bytes, extra.sha256) {} } }.getOrDefault(false)
                }
                // Solo llegan archivos nuevos: las imágenes del giro en memoria siguen sirviendo.
                if (done) changed(evict = false)
            }
        }
    }

    /**
     * El catálogo para los extras: uno bajado hace menos de [CHECK_EVERY_MS] sirve de nuevo,
     * así se baja a lo más una vez por hora. null sin internet.
     */
    private suspend fun recentCatalog(): List<CatalogEntry>? {
        val now = System.currentTimeMillis()
        catalogCache?.takeIf { now - it.first < CHECK_EVERY_MS }?.let { return it.second }
        return runCatching { catalog() }.getOrNull()
    }

    /**
     * Acomoda un extra (el zip que da [obtain], ya revisado) en la carpeta de [p]: se descomprime aparte
     * ([folder]) y cada archivo va a su lugar (también dentro de giro/), con el [marker] al último (dice
     * que ya está completo); lo que hubiera de un intento cortado se quita, y el zip también. Solo en IO.
     */
    private fun installExtra(p: CarPackage, folder: String, marker: String, obtain: () -> File): Boolean {
        val temp = obtain()
        val staging = File(root, folderOf(p.id) + "-" + folder + CarFiles.STAGING_SUFFIX)
        try {
            staging.deleteRecursively()
            unzip(temp, staging)
            val mark = File(staging, marker)
            if (!mark.exists()) return false
            return staging.walkTopDown().filter { it.isFile }.sortedBy { it == mark }.toList().all {
                val target = File(p.folder, it.relativeTo(staging).path)
                target.parentFile?.mkdirs()
                target.deleteRecursively()
                it.renameTo(target)
            }
        } finally {
            temp.delete()
            staging.deleteRecursively()
        }
    }

    /**
     * Miniatura de un auto del catálogo: se baja la primera vez que se ve la
     * lista y se guarda (unos KB por auto), así la app no carga con ninguna.
     * El archivo lleva el nombre de su URL: si el catálogo publica una miniatura
     * nueva (otra URL, p. ej. al rehacer un auto), se baja de nuevo.
     */
    suspend fun thumbnail(e: CatalogEntry): ImageBitmap? {
        if (e.thumbnail.isBlank()) return null
        val key = "mini/${e.thumbnail}"
        images.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val prefix = folderOf(e.id) + "-"
                val name = prefix + Integer.toHexString(e.thumbnail.hashCode()) + ".webp"
                val file = File(File(root, CarFiles.THUMBNAILS).apply { mkdirs() }, name)
                if (!file.exists()) {
                    val temp = File(file.path + CarFiles.PARTIAL_SUFFIX)
                    Https.get(context, e.thumbnail).inputStream.use { input -> temp.outputStream().use { input.copyTo(it) } }
                    temp.renameTo(file)
                    // Las de antes de este auto (otra URL) ya no se usan.
                    file.parentFile?.listFiles { f -> f.name.startsWith(prefix) && f.name != name }?.forEach { it.delete() }
                }
                val bitmap = BitmapFactory.decodeFile(file.path)
                // Dañada (se apagó a media escritura): se baja de nuevo la próxima vez.
                if (bitmap == null) file.delete()
                bitmap?.asImageBitmap()?.also { images.put(key, it) }
            }.getOrNull()
        }
    }

    /** Lo que ocupa un auto descargado, en bytes. */
    suspend fun sizeOnDisk(p: CarPackage): Long = withContext(Dispatchers.IO) {
        p.folder.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /**
     * Una descarga a la vez, en el ámbito del repositorio, con su avance en [downloading]; si falla, [onFail] con el
     * motivo. Falso si ya había otra (esta no empieza).
     */
    private fun download(what: String, onFail: (InstallError) -> Unit = {}, work: suspend ((Float) -> Unit) -> Unit): Boolean {
        if (downloading != null) return false
        downloading = what to 0f
        failed = false
        scope.launch {
            val error = disk.withLock { runCatching { work { f -> downloading = what to f } }.exceptionOrNull() }
            if (error != null) failed = true
            downloading = null
            if (error != null) {
                onFail(
                    when (error) {
                        is Offline -> InstallError.OFFLINE
                        is Damaged -> InstallError.DAMAGED
                        else -> InstallError.OTHER
                    },
                )
            }
        }
        return true
    }

    private suspend fun installPackage(e: CatalogEntry, progress: (Float) -> Unit) =
        placePackage(e) { fetch(e.url, e.bytes, e.sha256, progress) }

    /** Instala [e] con el zip que da [obtain] (ya revisado con su SHA-256); el zip se borra al terminar. */
    private suspend fun placePackage(e: CatalogEntry, obtain: () -> File) {
        withContext(Dispatchers.IO) {
            root.mkdirs()
            val temp = obtain()
            val target = File(root, folderOf(e.id))
            val staging = File(root, folderOf(e.id) + CarFiles.STAGING_SUFFIX)
            try {
                staging.deleteRecursively()
                unzip(temp, staging)
                File(staging, CarFiles.PACKAGE_HASH).writeText(e.sha256)
                target.deleteRecursively()
                check(staging.renameTo(target)) { "No se pudo acomodar el auto" }
            } finally {
                // Si algo se cortó, nada queda ocupando espacio.
                temp.delete()
                staging.deleteRecursively()
            }
        }
        // El paquete nuevo llega sin la vista desde arriba: que el Taller la pida de una vez.
        checks.edit().apply { Extra.entries.forEach { remove(it.checkKey + e.id) } }.apply()
        changed()
    }

    private suspend fun updatePackage(p: CarPackage, e: CatalogEntry, progress: (Float) -> Unit) {
        val extra = e.high?.takeIf { p.high }
        val finish = e.finish?.takeIf { p.finish }
        val before = e.bytes + (extra?.bytes ?: 0)
        val total = (before + (finish?.bytes ?: 0)).coerceAtLeast(1).toFloat()
        installPackage(e) { f -> progress(f * e.bytes / total) }
        if (extra != null) addHigh(pkg(e.id) ?: return, extra) { f -> progress((e.bytes + f * extra.bytes) / total) }
        // Las capas del acabado son de los cuadros nuevos: se vuelven a bajar (después de la calidad alta).
        if (finish != null) {
            addFinish(pkg(e.id) ?: return) { fetch(finish.url, finish.bytes, finish.sha256) { f -> progress((before + f * finish.bytes) / total) } }
        }
    }

    /**
     * Las capas del acabado encima de lo instalado (si [p] sigue igual; si cambió o ya las tiene, nada y falso).
     * Solo llegan archivos nuevos: las imágenes en memoria siguen sirviendo (la llave de cada cuadro con acabado
     * dice si tenía capas).
     */
    private suspend fun addFinish(p: CarPackage, obtain: () -> File): Boolean {
        val now = pkg(p.id)?.takeIf { it.sha256 == p.sha256 && !it.finish } ?: return false
        val done = withContext(Dispatchers.IO) { installExtra(now, FINISH, FINISH_MARK, obtain) }
        if (!done) throw Damaged("El acabado llegó incompleto")
        changed(evict = false)
        // Si aun así faltan capas (p. ej. de los cuadros de en medio), es falla: no se vuelve a pedir solo.
        if (pkg(p.id)?.finish != true) throw Damaged("El acabado no trae todos los cuadros")
        return true
    }

    /**
     * Los cuadros de en medio, encima de lo instalado: como todo extra, el primero ("m00", el que
     * dice que hay calidad alta) se acomoda al último; si algo se corta, el auto sigue completo en baja.
     */
    private suspend fun addHigh(p: CarPackage, extra: PackageExtra, progress: (Float) -> Unit) {
        val done = withContext(Dispatchers.IO) { installExtra(p, HIGH, HIGH_FILE) { fetch(extra.url, extra.bytes, extra.sha256, progress) } }
        check(done) { "La calidad alta llegó incompleta" }
        changed()
    }

    private fun spinFiles(folder: File, accept: (String) -> Boolean) =
        File(folder, CarFiles.SPIN).listFiles().orEmpty().filter { accept(it.name) }

    /**
     * La app se fue a segundo plano: con [all] suelta todos los cuadros del auto; si no, deja un cuarto
     * de la caché (lo más reciente) para que al volver el auto aparezca sin esperar.
     */
    fun trimMemory(all: Boolean) {
        if (all) images.evictAll() else images.trimToSize(images.maxSize() / 4)
    }

    /** Después de instalar, quitar o cambiar un auto: lo de memoria ya no sirve y se vuelve a leer. */
    private suspend fun changed(evict: Boolean = true) {
        if (evict) {
            images.evictAll()
            sourceWidths.clear()
        }
        val fresh = withContext(Dispatchers.IO) { scan(root) }
        installed = fresh
        outdated = outdated?.takeIf { e -> pkg(e.id)?.let { isOutdated(it, e) } == true }
    }

    private fun fetch(url: String, bytes: Long, sha256: String, progress: (Float) -> Unit): File {
        // Cada paquete con su archivo: dos descargas nunca escriben en el mismo.
        val temp = File(root, CarFiles.DOWNLOAD_PREFIX + sha256.take(12) + ".zip")
        val ok = runCatching { Https.download(context, url, temp, bytes, null, progress).equals(sha256, ignoreCase = true) }
        if (ok.getOrDefault(false)) return temp
        temp.delete()
        ok.exceptionOrNull()?.let { throw if (it is IOException) Offline(it) else it }
        throw Damaged("El archivo llegó dañado")
    }

    /**
     * [file] tal cual llegó (el celular ya lo bajó): si no mide [bytes] o su SHA-256 no es [sha256], se borra y
     * lanza. Solo en IO.
     */
    private fun verified(file: File, bytes: Long, sha256: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
        val ok = runCatching {
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            (bytes <= 0 || file.length() == bytes) && digest.digest().joinToString("") { "%02x".format(it) }.equals(sha256, ignoreCase = true)
        }.getOrDefault(false)
        if (ok) return file
        file.delete()
        throw Damaged("El archivo llegó dañado")
    }

    /**
     * Descomprime [zip] en [target] con topes para cualquier paquete (también los del catálogo): a lo más
     * [UNZIP_RATIO] veces lo que mide el zip (y nunca más de [UNZIP_MAX]), [UNZIP_ENTRIES] archivos y [UNZIP_FILE_MAX]
     * por archivo, contando lo que de verdad sale (no lo que dice el zip). Los paquetes reales son imágenes WebP que
     * casi no se comprimen (≤ 1.0×, ≤ 127 archivos, ≤ 70 KB cada uno): un zip que se expande de más no es un auto.
     */
    private fun unzip(zip: File, target: File) {
        val budget = (zip.length() * UNZIP_RATIO).coerceIn(UNZIP_MIN, UNZIP_MAX)
        var total = 0L
        var entries = 0
        val buffer = ByteArray(64 * 1024)
        try {
            ZipInputStream(zip.inputStream().buffered()).use { input ->
                while (true) {
                    val item = input.nextEntry ?: break
                    if (++entries > UNZIP_ENTRIES) throw Damaged("Demasiados archivos")
                    val file = File(target, item.name)
                    // Nada fuera de la carpeta del auto (zip con rutas "../").
                    if (!file.canonicalPath.startsWith(target.canonicalPath + File.separator)) continue
                    if (item.isDirectory) file.mkdirs() else {
                        file.parentFile?.mkdirs()
                        file.outputStream().use { out ->
                            var written = 0L
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                written += n
                                total += n
                                if (written > UNZIP_FILE_MAX || total > budget) throw Damaged("El paquete se expande de más")
                                out.write(buffer, 0, n)
                            }
                        }
                    }
                }
            }
        } catch (e: ZipException) {
            throw Damaged("Zip dañado: ${e.message}")
        }
    }

    /**
     * La imagen [name] lista para dibujar a [widthPx]: el auto con su [paint] y,
     * si esa imagen trae piso, su sombra y su reflejo (a la mitad: son suaves; el reflejo
     * siempre satinado). La usan la vista y la precarga del giro, así cada cuadro llega con
     * todas sus capas.
     */
    suspend fun layers(p: CarPackage, name: String, paint: CarPaint, widthPx: Int): CarLayers? {
        val car = painted(p, name, paint, widthPx) ?: return null
        val floor = p.floor[name] ?: return CarLayers(car, null, null, null)
        // La sombra y el reflejo son suaves: a la mitad del ancho se ven igual y ocupan
        // la cuarta parte de memoria.
        val floorWidth = (widthPx * floor.width / 2).roundToInt()
        return CarLayers(
            car,
            image(p, name + CarViews.SHADOW_SUFFIX, floorWidth),
            painted(p, name + CarViews.REFLECTION_SUFFIX, CarPaint(paint.color), floorWidth),
            floor,
        )
    }

    // Con la huella: una carga del modelo viejo que termine tarde no se confunde con el nuevo.
    private val CarPackage.cacheKey get() = "$id:${sha256.take(8)}"

    private suspend fun image(p: CarPackage, name: String, widthPx: Int): ImageBitmap? {
        val width = decodeWidth(p, name, widthPx) ?: return null
        return cached("${p.cacheKey}/$name@$width") { decode(p, name, width) }
    }

    /**
     * La imagen [name] ya pintada con [paint] (con su máscara "-pintura"). Se
     * tiñe una vez aquí y queda en caché: los brillos que se pasarían de blanco se
     * suavizan en vez de cortarse, así un auto blanco conserva su volumen. Sin las
     * capas del acabado ([CarPackage.finish]) se pinta satinado.
     */
    private suspend fun painted(p: CarPackage, name: String, paint: CarPaint, widthPx: Int): ImageBitmap? {
        val width = decodeWidth(p, name, widthPx) ?: return null
        val used = if (p.finish) paint else CarPaint(paint.color)
        return cached("${p.cacheKey}/$name#${used.color.toArgb()}${used.cacheTag}@$width") { paint(p, name, width, used) }
    }

    /**
     * Decodifica [name] a [width] y la tiñe con [paint] usando su máscara "-pintura" (si la trae) y,
     * con otro acabado, sus capas de brillo y espejo (al mismo ancho; si faltan o no cuadran, satinado).
     * Solo en IO.
     */
    private fun paint(p: CarPackage, name: String, width: Int, paint: CarPaint): Bitmap? =
        decode(p, name, width)?.also { base ->
            val mask = decode(p, name + CarViews.PAINT_MASK_SUFFIX, width)
            if (mask == null || !mask.sameSize(base)) return@also
            val layered = paint.finish != PaintFinish.SATIN
            val shine = if (layered) decode(p, name + CarViews.SHINE_SUFFIX, width)?.takeIf { it.sameSize(base) } else null
            val mirror = if (shine != null) decode(p, name + CarViews.MIRROR_SUFFIX, width)?.takeIf { it.sameSize(base) } else null
            val c = paint.color
            val shader = PaintShader(floatArrayOf(c.red, c.green, c.blue), p.gray, paint.finish, paint.glossStep)
            tint(base, mask, shader, shine?.takeIf { mirror != null }, mirror)
        }

    private fun Bitmap.sameSize(other: Bitmap) = width == other.width && height == other.height

    /**
     * De la caché, de la carga que ya va en camino, o se carga una sola vez en IO. Si
     * decodificar falla (sin memoria en una pantalla chica, archivo dañado) devuelve
     * null y vacía la caché para que quepa el siguiente: el auto se queda con el cuadro
     * anterior en vez de tumbar la app (la excepción llegaría a la composición).
     */
    private suspend fun cached(key: String, load: () -> Bitmap?): ImageBitmap? {
        images.get(key)?.let { return it }
        val job = loading[key] ?: run {
            // Perezosa: arranca ya guardada en [loading], así nunca se quita antes de entrar.
            val fresh = scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
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
            // putIfAbsent (desde Android 1; computeIfAbsent es de Android 7): si otra carga ganó, esta
            // se cancela sin haber arrancado (su finally no corre y no quita la ganadora).
            loading.putIfAbsent(key, fresh)?.also { fresh.cancel() } ?: fresh
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
        // Vacía o dañada: no se dibuja (un ancho de -1 tumbaba la app al acotarlo).
        return bounds.outWidth.takeIf { it > 0 }?.also { sourceWidths[file.path] = it }
    }

    /**
     * Decodifica ya escalada a [width]. Primero se salta píxeles al leer (potencias de 2,
     * que el decodificador hace sin leer la imagen completa) y luego ajusta lo que falta:
     * así un cuadro de 1400 px que se dibuja a 400 no se decodifica entero. Solo en IO.
     */
    private fun decode(p: CarPackage, name: String, width: Int): Bitmap? {
        val file = File(p.folder, "$name.webp")
        val source = sourceWidth(file, read = true) ?: return null
        return BitmapFactory.decodeFile(file.path, scaledDecode(source, width).apply { inMutable = true })
    }

    /** Pinta [base] con [shader] solo donde hay pintura ([mask]); [shine] y [mirror] = capas del acabado. */
    private fun tint(base: Bitmap, mask: Bitmap, shader: PaintShader, shine: Bitmap?, mirror: Bitmap?) {
        val w = base.width
        val h = base.height
        // Renglón por renglón: dos copias enteras de la imagen eran ~8 MB de basura por
        // cuadro (48 cuadros por color) y el recolector trababa el giro.
        val px = IntArray(w)
        val mp = IntArray(w)
        val sp = shine?.let { IntArray(w) }
        val ep = mirror?.let { IntArray(w) }
        for (y in 0 until h) {
            base.getPixels(px, 0, w, 0, y, w, 1)
            mask.getPixels(mp, 0, w, 0, y, w, 1)
            if (sp != null && ep != null) {
                shine.getPixels(sp, 0, w, 0, y, w, 1)
                mirror.getPixels(ep, 0, w, 0, y, w, 1)
            }
            if (shader.row(px, mp, sp, ep, w)) base.setPixels(px, 0, w, 0, y, w, 1)
        }
    }

    private fun folderOf(id: String) = id.replace(FOLDER_CHARS, "-")

    private fun readText(url: String) = Https.get(context, url).inputStream.bufferedReader().use { it.readText() }

    companion object {
        // La lectura de los autos que empezó [prefetch]; la recoge el primer repositorio.
        @Volatile private var early: FutureTask<List<CarPackage>>? = null

        /**
         * Empieza a leer del disco los autos bajados en un hilo aparte, para que al armar el inicio
         * ya estén y la primera composición no espere al disco (en las pantallas de auto es lento).
         */
        fun prefetch(context: Context) {
            if (early != null) return
            val root = File(context.filesDir, CarFiles.ROOT)
            early = FutureTask {
                // Al arrancar no hay descargas: lo que quedó de una cortada (zip o carpeta a medias) se quita.
                root.listFiles { f -> f.name.startsWith(CarFiles.DOWNLOAD_PREFIX) || f.name.endsWith(CarFiles.STAGING_SUFFIX) }
                    ?.forEach { it.deleteRecursively() }
                scan(root)
            }.also { Thread(it, "cars-scan").start() }
        }

        // Lo que leyó [prefetch] (espera lo que le falte); null si nadie la empezó o falló.
        private fun takeEarlyScan(): List<CarPackage>? =
            early?.let { task -> early = null; runCatching { task.get() }.getOrNull() }

        /** Los autos en el disco: solo sus carpetas (no la de miniaturas ni una a medio instalar, que llevan punto). */
        private fun scan(root: File): List<CarPackage> =
            root.listFiles().orEmpty().filter { it.isDirectory && '.' !in it.name }.mapNotNull(::readPackage).sortedBy { it.name }

        private fun readPackage(folder: File): CarPackage? {
            val json = File(folder, CarFiles.PACKAGE_INFO).takeIf { it.exists() } ?: return null
            return runCatching {
                val a = JSONObject(json.readText())
                val high = File(folder, HIGH_FILE).exists()
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
                    high = high,
                    floor = floor?.keys()?.asSequence()?.associateWith { v ->
                        val c = floor.getJSONArray(v)
                        FloorBox(c.getDouble(0).toFloat(), c.getDouble(1).toFloat(), c.getDouble(2).toFloat(), c.getDouble(3).toFloat())
                    }.orEmpty(),
                    topView = File(folder, CarViews.TOP_VIEW + ".webp").exists(),
                    folder = folder,
                    sha256 = File(folder, CarFiles.PACKAGE_HASH).takeIf { it.exists() }?.readText()?.trim().orEmpty(),
                    wheels = File(folder, CarViews.WHEELS + ".json").takeIf { it.exists() }
                        ?.let { CarWheels.parse(it.readText(), a.optInt(CarJson.SPIN)) },
                    // Con calidad alta, también las capas de los cuadros de en medio (llegan aparte).
                    finish = File(folder, FINISH_MARK).exists() &&
                        (!high ||
                            File(folder, "${CarFiles.SPIN}/$HIGH_MARK${CarViews.MIRROR_SUFFIX}.webp").exists()),
                )
            }.getOrNull()
        }

        /** [downloading] mientras se actualizan todos. */
        const val ALL = "all"
        /** [downloading] mientras se baja la calidad alta. */
        const val HIGH = "high"
        /** [downloading] mientras se bajan las capas del acabado (y su carpeta temporal). */
        const val FINISH = "finish"
        /** La última capa del acabado que se acomoda: dice que están completas. */
        private const val FINISH_MARK = "${CarFiles.SPIN}/00${CarViews.MIRROR_SUFFIX}.webp"
        /** Cuadros de en medio ("mNN"); el primero dice que el auto tiene calidad alta. */
        private const val HIGH_PREFIX = "m"
        private const val HIGH_MARK = "m00"
        private const val HIGH_FILE = "${CarFiles.SPIN}/$HIGH_MARK.webp"
        private const val CHECK_EVERY_MS = 60 * 60 * 1000L
        /** Topes al descomprimir ([unzip]): con mucho aire sobre los paquetes de hoy (~2.5 MB, 127 archivos). */
        private const val UNZIP_RATIO = 8
        private const val UNZIP_MIN = 1L shl 20
        private const val UNZIP_MAX = 200L shl 20
        private const val UNZIP_ENTRIES = 2_000
        private const val UNZIP_FILE_MAX = 16L shl 20
        private const val KEY_LAST_CHECK = "last_check:"
        /** Lo que no va en el nombre de la carpeta de un auto. */
        private val FOLDER_CHARS = Regex("[^a-z0-9-]")
    }
}

/**
 * Los archivos chicos que se bajan aparte del paquete: [folder] para su carpeta temporal,
 * [marker] (lo que dice que ya está completo; se mueve al último), [checkKey] para no
 * preguntar al catálogo más de una vez por hora, si el auto ya lo tiene y dónde viene en el catálogo.
 */
private enum class Extra(
    val folder: String,
    val marker: String,
    val checkKey: String,
    val has: (CarPackage) -> Boolean,
    val pick: (CatalogEntry) -> PackageExtra?,
) {
    TOP_VIEW(CarViews.TOP_VIEW, CarViews.TOP_VIEW + ".webp", "top_view_check:", { it.topView }, { it.topView }),
    WHEELS(CarViews.WHEELS, CarViews.WHEELS + ".json", "wheels_check:", { it.wheels != null }, { it.wheels }),
}
