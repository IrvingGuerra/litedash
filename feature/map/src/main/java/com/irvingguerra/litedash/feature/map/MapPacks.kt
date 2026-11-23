package com.irvingguerra.litedash.feature.map

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.irvingguerra.litedash.core.ui.Https
import com.irvingguerra.litedash.core.ui.NoSpaceException
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
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
    /** El índice de búsqueda (SRCH1, con gzip); null en índices viejos. */
    val search: PackFile? = null,
    /** Las calles para la ruta (LDM0, con gzip); null en índices viejos. */
    val routes: PackFile? = null,
) {
    /** Lo que baja todo el estado: mapa, búsqueda y calles para la ruta. */
    val totalSize: Long get() = map.size + (search?.size ?: 0) + (routes?.size ?: 0)

    /** El nombre en [language] (o en inglés, o el id). */
    fun name(language: String): String = names[language] ?: names["en"] ?: id

    /** ¿([lat], [lon]) cae dentro del estado? Primero su caja; luego el contorno (par-impar). */
    fun contains(lat: Double, lon: Double): Boolean = insideShape(bbox, shape, lat, lon)
}

/** Un mapa a medias: de qué región y archivo (huella y tamaño) y desde cuándo. */
private class Pending(val id: String, val sha256: String, val size: Long, val since: Long)

/** El índice leído: sus regiones y si se permite buscar en internet (Photon). */
internal class PackIndex(val regions: List<MapRegion>, val photon: Boolean)

/** Un archivo del paquete: dónde está, cuánto pesa y su huella. */
class PackFile(val url: String, val size: Long, val sha256: String)

/**
 * Un mapa de estado ya bajado: de qué región y versión (su huella), y su caja y contorno (guardados al bajarlo: así sabe
 * qué teselas son suyas aunque no haya índice).
 */
data class InstalledPack(
    val id: String, val sha256: String, val size: Long, val savedAt: Long, val bbox: DoubleArray, val shape: List<DoubleArray>,
    /** La huella del índice de búsqueda bajado ("" = aún no). */
    val searchSha: String = "",
    /** La huella de las calles para la ruta bajadas ("" = aún no). */
    val routesSha: String = "",
) {
    fun contains(lat: Double, lon: Double) = insideShape(bbox, shape, lat, lon)
}

/** La lista de mapas (el índice): sin pedir o al día, bajando o falló la última vez. */
enum class IndexState { IDLE, LOADING, FAILED }

/** Por qué falló una descarga: la red (o el archivo llegó mal) o no cabe en la pantalla. */
enum class PackError { NETWORK, SPACE }

/** Bajando el mapa de [id]: cuánto va (0–1) o por qué falló ([error]). */
class PackJob(val id: String, val progress: Float, val error: PackError? = null) {
    val failed: Boolean get() = error != null
}

