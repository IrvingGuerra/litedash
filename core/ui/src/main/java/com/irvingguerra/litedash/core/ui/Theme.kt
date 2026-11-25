package com.irvingguerra.litedash.core.ui

import android.annotation.SuppressLint
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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

/** Colores que se repiten entre los temas: tinta (fondo oscuro), tinta y papel del claro, y su ámbar. */
private val Ink = Color(0xFF0E1013)
private val Good = Color(0xFF3FB27F)
private val LightInk = Color(0xFF16181C)
private val LightPaper = Color(0xFFE9ECF0)
private val LightAmber = Color(0xFFE59500)

@Immutable
data class Colors(
    val dark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val line: Color,
    val text: Color,
    val textMuted: Color,
    /** El color de realce: interruptores, íconos y lo que se llena. Se puede elegir ([withAccent]). */
    val accent: Color,
    val danger: Color,
    /** Advertencia (Salud, Llantas, códigos pendientes): siempre ámbar, aunque se elija otro color. */
    val warning: Color,
    /** Todo bien (Salud, Llantas, conectado): siempre verde, aunque se elija otro color. */
    val good: Color,
    /** Relleno de lo principal (botón principal, play, barra de avance, chip elegido) y el texto encima. */
    val primary: Color,
    val onPrimary: Color,
)

/**
 * Los colores con el realce que eligió el usuario; null = el natural (ámbar, y lo principal en
 * blanco o negro). Con un color, lo principal también lo toma; nunca las tarjetas ni el fondo.
 * Un color que no se vería se ajusta: en oscuro, uno casi negro se aclara; en claro, uno claro se
 * oscurece. Lo de encima ([Colors.onPrimary]) es tinta o blanco, el que más contraste.
 */
internal fun Colors.withAccent(chosen: Color?): Colors {
    if (chosen == null) return this
    var color: Color = chosen
    if (dark) while (color.luminance() < 0.12f) color = lerp(color, Color.White, 0.1f)
    else while (color.luminance() > 0.35f) color = lerp(color, Color.Black, 0.08f)
    // En oscuro, uno casi blanco tampoco: el interruptor prendido llevaría bolita blanca sobre blanco.
    if (dark) while (color.luminance() > 0.8f) color = lerp(color, Color.Black, 0.08f)
    return copy(accent = color, primary = color, onPrimary = if (contrast(color, Ink) >= contrast(color, Color.White)) Ink else Color.White)
}

/**
 * Los colores con las tarjetas del color que eligió el usuario; null = las del tema. Él elige el tono y el tema
 * decide qué tan claro va: en oscuro se baja hasta [DARK_CARD_MAX] (un azul vivo queda azul profundo) y en claro se
 * sube hasta [LIGHT_CARD_MIN] (queda pastel), así el texto del tema siempre se lee. De ese color salen lo elevado,
 * las rayas y el fondo; el texto apagado y el realce se ajustan si no contrastan.
 */
internal fun Colors.withCards(chosen: Color?): Colors {
    if (chosen == null) return this
    val card = cardTone(chosen, dark)
    val high = lerp(card, text, if (dark) 0.09f else 0.06f)
    var muted = textMuted
    while (contrast(muted, card) < MUTED_CONTRAST && muted != text) muted = lerp(muted, text, 0.15f).takeIf { it != muted } ?: text
    var mark = accent
    val toward = if (dark) Color.White else Color.Black
    while (contrast(mark, card) < MARK_CONTRAST && mark != toward) mark = lerp(mark, toward, 0.12f).takeIf { it != mark } ?: toward
    val main = if (primary == accent) mark else primary
    return copy(
        background = lerp(card, Color.Black, if (dark) 0.5f else 0.07f),
        surface = card,
        surfaceHigh = high,
        line = lerp(card, text, 0.12f),
        textMuted = muted,
        accent = mark,
        primary = main,
        onPrimary = if (contrast(main, Ink) >= contrast(main, Color.White)) Ink else Color.White,
    )
}

/** El color [chosen] con la claridad que le toca a una tarjeta del tema oscuro ([dark]) o del claro. */
fun cardTone(chosen: Color, dark: Boolean): Color {
    var color = chosen.copy(alpha = 1f)
    if (dark) while (color.luminance() > DARK_CARD_MAX) color = lerp(color, Color.Black, 0.08f)
    else while (color.luminance() < LIGHT_CARD_MIN) color = lerp(color, Color.White, 0.1f)
    return color
}

