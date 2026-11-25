package com.irvingguerra.litedash.core.ui

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Explorador de archivos propio, para pantallas de auto que no traen el selector de Android (sin
 * "Archivos"/DocumentsUI: al pedir una imagen o una carpeta no se abría nada, o la app se cerraba).
 * Lee las memorias con [File] (necesita el permiso de leer el almacenamiento) y va dentro de una
 * columna, sin ventanas: lista perezosa con su propio scroll de alto fijo (solo se arma lo que se ve).
 *
 * [pickFolder] = se elige una carpeta ("Usar esta carpeta"); si no, un archivo que pase [accept]
 * (por nombre). [images] = se elige una foto: miniaturas en cuadrícula y "Recientes" (las últimas fotos de
 * la pantalla) antes de las memorias. [onPick] recibe lo elegido; [onCancel] cierra. [onPickUri] = quien llama
 * también acepta una foto de "Recientes" que solo se deja leer por el índice de Android (hay pantallas con Android
 * 11–12 donde la memoria no se deja leer con [File] aunque el permiso esté dado).
 */
@Composable
fun FileBrowser(
    pickFolder: Boolean,
    accept: (String) -> Boolean,
    onPick: (File) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    images: Boolean = false,
    onPickUri: ((Uri) -> Unit)? = null,
) {
    val c = LocalColors.current
    val context = LocalContext.current
    val px = with(LocalDensity.current) { THUMB.roundToPx() }
    // Las miniaturas ya leídas se quedan mientras el explorador esté abierto (volver a una carpeta no las relee).
    val thumbs = remember(px) { Thumbnails(context, px) }
    // null = la lista de memorias.
    var dir by remember { mutableStateOf<File?>(null) }
    var recent by remember { mutableStateOf(false) }
    val roots by produceState(emptyList<StorageRoot>()) { value = withContext(Dispatchers.IO) { storageRoots(context) } }
    val recentFiles by produceState<List<Shot>?>(null, recent, roots) {
        value = null
        // Sin memorias que se dejen leer, "Recientes" es la única puerta: ahí van todas las fotos, no solo las últimas.
        if (recent) value = withContext(Dispatchers.IO) {
            recentImages(context, byIndex = onPickUri != null, max = if (roots.isEmpty()) MAX_ENTRIES else MAX_RECENT)
        }
    }
    // Cada archivo con si es carpeta: se pregunta aquí (es leer el disco), no al dibujar.
    val entries by produceState<List<Pair<File, Boolean>>?>(null, dir) {
        value = null
        val d = dir ?: return@produceState
        value = withContext(Dispatchers.IO) {
            d.listFiles().orEmpty()
                .filter { !it.name.startsWith(".") }
                .map { it to it.isDirectory }
                .filter { (f, isDir) -> isDir || (!pickFolder && accept(f.name)) }
                .sortedWith(compareBy({ !it.second }, { it.first.name.lowercase() }))
                .take(MAX_ENTRIES)
        }
    }
    // Arriba hasta la raíz de la memoria; de ahí, a la lista de memorias.
    val up = {
        val current = dir
        if (recent) recent = false
        else dir = current?.parentFile?.takeIf { p -> roots.none { it.dir == current } && p.canRead() }
    }
    // "Atrás" de Android: sube de carpeta y, en la lista de memorias, cierra solo el explorador (no el panel donde está).
    BackHandler { if (dir != null || recent) up() else onCancel() }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val current = dir
            if (current != null || recent) IconButton(Icons.back, up, size = 44.dp, background = c.surfaceHigh)
            // El nombre de la carpeta (el de la memoria en su raíz), no la ruta completa.
            val title = when {
                recent -> stringResource(R.string.files_recent)
                current != null -> roots.firstOrNull { it.dir == current }?.name ?: current.name
                else -> stringResource(R.string.files_where)
            }
            Label(title, Typography.body, c.textMuted, 1, Modifier.weight(1f))
            Button(stringResource(R.string.files_close), onCancel)
        }
        // Con fotos, una cuadrícula de miniaturas (las carpetas y avisos ocupan el renglón completo).
        LazyVerticalGrid(
            columns = if (images) GridCells.FixedSize(THUMB + THUMB_GAP) else GridCells.Fixed(1),
            // Otra carpeta empieza arriba.
            state = remember(dir, recent) { LazyGridState() },
            modifier = Modifier.fillMaxWidth().heightIn(max = if (images) 360.dp else 320.dp).clip(RoundedCornerShape(Radius.inner)).background(c.surfaceHigh),
            contentPadding = PaddingValues(if (images) THUMB_GAP / 2 else 0.dp),
        ) {
            val current = dir
            fun row(content: @Composable () -> Unit) = item(span = { GridItemSpan(maxLineSpan) }) { content() }
            fun note(text: @Composable () -> String) = row { Label(text(), Typography.body, c.textMuted, modifier = Modifier.padding(16.dp)) }
            fun thumbItems(shots: List<Shot>) = items(shots, key = { it.uri.toString() }) { shot ->
                val u = shot.uri
                Thumb(shot, thumbs) { if (u.scheme == "file") onPick(File(u.path.orEmpty())) else onPickUri?.invoke(u) }
            }
            when {
                recent -> {
                    val list = recentFiles
                    when {
                        list == null -> note { "…" }
                        list.isEmpty() -> note { stringResource(R.string.files_recent_empty) }
                        else -> thumbItems(list)
                    }
                }
                current == null -> {
                    if (images) row { Entry(Icons.clock, stringResource(R.string.files_recent)) { recent = true } }
                    // Con fotos, sin memorias que se dejen leer quedan las del índice de Android ("Recientes").
                    if (roots.isEmpty() && !(images && onPickUri != null)) note { stringResource(R.string.files_none) }
                    for (r in roots) row { Entry(Icons.folder, r.name) { dir = r.dir } }
                }
                else -> {
                    val list = entries
                    when {
                        list == null -> note { "…" }
                        list.isEmpty() -> note { stringResource(R.string.files_empty) }
                        else -> {
                            val (folders, files) = list.partition { it.second }
                            items(folders.size, key = { "d:" + folders[it].first.path }, span = { GridItemSpan(maxLineSpan) }) { i ->
                                val f = folders[i].first
                                Entry(Icons.folder, f.name) { dir = f }
                            }
                            if (images) thumbItems(files.map { Shot(Uri.fromFile(it.first), it.first.name) })
                            else items(files.size, key = { files[it].first.path }) { i ->
                                val f = files[i].first
                                Entry(Icons.image, f.name) { onPick(f) }
                            }
                        }
                    }
                }
            }
        }
        val current = dir
        if (pickFolder && current != null) Button(stringResource(R.string.files_use_folder), { onPick(current) }, primary = true)
    }
}

