// Fondos de LiteDash: se descargan de litedash.dev solo al elegirlos (la APK no crece).
package com.irvingguerra.litedash

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.irvingguerra.litedash.core.ui.Https
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Dónde viven: `index.json` (la lista) y por cada fondo `<id>.webp` (1920×1080) y `<id>-thumb.webp` (320×180). */
private const val WALLPAPERS_URL = "https://litedash.dev/wallpapers/"

/** La lista se vuelve a pedir a lo más una vez al día; sin internet, la última que se bajó. */
private const val LIST_MAX_AGE_MS = 24 * 60 * 60 * 1000L

/** Un fondo de LiteDash: [credit] = de quién es la foto ("LiteDash" en los nuestros). */
class WallpaperChoice(val id: String, val credit: String)

private fun folder(context: Context) = File(context.cacheDir, "wallpapers").apply { mkdirs() }

/** La lista de fondos (null si nunca se ha podido bajar). */
suspend fun loadWallpapers(context: Context): List<WallpaperChoice>? = withContext(Dispatchers.IO) {
    val file = File(folder(context), "index.json")
    val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < LIST_MAX_AGE_MS
    val text = (if (fresh) null else runCatching { download(context, "index.json", file, image = false); file.readText() }.getOrNull())
        ?: runCatching { file.readText() }.getOrNull()
        ?: return@withContext null
    runCatching {
        val list = JSONObject(text).getJSONArray("wallpapers")
        (0 until list.length()).map { i ->
            val o = list.getJSONObject(i)
            WallpaperChoice(o.getString("id"), o.optString("credit"))
        }.filter { it.id.matches(SAFE_ID) }
    }.getOrNull()
}

/** Solo letras, números y guiones: el id va en la URL y en el nombre del archivo. */
private val SAFE_ID = Regex("[a-z0-9-]{1,40}")

/** La miniatura de [id] (se baja una vez y se queda en caché); null sin internet. */
suspend fun wallpaperThumb(context: Context, id: String): ImageBitmap? = withContext(Dispatchers.IO) {
    val file = File(folder(context), "$id-thumb.webp")
    if (!file.exists() && runCatching { download(context, "$id-thumb.webp", file, image = true) }.isFailure) return@withContext null
    runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull()
}

/** Baja el fondo [id] a [target] (lo reemplaza solo si llegó completo y se puede leer). false si no se pudo. */
suspend fun downloadWallpaper(context: Context, id: String, target: File): Boolean = withContext(Dispatchers.IO) {
    runCatching { download(context, "$id.webp", target, image = true) }.isSuccess
}

/**
 * Baja [name] a [target]: primero a un temporal y luego se mueve, así una descarga a medias nunca queda como la
 * buena. Con [image], solo si Android la puede leer.
 */
private fun download(context: Context, name: String, target: File, image: Boolean) {
    val temp = File(target.parentFile, "${target.name}.part")
    Https.get(context, WALLPAPERS_URL + name).inputStream.use { input -> temp.outputStream().use { input.copyTo(it) } }
    if (image) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(temp.path, bounds)
        if (bounds.outWidth <= 0) {
            temp.delete()
            error("not an image")
        }
    }
    if (!temp.renameTo(target)) error("rename")
}
