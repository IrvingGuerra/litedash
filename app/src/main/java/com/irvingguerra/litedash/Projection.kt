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

// Si la pantalla trae la suya con otro paquete, se reconoce por el nombre (o por una parte del paquete).
private val PROJECTION_WORDS = listOf("carplay", "android auto", "zlink", "autokit", "carlink", "easyconn", "tlink", "phonelink", "carbit", "headunit revived", "open headunit")

/**
 * ¿Es una app de Android Auto / CarPlay de la pantalla? Por su paquete (una parte del paquete empieza con la palabra:
 * "tlink" no confunde a "…btlink…") y, si se sabe, por su nombre.
 */
fun isProjectionPackage(pkg: String, name: String? = null): Boolean {
    if (pkg in PROJECTION_PACKAGES) return true
    val parts = pkg.lowercase().split('.', '_')
    val label = name?.lowercase()
    return PROJECTION_WORDS.any { w ->
        val joined = w.replace(" ", "")
        parts.any { it.startsWith(joined) } || (label != null && w in label)
    }
}

/**
 * La app de proyección conocida que esté instalada (ZLink, EasyConnection, Open Headunit…), o null; el botón de
 * Bluetooth la abre. La de Google no cuenta: es la del celular, no la de una pantalla.
 */
fun projectionPackage(context: android.content.Context): String? =
    PROJECTION_PACKAGES.firstOrNull { it != GOOGLE_AUTO && context.packageManager.getLaunchIntentForPackage(it) != null }

private const val GOOGLE_AUTO = "com.google.android.projection.gearhead"

/** La app de Android Auto / CarPlay que tenga la pantalla (primero las conocidas), o null. */
fun projectionApp(apps: List<App>): App? =
    PROJECTION_PACKAGES.firstNotNullOfOrNull { p -> apps.firstOrNull { it.pkg == p } }
        ?: apps.firstOrNull { isProjectionPackage(it.pkg, it.name) }

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
