package com.irvingguerra.litedash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Boton
import com.irvingguerra.litedash.core.ui.Etiqueta
import com.irvingguerra.litedash.core.ui.LocalColores
import com.irvingguerra.litedash.core.ui.Texto
import com.irvingguerra.litedash.feature.apps.rememberApps

/** Sección "Mapa" de Ajustes: qué app de navegación abren las piezas Mapa y Navegar. */
@Composable
fun ColumnScope.AjustesMapa(d: Dependencias) {
    val apps = rememberApps(d.apps).orEmpty()
    val mapas = remember(apps) { PAQUETES_NAVEGACION.mapNotNull { paquete -> apps.firstOrNull { it.paquete == paquete } } }
    val elegida = appMapa(d, apps)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (app in mapas) Boton(app.nombre, { d.prefs.appMapa = app.clave }, principal = app == elegida)
    }
    Etiqueta(
        stringResource(if (mapas.isEmpty()) R.string.mapa_sin_apps else R.string.mapa_ayuda),
        Texto.chico,
        LocalColores.current.textoSuave,
    )
}