/** Lo más clara que va una tarjeta en oscuro y lo más oscura en claro (luminancia 0–1). */
private const val DARK_CARD_MAX = 0.075f
private const val LIGHT_CARD_MIN = 0.78f

/** El contraste mínimo del texto apagado y del realce contra la tarjeta. */
private const val MUTED_CONTRAST = 3f
private const val MARK_CONTRAST = 3f

/** El contraste entre dos colores (1–21), como lo mide WCAG. */
internal fun contrast(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

// Estilo limpio (Irving, 2026-11-01: "más estilo Apple o DuduAuto"): tarjetas lisas sin
// borde sobre un fondo apenas distinto, texto secundario gris y el color de realce solo en lo importante.
val DarkColors = Colors(
    dark = true,
    background = Ink,
    surface = Color(0xFF1A1D22),
    surfaceHigh = Color(0xFF262A31),
    line = Color(0xFF2A2F37),
    text = Brand.LightWhite,
    textMuted = Color(0xFF8B939E),
    accent = Brand.Amber,
    danger = Color(0xFFFF6B5E),
    warning = Brand.Amber,
    good = Good,
    primary = Brand.LightWhite,
    onPrimary = Ink,
)

val LightColors = Colors(
    dark = false,
    background = LightPaper,
    surface = Color.White,
    surfaceHigh = Color(0xFFF0F2F5),
    line = Color(0xFFE3E6EA),
    text = LightInk,
    // ≥ 4.5 sobre el fondo, lo elevado y la tarjeta (el #7D8590 de antes daba 3.1 sobre el fondo).
    textMuted = Color(0xFF626A75),
    accent = LightAmber,
    danger = Color(0xFFD93A2B),
    warning = LightAmber,
    good = Good,
    primary = LightInk,
    onPrimary = LightPaper,
)

val LocalColors = staticCompositionLocalOf { DarkColors }

/** Los colores de un tema ([dark]) con el realce y las tarjetas elegidos (null = los suyos): los de [LiteDashTheme]. */
fun themeColors(dark: Boolean, accent: Color?, cards: Color?): Colors =
    (if (dark) DarkColors else LightColors).withAccent(accent).withCards(cards)

/**
 * Un bloque que resalta dentro de una tarjeta (la opción elegida, un campo, una ficha): liso en una tarjeta lisa y,
 * dentro del vidrio (ahí [Colors.surfaceHigh] ya es translúcido, ver [contentColors]), translúcido como lo demás.
 */
val Colors.raised: Color get() = if (surfaceHigh.alpha < 1f) surfaceHigh else surface

/**
 * El margen de adentro de las tarjetas del inicio (los widgets): poco, para que lo de adentro
 * luzca y se lea de lejos. Todas lo usan igual, con fondo o sin él.
 */
val TilePadding = 10.dp

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
 * [scale] agranda o achica toda la interfaz (pantallas que mienten con su dpi); [accent], el color elegido de la
 * interfaz y [cards], el de las tarjetas.
 */
@Composable
fun LiteDashTheme(dark: Boolean = true, scale: Float = 1f, accent: Color? = null, cards: Color? = null, content: @Composable () -> Unit) {
    val d = LocalDensity.current
    val colors = remember(dark, accent, cards) { themeColors(dark, accent, cards) }
    CompositionLocalProvider(
        LocalColors provides colors,
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

/**
 * Tarjeta base de toda la app, con esquinas [Radius.card]: lisa o de vidrio según la piel ([LocalCardLook]) y del
 * color del tema. Sin tarjeta ([LocalPlainCard]), transparente. Lo de adentro recibe sus colores ([contentColors]).
 */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val c = LocalColors.current
    val look = LocalCardLook.current
    val plain = LocalPlainCard.current
    val shape = RoundedCornerShape(Radius.card)
    val context = LocalContext.current
    val cheap = remember { lowMemoryDevice(context) }
    var m = modifier.then(if (plain) Modifier else Modifier.glassShadow(look, c.dark, Radius.card, cheap))
        .clip(shape).then(if (plain) Modifier else Modifier.skinSurface(look, c, shape))
    if (onClick != null) m = m.clickable(onClick = onClick)
    // Una vez por tema y piel, no en cada dibujo (un valor nuevo haría releer los colores a todo lo de adentro).
    val inner = remember(c, look.style, plain) { contentColors(c, look, plain) }
    Box(m) { CompositionLocalProvider(LocalColors provides inner) { content() } }
}

/** Botón redondo con ícono, objetivo táctil de 56 dp como pide el plan; [label] = lo que dice el lector de pantalla. */
@Composable
fun IconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    background: Color = Color.Transparent,
    color: Color = LocalColors.current.text,
    label: String? = null,
) {
    Box(
        modifier.size(size).clip(CircleShape).background(background).clickable(role = Role.Button, onClick = onClick)
            .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, color, size * 0.45f) }
}

