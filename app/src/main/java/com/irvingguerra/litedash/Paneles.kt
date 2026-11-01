package com.irvingguerra.litedash

import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.foundation.Image
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.net.toUri
import com.irvingguerra.litedash.feature.auto.EntradaCatalogo
import com.irvingguerra.litedash.feature.auto.Fluidez
import com.irvingguerra.litedash.feature.auto.GiroAuto
import com.irvingguerra.litedash.feature.auto.PaqueteAuto
import com.irvingguerra.litedash.feature.auto.RepositorioAutos
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Boton
import com.irvingguerra.litedash.core.ui.BotonIcono
import com.irvingguerra.litedash.core.ui.Etiqueta
import com.irvingguerra.litedash.core.ui.Icono
import com.irvingguerra.litedash.core.ui.Iconos
import com.irvingguerra.litedash.core.ui.LocalColores
import com.irvingguerra.litedash.core.ui.Tarjeta
import com.irvingguerra.litedash.core.ui.Texto
import com.irvingguerra.litedash.feature.actualizar.Actualizador
import com.irvingguerra.litedash.feature.actualizar.Version
import com.irvingguerra.litedash.feature.apps.App
import com.irvingguerra.litedash.feature.apps.CuadriculaApps
import com.irvingguerra.litedash.feature.apps.normalizar
import com.irvingguerra.litedash.feature.auto.ColoresAuto
import com.irvingguerra.litedash.feature.auto.DibujoAuto
import com.irvingguerra.litedash.feature.tablero.Cuadricula
import com.irvingguerra.litedash.feature.tablero.EstadoTablero
import com.irvingguerra.litedash.feature.tablero.Plantilla
import com.irvingguerra.litedash.feature.tablero.TamanoPantalla
import com.irvingguerra.litedash.feature.tablero.plantillaSugerida
import com.irvingguerra.litedash.feature.tablero.tamano
import com.irvingguerra.litedash.feature.tablero.Tipos
import com.irvingguerra.litedash.feature.tablero.piezas

/** Panel a pantalla completa sobre el inicio, con título y botón para cerrar. */
@Composable
internal fun PanelBase(
    titulo: String,
    cerrar: () -> Unit,
    acciones: @Composable () -> Unit = {},
    contenido: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalColores.current
    Column(
        Modifier
            .fillMaxSize()
            .background(c.fondo)
            // Atrapa los toques para que no lleguen al inicio de abajo.
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .safeDrawingPadding()
            .padding(horizontal = 24.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.CenterVertically) {
            Etiqueta(titulo, Texto.titulo, maxLineas = 1, modifier = Modifier.weight(1f))
            acciones()
            BotonIcono(Iconos.cerrar, cerrar, fondo = c.superficieAlta)
        }
        contenido()
    }
}

@Composable
fun CampoTexto(valor: String, alCambiar: (String) -> Unit, pista: String, modifier: Modifier = Modifier, icono: Boolean = false) {
    val c = LocalColores.current
    Row(
        modifier.height(56.dp).clip(RoundedCornerShape(28.dp)).background(c.superficie).border(1.dp, c.linea, RoundedCornerShape(28.dp)).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icono) {
            Icono(Iconos.buscar, c.textoSuave, 22.dp)
            Spacer(Modifier.width(12.dp))
        }
        Box(Modifier.weight(1f)) {
            if (valor.isEmpty()) Etiqueta(pista, Texto.normal, c.textoSuave, maxLineas = 1)
            BasicTextField(
                valor,
                alCambiar,
                Modifier.fillMaxWidth(),
                textStyle = Texto.normal.copy(color = c.texto),
                singleLine = true,
                cursorBrush = SolidColor(c.acento),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
        }
    }
}

