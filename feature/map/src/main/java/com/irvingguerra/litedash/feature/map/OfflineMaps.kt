package com.irvingguerra.litedash.feature.map

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Una zona descargada: un círculo de [km] alrededor de ([lat], [lon]) con sus [tiles] teselas ([bytes] en disco). */
class OfflineArea(val id: String, val lat: Double, val lon: Double, val km: Int, val tiles: Int, val bytes: Long, val savedAt: Long) {
    fun contains(lat: Double, lon: Double) = distanceKm(this.lat, this.lon, lat, lon) <= km
}

/**
 * La descarga en curso: [done] de [total] teselas, [bytes] en disco; [waiting] = el servidor pidió calma y
 * se está esperando; con [error] ya se detuvo (se reanuda al volver a bajar).
 */
class OfflineJob(val done: Int, val total: Int, val bytes: Long, val error: OfflineError? = null, val waiting: Boolean = false)

/** BLOCKED = el servidor nos cortó (403/418); NETWORK = sin internet o falló varias veces seguidas. */
enum class OfflineError { BLOCKED, NETWORK }

/**
 * El mapa minimalista sin conexión: zonas que el usuario descarga completas (las teselas del 14 en un
 * círculo y las del 12 y 13 que las cubren, para lo lejano del mapa inclinado) en `filesDir/map_offline`,
 * tal como llegan (comprimidas). No caducan ni se recortan: solo se van al borrar su zona. OSM no
 * permite bajar en bloque; OpenFreeMap sí (sin límites), igual con calma: dos a la vez y nuestro User-Agent.
 *
 * Todo el estado se cambia con [lock] y por [generation]: lo que haga una descarga ya cancelada o
 * reemplazada no toca el estado ni deja archivos. En disco solo se queda lo de alguna zona o de la
 * descarga en curso ([areaKeys] y [runningKeys]); lo demás se borra al cancelar y al arrancar.
 */
class OfflineMaps private constructor(private val app: Context) {
    private val dir = File(app.filesDir, FOLDER)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var running: Job? = null
    private var generation = 0
    private var runningKeys: Set<Long> = emptySet()
    private var areaKeys: Set<Long> = emptySet()
    /** La lista se lee del disco una vez, fuera del hilo principal; lo que la cambia espera a que esté. */
    private val ready = scope.async {
        readAreas()
        sweep(startup = true)
    }

    var areas by mutableStateOf<List<OfflineArea>>(emptyList())
        private set
    var job by mutableStateOf<OfflineJob?>(null)
        private set

    /** Cuántas teselas lleva una zona (por [AVG_TILE_BYTES], su tamaño aproximado); de 200 km son decenas de miles: fuera del hilo principal. */
    suspend fun estimate(lat: Double, lon: Double, km: Int): Int = withContext(Dispatchers.Default) { areaTiles(lat, lon, km).size }

    /** ¿([lat], [lon]) está dentro de alguna zona descargada? */
    fun covers(lat: Double, lon: Double): Boolean = areas.any { it.contains(lat, lon) }

    /** La tesela tal como se bajó (vacía = ahí no hay nada que dibujar); null = no está en ninguna zona. */
    fun tile(z: Int, x: Int, y: Int): ByteArray? = File(dir, "${z}_${x}_${y}").takeIf { it.isFile }?.let { runCatching { it.readBytes() }.getOrNull() }

    /** Baja la zona de [km] alrededor de ([lat], [lon]); sigue aunque se cierre el panel. Lo ya bajado se salta. */
    fun download(lat: Double, lon: Double, km: Int, source: VectorTiles, userAgent: String) = synchronized(lock) {
        if (running?.isActive == true) return@synchronized
        val gen = ++generation
        // Mientras se calculan las teselas (en IO: de 200 km son decenas de miles) ya se ve que empezó.
        job = OfflineJob(0, 0, 0L)
        running = scope.launch {
            val keys = areaTiles(lat, lon, km)
            val run = Run(gen, keys.size)
            // Desde ya, nadie borra estas teselas (ni borrar otra zona ni la limpieza); lo que se borró antes se baja otra vez.
            synchronized(lock) {
                if (!run.current) return@launch
                runningKeys = keys.toHashSet()
                job = OfflineJob(0, keys.size, 0L)
            }
            run(run, keys, lat, lon, km, source, userAgent)
        }
    }

