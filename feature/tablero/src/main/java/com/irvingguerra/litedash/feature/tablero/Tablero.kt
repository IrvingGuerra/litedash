package com.irvingguerra.litedash.feature.tablero

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.BotonIcono
import com.irvingguerra.litedash.core.ui.Icono
import com.irvingguerra.litedash.core.ui.Iconos
import com.irvingguerra.litedash.core.ui.LocalColores
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

private val SEPARACION = 6.dp

/** Arrastre en curso: mover la pieza o cambiarle el tamaño. */
private data class Arrastre(val id: String, val tamano: Boolean, val dx: Float = 0f, val dy: Float = 0f)

/**
 * El tablero del inicio. Fuera del modo edición solo pinta las piezas (nada se
 * recalcula ni se anima). Dejar presionado en cualquier parte entra al modo
 * edición; ahí cada pieza se arrastra, cambia de tamaño desde la esquina o se quita.
 */
@Composable
fun Tablero(
    cuadricula: Cuadricula,
    piezas: List<Pieza>,
    editando: Boolean,
    medidas: (String) -> Medidas,
    alCambiar: (List<Pieza>) -> Unit,
    alEditar: () -> Unit,
    modifier: Modifier = Modifier,
    contenido: @Composable (Pieza) -> Unit,
) {
    val c = LocalColores.current
    val alEditarActual by rememberUpdatedState(alEditar)
    BoxWithConstraints(modifier.fillMaxSize()) {
        val celdaAncho = maxWidth / cuadricula.columnas
        val celdaAlto = maxHeight / cuadricula.filas
        val densidad = LocalDensity.current
        val cwPx = with(densidad) { celdaAncho.toPx() }
        val chPx = with(densidad) { celdaAlto.toPx() }
        var arrastre by remember { mutableStateOf<Arrastre?>(null) }

        // Fondo: dejar presionado entra al modo edición; editando, se ven las celdas.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(editando) {
                    if (!editando) detectTapGestures(onLongPress = { alEditarActual() })
                }
                .then(
                    if (editando) Modifier.drawBehind {
                        val r = 2.dp.toPx()
                        for (i in 0..cuadricula.columnas) for (j in 0..cuadricula.filas) {
                            drawCircle(c.linea, r, Offset(i * cwPx, j * chPx))
                        }
                    } else Modifier,
                ),
        )

        // Vista previa de dónde caerá la pieza que se está arrastrando.
        val previa = arrastre?.let { a -> piezas.firstOrNull { it.id == a.id }?.let { destino(it, a, cwPx, chPx, medidas) } }
        if (previa != null) {
            val ok = piezas.cabe(previa, cuadricula)
            Box(
                Modifier
                    .offset(celdaAncho * previa.col, celdaAlto * previa.fila)
                    .size(celdaAncho * previa.ancho, celdaAlto * previa.alto)
                    .padding(SEPARACION)
                    .clip(RoundedCornerShape(20.dp))
                    .background((if (ok) c.acento else c.peligro).copy(alpha = 0.18f))
                    .border(2.dp, if (ok) c.acento else c.peligro, RoundedCornerShape(20.dp)),
            )
        }

        // El auto va primero: su sombra y reflejo pueden salirse de su pieza y deben
        // quedar debajo de las demás.
        for (p in piezas.sortedBy { if (it.tipo == Tipos.AUTO) 0 else 1 }) {
            val a = arrastre?.takeIf { it.id == p.id }
            val anchoDp: Dp
            val altoDp: Dp
            if (a != null && a.tamano) {
                // Mientras cambia de tamaño, la pieza sigue al dedo (acotada a su mínimo).
                val m = medidas(p.tipo)
                anchoDp = with(densidad) { (p.ancho * cwPx + a.dx).coerceAtLeast(m.minAncho * cwPx).toDp() }
                altoDp = with(densidad) { (p.alto * chPx + a.dy).coerceAtLeast(m.minAlto * chPx).toDp() }
            } else {
                anchoDp = celdaAncho * p.ancho
                altoDp = celdaAlto * p.alto
            }
            Box(
                Modifier
                    .offset(celdaAncho * p.col, celdaAlto * p.fila)
                    .size(anchoDp, altoDp)
                    .graphicsLayer {
                        if (a != null && !a.tamano) {
                            translationX = a.dx
                            translationY = a.dy
                        }
                    }
                    .padding(SEPARACION),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .then(if (editando) Modifier else Modifier.presionLarga { alEditarActual() }),
                ) { contenido(p) }

                if (editando) {
                    // Capa que atrapa los gestos: editando, la pieza no se usa, se acomoda.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(20.dp))
                            .border(2.dp, c.acento.copy(alpha = 0.7f), RoundedCornerShape(20.dp))
                            .pointerInput(p.id, piezas, cuadricula) {
                                detectDragGestures(
                                    onDragStart = { arrastre = Arrastre(p.id, tamano = false) },
                                    onDrag = { cambio, d ->
                                        cambio.consume()
                                        arrastre = arrastre?.let { it.copy(dx = it.dx + d.x, dy = it.dy + d.y) }
                                    },
                                    onDragEnd = {
                                        arrastre?.let { fin -> soltar(p, fin, cwPx, chPx, medidas, piezas, cuadricula, alCambiar) }
                                        arrastre = null
                                    },
                                    onDragCancel = { arrastre = null },
                                )
                            },
                    )
                    BotonIcono(
                        Iconos.cerrar,
                        alTocar = { alCambiar(piezas.filterNot { it.id == p.id }) },
                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                        tam = 40.dp,
                        fondo = c.superficieAlta,
                    )
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .pointerInput(p.id, piezas, cuadricula) {
                                detectDragGestures(
                                    onDragStart = { arrastre = Arrastre(p.id, tamano = true) },
                                    onDrag = { cambio, d ->
                                        cambio.consume()
                                        arrastre = arrastre?.let { it.copy(dx = it.dx + d.x, dy = it.dy + d.y) }
                                    },
                                    onDragEnd = {
                                        arrastre?.let { fin -> soltar(p, fin, cwPx, chPx, medidas, piezas, cuadricula, alCambiar) }
                                        arrastre = null
                                    },
                                    onDragCancel = { arrastre = null },
                                )
                            },
                    ) {
                        // Sin clickable: el gesto de esta esquina es solo arrastrar.
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(50)).background(c.acento), contentAlignment = Alignment.Center) {
                            Icono(Iconos.esquina, c.fondo, 22.dp)
                        }
                    }
                }
            }
        }
    }
}

