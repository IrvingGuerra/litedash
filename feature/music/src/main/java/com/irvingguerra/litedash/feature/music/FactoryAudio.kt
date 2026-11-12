package com.irvingguerra.litedash.feature.music

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * Para las estadísticas anónimas: la radio y el Bluetooth **de fábrica** de esta pantalla, para saber qué
 * familias conviene soportar. Solo paquetes que vienen en el sistema (nunca apps que instaló el usuario) y sin
 * nada del celular ni de lo que suena. Bloquea (consultas al sistema): va en un hilo de IO.
 */
object FactoryAudio {
    /** Lo que se manda cuando la pantalla no trae esa app. */
    const val NONE = "none"

    /** El Bluetooth es el de Android (sin app de marca). */
    const val ANDROID = "android"

    /** Radios de fábrica que LiteDash ya controla: si hay varias, gana la conocida. */
    private val KNOWN_RADIOS = listOf(HcnRadio.PACKAGE, NwdRadio.PACKAGE, "com.tw.radio")

    /** La FM genérica de Android: solo si no hay otra (la de la marca dice más). */
    private const val ANDROID_FM = "com.android.fmradio"

    /** El paquete de la radio de fábrica ("com.hcn.autoradio"…); [NONE] si no trae. */
    fun radio(context: Context): String = pickRadio(systemLaunchable(context))

    /** De las apps del sistema, la radio: la conocida, si no la de la marca y al último la FM genérica de Android. */
    internal fun pickRadio(system: Set<String>): String {
        val found = system.filter { pkg -> parts(pkg).any { "radio" in it || it.startsWith("fm") } }
        return KNOWN_RADIOS.firstOrNull { it in found }
            ?: found.filter { it != ANDROID_FM }.minOrNull()
            ?: ANDROID_FM.takeIf { it in found }
            ?: NONE
    }

    /** La app de Bluetooth de marca ("com.autochips.bluetooth"…); si no hay, [ANDROID] o [NONE] si no tiene Bluetooth. */
    fun bluetooth(context: Context): String =
        pickBluetooth(
            brand = BRAND_BT_APPS.filter { isSystem(context, it) },
            system = systemLaunchable(context),
            hasBluetooth = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH),
        )

    internal fun pickBluetooth(brand: List<String>, system: Set<String>, hasBluetooth: Boolean): String =
        brand.firstOrNull()
            ?: system.filter { pkg -> parts(pkg).any { it.startsWith("bt") || "bluetooth" in it } }.minOrNull()
            ?: if (hasBluetooth) ANDROID else NONE

    /** La pantalla tiene Bluetooth de baja energía (lo que usaría el enlace con el celular sin hotspot). */
    fun ble(context: Context): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    /** Apps del sistema con ícono en el cajón (sin LiteDash). */
    private fun systemLaunchable(context: Context): Set<String> = runCatching {
        context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.applicationInfo }
            .filter { it.packageName != context.packageName && it.flags and ApplicationInfo.FLAG_SYSTEM != 0 }
            .map { it.packageName }.toSet()
    }.getOrDefault(emptySet())

    private fun isSystem(context: Context, pkg: String) =
        runCatching { context.packageManager.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0 }.getOrDefault(false)

    /** Partes del paquete sin el prefijo ("com.hcn.autoradio" → hcn, autoradio): la radio viene pegada ("fmradio"). */
    private fun parts(pkg: String) = pkg.lowercase().split('.').drop(1)
}
