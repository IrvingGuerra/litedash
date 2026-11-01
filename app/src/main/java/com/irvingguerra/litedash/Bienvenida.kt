package com.irvingguerra.litedash

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.irvingguerra.litedash.core.ui.MarcaMorse
import com.irvingguerra.litedash.core.ui.Texto
import com.irvingguerra.litedash.feature.auto.ColoresAuto
import com.irvingguerra.litedash.feature.auto.DibujoAuto
import com.irvingguerra.litedash.feature.tablero.EstadoTablero
import com.irvingguerra.litedash.feature.tablero.Plantilla
import com.irvingguerra.litedash.feature.tablero.plantillaSugerida
import com.irvingguerra.litedash.feature.tablero.tamano

/** ¿LiteDash ya es la pantalla de inicio? */
fun esInicio(contexto: Context): Boolean {
    // Android 10+: el rol del sistema. Sin esto, desde Android 11 las otras apps de
    // inicio no se ven y LiteDash creería que es la única.
    if (Build.VERSION.SDK_INT >= 29) {
        runCatching { return contexto.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME) }
    }
    val intento = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    val quien = contexto.packageManager.resolveActivity(intento, PackageManager.MATCH_DEFAULT_ONLY)
    return quien?.activityInfo?.packageName == contexto.packageName
}

/**
 * Primer arranque: 1) tu auto (nombre y color), 2) cómo lo quieres (acomodo y
 * tema), 3) hacerlo tu pantalla de inicio. Todo se puede cambiar después.
 */
