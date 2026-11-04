package com.irvingguerra.litedash.feature.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.irvingguerra.litedash.core.ui.Https
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Las teselas de OpenStreetMap: memoria (LRU chica), disco (`filesDir/map_tiles`, ~50 MB) y red,
 * siguiendo la política de uso de OSM (operations.osmfoundation.org/policies/tiles): User-Agent
 * propio, caché según Cache-Control/Expires con revalidación (ETag/If-Modified-Since), a lo más
 * [MAX_DOWNLOADS] descargas a la vez y solo lo que se ve (lo último que se pidió va primero).
 * Solo trabaja entre [resume] y [pause] (el widget a la vista y la app al frente).
 */
class TileStore(context: Context, private val userAgent: String) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "map_tiles")
    private val lock = Any()
    private val memory = LruCache<Long, ImageBitmap>(MEMORY_TILES)
    /** Pila: lo último que se pidió es lo que se ve ahora. */
    private val queue = ArrayDeque<Long>()
    private val pending = HashSet<Long>()
    private val retryAt = HashMap<Long, Long>()
    private var visible: Set<Long> = emptySet()
    private var scope: CoroutineScope? = null
    private var workers = 0
    private var writes = 0
    /** El servidor pidió calma (429/503): sin red hasta esa hora; se muestra lo guardado. */
    @Volatile private var backoffUntil = 0L

    /** Sube con cada tesela nueva: el mapa lo lee al dibujar y se repinta. */
    var loaded by mutableIntStateOf(0)
        private set

    fun tile(key: Long): ImageBitmap? = memory.get(key)

    /** Las teselas que se ven ahora: se cargan las que falten; las que ya no se ven salen de la fila. */
    fun request(keys: Set<Long>) = synchronized(lock) {
        visible = keys
        val s = scope ?: return@synchronized
        val now = System.currentTimeMillis()
        for (k in keys) {
            if (k in pending || memory.get(k) != null || (retryAt[k] ?: 0L) > now) continue
            pending += k
            queue.addLast(k)
        }
        while (workers < MAX_DOWNLOADS && queue.isNotEmpty()) {
            workers++
            s.launch { work() }
        }
    }

    fun resume() = synchronized(lock) {
        if (scope != null) return@synchronized
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { it.launch { trim() } }
        // Lo que ya se dibujó antes de arrancar (el primer cuadro llega antes que este efecto).
        request(visible)
    }

    /** Deja de descargar y suelta la memoria (la app se fue o el widget ya no se ve). */
    fun pause() {
        synchronized(lock) {
            scope?.cancel()
            scope = null
            queue.clear()
            pending.clear()
            workers = 0
        }
        memory.evictAll()
    }

    private fun CoroutineScope.work() {
        while (isActive) {
            val key = synchronized(lock) {
                var next: Long? = null
                while (next == null && queue.isNotEmpty()) {
                    val k = queue.removeLast()
                    if (k in visible) next = k else pending -= k
                }
                if (next == null) workers--
                next
            } ?: return
            val image = runCatching { load(key) }.getOrNull()
            synchronized(lock) {
                pending -= key
                if (image == null) retryAt[key] = System.currentTimeMillis() + RETRY_MS
                else {
                    retryAt -= key
                    memory.put(key, image)
                }
            }
            if (image != null) loaded++
        }
    }

    /** Del disco si sigue vigente; si no, de la red; sin red, la vencida (mejor que nada). */
    private fun load(key: Long): ImageBitmap? {
        val file = File(dir, "${keyZ(key)}_${keyX(key)}_${keyY(key)}")
        val now = System.currentTimeMillis()
        val cached = if (file.exists()) TileFile.decode(file.readBytes()) else null
        if (cached != null) {
            file.setLastModified(now) // para el recorte: las más usadas se quedan
            if (cached.expires > now || now < backoffUntil) decode(cached.png)?.let { return it }
        }
        val fetched = runCatching { fetch(key, cached) }.getOrNull()
        if (fetched != null) decode(fetched.png)?.let { image ->
            save(file, fetched)
            return image
        }
        return cached?.let { decode(it.png) }
    }

    private fun fetch(key: Long, cached: TileFile?): TileFile? {
        if (System.currentTimeMillis() < backoffUntil) return null
        val c = Https.open(app, "https://tile.openstreetmap.org/${keyZ(key)}/${keyX(key)}/${keyY(key)}.png")
        try {
            c.connectTimeout = 8_000
            c.readTimeout = 10_000
            c.setRequestProperty("User-Agent", userAgent)
            cached?.etag?.let { c.setRequestProperty("If-None-Match", it) }
            cached?.lastModified?.let { c.setRequestProperty("If-Modified-Since", it) }
            val code = c.responseCode
            val now = System.currentTimeMillis()
            val png = when (code) {
                200 -> c.inputStream.use { it.readBytes() }
                304 -> cached?.png ?: return null
                else -> {
                    if (code == 429 || code == 503) backoffUntil = now + BACKOFF_MS
                    return null
                }
            }
            return TileFile(
                expiresAt(now, c.getHeaderField("Cache-Control"), c.getHeaderField("Expires"), c.getHeaderField("Date")),
                c.getHeaderField("ETag") ?: cached?.etag,
                c.getHeaderField("Last-Modified") ?: cached?.lastModified,
                png,
            )
        } finally {
            c.disconnect()
        }
    }

    private fun decode(png: ByteArray): ImageBitmap? {
        // Las teselas no traen transparencia: a 16 bits ocupan la mitad.
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        return BitmapFactory.decodeByteArray(png, 0, png.size, options)?.asImageBitmap()
    }

    private fun save(file: File, tile: TileFile) {
        dir.mkdirs()
        val temp = File.createTempFile(file.name, ".tmp", dir)
        temp.writeBytes(tile.encode())
        if (!temp.renameTo(file)) temp.delete()
        if (synchronized(lock) { ++writes } % TRIM_EVERY == 0) trim()
    }

    /** Recorta la caché en disco a [DISK_MAX] borrando las teselas que hace más que no se usan. */
    private fun trim() {
        val files = dir.listFiles() ?: return
        val names = tilesToTrim(files.map { CachedTile(it.name, it.length(), it.lastModified()) }, DISK_MAX, DISK_TARGET)
        for (name in names) File(dir, name).delete()
    }

    private companion object {
        const val MEMORY_TILES = 36
        const val MAX_DOWNLOADS = 2
        const val RETRY_MS = 20_000L
        const val BACKOFF_MS = 120_000L
        const val TRIM_EVERY = 100
        const val DISK_MAX = 50L * 1024 * 1024
        const val DISK_TARGET = 40L * 1024 * 1024
    }
}
