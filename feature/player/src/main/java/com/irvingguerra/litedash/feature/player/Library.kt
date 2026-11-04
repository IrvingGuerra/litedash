package com.irvingguerra.litedash.feature.player

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import com.irvingguerra.litedash.feature.music.Drive
import com.irvingguerra.litedash.feature.music.Drives

/** Una canción de la memoria. */
class Track(
    override val uri: Uri,
    override val title: String,
    val artist: String,
    val album: String,
    /** Carpeta donde está, relativa a la memoria ("Rock/Metallica"). */
    val folder: String,
    val durationMs: Long,
    /** Nombre del archivo, para ordenar como en la carpeta. */
    val file: String,
) : Playable {
    override val subtitle get() = artist
    /** Por carpeta y archivo, no por id: MediaStore da ids nuevos cada vez que se reconecta la memoria. */
    override val key get() = "usb:$folder/$file"
}

/**
 * Las memorias conectadas y sus canciones: primero por MediaStore (Android ya las tiene
 * indexadas); si no trae nada, recorriendo las carpetas de la memoria (varias pantallas montan
 * el USB donde el índice de Android no lo revisa y su propio reproductor lee los archivos).
 */
object Library {
    /** El permiso para leer la música de la memoria, según la versión de Android. */
    val permission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasPermission(context: Context) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Memorias montadas ahora (las detecta la pieza de música: [Drives]). */
    fun drives(context: Context): List<Drive> = Drives.list(context)

    /** Lo último que se leyó de cada memoria (por [Drive.id]): play sigue sin volver a leerla. */
    private val cache = HashMap<String, List<Track>>()

    /** Las canciones ya leídas de [drive]; null = todavía no se ha leído desde que se conectó. */
    @Synchronized
    fun cached(drive: Drive): List<Track>? = cache[drive.id]

    /** Todas las canciones de [drive], ordenadas por carpeta y archivo (como se ven en la memoria). */
    fun tracks(context: Context, drive: Drive): List<Track> {
        val found = indexed(context, drive).ifEmpty { drive.path?.let { scan(File(it)) }.orEmpty() }
            .sortedWith(compareBy<Track, String>(String.CASE_INSENSITIVE_ORDER) { it.folder }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.file })
        synchronized(this) { cache[drive.id] = found }
        return found
    }

    /** Las de MediaStore (rápido, con artista, álbum y duración). */
    private fun indexed(context: Context, drive: Drive): List<Track> {
        val uri = if (drive.volume != null) MediaStore.Audio.Media.getContentUri(drive.volume) else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val root = drive.path?.trimEnd('/')
        // En API < 29 el volumen "external" junta todo: se filtra por la ruta de la memoria.
        val selection = buildString {
            append("${MediaStore.Audio.Media.IS_MUSIC} != 0")
            if (drive.volume == null && root != null) append(" AND ${MediaStore.Audio.Media.DATA} LIKE ?")
        }
        val args = if (drive.volume == null && root != null) arrayOf("$root/%") else null
        val projection = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DISPLAY_NAME,
        )
        val out = ArrayList<Track>()
        runCatching {
            context.contentResolver.query(uri, projection, selection, args, null)?.use { c ->
                val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val title = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val album = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val duration = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val data = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val display = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                while (c.moveToNext()) {
                    val path = c.getString(data).orEmpty()
                    val file = c.getString(display) ?: path.substringAfterLast('/')
                    val folder = path.substringBeforeLast('/', "").let { if (root != null) it.removePrefix(root) else it }.trim('/')
                    out += Track(
                        uri = ContentUris.withAppendedId(uri, c.getLong(id)),
                        title = c.getString(title)?.takeIf { it.isNotBlank() } ?: file.substringBeforeLast('.'),
                        artist = c.getString(artist)?.takeIf { it.isNotBlank() && it != MediaStore.UNKNOWN_STRING } ?: "",
                        album = c.getString(album)?.takeIf { it.isNotBlank() && it != MediaStore.UNKNOWN_STRING } ?: "",
                        folder = folder,
                        durationMs = c.getLong(duration),
                        file = file,
                    )
                }
            }
        }
        return out
    }

    /**
     * Recorre las carpetas de la memoria buscando audio (sin índice de Android): título = nombre
     * del archivo; artista, álbum y duración no se saben sin abrir cada archivo (lento en una USB).
     * Con tope de carpetas y archivos para que una memoria enorme no se quede leyendo minutos.
     */
    private fun scan(root: File): List<Track> {
        val out = ArrayList<Track>()
        val base = root.path.trimEnd('/')
        val pending = ArrayDeque<Pair<File, Int>>().apply { add(root to 0) }
        var dirs = 0
        while (pending.isNotEmpty() && out.size < MAX_FILES && dirs < MAX_DIRS) {
            val (dir, depth) = pending.removeFirst()
            dirs++
            val children = runCatching { dir.listFiles() }.getOrNull() ?: continue
            for (f in children) {
                val name = f.name
                if (name.startsWith(".")) continue
                if (f.isDirectory) {
                    if (depth < MAX_DEPTH && name !in SKIP_DIRS) pending.add(f to depth + 1)
                } else if (name.substringAfterLast('.', "").lowercase() in AUDIO) {
                    out += Track(
                        uri = Uri.fromFile(f),
                        title = name.substringBeforeLast('.'),
                        artist = "",
                        album = "",
                        folder = f.parent.orEmpty().removePrefix(base).trim('/'),
                        durationMs = 0,
                        file = name,
                    )
                }
            }
        }
        return out
    }

    private val AUDIO = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "oga", "opus", "amr", "3gp", "mka")
    private val SKIP_DIRS = setOf("Android", "LOST.DIR", "System Volume Information", "\$RECYCLE.BIN")
    private const val MAX_DEPTH = 8
    private const val MAX_DIRS = 2_000
    private const val MAX_FILES = 10_000
}
