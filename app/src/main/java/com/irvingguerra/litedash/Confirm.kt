package com.irvingguerra.litedash

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography

/**
 * Una pregunta antes de algo que no se deshace: [title] (y [message] si hace falta explicar),
 * "Cancelar" y [confirm] ([destructive] = en rojo).
 */
class Alert(
    val title: String,
    val confirm: String,
    val message: String? = null,
    val destructive: Boolean = true,
    val onConfirm: () -> Unit,
)

/** Muestra una [Alert]; la pone [AlertHost] encima de todo. */
fun interface Alerts {
    fun ask(alert: Alert)
}

val LocalAlerts = staticCompositionLocalOf<Alerts> { error("Sin AlertHost") }

/**
 * Las confirmaciones de LiteDash, estilo iOS: una tarjeta al centro sobre lo oscurecido, que entra
 * con un pequeño zoom. Va dentro de la misma ventana (no un Dialog aparte), así no hace asomar la
 * barra de Android en las pantallas que la ocultan. Tocar afuera no la cierra; atrás = Cancelar.
 */
@Composable
fun AlertHost(content: @Composable () -> Unit) {
    var asked by remember { mutableStateOf<Alert?>(null) }
    var shown by remember { mutableStateOf<Alert?>(null) }
    val progress = remember { Animatable(0f) }
    val alerts = remember { Alerts { asked = it } }
    LaunchedEffect(asked) {
        val next = asked
        if (next != null) {
            shown = next
            progress.animateTo(1f, tween(ALERT_IN_MS))
        } else if (shown != null) {
            progress.animateTo(0f, tween(ALERT_OUT_MS))
            shown = null
        }
    }
    CompositionLocalProvider(LocalAlerts provides alerts) {
        Box(Modifier.fillMaxSize()) {
            content()
            shown?.let { alert ->
                BackHandler { asked = null }
                // Mientras se va, un segundo toque ya no hace nada (asked ya es null).
                AlertCard(alert, { progress.value }, onCancel = { asked = null }, onConfirm = {
                    if (asked === alert) {
                        asked = null
                        alert.onConfirm()
                    }
                })
            }
        }
    }
}

/** Un botón que pregunta ([question], con [message] si hace falta) antes de hacer algo que no se deshace solo. */
@Composable
internal fun Confirmed(
    label: String,
    question: String,
    confirm: String,
    message: String? = null,
    primary: Boolean = false,
    destructive: Boolean = true,
    action: () -> Unit,
) {
    val alerts = LocalAlerts.current
    Button(label, { alerts.ask(Alert(question, confirm, message, destructive, action)) }, primary = primary)
}

@Composable
private fun AlertCard(alert: Alert, progress: () -> Float, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val c = LocalColors.current
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = progress() }
            .background(Color.Black.copy(alpha = if (c.dark) 0.55f else 0.35f))
            // Los toques afuera no pasan a lo de atrás.
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(24.dp)
                // Ancho fijo, como en iOS (en pantallas chicas, lo que quepa).
                .widthIn(max = 320.dp)
                .fillMaxWidth()
                .graphicsLayer {
                    val s = 1.08f - 0.08f * progress()
                    scaleX = s
                    scaleY = s
                }
                .clip(RoundedCornerShape(16.dp))
                .background(c.surfaceHigh),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Label(alert.title, Typography.subtitle.copy(fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center))
                alert.message?.let { Label(it, Typography.small.copy(textAlign = TextAlign.Center), c.textMuted) }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                AlertButton(stringResource(R.string.cancel), c.text, FontWeight.Normal, Modifier.weight(1f), onCancel)
                Box(Modifier.width(1.dp).height(52.dp).background(c.line))
                AlertButton(alert.confirm, if (alert.destructive) c.danger else c.accent, FontWeight.SemiBold, Modifier.weight(1f), onConfirm)
            }
        }
    }
}

@Composable
private fun AlertButton(text: String, color: Color, weight: FontWeight, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.height(52.dp).clickable(onClick = onClick).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        Label(text, Typography.body.copy(fontWeight = weight, textAlign = TextAlign.Center), color, 1)
    }
}

private const val ALERT_IN_MS = 180
private const val ALERT_OUT_MS = 130
