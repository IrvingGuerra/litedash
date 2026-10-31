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

data class App(val nombre: String, val paquete: String, val actividad: String) {
    val componente get() = ComponentName(paquete, actividad)
}

/** Lista de apps abribles e íconos, siempre fuera del hilo principal. */
class RepositorioApps(contexto: Context) {
    private val pm: PackageManager = contexto.packageManager
    private val propio = contexto.packageName
    // Íconos ya convertidos a bitmap del tamaño que se pinta; caché por memoria (~4 MB).
    private val iconos = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    suspend fun cargar(): List<App> = withContext(Dispatchers.IO) {
        val intento = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intento, 0)
            .filter { it.activityInfo.packageName != propio }
            .map { App(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.activityInfo.name) }
            .sortedBy { it.nombre.lowercase() }
    }

    suspend fun icono(app: App, ladoPx: Int): Bitmap? {
        val clave = "${app.paquete}/${app.actividad}@$ladoPx"
        iconos.get(clave)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching { pm.getActivityIcon(app.componente) }.getOrNull()
                ?.aBitmap(ladoPx)
                ?.also { iconos.put(clave, it) }
        }
    }

    fun abrir(contexto: Context, app: App) {
        val intento = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(app.componente)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        runCatching { contexto.startActivity(intento) }
    }
}

private fun Drawable.aBitmap(lado: Int): Bitmap {
    if (this is BitmapDrawable && bitmap.width == lado && bitmap.height == lado) return bitmap
    val bmp = Bitmap.createBitmap(lado, lado, Bitmap.Config.ARGB_8888)
    val lienzo = Canvas(bmp)
    setBounds(0, 0, lado, lado)
    draw(lienzo)
    return bmp
}
