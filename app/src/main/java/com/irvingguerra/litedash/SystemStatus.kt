package com.irvingguerra.litedash

import android.app.Instrumentation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.view.KeyEvent
import android.os.SystemClock
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.irvingguerra.litedash.core.ui.Radius
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography
import kotlin.concurrent.thread

/** Avisos del sistema para LiteDash: escucha solo mientras se ve (con otra app al frente, nada). */
private fun Context.listen(receiver: BroadcastReceiver, action: String) =
    ContextCompat.registerReceiver(this, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)

/**
 * Volumen de la música en la barra: un solo botón (bocina con el nivel). Al tocarlo flota su barra
 * hacia arriba (+ barra −; a un lado en la barra lateral) y se cierra sola a los [VOLUME_OPEN_MS] sin
 * tocarla o al tocar fuera. Con el volumen del chip de la pantalla (fijo para Android) solo + y −.
 */
@Composable
fun Volume(buttonSize: Dp, vertical: Boolean = false) {
    val c = LocalColors.current
    val context = LocalContext.current
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    val max = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var level by remember { mutableIntStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    var open by remember { mutableStateOf(false) }
    // Cada cambio reinicia la cuenta para cerrarse.
    var touched by remember { mutableIntStateOf(0) }
    LifecycleStartEffect(audio) {
        level = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                level = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            }
        }
        context.listen(receiver, VOLUME_CHANGED)
        onStopOrDispose {
            context.unregisterReceiver(receiver)
            open = false
        }
    }
    LaunchedEffect(open, touched) {
        if (open) {
            delay(VOLUME_OPEN_MS)
            open = false
        }
    }
    fun adjust(direction: Int) {
        touched++
        val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val atEdge = if (direction == AudioManager.ADJUST_RAISE) before >= max else before <= 0
        if (!audio.isVolumeFixed) audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        level = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        // Muchas pantallas manejan el volumen con su propio chip (Android lo tiene fijo o no lo deja
        // cambiar: pasaba en una NWD). Entonces, la tecla de volumen como el botón físico: la toma el
        // sistema y la lleva a ese chip (y muestra su propia barra de volumen).
        if (level == before && !atEdge || audio.isVolumeFixed) volumeKey(if (direction == AudioManager.ADJUST_RAISE) KeyEvent.KEYCODE_VOLUME_UP else KeyEvent.KEYCODE_VOLUME_DOWN)
    }
    fun set(fraction: Float) {
        touched++
        val target = (fraction * max + 0.5f).toInt().coerceIn(0, max)
        if (target == level) return
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
        level = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    }
    val fixed = audio.isVolumeFixed
    // Tocar fuera cierra la barra; si ese toque fue en la bocina, no debe volver a abrirla.
    var closedAt by remember { mutableLongStateOf(0L) }
    Box {
        Box(
            Modifier.size(buttonSize).clip(CircleShape).background(if (open) c.surfaceHigh else Color.Transparent).clickable {
                open = !open && SystemClock.uptimeMillis() - closedAt > REOPEN_GUARD_MS
            },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.volume, c.text, buttonSize * 0.45f)
            // El nivel en chico, en la esquina de la bocina (no con volumen fijo).
            if (!fixed) Label(
                level.toString(),
                Typography.small.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                c.textMuted,
                maxLines = 1,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = buttonSize * 0.14f, bottom = buttonSize * 0.12f),
            )
        }
        // Flota encima de la barra (o a un lado de la lateral): no le quita lugar a las apps.
        if (open) Popup(
            popupPositionProvider = remember(vertical) { VolumePopupPosition(beside = vertical) },
            onDismissRequest = {
                open = false
                closedAt = SystemClock.uptimeMillis()
            },
            // Enfocable: el toque de afuera solo la cierra (no abre lo que haya abajo).
            properties = PopupProperties(focusable = true, dismissOnClickOutside = true),
        ) {
            Column(
                Modifier
                    .padding(6.dp)
                    .shadow(12.dp, RoundedCornerShape(Radius.card))
                    .clip(RoundedCornerShape(Radius.card))
                    .background(c.surfaceHigh)
                    .padding(vertical = 8.dp, horizontal = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                IconButton(Icons.plus, { adjust(AudioManager.ADJUST_RAISE) }, size = 48.dp)
                if (!fixed) {
                    VolumeBar(level.toFloat() / max, ::set)
                    Label(level.toString(), Typography.subtitle, maxLines = 1, modifier = Modifier.padding(vertical = 4.dp))
                }
                IconButton(Icons.minus, { adjust(AudioManager.ADJUST_LOWER) }, size = 48.dp)
            }
        }
    }
}

/**
 * Dónde flota la barra del volumen: centrada arriba de la bocina; con [beside] (barra lateral), a un
 * lado de ella (a la derecha, o a la izquierda si no cabe). Nunca se sale de la ventana.
 */
