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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Boton
import com.irvingguerra.litedash.core.ui.BotonIcono
import com.irvingguerra.litedash.core.ui.Etiqueta
import com.irvingguerra.litedash.core.ui.Iconos
import com.irvingguerra.litedash.core.ui.LocalColores
import com.irvingguerra.litedash.core.ui.Texto
import com.irvingguerra.litedash.feature.actualizar.Actualizador
import com.irvingguerra.litedash.feature.apps.App
import com.irvingguerra.litedash.feature.apps.IconoApp
import com.irvingguerra.litedash.feature.apps.RepositorioApps
import com.irvingguerra.litedash.feature.apps.rememberApps
import com.irvingguerra.litedash.feature.auto.ColoresAuto
import com.irvingguerra.litedash.feature.auto.ImagenAuto
import com.irvingguerra.litedash.feature.auto.PaqueteAuto
import com.irvingguerra.litedash.feature.auto.RepositorioAutos
import com.irvingguerra.litedash.feature.auto.PiezaAuto
import com.irvingguerra.litedash.feature.musica.MusicaAmpliada
import com.irvingguerra.litedash.feature.musica.PiezaMusica
import com.irvingguerra.litedash.feature.navegacion.RepositorioNavegacion
import com.irvingguerra.litedash.feature.tablero.Pieza
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
    val navegacion: RepositorioNavegacion,
    val prefs: Preferencias,
    val tablero: EstadoTablero,
    val autos: RepositorioAutos,
    val actualizador: Actualizador,
)

/** Qué hace cada pieza al tocarla (abrir un panel, el mapa o la música en grande). */
class AccionesPieza(
    val alPanel: (Panel) -> Unit,
    val alNavegar: (App?) -> Unit,
    val alMusica: () -> Unit,
)

/** Cada tipo de pieza dibujado según su tipo. */
@Composable
fun ContenidoPieza(d: Dependencias, p: Pieza, apps: List<App>, revision: Int, acciones: AccionesPieza) {
    val contexto = LocalContext.current
    when (p.tipo) {
        Tipos.AUTO -> {
            val color = ColoresAuto[d.prefs.colorAuto.coerceIn(ColoresAuto.indices)]
            val paquete = remember(d.prefs.modeloAuto, d.autos.cambios) { d.autos.paquete(d.prefs.modeloAuto) }
            PiezaAuto(
                nombre = d.prefs.nombreAuto ?: paquete?.nombre ?: stringResource(R.string.mi_auto),
                color = color,
                alTocar = { acciones.alPanel(Panel.Auto) },
                dibujo = paquete?.let { pq -> { m -> ImagenAuto(d.autos, pq, vistaTablero(pq), color, m) } },
                conPiso = paquete?.let { vistaTablero(it) in it.piso } == true,
            )
        }
        Tipos.MUSICA -> PiezaMusica(d.musica, revision, alAmpliar = acciones.alMusica)
        Tipos.RELOJ -> PiezaReloj()
        Tipos.NAVEGAR -> {
            val app = appMapa(d, apps, p.dato)
            PiezaNavegacion(d.navegacion, app) { acciones.alNavegar(app) }
        }
        Tipos.TELEFONO -> PiezaAcceso(Iconos.telefono, stringResource(R.string.pieza_telefono), null) {
            abrirTelefono(contexto)
        }
        Tipos.APP -> {
            val app = d.apps.buscar(apps, p.dato)
            PiezaApp(d.apps, app) {
                if (app != null) d.apps.abrir(contexto, app) else acciones.alPanel(Panel.ElegirApp(p.id))
            }
        }
    }
}

/** La vista del auto en el tablero: la de escena (con piso) si el paquete la trae. */
fun vistaTablero(p: PaqueteAuto) = if ("escena" in p.vistas) "escena" else "tres_cuartos"

/** La de la pieza, si eligió una; si no, la de Ajustes; si no, la primera instalada. */
fun appMapa(d: Dependencias, apps: List<App>, dato: String = ""): App? =
    d.apps.buscar(apps, dato)
        ?: d.prefs.appMapa?.let { d.apps.buscar(apps, it) }
        ?: d.apps.primeraDe(apps, *PAQUETES_NAVEGACION)

