package com.irvingguerra.litedash.feature.music

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.view.KeyEvent

/**
 * La radio FM de las pantallas Hengchen (HCN: Junsun/Jansun V1 y V7, UIS8581A, MT8163 y otras; app
 * `com.hcn.autoradio`). Su app sí atiende las teclas de medios (siguiente/anterior funcionaban), pero no
 * dice la estación y el play no la arranca si está cerrada. Se le habla a su servicio `FMPlugService`
 * (Binder `com.hcn.autoradio.IRadioServiceAPI`) como Radio+ (edman01, MIT: `RadioBackendContract`,
 * `LegacyHcnRadioApi`) y OpenRadioFM (kapi21, Apache-2.0: `MT8163Engine`, variante "8581a"):
 * - **Dos contratos con el mismo nombre**: el de 31 llamadas (V7) y el de 25 (MT8163). Iguales hasta la 16;
 *   de la 17 en adelante cambian (en el de 31 la 17 guarda favorita; en el de 25 la 25 pone a sonar). Se
 *   distinguen SOLO leyendo: la 19 es la frecuencia en el de 31 y `IsAS` (0/1) en el de 25, donde la 18 es la
 *   frecuencia (en el de 31, la banda 0–3). Si ninguna da una frecuencia creíble, el perfil se cierra.
 * - La estación: `getCurrentFreq` y el aviso `IRadioCallBack.onEvent` (100 = frecuencia, 101 = banda,
 *   103 = nombre RDS); en el de 31 también `getCurrentFreqRdsPs`.
 * - Play sin abrir la app: `requestPlayAudio` (y en el de 31 `requestAudioFocus`, que además le quita el
 *   silencio). Pausa: `releaseAudioFocus` en el de 31; en el de 25 no existe y va la tecla de pausa.
 * - Siguiente/anterior siguen siendo las teclas a su receptor (ya funcionaban y son lo que la app hace con el
 *   volante); el aviso o una relectura dicen la estación nueva.
 * Ver el widget solo se conecta si su servicio ya corre; se arranca al tocar play (arrancarlo puede tomar el audio).
 */
