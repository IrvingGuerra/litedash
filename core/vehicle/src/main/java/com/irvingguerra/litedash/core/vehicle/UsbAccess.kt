package com.irvingguerra.litedash.core.vehicle

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat

// Cables USB (OBD y receptor de llantas): pedir permiso y saber si se desenchufan, igual en los dos.

/**
 * Pide permiso para [device] (Android muestra su diálogo). [onResult] llega una sola vez, en el hilo
 * principal. Regresa cómo cancelar: quita el receptor, así una respuesta tardía (el módulo ya se
 * apagó) no hace nada. [action] distingue a quién pidió.
 */
fun requestUsbPermission(
    context: Context,
    manager: UsbManager,
    device: UsbDevice,
    action: String,
    requestCode: Int,
    onResult: (granted: Boolean) -> Unit,
): () -> Unit {
    var receiver: BroadcastReceiver? = null
    val cancel = {
        receiver?.let { context.unregisterReceiver(it) }
        receiver = null
    }
    receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (receiver == null) return
            cancel()
            onResult(i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
        }
    }
    ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
    val intent = Intent(action).setPackage(context.packageName)
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
    manager.requestPermission(device, PendingIntent.getBroadcast(context, requestCode, intent, flags))
    return cancel
}

/** Avisa cuando se desenchufa un aparato USB (en el hilo principal). Regresa cómo dejar de escuchar. */
fun watchUsbDetached(context: Context, onDetached: (UsbDevice) -> Unit): () -> Unit =
    watchUsb(context, UsbManager.ACTION_USB_DEVICE_DETACHED, onDetached)

/** Avisa cuando se enchufa un aparato USB (o la pantalla lo vuelve a ver al despertar). Regresa cómo dejar de escuchar. */
fun watchUsbAttached(context: Context, onAttached: (UsbDevice) -> Unit): () -> Unit =
    watchUsb(context, UsbManager.ACTION_USB_DEVICE_ATTACHED, onAttached)

private fun watchUsb(context: Context, action: String, onDevice: (UsbDevice) -> Unit): () -> Unit {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            IntentCompat.getParcelableExtra(i, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)?.let(onDevice)
        }
    }
    // Es un aviso del sistema: tiene que poder llegar de fuera de la app.
    ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_EXPORTED)
    return { context.unregisterReceiver(receiver) }
}
