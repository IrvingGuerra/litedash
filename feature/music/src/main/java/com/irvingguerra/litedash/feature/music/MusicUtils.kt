package com.irvingguerra.litedash.feature.music

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.media.AudioManager
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
 * Cómo abrir [pkg]. Una app de Bluetooth ([bluetooth], de marca conocida o con "bt"/"bluetooth" en el paquete) con
 * varias actividades de inicio ("Bluetooth" y "BT Music" en la misma app, issue #13) abre la de la música; si no,
 * la que da Android (la primera). Null = no tiene pantalla que abrir.
 */
internal fun launchIntent(context: Context, pkg: String, bluetooth: Boolean = false): Intent? {
    val pm = context.packageManager
    val default = runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull()
    if (!bluetooth && pkg !in HeadUnit.bluetoothApps && !looksBluetooth(pkg)) return default
    val list = launchActivities(context, pkg)
    if (list.size < 2) return default
    val music = list.firstOrNull { BT_MUSIC_CLASS.containsMatchIn(it.activityInfo.name) || BT_MUSIC_LABEL.containsMatchIn(it.loadLabel(pm)) } ?: return default
    return Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(pkg, music.activityInfo.name)
}

/** Para los reportes: las actividades de inicio de [pkg] (clase corta y etiqueta) y cuál abre LiteDash. */
internal fun describeLaunch(context: Context, pkg: String, bluetooth: Boolean = false): String {
    val pm = context.packageManager
    val list = launchActivities(context, pkg).joinToString { "${it.activityInfo.name.substringAfterLast('.')} (${it.loadLabel(pm)})" }
    val opens = launchIntent(context, pkg, bluetooth)?.component?.className?.substringAfterLast('.') ?: "-"
    return "$pkg: ${list.ifEmpty { "ninguna" }} → abre $opens"
}
