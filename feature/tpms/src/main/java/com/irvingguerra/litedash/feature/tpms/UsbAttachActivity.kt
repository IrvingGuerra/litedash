package com.irvingguerra.litedash.feature.tpms

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle

/**
 * Sin pantalla: existe solo para que Android ofrezca "usar siempre LiteDash" al conectar un
 * receptor de llantas o un cable OBD (filtro `@xml/usb_devices` = [TpmsRepository.USB_IDS]).
 * Con eso el permiso queda dado y no se pide en cada encendido. No abre el inicio ni nada:
 * quien use el cable ya escucha la conexión por su cuenta.
 *
 * Viene apagada en el manifiesto: solo se prende ([offer]) mientras las llantas usan USB o hay
 * un cable OBD USB guardado. Así, con los módulos apagados, conectar cualquier chip serie
 * (CH340, FTDI…) no ofrece abrir LiteDash.
 */
class UsbAttachActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }

    companion object {
        /** Prende o apaga la actividad (solo si cambia: es una llamada al sistema). */
        fun offer(context: Context, on: Boolean) {
            val pm = context.packageManager
            val component = ComponentName(context, UsbAttachActivity::class.java)
            val wanted = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            runCatching {
                if (pm.getComponentEnabledSetting(component) != wanted) pm.setComponentEnabledSetting(component, wanted, PackageManager.DONT_KILL_APP)
            }
        }
    }
}
