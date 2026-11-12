package com.irvingguerra.litedash.feature.link

import org.json.JSONObject

/**
 * Lo que el celular pide cambiar de tu auto (mensaje `setCar`, PROTOCOLO_LINK.md §5e), ya revisado:
 * lo que falta o no es válido queda en null (y se ignora). [name] "" = el nombre de fábrica, [id]
 * "" = el sedán genérico; [entry] es la entrada del catálogo tal como la mandó el celular (la revisa
 * quien la usa).
 */
class CarEdit(
    /** RGB, sin transparencia. */
    val color: Int?,
    /** `matte`, `satin`, `gloss` o `metallic`. */
    val finish: String?,
    val gloss: Float?,
    val id: String?,
    val name: String?,
    val entry: JSONObject?,
) {
    internal companion object {
        private val COLOR = Regex("[0-9A-Fa-f]{6}")
        private val FINISHES = setOf("matte", "satin", "gloss", "metallic")
        /** Como los del catálogo ("marca/modelo-año"): sin "..", sin "/" de más ni nada que salga de su carpeta. */
        private val ID = Regex("[a-z0-9][a-z0-9-]*(/[a-z0-9][a-z0-9-]*)?")
        private const val ID_MAX = 64
        const val NAME_MAX = 40

        fun parse(m: JSONObject): CarEdit {
            val gloss = (m.opt("gloss") as? Number)?.toFloat()
            return CarEdit(
                color = (m.opt("color") as? String)?.takeIf { COLOR.matches(it) }?.toInt(16),
                finish = (m.opt("finish") as? String)?.takeIf { it in FINISHES },
                gloss = gloss?.takeIf { it in 0f..1f },
                id = (m.opt("id") as? String)?.takeIf { it.isEmpty() || it.length <= ID_MAX && ID.matches(it) },
                name = (m.opt("name") as? String)?.trim()?.takeIf { it.length <= NAME_MAX },
                entry = m.optJSONObject("entry"),
            )
        }
    }
}

/**
 * El orden de las partes de un paquete que manda el celular (mensaje `pkg`, §5f): van de 0 a `of − 1`,
 * seguidas, del mismo `id` y `kind`. Solo lleva la cuenta (lo escribe quien lo usa); puro para probarlo.
 */
internal class PackageParts {
    enum class Step {
        /** Primera parte de un paquete nuevo (lo que hubiera a medias se tira). */
        FIRST,
        MORE,
        /** La última: el paquete está completo. */
        LAST,
        /** Fuera de orden, otro paquete a media entrega o datos de más: se tira todo y se avisa. */
        BAD,
        /** Lo que sigue de un paquete que ya se tiró: se ignora sin avisar otra vez. */
        IGNORE,
    }

    /** El paquete que se está recibiendo (id y kind); null = ninguno. */
    var current: Pair<String, String>? = null
        private set
    private var next = 0
    private var total = 0
    private var dropped: Pair<String, String>? = null

    fun take(id: String, kind: String, n: Int, of: Int, size: Int): Step {
        val key = id to kind
        if (n > 0 && current == null && dropped == key) return Step.IGNORE
        val fits = of in 1..MAX_PARTS && n in 0 until of && size in 0..PART_MAX
        // Una parte 0 del mismo paquete lo vuelve a empezar (el celular reintenta); de otro, a media entrega, es un error.
        val ok = fits && if (n == 0) current == null || current == key else current == key && n == next && of == total
        if (!ok) {
            dropped = key
            current = null
            return Step.BAD
        }
        if (n == 0) {
            current = key
            total = of
            dropped = null
        }
        next = n + 1
        if (next < total) return if (n == 0) Step.FIRST else Step.MORE
        current = null
        return Step.LAST
    }

    /**
     * Se tira lo que se estaba recibiendo (otro `setCar` lo canceló, se cerró la conexión o no se pudo guardar): lo que
     * siga llegando de él se ignora sin avisar.
     */
    fun cancel() {
        current?.let { dropped = it }
        current = null
    }

    companion object {
        /** Bytes por parte (64 KiB). */
        const val PART_MAX = 64 * 1024
        /** Un paquete de más de 64 MiB no es un auto. */
        const val MAX_PARTS = 1024
    }
}
