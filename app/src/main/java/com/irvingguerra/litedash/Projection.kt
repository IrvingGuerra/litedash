package com.irvingguerra.litedash

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.feature.apps.App
import com.irvingguerra.litedash.feature.apps.AppIcon
import com.irvingguerra.litedash.feature.apps.rememberApps

// Apps conocidas para usar el celular en la pantalla (Android Auto / CarPlay con o sin cable).
private val PROJECTION_PACKAGES = listOf(
    "com.zjinnova.zlink", "cn.manstep.phonemirrorBox", "net.easyconn.carman", "com.suding.speedplay",
    "com.google.android.projection.gearhead",
    // Open Headunit (antes Headunit Revived): Android Auto en pantallas que no lo traen (código abierto).
    "com.andrerinas.headunitrevived",
    // Headunit Reloaded: su paquete no dice nada.
    "gb.xxy.hr",
)

// Si la pantalla trae la suya con otro paquete, se reconoce por el nombre (o por una parte del paquete).
// "carletter" = CarLetter de Carlinkit (com.carletter.car); "carlink" ya cubre com.carlinkit.*.
private val PROJECTION_WORDS = listOf(
    "carplay", "android auto", "zlink", "autokit", "carlink", "carletter", "easyconn", "tlink", "phonelink", "phonemirror",
    "speedplay", "carbit", "headunit revived", "headunit reloaded", "open headunit",
)

// Lo que separa palabras en el nombre de una app ("Mi CarPlay", "Z-Link").
private val NAME_SEPARATORS = Regex("[^\\p{L}\\p{N}]+")

/**
 * ¿Es una app de Android Auto / CarPlay de la pantalla? Por su paquete (una parte del paquete empieza con la palabra:
 * "tlink" no confunde a "…btlink…") y, si se sabe, por su nombre con la misma regla (una palabra del nombre empieza con
 * ella: "BTLink" y "SmartLink" no cuentan; "ZLink" y "Mi CarPlay", sí).
 */
fun isProjectionPackage(pkg: String, name: String? = null): Boolean {
    if (pkg in PROJECTION_PACKAGES) return true
    val parts = pkg.lowercase().split('.', '_')
    // " mi carplay": cada palabra lleva un espacio antes, así " carplay" solo se halla al principio de una palabra.
    val label = name?.lowercase()?.split(NAME_SEPARATORS)?.filter { it.isNotEmpty() }?.joinToString(" ", prefix = " ")
    return PROJECTION_WORDS.any { w ->
        val joined = w.replace(" ", "")
        parts.any { it.startsWith(joined) } || (label != null && " $w" in label)
    }
}

/**
 * Para la música (no pausar a la app de proyección al cambiar de fuente): las que reconoce LiteDash por su paquete y
 * también la elegida a mano ([chosenKey], su [App.key]) aunque no se parezca a ninguna.
 */
fun isProjection(pkg: String, chosenKey: String?): Boolean =
    isProjectionPackage(pkg) || (chosenKey != null && pkg == chosenKey.substringBefore('/'))

/** La app de Android Auto / CarPlay que tenga la pantalla (primero las conocidas), o null. */
private fun projectionApp(apps: List<App>): App? =
    PROJECTION_PACKAGES.firstNotNullOfOrNull { p -> apps.firstOrNull { it.pkg == p } }
        ?: apps.firstOrNull { isProjectionPackage(it.pkg, it.name) }

/** La que se abre: la elegida ([chosen], su [App.key]) si sigue instalada; si no, la que encuentre LiteDash. */
fun projectionChoice(chosen: String?, apps: List<App>): App? =
    chosen?.let { key -> apps.firstOrNull { it.key == key } } ?: projectionApp(apps)

/** La app de Android Auto / CarPlay de toda la app: la usan su botón de la barra, el widget y el volante. */
fun projectionChoice(d: Dependencies, apps: List<App>): App? = projectionChoice(d.prefs.projectionApp, apps)

/**
 * Widget Android Auto / CarPlay: abre [projectionChoice]; si no hay ninguna, [onMissing] (sus ajustes, para elegirla).
 */
@Composable
fun ProjectionTile(d: Dependencies, apps: List<App>, onMissing: () -> Unit) {
    val context = LocalContext.current
    // La app propia que guardaban los widgets hasta la 1.5.15 ya se adoptó al arrancar ([Preferences.adoptWidgetApps]).
    val app = projectionChoice(d, apps)
    // Mientras se lee la lista de apps no se sabe si falta: ni «No encontré…» ni mandar a sus ajustes.
    val loading = d.apps.apps == null
    ShortcutTile(
        Icons.phoneCar, stringResource(R.string.tile_projection),
        app?.name ?: if (loading) null else stringResource(R.string.projection_missing),
        onClick = { if (app != null) d.apps.open(context, app) else if (!loading) onMissing() },
        // Con app, su ícono real y una flecha: se ve que ahí se abre.
        image = app?.let { a -> { side: Dp -> AppIcon(d.apps, a, side) } },
        chevron = app != null,
    )
}

/**
 * Ajustes → Apps → Android Auto / CarPlay → "Tu app de Android Auto / CarPlay", con el selector de apps de siempre: las
 * de proyección instaladas (con palomita la que se abre: la elegida o, en automático, la que encuentra LiteDash), "Otra
 * app…" para cualquiera (algunas pantallas la traen con otro nombre) y "Quitar" para volver a automático.
 */
@Composable
internal fun ProjectionSettings(d: Dependencies) {
    val loaded = rememberApps(d.apps)
    val apps = loaded.orEmpty()
    val chosen = d.prefs.projectionApp
    val current = projectionChoice(chosen, apps)
    // «Quitar» solo si la elegida sigue instalada: si se desinstaló ya se abre la automática y quitarla no cambiaría nada.
    val chosenInstalled = chosen != null && apps.any { it.key == chosen }
    // Las de la lista ya cargada (en memoria): no se le pregunta nada a Android aquí.
    val found = remember(apps, current) { (apps.filter { isProjectionPackage(it.pkg, it.name) } + listOfNotNull(current)).distinctBy { it.key } }
    AppPicker(
        apps, found.map { it.key to it.name }, current?.key, { d.prefs.projectionApp = it }, { d.prefs.projectionApp = it.key },
        onClear = if (chosenInstalled) { { d.prefs.projectionApp = null } } else null,
    )
    // Mientras carga la lista no se dice «No encontré…».
    if (loaded != null && found.isEmpty()) Label(stringResource(R.string.projection_missing), Typography.body, LocalColors.current.textMuted)
    Label(stringResource(R.string.projection_help), Typography.small, LocalColors.current.textMuted)
}
