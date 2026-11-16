package com.irvingguerra.litedash.feature.map

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import com.irvingguerra.litedash.core.ui.Https
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.HttpURLConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Las teselas del mapa de [source]: memoria (LRU por bytes que siempre cabe lo que se ve), disco
 * (`filesDir/<carpeta>`, ~25 MB por estilo: entre los dos ~50 MB) y red, siguiendo la política de uso de OSM
 * (operations.osmfoundation.org/policies/tiles; OpenFreeMap pide lo mismo): User-Agent propio, caché
 * según Cache-Control/Expires con revalidación (ETag/If-Modified-Since), a lo más [MAX_DOWNLOADS]
 * descargas a la vez, solo lo que se ve (lo último que se pidió va primero) y parar si nos bloquean.
 * Solo trabaja entre [resume] y [pause] (el mapa a la vista y la app al frente).
 */
class TileStore(context: Context, private val userAgent: String, val source: TileSource) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, source.folder)
    /** Las zonas descargadas (solo el minimalista: OSM no deja bajar en bloque). */
    private val offline = if (source is VectorTiles) OfflineMaps.get(app) else null
    /** Una descarga (y una lectura) por archivo: varias teselas del 15 en adelante salen de la misma del 14. */
    private val fileLocks = Array(FILE_LOCKS) { Any() }
    private val lock = Any()
    private val memory = object : LruCache<Long, ImageBitmap>(MEMORY_MIN_BYTES) {
        override fun sizeOf(key: Long, value: ImageBitmap) = value.asAndroidBitmap().allocationByteCount
    }
    /** El tamaño de una tesela en memoria (de la más grande que ha llegado). */
    private var tileBytes = 0
    /** Tope de la memoria de teselas: sin tope, hasta que una tesela no cupo (entonces, la mitad de lo que había). */
    private var memoryCap = Int.MAX_VALUE
    /** Pila: lo último que se pidió es lo que se ve ahora. */
    private val queue = ArrayDeque<Long>()
    private val pending = HashSet<Long>()
    /** Las que fallaron o vinieron vacías: no se piden hasta esa hora (solo de las que se ven). */
    private val retryAt = HashMap<Long, Long>()
    private var visible: Set<Long> = emptySet()
    /** Lo que pidió cada mapa que lo está viendo. */
    private val views = ViewRequests()
    private var scope: CoroutineScope? = null
    /** Cuántos mapas lo están viendo (solo en el hilo principal). */
    private var users = 0
    private val main = Handler(Looper.getMainLooper())
    /** Sin mapas que lo vean un momento seguido: deja de trabajar y suelta la memoria. */
    private val idle = Runnable { synchronized(lock) { if (users == 0) stop() } }
    /** Vuelve a pedir lo que falló cuando ya toca (nadie repinta para pedirlo). */
    private val retry = Runnable { synchronized(lock) { schedule() } }
    private var workers = 0
    private var writes = 0
    /** Ya se escribieron [TRIM_EVERY] teselas más: el siguiente trabajador recorta el disco (sin candados de archivo en la mano). */
    @Volatile private var trimDue = false
    /** El servidor pidió calma (429/503) o nos bloqueó (403/418): sin red hasta esa hora; se muestra lo guardado. */
    @Volatile private var backoffUntil = 0L

    /** Sube con cada tesela nueva: el mapa lo lee al dibujar y se repinta. */
    var loaded by mutableIntStateOf(0)
        private set

    fun tile(key: Long): ImageBitmap? = memory.get(key)

    /**
     * Las teselas que ve ahora el mapa [view] (el widget y el mapa en grande comparten almacén: cuenta lo de todos):
     * se cargan las que falten; las que ya nadie ve salen de la fila. Se llama solo cuando cambian.
     */
    fun request(view: Any, keys: Set<Long>) = synchronized(lock) {
        views.set(view, keys)
        schedule()
    }

    /** El mapa [view] ya no se ve (o lo taparon): lo suyo deja de pedirse. */
    fun forget(view: Any) = synchronized(lock) {
        if (views.remove(view)) schedule()
    }

    private fun schedule() {
        val keys = views.union()
        visible = keys
        retryAt.keys.retainAll(keys)
        fitMemory(keys.size)
        val s = scope ?: return
        val now = System.currentTimeMillis()
        var nextRetry = Long.MAX_VALUE
        for (k in keys) {
            val at = retryAt[k] ?: 0L
            if (at > now) nextRetry = minOf(nextRetry, at)
            if (k in pending || at > now || memory.get(k) != null) continue
            pending += k
            queue.addLast(k)
        }
        main.removeCallbacks(retry)
        if (nextRetry != Long.MAX_VALUE) main.postDelayed(retry, nextRetry - now)
        while (workers < MAX_DOWNLOADS && queue.isNotEmpty()) {
            workers++
            s.launch { work(s) }
        }
    }

    /**
     * Que siempre quepan las [visible] que se ven (inclinado son más que en plano) y, si la memoria da, otro tanto: las
     * del zoom anterior, que tapan el hueco mientras llegan las nuevas. Sin nada visible no se toca: la suelta [stop].
     */
    private fun fitMemory(visible: Int) {
        val roomy = (Runtime.getRuntime().maxMemory() / MEMORY_SHARE).toInt()
        memoryResize(memory.maxSize(), visible, tileBytes, roomy, memoryCap)?.let(memory::resize)
    }

    /** Un mapa más lo está viendo (el widget y el mapa en grande comparten almacén): trabaja mientras haya alguno. */
    fun resume() = synchronized(lock) {
        users++
        main.removeCallbacks(idle)
        if (scope != null) return@synchronized
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { it.launch { trim(force = false) } }
        // Lo que ya se dibujó antes de arrancar (el primer cuadro llega antes que este efecto).
        schedule()
    }

    /**
     * Un mapa menos. Sin ninguno un momento seguido ([RELEASE_MS]), deja de descargar y suelta la memoria (la app se fue
     * o el mapa ya no se ve): al abrir el mapa en grande el widget se va antes de que llegue el otro, y así no se tira nada.
     */
    fun pause() = synchronized(lock) {
        users = (users - 1).coerceAtLeast(0)
        if (users > 0) return@synchronized
        main.removeCallbacks(idle)
        main.postDelayed(idle, RELEASE_MS)
    }

    /** Ya nadie lo ve: sin trabajadores, fila ni memoria (también lo que se guardó leído de la fuente). */
    private fun stop() {
        scope?.cancel()
        scope = null
        queue.clear()
        pending.clear()
        retryAt.clear()
        workers = 0
        main.removeCallbacks(retry)
        memory.evictAll()
        source.trim()
    }

    /** Un trabajador de [owner]: si mientras tanto se pausó (o se pausó y se reanudó), sale sin tocar nada. */
    private fun work(owner: CoroutineScope) {
        while (owner.isActive) {
            val key = synchronized(lock) {
                if (scope !== owner) return
                var next: Long? = null
                while (next == null && queue.isNotEmpty()) {
                    val k = queue.removeLast()
                    if (k in visible) next = k else pending -= k
                }
                if (next == null) workers--
                next
            } ?: return
            val result = try {
                load(key)
            } catch (_: OutOfMemoryError) {
                // No cupo una tesela más: la memoria de teselas baja a la mitad y ya no crece más que eso.
                synchronized(lock) {
                    memoryCap = maxOf(MEMORY_MIN_BYTES, memory.size() / 2)
                    memory.resize(memoryCap)
                }
                Loaded(null, System.currentTimeMillis() + RETRY_MS)
            } catch (_: Exception) {
                // Sin red, disco o un archivo que no se pudo leer: otra vez al rato.
                Loaded(null, System.currentTimeMillis() + RETRY_MS)
            }
            if (trimDue) {
                trimDue = false
                trim(force = true)
            }
            val image = result.image
            synchronized(lock) {
                if (scope !== owner) return
                pending -= key
                if (image == null) {
                    retryAt[key] = result.retryAt
                    schedule()
                } else {
                    retryAt -= key
                    val bytes = image.asAndroidBitmap().allocationByteCount
                    // Más grande que las de antes: primero se agranda la memoria, que no saque a una que se ve.
                    if (bytes > tileBytes) {
                        tileBytes = bytes
                        fitMemory(visible.size)
                    }
                    memory.put(key, image)
                    loaded++
                }
            }
        }
    }

    private class Loaded(val image: ImageBitmap?, val retryAt: Long)

    /**
     * Del disco si sigue vigente; si no, de la red; sin red, la vencida (mejor que nada). Lo que no se
     * puede leer (PNG dañado, una página de portal cautivo) queda vencido para bajarlo otra vez.
     */
    private fun load(key: Long): Loaded {
        val origin = source.origin(key)
        return synchronized(fileLocks[Math.floorMod(origin.hashCode(), FILE_LOCKS)]) {
            val file = File(dir, "${keyZ(origin)}_${keyX(origin)}_${keyY(origin)}")
            val now = System.currentTimeMillis()
            // Primero lo descargado sin conexión (no caduca ni gasta datos); vacía = ahí no hay nada y se dibuja lisa.
            offline?.tile(keyZ(origin), keyX(origin), keyY(origin))?.let { body ->
                val image = decode(body, key, origin)
                if (image != null) return Loaded(image, 0L)
            }
            val tile = data(file, origin) ?: return Loaded(null, now + RETRY_MS)
            val image = decode(tile.body, key, origin)
            when {
                image != null -> Loaded(image, 0L)
                // Vacía (204/404): el minimalista la dibuja lisa; las de OSM no tienen qué mostrar hasta que caduque.
                tile.body.isEmpty() -> Loaded(null, maxOf(tile.expires, now + RETRY_MS))
                else -> {
                    // Sin ETag para que el servidor no conteste 304 con lo mismo (una vez: si ya estaba así, se queda).
                    if (tile.expires > 0L || tile.etag != null || tile.lastModified != null) save(file, TileFile(0L, null, null, tile.body))
                    Loaded(null, now + RETRY_MS)
                }
            }
        }
    }

    /** La imagen de lo bajado; null si está dañado (gzip roto, PNG a medias). Si no cabe en memoria, eso sí sube. */
    private fun decode(body: ByteArray, key: Long, origin: Long): ImageBitmap? = try {
        source.decode(body, key, origin)
    } catch (_: Exception) {
        null
    }

    private fun data(file: File, origin: Long): TileFile? {
        val now = System.currentTimeMillis()
        // Sin preguntar antes si existe: el recorte del disco la pudo borrar en medio (entonces no está y se baja).
        val cached = try {
            TileFile.decode(file.readBytes())
        } catch (_: FileNotFoundException) {
            null
        }
        if (cached != null) {
            file.setLastModified(now) // para el recorte: las más usadas se quedan
            if (cached.expires > now || now < backoffUntil) return cached
        }
        // Sin red (o el servidor tardó de más): lo guardado, aunque esté vencido.
        val fetched = try {
            fetch(origin, cached)
        } catch (_: IOException) {
            null
        }
        if (fetched != null) {
            save(file, fetched)
            return fetched
        }
        return cached
    }

    private fun fetch(origin: Long, cached: TileFile?): TileFile? {
        if (System.currentTimeMillis() < backoffUntil) return null
        val url = source.url(origin) ?: return null
        return httpGet(app, url, userAgent, setup = { c ->
            // Pedido así a mano, Android no lo descomprime: se guarda comprimido (ocupa la tercera parte).
            if (source.gzip) c.setRequestProperty("Accept-Encoding", "gzip")
            cached?.etag?.let { c.setRequestProperty("If-None-Match", it) }
            cached?.lastModified?.let { c.setRequestProperty("If-Modified-Since", it) }
        }) { c, code ->
            val now = System.currentTimeMillis()
            when (tileResponse(code)) {
                TileResponse.OK -> {
                    // Un portal cautivo contesta 200 con su página: no se guarda.
                    if (c.contentType?.startsWith("text/html") == true) return@httpGet null
                    TileFile(
                        expiresAt(now, c.getHeaderField("Cache-Control"), c.getHeaderField("Expires"), c.getHeaderField("Date")),
                        c.getHeaderField("ETag") ?: cached?.etag,
                        c.getHeaderField("Last-Modified") ?: cached?.lastModified,
                        c.inputStream.use { it.readBytes() },
                    )
                }
                TileResponse.NOT_MODIFIED -> cached?.let {
                    TileFile(
                        expiresAt(now, c.getHeaderField("Cache-Control"), c.getHeaderField("Expires"), c.getHeaderField("Date")),
                        c.getHeaderField("ETag") ?: it.etag,
                        c.getHeaderField("Last-Modified") ?: it.lastModified,
                        it.body,
                    )
                }
                TileResponse.EMPTY -> TileFile(now + EMPTY_MS, null, null, ByteArray(0))
                TileResponse.SLOW_DOWN -> null.also { backoffUntil = now + BACKOFF_MS }
                TileResponse.BLOCKED -> null.also { backoffUntil = now + BLOCKED_MS }
                TileResponse.FAILED -> null
            }
        }
    }

    private fun save(file: File, tile: TileFile) {
        dir.mkdirs()
        val temp = File.createTempFile(file.name, ".tmp", dir)
        temp.writeBytes(tile.encode())
        if (!temp.renameTo(file)) temp.delete()
        if (synchronized(lock) { ++writes } % TRIM_EVERY == 0) trimDue = true
    }

    /**
     * Recorta la caché en disco a [DISK_MAX] borrando las teselas que hace más que no se usan. Al
     * reanudar, a lo más una vez por hora (recorrer miles de archivos cada vez que se ve el mapa no vale).
     */
    private fun trim(force: Boolean) {
        val now = System.currentTimeMillis()
        synchronized(lastTrim) {
            if (!force && now - (lastTrim[source.folder] ?: 0L) < TRIM_INTERVAL_MS) return
            lastTrim[source.folder] = now
        }
        val files = dir.listFiles { f -> f.isFile && f.name != "tilejson" } ?: return
        val names = tilesToTrim(files.map { CachedTile(it.name, it.length(), it.lastModified()) }, DISK_MAX, DISK_TARGET)
        for (name in names) File(dir, name).delete()
    }

    private companion object {
        const val FILE_LOCKS = 32
        /** La memoria de teselas no pasa de esta parte de la que tiene la app (salvo que no quepa lo que se ve). */
        const val MEMORY_SHARE = 6
        const val RELEASE_MS = 1_500L
        const val RETRY_MS = 20_000L
        const val BACKOFF_MS = 120_000L
        const val BLOCKED_MS = 3_600_000L
        const val EMPTY_MS = 86_400_000L
        const val TRIM_EVERY = 100
        const val TRIM_INTERVAL_MS = 3_600_000L
        // Por carpeta (map_tiles y map_vector): alternando estilos, entre las dos no pasan de ~50 MB.
        const val DISK_MAX = 25L * 1024 * 1024
        const val DISK_TARGET = 20L * 1024 * 1024
        /** Último recorte por carpeta (los almacenes se recrean al cambiar de estilo). */
        val lastTrim = HashMap<String, Long>()
    }
}

/**
 * Un GET con el User-Agent de LiteDash: [setup] pone encabezados y [read] lee la respuesta con su código.
 * Sin `disconnect()`: al cerrar el stream la conexión queda viva (keep-alive) para la siguiente.
 */
internal inline fun <T> httpGet(
    context: Context,
    url: String,
    userAgent: String,
    setup: (HttpURLConnection) -> Unit = {},
    read: (HttpURLConnection, Int) -> T,
): T {
    val c = Https.open(context, url)
    c.connectTimeout = 8_000
    c.readTimeout = 10_000
    c.setRequestProperty("User-Agent", userAgent)
    setup(c)
    val code = c.responseCode
    try {
        return read(c, code)
    } finally {
        runCatching { (if (code >= 400) c.errorStream else c.inputStream)?.close() }
    }
}
