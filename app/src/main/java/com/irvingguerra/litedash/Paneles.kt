package com.irvingguerra.litedash

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
private fun CampoTexto(valor: String, alCambiar: (String) -> Unit, pista: String, modifier: Modifier = Modifier, icono: Boolean = false) {
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
    val contexto = LocalContext.current
    var busqueda by remember { mutableStateOf("") }
    val dock = d.prefs.dock ?: emptyList()
    PanelBase(stringResource(R.string.apps), cerrar) {
        CampoTexto(busqueda, { busqueda = it }, stringResource(R.string.buscar_app), Modifier.fillMaxWidth().widthIn(max = 640.dp), icono = true)
        Etiqueta(stringResource(R.string.cajon_ayuda), Texto.chico, LocalColores.current.textoSuave, modifier = Modifier.padding(top = 10.dp, start = 8.dp))
        CuadriculaApps(
            d.apps, apps, busqueda,
            alTocar = { d.apps.abrir(contexto, it); cerrar() },
            alPresionar = { app ->
                // Dejar presionado fija o quita la app del dock.
                val actual = d.prefs.dock ?: emptyList()
                d.prefs.dock = if (app.clave in actual) actual - app.clave else actual + app.clave
            },
            marcadas = dock.toSet(),
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
fun PanelAuto(prefs: Preferencias, cerrar: () -> Unit) {
    val c = LocalColores.current
    val color = ColoresAuto[prefs.colorAuto.coerceIn(ColoresAuto.indices)]
    PanelBase(stringResource(R.string.tu_auto), cerrar) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val horizontal = maxWidth > maxHeight
            val dibujo = @Composable { m: Modifier -> DibujoAuto(color, m) }
            val opciones = @Composable { m: Modifier ->
                Column(m, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Etiqueta(stringResource(R.string.nombre), Texto.etiqueta, c.textoSuave)
                    CampoTexto(prefs.nombreAuto ?: "", { prefs.nombreAuto = it.take(24).ifBlank { null } }, stringResource(R.string.mi_auto), Modifier.fillMaxWidth())
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
                    Etiqueta(stringResource(R.string.auto_pronto), Texto.chico, c.textoSuave)
                }
            }
            if (horizontal) {
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    dibujo(Modifier.weight(1.3f))
                    opciones(Modifier.weight(1f).verticalScroll(rememberScrollState()))
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    dibujo(Modifier.fillMaxWidth())
                    opciones(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
fun PanelAjustes(d: Dependencias, cerrar: () -> Unit, alEditar: () -> Unit) {
    val c = LocalColores.current
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
            Seccion(stringResource(R.string.acerca_de)) {
                Etiqueta("LiteDash ${BuildConfig.VERSION_NAME} · ${BuildConfig.CANAL}", Texto.normal, c.textoSuave)
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
