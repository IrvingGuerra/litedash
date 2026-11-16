package com.irvingguerra.litedash.feature.music

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.provider.Settings

/**
 * La radio FM de las pantallas NWD (Allwinner T3/K2401 y otras; app de fábrica `com.nwd.radio`), que no
 * publica sesión, receptor ni servicio de música. La app que se ve es solo la cara; la radio son dos
 * servicios del sistema, y se les habla como lo hacen OpenRadioFM (kapi21, Apache-2.0: `NWDTunerAdapter`,
 * `QS6Engine`) y Radio+ (edman01, MIT: `NwdRadioApi`, `NwdAudioRouting`):
 * - **El sintonizador** `com.nwd.radio.service/.RadioService` (Binder `com.nwd.radio.service.RadioFeature`):
 *   la estación (`getCurrentFrequency`), buscar la siguiente o la anterior (`search`), el tipo de
 *   sintonizador (`getRadioType`: 2 = Allwinner, que suena por Android; 0 = el MCU) y el aviso de cada
 *   cambio de estación (`registCallback` → `RadioCallback.notifyCurrentFrequency`), también del volante.
 * - **El kernel** `com.nwd.kernel`, que decide qué suena: se le pide la fuente de la radio (4) o la de
 *   Android (0) con `ACTION_REQUEST_CHANGE_SOURCE` dirigido a él (un aviso suelto puede no llegarle).
 *   Si no es del MCU, además se avisa al sintonizador que entra la radio (`ACTION_APP_IN_OUT`, id 8) y,
 *   al pausar, que sale (`ACTION_EXIT_ARM_FM_RAIDO`, con la errata del firmware).
 * - De respaldo (sin el servicio o si no contesta), lo que se hacía antes: la estación en Settings.System
 *   (`nwd_radio_current_freq` en decenas de kHz, `_band`, `_ps_data` en hex) y los avisos sueltos
 *   `com.nwd.action.ACTION_SEARCH_UP/DOWN`. `mcu_current_source` (4 = radio) dice si suena, si existe.
 * Ver la pieza solo se conecta al sintonizador si ya está corriendo; se arranca al tocar un botón (arrancarlo
 * podría tomar el audio). Si no conecta y no hay ajustes, el perfil se cierra y la radio se abre como antes.
 */
