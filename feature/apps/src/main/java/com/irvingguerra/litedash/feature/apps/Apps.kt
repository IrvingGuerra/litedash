package com.irvingguerra.litedash.feature.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class App(val name: String, val pkg: String, val activity: String) {
    val component get() = ComponentName(pkg, activity)
    /** Cómo se guarda una app en el tablero o el dock. */
    val key get() = "$pkg/$activity"
}

/** Lista de apps abribles e íconos, siempre fuera del hilo principal. */
class AppRepository(context: Context) {
    private val pm: PackageManager = context.packageManager
    private val ownPackage = context.packageName
    // Íconos ya convertidos a bitmap del tamaño que se pinta; caché por memoria (~4 MB).
    private val icons = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    suspend fun load(): List<App> = withContext(Dispatchers.IO) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != ownPackage }
            .map { App(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.activityInfo.name) }
            .sortedBy { it.name.lowercase() }
    }

    suspend fun icon(app: App, sidePx: Int): Bitmap? {
        val key = "${app.pkg}/${app.activity}@$sidePx"
        icons.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching { pm.getActivityIcon(app.component) }.getOrNull()
                ?.toBitmap(sidePx)
                ?.also { icons.put(key, it) }
        }
    }

    /** Busca una app por su [App.key] en la lista ya cargada. */
    fun find(apps: List<App>, key: String) = apps.firstOrNull { it.key == key }

    /** Primera app instalada de [packages], en ese orden de preferencia. */
    fun firstOf(apps: List<App>, vararg packages: String) =
        packages.firstNotNullOfOrNull { p -> apps.firstOrNull { it.pkg == p } }

    fun open(context: Context, app: App) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        runCatching { context.startActivity(intent) }
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