    /** Detiene la descarga y borra lo que bajó que no sea de ninguna zona. */
    fun cancel() {
        synchronized(lock) {
            generation++
            running?.cancel()
            running = null
            runningKeys = emptySet()
            job = null
        }
        scope.launch { sweep(startup = false) }
    }

    /** Quita la zona [id] y borra solo sus teselas que ninguna otra zona (ni la descarga en curso) usa. */
    fun delete(id: String) {
        scope.launch {
            ready.await()
            val candidates = synchronized(lock) {
                val area = areas.firstOrNull { it.id == id } ?: return@launch
                val rest = areas.filter { it.id != id }
                writeAreas(rest)
                replaceAreas(rest)
                areaTiles(area.lat, area.lon, area.km) - areaKeys
            }
            removeTiles(candidates)
        }
    }

    /** Una descarga: su [gen] y su avance. Solo publica mientras sea la vigente. */
    private inner class Run(val gen: Int, val total: Int) {
        val done = AtomicInteger()
        val bytes = AtomicLong()
        val waiting = AtomicInteger()

        val current get() = gen == generation

        fun publish() = synchronized(lock) {
            if (current) job = OfflineJob(done.get(), total, bytes.get(), waiting = waiting.get() > 0)
        }

        /** Termina (con [error] o guardando la zona [area]) y suelta sus teselas. */
        fun finish(error: OfflineError?, area: OfflineArea?) = synchronized(lock) {
            if (!current) return@synchronized
            if (area != null) {
                val list = areas.filter { it.id != area.id } + area
                writeAreas(list)
                replaceAreas(list)
            }
            job = if (error != null) OfflineJob(done.get(), total, bytes.get(), error) else null
            running = null
            runningKeys = emptySet()
        }
    }

    private suspend fun run(run: Run, keys: List<Long>, lat: Double, lon: Double, km: Int, source: VectorTiles, userAgent: String) {
        try {
            ready.await()
            dir.mkdirs()
            val missing = keys.filter { !File(dir, name(it)).isFile }
            run.done.set(keys.size - missing.size)
            run.bytes.set(keys.sumOf { File(dir, name(it)).length() })
            run.publish()
            val next = AtomicInteger(0)
            val failed = AtomicReference<OfflineError?>(null)
            coroutineScope {
                List(MAX_DOWNLOADS) {
                    async {
                        while (failed.get() == null) {
                            val i = next.getAndIncrement()
                            if (i >= missing.size) break
                            val size = fetch(run, missing[i], source, userAgent)
                            if (size < 0) failed.compareAndSet(null, if (size == BLOCKED) OfflineError.BLOCKED else OfflineError.NETWORK)
                            else {
                                run.done.incrementAndGet()
                                run.bytes.addAndGet(size)
                                run.publish()
                            }
                        }
                    }
                }.awaitAll()
            }
            val error = failed.get()
            run.finish(error, if (error == null) OfflineArea(areaId(lat, lon, km), lat, lon, km, keys.size, run.bytes.get(), System.currentTimeMillis()) else null)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Disco lleno o sin permiso de escribir: se detiene como un error de red (se puede reintentar).
            run.finish(OfflineError.NETWORK, null)
        }
    }

