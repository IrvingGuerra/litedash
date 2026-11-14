package com.irvingguerra.litedash.feature.music

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.irvingguerra.litedash.core.ui.Https
import java.io.File
import java.util.concurrent.Executor

/** Lo que se espera tras aplicar una receta para revisar si ya suena. */
private const val VERIFY_MS = 2_500L
/** Sin respuesta a "¿Se escucha?" en este rato, se deja así (no se sigue probando a ciegas). */
private const val ASK_TIMEOUT_MS = 30_000L
/** Si la revisión nunca llega (p. ej. LiteDash se fue a segundo plano), se deja así. */
private const val STUCK_MS = 20_000L
/** Tras abrir una app de fábrica, cuánto se espera para regresar a LiteDash. */
internal const val FACTORY_RETURN_MS = 3_000L
/** FYT: cuánto se espera a que el celular diga que suena antes de alternar la fuente. */
private const val FYT_CHECK_MS = 4_000L
/** FYT: el play va poco después de alternar la fuente (no dos veces en menos de 1.5 s). */
private const val FYT_REPLAY_MS = 700L

/** El catálogo compartido y cada cuánto se vuelve a bajar. */
private const val CATALOG_URL = "https://litedash.dev/audio/recipes.json"
private const val CATALOG_FILE = "audio-recipes.json"
private const val CATALOG_MAX_AGE_MS = 7 * 24 * 60 * 60_000L

/** La app de Bluetooth de fábrica de TopWay y la de FYT. */
private const val TOPWAY_BT = "com.tw.bt"
private const val FYT_BT = "com.syu.bt"

/** Qué pieza pregunta o avisa: la música (su Bluetooth) o la radio de la pantalla. */
enum class SoundPiece(internal val key: String) { MUSIC("bt"), RADIO("fm") }

/** Lo que la pieza [piece] muestra en su widget: "¿Se escucha?" o, con [failed], que no se encontró cómo. */
data class SoundCheck(val piece: SoundPiece, val failed: Boolean)

/** La receta guardada de cada pieza en el store de música (`bt_recipe`, `bt_recipe_by`, `bt_recipe_fails`; `fm_…`). */
internal class PrefsRecipeStore(private val prefs: SharedPreferences, piece: SoundPiece) : RecipeStore {
    private val key = piece.key + "_recipe"

    override var saved by mutableStateOf(read())
        private set

    override fun save(recipe: SavedRecipe?) {
        saved = recipe
        prefs.edit().apply {
            if (recipe == null) {
                remove(key).remove("${key}_by").remove("${key}_fails")
            } else {
                putString(key, recipe.id).putString("${key}_by", recipe.by.key).putInt("${key}_fails", recipe.fails)
            }
        }.apply()
    }

    private fun read(): SavedRecipe? {
        val id = prefs.getString(key, null) ?: return null
        val by = SavedBy.entries.firstOrNull { it.key == prefs.getString("${key}_by", null) } ?: SavedBy.SIGNAL
        return SavedRecipe(id, by, prefs.getInt("${key}_fails", 0))
    }

    companion object {
        /** El id guardado de [piece], sin abrir nada más (para las estadísticas). */
        fun savedId(prefs: SharedPreferences, piece: SoundPiece): String? = prefs.getString(piece.key + "_recipe", null)
    }
}

/**
 * Lleva el [Calibrator] de una pieza en la pantalla: aplica cada receta ([use]), revisa a su tiempo si suena
 * ([check], o quien la aplicó avisa con [heard]), muestra la pregunta ([show]) y la quita sin respuesta. Todo en
 * el hilo principal; cada paso va al registro de los reportes ([trace]).
 */
