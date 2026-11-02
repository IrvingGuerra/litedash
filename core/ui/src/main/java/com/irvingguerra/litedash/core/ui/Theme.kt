package com.irvingguerra.litedash.core.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
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

// Estilo limpio (Irving, 2026-11-01: "más estilo Apple o DuduAuto"): tarjetas lisas sin
// borde sobre un fondo apenas distinto, texto secundario gris y el ámbar solo en lo importante.
val DarkColors = Colors(
    dark = true,
    background = Color(0xFF0E1013),
    surface = Color(0xFF1A1D22),
    surfaceHigh = Color(0xFF262A31),
    line = Color(0xFF2A2F37),
    text = Brand.LightWhite,
    textMuted = Color(0xFF8B939E),
    accent = Brand.Amber,
    danger = Color(0xFFFF6B5E),
)

val LightColors = Colors(
    dark = false,
    background = Color(0xFFE9ECF0),
    surface = Color.White,
    surfaceHigh = Color(0xFFF0F2F5),
    line = Color(0xFFE3E6EA),
    text = Color(0xFF16181C),
    textMuted = Color(0xFF7D8590),
    accent = Color(0xFFE59500),
    danger = Color(0xFFD93A2B),
)

val LocalColors = staticCompositionLocalOf { DarkColors }

/** Esquinas: moderadas (Irving pidió menos redondeado que la propuesta). */
object Radius {
    /** Tarjetas del tablero y de Ajustes. */
    val card = 14.dp
    /** Cosas dentro de una tarjeta: portadas, botones, campos, filas. */
    val inner = 10.dp
    /** Detalles chicos (barras, indicadores). */
    val small = 6.dp
}

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

// El color va tercero a propósito: así se escribe en toda la app (Label(t, estilo, c.textMuted)).
@SuppressLint("ModifierParameter")
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

/** La pieza del tablero va sin fondo de tarjeta: [Card] queda transparente (lo de adentro se ve igual). */
val LocalPlainCard = compositionLocalOf { false }

/** Tarjeta base del tablero: superficie lisa, sin borde, con esquinas [Radius.card]. */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val c = LocalColors.current
    val shape = RoundedCornerShape(Radius.card)
    var m = modifier.clip(shape).background(if (LocalPlainCard.current) Color.Transparent else c.surface)
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

/** Botón de texto con esquinas [Radius.inner]. */
@Composable
fun Button(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false) {
    val c = LocalColors.current
    val shape = RoundedCornerShape(Radius.inner)
    Box(
        modifier
            .clip(shape)
            .background(if (primary) c.text else c.surfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Label(text, Typography.body.copy(fontWeight = FontWeight.SemiBold), if (primary) c.background else c.text, 1) }
}

/**
 * Opciones excluyentes (una u otra), estilo iOS: un bloque con la elegida resaltada.
 * Para encender o apagar algo se usa [SwitchRow].
 */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    Row(
        modifier.clip(RoundedCornerShape(Radius.inner)).background(c.surfaceHigh).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((value, text) in options) {
            val on = value == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(Radius.inner - 2.dp))
                    .background(if (on) c.surface else Color.Transparent)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 18.dp, vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Label(text, Typography.body.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal), if (on) c.text else c.textMuted, 1)
            }
        }
    }
}

/** Un renglón con su interruptor: el texto (y una ayuda opcional) a la izquierda. */
@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, help: String? = null) {
    val c = LocalColors.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.inner)).clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Label(title, Typography.body)
            if (help != null) Label(help, Typography.small, c.textMuted)
        }
        Switch(checked)
    }
}

/** El interruptor en sí (el toque lo maneja su renglón). */
@Composable
private fun Switch(checked: Boolean) {
    val c = LocalColors.current
    val knob by animateDpAsState(if (checked) 22.dp else 2.dp, label = "interruptor")
    Box(
        Modifier.size(width = 52.dp, height = 32.dp).clip(CircleShape).background(if (checked) c.accent else c.line),
    ) {
        Box(Modifier.padding(start = knob, top = 2.dp).size(28.dp).clip(CircleShape).background(Color.White))
    }
}
