package com.irvingguerra.litedash.feature.music

import android.bluetooth.BluetoothManager
import android.media.session.PlaybackState

/** Para el reporte: la familia, la receta de cada pieza (y quién la confirmó) y los "no" seguidos de la señal. */
internal fun MusicRepository.describeCalibration(): String {
    fun line(s: SavedRecipe?) = s?.let {
        val by = when (it.by) { SavedBy.SIGNAL -> "señal"; SavedBy.USER -> "usuario"; SavedBy.CATALOG -> "catálogo" }
        "${it.id} ($by)"
    } ?: "-"
    val fails = (btStore.saved?.fails ?: 0) + (fmStore.saved?.fails ?: 0)
    return "Calibración: familia=${headUnit.id}, bluetooth=${line(btStore.saved)}, radio=${line(fmStore.saved)}, fallos=$fails"
}

/** Para los reportes: qué Bluetooth trae la pantalla y qué apps de marca, para saber su familia. */
internal fun MusicRepository.describeBluetooth(): String = buildString {
    val adapter = runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter }.getOrNull()
    val service = bluetoothService
    appendLine("Bluetooth de Android: AVRCP=${service?.let { "${it.packageName}/${it.name}" } ?: "no"}; adaptador=${adapter?.let { if (it.isEnabled) "encendido" else "apagado" } ?: "no"}")
    val brand = HeadUnit.bluetoothApps.filter(context::hasPackage)
    // Las instaladas a mano cuentan para el motor ([HeadUnit.of]) pero no para las estadísticas ([HeadUnit.ofSystem]): se marcan.
    appendLine("Apps de Bluetooth de marca: ${brand.joinToString { if (isSystemApp(context, it)) it else "$it (instalada)" }.ifBlank { "ninguna" }}")
}

internal fun MusicRepository.actionNames(actions: Long): String = listOf(
    PlaybackState.ACTION_PLAY to "play", PlaybackState.ACTION_PAUSE to "pause", PlaybackState.ACTION_PLAY_PAUSE to "play_pause",
    PlaybackState.ACTION_SKIP_TO_NEXT to "next", PlaybackState.ACTION_SKIP_TO_PREVIOUS to "prev", PlaybackState.ACTION_STOP to "stop",
    PlaybackState.ACTION_SEEK_TO to "seek",
).filter { actions and it.first != 0L }.joinToString(",", "[", "]") { it.second }
