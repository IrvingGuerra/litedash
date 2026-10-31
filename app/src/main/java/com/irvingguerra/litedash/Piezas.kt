package com.irvingguerra.litedash

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.irvingguerra.litedash.core.ui.Etiqueta
import com.irvingguerra.litedash.core.ui.Icono
import com.irvingguerra.litedash.core.ui.Iconos
import com.irvingguerra.litedash.core.ui.LocalColores
import com.irvingguerra.litedash.core.ui.Tarjeta
import com.irvingguerra.litedash.core.ui.Texto
import com.irvingguerra.litedash.feature.apps.App
import com.irvingguerra.litedash.feature.apps.IconoApp
import com.irvingguerra.litedash.feature.apps.RepositorioApps
import com.irvingguerra.litedash.feature.musica.ServicioMusica
import com.irvingguerra.litedash.feature.navegacion.RepositorioNavegacion
import com.irvingguerra.litedash.feature.tablero.Medidas
import com.irvingguerra.litedash.feature.tablero.Tipos
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date

/** Lo que la app sabe de cada tipo de pieza: nombre, tamaños y con qué tamaño se agrega. */
data class DefPieza(val tipo: String, val nombre: Int, val medidas: Medidas, val inicial: Pair<Int, Int>)

val DefPiezas = listOf(
    DefPieza(Tipos.AUTO, R.string.pieza_auto, Medidas(3, 2), 5 to 3),
    DefPieza(Tipos.MUSICA, R.string.pieza_musica, Medidas(3, 1, maxAlto = 3), 4 to 1),
    DefPieza(Tipos.NAVEGAR, R.string.pieza_navegar, Medidas(2, 1), 3 to 1),
    DefPieza(Tipos.RELOJ, R.string.pieza_reloj, Medidas(2, 1), 3 to 1),
    DefPieza(Tipos.TELEFONO, R.string.pieza_telefono, Medidas(1, 1), 2 to 1),
    DefPieza(Tipos.APP, R.string.pieza_app, Medidas(1, 1, 3, 3), 1 to 1),
)

fun medidasDe(tipo: String) = DefPiezas.firstOrNull { it.tipo == tipo }?.medidas ?: Medidas(1, 1)

// Apps de navegación en orden de preferencia (Waze, Google Maps, Maps.me, OsmAnd, HERE).
val PAQUETES_NAVEGACION = arrayOf(
    "com.waze", "com.google.android.apps.maps", "com.mapswithme.maps.pro", "net.osmand", "com.here.app.maps",
)

// Apps de música para el dock y para "Abrir app de música" (Spotify primero).
val PAQUETES_MUSICA = arrayOf(
    "com.spotify.music", "com.google.android.apps.youtube.music", "deezer.android.app",
    "com.apple.android.music", "com.amazon.mp3",
)

/** Hora y fecha que respetan el formato del usuario (12/24 h); un repintado por minuto. */
@Composable
fun rememberHora(): Pair<String, String> {
    val idioma = LocalConfiguration.current.locales[0]
    val contexto = LocalContext.current
    val hora = remember(idioma) { android.text.format.DateFormat.getTimeFormat(contexto) }
    val fecha = remember(idioma) {
        SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(idioma, "EEEEdMMMM"), idioma)
    }
    val ahora by produceState(Date()) {
        while (true) {
            delay(60_000 - System.currentTimeMillis() % 60_000)
            value = Date()
        }
    }
    return hora.format(ahora) to fecha.format(ahora).replaceFirstChar { it.uppercase() }
}

@Composable
fun PiezaReloj() {
    val c = LocalColores.current
    val (hora, fecha) = rememberHora()
    BoxWithConstraints(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.CenterStart) {
        // El reloj crece con la pieza: en 1 fila es discreto, en 2 o más es protagonista.
        val tam = (maxHeight.value * 0.5f).coerceIn(28f, 96f)
        Column {
            Etiqueta(hora, Texto.reloj.copy(fontSize = tam.sp), maxLineas = 1)
            Etiqueta(fecha, Texto.normal, c.textoSuave, maxLineas = 1)
        }
    }
}

