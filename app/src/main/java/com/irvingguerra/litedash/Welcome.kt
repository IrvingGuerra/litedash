package com.irvingguerra.litedash

import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.MorseMark
import com.irvingguerra.litedash.core.ui.TextInput
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.feature.car.CarDrawing
import com.irvingguerra.litedash.feature.dashboard.DashboardState
import com.irvingguerra.litedash.feature.dashboard.Template
import com.irvingguerra.litedash.feature.dashboard.size
import com.irvingguerra.litedash.feature.dashboard.coarse
import com.irvingguerra.litedash.feature.dashboard.suggestedTemplate
import com.irvingguerra.litedash.feature.dashboard.tiles

/** ¿LiteDash ya es la pantalla de inicio? */
fun isHomeApp(context: Context): Boolean {
    // Android 10+: el rol del sistema. Sin esto, desde Android 11 las otras apps de
    // inicio no se ven y LiteDash creería que es la única.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        context.getSystemService(RoleManager::class.java)?.let { return it.isRoleHeld(RoleManager.ROLE_HOME) }
    }
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    val resolved = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
    return resolved?.activityInfo?.packageName == context.packageName
}

/**
 * Primer arranque: 1) tu auto (nombre y color), 2) cómo lo quieres (acomodo y
 * tema), 3) hacerlo tu pantalla de inicio. Todo se puede cambiar después.
 */