/** Inicio: barra de estado, tablero personalizable y dock. */
@Composable
fun Inicio(d: Dependencias, revision: Int, inicioTocado: Int) {
    val c = LocalColores.current
    val contexto = LocalContext.current
    val apps = rememberApps(d.apps).orEmpty()
    var panel by remember { mutableStateOf<Panel?>(null) }
    var editando by remember { mutableStateOf(false) }
    var musica by remember { mutableStateOf(false) }

    BackHandler(enabled = (panel != null || editando || musica) && d.prefs.bienvenidaVista) {
        when {
            panel != null -> panel = null
            musica -> musica = false
            else -> editando = false
        }
    }
    // Botón de inicio estando ya aquí: de vuelta al tablero tal como lo acomodaste.
    LaunchedEffect(inicioTocado) {
        if (d.prefs.bienvenidaVista) {
            panel = null
            musica = false
            editando = false
        }
    }

    val acciones = AccionesPieza(
        alPanel = { panel = it },
        alNavegar = { app -> abrirNavegacion(contexto, d.apps, app) },
        alMusica = { musica = true },
    )

    // Con un auto que trae piso, el fondo baja un poco de tono hacia abajo: es el
    // piso donde se para el auto (como en Tesla), y su sombra y reflejo caen ahí.
    val conPiso = d.tablero.piezas.any { it.tipo == Tipos.AUTO } &&
        remember(d.prefs.modeloAuto, d.autos.cambios) { d.autos.paquete(d.prefs.modeloAuto) }?.let { vistaTablero(it) in it.piso } == true
    val fondo = if (conPiso) {
        Modifier.background(Brush.verticalGradient(0.3f to c.fondo, 1f to lerp(c.fondo, Color.Black, if (c.oscuro) 0.35f else 0.08f)))
    } else Modifier.background(c.fondo)
    Box(Modifier.fillMaxSize().then(fondo)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            BarraSuperior(
                editando = editando,
                // Si ya hay un reloj en el tablero, la barra no repite la hora.
                conHora = d.tablero.piezas.none { it.tipo == Tipos.RELOJ },
                alAgregar = { panel = Panel.Agregar },
                alPlantillas = { panel = Panel.Plantillas },
                alTerminar = { editando = false },
            )
            if (d.prefs.bienvenidaVista && !d.prefs.noPedirInicio && !editando) AvisoInicio(d.prefs, revision)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp)) {
                val cuadricula = remember(maxWidth, maxHeight) { Cuadricula.para(maxWidth.value, maxHeight.value) }
                remember(cuadricula) { d.tablero.usar(cuadricula) }
                if (musica) {
                    MusicaAmpliada(d.musica, cerrar = { musica = false }, Modifier.padding(6.dp))
                } else {
                    Tablero(
                        cuadricula = cuadricula,
                        piezas = d.tablero.piezas,
                        editando = editando,
                        medidas = ::medidasDe,
                        alCambiar = d.tablero::cambiar,
                        alEditar = { editando = true },
                    ) { p -> ContenidoPieza(d, p, apps, revision, acciones) }
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
            Panel.Ajustes -> PanelAjustes(d, cerrar = { panel = null }, alEditar = { panel = null; editando = true }, alPlantillas = { panel = Panel.Plantillas })
            Panel.Auto -> PanelAuto(d.prefs, d.autos, cerrar = { panel = null })
            Panel.Plantillas -> PanelPlantillas(d.tablero, cerrar = { panel = null })
            Panel.Agregar -> PanelAgregar(d.tablero, cerrar = { panel = null }, alElegirApp = { panel = Panel.ElegirApp(null) })
            is Panel.ElegirApp -> PanelElegirApp(d, apps, p.idPieza, cerrar = { panel = null })
        }

        if (!d.prefs.bienvenidaVista) {
            Bienvenida(d.prefs, d.tablero, revision, alTerminar = { d.prefs.bienvenidaVista = true })
        }
    }
}