private fun destino(p: Pieza, a: Arrastre, cwPx: Float, chPx: Float, medidas: (String) -> Medidas): Pieza =
    if (a.tamano) {
        val (ancho, alto) = medidas(p.tipo).acotar(
            ((p.ancho * cwPx + a.dx) / cwPx).roundToInt(),
            ((p.alto * chPx + a.dy) / chPx).roundToInt(),
        )
        p.copy(ancho = ancho, alto = alto)
    } else {
        p.copy(col = p.col + (a.dx / cwPx).roundToInt(), fila = p.fila + (a.dy / chPx).roundToInt())
    }

private fun soltar(
    p: Pieza,
    a: Arrastre,
    cwPx: Float,
    chPx: Float,
    medidas: (String) -> Medidas,
    piezas: List<Pieza>,
    c: Cuadricula,
    alCambiar: (List<Pieza>) -> Unit,
) {
    val nueva = destino(p, a, cwPx, chPx, medidas)
    if (nueva != p && piezas.cabe(nueva, c)) alCambiar(piezas.con(nueva))
}

/**
 * Detecta "dejar presionado" sin quitarle los toques normales a los botones de
 * la pieza: escucha antes que ellos (pase Initial) y solo consume si se cumplió
 * el tiempo, para que el botón no reciba también un toque.
 */
private fun Modifier.presionLarga(alPresionar: () -> Unit) = pointerInput(Unit) {
    val espera = viewConfiguration.longPressTimeoutMillis
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val arriba = withTimeoutOrNull(espera) { waitForUpOrCancellation(PointerEventPass.Initial) }
        if (arriba == null) {
            alPresionar()
            // Se tragan los eventos hasta que se levante el dedo.
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                ev.changes.forEach { it.consume() }
                if (ev.changes.all { !it.pressed }) break
            }
        }
    }
}