private class VolumePopupPosition(private val beside: Boolean) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val w = popupContentSize.width
        val h = popupContentSize.height
        val (x, y) = if (beside) {
            val right = anchorBounds.right
            (if (right + w <= windowSize.width) right else anchorBounds.left - w) to anchorBounds.center.y - h / 2
        } else {
            anchorBounds.center.x - w / 2 to anchorBounds.top - h
        }
        return IntOffset(x.coerceIn(0, maxOf(0, windowSize.width - w)), y.coerceIn(0, maxOf(0, windowSize.height - h)))
    }
}

/** La barra del volumen: se toca o se arrastra a cualquier punto; lleno = [fraction]. */
@Composable
private fun VolumeBar(fraction: Float, onChange: (Float) -> Unit) {
    val c = LocalColors.current
    val change by rememberUpdatedState(onChange)
    fun at(offset: Offset, size: IntSize) = 1f - offset.y / size.height.coerceAtLeast(1)
    Canvas(
        Modifier.width(48.dp).height(VOLUME_BAR_LENGTH)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    change(at(down.position, size).coerceIn(0f, 1f))
                    while (true) {
                        val move = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!move.pressed) break
                        move.consume()
                        change(at(move.position, size).coerceIn(0f, 1f))
                    }
                }
            },
    ) {
        // Como la de iOS: una píldora ancha que se llena desde abajo.
        val thick = 14.dp.toPx()
        val x = size.width / 2
        val top = thick / 2
        val bottom = size.height - thick / 2
        val y = bottom - (bottom - top) * fraction.coerceIn(0f, 1f)
        drawLine(c.line, Offset(x, top), Offset(x, bottom), thick, StrokeCap.Round)
        drawLine(c.primary, Offset(x, y), Offset(x, bottom), thick, StrokeCap.Round)
    }
}

/** Lo que la barra del volumen sigue abierta sin tocarla. */
private const val VOLUME_OPEN_MS = 4_000L

private val VOLUME_BAR_LENGTH = 160.dp

/** Un toque en la bocina justo después de cerrarse la barra (tocando fuera) no la vuelve a abrir. */
private const val REOPEN_GUARD_MS = 300L

/**
 * Aprieta la tecla de volumen dentro de la ventana de LiteDash (se permite sin permisos porque es la
 * propia app): el sistema la intercepta antes, como la del volante o la del panel. En otro hilo:
 * Instrumentation no se puede usar en el principal.
 */
private fun volumeKey(code: Int) {
    thread(name = "volume-key") { runCatching { Instrumentation().sendKeyDownUpSync(code) } }
}

/** El sistema lo manda al cambiar cualquier volumen; no está en el SDK público. */
private const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"

/**
 * Wi-Fi o datos, audio Bluetooth conectado y ubicación encendida, en la barra.
 * Escucha al sistema solo mientras se ve (nada de revisar cada rato) y sin
 * permisos: el Bluetooth se nota por el audio conectado (tu celular, el estéreo).
 */
@Composable
fun StatusIcons() {
    val c = LocalColors.current
    val context = LocalContext.current
    var network by remember { mutableStateOf<Boolean?>(null) } // true Wi-Fi, false datos, null nada
    var bluetooth by remember { mutableStateOf(false) }
    var location by remember { mutableStateOf(false) }
    LifecycleStartEffect(Unit) {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        // Siempre se lee la red activa: Android 6 y 7 no avisan cómo está al registrarse (solo
        // cambios) y, escuchando cualquier red, perder una no quiere decir quedarse sin internet.
        fun check(lost: Network? = null) {
            // Al perder una red, Android 6 puede seguir dándola como activa un instante.
            val caps = runCatching { connectivity.activeNetwork?.takeIf { it != lost }?.let(connectivity::getNetworkCapabilities) }.getOrNull()
            network = when {
                caps == null -> null
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> false
                else -> null
            }
        }
        val netCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) = check()
            override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) = check()
            override fun onLost(n: Network) = check(lost = n)
        }
        runCatching {
            // Android 6 no tiene "la red de ahora": se escucha cualquier red con internet.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) connectivity.registerDefaultNetworkCallback(netCallback)
            else connectivity.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), netCallback)
        }
        check()

        val audio = context.getSystemService(AudioManager::class.java)
        fun checkAudio() {
            bluetooth = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
        }
        val audioCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = checkAudio()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = checkAudio()
        }
        audio.registerAudioDeviceCallback(audioCallback, null)
        checkAudio()

        val locations = context.getSystemService(LocationManager::class.java)
        fun checkLocation() {
            location = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) locations.isLocationEnabled
            else locations.isProviderEnabled(LocationManager.GPS_PROVIDER)
        }
        val locationReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = checkLocation()
        }
        context.listen(locationReceiver, LocationManager.PROVIDERS_CHANGED_ACTION)
        checkLocation()

        onStopOrDispose {
            runCatching { connectivity.unregisterNetworkCallback(netCallback) }
            audio.unregisterAudioDeviceCallback(audioCallback)
            context.unregisterReceiver(locationReceiver)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (bluetooth) Icon(Icons.bluetooth, c.textMuted, 20.dp)
        if (location) Icon(Icons.location, c.textMuted, 20.dp)
        when (network) {
            true -> Icon(Icons.wifi, c.textMuted, 20.dp)
            false -> Icon(Icons.signal, c.textMuted, 20.dp)
            null -> Unit
        }
    }
}
