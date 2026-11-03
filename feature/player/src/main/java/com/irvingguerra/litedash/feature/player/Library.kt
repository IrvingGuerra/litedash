package com.irvingguerra.litedash.feature.player

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
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

/** Las memorias conectadas y sus canciones (por MediaStore: Android ya las tiene indexadas). */
object Library {
    /** El permiso para leer la música de la memoria, según la versión de Android. */
    val permission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasPermission(context: Context) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Memorias montadas ahora (las detecta la pieza de música: [Drives]). */
    fun drives(context: Context): List<Drive> = Drives.list(context)

    /** Todas las canciones de [drive], ordenadas por carpeta y archivo (como se ven en la memoria). */
    fun tracks(context: Context, drive: Drive): List<Track> {
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
        return out.sortedWith(compareBy<Track, String>(String.CASE_INSENSITIVE_ORDER) { it.folder }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.file })
    }
}