internal class HcnRadio(
    context: Context,
    onState: (BroadcastMeta) -> Unit,
    trace: (String) -> Unit,
) : BinderFactoryRadio(context, onState, trace, "hcn", DESCRIPTOR, CALLBACK, CODE_REGISTER_CALLBACK, CODE_UNREGISTER_CALLBACK) {
    /** Frecuencia en centésimas de MHz (FM) o kHz (AM), como [stationText]. */
    private var frequency = 0
    private var am = false
    override val station get() = stationText(am, frequency)

    /** 31 o 25 llamadas; 0 = aún no se sabe. */
    @Volatile var contract = 0
        private set
    /** Cuál de [ACTIONS] se está usando: si una no da la interfaz, se prueba la siguiente. */
    private var action = 0
    /** Se pidió play antes de conectar (y cuándo): se da al conectar si no pasó mucho. */
    @Volatile private var playAskedAt = 0L

    override fun serviceIntent(): Intent =
        Intent(ACTIONS[action]).setComponent(ComponentName(PACKAGE, SERVICE_CLASS)).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)

    override fun handlesCallback(code: Int) = code == CALLBACK_EVENT

    override fun readCallback(code: Int, data: Parcel): () -> Unit {
        val event = data.readInt()
        val value = data.readString().orEmpty().trim()
        return { onEvent(event, value) }
    }

    override fun prepare(binder: IBinder): Boolean {
        // Recién arrancado puede no haber sintonizado aún (frecuencia 0): se le da un momento.
        val found = contractOf(if (created) DETECT_TRIES else 1, { Thread.sleep(DETECT_PAUSE_MS) }) { code ->
            runCatching { call(code, binder = binder) { it.readInt() } }.getOrNull()
        }
        if (found == 0) {
            trace("hcn contrato desconocido: se abre la app como antes")
            return false
        }
        contract = found
        return true
    }

    override fun connectedDetail() = "contrato=$contract"

    override fun ready() {
        // Primero el play pendiente (la confirmación espera poco) y luego la estación.
        val asked = playAskedAt
        playAskedAt = 0L
        if (asked > 0 && SystemClock.elapsedRealtime() - asked < PLAY_PENDING_MS) requestPlay()
        readTuner()
    }

    /** La acción no sirvió (la de la variante "8581a" es otra para el mismo servicio): la siguiente; sin más, se cierra. */
    override fun unusable() {
        if (!open) return
        if (action + 1 >= ACTIONS.size) return lost()
        action++
        bind(created)
    }

    override fun open(): Boolean {
        if (!connect()) return false
        trace("hcn abierto")
        return true
    }

    override fun refresh() {
        publish()
        if (service != null) worker?.execute(::readTuner)
    }

    /** La estación del servicio (en [worker]); se publica en el hilo principal. */
    private fun readTuner() {
        if (service == null) return
        val raw = runCatching { call(if (contract == 31) CODE_FREQ_31 else CODE_FREQ_25) { it.readInt() } }
            .onFailure { trace("hcn no dio la estación: ${it.javaClass.simpleName}") }
            .getOrNull() ?: return
        val ps = if (contract == 31) runCatching { call(CODE_RDS_PS_31) { it.readString() } }.getOrNull().orEmpty().trim() else null
        main.post {
            if (!open) return@post
            hcnFrequency(raw)?.let { (isAm, f) -> am = isAm; frequency = f }
            if (ps != null) name = ps
            publish()
        }
    }

    /** Un aviso del servicio: la frecuencia nueva o el nombre RDS; lo demás se relee. */
    private fun onEvent(event: Int, value: String) {
        when (event) {
            EVENT_FREQUENCY -> value.toIntOrNull()?.let(::hcnFrequency)?.let { (isAm, f) ->
                if (f != frequency) name = "" // el nombre RDS era de la estación anterior
                am = isAm; frequency = f
                publish()
            }
            EVENT_RDS_NAME -> { name = value; publish() }
            EVENT_BAND -> refresh()
        }
    }

    override fun next() = key(KeyEvent.KEYCODE_MEDIA_NEXT)
    override fun previous() = key(KeyEvent.KEYCODE_MEDIA_PREVIOUS)

    /** La tecla a su receptor (lo que ya funcionaba); sin aviso del servicio, la estación se relee. */
    private fun key(code: Int) {
        sendKey(code)
        rereadSoon()
    }

    override fun takeAudio() {
        if (service == null) {
            playAskedAt = SystemClock.elapsedRealtime()
            return wake()
        }
        worker?.execute(::requestPlay)
    }

    /** Pide la fuente de la radio (en [worker]); si la pantalla dice que sí, ya suena. */
    private fun requestPlay() {
        if (service == null) return
        val code = if (contract == 31) CODE_PLAY_31 else CODE_PLAY_25
        val ok = runCatching { call(code) { it.readInt() != 0 } }
            .onFailure { trace("hcn play falló: ${it.javaClass.simpleName}") }
            .getOrDefault(false)
        // En el de 31 el foco es también lo que le quita el silencio.
        if (ok && contract == 31) runCatching { call(CODE_FOCUS_31) {} }.onFailure { trace("hcn foco falló") }
        trace("hcn play: ${if (ok) "suena" else "no"}")
        if (!ok) return
        main.post {
            if (!open) return@post
            on = true
            audioAt = SystemClock.elapsedRealtime()
            publish()
        }
    }

    override fun releaseAudio() {
        playAskedAt = 0L
        if (service != null && contract == 31) {
            worker?.execute { runCatching { call(CODE_RELEASE_FOCUS_31) {} }.onFailure { sendKey(KeyEvent.KEYCODE_MEDIA_PAUSE) } }
        } else {
            sendKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
        }
        on = false
        publish()
    }

    override fun started() {
        on = true
        refresh()
    }

    /** Una tecla de medios (abajo y arriba) a su receptor, como el volante. */
    private fun sendKey(code: Int) {
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(PACKAGE).putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(action, code))
            runCatching { context.sendBroadcast(intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)) }
        }
    }

    companion object {
        const val PACKAGE = "com.hcn.autoradio"
        private const val SERVICE_CLASS = "com.hcn.autoradio.service.FMPlugService"
        /** La acción de siempre y la de la variante "8581a". */
        private val ACTIONS = listOf("com.hcn.autoradio.FM_PLUG_SERVICE", "com.hcn.radio.FM_PLUG_SERVICE")
        private const val DESCRIPTOR = "com.hcn.autoradio.IRadioServiceAPI"
        private const val CALLBACK = "com.hcn.autoradio.IRadioCallBack"
        // Llamadas (FIRST_CALL_TRANSACTION = 1). Iguales en los dos contratos hasta la 16.
        private const val CODE_REGISTER_CALLBACK = 3
        private const val CODE_UNREGISTER_CALLBACK = 4
        private const val CODE_FREQ_25 = 18
        private const val CODE_PLAY_25 = 25
        private const val CODE_FREQ_31 = 19
        private const val CODE_RDS_PS_31 = 20
        private const val CODE_PLAY_31 = 29
        private const val CODE_FOCUS_31 = 30
        private const val CODE_RELEASE_FOCUS_31 = 31
        private const val CALLBACK_EVENT = 1
        private const val EVENT_FREQUENCY = 100
        private const val EVENT_BAND = 101
        private const val EVENT_RDS_NAME = 103
        /** Un play pedido antes de conectar se da solo si conecta en este rato (si no, ya se abrió la app). */
        private const val PLAY_PENDING_MS = 1_200L
        private const val DETECT_TRIES = 3
        private const val DETECT_PAUSE_MS = 250L

        /**
         * Cuál de los dos contratos es (31 o 25; 0 = ninguno), solo con lecturas ([read] = lo que da ese código o
         * null si la pantalla no lo tiene): la 19 es la frecuencia en el de 31; si no, la 18 en el de 25. Recién
         * arrancado se prueba [tries] veces con [pause] entre una y otra.
         */
        internal fun contractOf(tries: Int, pause: () -> Unit, read: (code: Int) -> Int?): Int {
            repeat(tries) { i ->
                if (i > 0) pause()
                if (read(CODE_FREQ_31)?.let(::hcnFrequency) != null) return 31
                if (read(CODE_FREQ_25)?.let(::hcnFrequency) != null) return 25
            }
            return 0
        }

        /**
         * Lo que da `getCurrentFreq` (o el aviso 100) → (es AM, frecuencia como [stationText]); null si no es
         * una frecuencia creíble. Según el firmware llega en kHz (87500) o en decenas de kHz (8750); AM en kHz.
         */
        internal fun hcnFrequency(value: Int): Pair<Boolean, Int>? = when (value) {
            in 64_000..108_100 -> false to value / 10
            in 6_400..10_810 -> false to value
            in 520..1_710 -> true to value
            else -> null
        }

        /** Para los reportes (sin abrir nada); [radio] = el perfil abierto. */
        fun describe(context: Context, radio: HcnRadio?): String {
            val version = runCatching { context.packageManager.getPackageInfo(PACKAGE, 0).versionName }.getOrNull() ?: "no"
            return "HCN: app=$version perfil=${yes(radio?.open == true)} conectado=${yes(radio?.connected == true)} " +
                "contrato=${radio?.contract?.takeIf { it > 0 } ?: "-"} avisos=${yes(radio?.notified == true)} " +
                "estación=${radio?.station ?: "-"} nombre=${radio?.name?.ifEmpty { "-" } ?: "-"}"
        }
    }
}
