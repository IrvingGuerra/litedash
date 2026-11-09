package com.irvingguerra.litedash.feature.map

import android.content.Context
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import com.irvingguerra.litedash.core.ui.Https
import java.io.File
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
    /** Una descarga (y una lectura) por archivo: varias teselas del 15–17 salen de la misma del 14. */
    private val fileLocks = Array(FILE_LOCKS) { Any() }
    private val lock = Any()
    private val memory = object : LruCache<Long, ImageBitmap>(MEMORY_MIN_BYTES) {
        override fun sizeOf(key: Long, value: ImageBitmap) = value.asAndroidBitmap().allocationByteCount
    }
    /** El tamaño de una tesela en memoria (de la más grande que ha llegado). */
    private var tileBytes = 0
    /** Pila: lo último que se pidió es lo que se ve ahora. */
    private val queue = ArrayDeque<Long>()
    private val pending = HashSet<Long>()
    /** Las que fallaron o vinieron vacías: no se piden hasta esa hora (solo de las que se ven). */
    private val retryAt = HashMap<Long, Long>()
    private var visible: Set<Long> = emptySet()
    private var scope: CoroutineScope? = null
    private var workers = 0
    private var writes = 0
    /** El servidor pidió calma (429/503) o nos bloqueó (403/418): sin red hasta esa hora; se muestra lo guardado. */
    @Volatile private var backoffUntil = 0L

    /** Sube con cada tesela nueva: el mapa lo lee al dibujar y se repinta. */
    var loaded by mutableIntStateOf(0)
        private set

    fun tile(key: Long): ImageBitmap? = memory.get(key)

    /** Las teselas que se ven ahora: se cargan las que falten; las que ya no se ven salen de la fila. */
    fun request(keys: Set<Long>) = synchronized(lock) {
        visible = keys
        retryAt.keys.retainAll(keys)
        // Que siempre quepa lo que se ve (inclinado son más que en plano).
        val needed = memoryBytes(keys.size, tileBytes)
        if (needed > memory.maxSize()) memory.resize(needed)
        val s = scope ?: return@synchronized
        val now = System.currentTimeMillis()
        for (k in keys) {
            if (k in pending || memory.get(k) != null || (retryAt[k] ?: 0L) > now) continue
            pending += k
            queue.addLast(k)
        }
        while (workers < MAX_DOWNLOADS && queue.isNotEmpty()) {
            workers++
            s.launch { work(s) }
        }
    }

    fun resume() = synchronized(lock) {
        if (scope != null) return@synchronized
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { it.launch { trim(force = false) } }
        // Lo que ya se dibujó antes de arrancar (el primer cuadro llega antes que este efecto).
        request(visible)
    }

    /** Deja de descargar y suelta la memoria (la app se fue o el mapa ya no se ve). */
    fun pause() = synchronized(lock) {
        scope?.cancel()
        scope = null
        queue.clear()
        pending.clear()
        retryAt.clear()
        workers = 0
        memory.evictAll()
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
            val result = runCatching { load(key) }.getOrElse { Loaded(null, System.currentTimeMillis() + RETRY_MS) }
            val image = result.image
            synchronized(lock) {
                if (scope !== owner) return
                pending -= key
                if (image == null) retryAt[key] = result.retryAt
                else {
                    retryAt -= key
                    tileBytes = maxOf(tileBytes, image.asAndroidBitmap().allocationByteCount)
                    memory.put(key, image)
                }
            }
            if (image != null) loaded++
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
            val tile = data(file, origin) ?: return Loaded(null, now + RETRY_MS)
            val image = runCatching { source.decode(tile.body, key, origin) }.getOrNull()
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

    private fun data(file: File, origin: Long): TileFile? {
        val now = System.currentTimeMillis()
        val cached = if (file.exists()) TileFile.decode(file.readBytes()) else null
        if (cached != null) {
            file.setLastModified(now) // para el recorte: las más usadas se quedan
            if (cached.expires > now || now < backoffUntil) return cached
        }
        val fetched = runCatching { fetch(origin, cached) }.getOrNull()
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
        if (synchronized(lock) { ++writes } % TRIM_EVERY == 0) trim(force = true)
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
        const val MAX_DOWNLOADS = 2
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
