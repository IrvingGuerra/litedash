package com.irvingguerra.litedash.core.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Deslizar a lo ancho sobre una carátula en grande: a la izquierda [onLeft], a la derecha [onRight], desde [SWIPE_MIN].
 * Solo si va más a lo ancho que a lo alto; un toque sigue siendo toque. Sin [onLeft] o [onRight], nada. Siempre llama
 * las últimas acciones. En los widgets del inicio no se usa: ahí deslizar cambia de página.
 */
@Composable
fun Modifier.horizontalSwipe(onLeft: (() -> Unit)?, onRight: (() -> Unit)?): Modifier {
    val left by rememberUpdatedState(onLeft)
    val right by rememberUpdatedState(onRight)
    if (onLeft == null || onRight == null) return this
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
