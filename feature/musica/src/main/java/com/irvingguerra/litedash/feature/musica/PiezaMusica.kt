package com.irvingguerra.litedash.feature.musica

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Boton
import com.irvingguerra.litedash.core.ui.BotonIcono
import com.irvingguerra.litedash.core.ui.Etiqueta
import com.irvingguerra.litedash.core.ui.Icono
import com.irvingguerra.litedash.core.ui.Iconos
import com.irvingguerra.litedash.core.ui.LocalColores
import com.irvingguerra.litedash.core.ui.Tarjeta
import com.irvingguerra.litedash.core.ui.Texto
import kotlinx.coroutines.delay

/**
 * Tarjeta de música. Se acomoda a su tamaño: chica es una fila con controles;
 * con más alto suma la carátula grande. Tocarla (fuera de los botones) la amplía
 * con [alAmpliar]. [revision] cambia al volver a la app, para notar si el
 * usuario acaba de dar el permiso.
 */
@Composable
fun PiezaMusica(repo: RepositorioMusica, revision: Int, alAmpliar: () -> Unit, modifier: Modifier = Modifier) {
    DisposableEffect(repo) {
        repo.usar()
        onDispose { repo.soltar() }
    }
    val permiso = remember(revision) { repo.tienePermiso().also { if (it) repo.revisar() } }
    Tarjeta(modifier.fillMaxSize(), alTocar = if (permiso) alAmpliar else null) {
        if (!permiso) SinPermiso(repo) else Reproductor(repo)
    }
}

@Composable
private fun SinPermiso(repo: RepositorioMusica) {
    val c = LocalColores.current
    Row(
        Modifier.fillMaxSize().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icono(Iconos.musica, c.acento, 32.dp)
        Column(Modifier.weight(1f)) {
            Etiqueta(stringResource(R.string.musica), Texto.subtitulo, maxLineas = 1)
            Etiqueta(stringResource(R.string.musica_explica), Texto.chico, c.textoSuave, maxLineas = 2)
        }
        Boton(stringResource(R.string.activar), { repo.pedirPermiso() }, principal = true)
    }
}

@Composable
private fun Reproductor(repo: RepositorioMusica) {
    val c = LocalColores.current
    val cancion = repo.cancion
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val alta = maxHeight > 150.dp
        // La carátula (cuadrada, del alto de la tarjeta) solo si deja lugar al título y los botones.
        val conCaratula = maxWidth - maxHeight >= 240.dp
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            // Carátula pegada a la orilla, del alto de la tarjeta.
            if (conCaratula) Box(
                Modifier.fillMaxHeight().aspectRatio(1f).background(c.superficieAlta),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = cancion?.caratula
                if (bmp != null) {
                    Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Icono(Iconos.musica, c.textoSuave, 28.dp)
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.Center) {
                if (cancion == null) {
                    Etiqueta(stringResource(R.string.nada_sonando), Texto.subtitulo, maxLineas = 1)
                    Etiqueta(stringResource(R.string.abre_una_app), Texto.chico, c.textoSuave, maxLineas = 1)
                } else {
                    Etiqueta(cancion.titulo, if (alta) Texto.titulo else Texto.subtitulo, maxLineas = 1)
                    Etiqueta(
                        listOf(cancion.artista, cancion.app).filter { it.isNotBlank() }.joinToString(" · "),
                        Texto.chico, c.textoSuave, maxLineas = 1,
                    )
                    if (cancion.duracionMs > 0) {
                        Spacer(Modifier.height(10.dp))
                        Progreso(cancion)
                    }
                }
                if (alta) {
                    Spacer(Modifier.height(12.dp))
                    Controles(repo, cancion?.sonando == true)
                }
            }
            if (!alta) Controles(repo, cancion?.sonando == true, Modifier.padding(end = 8.dp))
        }
    }
}

@Composable
private fun Controles(repo: RepositorioMusica, sonando: Boolean, modifier: Modifier = Modifier) {
    val c = LocalColores.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        BotonIcono(Iconos.anterior, { repo.anterior() })
        BotonIcono(if (sonando) Iconos.pausa else Iconos.play, { repo.alternar() }, fondo = c.texto, color = c.fondo)
        BotonIcono(Iconos.siguiente, { repo.siguiente() })
    }
}

/** Barra de avance: se calcula con la hora, un repintado por segundo solo mientras suena. */
@Composable
private fun Progreso(cancion: Cancion) {
    val c = LocalColores.current
    var pos by remember { mutableLongStateOf(cancion.posicionAhora()) }
    LaunchedEffect(cancion) {
        pos = cancion.posicionAhora()
        while (cancion.sonando) {
            delay(1000)
            pos = cancion.posicionAhora()
        }
    }
    val fraccion = (pos.toFloat() / cancion.duracionMs).coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.linea)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraccion).clip(RoundedCornerShape(2.dp)).background(c.texto))
    }
}