@Composable
fun Welcome(prefs: Preferences, dashboard: DashboardState, revision: Int, onFinish: () -> Unit) {
    val c = LocalColors.current
    var step by remember { mutableIntStateOf(prefs.welcomeStep.coerceIn(0, 2)) }
    fun goTo(n: Int) {
        step = n
        prefs.welcomeStep = n
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .safeDrawingPadding()
            .padding(horizontal = 32.dp, vertical = 20.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MorseMark(Modifier.size(width = 92.dp, height = 24.dp))
            Spacer(Modifier.weight(1f))
            // Tres puntos: dónde vas.
            for (i in 0..2) {
                Box(
                    Modifier
                        .padding(start = 8.dp)
                        .size(width = if (i == step) 28.dp else 10.dp, height = 10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (i <= step) c.accent else c.line),
                )
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 24.dp)) {
            when (step) {
                0 -> CarStep(prefs)
                1 -> StyleStep(prefs, dashboard)
                else -> HomeStep(revision)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (step > 0) Button(stringResource(R.string.back), { goTo(step - 1) })
            Spacer(Modifier.weight(1f))
            if (step < 2) {
                Button(stringResource(R.string.skip), onFinish)
                Button(stringResource(R.string.next), { goTo(step + 1) }, primary = true)
            } else {
                Button(stringResource(R.string.start), onFinish, primary = true)
            }
        }
    }
}

@Composable
private fun Titles(title: String, text: String) {
    Label(title, Typography.clock.copy(fontSize = Typography.title.fontSize * 1.6f))
    Spacer(Modifier.height(8.dp))
    Label(text, Typography.body, LocalColors.current.textMuted, modifier = Modifier.widthIn(max = 640.dp))
    Spacer(Modifier.height(28.dp))
}

@Composable
private fun CarStep(prefs: Preferences) {
    val c = LocalColors.current
    Titles(stringResource(R.string.welcome_title), stringResource(R.string.welcome_car))
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val horizontal = maxWidth > 700.dp
        val drawing = @Composable { m: Modifier ->
            CarDrawing(prefs.carColor, m)
        }
        val options = @Composable { m: Modifier ->
            Column(m, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Label(stringResource(R.string.your_name), Typography.label, c.textMuted)
                DriverNameField(prefs)
                Label(stringResource(R.string.car_name), Typography.label, c.textMuted)
                CarNameField(prefs, stringResource(R.string.my_car))
                Label(stringResource(R.string.color), Typography.label, c.textMuted)
                CarColorPicker(prefs)
            }
        }
        if (horizontal) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                drawing(Modifier.weight(1.2f))
                options(Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                drawing(Modifier.fillMaxWidth())
                options(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun StyleStep(prefs: Preferences, dashboard: DashboardState) {
    val c = LocalColors.current
    Titles(stringResource(R.string.welcome_style_title), stringResource(R.string.welcome_style))
    Label(stringResource(R.string.layout), Typography.label, c.textMuted)
    Spacer(Modifier.height(12.dp))
    val grid = dashboard.grid
    // Los acomodos que lucen en esta pantalla, el recomendado primero.
    val options = grid?.coarse?.let { c -> listOf(c.suggestedTemplate()) + Template.entries.filter { c.size in it.sizes } }
        ?.distinct().orEmpty()
    // Marcado el que ya está puesto (al entrar, el recomendado de fábrica): el mismo acomodo, sin importar los ids.
    val shown = dashboard.shown
    val current = remember(grid, shown, options) {
        val placed = shown.map { listOf(it.col, it.row, it.width, it.height) }.toSet()
        options.firstOrNull { p -> grid != null && p.tiles(grid, skip = dashboard.hidden).map { listOf(it.col, it.row, it.width, it.height) }.toSet() == placed }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (p in options) {
            Button(stringResource(nameOf(p)), { dashboard.template(p) }, primary = current == p)
        }
    }
    Spacer(Modifier.height(28.dp))
    Label(stringResource(R.string.theme), Typography.label, c.textMuted)
    Spacer(Modifier.height(12.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for ((mode, text) in ThemeOptions) {
            Button(stringResource(text), { prefs.theme = mode }, primary = prefs.theme == mode)
        }
    }
    Spacer(Modifier.height(20.dp))
    Label(stringResource(R.string.welcome_change_later), Typography.small, c.textMuted)
}

/**
 * Pide ser la pantalla de inicio: el diálogo del sistema (Android 10+) y, si la
 * pantalla no lo tiene, sus ajustes de inicio o de apps predeterminadas.
 * Devuelve la acción y un contador que sube al volver del diálogo.
 */
@Composable
fun rememberRequestHome(): Pair<() -> Unit, Int> {
    val context = LocalContext.current
    var returns by remember { mutableIntStateOf(0) }
    val requestRole = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { returns++ }
    val request = {
        if (!requestHomeRole(context) { requestRole.launch(it) }) {
            // "Apps predeterminadas" existe desde Android 7.
            val defaults = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS else null
            listOfNotNull(Settings.ACTION_HOME_SETTINGS, defaults, Settings.ACTION_SETTINGS)
                .firstOrNull { action -> openSystemScreen(context, Intent(action)) }
        }
        Unit
    }
    return request to returns
}

/**
 * El diálogo del sistema para ser la pantalla de inicio (Android 10+), lanzado con [launch]. false si esta pantalla
 * no lo tiene (Android 9 o menos, o una capa de la marca sin ese rol): entonces se abren sus ajustes.
 */
private fun requestHomeRole(context: Context, launch: (Intent) -> Unit): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
    val roles = context.getSystemService(RoleManager::class.java) ?: return false
    if (!roles.isRoleAvailable(RoleManager.ROLE_HOME)) return false
    return try {
        launch(roles.createRequestRoleIntent(RoleManager.ROLE_HOME))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}

@Composable
private fun HomeStep(revision: Int) {
    Titles(stringResource(R.string.welcome_home_title), stringResource(R.string.welcome_home))
    MakeHome(revision, big = true)
}

/**
 * "Hazlo tu pantalla de inicio": si ya lo es, lo dice; si no, el botón para pedirlo ([beforeRequest] corre antes).
 * Se vuelve a revisar al regresar de Ajustes ([revision] cambia en onResume) o del diálogo. [big]: la bienvenida
 * (con palomita y ayuda); si no, como renglón de Ajustes.
 */
@Composable
internal fun MakeHome(revision: Int, big: Boolean, beforeRequest: () -> Unit = {}) {
    val c = LocalColors.current
    val context = LocalContext.current
    val (request, returns) = rememberRequestHome()
    val isHome = remember(revision, returns) { isHomeApp(context) }
    when {
        isHome && big -> Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                Icon(Icons.done, c.onPrimary, 24.dp)
            }
            Spacer(Modifier.width(14.dp))
            Label(stringResource(R.string.welcome_already_home), Typography.subtitle)
        }
        isHome -> Label(stringResource(R.string.welcome_already_home), Typography.body, c.accent)
        else -> {
            Button(stringResource(R.string.make_home), { beforeRequest(); request() }, primary = true)
            if (big) {
                Spacer(Modifier.height(16.dp))
                Label(stringResource(R.string.welcome_home_help), Typography.small, c.textMuted, modifier = Modifier.widthIn(max = 640.dp))
            }
        }
    }
}

/** Tu nombre (para el saludo del inicio): hasta 24 letras; vacío = sin nombre. */
@Composable
internal fun DriverNameField(prefs: Preferences) {
    TextInput(prefs.driverName ?: "", { prefs.driverName = nameOrNull(it) }, stringResource(R.string.your_name_hint), Modifier.fillMaxWidth())
}

/** El nombre de tu auto: hasta 24 letras; vacío = el del modelo ([hint]). */
@Composable
internal fun CarNameField(prefs: Preferences, hint: String) {
    TextInput(prefs.carName ?: "", { prefs.carName = nameOrNull(it) }, hint, Modifier.fillMaxWidth())
}

/** Un nombre escrito, recortado a 24 letras; null si quedó en blanco. */
private fun nameOrNull(text: String) = text.take(24).ifBlank { null }

/** El color de tu auto, con vista previa en vivo mientras lo eliges. */
@Composable
internal fun CarColorPicker(prefs: Preferences) {
    ColorPicker(prefs.carColor, prefs::previewCarColor) { prefs.carColor = it }
}