@Composable
fun PanelCajon(d: Dependencias, apps: List<App>, cerrar: () -> Unit) {
    val c = LocalColores.current
    val contexto = LocalContext.current
    var busqueda by remember { mutableStateOf("") }
    var elegida by remember { mutableStateOf<App?>(null) }
    var verOcultas by remember { mutableStateOf(false) }
    val dock = d.prefs.dock ?: emptyList()
    val ocultas = d.prefs.ocultas
    val visibles = remember(apps, ocultas, verOcultas) { if (verOcultas) apps else apps.filter { it.clave !in ocultas } }
    PanelBase(stringResource(R.string.apps), cerrar) {
        CampoTexto(busqueda, { busqueda = it }, stringResource(R.string.buscar_app), Modifier.fillMaxWidth().widthIn(max = 640.dp), icono = true)
        val app = elegida
        if (app == null) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp, start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Etiqueta(stringResource(R.string.cajon_ayuda), Texto.chico, c.textoSuave, modifier = Modifier.weight(1f))
                if (ocultas.isNotEmpty()) {
                    Etiqueta(
                        stringResource(if (verOcultas) R.string.esconder_ocultas else R.string.ver_ocultas, ocultas.size),
                        Texto.chico, c.acento,
                        modifier = Modifier.clickable { verOcultas = !verOcultas }.padding(8.dp),
                    )
                }
            }
        } else {
            // Acciones de la app que se dejó presionada.
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Etiqueta(app.nombre, Texto.subtitulo, maxLineas = 1, modifier = Modifier.padding(top = 12.dp, end = 8.dp))
                val enDock = app.clave in dock
                Boton(stringResource(if (enDock) R.string.quitar_del_dock else R.string.fijar_en_dock), {
                    d.prefs.dock = if (enDock) dock - app.clave else dock + app.clave
                    elegida = null
                })
                Boton(stringResource(R.string.poner_en_inicio), {
                    val def = DefPiezas.first { it.tipo == Tipos.APP }
                    d.tablero.agregar(def.tipo, def.inicial, def.medidas, app.clave)
                    elegida = null
                })
                val oculta = app.clave in ocultas
                Boton(stringResource(if (oculta) R.string.mostrar_app else R.string.ocultar_app), {
                    d.prefs.ocultas = if (oculta) ocultas - app.clave else ocultas + app.clave
                    elegida = null
                })
                Boton(stringResource(R.string.info_app), {
                    val intento = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${app.paquete}".toUri())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { contexto.startActivity(intento) }
                    elegida = null
                })
                BotonIcono(Iconos.cerrar, { elegida = null }, tam = 48.dp, fondo = c.superficieAlta)
            }
        }
        CuadriculaApps(
            d.apps, visibles, busqueda,
            alTocar = { d.apps.abrir(contexto, it); cerrar() },
            alPresionar = { elegida = it },
            marcadas = setOfNotNull(elegida?.clave),
            modifier = Modifier.weight(1f).padding(top = 8.dp),
        )
    }
}

@Composable
fun PanelElegirApp(d: Dependencias, apps: List<App>, idPieza: String?, cerrar: () -> Unit) {
    val tablero = d.tablero
    var busqueda by remember { mutableStateOf("") }
    PanelBase(stringResource(R.string.elegir_app), cerrar) {
        CampoTexto(busqueda, { busqueda = it }, stringResource(R.string.buscar_app), Modifier.fillMaxWidth().widthIn(max = 640.dp), icono = true)
        CuadriculaApps(
            d.apps, apps, busqueda,
            alTocar = { app ->
                if (idPieza != null) {
                    tablero.ponerDato(idPieza, app.clave)
                } else {
                    val def = DefPiezas.first { it.tipo == Tipos.APP }
                    tablero.agregar(def.tipo, def.inicial, def.medidas, app.clave)
                }
                cerrar()
            },
            modifier = Modifier.weight(1f).padding(top = 8.dp),
        )
    }
}