/**
 * Los mapas sin conexión por estado (M2 del Plan Mapas): el índice de github.com/IrvingGuerra/litedash-maps (un día en
 * caché), saber en qué estado estás, bajar el mapa de un estado (verificando su SHA-256), borrarlo y darle sus teselas
 * al mapa (antes que las zonas y que internet). Con el mapa se bajan su búsqueda y sus calles para la ruta.
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
    /** Cómo va la lista: sin ella, la pantalla dice si se está bajando o si falló (y deja reintentar). */
    var indexState by mutableStateOf(IndexState.IDLE)
        private set
    /** Una sola descarga de la lista a la vez. */
    private val indexBusy = AtomicBoolean(false)
    /** Para no leer la lista guardada dos veces a la vez (al arrancar y al pedirla). */
    private val indexLock = Any()

    /** Lo bajado, para las teselas (se lee sin candado desde los hilos del mapa). */
    @Volatile private var packs: List<InstalledPack> = emptyList()

    /** ¿Se permite buscar en internet (Photon)? Lo apaga el índice de litedash-maps si nos lo piden. */
    var onlineSearch by mutableStateOf(true)
        private set

    /** Los índices de búsqueda abiertos, uno por estado (con su candado, solo para abrir o cambiar uno: es corto). */
    private val indexes = HashMap<String, SearchIndex>()
    private val searchLock = Any()

    /** Las búsquedas se bajan de una en una (aparte: bajar tarda y no debe frenar a quien busca). */
    private val fetchLock = Any()

    init {
        scope.launch {
            // Lo que quedó a medias si la app se cerró bajando; el mapa que se puede seguir se queda (uno, unos días).
            val keep = resumable()?.let { "${it.id}.pmtiles$PART" }
            if (keep == null) File(dir, PENDING).delete()
            dir.listFiles { f -> f.name.endsWith(PART) && f.name != keep }?.forEach { it.delete() }
            readInstalled()
            // El índice de la caché: los nombres para la lista y el contorno de los adoptados (si no se lee, se volverá
            // a pedir: ver loadIndex).
            synchronized(indexLock) { if (regions.isEmpty()) readCachedIndex() }
        }
    }

    /** El estado donde estás (o null si el índice no lo trae o aún no llega). */
    fun regionAt(lat: Double, lon: Double): MapRegion? = regions.firstOrNull { it.contains(lat, lon) }

    /** ¿Hay mapa bajado del estado donde está ([lat], [lon])? */
    fun covers(lat: Double, lon: Double): Boolean = packs.any { it.contains(lat, lon) }

    /**
     * Baja el índice si el de la caché tiene más de [maxAgeMs] (un día) o [force]; sin red se queda con el de la caché.
     * Si ya se está bajando, no hace nada. El avance en [indexState].
     */
    fun refresh(force: Boolean = false, maxAgeMs: Long = INDEX_MAX_AGE_MS) {
        if (!indexBusy.compareAndSet(false, true)) return
        scope.launch {
            try {
                loadIndex(force, maxAgeMs)
            } finally {
                indexBusy.set(false)
            }
        }
    }

    private fun loadIndex(force: Boolean, maxAgeMs: Long) {
        val cached = File(dir, INDEX)
        // Una caché que no se pudo leer (dañada o sin memoria) no cuenta como al día: se vuelve a pedir.
        val readable = synchronized(indexLock) { regions.isNotEmpty() || readCachedIndex() }
        // Al día y de esta versión de la app (tras actualizarla se vuelve a pedir).
        val updated = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime }.getOrDefault(0L)
        if (!force && readable && indexFresh(cached.isFile, cached.lastModified(), updated, System.currentTimeMillis(), maxAgeMs)) {
            indexState = IndexState.IDLE
            return
        }
        indexState = IndexState.LOADING
        indexState = try {
            val text = fetchIndex()
            val index = readIndex(text) // dañado o de otra versión: no se guarda
            if (index.regions.isEmpty()) throw IOException("index")
            dir.mkdirs()
            File(dir, "$INDEX.tmp").apply { writeText(text) }.renameTo(cached)
            useIndex(index)
            IndexState.IDLE
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Sin internet, índice dañado o sin memoria para leerlo: se queda el de la caché (si hay).
            IndexState.FAILED
        }
    }

    /** El índice de GitHub; si esa red falla (bloqueada, limitada o un error del servidor), el de la copia. */
    private fun fetchIndex(): String = try {
        download(INDEX_URL)
    } catch (_: IOException) {
        download(INDEX_MIRROR_URL)
    }

    private fun download(url: String): String = Https.get(app, url).inputStream.use { it.readBytes().decodeToString() }

    /** Lee la lista guardada (una vez); false si no hay o no se pudo leer (dañada, de otra versión o sin memoria). */
    private fun readCachedIndex(): Boolean {
        val f = File(dir, INDEX)
        if (!f.isFile) return false
        val index = try {
            readIndex(f.readText())
        } catch (_: Throwable) {
            return false
        }
        if (index.regions.isEmpty()) return false
        useIndex(index)
        return true
    }

    /**
     * Usa el [index]: sus regiones, el interruptor de la búsqueda en línea, el contorno de los mapas adoptados y, a los
     * mapas bajados que aún no traen su búsqueda (o la traen vieja), se la baja sola.
     */
    private fun useIndex(index: PackIndex) {
        val list = index.regions
        regions = list
        onlineSearch = index.photon
        synchronized(stateLock) {
            if (installed.any { it.shape.isEmpty() }) {
                replaceInstalled(installed.map { p ->
                    val r = list.firstOrNull { it.id == p.id }
                    if (p.shape.isEmpty() && r != null) p.copy(bbox = r.bbox, shape = r.shape) else p
                })
            }
        }
        val missing = installed.mapNotNull { p -> list.firstOrNull { it.id == p.id && it.search != null && it.search.sha256 != p.searchSha } }
        if (missing.isNotEmpty()) scope.launch { for (r in missing) runCatching { fetchExtra(r, Extra.SEARCH, { true }) } }
    }

    /** ¿El mapa bajado de [region] es de una versión vieja? */
    fun outdated(region: MapRegion): Boolean = installed.firstOrNull { it.id == region.id }?.let { it.sha256.isNotEmpty() && it.sha256 != region.map.sha256 } ?: false

    /** Baja (o actualiza) el mapa de [region]; uno a la vez. Se verifica su huella antes de usarlo. */
    fun download(region: MapRegion) = synchronized(stateLock) {
        if (running?.isActive == true) return@synchronized
        job = PackJob(region.id, 0f)
        running = scope.launch {
            val me = coroutineContext.job
            val temp = File(dir, "${region.id}.pmtiles$PART")
            // ¿Lo que quedó a medias es de este mismo archivo? Entonces se sigue donde iba; si no, de cero.
            val resume = resumable()?.let { it.id == region.id && it.sha256.equals(region.map.sha256, ignoreCase = true) } == true
            var keepPart = false
            try {
                dir.mkdirs()
                if (!resume) {
                    temp.delete()
                    writePending(Pending(region.id, region.map.sha256, region.map.size, System.currentTimeMillis()))
                }
                val have = if (resume && temp.isFile) temp.length() else 0L
                if (dir.usableSpace < spaceNeeded(region, have)) {
                    job = PackJob(region.id, 0f, PackError.SPACE)
                    return@launch
                }
                // El avance cuenta todo el paquete (mapa, búsqueda y calles), por su tamaño.
                val total = region.totalSize.coerceAtLeast(1).toFloat()
                val mapShare = region.map.size / total
                var shown = (have.toFloat() / region.map.size.coerceAtLeast(1)) * mapShare
                job = PackJob(region.id, shown)
                // Si se corta la red, lo bajado se queda para seguir después (también tras cerrar la app).
                keepPart = true
                val sha = Https.download(app, region.map.url, temp, region.map.size, null, { mapP ->
                    val p = mapP * mapShare
                    // La descarga no se suspende: aquí se nota que la cancelaron.
                    if (!me.isActive) throw CancellationException()
                    // El avance cada 1 % (no en cada bloque: serían miles de redibujos); si volvió a empezar, se dice.
                    if (p - shown >= 0.01f || p < shown || p >= 1f) {
                        shown = p
                        job = PackJob(region.id, p)
                    }
                }, resume = resume)
                keepPart = false
                if (!sha.equals(region.map.sha256, ignoreCase = true)) throw IOException("SHA-256")
                if (!me.isActive) throw CancellationException()
                val target = File(dir, "${region.id}.pmtiles")
                synchronized(openLock) {
                    open.remove(region.id)?.close()
                    if (!temp.renameTo(target)) throw IOException("rename")
                }
                // Si su búsqueda o sus calles no cambiaron, se quedan las que ya están (no se vuelven a bajar).
                val before = installed.firstOrNull { it.id == region.id }
                fun keep(have: String?, file: PackFile?) = have?.takeIf { it.isNotEmpty() && it.equals(file?.sha256, ignoreCase = true) }.orEmpty()
                val pack = InstalledPack(
                    region.id, sha, target.length(), System.currentTimeMillis(), region.bbox, region.shape,
                    keep(before?.searchSha, region.search), keep(before?.routesSha, region.routes),
                )
                synchronized(stateLock) { replaceInstalled(installed.filter { it.id != region.id } + pack) }
                // Con el mapa, su búsqueda y sus calles para la ruta; si algo falla, la búsqueda se reintenta sola y las
                // calles al pedir una ruta.
                var done = region.map.size
                for (extra in Extra.entries) {
                    val size = extra.file(region)?.size ?: continue
                    val from = done / total
                    runCatching { fetchExtra(region, extra, { me.isActive }) { p -> job = PackJob(region.id, from + p * size / total) } }
                    done += size
                }
                job = null
            } catch (e: CancellationException) {
                keepPart = false
                job = null
                throw e
            } catch (e: NoSpaceException) {
                keepPart = false
                job = PackJob(region.id, 0f, PackError.SPACE)
            } catch (_: Exception) {
                job = PackJob(region.id, 0f, PackError.NETWORK)
            } finally {
                if (!keepPart) {
                    temp.delete()
                    File(dir, PENDING).delete()
                }
                synchronized(stateLock) { if (running === me) running = null }
            }
        }
    }

    /** El mapa que quedó a medias y se puede seguir (de qué región y archivo), o null si no hay o es muy viejo. */
    private fun resumable(): Pending? {
        val p = runCatching { JSONObject(File(dir, PENDING).readText()) }.getOrNull()?.let {
            Pending(it.getString("id"), it.getString("sha256"), it.getLong("size"), it.getLong("since"))
        } ?: return null
        val part = File(dir, "${p.id}.pmtiles$PART")
        return p.takeIf { part.isFile && keepPartial(part.length(), p.size, p.since, System.currentTimeMillis()) }
    }

    private fun writePending(p: Pending) {
        val temp = File(dir, "$PENDING.tmp")
        temp.writeText(JSONObject().put("id", p.id).put("sha256", p.sha256).put("size", p.size).put("since", p.since).toString())
        temp.renameTo(File(dir, PENDING))
    }

    /** Detiene la descarga: deja de escribir al siguiente bloque y no instala nada. */
    fun cancel() = synchronized(stateLock) {
        running?.cancel()
        job = null
    }

    /** Borra el mapa bajado de [id] y su búsqueda (lo pidió el usuario); si se estaba bajando, se detiene. */
    fun delete(id: String) {
        synchronized(stateLock) {
            if (job?.id == id) {
                running?.cancel()
                job = null
            }
        }
        scope.launch {
            synchronized(stateLock) { replaceInstalled(installed.filter { it.id != id }) }
            synchronized(openLock) {
                open.remove(id)?.close()
                File(dir, "$id.pmtiles").delete()
            }
            synchronized(searchLock) {
                indexes.remove(id)?.close()
                File(dir, "$id.srch").delete()
            }
            synchronized(routeLock) {
                if (routerId == id) releaseRouterNow()
                File(dir, "$id.ldm").delete()
            }
        }
    }

    /** Lo que baja aparte del mapa: la búsqueda y las calles para la ruta (cada una con su huella). */
    private enum class Extra(val suffix: String) {
        SEARCH("srch"), ROUTES("ldm");

        fun file(r: MapRegion) = if (this == SEARCH) r.search else r.routes
        fun sha(p: InstalledPack) = if (this == SEARCH) p.searchSha else p.routesSha
        fun with(p: InstalledPack, sha: String) = if (this == SEARCH) p.copy(searchSha = sha) else p.copy(routesSha = sha)
    }

    /**
     * Baja [extra] de [region] (con gzip), revisa su huella, lo descomprime y lo deja junto a su mapa. Uno a la vez;
     * [active] = false lo detiene (cancelaron). [progress] = cuánto va (0–1). Corre fuera del hilo principal.
     */
    private fun fetchExtra(region: MapRegion, extra: Extra, active: () -> Boolean, progress: ((Float) -> Unit)? = null) = synchronized(fetchLock) {
        val file = extra.file(region) ?: return@synchronized
        // Ya lo bajó otro (al arrancar se pide con el índice guardado y otra vez con el nuevo) o borraron el estado.
        val pack = installed.firstOrNull { it.id == region.id } ?: return@synchronized
        val final = File(dir, "${region.id}.${extra.suffix}")
        if (extra.sha(pack).equals(file.sha256, ignoreCase = true) && final.isFile) return@synchronized
        val gz = File(dir, "${region.id}.${extra.suffix}.gz$PART")
        val raw = File(dir, "${region.id}.${extra.suffix}$PART")
        try {
            var shown = 0f
            val sha = Https.download(app, file.url, gz, file.size, null, { p ->
                if (!active()) throw CancellationException()
                if (progress != null && (p - shown >= 0.01f || p < shown || p >= 1f)) {
                    shown = p
                    progress(p)
                }
            })
            if (!sha.equals(file.sha256, ignoreCase = true)) throw IOException("SHA-256")
            java.util.zip.GZIPInputStream(gz.inputStream()).use { input -> raw.outputStream().use { input.copyTo(it) } }
            if (!active() || installed.none { it.id == region.id }) throw CancellationException()
            val lock = if (extra == Extra.SEARCH) searchLock else routeLock
            synchronized(lock) {
                if (extra == Extra.SEARCH) indexes.remove(region.id)?.close() else if (routerId == region.id) releaseRouterNow()
                if (!raw.renameTo(final)) throw IOException("rename")
            }
            synchronized(stateLock) { replaceInstalled(installed.map { if (it.id == region.id) extra.with(it, sha) else it }) }
        } finally {
            gz.delete()
            raw.delete()
        }
    }

    /** El ruteador abierto (uno: sus arreglos pesan ~16 B por cruce) y de qué estado es. */
    private val routeLock = Any()
    private var routerId: String? = null
    private var graph: RoadGraph? = null
    private var router: Router? = null

    /** Bajando las calles para la ruta de un estado (al pedir la primera ruta): cuánto va o si falló. */
    var routesJob by mutableStateOf<PackJob?>(null)
        private set

    /**
     * Qué se puede hacer para ir de ([fromLat], [fromLon]) a ([toLat], [toLon]) con lo bajado: la ruta, bajar antes las
     * calles de tu estado, o nada (algún punto está fuera de tus estados bajados). Con [headingDeg] (al recalcular
     * manejando) la ruta no sale en sentido contrario a tu rumbo si hay cómo rodear. Fuera del hilo principal.
     */
    fun planRoute(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, headingDeg: Float? = null): RoutePlan = try {
        plan(fromLat, fromLon, toLat, toLon, headingDeg)
    } catch (e: RouteException) {
        // Las calles del estado no caben en la memoria de esta pantalla (no están dañadas: no se vuelven a bajar).
        synchronized(routeLock) { releaseRouterNow() }
        RoutePlan.Failed(e.error)
    } catch (_: OutOfMemoryError) {
        // Sin memoria para abrir el ruteador (sus arreglos): el estado no cabe en esta pantalla, sin tumbar la app.
        synchronized(routeLock) { releaseRouterNow() }
        RoutePlan.Failed(RouteError.TOO_BIG)
    } catch (_: Throwable) {
        // Un archivo raro por dentro: sin ruta, sin tumbar la app.
        synchronized(routeLock) { releaseRouterNow() }
        RoutePlan.Failed(RouteError.NO_PATH)
    }

    private fun plan(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, headingDeg: Float?): RoutePlan {
        val pack = installed.firstOrNull { it.contains(fromLat, fromLon) && it.contains(toLat, toLon) }
            ?: return when {
                installed.none { it.contains(fromLat, fromLon) } -> RoutePlan.NoState(regionAt(fromLat, fromLon))
                // Los dos estados bajados: lo que falta es la ruta entre estados (llega con los viajes largos).
                installed.any { it.contains(toLat, toLon) } -> RoutePlan.TwoStates
                else -> RoutePlan.OtherState(regionAt(toLat, toLon))
            }.also {
                // Sin la lista no se sabe qué estado falta: se pide (al llegar, la ruta se vuelve a planear).
                if (regions.isEmpty() && it !is RoutePlan.TwoStates) refresh()
            }
        if (pack.routesSha.isEmpty() || !File(dir, "${pack.id}.ldm").isFile) {
            // Sin el índice aún (sin red al abrir): se pide; mientras, no se sabe de dónde bajarlas.
            val region = regions.firstOrNull { it.id == pack.id && it.routes != null } ?: return RoutePlan.Failed(RouteError.NO_PATH).also { refresh() }
            return RoutePlan.NeedsRoutes(region)
        }
        return synchronized(routeLock) {
            if (routerId != pack.id) {
                // El de otro estado se suelta ya (con el candado tomado; la versión que espera el candado cerraría el nuevo).
                releaseRouterNow()
                // No abre (dañado): se olvida su huella para que se vuelvan a bajar.
                val g = RoadGraph.open(File(dir, "${pack.id}.ldm")) ?: run {
                    File(dir, "${pack.id}.ldm").delete()
                    synchronized(stateLock) { replaceInstalled(installed.map { if (it.id == pack.id) it.copy(routesSha = "") else it }) }
                    return@synchronized regions.firstOrNull { it.id == pack.id && it.routes != null }?.let { RoutePlan.NeedsRoutes(it) }
                        ?: RoutePlan.Failed(RouteError.NO_PATH)
                }
                graph = g
                router = Router(g)
                routerId = pack.id
            }
            router!!.route(fromLat, fromLon, toLat, toLon, headingDeg).fold(
                { RoutePlan.Found(it.rounded()) },
                { RoutePlan.Failed((it as? RouteException)?.error ?: RouteError.NO_PATH) },
            )
        }
    }

    /** Baja las calles para la ruta de [region] (lo pidió el usuario al pedir una ruta); el avance en [routesJob]. */
    fun downloadRoutes(region: MapRegion) {
        if (routesJob?.let { !it.failed && it.id == region.id } == true) return
        routesJob = PackJob(region.id, 0f)
        scope.launch {
            try {
                fetchExtra(region, Extra.ROUTES, { true }) { routesJob = PackJob(region.id, it) }
                routesJob = null
            } catch (_: NoSpaceException) {
                routesJob = PackJob(region.id, 0f, PackError.SPACE)
            } catch (_: Exception) {
                routesJob = PackJob(region.id, 0f, PackError.NETWORK)
            }
        }
    }

    /** Suelta el ruteador (memoria) al cerrar el mapa en grande; fuera del hilo principal (puede estar calculando). */
    fun releaseRouter() {
        scope.launch { synchronized(routeLock) { releaseRouterNow() } }
    }

    /** Suelta el ruteador ya con [routeLock] tomado. */
    private fun releaseRouterNow() {
        router = null
        graph?.close()
        graph = null
        routerId = null
    }

    /** ¿Ya se puede buscar sin internet en algún estado bajado? */
    fun canSearch(): Boolean = installed.any { it.searchSha.isNotEmpty() }

    /**
     * Busca [query] en la búsqueda de tus estados bajados, cerca de ([lat], [lon]). Fuera del hilo principal. Con
     * varios estados, se juntan en su orden (exactas primero, luego lo cercano según lo importante). Un índice que
     * falla (dañado) se suelta y se olvida su huella: se vuelve a bajar.
     */
    fun searchLocal(query: String, lat: Double?, lon: Double?, limit: Int = 20): List<SearchHit> {
        val ids = installed.filter { it.searchSha.isNotEmpty() }.map { it.id }
        val lists = ids.mapNotNull { id ->
            val index = synchronized(searchLock) { indexes.getOrPut(id) { SearchIndex.open(File(dir, "$id.srch")) ?: return@mapNotNull null } }
            try {
                index.search(query, lat, lon, limit)
            } catch (_: Exception) {
                synchronized(searchLock) { if (indexes[id] === index) indexes.remove(id)?.close() }
                synchronized(stateLock) { replaceInstalled(installed.map { if (it.id == id) it.copy(searchSha = "") else it }) }
                null
            }
        }
        return if (lists.size <= 1) lists.firstOrNull().orEmpty() else lists.flatten().sortedBy { it.order }.take(limit)
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
        /** De qué región y archivo es el `.part` del mapa a medias (para seguirlo tras cerrar la app). */
        private const val PENDING = "pending.json"
        /** Un mapa a medias se sigue hasta unos días después; luego se borra (no dejar basura de cientos de MB). */
        private const val PARTIAL_MAX_AGE_MS = 3 * 24 * 60 * 60 * 1000L
        /** Lo que se deja libre de más al bajar un estado (la búsqueda y las calles ocupan más ya descomprimidas). */
        private const val SPACE_MARGIN = 64L * 1024 * 1024
        /** La búsqueda y las calles descomprimidas pesan ~3 veces su .gz, y el .gz convive con ellas un momento. */
        private const val EXTRA_SPACE_FACTOR = 4
        // Desde la 1.5.2, todos los países (EUA trae estados con millones de cruces: el ruteador de antes no los aguanta en
        // pantallas de 1 GB, así que index.json se queda solo con México para las versiones anteriores).
        private const val INDEX_URL = "https://raw.githubusercontent.com/IrvingGuerra/litedash-maps/main/regions.json"
        // La misma lista por otra red (jsDelivr), para donde raw.githubusercontent.com está bloqueado o limitado; puede ir
        // hasta 12 h atrás.
        private const val INDEX_MIRROR_URL = "https://cdn.jsdelivr.net/gh/IrvingGuerra/litedash-maps@main/regions.json"
        private const val INDEX_MAX_AGE_MS = 24 * 60 * 60 * 1000L
        /** En Ajustes → Sin conexión la lista se pide si tiene más de una hora (ahí se buscan los países nuevos). */
        const val INDEX_SCREEN_MAX_AGE_MS = 60 * 60 * 1000L

        @SuppressLint("StaticFieldLeak") // solo el contexto de la aplicación
        @Volatile private var instance: MapPacks? = null

        /** Para el reporte técnico, sin abrir nada: cuántas regiones trae la lista y cuáles están bajadas. */
        fun describe(): String = instance?.let { p ->
            val cache = File(p.dir, INDEX)
            val state = when (p.indexState) {
                IndexState.LOADING -> " (bajando)"
                IndexState.FAILED -> " (falló)"
                // Sin lista y sin caché: aún no se ha pedido (no es un error).
                IndexState.IDLE -> if (p.regions.isEmpty() && !cache.isFile) " (sin pedir)" else ""
            }
            val age = if (cache.isFile) ", caché de hace ${(System.currentTimeMillis() - cache.lastModified()) / 3_600_000} h" else ""
            "Mapas: lista=${p.regions.size} regiones$state$age (${p.regions.map { it.country }.distinct().joinToString()}); bajados=" +
                p.installed.joinToString { it.id }.ifEmpty { "ninguno" }
        } ?: "Mapas: sin abrir en esta sesión"

        /** ¿Se sigue un `.part` de [have] bytes de un archivo de [size] empezado en [since]? (Sano, no completo de más y reciente.) */
        internal fun keepPartial(have: Long, size: Long, since: Long, now: Long): Boolean =
            have in 1..size && now - since in 0 until PARTIAL_MAX_AGE_MS

        /** Cuánto espacio libre hace falta para bajar [region] con [have] bytes del mapa ya bajados. */
        internal fun spaceNeeded(region: MapRegion, have: Long): Long =
            (region.map.size - have).coerceAtLeast(0) + EXTRA_SPACE_FACTOR * ((region.search?.size ?: 0) + (region.routes?.size ?: 0)) + SPACE_MARGIN

        fun get(context: Context): MapPacks =
            instance ?: synchronized(this) { instance ?: MapPacks(context.applicationContext).also { instance = it } }

        /**
         * ¿La lista guardada ([exists], guardada en [savedAt]) sirve todavía? Si tiene menos de [maxAgeMs] y es de después de
         * actualizar la app ([appUpdatedAt]); con el reloj hacia atrás (guardada "en el futuro"), no.
         */
        internal fun indexFresh(exists: Boolean, savedAt: Long, appUpdatedAt: Long, now: Long, maxAgeMs: Long): Boolean =
            exists && now - savedAt in 0 until maxAgeMs && savedAt > appUpdatedAt

        /** Las regiones del índice [text] (ver [readIndex]). */
        internal fun parseIndex(text: String): List<MapRegion> = readIndex(text).regions

        /**
         * El índice de litedash-maps (versión 1), analizado una sola vez: sus regiones (las sin mapa o dañadas se saltan) y
         * el interruptor de la búsqueda en línea ("online": {"photon": false} la apaga). De otra versión: sin regiones.
         */
        internal fun readIndex(text: String): PackIndex {
            val o = JSONObject(text)
            val photon = o.optJSONObject("online")?.optBoolean("photon", true) ?: true
            if (o.optInt("version") != 1) return PackIndex(emptyList(), photon)
            val base = o.getString("base")
            val list = o.getJSONArray("regions")
            return PackIndex((0 until list.length()).mapNotNull { i ->
                runCatching {
                    val r = list.getJSONObject(i)
                    val n = r.getJSONObject("name")
                    val names = n.keys().asSequence().associateWith { n.getString(it) }
                    val files = r.getJSONObject("files")
                    fun file(o: JSONObject) = PackFile(base + o.getString("name"), o.getLong("size"), o.getString("sha256"))
                    MapRegion(
                        r.getString("id"), r.getString("country"), names, box(r.getJSONArray("bbox")), rings(r.getJSONArray("shape")),
                        file(files.getJSONObject("map")), files.optJSONObject("search")?.let(::file), files.optJSONObject("routes")?.let(::file),
                    )
                }.getOrNull()
            }, photon)
        }

        internal fun packToJson(p: InstalledPack): JSONObject {
            val shape = JSONArray()
            for (ring in p.shape) shape.put(JSONArray().apply { for (i in ring.indices step 2) put(JSONArray().put(ring[i]).put(ring[i + 1])) })
            return JSONObject().put("id", p.id).put("sha256", p.sha256).put("size", p.size).put("savedAt", p.savedAt)
                .put("bbox", JSONArray().apply { p.bbox.forEach { put(it) } }).put("shape", shape).put("searchSha", p.searchSha)
                .put("routesSha", p.routesSha)
        }

        internal fun packFromJson(o: JSONObject) = InstalledPack(
            o.getString("id"), o.getString("sha256"), o.getLong("size"), o.getLong("savedAt"),
            box(o.getJSONArray("bbox")), rings(o.optJSONArray("shape") ?: JSONArray()), o.optString("searchSha"),
            o.optString("routesSha"),
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
