package com.irvingguerra.litedash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Boton
import com.irvingguerra.litedash.core.ui.BotonIcono
import com.irvingguerra.litedash.core.ui.Etiqueta
import com.irvingguerra.litedash.core.ui.Iconos
import com.irvingguerra.litedash.core.ui.LocalColores
import com.irvingguerra.litedash.core.ui.Texto
import com.irvingguerra.litedash.feature.apps.App
import com.irvingguerra.litedash.feature.apps.IconoApp
import com.irvingguerra.litedash.feature.apps.RepositorioApps
import com.irvingguerra.litedash.feature.apps.rememberApps
import com.irvingguerra.litedash.feature.auto.ColoresAuto
import com.irvingguerra.litedash.feature.auto.PiezaAuto
import com.irvingguerra.litedash.feature.musica.PiezaMusica
import com.irvingguerra.litedash.feature.musica.RepositorioMusica
import com.irvingguerra.litedash.feature.tablero.Cuadricula
import com.irvingguerra.litedash.feature.tablero.EstadoTablero
import com.irvingguerra.litedash.feature.tablero.Tablero
import com.irvingguerra.litedash.feature.tablero.Tipos

/** Qué panel está abierto encima del inicio (uno a la vez). */
sealed interface Panel {
    data object Cajon : Panel
    data object Ajustes : Panel
    data object Auto : Panel
    data object Agregar : Panel
    data object Plantillas : Panel
    /** Elegir app para una pieza existente ([idPieza]) o para una nueva (null). */
    data class ElegirApp(val idPieza: String?) : Panel
}

class Dependencias(
    val apps: RepositorioApps,
    val musica: RepositorioMusica,
    val prefs: Preferencias,
    val tablero: EstadoTablero,
)

/** Inicio: barra de estado, tablero personalizable y dock. */
@Composable
fun Inicio(d: Dependencias, revision: Int) {
    val c = LocalColores.current
    val contexto = LocalContext.current
    val apps = rememberApps(d.apps).orEmpty()
    var panel by remember { mutableStateOf<Panel?>(null) }
    var editando by remember { mutableStateOf(false) }

    BackHandler(enabled = panel != null || editando) {
        if (panel != null) panel = null else editando = false
    }

    Box(Modifier.fillMaxSize().background(c.fondo)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            BarraSuperior(
                editando = editando,
                // Si ya hay un reloj en el tablero, la barra no repite la hora.
                conHora = d.tablero.piezas.none { it.tipo == Tipos.RELOJ },
                alAgregar = { panel = Panel.Agregar },
                alPlantillas = { panel = Panel.Plantillas },
                alTerminar = { editando = false },
            )
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp)) {
                val cuadricula = remember(maxWidth, maxHeight) { Cuadricula.para(maxWidth.value, maxHeight.value) }
                remember(cuadricula) { d.tablero.usar(cuadricula) }
                Tablero(
                    cuadricula = cuadricula,
                    piezas = d.tablero.piezas,
                    editando = editando,
                    medidas = ::medidasDe,
                    alCambiar = d.tablero::cambiar,
                    alEditar = { editando = true },
                ) { p ->
                    when (p.tipo) {
                        Tipos.AUTO -> PiezaAuto(
                            nombre = d.prefs.nombreAuto ?: stringResource(R.string.mi_auto),
                            color = ColoresAuto[d.prefs.colorAuto.coerceIn(ColoresAuto.indices)],
                            alTocar = { panel = Panel.Auto },
                        )
                        Tipos.MUSICA -> PiezaMusica(d.musica, revision)
                        Tipos.RELOJ -> PiezaReloj()
                        Tipos.NAVEGAR -> {
                            val app = d.apps.buscar(apps, p.dato) ?: d.apps.primeraDe(apps, *PAQUETES_NAVEGACION)
                            PiezaAcceso(Iconos.navegar, stringResource(R.string.pieza_navegar), app?.nombre) {
                                abrirNavegacion(contexto, d.apps, app)
                            }
                        }
                        Tipos.TELEFONO -> PiezaAcceso(Iconos.telefono, stringResource(R.string.pieza_telefono), null) {
                            abrirTelefono(contexto)
                        }
                        Tipos.APP -> {
                            val app = d.apps.buscar(apps, p.dato)
                            PiezaApp(d.apps, app) {
                                if (app != null) d.apps.abrir(contexto, app) else panel = Panel.ElegirApp(p.id)
                            }
                        }
                    }
                }
            }
            Dock(
                repo = d.apps,
                favoritas = favoritasDelDock(d, apps),
                alAuto = { panel = Panel.Auto },
                alApps = { panel = Panel.Cajon },
                alAjustes = { panel = Panel.Ajustes },
            )
        }

        when (val p = panel) {
            null -> Unit
            Panel.Cajon -> PanelCajon(d, apps, cerrar = { panel = null })
            Panel.Ajustes -> PanelAjustes(d, cerrar = { panel = null }, alEditar = { panel = null; editando = true })
            Panel.Auto -> PanelAuto(d.prefs, cerrar = { panel = null })
            Panel.Plantillas -> PanelPlantillas(d.tablero, cerrar = { panel = null })
            Panel.Agregar -> PanelAgregar(d.tablero, cerrar = { panel = null }, alElegirApp = { panel = Panel.ElegirApp(null) })
            is Panel.ElegirApp -> PanelElegirApp(d, apps, p.idPieza, cerrar = { panel = null })
        }
    }
}

