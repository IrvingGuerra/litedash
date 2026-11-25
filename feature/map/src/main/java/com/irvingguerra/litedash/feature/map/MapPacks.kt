package com.irvingguerra.litedash.feature.map

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.irvingguerra.litedash.core.ui.HttpStatusException
import com.irvingguerra.litedash.core.ui.Https
import com.irvingguerra.litedash.core.ui.NoSpaceException
import com.irvingguerra.litedash.core.ui.isRecent
import java.io.File
import java.io.FileOutputStream
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

/** Un estado o región por su nombre (para la lista de países y el buscador de estados). */
interface NamedRegion {
    val id: String
    /** Su país (ISO de dos letras). */
    val country: String

    /** El nombre en [language] (o en inglés, o el id). */
    fun name(language: String): String
}

/** Un estado del índice por país del que solo se sabe el nombre (sus archivos vienen en el archivo de su país). */
class RegionName(override val id: String, override val country: String, private val names: Map<String, String>) : NamedRegion {
    override fun name(language: String): String = names[language] ?: names["en"] ?: id
}

/**
 * Un país del índice por país (`countries.json`): su caja (para saber si estás en él), sus regiones por nombre y su
 * archivo (`countries/<cc>.json`, con el mismo esquema que `regions.json`) con su huella.
 */
class PackCountry(
    val code: String, val bbox: DoubleArray, val regions: List<RegionName>, internal val file: String, internal val sha256: String,
    /** Lo que pesan todos sus estados juntos (mapa, búsqueda y calles); 0 si el índice no lo dice. */
    val total: Long = 0,
) {
    /** ¿([lat], [lon]) cae en su caja? (La caja de un país abarca pedazos de sus vecinos.) */
    fun covers(lat: Double, lon: Double): Boolean = lon >= bbox[0] && lat >= bbox[1] && lon <= bbox[2] && lat <= bbox[3]
}

/** El índice por país leído: sus países y si se permite buscar (Photon) y trazar rutas (OSRM) en internet. */
internal class CountryIndex(val countries: List<PackCountry>, val photon: Boolean, val osrm: Boolean)

/** Un estado o región del índice de litedash-maps: su nombre, su contorno y el archivo del mapa. */
class MapRegion(
    override val id: String,
    override val country: String,
    private val names: Map<String, String>,
    val bbox: DoubleArray,
    /** Anillos exteriores: lon, lat, lon, lat… */
    val shape: List<DoubleArray>,
    val map: PackFile,
    /** El índice de búsqueda (SRCH1, con gzip); null en índices viejos. */
    val search: PackFile? = null,
    /** Las calles para la ruta (LDM0, o LDM1 con dibujo en el formato 2; con gzip); null en índices viejos. */
    val routes: PackFile? = null,
    /**
     * 1 = el mapa trae sus calles y el `.ldm` solo sirve para la ruta; 2 = el mapa sin calles: se dibujan del `.ldm`
     * (LDM1 con dibujo) y los tres archivos bajan juntos como un solo paquete.
     */
    val format: Int = 1,
) : NamedRegion {
    /** Lo que baja todo el estado: mapa, búsqueda y calles para la ruta. */
    val totalSize: Long get() = map.size + (search?.size ?: 0) + (routes?.size ?: 0)

    /** Qué versión del paquete es (lo que dice de qué es un `.part` a medias): la huella del mapa y, en el 2, las tres. */
    val packKey: String get() = if (format < 2) map.sha256 else "${map.sha256}+${search?.sha256}+${routes?.sha256}"

    override fun name(language: String): String = names[language] ?: names["en"] ?: id

    /** ¿([lat], [lon]) cae dentro del estado? Primero su caja; luego el contorno (par-impar). */
    fun contains(lat: Double, lon: Double): Boolean = insideShape(bbox, shape, lat, lon)
}

/** Por qué un mapa del formato 2 se ve sin sus calles (se usan las de las zonas o de internet). */
enum class StreetsGap {
    /** Faltan y no se bajan solas (red que se cobra por dato o ya se intentó): se ofrece «Bajar calles». */
    OFFER,
    /** Faltan y no caben en el espacio libre. */
    NO_ROOM,
    /** Están, pero no caben en la memoria de la pantalla. */
    MEMORY,
}

/** Las calles que se piden solas a un mapa ([MapPacks.askRoutes]): intentos de esta sesión, el último y la última revisión. */
private class RoutesTry {
    var tries = 0
    var lastAt = 0L
    var checkedAt = 0L
    var busy = false
}

/** Un mapa a medias: de qué región y archivo (huella y tamaño) y desde cuándo. */
private class Pending(val id: String, val sha256: String, val size: Long, val since: Long)

/** Un mapa a medias que se puede seguir: de qué región y archivo ([sha256]) y cuántos [bytes] lleva. */
class PausedPack(val id: String, val sha256: String, val bytes: Long)

/** El índice leído: sus regiones y si se permite buscar (Photon) y trazar rutas (OSRM) en internet. */
internal class PackIndex(val regions: List<MapRegion>, val photon: Boolean, val osrm: Boolean)

/** Un archivo del paquete: dónde está, cuánto pesa, su huella y (los de gzip, si el índice lo dice) cuánto pesa abierto. */
class PackFile(val url: String, val size: Long, val sha256: String, val unpacked: Long = 0)

/** Una tesela de un mapa bajado: tal como está guardada y, en los del formato nuevo, de dónde salen sus calles. */
class PackTile(val body: ByteArray, val roads: RoadGraph?)

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
    /** El formato del paquete ([MapRegion.format]): en el 2 las calles se dibujan del `.ldm`. */
    val format: Int = 1,
) {
    fun contains(lat: Double, lon: Double) = insideShape(bbox, shape, lat, lon)
}

/** La lista de mapas (el índice): sin pedir o al día, bajando o falló la última vez. */
enum class IndexState { IDLE, LOADING, FAILED }

/** Por qué falló una descarga: la red (o el archivo llegó mal) o no cabe en la pantalla. */
enum class PackError { NETWORK, SPACE }

/**
 * Bajando un país completo ([MapPacks.downloadCountry]): de qué país, cuál va ([at], desde 1) de [count] estados que
 * faltaban y cuál ([regionId]); con [error], se detuvo ahí (Reintentar sigue desde ese).
 */