@Composable
private fun Entry(icon: ImageVector, name: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, LocalColors.current.textMuted, 22.dp)
        Label(name, Typography.body, maxLines = 1)
    }
}

/** Una foto para elegir: dónde está y su nombre (lo que dice la voz de accesibilidad). */
private class Shot(val uri: Uri, val name: String)

/**
 * Una foto de la cuadrícula: se lee reducida cuando se ve (si sale de la vista antes de empezar, no se lee) y
 * queda en [thumbs].
 */
@Composable
private fun Thumb(shot: Shot, thumbs: Thumbnails, onClick: () -> Unit) {
    val c = LocalColors.current
    val bitmap by produceState(thumbs.cached(shot.uri), shot.uri) { if (value == null) value = thumbs.load(shot.uri) }
    Box(
        Modifier.padding(THUMB_GAP / 2).size(THUMB).clip(RoundedCornerShape(10.dp)).background(c.surface).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        if (b != null) Image(b, shot.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.image, c.textMuted, 22.dp)
    }
}

/**
 * Miniaturas de [px] ya leídas (unas cuantas: [THUMB_CACHE_BYTES]). Se leen de a [THUMB_READERS] a la vez: una
 * carpeta con cientos de fotos no lanza cientos de lecturas juntas (en una pantalla de 1 GB era memoria y CPU de golpe).
 */
private class Thumbnails(private val context: Context, private val px: Int) {
    private val memory = object : LruCache<String, ImageBitmap>(THUMB_CACHE_BYTES) {
        // RGB_565: dos bytes por píxel.
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 2
    }

    fun cached(shot: Uri): ImageBitmap? = memory.get(shot.toString())

    suspend fun load(shot: Uri): ImageBitmap? = withContext(THUMB_DECODER) {
        cached(shot) ?: thumbnail(context, shot, px)?.also { memory.put(shot.toString(), it) }
    }
}

/** La foto reducida a [px] por su lado corto, en RGB_565 (la mitad de memoria); null si no se puede leer. */
private fun thumbnail(context: Context, shot: Uri, px: Int): ImageBitmap? = runCatching {
    // Un archivo se lee directo; lo demás (una foto del índice de Android), por su contenido.
    val path = shot.path?.takeIf { shot.scheme == "file" }
    fun decode(options: BitmapFactory.Options): Bitmap? =
        if (path != null) BitmapFactory.decodeFile(path, options)
        else context.contentResolver.openInputStream(shot)?.use { BitmapFactory.decodeStream(it, null, options) }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    decode(bounds)
    val side = minOf(bounds.outWidth, bounds.outHeight)
    if (side <= 0) return null
    decode(scaledDecode(side, px).apply { inPreferredConfig = Bitmap.Config.RGB_565 })?.asImageBitmap()
}.getOrNull()

/**
 * Las fotos más nuevas de la pantalla (las que Android ya tiene indexadas); en IO. Las que se dejan leer como archivo
 * van como archivo; con [byIndex], las demás van por su dirección en el índice de Android (sin él, no salen).
 */
