package com.irvingguerra.litedash.feature.tpms

import android.app.Activity
import android.os.Bundle

/**
 * Sin pantalla: existe solo para que Android ofrezca "usar siempre LiteDash" al conectar un
 * receptor de llantas o un cable OBD (filtro `@xml/usb_devices` = [TpmsRepository.USB_IDS]).
 * Con eso el permiso queda dado y no se pide en cada encendido. No abre el inicio ni nada:
 * quien use el cable ya escucha la conexión por su cuenta.
 */
class UsbAttachActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
