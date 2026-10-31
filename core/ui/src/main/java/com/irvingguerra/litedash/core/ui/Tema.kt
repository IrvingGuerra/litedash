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
object Marca {
    val Grafito = Color(0xFF101317)
    val GrafitoClaro = Color(0xFF1F252D)
    val BlancoLuz = Color(0xFFF4F6F8)
    val Ambar = Color(0xFFFFB224)
}

@Immutable
data class Colores(
    val oscuro: Boolean,
    val fondo: Color,
    val superficie: Color,
    val superficieAlta: Color,
    val linea: Color,
    val texto: Color,
    val textoSuave: Color,
    val acento: Color,
    val peligro: Color,
)

val Oscuro = Colores(
    oscuro = true,
    fondo = Marca.Grafito,
    superficie = Color(0xFF171B21),
    superficieAlta = Marca.GrafitoClaro,
    linea = Color(0xFF2A313B),
    texto = Marca.BlancoLuz,
    textoSuave = Color(0xFF9AA3AE),
    acento = Marca.Ambar,
    peligro = Color(0xFFFF6B5E),
)

val Claro = Colores(
    oscuro = false,
    fondo = Color(0xFFF2F3F5),
    superficie = Color.White,
    superficieAlta = Color(0xFFE6E9ED),
    linea = Color(0xFFDADEE3),
    texto = Marca.Grafito,
    textoSuave = Color(0xFF5B6470),
    acento = Color(0xFFE59500),
    peligro = Color(0xFFD93A2B),
)

val LocalColores = staticCompositionLocalOf { Oscuro }

/**
 * Sin Material: la app usa solo foundation para mantenerse ligera.
 * [escala] agranda o achica toda la interfaz (pantallas que mienten con su dpi).
 */
@Composable
fun LiteDashTema(oscuro: Boolean = true, escala: Float = 1f, contenido: @Composable () -> Unit) {
    val d = LocalDensity.current
    CompositionLocalProvider(
        LocalColores provides if (oscuro) Oscuro else Claro,
        LocalDensity provides Density(d.density * escala, d.fontScale),
        content = contenido,
    )
}

object Texto {
    val reloj = TextStyle(fontSize = 56.sp, fontWeight = FontWeight.Medium, letterSpacing = (-1).sp)
    val titulo = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    val subtitulo = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium)
    val normal = TextStyle(fontSize = 16.sp)
    val chico = TextStyle(fontSize = 13.sp)
    val etiqueta = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
}

@Composable
fun Etiqueta(
    texto: String,
    estilo: TextStyle,
    color: Color = LocalColores.current.texto,
    maxLineas: Int = Int.MAX_VALUE,
    modifier: Modifier = Modifier,
) {
    BasicText(texto, modifier, style = estilo.copy(color = color), maxLines = maxLineas, overflow = TextOverflow.Ellipsis)
}

/** Tarjeta base del tablero: superficie con borde fino y esquinas de 20. */
@Composable
fun Tarjeta(
    modifier: Modifier = Modifier,
    alTocar: (() -> Unit)? = null,
    contenido: @Composable BoxScope.() -> Unit,
) {
    val c = LocalColores.current
    val forma = RoundedCornerShape(20.dp)
    var m = modifier.clip(forma).background(c.superficie).border(1.dp, c.linea, forma)
    if (alTocar != null) m = m.clickable(onClick = alTocar)
    Box(m, content = contenido)
}

/** Botón redondo con ícono, objetivo táctil de 56 dp como pide el plan. */
@Composable
fun BotonIcono(
    icono: ImageVector,
    alTocar: () -> Unit,
    modifier: Modifier = Modifier,
    tam: Dp = 56.dp,
    fondo: Color = Color.Transparent,
    color: Color = LocalColores.current.texto,
) {
    Box(
        modifier.size(tam).clip(CircleShape).background(fondo).clickable(onClick = alTocar),
        contentAlignment = Alignment.Center,
    ) { Icono(icono, color, tam * 0.45f) }
}

/** Botón de texto en forma de píldora. */
@Composable
fun Boton(texto: String, alTocar: () -> Unit, modifier: Modifier = Modifier, principal: Boolean = false) {
    val c = LocalColores.current
    val forma = RoundedCornerShape(50)
    Box(
        modifier
            .clip(forma)
            .background(if (principal) c.texto else c.superficieAlta)
            .clickable(onClick = alTocar)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Etiqueta(texto, Texto.normal.copy(fontWeight = FontWeight.SemiBold), if (principal) c.fondo else c.texto, 1) }
}
