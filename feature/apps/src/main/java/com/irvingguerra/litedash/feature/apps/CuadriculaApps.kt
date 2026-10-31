package com.irvingguerra.litedash.feature.apps

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Etiqueta
import com.irvingguerra.litedash.core.ui.LocalColores
import com.irvingguerra.litedash.core.ui.Texto
import java.text.Normalizer

private val LADO_ICONO = 56.dp

/** Lista de apps cargada una vez fuera del hilo principal. */
@Composable
fun rememberApps(repo: RepositorioApps): List<App>? {
    val apps by produceState<List<App>?>(null, repo) { value = repo.cargar() }
    return apps
}

/** Sin acentos ni mayúsculas: "musica" encuentra "Música". */
fun normalizar(texto: String) =
    Normalizer.normalize(texto, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CuadriculaApps(
    repo: RepositorioApps,
    apps: List<App>,
    busqueda: String,
    alTocar: (App) -> Unit,
    modifier: Modifier = Modifier,
    alPresionar: ((App) -> Unit)? = null,
    marcadas: Set<String> = emptySet(),
) {
    val filtro = remember(busqueda) { normalizar(busqueda.trim()) }
    val lista = remember(apps, filtro) { if (filtro.isEmpty()) apps else apps.filter { filtro in normalizar(it.nombre) } }
    val c = LocalColores.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(112.dp),
        modifier = modifier,
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(lista, key = { it.clave }) { app ->
            Column(
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (app.clave in marcadas) c.superficieAlta else c.fondo.copy(alpha = 0f))
                    .combinedClickable(onClick = { alTocar(app) }, onLongClick = alPresionar?.let { { it(app) } })
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                IconoApp(repo, app, LADO_ICONO)
                Spacer(Modifier.height(6.dp))
                Etiqueta(app.nombre, Texto.chico.copy(textAlign = TextAlign.Center), c.textoSuave, maxLineas = 1)
            }
        }
    }
}

@Composable
fun IconoApp(repo: RepositorioApps, app: App, lado: Dp, modifier: Modifier = Modifier) {
    val ladoPx = with(LocalDensity.current) { lado.roundToPx() }
    val icono by produceState<ImageBitmap?>(null, app, ladoPx) {
        value = repo.icono(app, ladoPx)?.asImageBitmap()
    }
    Box(modifier.size(lado)) {
        val bmp = icono
        if (bmp != null) {
            Image(bmp, contentDescription = app.nombre, Modifier.size(lado))
        } else {
            Box(Modifier.size(lado).clip(RoundedCornerShape(lado / 4)).background(LocalColores.current.superficieAlta))
        }
    }
}
