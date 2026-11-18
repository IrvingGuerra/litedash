package com.irvingguerra.litedash.feature.music

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.service.media.MediaBrowserService
import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay

/**
 * Una tecla multimedia (abajo y arriba) como la del volante: a [receiver] (el receptor de una
 * app) o, sin él, al sistema de audio, que la lleva a la app de música que toca.
 */
fun sendMediaKey(context: Context, code: Int, receiver: ComponentName? = null) {
    val audio = context.getSystemService(AudioManager::class.java)
    for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
        val event = KeyEvent(action, code)
        runCatching {
            if (receiver != null) context.sendBroadcast(Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(receiver).putExtra(Intent.EXTRA_KEY_EVENT, event))
            else audio.dispatchMediaKeyEvent(event)
        }
    }
}

/** Dónde va la canción, calculado con la hora: se actualiza una vez por segundo y solo mientras suena. */
@Composable
fun rememberPosition(song: Song): State<Long> = produceState(song.currentPosition(), song) {
    value = song.currentPosition()
    while (song.playing) {
        delay(1000)
        value = song.currentPosition()
    }
}

/** "sí" o "no", para los reportes. */
internal fun yes(v: Boolean) = if (v) "sí" else "no"

/** La app [pkg] está instalada. */
internal fun Context.hasPackage(pkg: String) = runCatching { packageManager.getPackageInfo(pkg, 0) }.isSuccess

/** App del sistema (de la pantalla, no del usuario); si no se puede saber (no está), [unknown]. */
internal fun isSystemApp(context: Context, pkg: String, unknown: Boolean = false) =
    runCatching { context.packageManager.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0 }.getOrDefault(unknown)

/** Los servicios de música (MediaBrowserService) de [pkg], o de todas las apps con null. */
internal fun browserServices(context: Context, pkg: String? = null): List<ServiceInfo> = runCatching {
    val intent = Intent(MediaBrowserService.SERVICE_INTERFACE).apply { if (pkg != null) setPackage(pkg) }
    context.packageManager.queryIntentServices(intent, 0).map { it.serviceInfo }
}.getOrDefault(emptyList())

/** Las apps que se declaran de música (categoría APP_MUSIC de Android). */
internal fun declaredMusicApps(context: Context): List<String> = runCatching {
    context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC), 0)
        .map { it.activityInfo.packageName }
}.getOrDefault(emptyList())

/** El paquete parece de Bluetooth: alguna parte (sin el prefijo) empieza con "bt" o lleva "bluetooth". */
internal fun looksBluetooth(pkg: String) = pkg.lowercase().split('.').drop(1).any { it.startsWith("bt") || "bluetooth" in it }

/** Las actividades con ícono en el cajón de [pkg] (varias apps de fábrica traen más de una). */
internal fun launchActivities(context: Context, pkg: String): List<ResolveInfo> = runCatching {
    context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg), 0)
}.getOrDefault(emptyList())

// La actividad de la música en una app de Bluetooth con varias (idea de StradaOS, GPL-3.0).
private val BT_MUSIC_LABEL = Regex("audio bluetooth|m[uú]sica bluetooth|bt music|bluetooth music|m[uú]sica bt|a2dp", RegexOption.IGNORE_CASE)
private val BT_MUSIC_CLASS = Regex("btmusic|bt\\.music|bt_music|btaudio|a2dp|bluetoothmusic|music\\.bt", RegexOption.IGNORE_CASE)

/**
 * De las actividades de inicio de una app de Bluetooth (clase y etiqueta de cada una), cuál es la de la música
 * ("Bluetooth" y "BT Music" en la misma app, issue #13); -1 = ninguna lo parece. La etiqueta solo se pide si la clase
 * no lo dice (cargarla lee los recursos de la otra app).
 */
internal fun pickMusicActivity(classes: List<String>, label: (Int) -> CharSequence): Int =
    classes.indices.firstOrNull { BT_MUSIC_CLASS.containsMatchIn(classes[it]) || BT_MUSIC_LABEL.containsMatchIn(label(it)) } ?: -1

/**
 * Cómo abrir [pkg]. Si es la app del Bluetooth ([bluetooth]: lo sabe quien llama) y trae varias actividades de inicio,
 * la de la música ([pickMusicActivity]); si no, la que da Android. Null = no tiene pantalla que abrir.
 */
internal fun launchIntent(context: Context, pkg: String, bluetooth: Boolean = false): Intent? {
    val pm = context.packageManager
    val default = runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull()
    if (!bluetooth) return default
    val list = launchActivities(context, pkg)
    if (list.size < 2) return default
    val i = pickMusicActivity(list.map { it.activityInfo.name }) { list[it].loadLabel(pm) }
    if (i < 0) return default
    return Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(pkg, list[i].activityInfo.name)
}

/**
 * Cómo abrir la app del Bluetooth [pkg] para conectar un celular: si trae varias actividades de inicio, la que NO es la
 * de la música (la de emparejar; issue #13: «Bluetooth» y «BT Music» en la misma app); si no, la que da Android.
 */
internal fun pairingIntent(context: Context, pkg: String): Intent? {
    val pm = context.packageManager
    val list = launchActivities(context, pkg)
    val music = if (list.size < 2) -1 else pickMusicActivity(list.map { it.activityInfo.name }) { list[it].loadLabel(pm) }
    if (music < 0) return runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull()
    val other = list[if (music == 0) 1 else 0]
    return Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(pkg, other.activityInfo.name)
}

/** Para los reportes: las actividades de inicio de [pkg] (clase corta y etiqueta) y cuál abre LiteDash. */
internal fun describeLaunch(context: Context, pkg: String, bluetooth: Boolean = false): String {
    val pm = context.packageManager
    val list = launchActivities(context, pkg).joinToString { "${it.activityInfo.name.substringAfterLast('.')} (${it.loadLabel(pm)})" }
    val opens = launchIntent(context, pkg, bluetooth)?.component?.className?.substringAfterLast('.') ?: "-"
    return "$pkg: ${list.ifEmpty { "ninguna" }} → abre $opens"
}