    /** Baja y guarda una tesela: sus bytes, o [BLOCKED] / [FAILED]. 429/503 = esperar y otra vez; lo demás, unos reintentos. */
    private suspend fun fetch(run: Run, key: Long, source: VectorTiles, userAgent: String): Long {
        var attempts = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val url = source.url(key)
            val result: Any = if (url == null) Reply.RETRY else runCatching {
                httpGet(app, url, userAgent, setup = { it.setRequestProperty("Accept-Encoding", "gzip") }) { c, code ->
                    when (tileResponse(code)) {
                        // Un portal cautivo contesta 200 con su página: no sirve.
                        TileResponse.OK -> if (c.contentType?.startsWith("text/html") == true) Reply.RETRY else c.inputStream.use { it.readBytes() }
                        TileResponse.EMPTY -> ByteArray(0)
                        TileResponse.SLOW_DOWN -> Reply.SLOW_DOWN
                        TileResponse.BLOCKED -> Reply.BLOCKED
                        else -> Reply.RETRY
                    }
                }
            }.getOrDefault(Reply.RETRY)
            when (result) {
                is ByteArray -> {
                    saveTile(run, key, result)
                    return result.size.toLong()
                }
                Reply.BLOCKED -> return BLOCKED
                Reply.SLOW_DOWN -> {
                    if (++attempts > MAX_ATTEMPTS) return FAILED
                    // Que se vea por qué no avanza.
                    run.waiting.incrementAndGet()
                    run.publish()
                    try {
                        delay(SLOW_DOWN_MS)
                    } finally {
                        run.waiting.decrementAndGet()
                    }
                    run.publish()
                }
                else -> {
                    if (++attempts > MAX_ATTEMPTS) return FAILED
                    delay(RETRY_MS * attempts)
                }
            }
        }
    }

    private enum class Reply { SLOW_DOWN, BLOCKED, RETRY }

    /**
     * Temporal propio y luego renombrar: una tesela a medias nunca queda con su nombre. El renombre va con
     * el candado: una descarga ya cancelada tira lo suyo en vez de dejarlo sin zona.
     */
    private fun saveTile(run: Run, key: Long, data: ByteArray) {
        val temp = File.createTempFile(name(key), ".tmp", dir)
        try {
            temp.writeBytes(data)
            val saved = synchronized(lock) { !run.current || temp.renameTo(File(dir, name(key))) }
            check(saved) { "No se pudo guardar ${name(key)}" }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    /** Borra [keys] una por una (fuera del hilo principal), salvo las que una zona o la descarga en curso usen en ese momento. */
    private fun removeTiles(keys: Collection<Long>) {
        for (key in keys) synchronized(lock) {
            if (key !in areaKeys && key !in runningKeys) File(dir, name(key)).delete()
        }
    }

    /** Lo que no es de ninguna zona ni de la descarga en curso (y, al arrancar, los temporales que dejó un apagón). */
    private fun sweep(startup: Boolean) {
        val names = dir.list() ?: return
        if (startup) for (n in names) if (n.endsWith(".tmp")) File(dir, n).delete()
        val keep = synchronized(lock) { areaKeys + runningKeys }
        removeTiles(strayTiles(names.asList(), keep))
    }

    /** Cambia la lista y sus teselas juntas (con [lock]). */
    private fun replaceAreas(list: List<OfflineArea>) {
        areas = list
        areaKeys = tilesOf(list)
    }

    private fun readAreas() {
        val list = runCatching {
            val array = JSONArray(File(dir, AREAS).readText())
            List(array.length()) { i ->
                val o = array.getJSONObject(i)
                OfflineArea(o.getString("id"), o.getDouble("lat"), o.getDouble("lon"), o.getInt("km"), o.getInt("tiles"), o.getLong("bytes"), o.getLong("savedAt"))
            }
        }.getOrNull() ?: return
        synchronized(lock) { replaceAreas(list) }
    }

    private fun writeAreas(list: List<OfflineArea>) {
        val array = JSONArray()
        for (a in list) {
            array.put(
                JSONObject().put("id", a.id).put("lat", a.lat).put("lon", a.lon).put("km", a.km)
                    .put("tiles", a.tiles).put("bytes", a.bytes).put("savedAt", a.savedAt),
            )
        }
        dir.mkdirs()
        val temp = File.createTempFile(AREAS, ".tmp", dir)
        temp.writeText(array.toString())
        if (!temp.renameTo(File(dir, AREAS))) {
            temp.delete()
            error("No se pudo guardar $AREAS")
        }
    }

    companion object {
        /**
         * Promedio por tesela comprimida para el tamaño aproximado. Medido el 2026-11-14 en OpenFreeMap
         * (CDMX, 30 teselas del 14 al azar en 25 km: ~44 KB; las del 12 y 13 pesan más pero son pocas):
         * con todo, ~45 KB. Ciudades menos densas pesan menos, así que se queda como techo razonable.
         */
        const val AVG_TILE_BYTES = 45L * 1024

        private const val FOLDER = "map_offline"
        private const val AREAS = "areas.json"
        private const val MAX_DOWNLOADS = 2
        private const val MAX_ATTEMPTS = 5
        private const val RETRY_MS = 5_000L
        private const val SLOW_DOWN_MS = 60_000L
        private const val BLOCKED = -1L
        private const val FAILED = -2L

        @SuppressLint("StaticFieldLeak") // solo el contexto de la aplicación
        @Volatile private var instance: OfflineMaps? = null

        /** La única por proceso (la usan el mapa, su panel y el chip de conexión). */
        fun get(context: Context): OfflineMaps =
            instance ?: synchronized(this) { instance ?: OfflineMaps(context.applicationContext).also { instance = it } }

        private fun name(key: Long) = "${keyZ(key)}_${keyX(key)}_${keyY(key)}"
    }
}

/** El zoom de los datos (las del 15–17 salen de él) y los dos más lejanos del mapa inclinado. */
private const val AREA_ZOOM = 14
private const val EARTH_KM = 6371.0

/**
 * Las teselas de una zona: las del 14 cuyo centro cae a [km] o menos de ([lat], [lon]) (más la de en
 * medio), de la más cercana a la más lejana; luego las del 13 y del 12 que las cubren.
 */
internal fun areaTiles(lat: Double, lon: Double, km: Int): List<Long> {
    val n = 1 shl AREA_ZOOM
    val cx = tileX(lon, AREA_ZOOM)
    val cy = tileY(lat, AREA_ZOOM)
    // Ancho de una tesela en km a esta latitud (en Mercator es igual de alta que de ancha).
    val tileKm = 2 * PI * EARTH_KM * cos(Math.toRadians(lat)) / n
    val r = km / tileKm + 1
    val near = ArrayList<Pair<Long, Double>>()
    for (x in floor(cx - r).toInt().coerceAtLeast(0)..ceil(cx + r).toInt().coerceAtMost(n - 1)) {
        for (y in floor(cy - r).toInt().coerceAtLeast(0)..ceil(cy + r).toInt().coerceAtMost(n - 1)) {
            val d = distanceKm(lat, lon, tileLat(y + 0.5, AREA_ZOOM), tileLon(x + 0.5, AREA_ZOOM))
            if (d <= km || (x == cx.toInt() && y == cy.toInt())) near += tileKey(AREA_ZOOM, x, y) to d
        }
    }
    val z14 = near.sortedBy { it.second }.map { it.first }
    val z13 = z14.map { tileKey(AREA_ZOOM - 1, keyX(it) shr 1, keyY(it) shr 1) }.distinct()
    val z12 = z14.map { tileKey(AREA_ZOOM - 2, keyX(it) shr 2, keyY(it) shr 2) }.distinct()
    return z14 + z13 + z12
}

/** Todas las teselas de [areas] (las que no se pueden borrar). */
internal fun tilesOf(areas: List<OfflineArea>): Set<Long> {
    val keys = HashSet<Long>()
    for (a in areas) keys += areaTiles(a.lat, a.lon, a.km)
    return keys
}

/** De los archivos [names] de la carpeta, las teselas (`z_x_y`) que no están en [keep]; lo demás no se toca. */
internal fun strayTiles(names: List<String>, keep: Set<Long>): List<Long> = names.mapNotNull { name ->
    val parts = name.split('_')
    if (parts.size != 3) return@mapNotNull null
    val (z, x, y) = parts.map { it.toIntOrNull() ?: return@mapNotNull null }
    tileKey(z, x, y).takeIf { it !in keep }
}

/** Misma zona = mismo centro (a ~100 m) y radio: volver a bajarla la actualiza en vez de repetirla. */
internal fun areaId(lat: Double, lon: Double, km: Int) = String.format(Locale.US, "%.3f_%.3f_%d", lat, lon, km)

/** Distancia en km sobre la Tierra (haversine). */
internal fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val h = sin(dLat / 2).let { it * it } + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
    return 2 * EARTH_KM * asin(sqrt(h.coerceAtMost(1.0)))
}

/** Lo contrario de [tileX] y [tileY]: la longitud y la latitud de una coordenada de tesela. */
internal fun tileLon(x: Double, z: Int): Double = x / (1 shl z) * 360.0 - 180.0
internal fun tileLat(y: Double, z: Int): Double = Math.toDegrees(atan(sinh(PI * (1 - 2 * y / (1 shl z)))))
