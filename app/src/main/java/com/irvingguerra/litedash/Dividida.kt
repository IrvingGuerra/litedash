package com.irvingguerra.litedash

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.irvingguerra.litedash.core.ui.Boton
import com.irvingguerra.litedash.core.ui.BotonIcono
import com.irvingguerra.litedash.core.ui.Etiqueta
import com.irvingguerra.litedash.core.ui.Texto
import com.irvingguerra.litedash.core.ui.Iconos
import com.irvingguerra.litedash.core.ui.LiteDashTema
import com.irvingguerra.litedash.core.ui.LocalColores
import com.irvingguerra.litedash.feature.apps.App
import com.irvingguerra.litedash.feature.apps.rememberApps
import com.irvingguerra.litedash.feature.musica.MusicaAmpliada
import com.irvingguerra.litedash.feature.tablero.Cuadricula
import com.irvingguerra.litedash.feature.tablero.Tablero
import com.irvingguerra.litedash.feature.tablero.Tipos

/**
 * Cómo se ve el mapa junto a LiteDash:
 * - [OCULTA]: solo LiteDash (estás en el inicio) o solo el mapa, sin nada encima.
 * - [DIVIDIDA]: el mapa a un lado y una columna de LiteDash al otro.
 * - [MAPA]: el mapa completo con una pestañita para volver a dividir o al inicio.
 */
enum class ModoVista { OCULTA, DIVIDIDA, MAPA }

/**
 * Vista dividida. Android no deja meter otra app dentro de un launcher, así que
 * se hace al revés: el mapa (Waze, Maps…) se abre a pantalla completa y LiteDash
 * pone encima una columna con tus piezas ("mostrar sobre otras apps"). Tocar el
 * mapa lo deja completo; el botón de inicio regresa a tu tablero.
 */
class VistaDividida(private val app: Application, private val d: () -> Dependencias) {
    private val ventanas = app.getSystemService(WindowManager::class.java)
    private var columna: Ventana? = null
    private var pestana: Ventana? = null

    var modo by mutableStateOf(ModoVista.OCULTA)
        private set

    fun permitido() = Settings.canDrawOverlays(app)

    fun pedirPermiso() {
        val intento = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${app.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(intento) }
    }

    /** Abre el mapa y pone la columna de LiteDash a su lado. */
    fun abrir(mapa: App?) {
        abrirNavegacion(app, d().apps, mapa)
        mostrar(ModoVista.DIVIDIDA)
    }

    fun mostrar(nuevo: ModoVista) {
        if (nuevo != ModoVista.OCULTA && !permitido()) return
        modo = nuevo
        if (nuevo != ModoVista.DIVIDIDA) columna = quitar(columna)
        if (nuevo != ModoVista.MAPA) pestana = quitar(pestana)
        when (nuevo) {
            ModoVista.DIVIDIDA -> if (columna == null) columna = poner(parametrosColumna(), alTocarAfuera = { mostrar(ModoVista.MAPA) }) { Columna() }
            ModoVista.MAPA -> if (pestana == null) pestana = poner(parametrosPestana()) { Pestana() }
            ModoVista.OCULTA -> Unit
        }
    }

