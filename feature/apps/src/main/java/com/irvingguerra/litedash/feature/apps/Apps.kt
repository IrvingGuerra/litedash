package com.irvingguerra.litedash.feature.apps

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.text.Collator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class App(val name: String, val pkg: String, val activity: String) {
    val component get() = ComponentName(pkg, activity)
    /** Cómo se guarda una app en el tablero o el dock. */
    val key get() = "$pkg/$activity"
}

/**
 * Lista de apps abribles e íconos, siempre fuera del hilo principal. La lista es un estado
 * compartido: el cajón, el dock y los widgets leen la misma y se actualiza sola al instalar,
 * quitar o actualizar una app mientras LiteDash está a la vista (ver [watch]).
 */
class AppRepository(context: Context) {
    private val appContext = context.applicationContext
    private val pm: PackageManager = context.packageManager
    private val ownPackage = context.packageName
    private val scope: CoroutineScope = MainScope()
    private var reload: Job? = null
    private var watchers = 0

    /** Apps abribles en orden alfabético; null mientras carga la primera vez. */
    var apps by mutableStateOf<List<App>?>(null)
        private set

    // Instalar, quitar o actualizar una app: se olvidan sus íconos y se recarga la lista. Al actualizar Android manda
    // quitada + agregada (con EXTRA_REPLACING) + reemplazada: basta con la última.
    private val packages = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            if (replacing && intent.action != Intent.ACTION_PACKAGE_REPLACED) return
            intent.data?.schemeSpecificPart?.let(::forgetIcons)
            refresh()
        }
    }
    // Íconos ya convertidos a bitmap del tamaño que se pinta; caché por memoria (1/32 de la memoria de la app, 2–8 MB).
    private val icons = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 32).coerceIn(ICONS_MIN, ICONS_MAX).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    /** Íconos que se están leyendo: dos pedidos del mismo (dock y cajón a la vez) esperan la misma lectura. */
    private val loading = HashMap<String, Deferred<Bitmap?>>()

    /**
     * Empieza a escuchar cambios de apps (una llamada por cada pantalla que las muestra, mientras
     * se ve). El primero registra el receptor y recarga: lo que cambió con LiteDash en segundo
     * plano también se ve. Siempre en el hilo principal.
     */
    fun watch() {
        if (watchers++ > 0) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        appContext.registerReceiver(packages, filter)
        refresh()
    }

    /** Deja de escuchar; el último en salir quita el receptor. */
    fun unwatch() {
        if (watchers == 0 || --watchers > 0) return
        runCatching { appContext.unregisterReceiver(packages) }
    }

    /** Recarga la lista fuera del hilo principal; solo la cambia si de verdad cambió. */
    private fun refresh() {
        reload?.cancel()
        reload = scope.launch {
            val list = load()
            if (list != apps) apps = list
        }
    }

    private fun forgetIcons(pkg: String) {
        icons.snapshot().keys.filter { it.startsWith("$pkg/") }.forEach { icons.remove(it) }
    }

    /** LiteDash se fue a segundo plano: los íconos se vuelven a leer al regresar. */
    fun trimMemory() {
        icons.evictAll()
    }

    private suspend fun load(): List<App> = withContext(Dispatchers.IO) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != ownPackage }
            .map { App(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.activityInfo.name) }
            // Por idioma: "Ángel" junto a la A, no después de la Z.
            .sortedWith(compareBy(Collator.getInstance()) { it.name })
    }

    private fun iconKey(app: App, sidePx: Int) = "${app.key}@$sidePx"

    /** El ícono si ya está en memoria (sin esperar ni leer nada). */
    fun cachedIcon(app: App, sidePx: Int): Bitmap? = icons.get(iconKey(app, sidePx))

    /** El ícono de [app] a [sidePx]; lo lee fuera del hilo principal si no está en memoria. Null = la app no tiene. */
    suspend fun icon(app: App, sidePx: Int): Bitmap? {
        val key = iconKey(app, sidePx)
        icons.get(key)?.let { return it }
        val read = synchronized(loading) {
            loading.getOrPut(key) {
                scope.async(Dispatchers.IO, CoroutineStart.LAZY) {
                    try {
                        // Una app que se quitó en ese momento ya no tiene ícono.
                        runCatching { pm.getActivityIcon(app.component) }.getOrNull()?.toBitmap(sidePx)?.also { icons.put(key, it) }
                    } finally {
                        synchronized(loading) { loading.remove(key) }
                    }
                }
            }
        }
        return read.await()
    }

    /** Busca una app por su [App.key] en la lista ya cargada. */
    fun find(apps: List<App>, key: String) = apps.firstOrNull { it.key == key }

    /** Primera app instalada de [packages], en ese orden de preferencia. */
    fun firstOf(apps: List<App>, vararg packages: String) =
        packages.firstNotNullOfOrNull { p -> apps.firstOrNull { it.pkg == p } }

    /** Si devuelve true, ya abrió la app a su manera (p. ej. en pantalla dividida). */
    var opener: ((Context, Intent, String) -> Boolean)? = null

    fun open(context: Context, app: App) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        if (opener?.invoke(context, intent, app.pkg) == true) return
        runCatching { context.startActivity(intent) }
    }

    private companion object {
        const val ICONS_MIN = 2L * 1024 * 1024
        const val ICONS_MAX = 8L * 1024 * 1024
    }
}

private fun Drawable.toBitmap(side: Int): Bitmap {
    if (this is BitmapDrawable && bitmap.width == side && bitmap.height == side) return bitmap
    val bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    setBounds(0, 0, side, side)
    draw(canvas)
    return bmp
}
