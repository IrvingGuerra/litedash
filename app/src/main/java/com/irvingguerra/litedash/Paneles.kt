package com.irvingguerra.litedash

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.net.toUri
import com.irvingguerra.litedash.feature.auto.EntradaCatalogo
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
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
import com.irvingguerra.litedash.feature.apps.App
import com.irvingguerra.litedash.feature.apps.CuadriculaApps
import com.irvingguerra.litedash.feature.auto.ColoresAuto
import com.irvingguerra.litedash.feature.auto.DibujoAuto
import com.irvingguerra.litedash.feature.tablero.Cuadricula
import com.irvingguerra.litedash.feature.tablero.EstadoTablero
import com.irvingguerra.litedash.feature.tablero.Plantilla
import com.irvingguerra.litedash.feature.tablero.Tipos
import com.irvingguerra.litedash.feature.tablero.piezas

/** Panel a pantalla completa sobre el inicio, con título y botón para cerrar. */
@Composable
private fun PanelBase(
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
    var busqueda by remember { mutableStateOf("") }
    PanelBase(stringResource(R.string.elegir_app), cerrar) {
        CampoTexto(busqueda, { busqueda = it }, stringResource(R.string.buscar_app), Modifier.fillMaxWidth().widthIn(max = 640.dp), icono = true)
        CuadriculaApps(
            d.apps, apps, busqueda,
            alTocar = { app ->
                if (idPieza != null) {
                    d.tablero.ponerDato(idPieza, app.clave)
                } else {
                    val def = DefPiezas.first { it.tipo == Tipos.APP }
                    d.tablero.agregar(def.tipo, def.inicial, def.medidas, app.clave)
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
    val color = ColoresAuto[prefs.colorAuto.coerceIn(ColoresAuto.indices)]
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
            val opciones = @Composable { m: Modifier ->
                Column(m, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Etiqueta(stringResource(R.string.nombre), Texto.etiqueta, c.textoSuave)
                    CampoTexto(prefs.nombreAuto ?: "", { prefs.nombreAuto = it.take(24).ifBlank { null } }, paquete?.nombre ?: stringResource(R.string.mi_auto), Modifier.fillMaxWidth())
                    Etiqueta(stringResource(R.string.color), Texto.etiqueta, c.textoSuave)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        ColoresAuto.forEachIndexed { i, col ->
                            val elegido = i == prefs.colorAuto
                            Box(
                                Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .border(3.dp, if (elegido) c.acento else c.linea, CircleShape)
                                    .padding(6.dp)
                                    .clip(CircleShape)
                                    .background(col)
                                    .clickable { prefs.colorAuto = i },
                            )
                        }
                    }
                    // Mientras no haya catálogo ni autos instalados, solo existe el genérico.
                    if (autos.hayCatalogo || autos.instalados().isNotEmpty()) {
                        Etiqueta(stringResource(R.string.modelo), Texto.etiqueta, c.textoSuave)
                        Modelos(prefs, autos, paquete)
                    } else {
                        Etiqueta(stringResource(R.string.auto_pronto), Texto.chico, c.textoSuave)
                    }
                }
            }
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

/** Genérico, los autos ya instalados y (en la versión de GitHub) los del catálogo. */
@Composable
private fun Modelos(prefs: Preferencias, autos: RepositorioAutos, actual: PaqueteAuto?) {
    val c = LocalColores.current
    val contexto = LocalContext.current
    val alcance = rememberCoroutineScope()
    val instalados = remember(autos.cambios) { autos.instalados() }
    var catalogo by remember { mutableStateOf<List<EntradaCatalogo>?>(null) }
    var error by remember { mutableStateOf(false) }
    var bajando by remember { mutableStateOf<Pair<String, Float>?>(null) }
    LaunchedEffect(autos.hayCatalogo) {
        if (autos.hayCatalogo) runCatching { autos.catalogo() }.onSuccess { catalogo = it }.onFailure { error = true }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OpcionModelo(stringResource(R.string.modelo_generico), null, elegido = actual == null) { prefs.modeloAuto = null }
        for (p in instalados) {
            OpcionModelo("${p.marca} ${p.nombre} ${p.anio}", null, elegido = actual?.id == p.id) { prefs.modeloAuto = p.id }
        }
        for (e in catalogo.orEmpty().filter { e -> instalados.none { it.id == e.id } }) {
            val progreso = bajando?.takeIf { it.first == e.id }?.second
            val detalle = if (progreso != null) "${(progreso * 100).toInt()} %" else stringResource(R.string.descargar_mb, e.bytes / 1e6f)
            OpcionModelo("${e.marca} ${e.nombre} ${e.anio}", detalle, elegido = false) {
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
private fun OpcionModelo(titulo: String, detalle: String?, elegido: Boolean, alTocar: () -> Unit) {
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
        Etiqueta(titulo, Texto.normal, maxLineas = 1, modifier = Modifier.weight(1f))
        if (detalle != null) Etiqueta(detalle, Texto.chico, c.acento, maxLineas = 1)
        if (elegido) Icono(Iconos.listo, c.acento, 22.dp)
    }
}

@Composable
fun PanelAjustes(d: Dependencias, cerrar: () -> Unit, alEditar: () -> Unit) {
    val c = LocalColores.current
    val contexto = LocalContext.current
    val prefs = d.prefs
    PanelBase(stringResource(R.string.ajustes), cerrar) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            Seccion(stringResource(R.string.tema)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for ((modo, texto) in listOf(ModoTema.OSCURO to R.string.tema_oscuro, ModoTema.CLARO to R.string.tema_claro, ModoTema.SISTEMA to R.string.tema_sistema)) {
                        Boton(stringResource(texto), { prefs.tema = modo }, principal = prefs.tema == modo)
                    }
                }
            }
            Seccion(stringResource(R.string.escala)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BotonIcono(Iconos.menos, { prefs.escala = (prefs.escala - 0.1f).coerceAtLeast(0.8f).redondear() }, fondo = c.superficieAlta)
                    Etiqueta("${(prefs.escala * 100).toInt()} %", Texto.titulo, modifier = Modifier.widthIn(min = 90.dp))
                    BotonIcono(Iconos.mas, { prefs.escala = (prefs.escala + 0.1f).coerceAtMost(1.5f).redondear() }, fondo = c.superficieAlta)
                }
                Etiqueta(stringResource(R.string.escala_ayuda), Texto.chico, c.textoSuave)
            }
            Seccion(stringResource(R.string.inicio)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Boton(stringResource(R.string.acomodar), alEditar, principal = true)
                }
                Etiqueta(stringResource(R.string.acomodar_ayuda), Texto.chico, c.textoSuave)
            }
            Seccion(stringResource(R.string.modulos)) {
                Etiqueta(stringResource(R.string.modulos_ayuda), Texto.chico, c.textoSuave)
                Modulos(d)
            }
            Seccion(stringResource(R.string.acerca_de)) {
                Etiqueta("LiteDash ${BuildConfig.VERSION_NAME} · ${BuildConfig.CANAL}", Texto.normal, c.textoSuave)
                var copiado by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Boton(stringResource(R.string.copiar_info), { copiarInfo(contexto); copiado = true })
                    if (copiado) Etiqueta(stringResource(R.string.copiado), Texto.chico, c.acento)
                }
                Etiqueta(stringResource(R.string.copiar_info_ayuda), Texto.chico, c.textoSuave)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun Float.redondear() = kotlin.math.round(this * 10) / 10f

@Composable
private fun Seccion(titulo: String, contenido: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Etiqueta(titulo.uppercase(), Texto.etiqueta, LocalColores.current.textoSuave)
        contenido()
    }
}

@Composable
fun PanelPlantillas(tablero: EstadoTablero, cerrar: () -> Unit) {
    val cuadricula = tablero.cuadricula ?: return
    PanelBase(stringResource(R.string.plantillas), cerrar) {
        Etiqueta(stringResource(R.string.plantillas_ayuda), Texto.normal, LocalColores.current.textoSuave)
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            for ((p, nombre) in listOf(Plantilla.CENTRO to R.string.plantilla_centro, Plantilla.LATERAL to R.string.plantilla_lateral, Plantilla.VERTICAL to R.string.plantilla_vertical)) {
                Tarjeta(Modifier.weight(1f), alTocar = { tablero.plantilla(p); cerrar() }) {
                    Column(Modifier.padding(16.dp)) {
                        Miniatura(p, cuadricula)
                        Spacer(Modifier.height(12.dp))
                        Etiqueta(stringResource(nombre), Texto.subtitulo, maxLineas = 1)
                    }
                }
            }
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
private fun Modulos(d: Dependencias) {
    val c = LocalColores.current
    val enInicio = d.tablero.piezas.any { it.tipo == Tipos.MUSICA }
    val permiso = remember(enInicio) { d.musica.tienePermiso() }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FilaModulo(Iconos.musica, stringResource(R.string.pieza_musica), stringResource(if (permiso) R.string.musica_con_permiso else R.string.musica_sin_permiso)) {
            if (enInicio) {
                Etiqueta(stringResource(R.string.en_tu_inicio), Texto.chico, c.acento)
            } else {
                Boton(stringResource(R.string.agregar), {
                    val def = DefPiezas.first { it.tipo == Tipos.MUSICA }
                    d.tablero.agregar(def.tipo, def.inicial, def.medidas)
                })
            }
        }
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
private fun FilaModulo(icono: androidx.compose.ui.graphics.vector.ImageVector, titulo: String, detalle: String, accion: @Composable () -> Unit) {
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
            Etiqueta(detalle, Texto.chico, c.textoSuave, maxLineas = 2)
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
