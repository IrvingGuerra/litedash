package com.irvingguerra.litedash.feature.map

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.irvingguerra.litedash.core.ui.Https
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Un estado o región del índice de litedash-maps: su nombre, su contorno y el archivo del mapa. */
class MapRegion(
    val id: String,
    val country: String,
    private val names: Map<String, String>,
    val bbox: DoubleArray,
    /** Anillos exteriores: lon, lat, lon, lat… */
    val shape: List<DoubleArray>,
    val map: PackFile,
) {
    /** El nombre en [language] (o en inglés, o el id). */
    fun name(language: String): String = names[language] ?: names["en"] ?: id

    /** ¿([lat], [lon]) cae dentro del estado? Primero su caja; luego el contorno (par-impar). */
    fun contains(lat: Double, lon: Double): Boolean = insideShape(bbox, shape, lat, lon)
}

/** Un archivo del paquete: dónde está, cuánto pesa y su huella. */
class PackFile(val url: String, val size: Long, val sha256: String)

/**
 * Un mapa de estado ya bajado: de qué región y versión (su huella), y su caja y contorno (guardados al bajarlo: así sabe
 * qué teselas son suyas aunque no haya índice).
 */
class InstalledPack(val id: String, val sha256: String, val size: Long, val savedAt: Long, val bbox: DoubleArray, val shape: List<DoubleArray>) {
    fun contains(lat: Double, lon: Double) = insideShape(bbox, shape, lat, lon)
}

/** Bajando el mapa de [id]: cuánto va (0–1) o si falló. */
class PackJob(val id: String, val progress: Float, val failed: Boolean = false)

/**
 * Los mapas sin conexión por estado (M2 del Plan Mapas): el índice de github.com/IrvingGuerra/litedash-maps (un día en
 * caché), saber en qué estado estás, bajar el mapa de un estado (verificando su SHA-256), borrarlo y darle sus teselas
 * al mapa (antes que las zonas y que internet). Las calles para la ruta y la búsqueda se bajarán con sus etapas.
 *
 * Una tesela sale del mapa de un estado solo si su centro cae dentro del contorno del estado: la caja de un estado
 * abarca pedazos de los vecinos (la de Nayarit, Puerto Vallarta) que en su archivo vienen sin calles.
 */
class MapPacks private constructor(private val app: Context) {
    private val dir = File(app.filesDir, FOLDER)
    // Un error inesperado (disco lleno al guardar la lista) no tumba la app: se queda como estaba.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> })
    /** La descarga en curso y la lista (se toman poco tiempo). */
    private val stateLock = Any()
    private var running: Job? = null
    /** Los archivos abiertos, uno por mapa bajado; cada uno con su propio candado al leer. */
    private val open = HashMap<String, PmTiles>()
    private val openLock = Any()

    var regions by mutableStateOf<List<MapRegion>>(emptyList())
        private set
    var installed by mutableStateOf<List<InstalledPack>>(emptyList())
        private set
    var job by mutableStateOf<PackJob?>(null)
        private set

    /** Lo bajado, para las teselas (se lee sin candado desde los hilos del mapa). */
    @Volatile private var packs: List<InstalledPack> = emptyList()

    init {
        scope.launch {
            // Lo que quedó a medias si la app se cerró bajando.
            dir.listFiles { f -> f.name.endsWith(PART) }?.forEach { it.delete() }
            readInstalled()
            // El índice de la caché: los nombres para la lista y el contorno de los adoptados.
            File(dir, INDEX).takeIf { it.isFile }?.let { f -> runCatching { useRegions(parseIndex(f.readText())) } }
        }
    }

    /** El estado donde estás (o null si el índice no lo trae o aún no llega). */
    fun regionAt(lat: Double, lon: Double): MapRegion? = regions.firstOrNull { it.contains(lat, lon) }

    /** ¿Hay mapa bajado del estado donde está ([lat], [lon])? */
    fun covers(lat: Double, lon: Double): Boolean = packs.any { it.contains(lat, lon) }

    /** Baja el índice si el de la caché tiene más de un día (o [force]); sin red se queda con el de la caché. */
    fun refresh(force: Boolean = false) {
        scope.launch {
            val cached = File(dir, INDEX)
            if (regions.isEmpty() && cached.isFile) runCatching { useRegions(parseIndex(cached.readText())) }
            if (!force && cached.isFile && System.currentTimeMillis() - cached.lastModified() < INDEX_MAX_AGE_MS) return@launch
            try {
                val text = Https.get(app, INDEX_URL).inputStream.use { it.readBytes().decodeToString() }
                val parsed = parseIndex(text)
                dir.mkdirs()
                File(dir, "$INDEX.tmp").apply { writeText(text) }.renameTo(cached)
                useRegions(parsed)
            } catch (_: IOException) {
                // Sin internet: el de la caché (si hay).
            } catch (_: org.json.JSONException) {
                // Índice dañado: se queda el de antes.
            }
        }
    }

