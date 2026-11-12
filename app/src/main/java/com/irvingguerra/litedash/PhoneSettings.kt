// Ajustes → Celular: revisar la red, el QR para vincular LiteDash Connect (donde vive la cuenta) y, ya vinculado,
// cómo van los datos (el enlace) y el sonido (el Bluetooth).
package com.irvingguerra.litedash

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Radius
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.feature.link.Pairing
import com.irvingguerra.litedash.feature.link.PhoneLink
import com.irvingguerra.litedash.feature.link.PhoneSound
import com.irvingguerra.litedash.feature.link.QrCode
import com.irvingguerra.litedash.feature.music.MusicMode

/** Lado del QR a lo ancho; angosto se ajusta a lo que haya. */
private val QR_SIZE = 320.dp

/** Desde este ancho el QR va a un lado de los pasos; más angosto, arriba. */
private val SIDE_BY_SIDE = 660.dp

/** Desde este ancho las dos formas de tener red van lado a lado; más angosto, una sobre otra. */
private val OPTIONS_SIDE_BY_SIDE = 520.dp

/** Módulos claros alrededor del QR (lo que pide la norma para que la cámara lo encuentre). */
private const val QUIET_ZONE = 4

/**
 * La sección Celular. Mientras está a la vista el enlace intenta conectarse cada 2 s, revisa la red
 * y mantiene vigente el QR; al salir (o irse LiteDash al fondo) deja de intentar. Sin red no hay
 * código: primero se dice cómo tenerla. [openMusic] lleva a Ajustes → Apps → Música.
 */
@Composable
internal fun PhoneSettings(d: Dependencies, openMusic: () -> Unit) {
    val link = d.phone
    LifecycleResumeEffect(link) {
        link.watch(true)
        onPauseOrDispose { link.watch(false) }
    }
    val pairing = link.pairing
    SettingsCard(help = stringResource(R.string.phone_help)) {
        when {
            link.paired -> Linked(d, openMusic)
            !link.localNetwork -> NoNetwork()
            pairing != null -> PairingCode(pairing)
        }
    }
}

/** Cómo va algo: verde = bien, ámbar = falta o se está buscando, gris = sin dato. */
private enum class Mark { OK, WARN, INFO }

@Composable
private fun Mark.color(): Color = when (this) {
    Mark.OK -> Good
    Mark.WARN -> LocalColors.current.warning
    Mark.INFO -> LocalColors.current.textMuted
}

/** El estado grande de arriba: un punto (quieto: nada se anima en reposo) y el texto. */
@Composable
private fun StatusTitle(mark: Mark, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(mark.color()))
        Label(text, Typography.title, maxLines = 1)
    }
}

