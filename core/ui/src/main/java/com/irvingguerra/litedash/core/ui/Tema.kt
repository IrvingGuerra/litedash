package com.irvingguerra.litedash.core.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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
    val fondo: Color,
    val superficie: Color,
    val texto: Color,
    val textoSuave: Color,
    val acento: Color,
)

val Oscuro = Colores(
    fondo = Marca.Grafito,
    superficie = Marca.GrafitoClaro,
    texto = Marca.BlancoLuz,
    textoSuave = Color(0xFF9AA3AE),
    acento = Marca.Ambar,
)

val Claro = Colores(
    fondo = Marca.BlancoLuz,
    superficie = Color(0xFFE6E9ED),
    texto = Marca.Grafito,
    textoSuave = Color(0xFF5B6470),
    acento = Marca.Ambar,
)

val LocalColores = staticCompositionLocalOf { Oscuro }

// Sin Material: la app usa solo foundation para mantenerse ligera.
@Composable
fun LiteDashTema(oscuro: Boolean = true, contenido: @Composable () -> Unit) {
    CompositionLocalProvider(LocalColores provides if (oscuro) Oscuro else Claro, content = contenido)
}

object Texto {
    val reloj = TextStyle(fontSize = 56.sp, fontWeight = FontWeight.Medium)
    val titulo = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    val normal = TextStyle(fontSize = 16.sp)
    val chico = TextStyle(fontSize = 13.sp)
}

@Composable
fun Etiqueta(texto: String, estilo: TextStyle, color: Color = LocalColores.current.texto, maxLineas: Int = Int.MAX_VALUE) {
    BasicText(texto, style = estilo.copy(color = color), maxLines = maxLineas)
}