    /** Las regiones del índice; un mapa bajado sin contorno (adoptado) toma el de su región. */
    private fun useRegions(list: List<MapRegion>) {
        regions = list
        synchronized(stateLock) {
            if (installed.none { it.shape.isEmpty() }) return
            replaceInstalled(installed.map { p ->
                val r = list.firstOrNull { it.id == p.id }
                if (p.shape.isEmpty() && r != null) InstalledPack(p.id, p.sha256, p.size, p.savedAt, r.bbox, r.shape) else p
            })
        }
    }

    /** ¿El mapa bajado de [region] es de una versión vieja? */
    fun outdated(region: MapRegion): Boolean = installed.firstOrNull { it.id == region.id }?.let { it.sha256 != region.map.sha256 } ?: false

    /** Baja (o actualiza) el mapa de [region]; uno a la vez. Se verifica su huella antes de usarlo. */
    fun download(region: MapRegion) = synchronized(stateLock) {
        if (running?.isActive == true) return@synchronized
        job = PackJob(region.id, 0f)
        running = scope.launch {
            val me = coroutineContext.job
            val temp = File(dir, "${region.id}.pmtiles$PART")
            try {
                dir.mkdirs()
                var shown = 0f
                val sha = Https.download(app, region.map.url, temp, region.map.size, null) { p ->
                    // La descarga no se suspende: aquí se nota que la cancelaron.
                    if (!me.isActive) throw CancellationException()
                    // El avance cada 1 % (no en cada bloque: serían miles de redibujos).
                    if (p - shown >= 0.01f || p >= 1f) {
                        shown = p
                        job = PackJob(region.id, p)
                    }
                }
                if (!sha.equals(region.map.sha256, ignoreCase = true)) throw IOException("SHA-256")
                if (!me.isActive) throw CancellationException()
                val target = File(dir, "${region.id}.pmtiles")
                synchronized(openLock) {
                    open.remove(region.id)?.close()
                    if (!temp.renameTo(target)) throw IOException("rename")
                }
                val pack = InstalledPack(region.id, sha, target.length(), System.currentTimeMillis(), region.bbox, region.shape)
                synchronized(stateLock) { replaceInstalled(installed.filter { it.id != region.id } + pack) }
                job = null
            } catch (e: CancellationException) {
                job = null
                throw e
            } catch (_: Exception) {
                job = PackJob(region.id, 0f, failed = true)
            } finally {
                temp.delete()
                synchronized(stateLock) { if (running === me) running = null }
            }
        }
    }

    /** Detiene la descarga: deja de escribir al siguiente bloque y no instala nada. */
    fun cancel() = synchronized(stateLock) {
        running?.cancel()
        job = null
    }

    /** Borra el mapa bajado de [id] (lo pidió el usuario). */
    fun delete(id: String) {
        scope.launch {
            synchronized(stateLock) { replaceInstalled(installed.filter { it.id != id }) }
            synchronized(openLock) {
                open.remove(id)?.close()
                File(dir, "$id.pmtiles").delete()
            }
        }
    }

    /** La tesela z/x/y del mapa del estado donde cae su centro (tal como está guardada, con gzip); null = ninguno. */
    fun tile(z: Int, x: Int, y: Int): ByteArray? {
        val packs = packs
        if (packs.isEmpty()) return null
        val lat = tileLat(y + 0.5, z)
        val lon = tileLon(x + 0.5, z)
        val pack = packs.firstOrNull { it.contains(lat, lon) } ?: return null
        val file = synchronized(openLock) { open.getOrPut(pack.id) { PmTiles.open(File(dir, "${pack.id}.pmtiles")) ?: return null } }
        // Cada archivo con su candado: dos estados se leen a la vez y nadie más espera.
        return synchronized(file) { runCatching { file.tile(z, x, y) }.getOrNull() }
    }

    /** La lista guardada; los mapas que estén en la carpeta sin anotar (se cortó al guardarla) se adoptan con su caja. */
    private fun readInstalled() {
        val listed = runCatching {
            val a = JSONArray(File(dir, INSTALLED).readText())
            List(a.length()) { i -> packFromJson(a.getJSONObject(i)) }
        }.getOrDefault(emptyList()).filter { File(dir, "${it.id}.pmtiles").isFile }
        val orphans = dir.listFiles { f -> f.name.endsWith(".pmtiles") }.orEmpty()
            .filter { f -> listed.none { "${it.id}.pmtiles" == f.name } }
            .mapNotNull { f ->
                val bounds = PmTiles.open(f)?.use { it.bounds } ?: return@mapNotNull null
                // Su huella (para saber si hay versión nueva) se calcula aquí, una vez: ya estamos fuera del hilo principal.
                InstalledPack(f.name.removeSuffix(".pmtiles"), Https.sha256(f), f.length(), f.lastModified(), bounds, emptyList())
            }
        synchronized(stateLock) { replaceInstalled(listed + orphans) }
    }