/** A · Sin red: las dos formas de que el celular y la pantalla se hablen, con los botones que lo arreglan. */
@Composable
private fun NoNetwork() {
    val c = LocalColors.current
    val context = LocalContext.current
    StatusTitle(Mark.INFO, stringResource(R.string.phone_no_wifi))
    Label(stringResource(R.string.phone_no_wifi_body), Typography.body, c.textMuted)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val options = listOf(
            R.string.phone_use_phone to R.string.phone_use_phone_body,
            R.string.phone_share_screen to R.string.phone_share_screen_body,
        )
        if (maxWidth >= OPTIONS_SIDE_BY_SIDE) {
            // Las dos del mismo alto aunque una ocupe un renglón más.
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for ((title, body) in options) NetworkOption(title, body, Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for ((title, body) in options) NetworkOption(title, body, Modifier.fillMaxWidth())
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(stringResource(R.string.phone_open_wifi), { openWifi(context) }, primary = true)
        Button(stringResource(R.string.phone_open_hotspot), { openHotspot(context) })
    }
}

@Composable
private fun NetworkOption(title: Int, body: Int, modifier: Modifier) {
    val c = LocalColors.current
    Column(
        modifier.clip(RoundedCornerShape(Radius.inner)).background(c.surfaceHigh).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Label(stringResource(title), Typography.body.copy(fontWeight = FontWeight.SemiBold))
        Label(stringResource(body), Typography.small, c.textMuted)
    }
}

private fun openWifi(context: Context) = openSystemScreen(context, Intent(Settings.ACTION_WIFI_SETTINGS))

/** El hotspot vive en "Anclaje a red"; si la pantalla no lo tiene a la mano, en "Redes inalámbricas". */
private fun openHotspot(context: Context) {
    val tether = Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings")
    if (!openSystemScreen(context, tether)) openSystemScreen(context, Intent(Settings.ACTION_WIRELESS_SETTINGS))
}

/** B · Sin celular y con red: el QR y los tres pasos. */
@Composable
private fun PairingCode(pairing: Pairing) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = maxWidth
        if (width >= SIDE_BY_SIDE) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(30.dp)) {
                QrImage(pairing.text, QR_SIZE)
                Steps(pairing.code, Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                QrImage(pairing.text, minOf(width, QR_SIZE), Modifier.align(Alignment.CenterHorizontally))
                Steps(pairing.code, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun Steps(code: String, modifier: Modifier) {
    val c = LocalColors.current
    Column(modifier) {
        StatusTitle(Mark.WARN, stringResource(R.string.phone_waiting))
        // Los dígitos separados: se comparan de un vistazo con los del celular.
        Label(stringResource(R.string.phone_link_code, code.toList().joinToString(" ")), Typography.body, c.textMuted, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
        val steps = listOf(R.string.phone_step_install, R.string.phone_step_tap, R.string.phone_step_scan)
        steps.forEachIndexed { i, step ->
            if (i > 0) Divider()
            Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Label("${i + 1}", Typography.body, c.accent)
                Label(stringResource(step), Typography.body)
            }
        }
    }
}

/** El QR: siempre negro sobre blanco (también en tema oscuro) y con módulos de píxeles enteros. */
@Composable
private fun QrImage(text: String, side: Dp, modifier: Modifier = Modifier) {
    val qr = remember(text) { QrCode.encode(text.toByteArray()) } ?: return
    Canvas(modifier.size(side).clip(RoundedCornerShape(Radius.card)).background(Color.White)) {
        val module = (size.minDimension / (qr.size + QUIET_ZONE * 2)).toInt().coerceAtLeast(1)
        val start = ((size.minDimension - module * qr.size) / 2).toInt()
        for (y in 0 until qr.size) {
            var x = 0
            while (x < qr.size) {
                // Cada corrida oscura del renglón, de un solo trazo.
                val from = x
                while (x < qr.size && qr[x, y]) x++
                if (x > from) drawRect(Color.Black, Offset((start + from * module).toFloat(), (start + y * module).toFloat()), Size(((x - from) * module).toFloat(), module.toFloat()))
                x++
            }
        }
    }
}

/** C y D · Con celular vinculado: conectado (verde) o buscándolo (ámbar), con los datos y el sonido. */
@Composable
private fun Linked(d: Dependencies, openMusic: () -> Unit) {
    val link = d.phone
    val c = LocalColors.current
    // El Bluetooth solo se escucha mientras esto está a la vista.
    LifecycleResumeEffect(d.music) {
        d.music.watchPhone(true)
        onPauseOrDispose { d.music.watchPhone(false) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StatusTitle(if (link.connected) Mark.OK else Mark.WARN, stringResource(if (link.connected) R.string.phone_connected else R.string.phone_searching))
        phoneLine(link)?.let { Label(it, Typography.body, c.textMuted, maxLines = 1) }
    }
    Column {
        Divider()
        DataRow(link)
        Divider()
        SoundRow(d, openMusic)
        Divider()
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // Conectado, buscar es lo de menos; sin conexión es lo principal.
        Button(stringResource(R.string.phone_search_now), link::searchNow, primary = !link.connected)
        Button(stringResource(R.string.phone_unlink), link::unlink)
    }
}

/** "{modelo} · LiteDash Connect {versión}" (sin conexión, solo el modelo); null si aún no se sabe nada. */
@Composable
private fun phoneLine(link: PhoneLink): String? {
    val name = link.phoneName?.ifBlank { null }
    val app = link.phoneApp?.ifBlank { null }.takeIf { link.connected }
    return when {
        name != null && app != null -> stringResource(R.string.phone_model_app, name, app)
        app != null -> stringResource(R.string.phone_model_app, "—", app)
        else -> name
    }
}

/** Datos: el enlace con LiteDash Connect. */
@Composable
private fun DataRow(link: PhoneLink) {
    val context = LocalContext.current
    val title = stringResource(R.string.phone_data)
    when {
        link.connected && link.musicBlocked -> CheckRow(Mark.WARN, title, stringResource(R.string.phone_music_blocked))
        link.connected -> CheckRow(Mark.OK, title, stringResource(R.string.phone_data_ok))
        !link.localNetwork -> CheckRow(Mark.WARN, title, stringResource(R.string.phone_data_no_network)) {
            Button(stringResource(R.string.phone_open_wifi), { openWifi(context) })
        }
        else -> CheckRow(Mark.WARN, title, stringResource(R.string.phone_data_open_app))
    }
}

/**
 * Sonido: el Bluetooth del celular. Solo se sabe con el Bluetooth de Android (y permiso de ver sus
 * aparatos); con chip propio el sonido va por el estéreo y aquí solo se dice eso.
 */
@Composable
private fun SoundRow(d: Dependencies, openMusic: () -> Unit) {
    val context = LocalContext.current
    val title = stringResource(R.string.phone_sound)
    val phone = d.music.bluetoothDevice
    // Lo que dice el propio celular vale en cualquier pantalla, también con chip de Bluetooth propio.
    val sound = d.phone.phoneSound
    when {
        phone != null -> CheckRow(Mark.OK, title, stringResource(R.string.phone_sound_ok, phone))
        sound == PhoneSound.BLUETOOTH -> CheckRow(Mark.OK, title, stringResource(R.string.phone_sound_ok, d.phone.phoneName?.ifBlank { null } ?: "✓"))
        // Android Auto o un cable llevan el audio: no hace falta el Bluetooth.
        sound == PhoneSound.CAR -> CheckRow(Mark.OK, title, stringResource(R.string.phone_sound_car))
        sound == PhoneSound.WIRED -> CheckRow(Mark.OK, title, stringResource(R.string.phone_sound_wired))
        sound == PhoneSound.PHONE || d.music.phoneKnowable -> CheckRow(Mark.WARN, title, stringResource(R.string.phone_sound_missing)) {
            Button(stringResource(R.string.phone_connect_bt), {
                // Donde se empareja: la tarjeta de LiteDash si la música ya va por Bluetooth; si no, la de Android.
                if (d.music.mode == MusicMode.BLUETOOTH || !openSystemScreen(context, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))) openMusic()
            })
        }
        else -> CheckRow(Mark.INFO, title, stringResource(R.string.phone_sound_stereo))
    }
}

/** Un renglón: la marca (✓, ! o i), qué es y cómo va, y a la derecha el botón que lo arregla (si hay). */
@Composable
private fun CheckRow(mark: Mark, title: String, detail: String, action: (@Composable () -> Unit)? = null) {
    val c = LocalColors.current
    val color = mark.color()
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(if (mark == Mark.INFO) c.surfaceHigh else color.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            when (mark) {
                Mark.OK -> Icon(Icons.done, color, 18.dp)
                Mark.WARN -> Label("!", Typography.body.copy(fontWeight = FontWeight.Bold), color, maxLines = 1)
                Mark.INFO -> Label("i", Typography.body.copy(fontWeight = FontWeight.Bold), color, maxLines = 1)
            }
        }
        Column(Modifier.weight(1f)) {
            Label(title, Typography.body.copy(fontWeight = FontWeight.Medium), maxLines = 1)
            Label(detail, Typography.small, c.textMuted)
        }
        action?.invoke()
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(LocalColors.current.line))
}
