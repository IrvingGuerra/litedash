package com.irvingguerra.litedash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Etiqueta
import com.irvingguerra.litedash.core.ui.LocalColores
import com.irvingguerra.litedash.core.ui.MarcaMorse
import com.irvingguerra.litedash.core.ui.Texto
import com.irvingguerra.litedash.feature.apps.CuadriculaApps
import com.irvingguerra.litedash.feature.apps.RepositorioApps
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date

/** Inicio: panel del auto + cajón de apps. Nada se anima en el perfil ligero. */
@Composable
fun Inicio(repoApps: RepositorioApps) {
    val c = LocalColores.current
    BoxWithConstraints(Modifier.fillMaxSize().background(c.fondo).safeDrawingPadding()) {
        val ancho = maxWidth
        val alto = maxHeight
        if (ancho > alto) {
            Row(Modifier.fillMaxSize()) {
                PanelAuto(Modifier.fillMaxHeight().width(ancho / 3).padding(24.dp))
                CuadriculaApps(repoApps, Modifier.weight(1f).fillMaxHeight().padding(16.dp))
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                PanelAuto(Modifier.fillMaxWidth().height(alto / 3).padding(24.dp))
                CuadriculaApps(repoApps, Modifier.weight(1f).fillMaxWidth().padding(16.dp))
            }
        }
    }
}

@Composable
private fun PanelAuto(modifier: Modifier) {
    val c = LocalColores.current
    Column(modifier.clip(RoundedCornerShape(24.dp)).background(c.superficie).padding(24.dp)) {
        Reloj()
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth()) {
            MarcaMorse(Modifier.size(width = 138.dp, height = 36.dp))
        }
        Spacer(Modifier.height(12.dp))
        Etiqueta(stringResource(R.string.tu_auto), Texto.titulo)
    }
}

/** Hora y fecha: un tick por minuto, alineado al cambio de minuto (nada de polling). */
@Composable
private fun Reloj() {
    val idioma = LocalConfiguration.current.locales[0]
    val contexto = LocalContext.current
    // getTimeFormat respeta si el usuario eligió formato de 24 h.
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
    Etiqueta(hora.format(ahora), Texto.reloj)
    Etiqueta(fecha.format(ahora).replaceFirstChar { it.uppercase() }, Texto.normal, LocalColores.current.textoSuave)
}
