package com.irvingguerra.litedash.feature.music

import org.json.JSONObject

/**
 * Ids de las recetas de sonido. Son fijos: van en las estadísticas (`btRecipe`, `fmRecipe`) y en el catálogo
 * compartido (`litedash.dev/audio/recipes.json`); NO cambiarlos.
 */
internal object Recipes {
    /** Suena sin hacer nada. */
    const val PLAIN = "plain"
    /** TopWay: pedirle al MCU la fuente 8 (la del Bluetooth de fábrica). */
    const val TW_SOURCE8 = "tw.source8"
    /** FYT: alternar la fuente con el servicio de su app de Bluetooth y volver a dar play. */
    const val FYT_TOGGLE = "fyt.toggle"
    /** Abrir la app de fábrica (Bluetooth o radio) y regresar a LiteDash. */
    const val OPEN_FACTORY = "open.factory"
    /** Radio: el perfil de su marca, sin abrir la app. */
    const val PROFILE = "profile"
    /** Nada funcionó. */
    const val FAILED = "failed"

    val BLUETOOTH = setOf(PLAIN, TW_SOURCE8, FYT_TOGGLE, OPEN_FACTORY, FAILED)
    val RADIO = setOf(PROFILE, OPEN_FACTORY, FAILED)
    /** Las de una marca (no "sin hacer nada" ni abrir su app). */
    val BRAND = setOf(TW_SOURCE8, FYT_TOGGLE)
}

/** Quién confirmó la receta guardada; [key] es lo que se guarda. */
internal enum class SavedBy(val key: String) {
    /** Android tuvo audio: se vuelve a revisar en cada uso. */
    SIGNAL("signal"),
    /** La persona dijo "Sí": Android no lo ve (camino analógico), no se cuentan fallos. */
    USER("user"),
    /** Venía del catálogo y Android lo confirmó. */
    CATALOG("catalog"),
}

internal data class SavedRecipe(val id: String, val by: SavedBy, val fails: Int = 0)

/** Dónde se guarda la receta de una pieza (Bluetooth o radio). */
internal interface RecipeStore {
    val saved: SavedRecipe?
    fun save(recipe: SavedRecipe?)
}

/**
 * El calibrador de una pieza (Bluetooth o radio), sin nada de Android para poder probarlo. Cada vez que la
 * pieza empieza a sonar ([play]) dice qué receta aplicar; quien lo usa la aplica, revisa si suena ([signal])
 * y, si duda, pregunta "¿Se escucha?" ([answer]).
 *
 * - Con receta guardada: se aplica y se revisa. Guardada por la señal (o el catálogo), tres "no" seguidos la
 *   olvidan y se calibra otra vez; guardada por la persona, no se cuentan.
 * - Sin guardada pero con la del catálogo para esta pantalla: se aplica; si suena se guarda, si no se pregunta
 *   una vez y con "No" se calibra completo.
 * - Sin nada: las recetas en orden. Si suena, se guarda (señal); si no, se pregunta ([trustsSignal]: solo en la
 *   última). "Sí" guarda (persona), "No" pasa a la siguiente; sin más, se guarda [Recipes.FAILED].
 * - Abrir la app de fábrica no corre con el auto en movimiento ([moving]): se pospone sin guardar nada.
 */
