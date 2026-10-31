package com.irvingguerra.litedash.feature.musica

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * La música en grande, sin salir de LiteDash: carátula, barra para adelantar,
 * los botones propios de la app (en Spotify: me gusta, aleatorio, repetir) y
 * con qué app se controla. Ancha pone la carátula a un lado; angosta, arriba.
 */
@Composable
fun MusicaAmpliada(repo: RepositorioMusica, cerrar: () -> Unit, alAbrirMusica: () -> Unit, modifier: Modifier = Modifier) {
    DisposableEffect(repo) {
        repo.usar()
        onDispose { repo.soltar() }
    }
    val c = LocalColores.current
    val cancion = repo.cancion
    Tarjeta(modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(20.dp)) {
            val ancha = maxWidth > 520.dp && maxWidth > maxHeight
            Column(Modifier.fillMaxSize()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Con más de una app sonando (Spotify y la radio), se elige cuál controlar.
                        if (repo.fuentes.size > 1) for (f in repo.fuentes) {
                            Chip(f.nombre, f.paquete == cancion?.paquete) { repo.elegirFuente(f.paquete) }
                        } else if (cancion != null) {
                            Etiqueta(cancion.app, Texto.etiqueta, c.textoSuave, maxLineas = 1, modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                    if (cancion != null) BotonIcono(Iconos.abrirFuera, { repo.abrirApp() }, tam = 48.dp, fondo = c.superficieAlta)
                    BotonIcono(Iconos.cerrar, cerrar, tam = 48.dp, fondo = c.superficieAlta)
                }
                Spacer(Modifier.height(16.dp))
                when {
                    cancion == null -> Vacio(repo, alAbrirMusica, Modifier.weight(1f).fillMaxWidth())
                    ancha -> Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Caratula(cancion, Modifier.fillMaxHeight().aspectRatio(1f))
                        Spacer(Modifier.width(32.dp))
                        Detalle(repo, cancion, Modifier.weight(1f))
                    }
                    else -> Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Caratula(cancion, Modifier.weight(1f, fill = false).aspectRatio(1f, matchHeightConstraintsFirst = true))
                        Spacer(Modifier.height(16.dp))
                        Detalle(repo, cancion, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(texto: String, elegido: Boolean, alTocar: () -> Unit) {
    val c = LocalColores.current
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (elegido) c.texto else c.superficieAlta)
            .clickable(onClick = alTocar)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) { Etiqueta(texto, Texto.chico.copy(fontWeight = FontWeight.SemiBold), if (elegido) c.fondo else c.texto, 1) }
}

@Composable
private fun Caratula(cancion: Cancion, modifier: Modifier) {
    val c = LocalColores.current
    Box(modifier.clip(RoundedCornerShape(20.dp)).background(c.superficieAlta), contentAlignment = Alignment.Center) {
        val bmp = cancion.caratula
        if (bmp != null) {
            Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icono(Iconos.musica, c.textoSuave, 56.dp)
        }
    }
}

@Composable
private fun Detalle(repo: RepositorioMusica, cancion: Cancion, modifier: Modifier) {
    val c = LocalColores.current
    Column(modifier) {
        Etiqueta(cancion.titulo, Texto.titulo.copy(fontSize = 26.sp), maxLineas = 2)
        Spacer(Modifier.height(4.dp))
        Etiqueta(cancion.artista, Texto.subtitulo, c.textoSuave, maxLineas = 1)
        if (cancion.duracionMs > 0) {
            Spacer(Modifier.height(20.dp))
            BarraTiempo(repo, cancion)
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            BotonIcono(Iconos.anterior, { repo.anterior() }, tam = 64.dp)
            Spacer(Modifier.width(12.dp))
            BotonIcono(if (cancion.sonando) Iconos.pausa else Iconos.play, { repo.alternar() }, tam = 76.dp, fondo = c.texto, color = c.fondo)
            Spacer(Modifier.width(12.dp))
            BotonIcono(Iconos.siguiente, { repo.siguiente() }, tam = 64.dp)
        }
        if (cancion.acciones.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                for (a in cancion.acciones.take(5)) Accion(a) { repo.accion(a) }
            }
        }
    }
}

/** Botón propio de la app con su ícono (se pinta del color del texto: suelen ser monocromos). */
@Composable
private fun Accion(a: AccionMedios, alTocar: () -> Unit) {
    val c = LocalColores.current
    Box(Modifier.size(56.dp).clip(CircleShape).clickable(onClick = alTocar), contentAlignment = Alignment.Center) {
        if (a.icono != null) {
            Image(a.icono.asImageBitmap(), a.nombre, Modifier.size(26.dp), colorFilter = ColorFilter.tint(c.textoSuave))
        } else {
            Etiqueta(a.nombre.take(2), Texto.chico, c.textoSuave, 1)
        }
    }
}

/** Barra de avance que se toca o arrastra para adelantar (si la app lo permite). */
@Composable
private fun BarraTiempo(repo: RepositorioMusica, cancion: Cancion) {
    val c = LocalColores.current
    var pos by remember { mutableLongStateOf(cancion.posicionAhora()) }
    var arrastre by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(cancion) {
        pos = cancion.posicionAhora()
        while (cancion.sonando) {
            delay(1000)
            pos = cancion.posicionAhora()
        }
    }
    val duracion = cancion.duracionMs
    val fraccion = arrastre ?: (pos.toFloat() / duracion).coerceIn(0f, 1f)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .then(
                if (cancion.adelantable) Modifier
                    .pointerInput(cancion) {
                        detectTapGestures { o -> repo.adelantar((o.x / size.width * duracion).toLong()) }
                    }
                    .pointerInput(cancion) {
                        detectHorizontalDragGestures(
                            onDragStart = { o -> arrastre = (o.x / size.width).coerceIn(0f, 1f) },
                            onHorizontalDrag = { cambio, d ->
                                cambio.consume()
                                arrastre = ((arrastre ?: 0f) + d / size.width).coerceIn(0f, 1f)
                            },
                            onDragEnd = {
                                arrastre?.let { repo.adelantar((it * duracion).toLong()) }
                                arrastre = null
                            },
                            onDragCancel = { arrastre = null },
                        )
                    }
                else Modifier,
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.linea)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraccion).background(c.texto))
        }
        if (cancion.adelantable) {
            Box(Modifier.offset(maxWidth * fraccion - 8.dp).size(16.dp).clip(CircleShape).background(c.texto))
        }
    }
    Row(Modifier.fillMaxWidth()) {
        Etiqueta(tiempo(if (arrastre != null) (fraccion * duracion).toLong() else pos), Texto.chico, c.textoSuave, 1)
        Spacer(Modifier.weight(1f))
        Etiqueta(tiempo(duracion), Texto.chico, c.textoSuave, 1)
    }
}

private fun tiempo(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun Vacio(repo: RepositorioMusica, alAbrirMusica: () -> Unit, modifier: Modifier) {
    val c = LocalColores.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icono(Iconos.musica, c.acento, 56.dp)
        Spacer(Modifier.height(16.dp))
        Etiqueta(stringResource(R.string.nada_sonando), Texto.titulo, maxLineas = 1)
        Spacer(Modifier.height(6.dp))
        Etiqueta(stringResource(R.string.reanudar_explica), Texto.normal, c.textoSuave, maxLineas = 2)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Boton(stringResource(R.string.reanudar), { repo.reanudar() }, principal = true)
            Boton(stringResource(R.string.abrir_app_musica), alAbrirMusica)
        }
    }
}
