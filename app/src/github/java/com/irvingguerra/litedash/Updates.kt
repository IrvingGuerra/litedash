// Solo la versión de GitHub: buscar, bajar e instalar versiones nuevas (Ajustes → Sistema → Actualizaciones y el
// aviso del inicio). La de Play no trae nada de esto (ver app/src/play).
package com.irvingguerra.litedash

import android.content.Context
import android.content.pm.PackageInstaller
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Radius
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.feature.updater.InstallFailure
import com.irvingguerra.litedash.feature.updater.Updater
import com.irvingguerra.litedash.feature.updater.Version
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** El actualizador de la versión de GitHub (Releases del repo público). */
internal fun appUpdates(app: Context): AppUpdates? = GithubUpdates(Updater(app, BuildConfig.UPDATES_URL, BuildConfig.VERSION_NAME))

private class GithubUpdates(private val updater: Updater) : AppUpdates {
    override suspend fun checkQuietly() = updater.checkQuietly()

    @Composable
    override fun Banner(onOpen: () -> Unit) {
        val v = updater.notice ?: return
        Notice(stringResource(R.string.new_version, v.name), Icons.info) {
            Button(stringResource(R.string.update_see), onOpen, primary = true)
            Button(stringResource(R.string.update_later), updater::dismiss)
        }
    }

    @Composable
    override fun Card() = SettingsCard { Updates(updater) }

    override fun describe() = "Actualizaciones: " +
        (Updater.installFailure?.let { "el instalador falló (${it.status}: ${it.message})" } ?: "sin fallas del instalador") +
        (updater.downloadError?.let { " · la descarga falló ($it)" } ?: "")
}

/** Cuánto se espera la confirmación de Android antes de ofrecer el instalador de siempre. */
private const val INSTALLER_WAIT_MS = 8_000L

/** Estados del botón de actualizaciones. */
private sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val v: Version) : UpdateState
    data class NeedsPermission(val v: Version) : UpdateState
    data object Error : UpdateState
}

@Composable
private fun Updates(a: Updater) {
    val c = LocalColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // La confirmación de Android ("¿Instalar?") la abre esta pantalla, que está al frente.
    val confirm = Updater.confirm
    LaunchedEffect(confirm) {
        if (confirm == null) return@LaunchedEffect
        Updater.confirm = null
        runCatching { context.startActivity(confirm) }
    }
    // Si al abrir ya se encontró una versión nueva, se muestra de una vez.
    var state by remember { mutableStateOf(a.found?.let { UpdateState.Available(it) } ?: UpdateState.Idle) }

    fun install(v: Version) {
        if (!a.canInstall()) {
            state = UpdateState.NeedsPermission(v)
            a.requestInstallPermission()
            return
        }
        // La descarga vive en el Updater: sigue aunque se cierre Ajustes.
        a.downloadAndInstall(v)
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (val p = a.progress) {
            is Updater.Progress.Downloading -> Label(stringResource(R.string.downloading, p.percent), Typography.body, c.accent)
            is Updater.Progress.Installing -> {
                Label(stringResource(R.string.installing), Typography.body, c.accent)
                // Si la confirmación de Android no aparece en unos segundos (o falla), el respaldo.
                var stuck by remember(p) { mutableStateOf(false) }
                LaunchedEffect(p) {
                    delay(INSTALLER_WAIT_MS)
                    stuck = true
                }
                if (stuck || Updater.installFailure != null) {
                    Button(stringResource(R.string.open_installer), { a.openWithInstaller(p.apk) }, primary = true)
                }
            }
            // Sin descarga en curso: buscar, la versión encontrada o el permiso de instalar.
            else -> when (val e = state) {
                UpdateState.Idle, UpdateState.UpToDate, UpdateState.Error -> {
                    Button(stringResource(R.string.check_updates), {
                        state = UpdateState.Checking
                        scope.launch {
                            state = runCatching { a.checkForUpdate() }
                                .fold({ v -> if (v == null) UpdateState.UpToDate else UpdateState.Available(v) }, { UpdateState.Error })
                        }
                    })
                    if (e == UpdateState.UpToDate) Label(stringResource(R.string.up_to_date), Typography.small, c.accent)
                }
                UpdateState.Checking -> Label(stringResource(R.string.checking), Typography.body, c.textMuted)
                is UpdateState.Available -> NewVersion(e.v) { install(e.v) }
                is UpdateState.NeedsPermission -> {
                    Label(stringResource(R.string.install_permission), Typography.small, c.textMuted)
                    Button(stringResource(R.string.already_allowed), { install(e.v) }, primary = true)
                }
            }
        }
        // Falló la búsqueda o la última descarga (cada una con su aviso: no es lo mismo no revisar que no bajar).
        if (a.progress == Updater.Progress.Failed) Label(downloadError(R.string.update_download_error), Typography.small, c.danger)
        else if (state == UpdateState.Error) Label(downloadError(R.string.update_error), Typography.small, c.danger)
        Updater.installFailure?.let { Label(stringResource(failureText(it)), Typography.small, c.danger) }
    }
}

/** El porqué de Android en palabras claras; lo que dijo tal cual va solo al reporte técnico ([AppUpdates.describe]). */
private fun failureText(f: InstallFailure) = when (f.status) {
    PackageInstaller.STATUS_FAILURE_ABORTED -> R.string.install_aborted
    PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> R.string.install_incompatible
    PackageInstaller.STATUS_FAILURE_STORAGE -> R.string.install_storage
    PackageInstaller.STATUS_FAILURE_BLOCKED -> R.string.install_blocked
    else -> R.string.install_error
}

@Composable
private fun NewVersion(v: Version, onInstall: () -> Unit) {
    val c = LocalColors.current
    val shape = RoundedCornerShape(Radius.card)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.accent, shape).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Label(stringResource(R.string.new_version, v.name), Typography.subtitle)
        // Las notas del Release como lista corta (sin los títulos de sección).
        val (spanish, english) = remember(v.notes) { splitReleaseNotes(v.notes) }
        val notes = releaseNotes(localReleaseNotes(spanish, english)).filterNot { it.startsWith("###") }.take(6).joinToString("\n") { "• $it" }
        if (notes.isNotBlank()) Label(notes, Typography.small, c.textMuted, maxLines = 8)
        Button(stringResource(R.string.download_install, v.bytes / 1e6f), onInstall, primary = true)
    }
}