@Composable
fun Bienvenida(prefs: Preferencias, tablero: EstadoTablero, revision: Int, alTerminar: () -> Unit) {
    val c = LocalColores.current
    var paso by remember { mutableIntStateOf(prefs.pasoBienvenida.coerceIn(0, 2)) }
    fun ir(n: Int) {
        paso = n
        prefs.pasoBienvenida = n
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(c.fondo)
            .clickable(enabled = false) {}
            .safeDrawingPadding()
            .padding(horizontal = 32.dp, vertical = 20.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MarcaMorse(Modifier.size(width = 92.dp, height = 24.dp))
            Spacer(Modifier.weight(1f))
            // Tres puntos: dónde vas.
            for (i in 0..2) {
                Box(
                    Modifier
                        .padding(start = 8.dp)
                        .size(width = if (i == paso) 28.dp else 10.dp, height = 10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (i <= paso) c.acento else c.linea),
                )
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 24.dp)) {
            when (paso) {
                0 -> PasoAuto(prefs)
                1 -> PasoEstilo(prefs, tablero)
                else -> PasoInicio(revision)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (paso > 0) Boton(stringResource(R.string.atras), { ir(paso - 1) })
            Spacer(Modifier.weight(1f))
            if (paso < 2) {
                Boton(stringResource(R.string.omitir), alTerminar)
                Boton(stringResource(R.string.siguiente), { ir(paso + 1) }, principal = true)
            } else {
                Boton(stringResource(R.string.empezar), alTerminar, principal = true)
            }
        }
    }
}

@Composable
private fun Titulos(titulo: String, texto: String) {
    Etiqueta(titulo, Texto.reloj.copy(fontSize = Texto.titulo.fontSize * 1.6f))
    Spacer(Modifier.height(8.dp))
    Etiqueta(texto, Texto.normal, LocalColores.current.textoSuave, modifier = Modifier.widthIn(max = 640.dp))
    Spacer(Modifier.height(28.dp))
}

@Composable
private fun ColumnScope.PasoAuto(prefs: Preferencias) {
    val c = LocalColores.current
    Titulos(stringResource(R.string.bienvenida_titulo), stringResource(R.string.bienvenida_auto))
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val horizontal = maxWidth > 700.dp
        val dibujo = @Composable { m: Modifier ->
            DibujoAuto(prefs.colorAuto, m)
        }
        val opciones = @Composable { m: Modifier ->
            Column(m, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Etiqueta(stringResource(R.string.nombre), Texto.etiqueta, c.textoSuave)
                CampoTexto(prefs.nombreAuto ?: "", { prefs.nombreAuto = it.take(24).ifBlank { null } }, stringResource(R.string.mi_auto), Modifier.fillMaxWidth())
                Etiqueta(stringResource(R.string.color), Texto.etiqueta, c.textoSuave)
                SelectorColor(prefs.colorAuto) { prefs.colorAuto = it }
            }
        }
        if (horizontal) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                dibujo(Modifier.weight(1.2f))
                opciones(Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                dibujo(Modifier.fillMaxWidth())
                opciones(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun PasoEstilo(prefs: Preferencias, tablero: EstadoTablero) {
    val c = LocalColores.current
    Titulos(stringResource(R.string.bienvenida_estilo_titulo), stringResource(R.string.bienvenida_estilo))
    Etiqueta(stringResource(R.string.acomodo), Texto.etiqueta, c.textoSuave)
    Spacer(Modifier.height(12.dp))
    var elegida by rememberSaveable { mutableIntStateOf(-1) }
    // Los acomodos que lucen en esta pantalla, el recomendado primero.
    val opciones = tablero.cuadricula?.let { c -> listOf(c.plantillaSugerida()) + Plantilla.entries.filter { c.tamano in it.para } }
        ?.distinct().orEmpty()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for ((i, p) in opciones.withIndex()) {
            Boton(stringResource(nombreDe(p)), { elegida = i; tablero.plantilla(p) }, principal = elegida == i)
        }
    }
    Spacer(Modifier.height(28.dp))
    Etiqueta(stringResource(R.string.tema), Texto.etiqueta, c.textoSuave)
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for ((modo, texto) in listOf(ModoTema.OSCURO to R.string.tema_oscuro, ModoTema.CLARO to R.string.tema_claro, ModoTema.SISTEMA to R.string.tema_sistema)) {
            Boton(stringResource(texto), { prefs.tema = modo }, principal = prefs.tema == modo)
        }
    }
    Spacer(Modifier.height(20.dp))
    Etiqueta(stringResource(R.string.bienvenida_cambiar_despues), Texto.chico, c.textoSuave)
}

/**
 * Pide ser la pantalla de inicio: el diálogo del sistema (Android 10+) y, si la
 * pantalla no lo tiene, sus ajustes de inicio o de apps predeterminadas.
 * Devuelve la acción y un contador que sube al volver del diálogo.
 */
@Composable
fun rememberPedirInicio(): Pair<() -> Unit, Int> {
    val contexto = LocalContext.current
    var vueltas by remember { mutableIntStateOf(0) }
    val pedirRol = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vueltas++ }
    val pedir = {
        val pidio = Build.VERSION.SDK_INT >= 29 && runCatching {
            val rol = contexto.getSystemService(RoleManager::class.java)
            if (!rol.isRoleAvailable(RoleManager.ROLE_HOME)) return@runCatching false
            pedirRol.launch(rol.createRequestRoleIntent(RoleManager.ROLE_HOME))
            true
        }.getOrDefault(false)
        if (!pidio) {
            listOf(Settings.ACTION_HOME_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, Settings.ACTION_SETTINGS)
                .firstOrNull { accion ->
                    runCatching { contexto.startActivity(Intent(accion).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
                }
        }
        Unit
    }
    return pedir to vueltas
}

@Composable
private fun PasoInicio(revision: Int) {
    val c = LocalColores.current
    val contexto = LocalContext.current
    val (pedir, vueltas) = rememberPedirInicio()
    // Se vuelve a revisar al regresar de Ajustes (revision cambia en onResume).
    val yaEs = remember(revision, vueltas) { esInicio(contexto) }
    Titulos(stringResource(R.string.bienvenida_inicio_titulo), stringResource(R.string.bienvenida_inicio))
    if (yaEs) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(c.acento), contentAlignment = Alignment.Center) {
                Icono(Iconos.listo, c.fondo, 24.dp)
            }
            Spacer(Modifier.width(14.dp))
            Etiqueta(stringResource(R.string.bienvenida_ya_es_inicio), Texto.subtitulo)
        }
    } else {
        Boton(stringResource(R.string.hacer_inicio), pedir, principal = true)
        Spacer(Modifier.height(16.dp))
        Etiqueta(stringResource(R.string.bienvenida_inicio_ayuda), Texto.chico, c.textoSuave, modifier = Modifier.widthIn(max = 640.dp))
    }
}