@Composable
fun PanelAuto(prefs: Preferencias, autos: RepositorioAutos, cerrar: () -> Unit) {
    val c = LocalColores.current
    val color = prefs.colorAuto
    val paquete = remember(prefs.modeloAuto, autos.cambios) { autos.paquete(prefs.modeloAuto) }
    PanelBase(stringResource(R.string.tu_auto), cerrar) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val horizontal = maxWidth > maxHeight
            val vista = @Composable { m: Modifier ->
                Column(m, horizontalAlignment = Alignment.CenterHorizontally) {
                    if (paquete != null) {
                        GiroAuto(autos, paquete, color, Modifier.fillMaxWidth().aspectRatio(1.9f))
                        Etiqueta(stringResource(R.string.gira_tu_auto), Texto.chico, c.textoSuave)
                    } else {
                        DibujoAuto(color, Modifier.fillMaxWidth())
                    }
                }
            }
            val opciones = @Composable { m: Modifier -> OpcionesAuto(prefs, autos, paquete, m) }
            if (horizontal) {
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    vista(Modifier.weight(1.3f))
                    opciones(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 16.dp))
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    vista(Modifier.fillMaxWidth())
                    opciones(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** Nombre, color y modelo del auto: en "Tu auto" y en Ajustes → Tu auto. */
@Composable
private fun OpcionesAuto(prefs: Preferencias, autos: RepositorioAutos, paquete: PaqueteAuto?, modifier: Modifier = Modifier) {
    val c = LocalColores.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Etiqueta(stringResource(R.string.nombre), Texto.etiqueta, c.textoSuave)
        CampoTexto(prefs.nombreAuto ?: "", { prefs.nombreAuto = it.take(24).ifBlank { null } }, paquete?.nombre ?: stringResource(R.string.mi_auto), Modifier.fillMaxWidth())
        Etiqueta(stringResource(R.string.color), Texto.etiqueta, c.textoSuave)
        SelectorColor(prefs.colorAuto) { prefs.colorAuto = it }
        // Mientras no haya catálogo ni autos instalados, solo existe el genérico.
        if (autos.hayCatalogo || autos.instalados().isNotEmpty()) {
            Etiqueta(stringResource(R.string.modelo), Texto.etiqueta, c.textoSuave)
            Modelos(prefs, autos, paquete)
        } else {
            Etiqueta(stringResource(R.string.auto_pronto), Texto.chico, c.textoSuave)
        }
    }
}

/** Ajustes → Tu auto: el auto como se ve en el inicio y sus opciones. */
@Composable
private fun AjustesAuto(d: Dependencias) {
    val prefs = d.prefs
    val paquete = remember(prefs.modeloAuto, d.autos.cambios) { d.autos.paquete(prefs.modeloAuto) }
    TarjetaAjuste(Iconos.auto) {
        Box(Modifier.fillMaxWidth().widthIn(max = 520.dp).aspectRatio(2.4f)) {
            if (paquete != null) {
                GiroAuto(d.autos, paquete, prefs.colorAuto, Modifier.fillMaxSize())
            } else {
                DibujoAuto(prefs.colorAuto, Modifier.fillMaxWidth().align(Alignment.Center))
            }
        }
        OpcionesAuto(prefs, d.autos, paquete, Modifier.fillMaxWidth())
    }
}

/** Genérico, los autos ya instalados y los del catálogo, con buscador. */
@Composable
private fun Modelos(prefs: Preferencias, autos: RepositorioAutos, actual: PaqueteAuto?) {
    val c = LocalColores.current
    val contexto = LocalContext.current
    val alcance = rememberCoroutineScope()
    val instalados = remember(autos.cambios) { autos.instalados() }
    var catalogo by remember { mutableStateOf<List<EntradaCatalogo>?>(null) }
    var error by remember { mutableStateOf(false) }
    var bajando by remember { mutableStateOf<Pair<String, Float>?>(null) }
    var busqueda by remember { mutableStateOf("") }
    LaunchedEffect(autos.hayCatalogo) {
        if (autos.hayCatalogo) runCatching { autos.catalogo() }.onSuccess { catalogo = it }.onFailure { error = true }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Busca por marca, modelo o año, sin importar acentos ni mayúsculas.
        CampoTexto(busqueda, { busqueda = it }, stringResource(R.string.buscar_auto), Modifier.fillMaxWidth(), icono = true)
        val q = normalizar(busqueda.trim())
        fun coincide(texto: String) = q.isEmpty() || q.split(' ').all { it in normalizar(texto) }
        OpcionModelo(stringResource(R.string.modelo_generico), null, elegido = actual == null) { prefs.modeloAuto = null }
        for (p in instalados.filter { coincide("${it.marca} ${it.nombre} ${it.anio}") }) {
            val deCatalogo = catalogo?.firstOrNull { it.id == p.id }
            OpcionModelo("${p.marca} ${p.nombre} ${p.anio}", null, elegido = actual?.id == p.id, miniatura = deCatalogo?.let { e -> { autos.miniatura(e) } }) { prefs.modeloAuto = p.id }
        }
        for (e in catalogo.orEmpty().filter { e -> instalados.none { it.id == e.id } && coincide("${e.marca} ${e.nombre} ${e.anio}") }) {
            val progreso = bajando?.takeIf { it.first == e.id }?.second
            val detalle = if (progreso != null) "${(progreso * 100).toInt()} %" else stringResource(R.string.descargar_mb, e.bytes / 1e6f)
            OpcionModelo("${e.marca} ${e.nombre} ${e.anio}", detalle, elegido = false, miniatura = { autos.miniatura(e) }) {
                if (bajando != null) return@OpcionModelo
                bajando = e.id to 0f
                alcance.launch {
                    runCatching { autos.instalar(e) { f -> bajando = e.id to f } }
                        .onSuccess { prefs.modeloAuto = e.id }
                        .onFailure { error = true }
                    bajando = null
                }
            }
        }
        if (error) Etiqueta(stringResource(R.string.catalogo_error), Texto.chico, c.peligro)
        // Fluidez del giro: cada nivel baja solo los cuadros que faltan.
        val entrada = actual?.let { a -> catalogo?.firstOrNull { it.id == a.id } }
        if (actual != null && actual.giro == 24 && entrada != null && entrada.extras.isNotEmpty()) {
            Etiqueta(stringResource(R.string.fluidez), Texto.etiqueta, c.textoSuave, modifier = Modifier.padding(top = 8.dp))
            val progreso = bajando?.takeIf { it.first == "fluidez" }?.second
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((nivel, texto) in listOf(Fluidez.LIGERA to R.string.fluidez_ligera, Fluidez.MEDIA to R.string.fluidez_media, Fluidez.MAXIMA to R.string.fluidez_maxima)) {
                    // Subir dice cuánto se baja; bajar, cuánto espacio se libera.
                    val sube = entrada.extras.filter { it.nivel in (actual.fluidez + 1)..nivel }.sumOf { it.bytes }
                    val libera = entrada.extras.filter { it.nivel in (nivel + 1)..actual.fluidez }.sumOf { it.bytes }
                    val etiqueta = when {
                        sube > 0 -> "${stringResource(texto)} · +${"%.1f".format(sube / 1e6f)} MB"
                        libera > 0 -> "${stringResource(texto)} · −${"%.1f".format(libera / 1e6f)} MB"
                        else -> stringResource(texto)
                    }
                    Boton(etiqueta, {
                        if (bajando != null || nivel == actual.fluidez) return@Boton
                        if (nivel < actual.fluidez) {
                            alcance.launch { autos.bajarFluidez(actual, nivel) }
                            return@Boton
                        }
                        bajando = "fluidez" to 0f
                        alcance.launch {
                            runCatching { autos.subirFluidez(actual, entrada, nivel) { f -> bajando = "fluidez" to f } }
                                .onFailure { error = true }
                            bajando = null
                        }
                    }, principal = nivel == actual.fluidez)
                }
            }
            if (progreso != null) Etiqueta("${(progreso * 100).toInt()} %", Texto.chico, c.acento)
            else Etiqueta(stringResource(R.string.fluidez_ayuda), Texto.chico, c.textoSuave)
        }
        // Eliminar lo descargado: pide confirmar con un segundo toque.
        if (actual != null) {
            var confirmar by remember(actual.id) { mutableStateOf(false) }
            val mb = remember(actual, autos.cambios) { autos.ocupa(actual) / 1e6f }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                if (!confirmar) {
                    Boton(stringResource(R.string.eliminar_auto, mb), { confirmar = true })
                } else {
                    Boton(stringResource(R.string.eliminar_si), {
                        prefs.modeloAuto = null
                        alcance.launch { autos.eliminar(actual) }
                    }, principal = true)
                    Boton(stringResource(R.string.cancelar), { confirmar = false })
                }
            }
        }
        // Crédito del modelo 3D (lo pide su licencia).
        if (actual != null && actual.autor.isNotBlank()) {
            Etiqueta(
                stringResource(R.string.credito_modelo, actual.autor, actual.licencia),
                Texto.chico,
                c.textoSuave,
                modifier = Modifier.clickable {
                    runCatching {
                        contexto.startActivity(Intent(Intent.ACTION_VIEW, actual.fuente.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                },
            )
        }
    }
}

@Composable
private fun OpcionModelo(titulo: String, detalle: String?, elegido: Boolean, miniatura: (suspend () -> ImageBitmap?)? = null, alTocar: () -> Unit) {
    val c = LocalColores.current
    val forma = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(forma)
            .background(c.superficie)
            .border(if (elegido) 2.dp else 1.dp, if (elegido) c.acento else c.linea, forma)
            .clickable(onClick = alTocar)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (miniatura != null) {
            // Vista previa: el auto antes de bajarlo (la imagen viene del catálogo).
            var img by remember(titulo) { mutableStateOf<ImageBitmap?>(null) }
            LaunchedEffect(titulo) { img = miniatura() }
            Box(Modifier.padding(end = 14.dp).size(width = 92.dp, height = 52.dp), contentAlignment = Alignment.Center) {
                img?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            }
        }
        Etiqueta(titulo, Texto.normal, maxLineas = 1, modifier = Modifier.weight(1f))
        if (detalle != null) Etiqueta(detalle, Texto.chico, c.acento, maxLineas = 1)
        if (elegido) Icono(Iconos.listo, c.acento, 22.dp)
    }
}

/** Secciones de Ajustes, en el menú de la izquierda. */
private enum class SeccionAjustes(val nombre: Int) { APARIENCIA(R.string.apariencia), AUTO(R.string.tu_auto), INICIO(R.string.inicio), MUSICA(R.string.musica), MAPA(R.string.mapa), MODULOS(R.string.modulos), ACERCA(R.string.acerca_de) }

private fun SeccionAjustes.icono() = when (this) {
    SeccionAjustes.APARIENCIA -> Iconos.tema
    SeccionAjustes.AUTO -> Iconos.auto
    SeccionAjustes.INICIO -> Iconos.tablero
    SeccionAjustes.MUSICA -> Iconos.musica
    SeccionAjustes.MAPA -> Iconos.navegar
    SeccionAjustes.MODULOS -> Iconos.apps
    SeccionAjustes.ACERCA -> Iconos.info
}

/**
 * Ajustes como en las pantallas de auto: menú de secciones a la izquierda y
 * tarjetas a la derecha. En pantallas angostas el menú queda solo con íconos.
 */
@Composable
fun PanelAjustes(d: Dependencias, cerrar: () -> Unit, alEditar: () -> Unit, alPlantillas: () -> Unit) {
    val c = LocalColores.current
    var seccion by rememberSaveable { mutableStateOf(SeccionAjustes.APARIENCIA) }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(c.fondo)
            // Atrapa los toques para que no lleguen al inicio de abajo.
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .safeDrawingPadding(),
    ) {
        val angosta = maxWidth < 720.dp
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier.width(if (angosta) 88.dp else 270.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                EntradaMenu(Iconos.atras, stringResource(R.string.volver_inicio), elegida = false, angosta, cerrar)
                Spacer(Modifier.height(10.dp))
                for (s in SeccionAjustes.entries) EntradaMenu(s.icono(), stringResource(s.nombre), s == seccion, angosta) { seccion = s }
            }
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 8.dp, end = 24.dp, top = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Etiqueta(stringResource(seccion.nombre), Texto.titulo.copy(fontSize = 30.sp), maxLineas = 1)
                when (seccion) {
                    SeccionAjustes.APARIENCIA -> Apariencia(d.prefs)
                    SeccionAjustes.AUTO -> AjustesAuto(d)
                    SeccionAjustes.INICIO -> AjustesInicio(d.prefs, alEditar, alPlantillas)
                    SeccionAjustes.MUSICA -> TarjetaAjuste(Iconos.musica) { AjustesMusica(d) }
                    SeccionAjustes.MAPA -> TarjetaAjuste(Iconos.navegar) { AjustesMapa(d) }
                    SeccionAjustes.MODULOS -> {
                        Etiqueta(stringResource(R.string.modulos_ayuda), Texto.normal, c.textoSuave)
                        Modulos(d, alEditar)
                    }
                    SeccionAjustes.ACERCA -> AcercaDe(d)
                }
            }
        }
    }
}