internal class Calibrator(
    private val store: RecipeStore,
    private val trustsSignal: Boolean,
    private val moving: () -> Boolean,
) {
    sealed interface Step {
        /** Aplicar esta receta (soltando la anterior, si era otra) y revisar si suena. */
        data class Apply(val recipe: String) : Step
        /** Preguntar "¿Se escucha?". */
        data object Ask : Step
        /** Nada más que hacer (la receta aplicada se queda). */
        data object Done : Step
        /** Nada funcionó: soltar la aplicada y avisar. */
        data object Failed : Step
        /** El auto se mueve: soltar la aplicada y esperar al siguiente play. */
        data object Wait : Step
    }

    private enum class Phase { IDLE, SAVED, CATALOG, ASK_CATALOG, TRYING, ASK }

    private var phase = Phase.IDLE
    private var candidates = emptyList<String>()
    private val queue = ArrayDeque<String>()
    /** Las que la persona ya rechazó en esta sesión: si se pospuso o no contestó, se sigue desde ahí. */
    private val rejected = HashSet<String>()

    /** La receta que se está probando o usando; null = ninguna. */
    var current: String? = null
        private set

    /** Espera la señal o la respuesta: otro play no empieza nada. */
    val pending: Boolean get() = phase != Phase.IDLE

    /** Preguntando "¿Se escucha?". */
    val asking: Boolean get() = phase == Phase.ASK || phase == Phase.ASK_CATALOG

    /** La pieza empezó a sonar: [available] son las recetas que se pueden en esta pantalla, en orden. */
    fun play(available: List<String>, catalog: String?): Step {
        if (pending || available.isEmpty()) return Step.Done
        candidates = available
        val saved = store.saved
        if (saved != null) {
            if (saved.id == Recipes.FAILED) return Step.Done
            if (saved.id in available) return use(Phase.SAVED, saved.id)
            // Ahora no se puede (p. ej. el perfil de la radio aún no abre): esta vez lo de siempre, sin borrarla.
            return Step.Done
        }
        if (catalog != null && catalog in available && catalog !in rejected) return use(Phase.CATALOG, catalog)
        restart()
        return next()
    }

    /** Lo que dijo Android a los ~2.5 s: [heard] = suena (y no es otra app). */
    fun signal(heard: Boolean): Step {
        val id = current
        return when (phase) {
            Phase.SAVED -> {
                phase = Phase.IDLE
                val s = store.saved ?: return Step.Done
                when {
                    s.by == SavedBy.USER -> Step.Done
                    heard -> Step.Done.also { if (s.fails > 0) store.save(s.copy(fails = 0)) }
                    s.fails + 1 >= MAX_FAILS -> {
                        // Dejó de servir (¿otra versión del firmware?): se olvida y se calibra desde el principio.
                        store.save(null)
                        restart()
                        next()
                    }
                    else -> Step.Done.also { store.save(s.copy(fails = s.fails + 1)) }
                }
            }
            Phase.CATALOG -> if (heard) done(id, SavedBy.CATALOG) else Step.Ask.also { phase = Phase.ASK_CATALOG }
            Phase.TRYING -> when {
                heard -> done(id, SavedBy.SIGNAL)
                trustsSignal && queue.isNotEmpty() -> {
                    id?.let(rejected::add)
                    next()
                }
                else -> Step.Ask.also { phase = Phase.ASK }
            }
            else -> Step.Done
        }
    }

    /** La respuesta a "¿Se escucha?". */
    fun answer(yes: Boolean): Step {
        val id = current
        if (!asking) return Step.Done
        if (yes) return done(id, SavedBy.USER)
        id?.let(rejected::add)
        if (phase == Phase.ASK_CATALOG) restart()
        return next()
    }

    /** Sin respuesta, se pausó o se cambió de método: se deja así, sin guardar nada. */
    fun abort() {
        phase = Phase.IDLE
    }

    /** "Calibrar otra vez": se borra lo guardado y lo rechazado (quien lo usa suelta la receta aplicada). */
    fun reset() {
        store.save(null)
        rejected.clear()
        phase = Phase.IDLE
        current = null
    }

    private fun restart() {
        queue.clear()
        queue.addAll(candidates.filter { it !in rejected })
    }

    private fun use(next: Phase, id: String): Step {
        if (id == Recipes.OPEN_FACTORY && moving()) return wait()
        phase = next
        current = id
        return Step.Apply(id)
    }

    private fun next(): Step {
        val id = queue.removeFirstOrNull() ?: return failed()
        if (id == Recipes.OPEN_FACTORY && moving()) {
            queue.addFirst(id)
            return wait()
        }
        phase = Phase.TRYING
        current = id
        return Step.Apply(id)
    }

    private fun wait(): Step {
        phase = Phase.IDLE
        current = null
        return Step.Wait
    }

    private fun failed(): Step {
        store.save(SavedRecipe(Recipes.FAILED, SavedBy.USER))
        phase = Phase.IDLE
        current = null
        return Step.Failed
    }

    private fun done(id: String?, by: SavedBy): Step {
        phase = Phase.IDLE
        if (id != null) store.save(SavedRecipe(id, by))
        return Step.Done
    }

    companion object {
        /** "No" seguidos de la señal con la receta guardada antes de olvidarla. */
        const val MAX_FAILS = 3
    }
}

/** Lo que el catálogo compartido dice para esta pantalla; null = no dice. */
internal data class CatalogRecipe(val bluetooth: String?, val radio: String?)

/**
 * El catálogo de recetas que funcionaron en otras pantallas (`litedash.dev/audio/recipes.json`, estático y
 * revisado a mano): `{"version":1,"recipes":[{"bluetooth","radio"?,"bt"?,"fm"?}]}`. `bluetooth`/`radio` son los
 * paquetes de [FactoryAudio]; una entrada sin `radio` vale para cualquier radio y la que trae `radio` le gana.
 * Ids desconocidos se ignoran (una versión vieja de LiteDash no sabe aplicarlos).
 */
internal object RecipeCatalog {
    fun match(json: String, bluetooth: String, radio: String): CatalogRecipe? = runCatching {
        val root = JSONObject(json)
        if (root.optInt("version") != 1) return null
        val list = root.optJSONArray("recipes") ?: return null
        val entries = (0 until list.length()).mapNotNull { list.optJSONObject(it) }
            .filter { it.optString("bluetooth") == bluetooth && (!it.has("radio") || it.optString("radio") == radio) }
            // Primero las que nombran la radio: le ganan a las que valen para cualquiera.
            .sortedByDescending { it.has("radio") }
        fun pick(key: String, known: Set<String>) = entries.firstNotNullOfOrNull { e -> e.optString(key).takeIf { it in known } }
        CatalogRecipe(pick("bt", Recipes.BLUETOOTH), pick("fm", Recipes.RADIO)).takeIf { it.bluetooth != null || it.radio != null }
    }.getOrNull()
}