class CountryBatch(val code: String, val at: Int, val count: Int, val regionId: String, val error: PackError? = null)

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
 *
 * El índice va **por país** (decisión 29 de la auditoría 1.5.15; `regions.json` ya pesaba 1.46 MB): `countries.json`
 * (chico: países, sus cajas y los nombres de sus regiones) y un archivo por país con sus regiones completas, que se baja
 * solo cuando hace falta: el de donde estás, el de los mapas que ya tienes, el de la región del sistema y el que tocas en
 * la lista (cada uno guardado por su huella: si no cambió, no se vuelve a bajar). Si `countries.json` no existe o no se
 * puede bajar ni leer, se usa `regions.json` como en la 1.5.2–1.5.15 (todas las regiones de una vez).
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
    /**
     * Los archivos de ruta abiertos (un solo mmap por estado, lo comparten la ruta y el dibujo de las calles del formato 2),
     * del menos al más usado: a lo más [MAX_GRAPHS] (y, en procesos de 32 bits, [GRAPH_BUDGET_32] bytes). Con [openLock];
     * se olvidan al cambiar el mapa o sus calles. [unreadable] = cuándo no abrió cada uno (las teselas no lo reintentan
     * hasta [GRAPH_RETRY_MS] después); [corrupt] = los que no abrieron por dañados (se vuelven a bajar).
     */
    private val graphs = LinkedHashMap<String, RoadGraph>(4, 0.75f, true)
    private val unreadable = HashMap<String, Long>()
    private val corrupt = HashSet<String>()
    private val openLock = Any()
    /** Hasta cuánto pesan juntos los archivos de ruta mapeados en este proceso. */
    private val graphBudget = if (android.os.Process.is64Bit()) Long.MAX_VALUE else GRAPH_BUDGET_32
    /** Las calles que se piden solas a un mapa del formato 2 que no las tiene ([askRoutes]), por estado. */
    private val routesAsked = HashMap<String, RoutesTry>()

    /** Los mapas del formato 2 que se ven sin sus calles y por qué (la pantalla lo dice y ofrece bajarlas). */
    var streetsGaps by mutableStateOf<Map<String, StreetsGap>>(emptyMap())
        private set

    /** Las regiones con todos sus datos que ya se tienen (con el índice por país, solo las de los países cargados). */
    var regions by mutableStateOf<List<MapRegion>>(emptyList())
        private set
    /** Todas las regiones por su nombre (de todos los países): la lista de países y el buscador de estados. */
    var catalog by mutableStateOf<List<NamedRegion>>(emptyList())
        private set
    /** Cómo va el archivo de cada país (sin entrada = aún no se pide). Solo con el índice por país. */
    var countryState by mutableStateOf<Map<String, IndexState>>(emptyMap())
        private set
    var installed by mutableStateOf<List<InstalledPack>>(emptyList())
        private set
    var job by mutableStateOf<PackJob?>(null)
        private set
    /** El país que se está bajando completo (o que se detuvo con un error); null si ninguno. */
    var batch by mutableStateOf<CountryBatch?>(null)
        private set
    /** La cola del país completo: vive aquí (no en la pantalla), así sigue al cerrar Ajustes mientras LiteDash viva. */
    private var batchJob: Job? = null

    /** ¿Se está bajando algo (un estado o un país completo)? Mientras, no se empieza otra descarga. */
    val busy: Boolean get() = job?.failed == false || batch?.let { it.error == null } == true
    /** El mapa a medias que se puede seguir (de qué región, archivo y cuánto lleva); null si no hay. Sin leer disco. */
    var paused by mutableStateOf<PausedPack?>(null)
        private set
    /** Cómo va la lista: sin ella, la pantalla dice si se está bajando o si falló (y deja reintentar). */
    var indexState by mutableStateOf(IndexState.IDLE)
        private set
    /** Una sola descarga de la lista a la vez. */
    private val indexBusy = AtomicBoolean(false)
    /** Para no leer la lista guardada dos veces a la vez (al arrancar y al pedirla). */
    private val indexLock = Any()

    /** El índice por país (null = aún no, o se usa `regions.json`). */
    @Volatile private var countries: List<PackCountry>? = null
    /** Las regiones de cada país cargado, la huella de su archivo y los pedidos en curso (con [countryLock], un momento). */
    private val loaded = HashMap<String, List<MapRegion>>()
    private val loadedSha = HashMap<String, String>()
    private val asked = HashSet<String>()
    private val countryLock = Any()
    /** Un archivo de país a la vez (bajarlo o leerlo de la caché tarda: aparte de [countryLock]). */
    private val countryLoadLock = Any()

    /** Lo bajado, para las teselas (se lee sin candado desde los hilos del mapa). */
    @Volatile private var packs: List<InstalledPack> = emptyList()

    /** ¿Se permite buscar en internet (Photon)? Lo apaga el índice de litedash-maps si nos lo piden. */
    var onlineSearch by mutableStateOf(true)
        private set

    /** ¿Se permite pedir la ruta al servidor cuando no hay mapa bajado ([OnlineRoute])? Lo apaga el índice igual que Photon. */
    @Volatile var onlineRouting = true
        private set

    /** Lo que permite cada índice (por país y `regions.json`): basta que uno apague algo. */
    private val switches = OnlineSwitches()

    /** Los índices de búsqueda abiertos, uno por estado (con su candado, solo para abrir o cambiar uno: es corto). */
    private val indexes = HashMap<String, SearchIndex>()
    private val searchLock = Any()

    /** Las búsquedas se bajan de una en una (aparte: bajar tarda y no debe frenar a quien busca). */
    private val fetchLock = Any()

    init {
        scope.launch {
            // Lo que quedó a medias si la app se cerró bajando; el mapa que se puede seguir se queda (uno, unos días).
            val pending = resumable()
            val keep = pending?.let { p -> partFiles(p.id).map { it.name } }.orEmpty()
            if (pending == null) File(dir, PENDING).delete()
            synchronized(stateLock) { if (running == null) paused = pending?.let { pausedOf(it) } }
            dir.listFiles { f -> f.name.endsWith(PART) && f.name !in keep }?.forEach { it.delete() }
            readInstalled()
            // El índice de la caché: los nombres para la lista y el contorno de los adoptados (si no se lee, se volverá
            // a pedir: ver loadIndex). Primero el índice por país; si no hay, `regions.json`.
            synchronized(indexLock) { if (catalog.isEmpty()) readCachedCountries() || readCachedIndex() }
        }
    }

    /**
     * El estado donde estás (o null si el índice no lo trae o aún no llega). Con el índice por país, si tu país aún no se
     * carga se pide (sin reintentar uno que falló: eso lo hace Reintentar o la siguiente lista) y al llegar cambia
     * [regions]. Se puede llamar al dibujar: ahí mismo no escribe estado.
     */
    fun regionAt(lat: Double, lon: Double): MapRegion? = regions.firstOrNull { it.contains(lat, lon) } ?: run {
        countries?.filter { it.covers(lat, lon) }?.forEach { need(it.code, retry = false) }
        null
    }

    /**
     * ¿Se sabe que en ([lat], [lon]) no hay mapa de estado? Solo con la lista y, con el índice por país, ya cargados los
     * países cuya caja lo cubre (si falta alguno se pide y, mientras, no se sabe: false).
     */
    fun knownOutside(lat: Double, lon: Double): Boolean {
        if (catalog.isEmpty() || regionAt(lat, lon) != null) return false
        val all = countries ?: return true
        return all.none { it.covers(lat, lon) && countryState[it.code] != IndexState.IDLE }
    }

    /**
     * Pide el archivo del país [code] (índice por país) si aún no está o cambió; el avance en [countryState] y, al
     * llegar, sus regiones en [regions]. Con [retry] = false no vuelve a intentar uno que falló. Con `regions.json` ya
     * están todas: no hace nada.
     */
    fun need(code: String, retry: Boolean = true) {
        val country = countries?.firstOrNull { it.code == code } ?: return
        synchronized(countryLock) {
            if (loadedSha[code] == country.sha256 || code in asked) return
            if (!retry && countryState[code] == IndexState.FAILED) return
            asked += code
        }
        scope.launch {
            try {
                fetchCountries(listOf(country))
            } finally {
                synchronized(countryLock) { asked -= code }
            }
        }
    }

    /** Cambia lo que se muestra de cómo va el país [code]. */
    private fun setCountryState(code: String, state: IndexState) = synchronized(countryLock) {
        countryState = countryState + (code to state)
    }

    /**
     * Carga (de la caché o, con [network], de la red) los [list] y publica sus regiones; uno que no se pudo bajar queda
     * FAILED. Fuera del hilo principal.
     */
    private fun fetchCountries(list: List<PackCountry>, network: Boolean = true) {
        var changed = false
        for (c in list) {
            val ok = try {
                synchronized(countryLoadLock) { loadCountry(c, network) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                false
            }
            // Sin red (al arrancar) y sin caché: aún no se pidió, no es un error.
            if (ok || network) setCountryState(c.code, if (ok) IndexState.IDLE else IndexState.FAILED)
            changed = changed || ok
        }
        if (changed) publishCountries()
    }

    /**
     * Deja listas las regiones del país [c] (con [countryLoadLock]): si ya están con esta huella, nada; si la copia
     * guardada tiene esta huella, de ahí; si no y hay [network], se baja (GitHub y, si falla o llega otra cosa, la copia).
     */
    private fun loadCountry(c: PackCountry, network: Boolean): Boolean {
        if (synchronized(countryLock) { loadedSha[c.code] == c.sha256 }) return true
        val cached = File(dir, c.file)
        val bytes = cached.takeIf { it.isFile }?.readBytes()?.takeIf { sha256Of(it).equals(c.sha256, ignoreCase = true) }
            ?: if (!network) return false else {
                setCountryState(c.code, IndexState.LOADING)
                fetchCountry(c).also { b ->
                    cached.parentFile?.mkdirs()
                    File(dir, "${c.file}.tmp").apply { writeBytes(b) }.renameTo(cached)
                }
            }
        // Solo las regiones de ese país (el archivo no debe traer otras).
        val list = readIndex(bytes.decodeToString()).regions.filter { it.country == c.code }
        if (list.isEmpty()) return false
        synchronized(countryLock) {
            loaded[c.code] = list
            loadedSha[c.code] = c.sha256
        }
        return true
    }

    /** El archivo del país [c] con la huella que dice `countries.json` (GitHub y, si no, la copia de jsDelivr). */
    private fun fetchCountry(c: PackCountry): ByteArray {
        for (base in listOf(DATA_URL, DATA_MIRROR_URL)) {
            val bytes = try {
                Https.get(app, base + c.file).inputStream.use { it.readBytes() }
            } catch (_: IOException) {
                continue
            }
            if (sha256Of(bytes).equals(c.sha256, ignoreCase = true)) return bytes
        }
        throw IOException("country ${c.code}")
    }

    /** Pone en [regions] las de los países cargados (en el orden del índice). */
    private fun publishCountries() {
        val all = countries ?: return
        val list = synchronized(countryLock) { all.flatMap { loaded[it.code].orEmpty() } }
        useRegions(list)
    }

    /** Lo que ocupa en la pantalla el mapa bajado [id] (mapa, búsqueda y calles). Lee el disco: fuera del hilo principal. */
    fun savedBytes(id: String): Long = listOf("pmtiles", Extra.SEARCH.suffix, Extra.ROUTES.suffix).sumOf { File(dir, "$id.$it").length() }

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
        // Primero el índice por país; si no existe o no se puede (y no hay uno guardado), `regions.json` como antes.
        if (loadCountryIndex(force, maxAgeMs)) return
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

    /**
     * El índice por país: al día (y sus países pendientes de la caché o la red) o bajado. false = no hay ninguno (ni
     * guardado ni en la red): que se use `regions.json`.
     */
    private fun loadCountryIndex(force: Boolean, maxAgeMs: Long): Boolean {
        val cached = File(dir, COUNTRIES)
        val readable = synchronized(indexLock) { countries != null || readCachedCountries() }
        val updated = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime }.getOrDefault(0L)
        if (!force && readable && indexFresh(cached.isFile, cached.lastModified(), updated, System.currentTimeMillis(), maxAgeMs)) {
            // Al día: solo los países que faltan (los que no estaban en la caché).
            countries?.let { all -> fetchCountries(wantedCountries(all).filter { c -> synchronized(countryLock) { loadedSha[c.code] != c.sha256 } }) }
            indexState = IndexState.IDLE
            return true
        }
        indexState = IndexState.LOADING
        return try {
            // El de los paquetes nuevos (versión 2); si aún no se publica, el de siempre. Se guarda con un solo nombre.
            val text = try {
                fetchIndex(COUNTRIES_V2)
            } catch (e: HttpStatusException) {
                if (e.code != 404) throw e
                fetchIndex(COUNTRIES)
            }
            val index = readCountries(text) // dañado o de otra versión: no se guarda
            if (index.countries.isEmpty()) throw IOException("countries")
            dir.mkdirs()
            File(dir, "$COUNTRIES.tmp").apply { writeText(text) }.renameTo(cached)
            useCountries(index, network = true)
            indexState = IndexState.IDLE
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Sin internet o aún no publicado: con uno guardado se queda ese (falló); sin él, a `regions.json`.
            if (countries != null) {
                indexState = IndexState.FAILED
                true
            } else {
                false
            }
        }
    }

    /** Lee el índice por país guardado (y los países de la caché que hagan falta); false si no hay o no se pudo leer. */
    private fun readCachedCountries(): Boolean {
        val f = File(dir, COUNTRIES)
        if (!f.isFile) return false
        val index = try {
            readCountries(f.readText())
        } catch (_: Throwable) {
            return false
        }
        if (index.countries.isEmpty()) return false
        useCountries(index, network = false)
        return true
    }

    /**
     * Usa el índice por país: sus países, el interruptor de la búsqueda en línea y, antes de mostrar nada, las regiones de
     * los países que hacen falta ([wantedCountries]); sin [network], solo los que estén en la caché con su huella.
     */
    private fun useCountries(index: CountryIndex, network: Boolean) {
        countries = index.countries
        useSwitches(OnlineSwitches.COUNTRIES, index.photon, index.osrm)
        synchronized(countryLock) {
            // Un país que ya no está en el índice se suelta.
            val codes = index.countries.map { it.code }.toSet()
            loaded.keys.retainAll(codes)
            loadedSha.keys.retainAll(codes)
            countryState = countryState.filterKeys { it in codes }
        }
        catalog = index.countries.flatMap { it.regions }
        // Con el índice por país, la copia del índice completo (`regions.json`, ~1.5 MB) sobra; si algún día falta el
        // índice por país, se vuelve a bajar como respaldo.
        File(dir, INDEX).delete()
        fetchCountries(wantedCountries(index.countries), network)
        publishCountries()
    }

    /**
     * Los países que hacen falta sin que nadie los pida: los de los mapas bajados (para sus actualizaciones y su contorno),
     * los ya cargados o pedidos (por si cambiaron) y el de la región del sistema.
     */
    private fun wantedCountries(all: List<PackCountry>): List<PackCountry> {
        val ids = installed.map { it.id }.toSet()
        val system = java.util.Locale.getDefault().country
        val had = synchronized(countryLock) { loaded.keys.toSet() + countryState.keys }
        return all.filter { c -> c.code == system || c.code in had || c.regions.any { it.id in ids } }
    }

    /** El archivo [name] del repo de GitHub; si esa red falla (bloqueada, limitada o un error del servidor), el de la copia. */
    private fun fetchIndex(name: String = INDEX_FILE): String = try {
        download(DATA_URL + name)
    } catch (_: IOException) {
        download(DATA_MIRROR_URL + name)
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
        useSwitches(OnlineSwitches.REGIONS, index.photon, index.osrm)
        catalog = index.regions
        useRegions(index.regions)
    }

    /** Lo que dice el índice [source] de la búsqueda y la ruta en línea, junto con lo que dijo el otro. */
    private fun useSwitches(source: Int, photon: Boolean, osrm: Boolean) {
        synchronized(switches) {
            switches.set(source, photon, osrm)
            onlineSearch = switches.search
            onlineRouting = switches.routing
        }
    }

    /** Las regiones con todos sus datos en [regions], y lo que sale de ellas para los mapas bajados. */
    private fun useRegions(list: List<MapRegion>) {
        regions = list
        synchronized(stateLock) {
            if (installed.any { it.shape.isEmpty() }) {
                replaceInstalled(installed.map { p ->
                    val r = list.firstOrNull { it.id == p.id }
                    if (p.shape.isEmpty() && r != null) p.copy(bbox = r.bbox, shape = r.shape) else p
                })
            }
        }
        // Solo del mismo formato: a un mapa de otro formato se le ofrece «Actualizar» (los tres juntos).
        val missing = installed.mapNotNull { p ->
            list.firstOrNull { it.id == p.id && it.format == p.format && it.search != null && it.search.sha256 != p.searchSha }
        }
        if (missing.isNotEmpty()) scope.launch { for (r in missing) runCatching { fetchExtra(r, Extra.SEARCH, { true }) } }
    }

    /**
     * ¿El mapa bajado de [region] es de una versión vieja? En el formato 2 cuentan las tres huellas (un release que solo
     * cambia las calles, por los carriles, también es actualización); unas calles o búsqueda que aún no se bajan, no.
     */
    fun outdated(region: MapRegion): Boolean = installed.firstOrNull { it.id == region.id }?.let { isOutdated(it, region) } ?: false

    /** Baja (o actualiza) el mapa de [region]; uno a la vez. Se verifica su huella antes de usarlo. */
    fun download(region: MapRegion) {
        // Bajar un estado suelto deja atrás el aviso de un país que se detuvo.
        synchronized(stateLock) { if (batch?.error != null) batch = null }
        start(region, auto = false)
    }

    /** Cuánto pesa todo el país [code]: lo que dice el índice o, si no, la suma de sus estados ya cargados. */
    fun countryTotal(code: String): Long =
        countries?.firstOrNull { it.code == code }?.total?.takeIf { it > 0 }
            ?: regions.filter { it.country == code }.sumOf { it.totalSize }

    /** Cuántos estados tiene el país [code] (con los nombres: aún sin cargar sus datos). */
    fun countryCount(code: String): Int = catalog.count { it.country == code }

    /** Los estados del país [code] que aún no están bajados (solo de los ya cargados; ver [need]). */
    fun missingIn(code: String): List<MapRegion> {
        val have = installed.map { it.id }.toSet()
        return regions.filter { it.country == code && it.id !in have }
    }

    /** ¿Ya están cargados todos los estados del país [code] (para saber cuáles faltan)? */
    fun countryReady(code: String): Boolean = regions.count { it.country == code } >= countryCount(code)

    /** ¿Caben en [free] bytes libres todos los estados de [list], bajados uno tras otro? */
    fun hasRoomForAll(list: List<MapRegion>, free: Long): Boolean = free >= spaceNeededAll(list, paused)

    /**
     * Baja todos los estados que falten del país [code], uno tras otro (nunca varios a la vez), con la misma descarga
     * reanudable de un estado. El avance en [batch]; si uno falla se detiene ahí (Reintentar = llamarla otra vez, y ese
     * sigue donde iba); [cancel] la detiene. Vive en este objeto: sigue aunque se cierre Ajustes.
     */
    fun downloadCountry(code: String) = synchronized(stateLock) {
        if (running?.isActive == true || batchJob?.isActive == true) return@synchronized
        batch = null
        batchJob = scope.launch {
            val me = coroutineContext.job
            try {
                // Los datos de sus estados (índice por país); con `regions.json` ya están.
                countries?.firstOrNull { it.code == code }?.let { fetchCountries(listOf(it)) }
                val todo = missingIn(code)
                for ((i, region) in todo.withIndex()) {
                    if (!me.isActive) return@launch
                    synchronized(stateLock) { if (batchJob === me) batch = CountryBatch(code, i + 1, todo.size, region.id) }
                    val one = start(region, auto = true) ?: run {
                        // Otra descarga empezó justo antes: se espera y se sigue con esta.
                        synchronized(stateLock) { running }?.join()
                        start(region, auto = true)
                    } ?: return@launch
                    one.join()
                    if (installed.none { it.id == region.id }) {
                        // Falló (se dice cuál y por qué) o la cancelaron (se suelta todo).
                        val error = job?.takeIf { it.id == region.id }?.error
                        synchronized(stateLock) { if (batchJob === me) batch = error?.let { CountryBatch(code, i + 1, todo.size, region.id, it) } }
                        return@launch
                    }
                }
            } finally {
                // Al terminar solo se queda el aviso de un error (Cancelar ya soltó todo y quizá empezó otra cola).
                synchronized(stateLock) {
                    if (batchJob === me) {
                        batchJob = null
                        batch = batch?.takeIf { it.error != null }
                    }
                }
            }
        }
    }

    /**
     * [auto] = el reintento solo al volver la red ([watchNetwork]): si vuelve a fallar, ya no se arma otro. Un paquete del
     * formato 2 baja sus tres archivos como uno solo (reanudable, con el espacio de todo) y queda bajado solo con los tres;
     * si el mapa es el mismo y solo cambiaron su búsqueda o sus calles, se bajan esas ([updateExtras]).
     */
    private fun start(region: MapRegion, auto: Boolean): Job? = synchronized(stateLock) {
        if (running?.isActive == true) return@synchronized null
        // Seguirla a mano (o el reintento mismo) suelta la espera de la red.
        unwatchNetwork()
        job = PackJob(region.id, 0f)
        running = scope.launch {
            val me = coroutineContext.job
            val bundle = region.format >= 2
            val before = installed.firstOrNull { it.id == region.id }
            if (bundle && before != null && before.format == region.format && before.sha256.equals(region.map.sha256, ignoreCase = true)) {
                updateExtras(region, me)
                return@launch
            }
            val parts = partFiles(region.id).let { if (bundle) it else it.take(1) }
            val files = listOf(region.map) + if (bundle) Extra.entries.map { it.file(region)!! } else emptyList()
            val target = File(dir, "${region.id}.pmtiles")
            // Abiertos, aparte de los de [fetchExtra] (que pueden andar a la vez con el mismo estado).
            val unpacked = if (bundle) Extra.entries.map { File(dir, "${region.id}.${it.suffix}.new$PART") } else emptyList()
            val pack = if (!bundle) null else PackBundle(parts[0], parts.drop(1), unpacked, Extra.entries.map { File(dir, "${region.id}.${it.suffix}") }, target)
            // ¿Lo que quedó a medias es de este mismo paquete? Entonces se sigue donde iba; si no, de cero.
            val resume = resumable()?.let { it.id == region.id && it.sha256.equals(region.packKey, ignoreCase = true) } == true
            // Lo bajado se queda para seguir después (corte de red, disco lleno, también tras cerrar la app) salvo que se
            // cancele, que por dentro no sirva o que ya esté puesto en su lugar.
            var keepPart = false
            paused = null
            try {
                dir.mkdirs()
                if (!resume) {
                    parts.forEach { it.delete() }
                    writePending(Pending(region.id, region.packKey, region.map.size, System.currentTimeMillis()))
                }
                keepPart = true
                val have = if (resume) parts.sumOf { if (it.isFile) it.length() else 0L } else 0L
                if (dir.usableSpace < spaceNeeded(region, have)) {
                    job = PackJob(region.id, 0f, PackError.SPACE)
                    return@launch
                }
                // El avance cuenta todo el paquete (mapa, búsqueda y calles), por su tamaño.
                val total = region.totalSize.coerceAtLeast(1).toFloat()
                var shown = have / total
                job = PackJob(region.id, shown)
                var done = 0L
                val shas = files.mapIndexed { i, file ->
                    val from = done
                    val sha = Https.download(app, file.url, parts[i], file.size, null, { fileP ->
                        val p = (from + fileP * file.size) / total
                        // La descarga no se suspende: aquí se nota que la cancelaron.
                        if (!me.isActive) throw CancellationException()
                        // El avance cada 1 % (no en cada bloque: serían miles de redibujos); si volvió a empezar, se dice.
                        if (p - shown >= 0.01f || p < shown || p >= 1f) {
                            shown = p
                            job = PackJob(region.id, p)
                        }
                    }, resume = true)
                    if (!sha.equals(file.sha256, ignoreCase = true)) {
                        // Llegó otra cosa: solo ese archivo se vuelve a bajar (si es el mapa, todo de cero).
                        parts[i].delete()
                        throw IOException("SHA-256")
                    }
                    done += file.size
                    sha
                }
                if (!me.isActive) throw CancellationException()
                // La búsqueda y las calles se abren aquí y los tres se revisan juntos; si por dentro no sirven, se borran.
                try {
                    pack?.unpack { me.isActive }
                } catch (e: BadPackException) {
                    keepPart = false
                    throw e
                }
                // Formato 1: si su búsqueda o sus calles no cambiaron, se quedan las que ya están (no se vuelven a bajar).
                fun keep(have: String?, file: PackFile?) = have?.takeIf { it.isNotEmpty() && it.equals(file?.sha256, ignoreCase = true) }.orEmpty()
                val saved = InstalledPack(
                    region.id, shas[0], parts[0].length(), System.currentTimeMillis(), region.bbox, region.shape,
                    if (bundle) shas[1] else keep(before?.searchSha, region.search),
                    if (bundle) shas[2] else keep(before?.routesSha, region.routes), region.format,
                )
                // Todo cambia a la vez: lo abierto de antes se suelta y los archivos nuevos toman su lugar.
                synchronized(searchLock) {
                    synchronized(routeLock) {
                        synchronized(openLock) {
                            open.remove(region.id)?.close()
                            if (routerId == region.id) releaseRouterNow()
                            forgetGraph(region.id)
                            indexes.remove(region.id)?.close()
                            if (pack != null) pack.install { me.isActive } else {
                                if (!me.isActive) throw CancellationException()
                                syncFile(parts[0])
                                if (!parts[0].renameTo(target)) throw IOException("rename")
                            }
                        }
                    }
                }
                keepPart = false
                // Lo de antes se desmapea (solo lo hace el GC) antes de que las teselas abran lo nuevo.
                System.gc()
                synchronized(stateLock) { replaceInstalled(installed.filter { it.id != region.id } + saved) }
                setGap(region.id, null)
                changed(region.bbox)
                // Formato 1: con el mapa, su búsqueda y sus calles para la ruta; si algo falla, la búsqueda se reintenta
                // sola y las calles al pedir una ruta.
                if (!bundle) {
                    var at = region.map.size
                    for (extra in Extra.entries) {
                        val size = extra.file(region)?.size ?: continue
                        val from = at / total
                        runCatching { fetchExtra(region, extra, { me.isActive }) { p -> job = PackJob(region.id, from + p * size / total) } }
                        at += size
                    }
                }
                job = null
            } catch (e: CancellationException) {
                keepPart = false
                job = null
                throw e
            } catch (_: NoSpaceException) {
                // Lo bajado se queda: al liberar espacio se sigue desde ahí.
                job = PackJob(region.id, 0f, PackError.SPACE)
            } catch (_: Exception) {
                job = PackJob(region.id, 0f, PackError.NETWORK)
            } finally {
                unpacked.forEach { it.delete() }
                if (!keepPart) {
                    parts.forEach { it.delete() }
                    File(dir, PENDING).delete()
                }
                synchronized(stateLock) {
                    if (running === me) running = null
                    // Lo bajado se queda para seguirlo (y la pantalla lo descuenta del espacio que falta).
                    if (keepPart) paused = resumable()?.let { pausedOf(it) }
                    // Se cortó la red con una parte guardada: se sigue sola UNA vez cuando vuelva.
                    if (!auto && paused?.id == region.id && job?.error == PackError.NETWORK) watchNetwork(region)
                }
            }
        }
        running
    }

    /**
     * Actualiza solo la búsqueda y las calles de [region] (formato 2 con el mismo mapa: un release que cambió, por ejemplo,
     * los carriles); el avance en [job] y Cancelar ([me]) como en una descarga.
     */
    private fun updateExtras(region: MapRegion, me: Job) {
        try {
            val todo = Extra.entries.filter { e ->
                val have = installed.firstOrNull { it.id == region.id }?.let(e::sha)
                e.file(region)?.let { f -> !f.sha256.equals(have, ignoreCase = true) } == true
            }
            val total = todo.sumOf { it.file(region)!!.size }.coerceAtLeast(1).toFloat()
            var at = 0L
            for (extra in todo) {
                val size = extra.file(region)!!.size
                val from = at
                fetchExtra(region, extra, { me.isActive }) { p -> job = PackJob(region.id, (from + p * size) / total) }
                at += size
            }
            if (!me.isActive) throw CancellationException()
            job = null
        } catch (e: CancellationException) {
            job = null
            throw e
        } catch (_: NoSpaceException) {
            job = PackJob(region.id, 0f, PackError.SPACE)
        } catch (_: Exception) {
            job = PackJob(region.id, 0f, PackError.NETWORK)
        } finally {
            synchronized(stateLock) { if (running === me) running = null }
        }
    }

    /** La espera de la red para seguir una descarga cortada ([watchNetwork]); null = no se espera nada. */
    private var networkWatch: ConnectivityManager.NetworkCallback? = null

    /**
     * Espera a que vuelva internet (sin sondear: avisos del sistema, `registerNetworkCallback`, que sirve desde Android 5)
     * y entonces sigue la descarga de [region] donde iba, una sola vez. Solo cuenta un regreso: una red con internet
     * comprobado que aparece después de no haberla (si al armarse ya había red, espera a que se caiga y vuelva).
     * Se suelta al seguirla (sola o a mano), al cancelar o si ya no aplica.
     */
    private fun watchNetwork(region: MapRegion) {
        unwatchNetwork()
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        var down = runCatching { cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) != true }
            .getOrDefault(true)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                down = true
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    down = true
                    return
                }
                if (!down) return
                synchronized(stateLock) {
                    if (networkWatch !== this) return
                    unwatchNetwork()
                    // Solo si sigue igual: cortada por la red, con su parte y sin otra descarga andando.
                    if (running == null && job?.let { it.id == region.id && it.error == PackError.NETWORK } == true && paused?.id == region.id) {
                        start(region, auto = true)
                    }
                }
            }
        }
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        if (runCatching { cm.registerNetworkCallback(request, callback) }.isSuccess) networkWatch = callback
    }

    /** Suelta la espera de la red (llamar con [stateLock]). */
    private fun unwatchNetwork() {
        val callback = networkWatch ?: return
        networkWatch = null
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching { cm.unregisterNetworkCallback(callback) }
    }

    /** El mapa que quedó a medias y se puede seguir (de qué región y archivo), o null si no hay o es muy viejo. */
    private fun resumable(): Pending? {
        val p = runCatching { JSONObject(File(dir, PENDING).readText()) }.getOrNull()?.let {
            Pending(it.getString("id"), it.getString("sha256"), it.getLong("size"), it.getLong("since"))
        } ?: return null
        val part = File(dir, "${p.id}.pmtiles$PART")
        return p.takeIf { part.isFile && keepPartial(part.length(), p.size, p.since, System.currentTimeMillis()) }
    }

    private fun pausedOf(p: Pending) = PausedPack(p.id, p.sha256, partFiles(p.id).sumOf { it.length() })

    /**
     * Lo que se baja a medias de [id], en orden: el mapa y, en un paquete del formato 2, su búsqueda y sus calles (con
     * gzip, aparte de los de [fetchExtra] para que no se pisen).
     */
    private fun partFiles(id: String): List<File> =
        listOf(File(dir, "$id.pmtiles$PART")) + Extra.entries.map { File(dir, "$id.${it.suffix}$BUNDLE_PART") }

    /** Cuánto del mapa de [region] ya está bajado y se seguiría (0 si nada o si es de otro archivo). */
    fun partialBytes(region: MapRegion): Long = partialOf(region, paused)

    /** ¿Cabe bajar (o seguir) [region] en [free] bytes libres? La misma regla que usa la descarga ([spaceNeeded]). */
    fun hasRoom(region: MapRegion, free: Long): Boolean = free >= spaceNeeded(region, partialBytes(region))

    private fun writePending(p: Pending) {
        val temp = File(dir, "$PENDING.tmp")
        temp.writeText(JSONObject().put("id", p.id).put("sha256", p.sha256).put("size", p.size).put("since", p.since).toString())
        temp.renameTo(File(dir, PENDING))
    }

    /**
     * Cancelar = no quiero ese archivo: detiene la descarga (deja de escribir al siguiente bloque, no instala nada y borra
     * lo bajado) o, si estaba en pausa (se cortó la red, se cerró la app), borra lo que quedó a medias.
     */
    fun cancel() = synchronized(stateLock) {
        unwatchNetwork()
        // También el país completo: Cancelar = parar todo lo que se baja.
        batchJob?.cancel()
        batchJob = null
        batch = null
        running?.cancel()
        job = null
        if (running == null && paused != null) {
            paused = null
            scope.launch {
                synchronized(stateLock) {
                    // Si mientras tanto empezó otra descarga, ella decide qué hacer con el .part.
                    if (running != null) return@synchronized
                    resumable()?.let { p -> partFiles(p.id).forEach { it.delete() } }
                    File(dir, PENDING).delete()
                }
            }
        }
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
            val gone = installed.firstOrNull { it.id == id }
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
                synchronized(openLock) { forgetGraph(id) }
                File(dir, "$id.ldm").delete()
            }
            setGap(id, null)
            // Su archivo de ruta se desmapea (solo lo hace el GC) antes de que otro estado abra el suyo.
            System.gc()
            changed(gone?.bbox)
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
        // Un mapa del formato 2 solo lleva calles con su dibujo: nunca las de un índice del formato 1.
        if (extra == Extra.ROUTES && pack.format >= 2 && region.format < 2) return@synchronized
        val final = File(dir, "${region.id}.${extra.suffix}")
        if (extra.sha(pack).equals(file.sha256, ignoreCase = true) && final.isFile) return@synchronized
        val gz = File(dir, "${region.id}.${extra.suffix}.gz$PART")
        val raw = File(dir, "${region.id}.${extra.suffix}$PART")
        // El gzip y lo abierto conviven un momento (lo abierto, lo que diga el índice o ~3 veces el gzip).
        val unpacked = if (file.unpacked > 0) file.unpacked else (EXTRA_SPACE_FACTOR - 1) * file.size
        dir.mkdirs()
        if (dir.usableSpace < file.size + unpacked + SPACE_MARGIN) throw NoSpaceException(IOException("ENOSPC"))
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
            gunzip(gz, raw, active)
            if (!active() || installed.none { it.id == region.id }) throw CancellationException()
            if (extra == Extra.ROUTES && pack.format >= 2 && !RoadGraph.hasDrawing(raw)) throw BadPackException("ldm")
            val lock = if (extra == Extra.SEARCH) searchLock else routeLock
            synchronized(lock) {
                // Mientras bajaba, el mapa pudo cambiar (otra versión u otro formato, de [start]): esto ya no es suyo.
                if (!samePack(pack, installed.firstOrNull { it.id == region.id }) || !active()) throw CancellationException()
                if (extra == Extra.SEARCH) indexes.remove(region.id)?.close() else {
                    if (routerId == region.id) releaseRouterNow()
                    synchronized(openLock) { forgetGraph(region.id) }
                }
                if (!raw.renameTo(final)) throw IOException("rename")
                synchronized(stateLock) {
                    replaceInstalled(installed.map { if (it.id == region.id && samePack(pack, it)) extra.with(it, sha) else it })
                }
            }
            // Las calles de un mapa del formato 2 cambian su dibujo: lo dibujado sin ellas (o con las de antes) se suelta.
            if (extra == Extra.ROUTES && pack.format >= 2) {
                setGap(region.id, null)
                System.gc()
                changed(pack.bbox)
            }
        } finally {
            gz.delete()
            raw.delete()
        }
    }

    /** El ruteador abierto (uno: sus arreglos pesan ~16 B por cruce) y de qué estado es. */
    private val routeLock = Any()
    private var routerId: String? = null
    private var router: Router? = null

    /** Bajando las calles para la ruta de un estado (al pedir la primera ruta): cuánto va o si falló. */
    var routesJob by mutableStateOf<PackJob?>(null)
        private set

    /**
     * Qué se puede hacer para ir de ([fromLat], [fromLon]) a ([toLat], [toLon]): con el mapa bajado que cubre los dos
     * puntos, la ruta en la pantalla (siempre primero: no se gasta el servidor); si falta un mapa (tu estado, el del
     * destino o la ruta entre estados) y hay internet, la ruta en línea ([OnlineRoute], [Route.online]); si no, qué
     * bajar. Con [headingDeg] (al recalcular manejando) la ruta no sale en sentido contrario a tu rumbo si hay cómo
     * rodear. Fuera del hilo principal.
     */
    fun planRoute(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, headingDeg: Float? = null): RoutePlan {
        val local = planLocal(fromLat, fromLon, toLat, toLon, headingDeg)
        // Mientras carga la lista de estados se espera (al llegar se vuelve a planear): quizá sí está bajado.
        val needsRoutes = local is RoutePlan.NeedsRoutes
        if (!local.missingMap && !needsRoutes) return local
        if (!onlineRouting) return local.missed(OnlineMiss.OFF)
        if (!OnlineRoute.hasInternet(app)) return local.missed(OnlineMiss.NO_NET)
        // Con poca memoria un viaje muy largo en línea no cabe (la respuesta, sus puntos y la curva): ni se pide.
        if (Runtime.getRuntime().maxMemory() < ONLINE_SMALL_HEAP && distanceKm(fromLat, fromLon, toLat, toLon) > ONLINE_SMALL_HEAP_KM) {
            return if (needsRoutes) local else RoutePlan.Failed(RouteError.TOO_BIG)
        }
        return try {
            OnlineRoute.route(app, fromLat, fromLon, toLat, toLon, headingDeg)
                ?.let { RoutePlan.Found(it.rounded(), (local as? RoutePlan.NeedsRoutes)?.region) }
                ?: local.missed(OnlineMiss.FAILED)
        } catch (_: OutOfMemoryError) {
            // Una respuesta enorme: sin tumbar la app, lo de la pantalla.
            OnlineRoute.clearCache()
            local.missed(OnlineMiss.FAILED)
        } catch (_: Exception) {
            local.missed(OnlineMiss.FAILED)
        }
    }

    /** La ruta con lo bajado (ver [planRoute]). */
    private fun planLocal(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, headingDeg: Float?): RoutePlan = try {
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
        // Nada de red aquí (ni con [routeLock]): un país que falta se pide aparte y, al llegar, la ruta se vuelve a planear.
        val pack = installed.firstOrNull { it.contains(fromLat, fromLon) && it.contains(toLat, toLon) }
            ?: return when {
                installed.none { it.contains(fromLat, fromLon) } ->
                    regionAt(fromLat, fromLon)?.let { RoutePlan.NoState(it) } ?: waitOr(fromLat, fromLon) { RoutePlan.NoState(null) }
                // Los dos estados bajados: lo que falta es la ruta entre estados (llega con los viajes largos).
                installed.any { it.contains(toLat, toLon) } -> RoutePlan.TwoStates()
                else -> regionAt(toLat, toLon)?.let { RoutePlan.OtherState(it) } ?: waitOr(toLat, toLon) { RoutePlan.OtherState(null) }
            }.also {
                // Sin la lista no se sabe qué estado falta: se pide (al llegar, la ruta se vuelve a planear).
                if (catalog.isEmpty() && it !is RoutePlan.TwoStates) refresh()
            }
        if (pack.routesSha.isEmpty() || !File(dir, "${pack.id}.ldm").isFile) {
            // Sin el índice (o sin su país) aún: se pide; mientras, no se sabe de dónde bajarlas.
            val region = regionById(pack.id)?.takeIf { it.routes != null }
                ?: return if (countryPending(pack.id)) RoutePlan.Loading else RoutePlan.Failed(RouteError.NO_PATH).also { refresh() }
            return RoutePlan.NeedsRoutes(region)
        }
        return synchronized(routeLock) {
            if (routerId != pack.id) {
                // El de otro estado se suelta ya (con el candado tomado; la versión que espera el candado cerraría el nuevo).
                releaseRouterNow()
                // No abre (dañado): se olvida su huella para que se vuelvan a bajar.
                val g = synchronized(openLock) { graphOf(pack.id, retry = true) } ?: run {
                    synchronized(openLock) { forgetGraph(pack.id) }
                    File(dir, "${pack.id}.ldm").delete()
                    synchronized(stateLock) { replaceInstalled(installed.map { if (it.id == pack.id) it.copy(routesSha = "") else it }) }
                    return@synchronized regionById(pack.id)?.takeIf { it.routes != null }?.let { RoutePlan.NeedsRoutes(it) }
                        ?: RoutePlan.Failed(RouteError.NO_PATH)
                }
                router = Router(g)
                routerId = pack.id
            }
            router!!.route(fromLat, fromLon, toLat, toLon, headingDeg).fold(
                { RoutePlan.Found(it.rounded()) },
                { RoutePlan.Failed((it as? RouteException)?.error ?: RouteError.NO_PATH) },
            )
        }
    }

    /** La región [id] con todos sus datos; si su país (índice por país) aún no está, se pide aparte ([need]) y da null. */
    private fun regionById(id: String): MapRegion? = regions.firstOrNull { it.id == id } ?: run {
        countries?.filter { c -> c.regions.any { it.id == id } }?.forEach { need(it.code, retry = false) }
        null
    }

    /** Con el índice por país, [RoutePlan.Loading] si algún país cuya caja cubre ([lat], [lon]) aún se está cargando; si no, [otherwise]. */
    private inline fun waitOr(lat: Double, lon: Double, otherwise: () -> RoutePlan): RoutePlan =
        if (countries?.any { it.covers(lat, lon) && pending(it.code) } == true) RoutePlan.Loading else otherwise()

    /** ¿El país de la región [id] aún se está cargando? */
    private fun countryPending(id: String): Boolean = countries?.any { c -> c.regions.any { it.id == id } && pending(c.code) } == true

    /** Un país sin cargar ni fallar: se está bajando o está por pedirse ([need]). */
    private fun pending(code: String): Boolean = countryState[code].let { it != IndexState.IDLE && it != IndexState.FAILED }

    /** Baja las calles para la ruta de [region] (lo pidió el usuario al pedir una ruta); el avance en [routesJob]. */
    fun downloadRoutes(region: MapRegion) {
        if (routesJob?.let { !it.failed && it.id == region.id } == true) return
        routesJob = PackJob(region.id, 0f)
        scope.launch {
            try {
                fetchExtra(region, Extra.ROUTES, { true }) { routesJob = PackJob(region.id, it) }
                routesJob = null
            } catch (_: CancellationException) {
                // El mapa cambió o se borró mientras bajaban: ya no hacen falta.
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

    /** Suelta el ruteador ya con [routeLock] tomado; su archivo de ruta se queda abierto solo si con él se dibuja. */
    private fun releaseRouterNow() {
        val id = routerId ?: return
        router = null
        routerId = null
        synchronized(openLock) { if (graphs[id]?.drawable == false) forgetGraph(id) }
    }

    /** ¿Ya se puede buscar sin internet en algún estado bajado? */
    fun canSearch(): Boolean = installed.any { it.searchSha.isNotEmpty() }

    /**
     * Busca [query] en la búsqueda de tus estados bajados, cerca de ([lat], [lon]). Fuera del hilo principal. Con
     * varios estados, se juntan en su orden (la regla de SearchRank.kt). Un índice que
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
        if (lists.size <= 1) return lists.firstOrNull().orEmpty()
        // Varios estados: cada índice ordenó lo suyo; juntos se vuelve a aplicar la regla (lo exacto y lejano de otro estado
        // no va antes que lo cercano de aquí). Sin saber dónde estás, el orden de cada uno.
        val all = lists.flatten().sortedBy { it.order }
        return (if (lat != null && lon != null) orderHits(all, query) else all).take(limit)
    }

    /**
     * La tesela z/x/y del mapa del estado donde cae su centro (tal como está guardada, con gzip) y, si es del formato nuevo,
     * de dónde salen sus calles (entonces sin tesela guardada va vacía: igual lleva calles); null = ningún mapa.
     */
    fun tile(z: Int, x: Int, y: Int): PackTile? {
        val packs = packs
        if (packs.isEmpty()) return null
        val lat = tileLat(y + 0.5, z)
        val lon = tileLon(x + 0.5, z)
        val pack = packs.firstOrNull { it.contains(lat, lon) } ?: return null
        val file = synchronized(openLock) { open.getOrPut(pack.id) { PmTiles.open(File(dir, "${pack.id}.pmtiles")) ?: return null } }
        // Cada archivo con su candado: dos estados se leen a la vez y nadie más espera.
        val (body, hasRoads) = synchronized(file) { runCatching { file.tile(z, x, y) }.getOrNull() to file.hasRoads }
        if (hasRoads) return body?.let { PackTile(it, null) }
        // Formato 2: sus calles salen del `.ldm`. Sin él (o sin su dibujo, o sin memoria para abrirlo) se piden o se avisa
        // y, mientras, el camino seguro: la tesela de las zonas o de internet, que sí las trae (nunca un mapa sin calles
        // sin decir nada).
        val roads = synchronized(openLock) { graphOf(pack.id, retry = false)?.takeIf { it.drawable } }
        if (roads == null) {
            askRoutes(pack.id)
            return null
        }
        return PackTile(body ?: ByteArray(0), roads)
    }

    /**
     * El archivo de ruta de [id], abierto una sola vez (con [openLock]); null si no hay o no abre. Antes de abrir uno se
     * sueltan los menos usados ([graphsToDrop]). Con [retry] = false no se reintenta uno que no abrió hasta
     * [GRAPH_RETRY_MS] después (las teselas lo piden muchas veces); la ruta sí ([RouteException] si no cabe).
     */
    private fun graphOf(id: String, retry: Boolean): RoadGraph? {
        graphs[id]?.let { return it }
        val now = System.currentTimeMillis()
        if (!retry && unreadable[id]?.let { now - it < GRAPH_RETRY_MS } == true) return null
        val file = File(dir, "$id.ldm")
        val drop = graphsToDrop(graphs.map { (k, g) -> k to g.bytes }, file.length(), MAX_GRAPHS, graphBudget, routerId)
        for (old in drop) forgetGraph(old)
        val graph = try {
            RoadGraph.open(file)
        } catch (e: RouteException) {
            // No cupo: se sueltan los demás (menos el de la ruta) y se pide al GC que los desmapee; las teselas lo
            // reintentan en un rato y, mientras, se dice por qué no hay calles.
            for (old in graphs.keys.filter { it != routerId }) forgetGraph(old)
            System.gc()
            unreadable[id] = now
            setGap(id, StreetsGap.MEMORY)
            if (retry) throw e
            return null
        } ?: run {
            unreadable[id] = now
            // Está pero no abre: dañado (se vuelve a bajar, [askRoutes]).
            if (file.isFile) corrupt += id
            return null
        }
        unreadable -= id
        corrupt -= id
        if (streetsGaps[id] == StreetsGap.MEMORY) setGap(id, null)
        graphs[id] = graph
        return graph
    }

    /** Olvida el archivo de ruta abierto de [id] (cambió, se borró o hace falta lugar); con [openLock]. */
    private fun forgetGraph(id: String) {
        graphs.remove(id)?.close()
        unreadable -= id
        corrupt -= id
    }

    /** Anota (o, con null, quita) por qué el mapa [id] se ve sin sus calles. */
    private fun setGap(id: String, gap: StreetsGap?) = synchronized(routesAsked) {
        if (streetsGaps[id] != gap) streetsGaps = if (gap == null) streetsGaps - id else streetsGaps + (id to gap)
    }

    /** Cambió lo bajado en [bbox] (null = no se sabe dónde): lo que no abrió se vuelve a intentar y se redibuja esa caja. */
    private fun changed(bbox: DoubleArray?) {
        synchronized(openLock) { unreadable.clear() }
        TileStore.packsChanged(bbox ?: WORLD)
    }

    /** Lo que ve un mapa en ([lat], [lon]) de las calles de su mapa bajado: el estado y por qué faltan; null = nada falta. */
    fun streetsGapAt(lat: Double, lon: Double): Pair<String, StreetsGap>? {
        val gaps = streetsGaps
        if (gaps.isEmpty()) return null
        val id = packs.firstOrNull { it.contains(lat, lon) }?.id ?: return null
        return gaps[id]?.let { id to it }
    }

    /** Baja ya (lo pidió el usuario: «Bajar calles») las calles del mapa [id]; sin la lista aún, se pide. */
    fun downloadStreets(id: String) {
        val region = regionById(id)?.takeIf { it.routes != null } ?: return refresh()
        synchronized(routesAsked) { routesAsked.remove(id) }
        downloadRoutes(region)
    }

    /**
     * Un mapa del formato 2 sin calles que dibujar. Unas calles sin dibujo (de un índice viejo) o dañadas se sueltan; unas
     * que no abrieron por memoria no se vuelven a bajar ([StreetsGap.MEMORY]). Las que faltan se bajan solas, sin avance
     * a la vista, solo con internet que no se cobre por dato y espacio ([routesAsk]); si no, se ofrece «Bajar calles».
     * Se revisa a lo más una vez por [ROUTES_ASK_MS]. Fuera del hilo de quien llama.
     */
    private fun askRoutes(id: String) {
        val now = System.currentTimeMillis()
        val ask = synchronized(routesAsked) {
            val t = routesAsked.getOrPut(id) { RoutesTry() }
            if (t.busy || now - t.checkedAt < ROUTES_ASK_MS) return
            t.checkedAt = now
            t.busy = true
            t
        }
        scope.launch {
            try {
                val ldm = File(dir, "$id.ldm")
                if (ldm.isFile && (!RoadGraph.hasDrawing(ldm) || synchronized(openLock) { id in corrupt })) {
                    synchronized(routeLock) {
                        if (routerId == id) releaseRouterNow()
                        synchronized(openLock) { forgetGraph(id) }
                        ldm.delete()
                    }
                    synchronized(stateLock) { replaceInstalled(installed.map { if (it.id == id) it.copy(routesSha = "") else it }) }
                }
                val region = regionById(id)?.takeIf { it.format >= 2 } ?: return@launch
                val routes = region.routes ?: return@launch
                val pack = installed.firstOrNull { it.id == id } ?: return@launch
                // Ya están (no abrieron por falta de memoria): no se vuelven a bajar.
                if (ldm.isFile && pack.routesSha.equals(routes.sha256, ignoreCase = true)) return@launch
                // Las pidió el usuario y van bajando: no se piden otra vez.
                if (routesJob?.let { it.id == id && !it.failed } == true) return@launch
                val unpacked = if (routes.unpacked > 0) routes.unpacked else (EXTRA_SPACE_FACTOR - 1) * routes.size
                val decision = routesAsk(
                    OnlineRoute.hasInternet(app), meteredNetwork(), dir.usableSpace >= routes.size + unpacked + SPACE_MARGIN,
                    ask.tries, ask.lastAt, now,
                )
                when (decision) {
                    RoutesAsk.WAIT -> Unit
                    RoutesAsk.OFFER -> setGap(id, StreetsGap.OFFER)
                    RoutesAsk.NO_ROOM -> setGap(id, StreetsGap.NO_ROOM)
                    RoutesAsk.FETCH -> {
                        synchronized(routesAsked) {
                            ask.tries++
                            ask.lastAt = now
                        }
                        setGap(id, null)
                        try {
                            fetchExtra(region, Extra.ROUTES, { true })
                        } catch (_: CancellationException) {
                            // El mapa cambió o se borró mientras bajaban: nada que decir.
                        } catch (_: NoSpaceException) {
                            setGap(id, StreetsGap.NO_ROOM)
                        } catch (_: Exception) {
                            // Se reintenta solo, cada vez más espaciado; tras el último, el botón.
                            if (ask.tries >= ROUTES_MAX_TRIES) setGap(id, StreetsGap.OFFER)
                        }
                    }
                }
            } finally {
                synchronized(routesAsked) { ask.busy = false }
            }
        }
    }

    /** ¿La red de ahora se cobra por dato (datos del celular, zona Wi‑Fi de un celular)? Sin dato, como si sí. */
    private fun meteredNetwork(): Boolean = runCatching {
        (app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
    }.getOrDefault(true)

    /** La lista guardada; los mapas que estén en la carpeta sin anotar (se cortó al guardarla) se adoptan con su caja. */
    private fun readInstalled() {
        // Un corte de corriente puede dejar un archivo de 0 bytes con su nombre: el mapa no cuenta y una búsqueda o unas
        // calles así faltan (se vuelven a bajar).
        fun present(name: String) = File(dir, name).length() > 0
        val listed = runCatching {
            val a = JSONArray(File(dir, INSTALLED).readText())
            List(a.length()) { i -> packFromJson(a.getJSONObject(i)) }
        }.getOrDefault(emptyList()).filter { present("${it.id}.pmtiles") }.map { p ->
            p.copy(
                searchSha = p.searchSha.takeIf { present("${p.id}.srch") }.orEmpty(),
                routesSha = p.routesSha.takeIf { present("${p.id}.ldm") }.orEmpty(),
            )
        }
        val orphans = dir.listFiles { f -> f.name.endsWith(".pmtiles") }.orEmpty()
            .filter { f -> f.length() > 0 && listed.none { "${it.id}.pmtiles" == f.name } }
            .mapNotNull { f ->
                val (bounds, roads) = PmTiles.open(f)?.use { it.bounds to it.hasRoads } ?: return@mapNotNull null
                // Su huella (para saber si hay versión nueva) se calcula aquí, una vez: ya estamos fuera del hilo principal.
                InstalledPack(
                    f.name.removeSuffix(".pmtiles"), Https.sha256(f), f.length(), f.lastModified(), bounds, emptyList(),
                    format = if (roads) 1 else 2,
                )
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
            FileOutputStream(temp).use {
                it.write(a.toString().toByteArray())
                it.fd.sync()
            }
            temp.renameTo(File(dir, INSTALLED))
        } catch (_: IOException) {
            // Disco lleno: en memoria ya está; al abrir otra vez se adoptan los archivos que haya.
        }
    }

    companion object {
        private const val FOLDER = "map_packs"
        /** La copia guardada de `regions.json` (se llama así desde la 1.5.2). */
        private const val INDEX = "index.json"
        /**
         * El índice por país, guardado con este nombre aunque sea el de la versión 2 (los países en `countries/<cc>.json` o
         * `countries2/<cc>.json`, guardados con el mismo nombre que en el repo).
         */
        private const val COUNTRIES = "countries.json"
        /**
         * El índice por país de esta versión de la app (versión 2: regiones con `format`). Las apps hasta la 1.5.18 no lo
         * leen: `countries.json`, `countries/`, `regions.json` e `index.json` se quedan como están para ellas.
         */
        private const val COUNTRIES_V2 = "countries2.json"
        private const val INSTALLED = "installed.json"
        private const val PART = ".part"
        /** Los gzip a medias de un paquete del formato 2 (`<id>.srch.gz.dl.part`, `<id>.ldm.gz.dl.part`). */
        private const val BUNDLE_PART = ".gz.dl$PART"
        /** De qué región y archivo es el `.part` del mapa a medias (para seguirlo tras cerrar la app). */
        private const val PENDING = "pending.json"
        /** Un mapa a medias se sigue hasta unos días después; luego se borra (no dejar basura de cientos de MB). */
        private const val PARTIAL_MAX_AGE_MS = 3 * 24 * 60 * 60 * 1000L
        /** Lo que se deja libre de más al bajar un estado (la búsqueda y las calles ocupan más ya descomprimidas). */
        private const val SPACE_MARGIN = 64L * 1024 * 1024
        /** La búsqueda y las calles descomprimidas pesan ~3 veces su .gz, y el .gz convive con ellas un momento. */
        private const val EXTRA_SPACE_FACTOR = 4
        /** Los datos del repo litedash-maps (`main`): los índices y los archivos de cada país. */
        private const val DATA_URL = "https://raw.githubusercontent.com/IrvingGuerra/litedash-maps/main/"
        // Lo mismo por otra red (jsDelivr), para donde raw.githubusercontent.com está bloqueado o limitado; puede ir hasta
        // 12 h atrás.
        private const val DATA_MIRROR_URL = "https://cdn.jsdelivr.net/gh/IrvingGuerra/litedash-maps@main/"
        // Desde la 1.5.2, todos los países en un archivo (EUA trae estados con millones de cruces: el ruteador de antes no
        // los aguanta en pantallas de 1 GB, así que index.json se queda solo con México para las versiones anteriores).
        // Desde la 1.5.16 es el respaldo del índice por país.
        private const val INDEX_FILE = "regions.json"
        private const val INDEX_MAX_AGE_MS = 24 * 60 * 60 * 1000L
        /** En Ajustes → Sin conexión la lista se pide si tiene más de una hora (ahí se buscan los países nuevos). */
        const val INDEX_SCREEN_MAX_AGE_MS = 60 * 60 * 1000L

        /** Archivos de ruta abiertos a la vez (el del ruteador y el que se dibuja; uno más se suelta). */
        private const val MAX_GRAPHS = 2
        /** En un proceso de 32 bits (poco espacio de direcciones), hasta cuánto pesan juntos los archivos de ruta mapeados. */
        private const val GRAPH_BUDGET_32 = 512L * 1024 * 1024
        /** Un archivo de ruta que no abrió (sin memoria o dañado) se reintenta para las teselas tras este tiempo. */
        private const val GRAPH_RETRY_MS = 30_000L
        /** Toda la Tierra (oeste, sur, este, norte): cuando no se sabe qué caja cambió. */
        private val WORLD = doubleArrayOf(-180.0, -90.0, 180.0, 90.0)

        /**
         * ¿El mapa bajado [p] es de una versión vieja de [region]? Su mapa cambió o, en el formato 2, su búsqueda o sus
         * calles (las que aún no se bajan, "", no cuentan: eso lo resuelve «Bajar calles»).
         */
        internal fun isOutdated(p: InstalledPack, region: MapRegion): Boolean {
            fun differs(have: String, file: PackFile?) = have.isNotEmpty() && file != null && !have.equals(file.sha256, ignoreCase = true)
            if (p.sha256.isEmpty()) return false
            if (!p.sha256.equals(region.map.sha256, ignoreCase = true)) return true
            return region.format >= 2 && (differs(p.searchSha, region.search) || differs(p.routesSha, region.routes))
        }

        /** Con menos memoria por app que esto (bytes) un viaje en línea de más de [ONLINE_SMALL_HEAP_KM] en recta no se pide. */
        private const val ONLINE_SMALL_HEAP = 128L * 1024 * 1024
        private const val ONLINE_SMALL_HEAP_KM = 300.0

        @SuppressLint("StaticFieldLeak") // solo el contexto de la aplicación
        @Volatile private var instance: MapPacks? = null

        /** Para el reporte técnico, sin abrir nada: cuántas regiones trae la lista y cuáles están bajadas. */
        fun describe(): String = instance?.let { p ->
            val split = p.countries
            val cache = File(p.dir, if (split != null) COUNTRIES else INDEX)
            val state = when (p.indexState) {
                IndexState.LOADING -> " (bajando)"
                IndexState.FAILED -> " (falló)"
                // Sin lista y sin caché: aún no se ha pedido (no es un error).
                IndexState.IDLE -> if (p.catalog.isEmpty() && !cache.isFile) " (sin pedir)" else ""
            }
            val age = if (cache.isFile) ", caché de hace ${(System.currentTimeMillis() - cache.lastModified()) / 3_600_000} h" else ""
            // Con el índice por país: cuántos países y cuáles se cargaron (y cuáles fallaron).
            val how = if (split == null) "" else {
                val failed = p.countryState.filterValues { it == IndexState.FAILED }.keys
                ", por país: ${split.size}, cargados=${p.regions.map { it.country }.distinct().joinToString().ifEmpty { "ninguno" }}" +
                    if (failed.isEmpty()) "" else ", fallaron=${failed.joinToString()}"
            }
            // Los que se ven sin sus calles (formato 2) y por qué.
            val gaps = p.streetsGaps.entries.joinToString { "${it.key}=${it.value.name.lowercase()}" }
            "Mapas: lista=${p.catalog.size} regiones$state$age$how (${p.catalog.map { it.country }.distinct().joinToString()}); bajados=" +
                p.installed.joinToString { it.id }.ifEmpty { "ninguno" } + if (gaps.isEmpty()) "" else "; sin calles: $gaps"
        } ?: "Mapas: sin abrir en esta sesión"

        /** ¿Se sigue un `.part` de [have] bytes de un archivo de [size] empezado en [since]? (Sano, no completo de más y reciente.) */
        internal fun keepPartial(have: Long, size: Long, since: Long, now: Long): Boolean =
            have in 1..size && isRecent(since, now, PARTIAL_MAX_AGE_MS)

        /** Lo que [paused] lleva del mapa de [region] (0 si es de otra región o de otro archivo). */
        internal fun partialOf(region: MapRegion, paused: PausedPack?): Long =
            paused?.takeIf { it.id == region.id && it.sha256.equals(region.packKey, ignoreCase = true) }?.bytes ?: 0L

        /**
         * Cuánto espacio libre hace falta para bajar todos los [list] uno tras otro: lo que quedan todos (menos lo que
         * [paused] ya lleva del suyo) más lo que pide de más el que más pide al bajarse (búsqueda y calles comprimidas).
         */
        internal fun spaceNeededAll(list: List<MapRegion>, paused: PausedPack?): Long {
            if (list.isEmpty()) return 0
            val extra = list.maxOf { spaceNeeded(it, 0) - it.totalSize }
            return list.sumOf { it.totalSize - partialOf(it, paused) } + extra
        }

        /**
         * Cuánto espacio libre hace falta para bajar [region] con [have] bytes ya bajados (del mapa o, en el formato 2, de
         * los tres archivos): lo que falta, más la búsqueda y las calles abiertas (lo que diga el índice o ~3 veces el gzip).
         */
        internal fun spaceNeeded(region: MapRegion, have: Long): Long {
            val extras = listOfNotNull(region.search, region.routes)
            if (region.format < 2) return (region.map.size - have).coerceAtLeast(0) + EXTRA_SPACE_FACTOR * extras.sumOf { it.size } + SPACE_MARGIN
            return (region.totalSize - have).coerceAtLeast(0) +
                extras.sumOf { if (it.unpacked > 0) it.unpacked else (EXTRA_SPACE_FACTOR - 1) * it.size } + SPACE_MARGIN
        }

        fun get(context: Context): MapPacks =
            instance ?: synchronized(this) { instance ?: MapPacks(context.applicationContext).also { instance = it } }

        /**
         * ¿La lista guardada ([exists], guardada en [savedAt]) sirve todavía? Si tiene menos de [maxAgeMs] y es de después de
         * actualizar la app ([appUpdatedAt]); con el reloj hacia atrás (guardada "en el futuro"), no.
         */
        internal fun indexFresh(exists: Boolean, savedAt: Long, appUpdatedAt: Long, now: Long, maxAgeMs: Long): Boolean =
            exists && isRecent(savedAt, now, maxAgeMs) && savedAt > appUpdatedAt

        /** Las regiones del índice [text] (ver [readIndex]). */
        internal fun parseIndex(text: String): List<MapRegion> = readIndex(text).regions

        /**
         * El índice de litedash-maps (versión 1 o 2), analizado una sola vez: sus regiones (las sin mapa o dañadas se saltan)
         * y los interruptores de lo que va por internet ("online": {"photon": false} apaga la búsqueda; {"osrm": false}, la
         * ruta). En la versión 2 cada región dice su `format` (1 o 2; uno más nuevo se salta) y las del 2 traen sus tres
         * archivos. De otra versión: sin regiones.
         */
        internal fun readIndex(text: String): PackIndex {
            val o = JSONObject(text)
            val photon = online(o, "photon")
            val osrm = online(o, "osrm")
            val version = o.optInt("version")
            if (version != 1 && version != 2) return PackIndex(emptyList(), photon, osrm)
            val base = o.getString("base")
            val list = o.getJSONArray("regions")
            return PackIndex((0 until list.length()).mapNotNull { i ->
                runCatching {
                    val r = list.getJSONObject(i)
                    val n = r.getJSONObject("name")
                    val names = n.keys().asSequence().associateWith { n.getString(it) }
                    val files = r.getJSONObject("files")
                    val format = if (version == 1) 1 else r.optInt("format", 1)
                    require(format in 1..MAX_FORMAT)
                    fun file(o: JSONObject) = PackFile(base + o.getString("name"), o.getLong("size"), o.getString("sha256"), o.optLong("unpacked"))
                    val search = files.optJSONObject("search")?.let(::file)
                    val routes = files.optJSONObject("routes")?.let(::file)
                    require(format < 2 || (search != null && routes != null))
                    MapRegion(
                        r.getString("id"), r.getString("country"), names, box(r.getJSONArray("bbox")), rings(r.getJSONArray("shape")),
                        file(files.getJSONObject("map")), search, routes, format,
                    )
                }.getOrNull()
            }, photon, osrm)
        }

        /** El formato de paquete más nuevo que entiende esta versión ([MapRegion.format]). */
        private const val MAX_FORMAT = 2

        /** El interruptor [name] de "online" en el índice [o]; si falta, prendido. */
        private fun online(o: JSONObject, name: String): Boolean = o.optJSONObject("online")?.optBoolean(name, true) ?: true

        /**
         * El índice por país (`countries.json` versión 1 o `countries2.json` versión 2, el mismo esquema): por país su código,
         * su caja, su archivo con su huella y los nombres de sus regiones; los dañados se saltan. De otra versión: sin países
         * (el interruptor de Photon sí cuenta).
         */
        internal fun readCountries(text: String): CountryIndex {
            val o = JSONObject(text)
            val photon = online(o, "photon")
            val osrm = online(o, "osrm")
            if (o.optInt("version") != 1 && o.optInt("version") != 2) return CountryIndex(emptyList(), photon, osrm)
            val list = o.getJSONArray("countries")
            return CountryIndex((0 until list.length()).mapNotNull { i ->
                runCatching {
                    val c = list.getJSONObject(i)
                    val code = c.getString("code")
                    val file = c.getString("file")
                    // Solo un archivo de la carpeta countries/ (nada de rutas raras).
                    require(COUNTRY_FILE.matches(file))
                    val r = c.getJSONObject("regions")
                    val regions = r.keys().asSequence().map { id ->
                        val n = r.getJSONObject(id)
                        RegionName(id, code, n.keys().asSequence().associateWith { n.getString(it) })
                    }.toList()
                    require(regions.isNotEmpty())
                    PackCountry(code, box(c.getJSONArray("bbox")), regions, file, c.getString("sha256"), c.optLong("total").coerceAtLeast(0))
                }.getOrNull()
            }, photon, osrm)
        }

        /** El nombre de un archivo de país: `countries/<cc>.json` (versión 1) o `countries2/<cc>.json` (versión 2). */
        private val COUNTRY_FILE = Regex("countries2?/[a-z0-9-]{2,16}\\.json")

        /** La huella SHA-256 de [bytes] (en hexadecimal). */
        internal fun sha256Of(bytes: ByteArray): String =
            java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        internal fun packToJson(p: InstalledPack): JSONObject {
            val shape = JSONArray()
            for (ring in p.shape) shape.put(JSONArray().apply { for (i in ring.indices step 2) put(JSONArray().put(ring[i]).put(ring[i + 1])) })
            return JSONObject().put("id", p.id).put("sha256", p.sha256).put("size", p.size).put("savedAt", p.savedAt)
                .put("bbox", JSONArray().apply { p.bbox.forEach { put(it) } }).put("shape", shape).put("searchSha", p.searchSha)
                .put("routesSha", p.routesSha).put("format", p.format)
        }

        internal fun packFromJson(o: JSONObject) = InstalledPack(
            o.getString("id"), o.getString("sha256"), o.getLong("size"), o.getLong("savedAt"),
            box(o.getJSONArray("bbox")), rings(o.optJSONArray("shape") ?: JSONArray()), o.optString("searchSha"),
            o.optString("routesSha"), o.optInt("format", 1),
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

/**
 * Los interruptores de lo que va por internet según los dos índices (por país, [COUNTRIES], y `regions.json`, [REGIONS]):
 * algo se apaga si cualquiera de los dos lo apaga (no gana el último que se leyó).
 */
internal class OnlineSwitches {
    private val photon = booleanArrayOf(true, true)
    private val osrm = booleanArrayOf(true, true)

    fun set(source: Int, photon: Boolean, osrm: Boolean) {
        this.photon[source] = photon
        this.osrm[source] = osrm
    }

    val search: Boolean get() = photon.all { it }
    val routing: Boolean get() = osrm.all { it }

    companion object {
        const val COUNTRIES = 0
        const val REGIONS = 1
    }
}