/** Tarjeta de acceso grande (Navegar, Teléfono): ícono en círculo, título y detalle. */
@Composable
fun PiezaAcceso(icono: androidx.compose.ui.graphics.vector.ImageVector, titulo: String, detalle: String?, alTocar: () -> Unit) {
    val c = LocalColores.current
    Tarjeta(Modifier.fillMaxSize(), alTocar = alTocar) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(16.dp)) {
            val grande = maxHeight > 120.dp
            val ancho = maxWidth
            val contenido = @Composable {
                Box(Modifier.size(if (grande) 64.dp else 44.dp).clip(CircleShape).background(c.superficieAlta), contentAlignment = Alignment.Center) {
                    Icono(icono, c.acento, if (grande) 30.dp else 22.dp)
                }
            }
            if (grande) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                    contenido()
                    Column {
                        Etiqueta(titulo, Texto.titulo, maxLineas = 1)
                        if (detalle != null) Etiqueta(detalle, Texto.chico, c.textoSuave, maxLineas = 1)
                    }
                }
            } else {
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    contenido()
                    if (ancho > 120.dp) {
                        Column(Modifier.padding(start = 14.dp)) {
                            Etiqueta(titulo, Texto.subtitulo, maxLineas = 1)
                            if (detalle != null) Etiqueta(detalle, Texto.chico, c.textoSuave, maxLineas = 1)
                        }
                    }
                }
            }
        }
    }
}

/** Una app como pieza: ícono grande y nombre. Vacía, invita a elegir cuál. */
@Composable
fun PiezaApp(repo: RepositorioApps, app: App?, alTocar: () -> Unit) {
    val c = LocalColores.current
    Tarjeta(Modifier.fillMaxSize(), alTocar = alTocar) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.Center) {
            val lado = (minOf(maxWidth, maxHeight) * 0.55f).coerceAtMost(96.dp)
            val alto = maxHeight
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (app == null) {
                    Icono(Iconos.mas, c.textoSuave, lado * 0.6f)
                    Spacer(Modifier.height(6.dp))
                    Etiqueta(stringResource(R.string.elegir_app), Texto.chico, c.textoSuave, maxLineas = 1)
                } else {
                    IconoApp(repo, app, lado)
                    if (alto > 90.dp) {
                        Spacer(Modifier.height(8.dp))
                        Etiqueta(app.nombre, Texto.chico.copy(fontWeight = FontWeight.Medium), maxLineas = 1)
                    }
                }
            }
        }
    }
}

/**
 * Navegar. Sin ruta: acceso a la app de mapas. Navegando: la siguiente vuelta
 * que dice el aviso de Waze o Maps (flecha, distancia y calle), sin abrir el mapa.
 */
@Composable
fun PiezaNavegacion(repo: RepositorioNavegacion, app: App?, alTocar: () -> Unit) {
    DisposableEffect(repo) {
        repo.usar(ServicioMusica::activas)
        onDispose { repo.soltar() }
    }
    val ind = repo.indicacion
    if (ind == null) return PiezaAcceso(Iconos.navegar, stringResource(R.string.pieza_navegar), app?.nombre, alTocar)
    val c = LocalColores.current
    Tarjeta(Modifier.fillMaxSize(), alTocar = alTocar) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(14.dp), contentAlignment = Alignment.CenterStart) {
            val grande = maxHeight > 120.dp
            val lado = if (grande) 72.dp else 48.dp
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(lado).clip(RoundedCornerShape(16.dp)).background(c.acento), contentAlignment = Alignment.Center) {
                    val flecha = ind.flecha
                    if (flecha != null) {
                        Image(flecha.asImageBitmap(), null, Modifier.size(lado * 0.7f), colorFilter = ColorFilter.tint(c.fondo))
                    } else {
                        Icono(Iconos.navegar, c.fondo, lado * 0.5f)
                    }
                }
                Column(Modifier.padding(start = 14.dp)) {
                    if (ind.titulo.isNotBlank()) Etiqueta(ind.titulo, if (grande) Texto.titulo.copy(fontSize = 28.sp) else Texto.titulo, maxLineas = 1)
                    if (ind.texto.isNotBlank()) Etiqueta(ind.texto, Texto.normal, maxLineas = if (grande) 2 else 1)
                    if (grande && ind.detalle.isNotBlank()) Etiqueta(ind.detalle, Texto.chico, c.textoSuave, maxLineas = 1)
                }
            }
        }
    }
}

/** La app de música preferida (la primera instalada de [PAQUETES_MUSICA]). */
fun abrirMusica(contexto: Context, repo: RepositorioApps, apps: List<App>) {
    repo.primeraDe(apps, *PAQUETES_MUSICA)?.let { repo.abrir(contexto, it) }
}

fun abrirTelefono(contexto: Context) {
    val intento = Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { contexto.startActivity(intento) }
}

/** Abre la navegación: la app elegida, o la que haya instalada, o lo que atienda "geo:". */
fun abrirNavegacion(contexto: Context, repo: RepositorioApps, app: App?) {
    if (app != null) return repo.abrir(contexto, app)
    val intento = Intent(Intent.ACTION_VIEW, "geo:0,0?q=".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { contexto.startActivity(intento) }
}
