package com.irvingguerra.litedash.feature.music

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import java.util.Locale

/**
 * La radio FM de las pantallas NWD (Allwinner T3/T5 y otras; app de fábrica `com.nwd.radio`), que no
 * publica sesión, receptor ni servicio. Lo que se sabe sale de OpenRadioFM (kapi21/OpenRadioFM,
 * Apache-2.0, `QS6Engine`/`NWDTunerAdapter`):
 * - La estación la deja el sistema en Settings.System: `nwd_radio_current_freq` (FM en decenas de kHz:
 *   9810 = 98.1; AM en kHz), `nwd_radio_current_band` (0–2 FM, 3+ AM) y `nwd_radio_current_ps_data`
 *   (nombre RDS en hexadecimal); la fuente de audio del MCU en `mcu_current_source` (4 = radio). Se
 *   escucha con un ContentObserver mientras la pieza Radio está a la vista (sin polling).
 * - Siguiente/anterior: los avisos `com.nwd.action.ACTION_SEARCH_UP/DOWN` (busca la estación que sigue).
 * - Play sin abrir la app: pedir la fuente de la radio (0x04) con `ACTION_REQUEST_CHANGE_SOURCE` y
 *   `ACTION_CHANGE_SOURCE`, y avisar que "entra" la app de radio (id 8) con `ACTION_APP_IN_OUT`, como
 *   OpenRadioFM. Pausa: la fuente de Android (0x00) y que "sale".
 */
internal class NwdRadio(
    private val context: Context,
    private val onState: (BroadcastMeta) -> Unit,
    private val trace: (String) -> Unit,
) : FactoryRadio {
    override val tag = "nwd"
    override var open = false
        private set
    private var frequency = 0
    private var band = 0
    override val station: String?
        get() = when {
            frequency <= 0 -> null
            band >= BAND_AM -> "AM $frequency"
            else -> "FM " + String.format(Locale.US, "%.1f", frequency / 100f)
        }
    override var name = ""
        private set
    override var on: Boolean? = null
        private set
    override var audioAt = 0L
        private set

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = refresh()
    }

    override fun open(): Boolean {
        if (open) return true
        val cr = context.contentResolver
        // Si el sistema no tiene la frecuencia, no es una NWD (o no deja leerla).
        if (runCatching { Settings.System.getString(cr, FREQ) }.getOrNull() == null) {
            trace("nwd no abre: sin $FREQ")
            return false
        }
        for (key in listOf(FREQ, BAND, PS, SOURCE)) runCatching { cr.registerContentObserver(Settings.System.getUriFor(key), false, observer) }
        open = true
        trace("nwd abierto")
        refresh()
        return true
    }

    override fun close() {
        if (!open) return
        open = false
        runCatching { context.contentResolver.unregisterContentObserver(observer) }
    }

    override fun refresh() {
        val cr = context.contentResolver
        band = runCatching { Settings.System.getInt(cr, BAND, band) }.getOrDefault(band)
        frequency = runCatching { Settings.System.getInt(cr, FREQ, frequency) }.getOrDefault(frequency)
        name = decodePs(runCatching { Settings.System.getString(cr, PS) }.getOrNull())
        // Sin la clave de la fuente no se sabe si suena: se queda lo último que se supo.
        runCatching { Settings.System.getInt(cr, SOURCE) }.getOrNull()?.let { src ->
            val radio = src == SOURCE_RADIO
            if (radio && on != true) audioAt = SystemClock.elapsedRealtime()
            on = radio
        }
        publish()
    }

    override fun next() = send(Intent(SEARCH_UP))
    override fun previous() = send(Intent(SEARCH_DOWN))

    override fun takeAudio() {
        source(SOURCE_RADIO)
        send(Intent(APP_IN_OUT).putExtra("extra_app_id", APP_RADIO).putExtra("extra_app_operation", 1).putExtra("extra_app_event", 0))
    }

    override fun releaseAudio() {
        source(SOURCE_ANDROID)
        send(Intent(APP_IN_OUT).putExtra("extra_app_id", APP_RADIO).putExtra("extra_app_operation", 0))
        on = false
        publish()
    }

    override fun started() {
        on = true
        refresh()
    }

    private fun source(id: Int) {
        send(Intent(REQUEST_CHANGE_SOURCE).putExtra("extra_source_id", id.toByte()))
        send(Intent(CHANGE_SOURCE).putExtra("extra_source_id", id.toByte()))
    }

    private fun send(intent: Intent) {
        runCatching { context.sendBroadcast(intent) }.onFailure { trace("nwd aviso ${intent.action} falló") }
    }

    private fun publish() {
        val text = station
        if (text == null && name.isEmpty()) return
        onState(BroadcastMeta(title = name.ifEmpty { text }, artist = text.takeIf { name.isNotEmpty() }, album = null, playing = on ?: false, pkg = null, bluetooth = false))
    }

    companion object {
        const val PACKAGE = "com.nwd.radio"
        private const val FREQ = "nwd_radio_current_freq"
        private const val BAND = "nwd_radio_current_band"
        private const val PS = "nwd_radio_current_ps_data"
        private const val SOURCE = "mcu_current_source"
        private const val BAND_AM = 3
        private const val SOURCE_RADIO = 0x04
        private const val SOURCE_ANDROID = 0x00
        private const val APP_RADIO = 8
        private const val SEARCH_UP = "com.nwd.action.ACTION_SEARCH_UP"
        private const val SEARCH_DOWN = "com.nwd.action.ACTION_SEARCH_DOWN"
        private const val REQUEST_CHANGE_SOURCE = "com.nwd.action.ACTION_REQUEST_CHANGE_SOURCE"
        private const val CHANGE_SOURCE = "com.nwd.action.ACTION_CHANGE_SOURCE"
        private const val APP_IN_OUT = "com.nwd.action.ACTION_APP_IN_OUT"

        /** El nombre RDS viene en hexadecimal (8 letras = 16 cifras); "" si no hay o no se entiende. */
        internal fun decodePs(hex: String?): String {
            val h = hex?.trim()?.takeIf { it.length >= 2 && it.length % 2 == 0 } ?: return ""
            val bytes = runCatching { h.chunked(2).map { it.toInt(16) } }.getOrNull() ?: return ""
            return bytes.filter { it in 0x20..0x7E }.map { it.toChar() }.joinToString("").trim()
        }

        /** Lo que hay de NWD en el sistema, para los reportes (sin abrir nada). */
        fun describe(context: Context): String {
            val cr = context.contentResolver
            fun v(key: String) = runCatching { Settings.System.getString(cr, key) }.getOrNull() ?: "-"
            return "NWD: frecuencia=${v(FREQ)} banda=${v(BAND)} rds=${decodePs(v(PS).takeIf { it != "-" }).ifEmpty { "-" }} fuente=${v(SOURCE)}"
        }
    }
}
