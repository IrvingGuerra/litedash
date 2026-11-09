package com.irvingguerra.litedash.core.ui

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Explorador de archivos propio, para pantallas de auto que no traen el selector de Android (sin
 * "Archivos"/DocumentsUI: al pedir una imagen o una carpeta no se abría nada, o la app se cerraba).
 * Lee las memorias con [File] (necesita el permiso de leer el almacenamiento) y va dentro de una
 * columna, sin ventanas: lista con su propio scroll de alto fijo.
 *
 * [pickFolder] = se elige una carpeta ("Usar esta carpeta"); si no, un archivo que pase [accept]
 * (por nombre). [onPick] recibe lo elegido; [onCancel] cierra.
 */
@Composable
fun FileBrowser(
    pickFolder: Boolean,
    accept: (String) -> Boolean,
    onPick: (File) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalColors.current
    val context = LocalContext.current
    // null = la lista de memorias.
    var dir by remember { mutableStateOf<File?>(null) }
    val roots by produceState(emptyList<StorageRoot>()) { value = withContext(Dispatchers.IO) { storageRoots(context) } }
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
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val current = dir
            if (current != null) {
                IconButton(Icons.back, {
                    // Arriba hasta la raíz de la memoria; de ahí, a la lista de memorias.
                    dir = current.parentFile?.takeIf { p -> roots.none { it.dir == current } && p.canRead() }
                }, size = 44.dp, background = c.surfaceHigh)
            }
            Label(current?.path ?: stringResource(R.string.files_where), Typography.body, c.textMuted, 1, Modifier.weight(1f))
            Button(stringResource(R.string.files_cancel), onCancel)
        }
        Column(
            Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(RoundedCornerShape(Radius.inner)).background(c.surfaceHigh).verticalScroll(rememberScrollState()),
        ) {
            val current = dir
            if (current == null) {
                if (roots.isEmpty()) Label(stringResource(R.string.files_none), Typography.body, c.textMuted, modifier = Modifier.padding(16.dp))
                for (r in roots) Entry(Icons.folder, r.name) { dir = r.dir }
            } else {
                val list = entries
                when {
                    list == null -> Label("…", Typography.body, c.textMuted, modifier = Modifier.padding(16.dp))
                    list.isEmpty() -> Label(stringResource(R.string.files_empty), Typography.body, c.textMuted, modifier = Modifier.padding(16.dp))
                    else -> for ((f, isDir) in list) {
                        if (isDir) Entry(Icons.folder, f.name) { dir = f }
                        else Entry(Icons.image, f.name) { onPick(f) }
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

private val SKIP_ROOTS = setOf("emulated", "self", "secure", "asec", "obb", "runtime", "user", "sdcard0", "appfuse", "expand", "vendor", "product")
private const val MAX_ENTRIES = 500
