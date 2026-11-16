package com.irvingguerra.litedash.feature.music

import android.content.Intent

/** Palabras con las que se reconoce la app de radio de la pantalla (en el paquete o en el nombre). */
private val RADIO_WORDS = listOf("radio", "fm")

/**
 * Adivina, una vez, la app de radio de la pantalla: entre las del sistema que atienden el botón de
 * play y se pueden abrir, la que lleva "radio" o "fm" en el paquete o en el nombre. Solo si hay una
 * sola candidata (si hay dudas, mejor nada: se elige en Ajustes → Apps → Radio). El Bluetooth no se
 * adivina: [bluetoothApp] ya usa la app de marca instalada (adivinar por nombre enlazó ZLink en una
 * TopWay). Corre en un hilo aparte (son varias consultas al sistema); el resultado se guarda en el principal.
 */
internal fun MusicRepository.guessSources() {
    val launchable = runCatching {
        context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }.toSet()
    }.getOrDefault(emptySet())
    val players = mediaButtonReceivers().map { it.packageName }.distinct()
        .filter { it != context.packageName && it in launchable && isSystemApp(context, it) }
    val radio = players.filter { pkg -> RADIO_WORDS.any { it in tokens(pkg, appLabel(pkg)) } }.singleOrNull() ?: return
    mainHandler.post { if (SourceKind.RADIO !in sourceApps) setSource(SourceKind.RADIO, radio) }
}

/** Palabras sueltas del paquete y del nombre, en minúsculas ("com.syu.bt" → com, syu, bt). */
internal fun MusicRepository.tokens(pkg: String, label: String): Set<String> =
    (pkg.split('.') + label.split(Regex("[^\\p{L}\\p{N}]+"))).map { it.lowercase() }.filter { it.isNotEmpty() }.toSet()