internal class SoundCalibration(
    private val piece: SoundPiece,
    val store: PrefsRecipeStore,
    trustsSignal: Boolean,
    moving: () -> Boolean,
    private val main: Handler,
    private val trace: (String) -> Unit,
    private val show: (SoundCheck?) -> Unit,
    /** Aplica la receta (null = suelta la aplicada) y dice en cuántos ms revisar; null = avisa sola con [heard]. */
    private val use: (String?) -> Long?,
    /** ¿Suena de verdad? null = ya no debería sonar (se pausó): esa vez no cuenta. */
    private val check: () -> Boolean? = { null },
    /** Terminó sin pregunta pendiente tras una revisión o un "Sí" ([heard] = suena o la persona dice que sí). */
    private val settled: (heard: Boolean) -> Unit = {},
) {
    private val engine = Calibrator(store, trustsSignal, moving)
    /** Lo último fue posponer: mientras el auto siga en movimiento, cada play lo vuelve a posponer sin llenar el registro. */
    private var postponed = false

    val current: String? get() = engine.current

    /** La pieza empezó a sonar; false = no se aplicó nada (ya calibrando, nada que probar o nada funcionó antes). */
    fun play(available: List<String>, catalog: String?): Boolean {
        // Ya calibrando: sus tiempos siguen corriendo (no se tocan).
        if (engine.pending) return false
        val step = engine.play(available, catalog)
        handle(step)
        return step is Calibrator.Step.Apply
    }

    /** Lo que dijo la señal tras aplicar la receta. */
    fun heard(heard: Boolean) {
        if (!engine.pending || engine.asking) return
        trace("calibración ${piece.key}: ${engine.current} ${if (heard) "suena" else "no suena"}")
        handle(engine.signal(heard), heard)
    }

    fun answer(yes: Boolean) {
        if (!engine.asking) return
        show(null)
        trace("calibración ${piece.key}: ${engine.current} → ${if (yes) "sí se escucha" else "no se escucha"}")
        handle(engine.answer(yes), heard = if (yes) true else null)
    }

    /** Se cambió de método o se fue la fuente: lo pendiente se deja (sin guardar nada). */
    fun abort() {
        if (!engine.pending) return
        clearTimers()
        if (engine.asking) show(null)
        engine.abort()
    }

    /** "Calibrar otra vez": se olvida lo guardado (lo que suena no se corta: se vuelve a probar en el siguiente play). */
    fun reset() {
        abort()
        engine.reset()
    }

    private fun handle(step: Calibrator.Step, heard: Boolean? = null) {
        clearTimers()
        val wasPostponed = postponed
        postponed = step == Calibrator.Step.Wait
        when (step) {
            is Calibrator.Step.Apply -> {
                trace("calibración ${piece.key}: aplica ${step.recipe}")
                val ms = use(step.recipe)
                // Quien la aplica pudo soltarla ya (p. ej. el perfil de la radio se cerró).
                if (!engine.pending) return
                main.postDelayed(stuck, STUCK_MS)
                if (ms != null) main.postDelayed(verify, ms)
            }
            Calibrator.Step.Ask -> {
                show(SoundCheck(piece, failed = false))
                main.postDelayed(noAnswer, ASK_TIMEOUT_MS)
            }
            Calibrator.Step.Failed -> {
                trace("calibración ${piece.key}: nada funcionó")
                use(null)
                show(SoundCheck(piece, failed = true))
            }
            // La aplicada se queda: puede ser la que sí se oía (Android no lo ve).
            Calibrator.Step.Wait -> if (!wasPostponed) trace("calibración ${piece.key}: pospuesta (el auto se mueve o LiteDash no está a la vista)")
            Calibrator.Step.Done -> heard?.let(settled)
        }
    }

    private fun clearTimers() {
        main.removeCallbacks(verify)
        main.removeCallbacks(stuck)
        main.removeCallbacks(noAnswer)
    }

    private val verify = Runnable {
        val heard = check()
        if (heard == null) {
            trace("calibración ${piece.key}: ya no suena, sin revisar")
            main.removeCallbacks(stuck)
            engine.abort()
        } else {
            heard(heard)
        }
    }

    private val stuck = Runnable {
        trace("calibración ${piece.key}: sin revisión, se deja así")
        engine.abort()
    }

    private val noAnswer = Runnable {
        trace("calibración ${piece.key}: sin respuesta, se deja así")
        show(null)
        engine.abort()
    }
}

/** Lo que las recetas necesitan de la música ([MusicRepository]). */
internal interface SoundHost {
    val context: Context
    val store: SharedPreferences
    val main: Handler
    /** El hilo de los perfiles de fábrica (reflexión y el driver). */
    val worker: Executor
    /** Al registro de los reportes; desde cualquier hilo. */
    fun log(event: String)
    fun bluetoothApp(): String?
    /** La app de Bluetooth de fábrica (no la del Bluetooth de Android); null si no hay. */
    fun factoryBluetooth(): String?
    /** Abre [pkg] encima de LiteDash y regresa en [FACTORY_RETURN_MS]. */
    fun openBriefly(pkg: String)
    fun musicActive(): Boolean
    /** El celular dice por el Bluetooth de Android (AVRCP) que suena. */
    fun avrcpPlaying(): Boolean
    fun avrcpPlay()
}

/** Una receta para que suene el Bluetooth de la pantalla; [apply] dice en cuántos ms revisar si ya suena. */
internal abstract class BluetoothRecipe(val id: String) {
    open fun available(): Boolean = true
    open fun apply(): Long = VERIFY_MS
    open fun release() = Unit
}

/** Las recetas de Bluetooth por id (el orden lo pone la familia: [HeadUnit.bluetoothRecipes]). */
internal fun bluetoothRecipes(host: SoundHost): Map<String, BluetoothRecipe> =
    listOf(object : BluetoothRecipe(Recipes.PLAIN) {}, TopWaySource8(host), FytToggle(host), OpenFactory(host)).associateBy { it.id }

