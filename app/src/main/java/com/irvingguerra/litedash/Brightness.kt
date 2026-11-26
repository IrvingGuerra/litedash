package com.irvingguerra.litedash

import com.irvingguerra.litedash.core.ui.Segmented
import android.app.Activity
import android.content.Context
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Brillo de día y de noche: [night] es el mismo que decide el tema (hora del sol, sensor de
 * luz o faros, según "¿Cuándo es de noche?"). Solo cambia algo cuando cambia el día o la
 * noche (no revisa cada rato). Sin el permiso de ajustes del sistema, el brillo aplica solo
 * mientras se ve LiteDash; con él cambia el del sistema y, al apagarlo, regresa el que tenía
 * el usuario. Los ajustes del sistema se escriben fuera del hilo principal.
 */
@Composable
fun ApplyBrightness(prefs: Preferences, night: Boolean, revision: Int) {
    val activity = LocalActivity.current ?: return
    val level = if (night) prefs.nightBrightness else prefs.dayBrightness
    // [revision] sube al volver a LiteDash: así aplica justo después de dar el permiso.
    // Si los faros se aprendieron por el brillo de la pantalla, el del sistema no se toca: LiteDash apagaría su propia señal.
    val signal = brightnessIsTheSignal(prefs.lightsKey, prefs.lightsOnValue)
    LaunchedEffect(prefs.brightnessBySun, level, prefs.brightnessAllApps, signal, revision) {
        val system = prefs.brightnessBySun && prefs.brightnessAllApps && !signal && Settings.System.canWrite(activity)
        windowBrightness(activity, level.takeIf { prefs.brightnessBySun && !system })
        withContext(Dispatchers.IO) {
            // Con dos inicios abiertos, uno a la vez: si no, el segundo guardaba el brillo de LiteDash como "el original".
            synchronized(SystemBrightnessLock) {
                if (system) setSystemBrightness(activity, prefs, level) else restoreSystemBrightness(activity, prefs)
            }
        }
    }
}

private object SystemBrightnessLock

/** Cambia el brillo del sistema; la primera vez guarda el que tenía el usuario. */
private fun setSystemBrightness(context: Context, prefs: Preferences, percent: Int) {
    val r = context.contentResolver
    if (prefs.brightnessOriginal == null) {
        // Sin valor guardado en la pantalla: manual y a todo (lo de fábrica de Android).
        prefs.brightnessOriginal = Settings.System.getInt(r, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) to
            Settings.System.getInt(r, Settings.System.SCREEN_BRIGHTNESS, 255)
    }
    writeBrightness(context, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL, (percent * 255 / 100).coerceIn(1, 255))
}

/** Regresa el brillo del sistema como estaba antes de LiteDash (automático incluido). */
private fun restoreSystemBrightness(context: Context, prefs: Preferences) {
    val (mode, level) = prefs.brightnessOriginal ?: return
    if (!Settings.System.canWrite(context)) return
    if (writeBrightness(context, mode, level)) prefs.brightnessOriginal = null
}

/** Escribe modo y nivel del brillo del sistema; false si Android quitó el permiso entre la revisión y la escritura. */
private fun writeBrightness(context: Context, mode: Int, level: Int): Boolean = try {
    val r = context.contentResolver
    // El nivel antes que el modo: al regresar a automático, el nivel manual del usuario ya quedó.
    Settings.System.putInt(r, Settings.System.SCREEN_BRIGHTNESS, level)
    Settings.System.putInt(r, Settings.System.SCREEN_BRIGHTNESS_MODE, mode)
    true
} catch (e: SecurityException) {
    Log.w("LiteDash", "Sin permiso para el brillo del sistema", e)
    false
}

private fun windowBrightness(activity: Activity, percent: Int?) {
    val window = activity.window
    window.attributes = window.attributes.apply {
        screenBrightness = percent?.let { it / 100f } ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    }
}

// Algunas pantallas de auto no traen esa página de Ajustes: entonces no pasa nada (queda en el registro).
private fun requestWriteSettings(context: Context) {
    openSystemScreen(context, appSettingsIntent(context, Settings.ACTION_MANAGE_WRITE_SETTINGS))
}

/** Ajustes → Inicio y apariencia → Brillo: encender, cuánto de día y de noche, y en todas las apps. */
@Composable
fun BrightnessSettings(prefs: Preferences, revision: Int) {
    val c = LocalColors.current
    val context = LocalContext.current
    Segmented(
        listOf(false to stringResource(R.string.brightness_system), true to stringResource(R.string.brightness_sun)),
        prefs.brightnessBySun,
        { prefs.brightnessBySun = it },
    )
    if (!prefs.brightnessBySun) return
    Level(Icons.sun, stringResource(R.string.brightness_day), prefs.dayBrightness) { prefs.dayBrightness = it }
    Level(Icons.moon, stringResource(R.string.brightness_night), prefs.nightBrightness) { prefs.nightBrightness = it }
    // Se vuelve a revisar al regresar de la pantalla del permiso.
    val canWrite = remember(revision, prefs.brightnessAllApps) { Settings.System.canWrite(context) }
    Label(stringResource(R.string.brightness_all_apps_help), Typography.small, c.textMuted)
    Segmented(
        listOf(false to stringResource(R.string.brightness_only_litedash), true to stringResource(R.string.brightness_all_apps)),
        prefs.brightnessAllApps,
        { all ->
            prefs.brightnessAllApps = all
            if (all && !Settings.System.canWrite(context)) requestWriteSettings(context)
        },
    )
    if (prefs.brightnessAllApps && !canWrite) {
        Label(stringResource(R.string.brightness_permission), Typography.small, c.danger)
    }
}

@Composable
private fun Level(icon: ImageVector, title: String, value: Int, set: (Int) -> Unit) {
    val c = LocalColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, c.accent, 22.dp)
        Label(title, Typography.body, modifier = Modifier.widthIn(min = 70.dp))
        IconButton(Icons.minus, { set((value - 10).coerceAtLeast(10)) }, background = c.surfaceHigh)
        Label(stringResource(R.string.brightness_level, value), Typography.title, modifier = Modifier.widthIn(min = 90.dp))
        IconButton(Icons.plus, { set((value + 10).coerceAtMost(100)) }, background = c.surfaceHigh)
    }
}