/** Las del usuario, o unas sugeridas con lo que tenga instalado (navegación, música, mensajes). */
private fun favoritasDelDock(d: Dependencias, apps: List<App>): List<App> {
    d.prefs.dock?.let { claves -> return claves.mapNotNull { d.apps.buscar(apps, it) } }
    return listOfNotNull(
        d.apps.primeraDe(apps, *PAQUETES_NAVEGACION),
        // La app de música elegida en Ajustes, o la primera conocida que haya.
        d.prefs.appMusica?.let { d.apps.primeraDe(apps, it) } ?: d.apps.primeraDe(apps, *PAQUETES_MUSICA),
        d.apps.primeraDe(apps, "com.google.android.youtube"),
        d.apps.primeraDe(apps, "com.whatsapp", "org.telegram.messenger"),
        d.apps.primeraDe(apps, "com.android.chrome"),
    )
}

@Composable
private fun BarraSuperior(editando: Boolean, conHora: Boolean, alAgregar: () -> Unit, alPlantillas: () -> Unit, alTerminar: () -> Unit) {
    val c = LocalColores.current
    // Sin nada que mostrar (la hora ya está en una pieza), la barra no aparta espacio: el tablero sube.
    if (!editando && !conHora) return Spacer(Modifier.height(12.dp))
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
    // En pantallas chicas (7") todo se ve lejos: íconos más grandes, más fáciles de atinar manejando.
    val chica = pantallaChica()
    val lado = if (chica) 56.dp else 44.dp
    val caja = if (chica) 68.dp else 56.dp
    Row(
        Modifier
            .fillMaxWidth()
            .height(if (chica) 96.dp else 84.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(c.superficie)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val boton = if (chica) 64.dp else 56.dp
        BotonIcono(Iconos.auto, alAuto, tam = boton)
        BotonIcono(Iconos.apps, alApps, tam = boton)
        Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(32.dp).background(c.linea))
        BoxWithConstraints(Modifier.weight(1f)) {
            // Caben las que quepan: en pantallas chicas el dock no se encima.
            val caben = (maxWidth / (caja + 8.dp)).toInt()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                for (app in favoritas.take(caben)) {
                    Box(Modifier.size(caja).clip(RoundedCornerShape(16.dp)).clickable { repo.abrir(contexto, app) }, contentAlignment = Alignment.Center) {
                        IconoApp(repo, app, lado)
                    }
                }
            }
        }
        Volumen(boton)
        BotonIcono(Iconos.ajustes, alAjustes, tam = boton)
    }
}

/** Volumen de la música: botones − / + y el nivel; escucha los cambios solo mientras se ve. */
@Composable
private fun Volumen(boton: androidx.compose.ui.unit.Dp) {
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
        BotonIcono(Iconos.menos, { ajustar(AudioManager.ADJUST_LOWER) }, tam = boton)
        Row(Modifier.widthIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Etiqueta(nivel.toString(), Texto.subtitulo, maxLineas = 1)
        }
        BotonIcono(Iconos.mas, { ajustar(AudioManager.ADJUST_RAISE) }, tam = boton)
        Spacer(Modifier.width(4.dp))
    }
}


/** Aviso cuando LiteDash no es la pantalla de inicio: un toque para arreglarlo, o no volver a preguntar. */
@Composable
private fun AvisoInicio(prefs: Preferencias, revision: Int) {
    val c = LocalColores.current
    val contexto = LocalContext.current
    val (pedir, vueltas) = rememberPedirInicio()
    val esLaDeInicio = remember(revision, vueltas) { esInicio(contexto) }
    if (esLaDeInicio) return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(c.superficie)
            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Etiqueta(stringResource(R.string.aviso_no_es_inicio), Texto.normal, maxLineas = 2, modifier = Modifier.weight(1f))
        Boton(stringResource(R.string.hacer_inicio), pedir, principal = true)
        Boton(stringResource(R.string.no_preguntar), { prefs.noPedirInicio = true })
    }
}
