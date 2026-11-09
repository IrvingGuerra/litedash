package com.irvingguerra.litedash.feature.player

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.FileBrowser
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography

/**
 * "Elegir carpeta" de la música: el selector de Android si la pantalla lo trae; si no lo trae, o
 * no se abrió, el explorador propio ([FileBrowser], con el permiso de leer la música). Varias
 * pantallas de auto no traen "Archivos" (DocumentsUI) y el botón no hacía nada. [onChosen] avisa
 * cuando ya quedó guardada.
 */
@Composable
fun FolderPicker(text: String, onChosen: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false) {
    val context = LocalContext.current
    var browsing by remember { mutableStateOf(false) }
    // El selector de Android regresó sin carpeta: quizá ni se abrió (se ofrece el explorador propio).
    var missed by remember { mutableStateOf(false) }
    val tree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) missed = true
        else {
            Library.setFolder(context, uri)
            onChosen()
        }
    }
    var denied by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        denied = !ok
        if (ok) browsing = true
    }
    val browse = { if (Library.hasPermission(context)) browsing = true else ask.launch(Library.permission) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(text, {
            val opened = hasTreePicker(context) && runCatching { tree.launch(null) }.isSuccess
            if (!opened) browse()
        }, primary = primary)
        if (missed && !browsing) Button(stringResource(R.string.library_browse_memory), browse)
        if (denied) Label(stringResource(R.string.library_no_permission), Typography.small, LocalColors.current.danger)
        if (browsing) FileBrowser(
            pickFolder = true,
            accept = { false },
            onPick = { dir ->
                Library.setFolder(context, dir)
                browsing = false
                missed = false
                onChosen()
            },
            onCancel = { browsing = false },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** La pantalla trae el selector de carpetas de Android. */
private fun hasTreePicker(context: Context): Boolean =
    runCatching { Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).resolveActivity(context.packageManager) != null }.getOrDefault(false)