/** Las del usuario, o unas sugeridas con lo que tenga instalado (navegación, música, mensajes). */
private fun favoritasDelDock(d: Dependencias, apps: List<App>): List<App> {
    d.prefs.dock?.let { claves -> return claves.mapNotNull { d.apps.buscar(apps, it) } }
    return listOfNotNull(
        d.apps.primeraDe(apps, *PAQUETES_NAVEGACION),
        d.apps.primeraDe(apps, "com.spotify.music", "com.google.android.apps.youtube.music", "deezer.android.app"),
        d.apps.primeraDe(apps, "com.google.android.youtube"),
        d.apps.primeraDe(apps, "com.whatsapp", "org.telegram.messenger"),
        d.apps.primeraDe(apps, "com.android.chrome"),
    )
}

@Composable
private fun BarraSuperior(editando: Boolean, conHora: Boolean, alAgregar: () -> Unit, alPlantillas: () -> Unit, alTerminar: () -> Unit) {
    val c = LocalColores.current
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (editando) {
            Etiqueta(stringResource(R.string.acomoda_tu_inicio), Texto.subtitulo, maxLineas = 1, modifier = Modifier.weight(1f))
            Boton(stringResource(R.string.plantillas), alPlantillas)
            Boton(stringResource(R.string.agregar), alAgregar)
            Boton(stringResource(R.string.listo), alTerminar, principal = true)
        } else if (conHora) {
            val (hora, fecha) = rememberHora()
            Etiqueta(fecha, Texto.normal, c.textoSuave, maxLineas = 1, modifier = Modifier.weight(1f))
            Etiqueta(hora, Texto.subtitulo, maxLineas = 1)
        }
    }
}

@Composable
private fun Dock(
    repo: RepositorioApps,
    favoritas: List<App>,
    alAuto: () -> Unit,
    alApps: () -> Unit,
    alAjustes: () -> Unit,
) {
    val c = LocalColores.current
    val contexto = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(84.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(c.superficie)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BotonIcono(Iconos.auto, alAuto)
        BotonIcono(Iconos.apps, alApps)
        Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(32.dp).background(c.linea))
        BoxWithConstraints(Modifier.weight(1f)) {
            // Caben las que quepan: en pantallas chicas el dock no se encima.
            val caben = (maxWidth / 64.dp).toInt()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                for (app in favoritas.take(caben)) {
                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).clickable { repo.abrir(contexto, app) }, contentAlignment = Alignment.Center) {
                        IconoApp(repo, app, 44.dp)
                    }
                }
            }
        }
        Volumen()
        BotonIcono(Iconos.ajustes, alAjustes)
    }
}

/** Volumen de la música: botones − / + y el nivel; escucha los cambios solo mientras se ve. */
@Composable
private fun Volumen() {
    val contexto = LocalContext.current
    val audio = remember { contexto.getSystemService(AudioManager::class.java) }
    var nivel by remember { mutableIntStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    DisposableEffect(audio) {
        val receptor = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                nivel = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            }
        }
        val filtro = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        if (Build.VERSION.SDK_INT >= 33) {
            contexto.registerReceiver(receptor, filtro, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            contexto.registerReceiver(receptor, filtro)
        }
        onDispose { contexto.unregisterReceiver(receptor) }
    }
    fun ajustar(direccion: Int) {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direccion, 0)
        nivel = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        BotonIcono(Iconos.menos, { ajustar(AudioManager.ADJUST_LOWER) })
        Row(Modifier.widthIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Etiqueta(nivel.toString(), Texto.subtitulo, maxLineas = 1)
        }
        BotonIcono(Iconos.mas, { ajustar(AudioManager.ADJUST_RAISE) })
        Spacer(Modifier.width(4.dp))
    }
}
