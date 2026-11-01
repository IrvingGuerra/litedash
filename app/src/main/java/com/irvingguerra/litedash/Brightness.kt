package com.irvingguerra.litedash

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography

/**
 * Brillo de día y de noche con la hora del sol (el mismo cálculo del tema): sin
 * sensor ni señal del auto, así funciona en cualquier pantalla. Solo cambia algo
 * al amanecer y al atardecer (no revisa cada rato). Sin el permiso de ajustes del
 * sistema, el brillo aplica solo mientras se ve LiteDash.
 */
@Composable
fun ApplyBrightness(prefs: Preferences, revision: Int) {
    val activity = LocalActivity.current ?: return
    if (!prefs.brightnessBySun) {
        LaunchedEffect(Unit) { windowBrightness(activity, null) }
        return
    }
    val night = rememberIsNight()
    val level = if (night) prefs.nightBrightness else prefs.dayBrightness
    // [revision] sube al volver a LiteDash: así aplica justo después de dar el permiso.
    LaunchedEffect(level, prefs.brightnessAllApps, revision) {
        if (prefs.brightnessAllApps && Settings.System.canWrite(activity)) {
            // El del sistema ya cubre a LiteDash: la ventana sigue al sistema.
            windowBrightness(activity, null)
            runCatching {
                val r = activity.contentResolver
                Settings.System.putInt(r, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(r, Settings.System.SCREEN_BRIGHTNESS, (level * 255 / 100).coerceIn(1, 255))
            }
        } else {
            windowBrightness(activity, level)
        }
    }
}

private fun windowBrightness(activity: Activity, percent: Int?) {
    val window = activity.window
    window.attributes = window.attributes.apply {
        screenBrightness = percent?.let { it / 100f } ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    }
}

private fun requestWriteSettings(context: Context) {
    val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, "package:${context.packageName}".toUri())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/** Ajustes → Apariencia → Brillo: encender, cuánto de día y de noche, y en todas las apps. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BrightnessSettings(prefs: Preferences, revision: Int) {
    val c = LocalColors.current
    val context = LocalContext.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(stringResource(R.string.brightness_system), { prefs.brightnessBySun = false }, primary = !prefs.brightnessBySun)
        Button(stringResource(R.string.brightness_sun), { prefs.brightnessBySun = true }, primary = prefs.brightnessBySun)
    }
    if (!prefs.brightnessBySun) return
    Level(Icons.sun, stringResource(R.string.brightness_day), prefs.dayBrightness) { prefs.dayBrightness = it }
    Level(Icons.moon, stringResource(R.string.brightness_night), prefs.nightBrightness) { prefs.nightBrightness = it }
    // Se vuelve a revisar al regresar de la pantalla del permiso.
    val canWrite = remember(revision, prefs.brightnessAllApps) { Settings.System.canWrite(context) }
    Label(stringResource(R.string.brightness_all_apps_help), Typography.small, c.textMuted)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(stringResource(R.string.brightness_only_litedash), { prefs.brightnessAllApps = false }, primary = !prefs.brightnessAllApps)
        Button(stringResource(R.string.brightness_all_apps), {
            prefs.brightnessAllApps = true
            if (!Settings.System.canWrite(context)) requestWriteSettings(context)
        }, primary = prefs.brightnessAllApps)
    }
    if (prefs.brightnessAllApps && !canWrite) {
        Label(stringResource(R.string.brightness_permission), Typography.small, c.danger)
    }
}

@Composable
private fun Level(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, value: Int, set: (Int) -> Unit) {
    val c = LocalColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        com.irvingguerra.litedash.core.ui.Icon(icon, c.accent, 22.dp)
        Label(title, Typography.body, modifier = Modifier.widthIn(min = 70.dp))
        IconButton(Icons.minus, { set((value - 10).coerceAtLeast(10)) }, background = c.surfaceHigh)
        Label("$value %", Typography.title, modifier = Modifier.widthIn(min = 90.dp))
        IconButton(Icons.plus, { set((value + 10).coerceAtMost(100)) }, background = c.surfaceHigh)
    }
}
