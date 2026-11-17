package com.irvingguerra.litedash

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.LocalColors

/**
 * La brújula: un botón redondo con la aguja apuntando al norte del mapa (girada [rotation] grados, la misma vuelta que
 * el mapa). Un toque cambia entre «al norte» y «hacia donde vas» ([northUp] = cómo está ahora).
 */
@Composable
internal fun CompassButton(
    rotation: Float, northUp: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 44.dp,
    background: Color = LocalColors.current.surface.copy(alpha = 0.9f),
) {
    val c = LocalColors.current
    val label = stringResource(if (northUp) R.string.map_heading else R.string.map_north_up)
    Box(
        modifier.size(size).clip(CircleShape).background(background)
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        // Como una brújula de verdad: la punta norte roja.
        val tip = NEEDLE_RED
        val tail = c.textMuted
        Canvas(Modifier.size(size * 0.62f)) {
            val w = this.size.minDimension
            val mid = Offset(w / 2, w / 2)
            rotate(rotation, mid) {
                val half = w * 0.16f
                drawPath(Path().apply { moveTo(mid.x, 0f); lineTo(mid.x + half, mid.y); lineTo(mid.x - half, mid.y); close() }, tip)
                drawPath(Path().apply { moveTo(mid.x, w); lineTo(mid.x + half, mid.y); lineTo(mid.x - half, mid.y); close() }, tail)
            }
        }
    }
}

private val NEEDLE_RED = Color(0xFFE5484D)