    /** Quita todo y regresa al tablero completo. */
    fun irAlInicio() {
        mostrar(ModoVista.OCULTA)
        val intento = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(intento) }
    }

    // El mapa del lado del conductor (izquierda por defecto) y LiteDash del otro.
    private fun gravedad() = if (d().prefs.mapaIzquierda) Gravity.END else Gravity.START

    private fun parametrosColumna(): WindowManager.LayoutParams {
        val ancho = anchoPantalla()
        val columna = if (d().prefs.mapaGrande) ancho / 3 else ancho / 2
        return parametros(columna, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
            .apply { gravity = gravedad() or Gravity.TOP }
    }

    private fun parametrosPestana() =
        parametros(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, 0)
            .apply { gravity = gravedad() or Gravity.CENTER_VERTICAL }

    // Sin foco: el teclado y "atrás" siguen siendo del mapa.
    private fun parametros(ancho: Int, alto: Int, extra: Int) = WindowManager.LayoutParams(
        ancho,
        alto,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or extra,
        PixelFormat.TRANSLUCENT,
    )

    private fun anchoPantalla(): Int =
        if (Build.VERSION.SDK_INT >= 30) {
            ventanas.maximumWindowMetrics.bounds.width()
        } else {
            val m = android.util.DisplayMetrics()
            @Suppress("DEPRECATION") ventanas.defaultDisplay.getRealMetrics(m)
            m.widthPixels
        }

    private fun poner(p: WindowManager.LayoutParams, alTocarAfuera: (() -> Unit)? = null, contenido: @Composable () -> Unit): Ventana? {
        val dueno = DuenoVentana()
        val marco = Marco(contextoConIdioma(app), alTocarAfuera)
        marco.setViewTreeLifecycleOwner(dueno)
        marco.setViewTreeSavedStateRegistryOwner(dueno)
        marco.addView(ComposeView(marco.context).apply { setContent { TemaVentana(contenido) } })
        dueno.iniciar()
        return runCatching { ventanas.addView(marco, p) }
            .map { Ventana(marco, dueno) }
            .getOrElse { dueno.terminar(); null }
    }

    private fun quitar(v: Ventana?): Ventana? {
        if (v != null) {
            runCatching { ventanas.removeView(v.vista) }
            v.dueno.terminar()
        }
        return null
    }

    @Composable
    private fun TemaVentana(contenido: @Composable () -> Unit) {
        val prefs = d().prefs
        val oscuro = when (prefs.tema) {
            ModoTema.OSCURO -> true
            ModoTema.CLARO -> false
            ModoTema.SISTEMA -> app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        LiteDashTema(oscuro = oscuro, escala = prefs.escala, contenido = contenido)
    }

    /** La columna de LiteDash junto al mapa: sus propias piezas (se acomodan igual que el inicio). */
    @Composable
    private fun Columna() {
        val d = d()
        val c = LocalColores.current
        val apps = rememberApps(d.apps).orEmpty()
        var editando by remember { mutableStateOf(false) }
        var panel by remember { mutableStateOf<Panel?>(null) }
        var musica by remember { mutableStateOf(false) }
        val acciones = AccionesPieza(
            alPanel = { p -> if (p is Panel.ElegirApp) panel = p else irAlInicio() },
            alNavegar = { mostrar(ModoVista.MAPA) },
            alMusica = { musica = true },
        )
        val izquierda = !d.prefs.mapaIzquierda
        Box(Modifier.fillMaxSize().background(c.fondo)) {
            // Línea del lado del mapa.
            Box(Modifier.align(if (izquierda) Alignment.CenterEnd else Alignment.CenterStart).width(1.dp).fillMaxHeight().background(c.linea))
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 6.dp)) {
                Row(
                    Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (editando) {
                        Boton(stringResource(R.string.agregar), { panel = Panel.Agregar })
                        Spacer(Modifier.weight(1f))
                        Boton(stringResource(R.string.listo), { editando = false }, principal = true)
                    } else {
                        // Sin pieza de reloj, la hora va en la barra (como en el inicio).
                        if (d.tableroLado.piezas.none { it.tipo == Tipos.RELOJ }) {
                            Etiqueta(rememberHora().first, Texto.subtitulo, maxLineas = 1, modifier = Modifier.padding(start = 8.dp))
                        }
                        Spacer(Modifier.weight(1f))
                        BotonIcono(Iconos.ampliar, { mostrar(ModoVista.MAPA) }, tam = 48.dp, fondo = c.superficieAlta)
                        BotonIcono(Iconos.casa, ::irAlInicio, tam = 48.dp, fondo = c.superficieAlta)
                        BotonIcono(Iconos.cerrar, { mostrar(ModoVista.OCULTA) }, tam = 48.dp, fondo = c.superficieAlta)
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth().padding(bottom = 6.dp)) {
                    if (musica) {
                        MusicaAmpliada(d.musica, cerrar = { musica = false }, alAbrirMusica = { abrirMusica(app, d.apps, apps) }, Modifier.padding(6.dp))
                    } else {
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            val cuadricula = remember(maxWidth, maxHeight) { Cuadricula.para(maxWidth.value, maxHeight.value) }
                            remember(cuadricula) { d.tableroLado.usar(cuadricula) }
                            Tablero(
                                cuadricula = cuadricula,
                                piezas = d.tableroLado.piezas,
                                editando = editando,
                                medidas = ::medidasDe,
                                alCambiar = d.tableroLado::cambiar,
                                alEditar = { editando = true },
                            ) { p -> ContenidoPieza(d, p, apps, 0, acciones) }
                        }
                    }
                }
            }
            when (val p = panel) {
                Panel.Agregar -> PanelAgregar(d.tableroLado, cerrar = { panel = null }, alElegirApp = { panel = Panel.ElegirApp(null) })
                is Panel.ElegirApp -> PanelElegirApp(d, apps, p.idPieza, cerrar = { panel = null }, tablero = d.tableroLado)
                else -> Unit
            }
        }
    }

    /** Con el mapa completo: una pestañita en la orilla para volver a dividir o ir al inicio. */
    @Composable
    private fun Pestana() {
        val c = LocalColores.current
        val esquinas = 20.dp
        val forma = if (d().prefs.mapaIzquierda) {
            RoundedCornerShape(topStart = esquinas, bottomStart = esquinas)
        } else {
            RoundedCornerShape(topEnd = esquinas, bottomEnd = esquinas)
        }
        Column(Modifier.clip(forma).background(c.superficie.copy(alpha = 0.94f)).padding(6.dp)) {
            BotonIcono(Iconos.dividir, { mostrar(ModoVista.DIVIDIDA) }, tam = 52.dp)
            BotonIcono(Iconos.casa, ::irAlInicio, tam = 52.dp)
        }
    }
}

private class Ventana(val vista: FrameLayout, val dueno: DuenoVentana)

/** Marco de la ventana: avisa cuando se toca fuera de ella (en el mapa). */
private class Marco(contexto: Context, private val alTocarAfuera: (() -> Unit)?) : FrameLayout(contexto) {
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            alTocarAfuera?.invoke()
            return true
        }
        return super.dispatchTouchEvent(e)
    }
}

/** Ciclo de vida mínimo para que Compose pinte en una ventana que no es de una actividad. */
private class DuenoVentana : SavedStateRegistryOwner {
    private val ciclo = LifecycleRegistry(this)
    private val estado = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = ciclo
    override val savedStateRegistry get() = estado.savedStateRegistry

    fun iniciar() {
        estado.performRestore(null)
        ciclo.currentState = Lifecycle.State.RESUMED
    }

    fun terminar() {
        ciclo.currentState = Lifecycle.State.DESTROYED
    }
}
