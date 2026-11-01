package com.irvingguerra.litedash

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.feature.apps.App

// Apps conocidas para usar el celular en la pantalla (Android Auto / CarPlay con o sin cable).
private val PROJECTION_PACKAGES = listOf(
    "com.zjinnova.zlink", "cn.manstep.phonemirrorBox", "net.easyconn.carman", "com.suding.speedplay",
    "com.google.android.projection.gearhead",
)

// Si la pantalla trae la suya con otro paquete, se reconoce por el nombre.
private val PROJECTION_WORDS = listOf("carplay", "android auto", "androidauto", "zlink", "autokit", "carlink", "easyconn", "tlink", "phonelink", "carbit")

/** La app de Android Auto / CarPlay que tenga la pantalla, o null. */
fun projectionApp(apps: List<App>): App? =
    PROJECTION_PACKAGES.firstNotNullOfOrNull { p -> apps.firstOrNull { it.pkg == p } }
        ?: apps.firstOrNull { a ->
            val name = a.name.lowercase()
            val pkg = a.pkg.lowercase()
            PROJECTION_WORDS.any { w -> w in name || w.replace(" ", "") in pkg }
        }

/** Pieza Android Auto / CarPlay: abre la app de proyección; si no hay, el cajón para buscarla. */
@Composable
fun ProjectionTile(d: Dependencies, apps: List<App>, onMissing: () -> Unit) {
    val context = LocalContext.current
    val app = projectionApp(apps)
    ShortcutTile(Icons.phoneCar, stringResource(R.string.tile_projection), app?.name ?: stringResource(R.string.projection_missing)) {
        if (app != null) d.apps.open(context, app) else onMissing()
    }
}