@Composable
private fun EntradaMenu(icono: androidx.compose.ui.graphics.vector.ImageVector, texto: String, elegida: Boolean, angosta: Boolean, alTocar: () -> Unit) {
    val c = LocalColores.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (elegida) c.superficieAlta else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = alTocar)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (angosta) Arrangement.Center else Arrangement.Start,
    ) {
        Icono(icono, if (elegida) c.acento else c.textoSuave, 26.dp)
        if (!angosta) Etiqueta(texto, Texto.subtitulo, if (elegida) c.texto else c.textoSuave, maxLineas = 1, modifier = Modifier.padding(start = 16.dp))
    }
}

/** Tarjeta de Ajustes: título, controles y el ícono de la sección grande y tenue a la derecha. */
@Composable
private fun TarjetaAjuste(
    icono: androidx.compose.ui.graphics.vector.ImageVector,
    titulo: String? = null,
    ayuda: String? = null,
    contenido: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalColores.current
    Tarjeta(Modifier.fillMaxWidth().widthIn(max = 860.dp)) {
        Icono(icono, c.acento.copy(alpha = 0.14f), 72.dp, Modifier.align(Alignment.TopEnd).padding(18.dp))
        Column(Modifier.padding(22.dp).padding(end = 64.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (titulo != null) Etiqueta(titulo, Texto.subtitulo, maxLineas = 1)
            contenido()
            if (ayuda != null) Etiqueta(ayuda, Texto.chico, c.textoSuave)
        }
    }
}

@Composable
private fun Apariencia(prefs: Preferencias) {
    val c = LocalColores.current
    val contexto = LocalContext.current
    TarjetaAjuste(Iconos.tema, stringResource(R.string.tema)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((modo, texto) in listOf(ModoTema.OSCURO to R.string.tema_oscuro, ModoTema.CLARO to R.string.tema_claro, ModoTema.SISTEMA to R.string.tema_sistema)) {
                Boton(stringResource(texto), { prefs.tema = modo }, principal = prefs.tema == modo)
            }
        }
    }
    TarjetaAjuste(Iconos.idioma, stringResource(R.string.idioma)) {
        val actividad = contexto as? android.app.Activity
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((codigo, texto) in listOf("" to R.string.idioma_sistema, "es" to R.string.idioma_es, "en" to R.string.idioma_en)) {
                Boton(stringResource(texto), {
                    if (prefs.idioma != codigo) {
                        prefs.idioma = codigo
                        actividad?.recreate()
                    }
                }, principal = prefs.idioma == codigo)
            }
        }
    }
    TarjetaAjuste(Iconos.ampliar, stringResource(R.string.escala), stringResource(R.string.escala_ayuda)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BotonIcono(Iconos.menos, { prefs.escala = (prefs.escala - 0.1f).coerceAtLeast(0.8f).redondear() }, fondo = c.superficieAlta)
            Etiqueta("${(prefs.escala * 100).toInt()} %", Texto.titulo, modifier = Modifier.widthIn(min = 90.dp))
            BotonIcono(Iconos.mas, { prefs.escala = (prefs.escala + 0.1f).coerceAtMost(1.5f).redondear() }, fondo = c.superficieAlta)
        }
    }
}