/** Botón de texto con esquinas [Radius.inner]; sin [enabled] se ve apagado y no responde. */
@Composable
fun Button(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true) {
    val c = LocalColors.current
    val shape = RoundedCornerShape(Radius.inner)
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .background(if (primary) c.primary else c.surfaceHigh)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Label(text, Typography.body.copy(fontWeight = FontWeight.SemiBold), if (primary) c.onPrimary else c.text, 1) }
}

/**
 * Opciones excluyentes (una u otra), estilo iOS: un bloque con la elegida resaltada.
 * Para encender o apagar algo se usa [SwitchRow]. [check] = la elegida lleva palomita (para un
 * método que se activa, como la fuente de la música o de la radio, no un simple filtro).
 */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier, check: Boolean = false) {
    val c = LocalColors.current
    // Si no caben (pantalla de pie), las que sobran bajan de renglón en vez de cortarse; si caben, una fila.
    FlowRow(
        modifier.clip(RoundedCornerShape(Radius.inner)).background(c.surfaceHigh).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((value, text) in options) {
            val on = value == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(Radius.inner - 2.dp))
                    .background(if (on) c.raised else Color.Transparent)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 18.dp, vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (check && on) Icon(Icons.done, c.primary, 18.dp)
                    Label(text, Typography.body.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal), if (on) c.text else c.textMuted, 1)
                }
            }
        }
    }
}

/** Atrapa los toques para que no pasen a lo de abajo (paneles y capas sobre el inicio); no se ve ni hace nada. */
fun Modifier.blockTouches(): Modifier = clickable(interactionSource = null, indication = null, onClick = {})

/** Una raya fina que separa renglones dentro de una tarjeta. */
@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(LocalColors.current.line))
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
fun Switch(checked: Boolean, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    val knob by animateDpAsState(if (checked) 22.dp else 2.dp, label = "interruptor")
    Box(
        modifier.size(width = 52.dp, height = 32.dp).clip(CircleShape).background(if (checked) c.accent else c.line),
    ) {
        Box(Modifier.padding(start = knob, top = 2.dp).size(28.dp).clip(CircleShape).background(Color.White))
    }
}

/**
 * Campo de texto de una línea con [hint]; [icon] pone la lupa (buscadores). [compact] = píldora baja para la cabecera
 * de un panel (#28: así la lista de abajo gana un renglón). [onDone] = qué hace «Listo» del teclado (si no, lo de Android:
 * soltar el foco, que en Android ≤ 8 regresa al primer campo y vuelve a abrir el teclado).
 */
@Composable
fun TextInput(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    icon: Boolean = false,
    compact: Boolean = false,
    onDone: (() -> Unit)? = null,
) {
    val c = LocalColors.current
    val shape = if (compact) CircleShape else RoundedCornerShape(Radius.card)
    Row(
        modifier.height(if (compact) 44.dp else 56.dp).clip(shape).background(c.raised).border(1.dp, c.line, shape)
            .padding(horizontal = if (compact) 16.dp else 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon) {
            Icon(Icons.search, c.textMuted, if (compact) 18.dp else 22.dp)
            Spacer(Modifier.width(if (compact) 10.dp else 12.dp))
        }
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Label(hint, Typography.body, c.textMuted, maxLines = 1)
            BasicTextField(
                value,
                onChange,
                Modifier.fillMaxWidth(),
                textStyle = Typography.body.copy(color = c.text),
                singleLine = true,
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = onDone?.let { done -> KeyboardActions(onDone = { done() }) } ?: KeyboardActions.Default,
            )
        }
    }
}