internal class NwdRadio(
    context: Context,
    onState: (BroadcastMeta) -> Unit,
    trace: (String) -> Unit,
) : BinderFactoryRadio(context, onState, trace, "nwd", DESCRIPTOR, CALLBACK, CODE_REGISTER, CODE_UNREGISTER) {
    private var frequency = 0
    private var band = 0
    override val station get() = stationText(band >= BAND_AM, frequency)

    // Se crea en el hilo de los perfiles: el PackageManager no se consulta en el principal.
    private val tunerInstalled = context.hasPackage(SERVICE_PACKAGE)
    private val kernel = context.hasPackage(KERNEL_PACKAGE)

    /** 2 = Allwinner, 0 = MCU, -1 = no se sabe (sin servicio). */
    @Volatile var tunerType = -1
        private set
    private var settingsKeys = false

    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) = refresh()
    }

    override val serviceInstalled get() = tunerInstalled

    override fun serviceIntent(): Intent = Intent(SERVICE_ACTION).setComponent(ComponentName(SERVICE_PACKAGE, SERVICE_CLASS))

    /** `RadioCallback` del firmware: solo importa `notifyCurrentFrequency` (banda, frecuencia, nombre, presintonía). */
    override fun handlesCallback(code: Int) = code in 1..CALLBACK_LAST

    override fun readCallback(code: Int, data: Parcel): (() -> Unit)? {
        if (code != CALLBACK_FREQUENCY) return null
        val tuned = Tuned(data.readByte().toInt(), data.readInt(), data.readString().orEmpty().trim())
        return { show(tuned) }
    }

    override fun prepare(binder: IBinder): Boolean {
        tunerType = runCatching { call(CODE_RADIO_TYPE, binder = binder) { it.readInt() } }.getOrDefault(-1)
        return true
    }

    override fun connectedDetail() = "tipo=$tunerType"

    override fun ready() = readTuner()

    override fun open(): Boolean {
        if (open) return true
        val cr = context.contentResolver
        settingsKeys = runCatching { Settings.System.getString(cr, FREQ) }.getOrNull() != null
        if (!settingsKeys && !tunerInstalled) {
            trace("nwd no abre: sin $SERVICE_PACKAGE ni $FREQ")
            return false
        }
        // Sin ajustes y sin sintonizador que conecte, el enlace ya lo cerró: nada que escuchar.
        if (!connect()) return false
        for (key in listOf(FREQ, BAND, PS, SOURCE)) runCatching { cr.registerContentObserver(Settings.System.getUriFor(key), false, observer) }
        trace("nwd abierto: sintonizador=${yes(tunerInstalled)} ajustes=${yes(settingsKeys)} kernel=${yes(kernel)}")
        refresh()
        return true
    }

    override fun close() {
        if (!open) return
        runCatching { context.contentResolver.unregisterContentObserver(observer) }
        super.close()
    }

    /** Sin sintonizador: con los ajustes aún se ve y se controla; si tampoco hay, se cierra y la radio se abre como antes. */
    override fun lost() {
        if (!open || settingsKeys) return
        super.lost()
    }

    override fun refresh() {
        val cr = context.contentResolver
        // Con el sintonizador conectado, él dice la estación; los ajustes solo si suena.
        if (service == null && settingsKeys) {
            band = runCatching { Settings.System.getInt(cr, BAND, band) }.getOrDefault(band)
            frequency = runCatching { Settings.System.getInt(cr, FREQ, frequency) }.getOrDefault(frequency)
            name = decodePs(runCatching { Settings.System.getString(cr, PS) }.getOrNull())
        }
        // Sin la clave de la fuente no se sabe si suena: se queda lo último que se supo.
        runCatching { Settings.System.getInt(cr, SOURCE) }.getOrNull()?.let { src ->
            val radio = src == SOURCE_RADIO
            if (radio && on != true) audioAt = SystemClock.elapsedRealtime()
            on = radio
        }
        publish()
        if (service != null) worker?.execute(::readTuner)
    }

    /** La estación del sintonizador (en [worker]); se publica en el hilo principal. */
    private fun readTuner() {
        if (service == null) return
        val tuned = runCatching { call(CODE_FREQUENCY, read = ::readFrequency) }
            .onFailure { trace("nwd no dio la estación: ${it.javaClass.simpleName}") }
            .getOrNull() ?: return
        main.post { if (open) show(tuned) }
    }

    private fun show(tuned: Tuned) {
        band = tuned.band
        frequency = tuned.frequency
        name = tuned.name
        publish()
    }

    override fun next() = search(up = true)
    override fun previous() = search(up = false)

    private fun search(up: Boolean) {
        val w = worker
        if (service == null || w == null) {
            wake()
            return send(Intent(if (up) SEARCH_UP else SEARCH_DOWN))
        }
        w.execute {
            runCatching { call(CODE_SEARCH, { it.writeInt(if (up) 1 else 0) }) {} }.onFailure {
                trace("nwd búsqueda falló: se usa el aviso")
                send(Intent(if (up) SEARCH_UP else SEARCH_DOWN))
            }
        }
        // Sin el aviso del sintonizador, la búsqueda tarda: se vuelve a leer la estación.
        rereadSoon()
    }

    override fun takeAudio() {
        wake()
        source(SOURCE_RADIO)
        if (tunerType != TYPE_MCU) send(Intent(APP_IN_OUT).forTuner().putExtra("extra_app_id", APP_RADIO).putExtra("extra_app_operation", 1).putExtra("extra_app_event", 0))
    }

    override fun releaseAudio() {
        source(SOURCE_ANDROID)
        if (tunerType != TYPE_MCU) {
            if (tunerInstalled) send(Intent(EXIT_RADIO).setPackage(SERVICE_PACKAGE))
            else send(Intent(APP_IN_OUT).putExtra("extra_app_id", APP_RADIO).putExtra("extra_app_operation", 0))
        }
        on = false
        publish()
    }

    override fun started() {
        on = true
        refresh()
    }

    /** Sin la clave de la fuente del MCU (on = null), la NWD suena por Android: solo así se confirma un play. */
    override val confirmsByAudio get() = on == null

    /** Pide la fuente al kernel (dirigido); sin kernel, los dos avisos sueltos de antes. */
    private fun source(id: Int) {
        if (kernel) return send(Intent(REQUEST_CHANGE_SOURCE).setPackage(KERNEL_PACKAGE).putExtra("extra_source_id", id.toByte()))
        send(Intent(REQUEST_CHANGE_SOURCE).putExtra("extra_source_id", id.toByte()))
        send(Intent(CHANGE_SOURCE).putExtra("extra_source_id", id.toByte()))
    }

    /** Dirigido al sintonizador si está instalado (un aviso suelto puede no llegarle). */
    private fun Intent.forTuner() = if (tunerInstalled) setPackage(SERVICE_PACKAGE) else this

    /** También a una app del sistema que aún no ha corrido ("detenida": sin la bandera no le llega). */
    private fun send(intent: Intent) {
        runCatching { context.sendBroadcast(intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)) }
            .onFailure { trace("nwd aviso ${intent.action} falló") }
    }

    private data class Tuned(val band: Int, val frequency: Int, val name: String)

    /** `Frequency` del firmware: marca de no nulo, banda (byte), nombre RDS y frecuencia. */
    private fun readFrequency(p: Parcel): Tuned {
        check(p.readInt() == 1) { "sin estación" }
        val band = p.readByte().toInt()
        val name = p.readString().orEmpty().trim()
        return Tuned(band, p.readInt(), name)
    }

    companion object {
        const val PACKAGE = "com.nwd.radio"
        private const val SERVICE_PACKAGE = "com.nwd.radio.service"
        private const val SERVICE_CLASS = "com.nwd.radio.service.RadioService"
        private const val SERVICE_ACTION = "com.nwd.radio.service.ACTION_RADIO_SERVICE"
        private const val DESCRIPTOR = "com.nwd.radio.service.RadioFeature"
        private const val CALLBACK = "com.nwd.radio.service.RadioCallback"
        internal const val KERNEL_PACKAGE = "com.nwd.kernel"
        // Orden de los AIDL RadioFeature y RadioCallback (FIRST_CALL_TRANSACTION = 1).
        private const val CODE_FREQUENCY = 2
        private const val CODE_SEARCH = 4
        private const val CODE_REGISTER = 24
        private const val CODE_UNREGISTER = 25
        private const val CODE_RADIO_TYPE = 29
        private const val CALLBACK_FREQUENCY = 2
        private const val CALLBACK_LAST = 14
        private const val TYPE_MCU = 0

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
        private const val EXIT_RADIO = "com.nwd.android.ACTION_EXIT_ARM_FM_RAIDO"

        /** El nombre RDS viene en hexadecimal (8 letras = 16 cifras); "" si no hay o no se entiende. */
        internal fun decodePs(hex: String?): String {
            val h = hex?.trim()?.takeIf { it.length >= 2 && it.length % 2 == 0 } ?: return ""
            val bytes = runCatching { h.chunked(2).map { it.toInt(16) } }.getOrNull() ?: return ""
            return bytes.filter { it in 0x20..0x7E }.map { it.toChar() }.joinToString("").trim()
        }

        /** Lo que hay de NWD en el sistema, para los reportes (sin abrir nada); [radio] = el perfil abierto. */
        fun describe(context: Context, radio: NwdRadio?): String {
            val cr = context.contentResolver
            fun v(key: String) = runCatching { Settings.System.getString(cr, key) }.getOrNull() ?: "-"
            fun version(pkg: String) = runCatching { context.packageManager.getPackageInfo(pkg, 0).versionName }.getOrNull() ?: "no"
            return "NWD: frecuencia=${v(FREQ)} banda=${v(BAND)} rds=${decodePs(v(PS).takeIf { it != "-" }).ifEmpty { "-" }} fuente=${v(SOURCE)} " +
                "sintonizador=${version(SERVICE_PACKAGE)} kernel=${version(KERNEL_PACKAGE)} " +
                "perfil=${yes(radio?.open == true)} conectado=${yes(radio?.connected == true)} tipo=${radio?.tunerType ?: "-"} " +
                "avisos=${yes(radio?.notified == true)} estación=${radio?.station ?: "-"}"
        }
    }
}