@Composable
private fun AjustesInicio(prefs: Preferencias, alEditar: () -> Unit, alPlantillas: () -> Unit) {
    val contexto = LocalContext.current
    TarjetaAjuste(Iconos.tablero, stringResource(R.string.acomodo), stringResource(R.string.acomodar_ayuda)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Boton(stringResource(R.string.acomodar), alEditar, principal = true)
            Boton(stringResource(R.string.plantillas), alPlantillas)
        }
    }
    val (pedirInicio, vueltas) = rememberPedirInicio()
    val esLaDeInicio = remember(vueltas) { esInicio(contexto) }
    TarjetaAjuste(Iconos.casa, stringResource(R.string.bienvenida_inicio_titulo)) {
        if (esLaDeInicio) {
            Etiqueta(stringResource(R.string.bienvenida_ya_es_inicio), Texto.normal, LocalColores.current.acento)
        } else {
            Boton(stringResource(R.string.hacer_inicio), { prefs.noPedirInicio = false; pedirInicio() }, principal = true)
        }
    }
}

@Composable
private fun AcercaDe(d: Dependencias) {
    val c = LocalColores.current
    val contexto = LocalContext.current
    TarjetaAjuste(Iconos.info, "LiteDash ${BuildConfig.VERSION_NAME}") {
        Etiqueta(BuildConfig.CANAL, Texto.chico, c.textoSuave)
        if (d.actualizador.disponible) Actualizaciones(d.actualizador)
    }
    TarjetaAjuste(Iconos.apps, stringResource(R.string.copiar_info), stringResource(R.string.copiar_info_ayuda)) {
        var copiado by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Boton(stringResource(R.string.copiar_info), { copiarInfo(contexto); copiado = true })
            if (copiado) Etiqueta(stringResource(R.string.copiado), Texto.chico, c.acento)
        }
    }
}

