package com.irvingguerra.litedash

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.feature.apps.App
import com.irvingguerra.litedash.feature.apps.AppIcon

// Apps conocidas para usar el celular en la pantalla (Android Auto / CarPlay con o sin cable).
private val PROJECTION_PACKAGES = listOf(
    "com.zjinnova.zlink", "cn.manstep.phonemirrorBox", "net.easyconn.carman", "com.suding.speedplay",
    "com.google.android.projection.gearhead",
    // Open Headunit (antes Headunit Revived): Android Auto en pantallas que no lo traen (código abierto).
    "com.andrerinas.headunitrevived",
)

// Si la pantalla trae la suya con otro paquete, se reconoce por el nombre.
private val PROJECTION_WORDS = listOf("carplay", "android auto", "androidauto", "zlink", "autokit", "carlink", "easyconn", "tlink", "phonelink", "carbit", "headunit revived", "open headunit")

/** ¿Ese paquete es una app de Android Auto / CarPlay de la pantalla? (por su paquete: sin pedir su nombre). */
fun isProjectionPackage(pkg: String): Boolean {
    val name = pkg.lowercase()
    return pkg in PROJECTION_PACKAGES || PROJECTION_WORDS.any { it.replace(" ", "") in name }
}

/**
 * La app de proyección instalada que maneja ella misma el Bluetooth del celular (ZLink y parecidas), o null.
 * La de Google no cuenta: es la del celular, no la de una pantalla.
 */
fun projectionPackage(context: android.content.Context): String? =
    PROJECTION_PACKAGES.firstOrNull { it != GOOGLE_AUTO && context.packageManager.getLaunchIntentForPackage(it) != null }

private const val GOOGLE_AUTO = "com.google.android.projection.gearhead"

/** La app de Android Auto / CarPlay que tenga la pantalla, o null. */
fun projectionApp(apps: List<App>): App? =
    PROJECTION_PACKAGES.firstNotNullOfOrNull { p -> apps.firstOrNull { it.pkg == p } }
        ?: apps.firstOrNull { a ->
            val name = a.name.lowercase()
            val pkg = a.pkg.lowercase()
            PROJECTION_WORDS.any { w -> w in name || w.replace(" ", "") in pkg }
        }

/**
 * Pieza Android Auto / CarPlay: abre la app elegida en sus opciones ([chosen]) o la de proyección que
 * encuentre; si no hay, el cajón para buscarla.
 */
@Composable
fun ProjectionTile(d: Dependencies, apps: List<App>, chosen: String?, onMissing: () -> Unit) {
    val context = LocalContext.current
    val app = chosen?.let { d.apps.find(apps, it) } ?: projectionApp(apps)
    ShortcutTile(
        Icons.phoneCar, stringResource(R.string.tile_projection), app?.name ?: stringResource(R.string.projection_missing),
        onClick = { if (app != null) d.apps.open(context, app) else onMissing() },
        // Con app, su ícono real y una flecha: se ve que ahí se abre.
        image = app?.let { a -> { side: Dp -> AppIcon(d.apps, a, side) } },
        chevron = app != null,
    )
}
