package com.irvingguerra.litedash.feature.map

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
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
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Las teselas del mapa de [source]: memoria (LRU por bytes que siempre cabe lo que se ve), disco
 * (`filesDir/<carpeta>`, ~25 MB por estilo) y red, siguiendo la política de uso de OpenFreeMap
 * (como la de operations.osmfoundation.org/policies/tiles): User-Agent propio, caché
 * según Cache-Control/Expires con revalidación (ETag/If-Modified-Since), a lo más [MAX_DOWNLOADS]
 * descargas a la vez, solo lo que se ve (lo último que se pidió va primero) y parar si nos bloquean.
 * Solo trabaja entre [resume] y [pause] (el mapa a la vista y la app al frente).
 */
class TileStore(context: Context, private val userAgent: String, val source: TileSource) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, source.folder)
    /** Las zonas descargadas. */
    private val offline = if (source is VectorTiles) OfflineMaps.get(app) else null
    /** Los mapas de estado bajados (mismo esquema que el minimalista): van antes que las zonas. */
    private val packs = if (source is VectorTiles) MapPacks.get(app) else null
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
    /** Sube cuando cambian los mapas bajados ([packsChanged]): lo que se leyó antes ya no se guarda. */
    private var generation = 0
    /** Lo que pidió cada mapa que lo está viendo. */
    private val views = ViewRequests()
    private var scope: CoroutineScope? = null
    /**
     * Los hilos de los trabajadores y del recorte, en segundo plano para no competir con la interfaz (propios: bajar la
     * prioridad de los de `Dispatchers.IO` la dejaría baja para todo lo demás). Cuántos, lo limita [MAX_DOWNLOADS] en
     * [schedule]; sin tope aquí para que uno de antes de pausar, aún esperando la red, no frene a los nuevos. Se cierran
     * solos tras un rato sin trabajo.
     */
    private val workerThreads = ThreadPoolExecutor(0, Int.MAX_VALUE, WORKER_IDLE_S, TimeUnit.SECONDS, SynchronousQueue()) { r ->
        Thread({ Process.setThreadPriority(WORKER_PRIORITY); r.run() }, "map-tiles")
    }.asCoroutineDispatcher()
    /** Cuántos mapas lo están viendo (solo en el hilo principal). */
    private var users = 0
    private val main = Handler(Looper.getMainLooper())
    /**
     * Sin mapas que lo vean un momento seguido: deja de trabajar. Con la app al frente (se cerró ese mapa, cambió el
     * estilo, la guía puso los suyos) suelta la memoria ya; si la app se fue (cámara de reversa, otra app), la guarda
     * [KEEP_MEMORY_MS] por si vuelves ([release]).
     */
    private val idle = Runnable {
        synchronized(lock) {
            if (users == 0) {
                stop()
                if (appInFront()) releaseMemory() else main.postDelayed(release, KEEP_MEMORY_MS)
            }
        }
    }
    /** Lleva un buen rato sin que nadie lo vea: suelta la memoria. */
    private val release = Runnable { synchronized(lock) { if (users == 0 && scope == null) releaseMemory() } }
    /** Vuelve a pedir lo que falló cuando ya toca (nadie repinta para pedirlo). */
    private val retry = Runnable { synchronized(lock) { schedule() } }
    private var workers = 0
    private var writes = 0
    /** Ya se escribieron [TRIM_EVERY] teselas más: el siguiente trabajador recorta el disco (sin candados de archivo en la mano). */
    @Volatile private var trimDue = false
    /** El servidor pidió calma (429/503) o nos bloqueó (403/418): sin red hasta esa hora; se muestra lo guardado. */
    @Volatile private var backoffUntil = 0L

    /**
     * Sube cuando llegan teselas nuevas: el mapa lo lee al dibujar y se repinta. A lo más una vez cada [SHOW_MS] mientras
     * siguen llegando (40 teselas no son 40 cuadros) y siempre una vez más tras la última.
     */
    var loaded by mutableIntStateOf(0)
        private set
    /** Cuándo subió [loaded] por última vez ([SystemClock.uptimeMillis]) y si ya está pendiente la siguiente. */
    private var shownAt = 0L
    private var showPending = false
    private val show = Runnable { synchronized(lock) { showNow() } }

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
        main.removeCallbacks(release)
        if (scope != null) return@synchronized
        scope = CoroutineScope(SupervisorJob() + workerThreads).also { it.launch { trim(force = false) } }
        // Lo que ya se dibujó antes de arrancar (el primer cuadro llega antes que este efecto).
        schedule()
    }

    /**
     * Un mapa menos. Sin ninguno un momento seguido ([RELEASE_MS]), deja de descargar (la app se fue o el mapa ya no se
     * ve): al abrir el mapa en grande el widget se va antes de que llegue el otro, y así no se tira nada. La memoria se
     * suelta ya si la app sigue al frente; si se fue, se queda [KEEP_MEMORY_MS] más (volver de la cámara de reversa no
     * rearma el mapa) o hasta que Android pida ([trimAll]).
     */
    fun pause() = synchronized(lock) {
        users = (users - 1).coerceAtLeast(0)
        if (users > 0) return@synchronized
        main.removeCallbacks(idle)
        main.postDelayed(idle, RELEASE_MS)
    }

    /** Ya nadie lo ve: sin trabajadores ni fila (la memoria la suelta [idle] o [release]). */
    private fun stop() {
        scope?.cancel()
        scope = null
        queue.clear()
        pending.clear()
        retryAt.clear()
        workers = 0
        main.removeCallbacks(retry)
        main.removeCallbacks(show)
        showPending = false
    }

    /** Suelta las teselas en memoria y lo leído de la fuente; el tope vuelve a empezar (un OOM pasajero no es para siempre). */
    private fun releaseMemory() {
        memory.evictAll()
        source.trim()
        memoryCap = Int.MAX_VALUE
    }

    init {
        synchronized(all) { all += this }
    }

    /** Un trabajador de [owner]: si mientras tanto se pausó (o se pausó y se reanudó), sale sin tocar nada. */
    private fun work(owner: CoroutineScope) {
        while (owner.isActive) {
            var gen = 0
            val key = synchronized(lock) {
                if (scope !== owner) return
                var next: Long? = null
                while (next == null && queue.isNotEmpty()) {
                    val k = queue.removeLast()
                    if (k in visible) next = k else pending -= k
                }
                if (next == null) workers--
                gen = generation
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
                if (gen != generation) {
                    // Se leyó con los mapas de antes: se vuelve a pedir (y lo que la fuente guardó de esa lectura, fuera).
                    val origin = source.origin(key)
                    source.forget { it == origin }
                    schedule()
                } else if (image == null) {
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
                    showLoaded()
                }
            }
        }
    }

    /** Una tesela más en memoria (con [lock]): avisa ya o, si se avisó hace poco, al cumplirse [SHOW_MS]. */
    private fun showLoaded() {
        val wait = shownAt + SHOW_MS - SystemClock.uptimeMillis()
        when {
            wait <= 0L -> {
                main.removeCallbacks(show)
                showNow()
            }
            !showPending -> {
                showPending = true
                main.postDelayed(show, wait)
            }
        }
    }

    private fun showNow() {
        showPending = false
        shownAt = SystemClock.uptimeMillis()
        loaded++
    }

    private class Loaded(val image: ImageBitmap?, val retryAt: Long)

    /**
     * Del disco si sigue vigente; si no, de la red; sin red, la vencida (mejor que nada). Lo que no se
     * puede leer (PNG dañado, una página de portal cautivo) queda vencido para bajarlo otra vez.
     */
    private fun load(key: Long): Loaded {
        val origin = source.origin(key)
        return synchronized(fileLock(source.folder, origin)) {
            // Los pedazos del 15 en adelante de una tesela ya leída se dibujan sin volver al disco.
            draw(key, origin)?.let { return Loaded(it, 0L) }
            val file = File(dir, "${keyZ(origin)}_${keyX(origin)}_${keyY(origin)}")
            val now = System.currentTimeMillis()
            // Primero lo descargado sin conexión (el mapa de tu estado y luego tus zonas: no caducan ni gastan datos);
            // vacía = ahí no hay nada y se dibuja lisa.
            val pack = packs?.tile(keyZ(origin), keyX(origin), keyY(origin))
            (pack?.body ?: offline?.tile(keyZ(origin), keyX(origin), keyY(origin)))?.let { body ->
                val image = decode(body, key, origin, pack?.roads)
                if (image != null) return Loaded(image, 0L)
            }
            val tile = data(file, origin) ?: return Loaded(null, now + RETRY_MS)
            val image = decode(tile.body, key, origin)
            when {
                image != null -> Loaded(image, 0L)
                // Vacía (204/404): el minimalista la dibuja lisa.
                tile.body.isEmpty() -> Loaded(null, maxOf(tile.expires, now + RETRY_MS))
                else -> {
                    // Sin ETag para que el servidor no conteste 304 con lo mismo (una vez: si ya estaba así, se queda).
                    if (tile.expires > 0L || tile.etag != null || tile.lastModified != null) save(file, TileFile(0L, null, null, tile.body))
                    Loaded(null, now + RETRY_MS)
                }
            }
        }
    }

    /**
     * La imagen de lo bajado; null si está dañado (gzip roto, PNG a medias). Si no cabe en memoria, suelta lo leído de la
     * fuente (miles de formas por tesela) y sube: el trabajador baja el tope de teselas en memoria.
     */
    private fun decode(body: ByteArray, key: Long, origin: Long, roads: RoadGraph? = null): ImageBitmap? = try {
        source.decode(body, key, origin, roads)
    } catch (_: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        source.trim()
        throw e
    }

    /** Como [decode], con lo que la fuente ya tiene leído en memoria; null = hay que leer los bytes. */
    private fun draw(key: Long, origin: Long): ImageBitmap? = try {
        source.draw(key, origin)
    } catch (_: Exception) {
        null
    }

    private fun data(file: File, origin: Long): TileFile? {
        val now = System.currentTimeMillis()
        // Largo 0 = no está. Aun así el recorte del disco la pudo borrar en medio: entonces no está y se baja.
        val cached = if (file.length() == 0L) null else try {
            TileFile.decode(file.readBytes())
        } catch (_: FileNotFoundException) {
            null
        }
        if (cached != null) {
            // Para el recorte: las más usadas se quedan (a lo más una escritura por hora). Con la fecha de la pantalla mal
            // no se toca: marcaría en 2015 justo las de uso diario y el recorte las borraría primero.
            if (!Https.clockLooksWrong() && kotlin.math.abs(now - file.lastModified()) > TOUCH_MS) file.setLastModified(now)
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
        try {
            temp.writeBytes(tile.encode())
            temp.renameTo(file)
        } finally {
            // Si no quedó con su nombre (disco lleno, no se pudo renombrar), el temporal no se queda tirado.
            temp.delete()
        }
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
        // Sin los temporales: alguno se puede estar escribiendo en este momento; los de hace rato los dejó un apagón.
        dir.listFiles { f -> f.name.endsWith(".tmp") && now - f.lastModified() > STALE_TEMP_MS }?.forEach { it.delete() }
        val files = dir.listFiles { f -> f.isFile && f.name != "tilejson" && !f.name.endsWith(".tmp") } ?: return
        val names = tilesToTrim(files.map { CachedTile(it.name, it.length(), it.lastModified()) }, DISK_MAX, DISK_TARGET)
        for (name in names) File(dir, name).delete()
    }

    companion object {
        private const val FILE_LOCKS = 32
        /**
         * Una descarga (y una lectura) por archivo, para todos los almacenes: varias teselas del 15 en adelante salen de
         * la misma del 14, y en la guía dos mapas piden la misma: el segundo espera y la lee del disco (sin otra descarga).
         */
        private val fileLocks = Array(FILE_LOCKS) { Any() }

        private fun fileLock(folder: String, origin: Long) = fileLocks[Math.floorMod(31 * folder.hashCode() + origin.hashCode(), FILE_LOCKS)]

        /** ¿La app se ve ahora? (Al frente o visible; sin dato, como si sí: suelta como antes.) */
        private fun appInFront(): Boolean = runCatching {
            val info = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(info)
            info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
        }.getOrDefault(true)

        /** Los almacenes vivos (para soltar su memoria si Android la pide). */
        private val all = java.util.Collections.newSetFromMap(java.util.WeakHashMap<TileStore, Boolean>())

        /**
         * Cambiaron los mapas bajados en la caja [bbox] (oeste, sur, este, norte; llegó un estado, sus calles o una versión
         * nueva): las teselas en memoria de esa caja se dibujaron con lo de antes (quizá sin calles), así que se sueltan y
         * lo que se ve se vuelve a pedir. Las de fuera se quedan.
         */
        internal fun packsChanged(bbox: DoubleArray) {
            val affected = { key: Long -> tileTouches(key, bbox) }
            val stores = synchronized(all) { all.toList() }
            for (s in stores) if (s.packs != null) s.main.post {
                synchronized(s.lock) {
                    s.generation++
                    for (key in s.memory.snapshot().keys) if (affected(key)) s.memory.remove(key)
                    s.source.forget(affected)
                    s.retryAt.keys.removeAll(affected)
                    s.schedule()
                    s.showLoaded()
                }
            }
        }

        /**
         * Android pide memoria (onTrimMemory): los almacenes que nadie ve la sueltan ya. Sirve igual con la app al frente
         * (TRIM_MEMORY_RUNNING_LOW/CRITICAL): no suelta lo que se está viendo. También las rutas en línea guardadas.
         */
        fun trimAll() {
            OnlineRoute.clearCache()
            val stores = synchronized(all) { all.toList() }
            for (s in stores) s.main.post { synchronized(s.lock) { if (s.users == 0 && s.scope == null) s.releaseMemory() } }
        }

        /**
         * Borra la caché del mapa «Completo» de OSM (`map_tiles/`, hasta ~25 MB), que ya no existe y nadie lee. La
         * carpeta misma es la marca: ya borrada, no hace nada. Lee y borra en disco: fuera del hilo principal.
         */
        fun dropRetiredCache(context: Context) {
            File(context.filesDir, RETIRED_FOLDER).takeIf { it.exists() }?.deleteRecursively()
        }

        /** La carpeta de la caché del mapa «Completo» de OSM (quitado). */
        private const val RETIRED_FOLDER = "map_tiles"

        /** La memoria de teselas no pasa de esta parte de la que tiene la app (salvo que no quepa lo que se ve). */
        private const val MEMORY_SHARE = 6
        /** Entre dos avisos de teselas nuevas ([loaded]). */
        private const val SHOW_MS = 80L
        /** Cada cuánto se marca como usado un archivo de la caché (para el recorte). */
        private const val TOUCH_MS = 3_600_000L
        /**
         * Menos que la interfaz, pero sin llegar al grupo «de fondo» de Android (prioridad 10 o más), que en Android 7 y
         * anteriores casi no recibe procesador mientras la interfaz trabaja: el mapa se quedaría con huecos al manejar.
         */
        private const val WORKER_PRIORITY = Process.THREAD_PRIORITY_BACKGROUND + Process.THREAD_PRIORITY_MORE_FAVORABLE
        /** Un hilo de teselas sin trabajo se cierra tras este tiempo. */
        private const val WORKER_IDLE_S = 30L
        private const val RELEASE_MS = 1_500L
        /** Con la app en segundo plano, la memoria se queda este rato por si vuelves (de la cámara de reversa, de otra app). */
        private const val KEEP_MEMORY_MS = 60_000L
        /** Un temporal de la caché más viejo que esto ya no se está escribiendo. */
        private const val STALE_TEMP_MS = 3_600_000L
        private const val RETRY_MS = 20_000L
        private const val BACKOFF_MS = 120_000L
        private const val BLOCKED_MS = 3_600_000L
        private const val EMPTY_MS = 86_400_000L
        private const val TRIM_EVERY = 100
        private const val TRIM_INTERVAL_MS = 3_600_000L
        // Por carpeta de caché (map_vector): no pasa de ~25 MB.
        private const val DISK_MAX = 25L * 1024 * 1024
        private const val DISK_TARGET = 20L * 1024 * 1024
        /** Último recorte por carpeta (los almacenes se recrean al cambiar de estilo). */
        private val lastTrim = HashMap<String, Long>()
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
    // A lo más [MAX_CONNECTIONS] a la vez entre todos los mapas y las zonas (las reglas de OpenFreeMap piden ≤ 4).
    tileConnections.acquire()
    try {
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
    } finally {
        tileConnections.release()
    }
}

/** Conexiones a los servidores de teselas a la vez, en toda la app. */
internal const val MAX_CONNECTIONS = 4
internal val tileConnections = java.util.concurrent.Semaphore(MAX_CONNECTIONS)

/** ¿La tesela [key] (z/x/y) toca la caja [bbox] (oeste, sur, este, norte; grados)? */
internal fun tileTouches(key: Long, bbox: DoubleArray): Boolean {
    val z = keyZ(key)
    val x = keyX(key).toDouble()
    val y = keyY(key).toDouble()
    return tileLon(x + 1, z) >= bbox[0] && tileLon(x, z) <= bbox[2] && tileLat(y, z) >= bbox[1] && tileLat(y + 1, z) <= bbox[3]
}
