package com.irvingguerra.litedash.feature.map

import com.irvingguerra.litedash.core.ui.Https
import com.irvingguerra.litedash.core.ui.NoSpaceException
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException

// Lo que se hace con los archivos de un mapa bajado antes de usarlo (abrir los gzip, revisar y ponerlos en su lugar), sin
// Android: se prueba con una carpeta temporal. Fuera del hilo principal.

/** Lo bajado llegó bien pero por dentro no sirve (gzip roto, mapa con calles, `.ldm` sin dibujo): se borra. */
internal class BadPackException(what: String) : IOException(what)

/**
 * Abre el gzip [gz] en [out] y lo deja escrito en el disco (`sync`: un corte de corriente no deja un archivo a medias que
 * después se renombre). Cada [CHECK_BYTES] mira [active]; si ya no, [CancellationException]. Disco lleno =
 * [NoSpaceException]; gzip dañado = [BadPackException]; otra falla al leer o escribir (el disco), la [IOException] tal
 * cual (lo bajado se queda).
 */
internal fun gunzip(gz: File, out: File, active: () -> Boolean = { true }) {
    val input: InputStream = try {
        GZIPInputStream(gz.inputStream())
    } catch (_: ZipException) {
        throw BadPackException("gzip")
    } catch (_: EOFException) {
        throw BadPackException("gzip")
    }
    input.use {
        try {
            FileOutputStream(out).use { output ->
                val buf = ByteArray(BUFFER)
                var since = 0L
                while (true) {
                    val n = try {
                        input.read(buf)
                    } catch (_: ZipException) {
                        throw BadPackException("gzip")
                    } catch (_: EOFException) {
                        throw BadPackException("gzip")
                    }
                    if (n < 0) break
                    output.write(buf, 0, n)
                    since += n
                    if (since >= CHECK_BYTES) {
                        since = 0
                        if (!active()) throw CancellationException()
                    }
                }
                output.fd.sync()
            }
        } catch (e: IOException) {
            if (e !is BadPackException && Https.noSpace(e, out)) throw NoSpaceException(e)
            throw e
        }
    }
}

/** Deja [file] escrito en el disco antes de renombrarlo (lo que bajó la red no se sincronizó). */
internal fun syncFile(file: File) {
    FileOutputStream(file, true).use { it.fd.sync() }
}

/**
 * Un paquete del formato 2 bajado: el mapa ([map], `.pmtiles` a medias), la búsqueda y las calles con gzip ([gz]) y
 * abiertos ([raw]), sus nombres finales ([finals], en el mismo orden) y el del mapa ([target]). Se abre y se revisa
 * junto ([unpack]) y luego se pone en su lugar ([install]): nunca un mapa nuevo con calles viejas.
 */
internal class PackBundle(val map: File, val gz: List<File>, val raw: List<File>, val finals: List<File>, val target: File) {
    /** El índice de las calles en [raw] (búsqueda, calles). */
    private val routes = raw.size - 1

    /**
     * Abre los gzip y revisa los tres: el mapa sin calles y el `.ldm` con su dibujo, ninguno vacío. Si por dentro no
     * sirven, [BadPackException]; cancelado, [CancellationException]; disco lleno, [NoSpaceException].
     */
    fun unpack(active: () -> Boolean) {
        for (i in gz.indices) gunzip(gz[i], raw[i], active)
        if (!active()) throw CancellationException()
        // Que se dejen leer (si el disco falla, eso no es culpa de lo bajado: se queda); luego, que sirvan.
        for (f in listOf(map) + raw) f.inputStream().use { it.read() }
        if (!check()) throw BadPackException("bundle")
    }

    /** ¿Los tres sirven? */
    fun check(): Boolean = map.length() > 0 && raw.all { it.length() > 0 } &&
        PmTiles.open(map)?.use { !it.hasRoads } == true && RoadGraph.hasDrawing(raw[routes])

    /**
     * Los pone en su lugar: primero búsqueda y calles, al final el mapa (lo que dice que el paquete está). Si ya no se
     * quiere ([active]), no toca nada.
     */
    fun install(active: () -> Boolean) {
        if (!active()) throw CancellationException()
        syncFile(map)
        for (i in raw.indices) if (!raw[i].renameTo(finals[i])) throw IOException("rename")
        if (!map.renameTo(target)) throw IOException("rename")
    }
}

/** ¿[now] sigue siendo el mapa [before] (mismo formato y huella)? Si no, lo que se bajó aparte para él ya no le toca. */
internal fun samePack(before: InstalledPack, now: InstalledPack?): Boolean =
    now != null && now.format == before.format && now.sha256.equals(before.sha256, ignoreCase = true)

/**
 * Qué archivos de ruta abiertos ([open], del menos usado al más usado, con lo que pesan) se sueltan antes de abrir uno de
 * [adding] bytes: que no pasen de [max] ni de [budget] bytes juntos. [keep] (el del ruteador) nunca se suelta.
 */
internal fun graphsToDrop(open: List<Pair<String, Long>>, adding: Long, max: Int, budget: Long, keep: String?): List<String> {
    val out = ArrayList<String>()
    var count = open.size
    var bytes = open.sumOf { it.second }
    for ((id, size) in open) {
        if (count + 1 <= max && bytes + adding <= budget) break
        if (id == keep) continue
        out += id
        count--
        bytes -= size
    }
    return out
}

/** Qué hacer con las calles que le faltan a un mapa del formato 2 ([MapPacks] las pide solas). */
internal enum class RoutesAsk {
    /** Aún no toca (se intentó hace poco o no hay internet). */
    WAIT,
    /** Bajarlas ya, sin avisar. */
    FETCH,
    /** Ofrecer «Bajar calles» (red medida o ya se intentó muchas veces). */
    OFFER,
    /** No caben: decir que falta espacio. */
    NO_ROOM,
}

/**
 * Las calles que faltan se bajan solas solo con internet ([online]) que no se cobre por dato ([metered]) y espacio
 * ([roomy]); cada intento fallido espera el doble (de [ROUTES_ASK_MS] a [ROUTES_ASK_MAX_MS]) y tras [ROUTES_MAX_TRIES]
 * ya no se insiste: se ofrece el botón. [tries] = intentos solos en esta sesión; [lastAt] = el último.
 */
internal fun routesAsk(online: Boolean, metered: Boolean, roomy: Boolean, tries: Int, lastAt: Long, now: Long): RoutesAsk = when {
    tries >= ROUTES_MAX_TRIES -> RoutesAsk.OFFER
    tries > 0 && now - lastAt < routesBackoff(tries) -> RoutesAsk.WAIT
    !roomy -> RoutesAsk.NO_ROOM
    metered -> RoutesAsk.OFFER
    !online -> RoutesAsk.WAIT
    else -> RoutesAsk.FETCH
}

/** Cuánto se espera tras [tries] intentos fallidos: 1 min, 2, 4… hasta una hora. */
internal fun routesBackoff(tries: Int): Long = (ROUTES_ASK_MS shl (tries - 1).coerceIn(0, 6)).coerceAtMost(ROUTES_ASK_MAX_MS)

/** El primer reintento de las calles que faltan; luego, el doble cada vez hasta [ROUTES_ASK_MAX_MS]. */
internal const val ROUTES_ASK_MS = 60_000L
internal const val ROUTES_ASK_MAX_MS = 3_600_000L
/** Intentos solos por sesión; después solo con el botón. */
internal const val ROUTES_MAX_TRIES = 6

private const val BUFFER = 64 * 1024
/** Cada cuánto se mira si cancelaron mientras se abre un gzip. */
private const val CHECK_BYTES = 4L shl 20
