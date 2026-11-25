package com.irvingguerra.litedash.core.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Deslizar a lo ancho sobre un widget: a la izquierda [onLeft], a la derecha [onRight], desde [SWIPE_MIN]. Solo si empieza
 * antes del "dejar presionado" (acomodar el widget) y va más a lo ancho que a lo alto; un toque sigue siendo toque. El gesto
 * se queda con el dedo (no cambia de página el inicio ni lo toma quien está debajo); si algo de adentro ya lo usó (la
 * música que pasa de canción), no hace nada. Sin [onLeft] o [onRight], nada. Siempre llama las últimas acciones. Dentro
 * de algo que ya usa el deslizar ([LocalSwipeTaken]: una cara de una Pila) no hace nada: un gesto = una cosa.
 */
@Composable
fun Modifier.horizontalSwipe(onLeft: (() -> Unit)?, onRight: (() -> Unit)?): Modifier {
    val left by rememberUpdatedState(onLeft)
    val right by rememberUpdatedState(onRight)
    if (onLeft == null || onRight == null || LocalSwipeTaken.current) return this
    return pointerInput(Unit) {
        val min = SWIPE_MIN.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val start = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                awaitTouchSlopOrCancellation(down.id) { change, over -> if (abs(over.x) > abs(over.y)) change.consume() }
            } ?: return@awaitEachGesture
            var last = start.position
            val ended = horizontalDrag(start.id) {
                it.consume()
                last = it.position
            }
            val dx = last.x - down.position.x
            if (!ended || abs(dx) < min) return@awaitEachGesture
            if (dx < 0) left?.invoke() else right?.invoke()
        }
    }
}

/** Lo que hay que deslizar para que cuente. */
private val SWIPE_MIN = 64.dp

/**
 * true dentro de algo cuyo deslizar a lo ancho ya significa otra cosa (una cara de una Pila: cambia de cara): ahí lo de
 * adentro (pasar de canción) no se desliza.
 */
val LocalSwipeTaken = staticCompositionLocalOf { false }
