// Fondos de LiteDash: se descargan de litedash.dev solo al elegirlos (la APK no crece).
package com.irvingguerra.litedash

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.irvingguerra.litedash.core.ui.HttpStatusException
import com.irvingguerra.litedash.core.ui.Https
import com.irvingguerra.litedash.core.ui.isRecent
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/** Etiqueta del registro de Android para lo que falla con las imágenes y los fondos. */
internal const val LOG_TAG = "LiteDash"

/** Dónde viven: `index.json` (la lista) y por cada fondo `<id>.webp` (1920×1080) y `<id>-thumb.webp` (320×180). */
private const val WALLPAPERS_URL = "https://litedash.dev/wallpapers/"

/** Las listas de litedash.dev (fondos, idiomas) se vuelven a pedir a lo más una vez al día; sin internet, la última. */
internal const val LIST_MAX_AGE_MS = 24 * 60 * 60 * 1000L

/** Un fondo de LiteDash: [credit] = de quién es la foto ("LiteDash" en los nuestros). */
class WallpaperChoice(val id: String, val credit: String)

private fun folder(context: Context) = File(context.cacheDir, "wallpapers").apply { mkdirs() }

/** La lista de fondos (null si nunca se ha podido bajar). */
suspend fun loadWallpapers(context: Context): List<WallpaperChoice>? = withContext(Dispatchers.IO) {
    val file = File(folder(context), "index.json")
    // Una edad negativa (el reloj regresó, p. ej. una pantalla sin pila en 2015) no cuenta como fresca.
    val fresh = file.exists() && isRecent(file.lastModified(), System.currentTimeMillis(), LIST_MAX_AGE_MS)
    // Una lista ilegible (p. ej. la página de un wifi con registro) no reemplaza a la buena.
    val downloaded = if (fresh) null else try {
        download(context, "index.json", file, ::readList)
    } catch (e: IOException) {
        Log.w(LOG_TAG, "No se pudo bajar la lista de fondos", e)
        null
    }
    downloaded ?: readList(file)
}

/** La lista guardada en [file]; null si no existe o no se puede leer. */
private fun readList(file: File): List<WallpaperChoice>? = try {
    if (file.exists()) parseWallpapers(file.readText()) else null
} catch (e: IOException) {
    Log.w(LOG_TAG, "No se pudo leer la lista de fondos", e)
    null
}

/** Los fondos de un `index.json`: solo los que tienen un id seguro ([SAFE_ID]); null si el texto no es esa lista. */
internal fun parseWallpapers(text: String): List<WallpaperChoice>? = try {
    val list = JSONObject(text).getJSONArray("wallpapers")
    (0 until list.length()).map { i ->
        val o = list.getJSONObject(i)
        WallpaperChoice(o.getString("id"), o.optString("credit"))
    }.filter { it.id.matches(SAFE_ID) }
} catch (e: JSONException) {
    null
}

/** Las medidas de la imagen en [file]; null si Android no la puede leer. */
private fun readImage(file: File): BitmapFactory.Options? =
    BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(file.path, it) }.takeIf { it.outWidth > 0 }

/** Solo letras, números y guiones: el id va en la URL y en el nombre del archivo. */
private val SAFE_ID = Regex("[a-z0-9-]{1,40}")

/** La miniatura de [id] (se baja una vez y se queda en caché), en RGB_565: son fotos sin transparencia. null sin internet. */
suspend fun wallpaperThumb(context: Context, id: String): ImageBitmap? = withContext(Dispatchers.IO) {
    val file = File(folder(context), "$id-thumb.webp")
    if (!file.exists()) {
        try {
            download(context, "$id-thumb.webp", file, ::readImage)
        } catch (e: IOException) {
            Log.w(LOG_TAG, "No se pudo bajar la miniatura $id", e)
            return@withContext null
        }
    }
    BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })?.asImageBitmap()
}

/** Cómo le fue a un fondo de LiteDash: puesto, sin red (se puede reintentar), ya no existe (4xx) o ni se intentó. */
enum class WallpaperFetch { DONE, OFFLINE, GONE, SKIPPED }

/** Pone el fondo [id] en [target], de su copia en la caché o bajándolo (lo reemplaza solo si llegó completo y se puede leer). */
suspend fun downloadWallpaper(context: Context, id: String, target: File): WallpaperFetch = withContext(Dispatchers.IO) {
    try {
        // Una copia en la caché: el fondo hermano de un Diseño se pone al cambiar de tema aunque no haya red.
        val kept = File(folder(context), "$id.webp")
        if (readImage(kept) == null) download(context, "$id.webp", kept, ::readImage)
        replaceFile(target) { kept.copyTo(it, overwrite = true) }
        WallpaperFetch.DONE
    } catch (e: HttpStatusException) {
        Log.w(LOG_TAG, "El fondo $id ya no está", e)
        WallpaperFetch.GONE
    } catch (e: IOException) {
        Log.w(LOG_TAG, "No se pudo bajar el fondo $id", e)
        WallpaperFetch.OFFLINE
    }
}

/** Deja el fondo [id] en la caché para ponerlo después sin red (si no se puede, se bajará entonces). */
suspend fun keepWallpaper(context: Context, id: String) = withContext(Dispatchers.IO) {
    try {
        val kept = File(folder(context), "$id.webp")
        if (readImage(kept) == null) download(context, "$id.webp", kept, ::readImage)
    } catch (e: IOException) {
        Log.w(LOG_TAG, "No se pudo guardar el fondo $id", e)
    }
}

/** Baja [name] a [target] solo si [read] lo puede leer (devuelve lo leído); si no, lanza y [target] no cambia. */
private fun <T : Any> download(context: Context, name: String, target: File, read: (File) -> T?): T = replaceFile(target) { temp ->
    // Un 4xx (p. ej. un fondo que se quitó de la lista) llega como IOException igual que sin red.
    val connection = Https.get(context, WALLPAPERS_URL + name)
    connection.inputStream.use { input -> temp.outputStream().use { input.copyTo(it) } }
    read(temp) ?: throw IOException("unreadable $name")
}

/**
 * Reemplaza [target] con lo que [fill] escriba en un temporal a su lado: solo si [fill] termina se pone en su lugar,
 * así algo a medias (descarga cortada, disco lleno) nunca queda como el bueno. Lanza si no se pudo.
 */
internal fun <T> replaceFile(target: File, fill: (File) -> T): T {
    val temp = File(target.parentFile, "${target.name}.part")
    temp.parentFile?.mkdirs()
    try {
        val result = fill(temp)
        if (!temp.renameTo(target)) throw IOException("rename $temp")
        return result
    } finally {
        temp.delete()
    }
}
