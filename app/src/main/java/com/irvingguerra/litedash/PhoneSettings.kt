// Ajustes → Celular: revisar la red, el QR para vincular LiteDash Connect (donde vive la cuenta) y, ya vinculado,
// cómo van el enlace y el sonido (el Bluetooth).
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Divider
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Radius
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.core.vehicle.LOCAL_NETWORK_PERMISSION
import com.irvingguerra.litedash.feature.link.Pairing
import com.irvingguerra.litedash.feature.link.PhoneLink
import com.irvingguerra.litedash.feature.link.PhoneSound
import com.irvingguerra.litedash.feature.link.QrCode

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
 * código: primero se dice cómo tenerla. En Android 17+ sin el permiso de la red local, antes que nada se pide
 * (solo al tocar: pedirlo solo abriría el diálogo en bucle).
 */
@Composable
internal fun PhoneSettings(d: Dependencies) {
    val link = d.phone
    LifecycleResumeEffect(link) {
        // Sin celular vinculado el enlace nace aquí: se arranca (al volver a LiteDash solo se arranca si hay vínculo).
        link.start()
        link.watch(true)
        onPauseOrDispose { link.watch(false) }
    }
    val pairing = link.pairing
    SettingsCard(help = stringResource(R.string.phone_help)) {
        when {
            !link.networkAllowed -> AskLocalNetwork(link)
            link.paired -> Linked(d)
            // Sin la primera revisión de la red no se sabe si hay: ni el QR ni "sin red" todavía.
            !link.networkChecked -> StatusTitle(Mark.INFO, stringResource(R.string.phone_waiting))
            !link.localNetwork -> NoNetwork()
            pairing != null -> PairingCode(pairing)
            // Con red y el código aún sin hacer (un instante al abrir).
            else -> StatusTitle(Mark.INFO, stringResource(R.string.phone_waiting))
        }
    }
}

/** Cómo va algo: verde = bien, ámbar = falta o se está buscando, gris = sin dato. */
private enum class Mark { OK, WARN, INFO }

@Composable
private fun Mark.color(): Color = when (this) {
    Mark.OK -> LocalColors.current.good
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

/** 0 · Android 17+ sin permiso de la red local: sin él la pantalla no puede hablar con el celular. */
@Composable
private fun AskLocalNetwork(link: PhoneLink) {
    val c = LocalColors.current
    val ask = rememberPermissionAsk(LOCAL_NETWORK_PERMISSION) { link.permissionChanged() }
    StatusTitle(Mark.WARN, stringResource(R.string.phone_permission))
    Label(stringResource(R.string.phone_permission_body), Typography.body, c.textMuted)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PermissionButtons(ask, stringResource(R.string.local_network_allow))
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
        Label(pluralStringResource(R.plurals.phone_link_code, OFFER_MINUTES, code.toList().joinToString(" "), OFFER_MINUTES), Typography.body, c.textMuted, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
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
private fun Linked(d: Dependencies) {
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
        LinkRow(link)
        Divider()
        SoundRow(d)
        Divider()
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // Buscar solo sirve sin conexión (conectado no hace nada que se vea).
        if (!link.connected) Button(stringResource(R.string.phone_search_now), link::searchNow, primary = true)
        val unlink = stringResource(R.string.phone_unlink)
        Confirmed(unlink, stringResource(R.string.phone_unlink_question), unlink, stringResource(R.string.phone_unlink_message), action = link::unlink)
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

/** Enlace: la conexión con LiteDash Connect. */
@Composable
private fun LinkRow(link: PhoneLink) {
    val context = LocalContext.current
    val title = stringResource(R.string.phone_link)
    when {
        link.connected && link.musicBlocked -> CheckRow(Mark.WARN, title, stringResource(R.string.phone_music_blocked))
        link.connected -> CheckRow(Mark.OK, title, stringResource(R.string.phone_link_ok))
        !link.localNetwork -> CheckRow(Mark.WARN, title, stringResource(R.string.phone_link_no_network)) {
            Button(stringResource(R.string.phone_open_wifi), { openWifi(context) })
        }
        else -> CheckRow(Mark.WARN, title, stringResource(R.string.phone_link_open_app))
    }
}

/**
 * Sonido: por dónde llega al auto lo que suena en el celular. Manda lo que dice el propio celular (vale en
 * cualquier pantalla, también con chip de Bluetooth propio); sin ese dato, lo que vea el Bluetooth de Android
 * de la pantalla, y si tampoco se sabe, se dice que no se sabe.
 */
@Composable
private fun SoundRow(d: Dependencies) {
    val context = LocalContext.current
    val title = stringResource(R.string.phone_sound)
    val seen = d.music.bluetoothDevice
    val connect: @Composable () -> Unit = {
        // Sin app de Bluetooth del estéreo ni pantalla de Bluetooth de Android: los ajustes de Android (nada de Música).
        Button(stringResource(R.string.phone_connect_bt), { if (!openStereoBluetooth(d, context)) openSystemScreen(context, Intent(Settings.ACTION_SETTINGS)) })
    }
    when (d.phone.phoneSound) {
        PhoneSound.BLUETOOTH -> {
            val name = seen ?: d.phone.phoneName?.ifBlank { null }
            CheckRow(Mark.OK, title, name?.let { stringResource(R.string.phone_sound_ok, it) } ?: stringResource(R.string.phone_sound_ok_plain))
        }
        // Android Auto o un cable llevan el audio: no hace falta el Bluetooth.
        PhoneSound.CAR -> CheckRow(Mark.OK, title, stringResource(R.string.phone_sound_car))
        PhoneSound.WIRED -> CheckRow(Mark.OK, title, stringResource(R.string.phone_sound_wired))
        // El celular se conectó solo para llamadas: se arregla desde el estéreo, y ahí lleva el botón.
        PhoneSound.CALLS -> CheckRow(Mark.WARN, title, stringResource(R.string.phone_sound_calls), connect)
        PhoneSound.PHONE -> CheckRow(Mark.WARN, title, stringResource(R.string.phone_sound_missing), connect)
        null -> when {
            seen != null -> CheckRow(Mark.OK, title, stringResource(R.string.phone_sound_ok, seen))
            d.music.phoneKnowable -> CheckRow(Mark.WARN, title, stringResource(R.string.phone_sound_missing), connect)
            else -> CheckRow(Mark.INFO, title, stringResource(R.string.phone_sound_unknown))
        }
    }
}

/**
 * Abre donde se conecta el celular al estéreo: con chip de Bluetooth propio, la app de Bluetooth del estéreo
 * (la misma que usa la música); si no, los ajustes de Bluetooth de Android. Solo Bluetooth: Android Auto / CarPlay
 * (ZLink y parecidas) tiene su propio botón. Falso si no se pudo abrir nada.
 */
internal fun openStereoBluetooth(d: Dependencies, context: Context): Boolean {
    if (!d.music.bluetoothBuiltIn && d.music.openBluetoothApp()) return true
    return openSystemScreen(context, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
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

/** Lo que vive el código del QR, en minutos (lo decide el enlace). */
private val OFFER_MINUTES = (PhoneLink.OFFER_MS / 60_000).toInt()