/**
 * TopWay con Bluetooth de chip propio (`com.tw.bt`): la música del celular llega al chip, pero el MCU no la pasa a
 * las bocinas hasta que alguien pide su fuente (la app de fábrica lo hace al entrar a su Música). Se pide con un
 * enlace TWUtil propio (la radio tiene el suyo y solo vive con la pieza), cada vez que se aplica; al soltarla se
 * cierra. [link] solo en el hilo [SoundHost.worker].
 */
private class TopWaySource8(private val host: SoundHost) : BluetoothRecipe(Recipes.TW_SOURCE8) {
    private var link: TopWayRadio? = null
    private var held = false

    override fun available() = TopWayRadio.available && host.bluetoothApp() == TOPWAY_BT

    override fun apply(): Long {
        held = true
        host.worker.execute {
            val tw = link ?: TopWayRadio.openGuarded(host.store, onState = {}, trace = host::log)?.also { link = it } ?: return@execute
            tw.takeBluetooth()
        }
        return VERIFY_MS
    }

    override fun release() {
        if (!held) return
        held = false
        host.worker.execute {
            val tw = link ?: return@execute
            link = null
            tw.releaseBluetooth()
            tw.close()
        }
    }
}

/**
 * FYT (el MCU elige la fuente de audio): dar play al celular por AVRCP no basta, el MCU tiene que pasar la fuente
 * a Bluetooth. Si al poco rato el celular dice que suena pero Android no tiene audio, se alterna la fuente con el
 * servicio de su app y se vuelve a dar play.
 */
private class FytToggle(private val host: SoundHost) : BluetoothRecipe(Recipes.FYT_TOGGLE) {
    override fun available() = host.context.hasPackage(FYT_BT)

    override fun apply(): Long {
        host.main.removeCallbacks(toggle)
        host.main.postDelayed(toggle, FYT_CHECK_MS)
        return FYT_CHECK_MS + FYT_REPLAY_MS + VERIFY_MS
    }

    override fun release() {
        host.main.removeCallbacks(toggle)
        host.main.removeCallbacks(replay)
    }

    private val toggle = Runnable {
        if (!host.avrcpPlaying() || host.musicActive()) return@Runnable
        host.log("fyt: el celular suena por AVRCP pero no hay audio; se alterna la fuente")
        runCatching {
            host.context.startService(Intent("com.syu.bt.byav.widgetPlayPause").setClassName(FYT_BT, "com.syu.bt.broadcast.BtavPlayPauseService"))
        }
        host.main.postDelayed(replay, FYT_REPLAY_MS)
    }

    private val replay = Runnable { host.avrcpPlay() }
}

/**
 * Abrir la app de Bluetooth de fábrica y regresar a LiteDash (lo que hacía la gente a mano): su Música pide la
 * fuente al MCU. Tapa la pantalla un momento: va al final, nunca con el auto en movimiento ni con LiteDash
 * escondido ([Calibrator]) y una sola vez mientras siga aplicada (se suelta al irse el celular o cambiar de método).
 */
private class OpenFactory(private val host: SoundHost) : BluetoothRecipe(Recipes.OPEN_FACTORY) {
    private var opened = false

    override fun available() = host.factoryBluetooth()?.let { host.context.packageManager.getLaunchIntentForPackage(it) != null } == true

    override fun apply(): Long {
        if (opened) return VERIFY_MS
        val pkg = host.factoryBluetooth() ?: return VERIFY_MS
        opened = true
        host.openBriefly(pkg)
        return FACTORY_RETURN_MS + VERIFY_MS
    }

    override fun release() {
        opened = false
    }
}

/**
 * Lo que el catálogo compartido dice para esta pantalla (por su radio y su Bluetooth de fábrica, [FactoryAudio]).
 * Se guarda en disco y se vuelve a bajar a los 7 días; sin red sirve la copia vieja y, sin copia, nada. Bloquea
 * (consultas al sistema y la red): va en un hilo aparte.
 */
/** Ya hay una copia del catálogo en disco (bajada antes). Bloquea: en un hilo aparte. */
internal fun recipeCatalogCached(context: Context): Boolean = File(context.cacheDir, CATALOG_FILE).exists()

internal fun loadRecipeCatalog(context: Context): CatalogRecipe? {
    val file = File(context.cacheDir, CATALOG_FILE)
    val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < CATALOG_MAX_AGE_MS
    val text = (if (fresh) null else runCatching { download(context, file) }.getOrNull())
        ?: runCatching { file.readText() }.getOrNull()
        ?: return null
    return RecipeCatalog.match(text, FactoryAudio.bluetooth(context), FactoryAudio.radio(context))
}

private fun download(context: Context, file: File): String {
    val text = Https.get(context, CATALOG_URL, "application/json").inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
    // Escrito aparte y luego movido: una copia a medias nunca queda como la buena.
    val temp = File(file.parentFile, "$CATALOG_FILE.part")
    temp.writeText(text)
    temp.renameTo(file)
    return text
}
