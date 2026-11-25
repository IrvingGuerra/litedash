package com.irvingguerra.litedash.feature.music

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * Para las estadísticas anónimas: la radio y el Bluetooth **de fábrica** de esta pantalla, para saber qué
 * familias conviene soportar, y la receta de sonido que le funcionó a cada uno (para el catálogo compartido).
 * Solo paquetes que vienen en el sistema (nunca apps que instaló el usuario) y sin nada del celular ni de lo que
 * suena. Bloquea (consultas al sistema): va en un hilo de IO.
 */
object FactoryAudio {
    /** Lo que se manda cuando la pantalla no trae esa app. */
    const val NONE = "none"

    /** El Bluetooth es el de Android (sin app de marca). */
    const val ANDROID = "android"

    /** La FM genérica de Android: solo si no hay otra (la de la marca dice más). */
    private const val ANDROID_FM = "com.android.fmradio"

    /** El paquete de la radio de fábrica ("com.hcn.autoradio"…); [NONE] si no trae. */
    fun radio(context: Context): String = pickRadio(systemLaunchable(context))

    /** Para el reporte: todas las apps del sistema con pantalla que parecen una radio (no solo la elegida). */
    fun radioCandidates(context: Context): List<String> =
        systemLaunchable(context).filter { pkg -> parts(pkg).any { "radio" in it || it.startsWith("fm") } }.sorted()

    /** De las apps del sistema, la radio: la conocida, si no la de la marca y al último la FM genérica de Android. */
    internal fun pickRadio(system: Set<String>): String {
        val found = system.filter { pkg -> parts(pkg).any { "radio" in it || it.startsWith("fm") } }
        // Las que LiteDash ya controla ganan si hay varias.
        return HeadUnit.radioApps.firstOrNull { it in found }
            ?: found.filter { it != ANDROID_FM }.minOrNull()
            ?: ANDROID_FM.takeIf { it in found }
            ?: NONE
    }

    /** La app de Bluetooth de marca ("com.autochips.bluetooth"…); si no hay, [ANDROID] o [NONE] si no tiene Bluetooth. */
    fun bluetooth(context: Context): String =
        pickBluetooth(
            brand = HeadUnit.bluetoothApps.filter { isSystemApp(context, it) },
            system = systemLaunchable(context),
            hasBluetooth = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH),
        )

    internal fun pickBluetooth(brand: List<String>, system: Set<String>, hasBluetooth: Boolean): String =
        brand.firstOrNull()
            ?: system.filter(::looksBluetooth).minOrNull()
            ?: if (hasBluetooth) ANDROID else NONE

    /**
     * La receta de sonido que funcionó en el Bluetooth (ver [Recipes]) para las estadísticas; null si no se ha calibrado o
     * si no es de la familia que se manda ([HeadUnit.ofSystem]). Bloquea.
     */
    fun bluetoothRecipe(context: Context): String? = countedRecipe(savedRecipe(context, SoundPiece.MUSIC), HeadUnit.ofSystem(context))

    /**
     * La receta guardada que cuenta para [family]: «nada funcionó» siempre; una de la marca solo si es de esa familia (el
     * motor la pudo guardar con la app instalada a mano, y `docs/recetas.py` la contaría para la familia equivocada).
     */
    internal fun countedRecipe(saved: String?, family: HeadUnit): String? =
        saved?.takeIf { it == Recipes.FAILED || it in family.bluetoothRecipes }

    /** La receta de sonido que funcionó en la radio de fábrica; null si no se ha calibrado. */
    fun radioRecipe(context: Context): String? = savedRecipe(context, SoundPiece.RADIO)

    private fun savedRecipe(context: Context, piece: SoundPiece) =
        PrefsRecipeStore.savedId(context.getSharedPreferences(MUSIC_STORE, Context.MODE_PRIVATE), piece)

    /** La familia de la pantalla por sus paquetes del sistema ("nwd", "topway"… o "generic"). Bloquea: varias consultas al sistema. */
    fun family(context: Context): String = HeadUnit.ofSystem(context).id

    /** La pantalla tiene Bluetooth de baja energía (lo que usaría el enlace con el celular sin hotspot). */
    fun ble(context: Context): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    /** Apps del sistema con ícono en el cajón (sin LiteDash). */
    private fun systemLaunchable(context: Context): Set<String> = runCatching {
        context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.applicationInfo }
            .filter { it.packageName != context.packageName && it.flags and ApplicationInfo.FLAG_SYSTEM != 0 }
            .map { it.packageName }.toSet()
    }.getOrDefault(emptySet())

    /** Partes del paquete sin el prefijo ("com.hcn.autoradio" → hcn, autoradio): la radio viene pegada ("fmradio"). */
    private fun parts(pkg: String) = pkg.lowercase().split('.').drop(1)
}
