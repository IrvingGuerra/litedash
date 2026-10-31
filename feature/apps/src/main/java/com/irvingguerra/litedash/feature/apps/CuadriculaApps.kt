package com.irvingguerra.litedash.feature.apps

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Etiqueta
import com.irvingguerra.litedash.core.ui.LocalColores
import com.irvingguerra.litedash.core.ui.Texto

private val LADO_ICONO = 56.dp

@Composable
fun CuadriculaApps(repo: RepositorioApps, modifier: Modifier = Modifier) {
    val apps by produceState<List<App>?>(null, repo) { value = repo.cargar() }
    val lista = apps ?: return
    val contexto = LocalContext.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(104.dp),
        modifier = modifier,
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(lista, key = { "${it.paquete}/${it.actividad}" }) { app ->
            Celda(app, repo) { repo.abrir(contexto, app) }
        }
    }
}

@Composable
private fun Celda(app: App, repo: RepositorioApps, alTocar: () -> Unit) {
    val ladoPx = with(LocalDensity.current) { LADO_ICONO.roundToPx() }
    val icono by produceState<ImageBitmap?>(null, app, ladoPx) {
        value = repo.icono(app, ladoPx)?.asImageBitmap()
    }
    Column(
        Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = alTocar).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(LADO_ICONO)) {
            val bmp = icono
            if (bmp != null) {
                Image(bmp, contentDescription = null, Modifier.size(LADO_ICONO))
            } else {
                Box(Modifier.size(LADO_ICONO).clip(RoundedCornerShape(14.dp)).background(LocalColores.current.superficie))
            }
        }
        Spacer(Modifier.height(6.dp))
        Etiqueta(app.nombre, Texto.chico.copy(textAlign = TextAlign.Center), LocalColores.current.textoSuave, maxLineas = 1)
    }
}