private fun Float.redondear() = kotlin.math.round(this * 10) / 10f

fun nombreDe(p: Plantilla) = when (p) {
    Plantilla.CENTRO -> R.string.plantilla_centro
    Plantilla.TARJETAS -> R.string.plantilla_tarjetas
    Plantilla.LATERAL -> R.string.plantilla_lateral
    Plantilla.COLUMNAS -> R.string.plantilla_columnas
    Plantilla.PANEL -> R.string.plantilla_panel
    Plantilla.VERTICAL -> R.string.plantilla_vertical
}

private fun nombreDe(t: TamanoPantalla) = when (t) {
    TamanoPantalla.CHICA -> R.string.pantallas_chicas
    TamanoPantalla.MEDIANA -> R.string.pantallas_medianas
    TamanoPantalla.GRANDE -> R.string.pantallas_grandes
    TamanoPantalla.VERTICAL -> R.string.pantallas_verticales
}

/** Acomodos agrupados por tamaño de pantalla; primero el de la tuya, con el recomendado marcado. */
@Composable
fun PanelPlantillas(tablero: EstadoTablero, cerrar: () -> Unit) {
    val cuadricula = tablero.cuadricula ?: return
    val c = LocalColores.current
    val tuya = cuadricula.tamano
    val sugerida = cuadricula.plantillaSugerida()
    PanelBase(stringResource(R.string.plantillas), cerrar) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Etiqueta(stringResource(R.string.plantillas_ayuda), Texto.normal, c.textoSuave)
            for (grupo in listOf(tuya) + TamanoPantalla.entries.filter { it != tuya }) {
                val plantillas = Plantilla.entries.filter { grupo in it.para }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Etiqueta(stringResource(nombreDe(grupo)).uppercase(), Texto.etiqueta, c.textoSuave)
                        if (grupo == tuya) Etiqueta(stringResource(R.string.tu_pantalla).uppercase(), Texto.etiqueta, c.acento)
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        for (p in plantillas) {
                            Tarjeta(Modifier.width(260.dp), alTocar = { tablero.plantilla(p); cerrar() }) {
                                Column(Modifier.padding(16.dp)) {
                                    Miniatura(p, cuadricula)
                                    Spacer(Modifier.height(12.dp))
                                    Etiqueta(stringResource(nombreDe(p)), Texto.subtitulo, maxLineas = 1)
                                    if (grupo == tuya && p == sugerida) {
                                        Etiqueta(stringResource(R.string.recomendado).uppercase(), Texto.etiqueta, c.acento, maxLineas = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** Dibujo de la plantilla en chiquito, con las proporciones de esta pantalla. */
@Composable
private fun Miniatura(p: Plantilla, cuadricula: Cuadricula) {
    val c = LocalColores.current
    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(cuadricula.columnas.toFloat() / cuadricula.filas).clip(RoundedCornerShape(12.dp)).background(c.fondo)) {
        val cw = maxWidth / cuadricula.columnas
        val ch = maxHeight / cuadricula.filas
        for (pieza in p.piezas(cuadricula)) {
            Box(
                Modifier
                    .offset(cw * pieza.col, ch * pieza.fila)
                    .size(cw * pieza.ancho, ch * pieza.alto)
                    .padding(2.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (pieza.tipo == Tipos.AUTO) c.acento.copy(alpha = 0.55f) else c.superficieAlta),
            )
        }
    }
}

@Composable
fun PanelAgregar(tablero: EstadoTablero, cerrar: () -> Unit, alElegirApp: () -> Unit) {
    val c = LocalColores.current
    var sinLugar by remember { mutableStateOf(false) }
    PanelBase(stringResource(R.string.agregar_pieza), cerrar) {
        if (sinLugar) {
            Etiqueta(stringResource(R.string.sin_lugar), Texto.normal, c.peligro, modifier = Modifier.padding(bottom = 12.dp))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            for (def in DefPiezas) {
                Tarjeta(Modifier.width(200.dp).height(120.dp), alTocar = {
                    if (def.tipo == Tipos.APP) alElegirApp()
                    else if (tablero.agregar(def.tipo, def.inicial, def.medidas)) cerrar()
                    else sinLugar = true
                }) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icono(iconoDe(def.tipo), c.acento, 28.dp)
                        Etiqueta(stringResource(def.nombre), Texto.subtitulo, maxLineas = 1)
                    }
                }
            }
        }
    }
}

private fun iconoDe(tipo: String) = when (tipo) {
    Tipos.AUTO -> Iconos.auto
    Tipos.MUSICA -> Iconos.musica
    Tipos.NAVEGAR -> Iconos.navegar
    Tipos.RELOJ -> Iconos.reloj
    Tipos.TELEFONO -> Iconos.telefono
    else -> Iconos.apps
}


/** Cada módulo: qué hace y si está en tu inicio. Los que vienen después se anuncian, apagados. */
@Composable
private fun Modulos(d: Dependencias, alEditar: () -> Unit) {
    val c = LocalColores.current
    val permiso = remember(d.tablero.piezas.any { it.tipo == Tipos.MUSICA }) { d.musica.tienePermiso() }
    // Tipo que no cupo al agregarlo: se avisa y se ofrece acomodar el inicio.
    var sinLugar by remember { mutableStateOf<String?>(null) }

    @Composable
    fun Fila(tipo: String, icono: androidx.compose.ui.graphics.vector.ImageVector, nombre: String, detalle: String) {
        val enInicio = d.tablero.piezas.any { it.tipo == tipo }
        val noCupo = sinLugar == tipo && !enInicio
        FilaModulo(icono, nombre, if (noCupo) stringResource(R.string.sin_lugar) else detalle, if (noCupo) c.peligro else c.textoSuave) {
            when {
                enInicio -> Etiqueta(stringResource(R.string.en_tu_inicio), Texto.chico, c.acento)
                noCupo -> Boton(stringResource(R.string.acomodar), alEditar, principal = true)
                else -> Boton(stringResource(R.string.agregar), {
                    val def = DefPiezas.first { it.tipo == tipo }
                    sinLugar = if (d.tablero.agregar(def.tipo, def.inicial, def.medidas)) null else tipo
                })
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Fila(Tipos.MUSICA, Iconos.musica, stringResource(R.string.pieza_musica), stringResource(if (permiso) R.string.musica_con_permiso else R.string.musica_sin_permiso))
        for ((icono, nombre, detalle) in listOf(
            Triple(Iconos.auto, R.string.modulo_obd, R.string.modulo_obd_detalle),
            Triple(Iconos.llanta, R.string.modulo_llantas, R.string.modulo_llantas_detalle),
            Triple(Iconos.navegar, R.string.modulo_viaje, R.string.modulo_viaje_detalle),
            Triple(Iconos.camara, R.string.modulo_camaras, R.string.modulo_camaras_detalle),
        )) {
            FilaModulo(icono, stringResource(nombre), stringResource(detalle)) {
                Etiqueta(stringResource(R.string.pronto), Texto.etiqueta, c.textoSuave)
            }
        }
    }
}

@Composable
private fun FilaModulo(
    icono: androidx.compose.ui.graphics.vector.ImageVector,
    titulo: String,
    detalle: String,
    colorDetalle: androidx.compose.ui.graphics.Color = LocalColores.current.textoSuave,
    accion: @Composable () -> Unit,
) {
    val c = LocalColores.current
    val forma = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().clip(forma).background(c.superficie).border(1.dp, c.linea, forma).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icono(icono, c.acento, 26.dp)
        Column(Modifier.weight(1f)) {
            Etiqueta(titulo, Texto.subtitulo, maxLineas = 1)
            Etiqueta(detalle, Texto.chico, colorDetalle, maxLineas = 2)
        }
        accion()
    }
}

/** Datos de la pantalla para reportar problemas (marca, modelo, Android, resolución, memoria). */
private fun copiarInfo(contexto: android.content.Context) {
    val m = contexto.resources.displayMetrics
    val memoria = android.app.ActivityManager.MemoryInfo().also {
        contexto.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it)
    }
    val texto = buildString {
        appendLine("LiteDash ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.CANAL}")
        appendLine("Equipo: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (${android.os.Build.DEVICE})")
        appendLine("Plataforma: ${android.os.Build.HARDWARE} · ${android.os.Build.BOARD}")
        appendLine("Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        appendLine("Pantalla: ${m.widthPixels}×${m.heightPixels} px · ${m.densityDpi} dpi")
        appendLine("Memoria: ${memoria.totalMem / (1024 * 1024)} MB")
        append("CPU: ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
    }
    contexto.getSystemService(android.content.ClipboardManager::class.java)
        .setPrimaryClip(android.content.ClipData.newPlainText("LiteDash", texto))
}


/** Estados del botón de actualizaciones (solo versión de GitHub). */
private sealed interface Busqueda {
    data object Nada : Busqueda
    data object Buscando : Busqueda
    data object AlDia : Busqueda
    data class Nueva(val v: Version) : Busqueda
    data class Bajando(val v: Version, val avance: Float) : Busqueda
    data class Permiso(val v: Version) : Busqueda
    data object Instalando : Busqueda
    data object Error : Busqueda
}

@Composable
private fun Actualizaciones(a: Actualizador) {
    val c = LocalColores.current
    val alcance = rememberCoroutineScope()
    var estado by remember { mutableStateOf<Busqueda>(Busqueda.Nada) }

    fun instalar(v: Version) {
        if (!a.puedeInstalar()) {
            estado = Busqueda.Permiso(v)
            a.pedirPermisoInstalar()
            return
        }
        estado = Busqueda.Bajando(v, 0f)
        alcance.launch {
            runCatching {
                val apk = a.descargar(v) { f -> estado = Busqueda.Bajando(v, f) }
                estado = Busqueda.Instalando
                a.instalar(apk)
            }.onFailure { estado = Busqueda.Error }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (val e = estado) {
            Busqueda.Nada, Busqueda.AlDia, Busqueda.Error -> {
                Boton(stringResource(R.string.buscar_actualizaciones), {
                    estado = Busqueda.Buscando
                    alcance.launch {
                        estado = runCatching { a.buscar() }
                            .fold({ v -> if (v == null) Busqueda.AlDia else Busqueda.Nueva(v) }, { Busqueda.Error })
                    }
                })
                if (e == Busqueda.AlDia) Etiqueta(stringResource(R.string.al_dia), Texto.chico, c.acento)
                if (e == Busqueda.Error) Etiqueta(stringResource(R.string.actualizar_error), Texto.chico, c.peligro)
            }
            Busqueda.Buscando -> Etiqueta(stringResource(R.string.buscando), Texto.normal, c.textoSuave)
            is Busqueda.Nueva -> NuevaVersion(e.v) { instalar(e.v) }
            is Busqueda.Bajando -> Etiqueta(stringResource(R.string.bajando, (e.avance * 100).toInt()), Texto.normal, c.acento)
            is Busqueda.Permiso -> {
                Etiqueta(stringResource(R.string.permiso_instalar), Texto.chico, c.textoSuave)
                Boton(stringResource(R.string.ya_lo_permiti), { instalar(e.v) }, principal = true)
            }
            Busqueda.Instalando -> Etiqueta(stringResource(R.string.instalando), Texto.normal, c.acento)
        }
        Actualizador.errorInstalacion?.let { Etiqueta(stringResource(R.string.instalar_error, it), Texto.chico, c.peligro) }
    }
}

@Composable
private fun NuevaVersion(v: Version, alInstalar: () -> Unit) {
    val c = LocalColores.current
    val forma = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().clip(forma).background(c.superficie).border(1.dp, c.acento, forma).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Etiqueta(stringResource(R.string.nueva_version, v.nombre), Texto.subtitulo)
        // Las notas del Release como lista corta: une los renglones partidos del
        // CHANGELOG y quita el formato de Markdown.
        val notas = buildList {
            for (r in v.notas.lines().map { it.trim() }) when {
                r.isBlank() || r.startsWith("#") -> Unit
                r.startsWith("- ") -> add("• " + r.removePrefix("- "))
                isNotEmpty() -> this[lastIndex] = this[lastIndex] + " " + r
                else -> add(r)
            }
        }.take(6).joinToString("\n").replace("**", "")
        if (notas.isNotBlank()) Etiqueta(notas, Texto.chico, c.textoSuave, maxLineas = 8)
        Boton(stringResource(R.string.descargar_instalar, v.bytes / 1e6f), alInstalar, principal = true)
    }
}