private fun recentImages(context: Context, byIndex: Boolean, max: Int): List<Shot> = runCatching {
    @Suppress("DEPRECATION") // la ruta del archivo: el explorador lee con File, como en las carpetas
    val path = MediaStore.Images.Media.DATA
    val out = ArrayList<Shot>()
    context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Images.Media._ID, path, MediaStore.Images.Media.DISPLAY_NAME), null, null,
        "${MediaStore.Images.Media.DATE_MODIFIED} DESC",
    )?.use { cursor ->
        while (out.size < max && cursor.moveToNext()) {
            val f = cursor.getString(1)?.let(::File)
            val name = cursor.getString(2) ?: f?.name.orEmpty()
            if (f != null && f.canRead()) out.add(Shot(Uri.fromFile(f), f.name))
            else if (byIndex) out.add(Shot(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0)), name))
        }
    }
    out.toList()
}.getOrDefault(emptyList())

/**
 * Para el reporte: qué deja leer la pantalla de su memoria (permiso, memoria interna, si hay una memoria aparte (SD o
 * USB) y si se deja leer, y cuántas fotos tiene el índice de Android). Solo sí/no y cuentas: nunca archivos ni el número
 * de serie de una memoria (su carpeta en `/storage` lo lleva). En IO.
 */
fun storageReport(context: Context): String = runCatching {
    val granted = context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    @Suppress("DEPRECATION") // la ruta de la memoria interna sigue sirviendo para leerla
    val internal = Environment.getExternalStorageDirectory()
    val listed = runCatching { internal.listFiles()?.size }.getOrNull()
    val photos = runCatching {
        context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID), null, null, null)
            ?.use { it.count }
    }.getOrNull()
    // Una memoria aparte (SD o USB): montada según Android y si el explorador la puede abrir.
    val internalKey = runCatching { internal.canonicalPath }.getOrDefault(internal.path)
    val readable = storageRoots(context).any { runCatching { it.dir.canonicalPath }.getOrDefault(it.dir.path) != internalKey }
    val mounted = readable || removableMounted(context)
    fun yes(b: Boolean) = if (b) "sí" else "no"
    "Almacenamiento: permiso=${yes(granted)} · interna=${internal.path} estado=${Environment.getExternalStorageState()} " +
        "se lee=${yes(internal.canRead())} lista=${listed ?: "nada"} · SD: ${yes(mounted)}, se lee: ${yes(readable)} · " +
        "fotos en el índice=${photos ?: "sin dato"}"
}.getOrElse { "Almacenamiento: no se pudo leer (${it.javaClass.simpleName})" }

/** ¿Android dice que hay una memoria aparte (SD o USB) montada? (Desde Android 7; antes, no se sabe: no.) */
private fun removableMounted(context: Context): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && runCatching {
    context.getSystemService(StorageManager::class.java).storageVolumes.any { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
}.getOrDefault(false)

/** Una memoria donde empezar a buscar. */
private class StorageRoot(val name: String, val dir: File)

/**
 * Las memorias que se pueden leer: la interna y las removibles (USB, tarjeta). En Android 11+
 * con sus nombres; en todas, además, las carpetas de `/storage` y `/mnt` que se dejen leer (varias
 * pantallas montan la USB ahí sin anunciarla). Leer fuera del hilo principal.
 */
private fun storageRoots(context: Context): List<StorageRoot> {
    val out = LinkedHashMap<String, StorageRoot>()
    fun add(name: String, dir: File?) {
        val d = dir ?: return
        if (!d.canRead() || !d.isDirectory) return
        // Sin putIfAbsent: no existe en Android 6.
        val key = runCatching { d.canonicalPath }.getOrDefault(d.path)
        if (key !in out) out[key] = StorageRoot(name, d)
    }
    @Suppress("DEPRECATION") // la ruta de la memoria interna sigue sirviendo para leerla
    add(context.getString(R.string.files_internal), Environment.getExternalStorageDirectory())
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching { context.getSystemService(StorageManager::class.java).storageVolumes }.getOrNull().orEmpty()
            .forEach { v -> add(v.getDescription(context), v.directory) }
    }
    for (base in listOf("/storage", "/mnt", "/mnt/media_rw")) {
        File(base).listFiles().orEmpty()
            .filter { it.name !in SKIP_ROOTS && !it.name.startsWith(".") }
            .forEach { add(it.name, it) }
    }
    return out.values.toList()
}

/**
 * Carpetas de `/storage` y `/mnt` que son del sistema, no memorias. Las de Android 11+ (`androidwritable`, `installer`,
 * `pass_through`…) se dejan abrir en algunas pantallas y salían como memorias vacías.
 */
private val SKIP_ROOTS = setOf(
    "emulated", "self", "secure", "asec", "obb", "runtime", "user", "sdcard0", "appfuse", "expand", "vendor", "product",
    "androidwritable", "installer", "pass_through", "pre_reboot_dexopt",
)
private const val MAX_ENTRIES = 500
private const val MAX_RECENT = 60
private val THUMB = 96.dp
private val THUMB_GAP = 8.dp
/** Miniaturas que se guardan mientras el explorador está abierto: ~4 MB (unas 50 a 2× de densidad). */
private const val THUMB_CACHE_BYTES = 4 shl 20
/** Fotos que se leen a la vez. */
private const val THUMB_READERS = 2
private val THUMB_DECODER = Dispatchers.IO.limitedParallelism(THUMB_READERS)