    private fun replaceInstalled(list: List<InstalledPack>) {
        installed = list
        packs = list
        val a = JSONArray()
        for (p in list) a.put(packToJson(p))
        try {
            dir.mkdirs()
            val temp = File(dir, "$INSTALLED.tmp")
            temp.writeText(a.toString())
            temp.renameTo(File(dir, INSTALLED))
        } catch (_: IOException) {
            // Disco lleno: en memoria ya está; al abrir otra vez se adoptan los archivos que haya.
        }
    }

    companion object {
        private const val FOLDER = "map_packs"
        private const val INDEX = "index.json"
        private const val INSTALLED = "installed.json"
        private const val PART = ".part"
        private const val INDEX_URL = "https://raw.githubusercontent.com/IrvingGuerra/litedash-maps/main/index.json"
        private const val INDEX_MAX_AGE_MS = 24 * 60 * 60 * 1000L

        @SuppressLint("StaticFieldLeak") // solo el contexto de la aplicación
        @Volatile private var instance: MapPacks? = null

        fun get(context: Context): MapPacks =
            instance ?: synchronized(this) { instance ?: MapPacks(context.applicationContext).also { instance = it } }

        /** El índice de litedash-maps (versión 1); las regiones sin mapa o dañadas se saltan. */
        internal fun parseIndex(text: String): List<MapRegion> {
            val o = JSONObject(text)
            if (o.optInt("version") != 1) return emptyList()
            val base = o.getString("base")
            val list = o.getJSONArray("regions")
            return (0 until list.length()).mapNotNull { i ->
                runCatching {
                    val r = list.getJSONObject(i)
                    val n = r.getJSONObject("name")
                    val names = n.keys().asSequence().associateWith { n.getString(it) }
                    val m = r.getJSONObject("files").getJSONObject("map")
                    MapRegion(
                        r.getString("id"), r.getString("country"), names, box(r.getJSONArray("bbox")), rings(r.getJSONArray("shape")),
                        PackFile(base + m.getString("name"), m.getLong("size"), m.getString("sha256")),
                    )
                }.getOrNull()
            }
        }

        internal fun packToJson(p: InstalledPack): JSONObject {
            val shape = JSONArray()
            for (ring in p.shape) shape.put(JSONArray().apply { for (i in ring.indices step 2) put(JSONArray().put(ring[i]).put(ring[i + 1])) })
            return JSONObject().put("id", p.id).put("sha256", p.sha256).put("size", p.size).put("savedAt", p.savedAt)
                .put("bbox", JSONArray().apply { p.bbox.forEach { put(it) } }).put("shape", shape)
        }

        internal fun packFromJson(o: JSONObject) = InstalledPack(
            o.getString("id"), o.getString("sha256"), o.getLong("size"), o.getLong("savedAt"),
            box(o.getJSONArray("bbox")), rings(o.optJSONArray("shape") ?: JSONArray()),
        )

        private fun box(a: JSONArray) = DoubleArray(4) { a.getDouble(it) }

        private fun rings(a: JSONArray): List<DoubleArray> = (0 until a.length()).map { k ->
            val ring = a.getJSONArray(k)
            DoubleArray(ring.length() * 2) { j -> ring.getJSONArray(j / 2).getDouble(j % 2) }
        }
    }
}

/** ¿([lat], [lon]) dentro de la caja [bbox] y, si hay contorno [shape], dentro de alguno de sus anillos? */
internal fun insideShape(bbox: DoubleArray, shape: List<DoubleArray>, lat: Double, lon: Double): Boolean {
    if (lon < bbox[0] || lat < bbox[1] || lon > bbox[2] || lat > bbox[3]) return false
    return shape.isEmpty() || shape.any { insideRing(it, lon, lat) }
}

/** ¿(x, y) dentro del anillo [ring] (x, y, x, y…)? Par-impar. */
internal fun insideRing(ring: DoubleArray, x: Double, y: Double): Boolean {
    var inside = false
    val n = ring.size / 2
    var j = n - 1
    for (i in 0 until n) {
        val xi = ring[2 * i]; val yi = ring[2 * i + 1]
        val xj = ring[2 * j]; val yj = ring[2 * j + 1]
        if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
        j = i
    }
    return inside
}
