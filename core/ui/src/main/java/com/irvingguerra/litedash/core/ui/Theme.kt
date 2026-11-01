package com.irvingguerra.litedash.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Colores de la marca (los mismos de brand/logo).
object Brand {
    val Graphite = Color(0xFF101317)
    val GraphiteLight = Color(0xFF1F252D)
    val LightWhite = Color(0xFFF4F6F8)
    val Amber = Color(0xFFFFB224)
}

@Immutable
data class Colors(
    val dark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val line: Color,
    val text: Color,
    val textMuted: Color,
    val accent: Color,
    val danger: Color,
)

val DarkColors = Colors(
    dark = true,
    background = Brand.Graphite,
    surface = Color(0xFF171B21),
    surfaceHigh = Brand.GraphiteLight,
    line = Color(0xFF2A313B),
    text = Brand.LightWhite,
    textMuted = Color(0xFF9AA3AE),
    accent = Brand.Amber,
    danger = Color(0xFFFF6B5E),
)

val LightColors = Colors(
    dark = false,
    background = Color(0xFFF2F3F5),
    surface = Color.White,
    surfaceHigh = Color(0xFFE6E9ED),
    line = Color(0xFFDADEE3),
    text = Brand.Graphite,
    textMuted = Color(0xFF5B6470),
    accent = Color(0xFFE59500),
    danger = Color(0xFFD93A2B),
)

val LocalColors = staticCompositionLocalOf { DarkColors }

/**
 * Sin Material: la app usa solo foundation para mantenerse ligera.
 * [scale] agranda o achica toda la interfaz (pantallas que mienten con su dpi).
 */
@Composable
fun LiteDashTheme(dark: Boolean = true, scale: Float = 1f, content: @Composable () -> Unit) {
    val d = LocalDensity.current
    CompositionLocalProvider(
        LocalColors provides if (dark) DarkColors else LightColors,
        LocalDensity provides Density(d.density * scale, d.fontScale),
        content = content,
    )
}

object Typography {
    val clock = TextStyle(fontSize = 56.sp, fontWeight = FontWeight.Medium, letterSpacing = (-1).sp)
    val title = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    val subtitle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium)
    val body = TextStyle(fontSize = 16.sp)
    val small = TextStyle(fontSize = 13.sp)
    val label = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
}

@Composable
fun Label(
    text: String,
    style: TextStyle,
    color: Color = LocalColors.current.text,
    maxLines: Int = Int.MAX_VALUE,
    modifier: Modifier = Modifier,
) {
    BasicText(text, modifier, style = style.copy(color = color), maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/** Tarjeta base del tablero: superficie con borde fino y esquinas de 20. */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val c = LocalColors.current
    val shape = RoundedCornerShape(20.dp)
    var m = modifier.clip(shape).background(c.surface).border(1.dp, c.line, shape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Box(m, content = content)
}

/** Botón redondo con ícono, objetivo táctil de 56 dp como pide el plan. */
@Composable
fun IconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    background: Color = Color.Transparent,
    color: Color = LocalColors.current.text,
) {
    Box(
        modifier.size(size).clip(CircleShape).background(background).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, color, size * 0.45f) }
}

/** Botón de texto en forma de píldora. */
@Composable
fun Button(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false) {
    val c = LocalColors.current
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .clip(shape)
            .background(if (primary) c.text else c.surfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Label(text, Typography.body.copy(fontWeight = FontWeight.SemiBold), if (primary) c.background else c.text, 1) }
}
