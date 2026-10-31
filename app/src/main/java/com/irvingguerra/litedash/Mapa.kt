package com.irvingguerra.litedash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.irvingguerra.litedash.core.ui.Etiqueta
import com.irvingguerra.litedash.core.ui.Icono
import com.irvingguerra.litedash.core.ui.Iconos
import com.irvingguerra.litedash.core.ui.LocalColores
import com.irvingguerra.litedash.core.ui.Tarjeta
import com.irvingguerra.litedash.core.ui.Texto
import com.irvingguerra.litedash.feature.apps.App
import com.irvingguerra.litedash.feature.apps.rememberApps

/**
 * La primera vez que se toca "Navegar": mapa + LiteDash (vista dividida) o solo
 * el mapa. Si falta el permiso de mostrarse sobre otras apps, lo pide y al
 * volver abre el mapa solo.
 */
@Composable
fun PanelMapa(d: Dependencias, app: App?, revision: Int, cerrar: () -> Unit) {
    val c = LocalColores.current
    val contexto = LocalContext.current
    var esperando by remember { mutableStateOf(false) }
    LaunchedEffect(revision) {
        if (esperando && d.dividida.permitido()) {
            cerrar()
            d.dividida.abrir(app)
        }
    }
    PanelBase(stringResource(R.string.mapa_como), cerrar) {
        Etiqueta(stringResource(R.string.mapa_como_ayuda), Texto.normal, c.textoSuave)
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth().widthIn(max = 900.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Opcion(
                dividida = true,
                izquierda = d.prefs.mapaIzquierda,
                titulo = stringResource(R.string.mapa_dividido),
                detalle = stringResource(R.string.mapa_dividido_detalle),
                recomendada = true,
                modifier = Modifier.weight(1f),
            ) {
                d.prefs.modoMapa = ModoMapa.DIVIDIDO
                if (d.dividida.permitido()) {
                    cerrar()
                    d.dividida.abrir(app)
                } else {
                    esperando = true
                    d.dividida.pedirPermiso()
                }
            }
            Opcion(
                dividida = false,
                izquierda = d.prefs.mapaIzquierda,
                titulo = stringResource(R.string.mapa_completo),
                detalle = stringResource(R.string.mapa_completo_detalle, app?.nombre ?: stringResource(R.string.pieza_navegar)),
                recomendada = false,
                modifier = Modifier.weight(1f),
            ) {
                d.prefs.modoMapa = ModoMapa.COMPLETO
                cerrar()
                abrirNavegacion(contexto, d.apps, app)
            }
        }
        if (esperando) {
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Etiqueta(stringResource(R.string.mapa_permiso), Texto.normal, maxLineas = 2, modifier = Modifier.weight(1f, fill = false))
                Boton(stringResource(R.string.permitir), { d.dividida.pedirPermiso() }, principal = true)
            }
        }
    }
}

@Composable
private fun Opcion(
    dividida: Boolean,
    izquierda: Boolean,
    titulo: String,
    detalle: String,
    recomendada: Boolean,
    modifier: Modifier,
    alTocar: () -> Unit,
) {
    val c = LocalColores.current
    Tarjeta(modifier, alTocar = alTocar) {
        Column(Modifier.padding(16.dp)) {
            Miniatura(dividida, izquierda)
            Spacer(Modifier.height(14.dp))
            Etiqueta(titulo, Texto.subtitulo, maxLineas = 1)
            if (recomendada) Etiqueta(stringResource(R.string.recomendado).uppercase(), Texto.etiqueta, c.acento, maxLineas = 1)
            Spacer(Modifier.height(4.dp))
            Etiqueta(detalle, Texto.chico, c.textoSuave, maxLineas = 3)
        }
    }
}

/** Dibujo de cómo se verá: el mapa (con su flecha) y, si es dividida, la columna de LiteDash. */
@Composable
private fun Miniatura(dividida: Boolean, izquierda: Boolean) {
    val c = LocalColores.current
    Row(Modifier.fillMaxWidth().aspectRatio(16f / 8f).clip(RoundedCornerShape(12.dp)).background(c.fondo)) {
        val mapa = @Composable {
            Box(
                Modifier.weight(2f).fillMaxHeight().padding(3.dp).clip(RoundedCornerShape(8.dp)).background(c.acento.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
            ) { Icono(Iconos.navegar, c.acento, 28.dp) }
        }
        val columna = @Composable {
            Column(Modifier.weight(1f).fillMaxHeight().padding(3.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (peso in listOf(0.6f, 1.6f, 1f)) {
                    Box(Modifier.weight(peso).fillMaxSize().clip(RoundedCornerShape(6.dp)).background(c.superficieAlta))
                }
            }
        }
        when {
            !dividida -> Box(Modifier.weight(1f).fillMaxHeight().padding(3.dp).clip(RoundedCornerShape(8.dp)).background(c.acento.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                Icono(Iconos.navegar, c.acento, 28.dp)
            }
            izquierda -> { mapa(); columna() }
            else -> { columna(); mapa() }
        }
    }
}

/** Sección "Mapa" de Ajustes: qué app, cómo se abre y cómo se acomoda la vista dividida. */
@Composable
fun ColumnScope.AjustesMapa(d: Dependencias) {
    val c = LocalColores.current
    val prefs = d.prefs
    val apps = rememberApps(d.apps).orEmpty()
    val mapas = remember(apps) { PAQUETES_NAVEGACION.mapNotNull { paquete -> apps.firstOrNull { it.paquete == paquete } } }
    val elegida = appMapa(d, apps)
    if (mapas.size > 1) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (app in mapas) Boton(app.nombre, { prefs.appMapa = app.clave }, principal = app == elegida)
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Boton(stringResource(R.string.mapa_dividido), { prefs.modoMapa = ModoMapa.DIVIDIDO }, principal = prefs.modoMapa == ModoMapa.DIVIDIDO)
        Boton(stringResource(R.string.mapa_completo), { prefs.modoMapa = ModoMapa.COMPLETO }, principal = prefs.modoMapa == ModoMapa.COMPLETO)
    }
    if (prefs.modoMapa == ModoMapa.DIVIDIDO) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Boton(stringResource(R.string.mapa_izquierda), { prefs.mapaIzquierda = true }, principal = prefs.mapaIzquierda)
            Boton(stringResource(R.string.mapa_derecha), { prefs.mapaIzquierda = false }, principal = !prefs.mapaIzquierda)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Boton(stringResource(R.string.mapa_grande), { prefs.mapaGrande = true }, principal = prefs.mapaGrande)
            Boton(stringResource(R.string.mapa_mitad), { prefs.mapaGrande = false }, principal = !prefs.mapaGrande)
        }
        if (!d.dividida.permitido()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Etiqueta(stringResource(R.string.mapa_permiso), Texto.chico, c.peligro, maxLineas = 2, modifier = Modifier.weight(1f, fill = false))
                Boton(stringResource(R.string.permitir), { d.dividida.pedirPermiso() })
            }
        }
    }
    Etiqueta(stringResource(R.string.mapa_ayuda), Texto.chico, c.textoSuave)
}
