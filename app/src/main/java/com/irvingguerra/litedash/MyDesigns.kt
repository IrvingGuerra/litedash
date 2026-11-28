// Tus Diseños: tu inicio guardado con nombre (acomodo, colores y su foto), para volver a él de un toque. Viven junto a
// los de fábrica en la galería; nunca se borran solos y uno de fábrica nunca los pisa.
package com.irvingguerra.litedash

import android.content.Context
import com.irvingguerra.litedash.feature.dashboard.PageSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Un Diseño tuyo: su [name], lo de toda la app ([look]), la página como estaba ([page]: su acomodo en la cuadrícula de
 * cuando se guardó, su fondo de página y su plantilla) y si lleva foto ([image]: una copia del fondo de entonces,
 * [designThumb]).
 */
internal data class MyDesign(val id: String, val name: String, val look: DesignLook, val page: PageSnapshot, val image: Boolean) {
    /** ¿Sigue todo como en este Diseño? El color del auto no cuenta con un modelo del catálogo ([ownCar]): ese no se toca. */
    fun matches(now: DesignLook, ownCar: Boolean) = now == over(now, ownCar)

    /** Lo de toda la app con este Diseño puesto sobre [now]: todo lo suyo, salvo el color de tu auto del catálogo. */
    fun over(now: DesignLook, ownCar: Boolean) = if (ownCar) look.copy(carColor = now.carColor) else look

    private fun json() = JSONObject().apply {
        put("id", id)
        put("name", name)
        putLook(look)
        putPage(page)
        put("image", image)
    }

    companion object {
        /** Los ids de los tuyos empiezan así (los de fábrica son palabras: [Design.id]). */
        const val PREFIX = "my-"

        fun encode(all: List<MyDesign>): String? = if (all.isEmpty()) null else JSONArray().apply { all.forEach { put(it.json()) } }.toString()

        /** Lo guardado por [encode]; uno dañado se salta (los demás se conservan). */
        fun decode(text: String?): List<MyDesign> {
            if (text.isNullOrEmpty()) return emptyList()
            val array = try {
                JSONArray(text)
            } catch (e: JSONException) {
                return emptyList()
            }
            return (0 until array.length()).mapNotNull { i ->
                try {
                    val o = array.getJSONObject(i)
                    MyDesign(o.getString("id"), o.getString("name"), o.look() ?: return@mapNotNull null, o.page() ?: return@mapNotNull null, o.optBoolean("image"))
                } catch (e: JSONException) {
                    null
                }
            }
        }
    }
}

/** Lo más largo que se guarda el nombre de un Diseño tuyo. */
internal const val MY_DESIGN_NAME_MAX = 24

/** [typed] como nombre de un Diseño: sin espacios de sobra y recortado; null si queda vacío. */
internal fun designName(typed: String): String? = typed.trim().replace(Regex("\\s+"), " ").take(MY_DESIGN_NAME_MAX).trim().ifEmpty { null }

/** Tus Diseños, leídos una vez por texto (se preguntan en cada dibujo de la galería). */
private var read: Pair<String, List<MyDesign>>? = null

internal val Preferences.mine: List<MyDesign>
    get() {
        val text = myDesigns ?: return emptyList()
        read?.let { (from, all) -> if (from == text) return all }
        return MyDesign.decode(text).also { read = text to it }
    }

/**
 * Guarda tu inicio de ahora como un Diseño tuyo llamado [name]: la página que se ve, lo de toda la app y una copia de tu
 * fondo. Queda como el Diseño puesto. Si la foto no se pudo copiar, no se guarda nada. Desde el hilo principal.
 */
internal fun saveMyDesign(d: Dependencies, context: Context, scope: CoroutineScope, name: String) {
    val prefs = d.prefs
    val page = d.dashboard.snapshot() ?: return
    val look = prefs.look
    val id = MyDesign.PREFIX + System.currentTimeMillis().toString(36)
    scope.launch {
        val image = keepDesignImage(context, id) ?: return@launch
        prefs.myDesigns = MyDesign.encode(prefs.mine + MyDesign(id, name, look, page.copy(page = 0), image))
        prefs.design = id
    }
}

/**
 * Aplica tu Diseño [mine] a la página que se ve y a lo de toda la app, y deja listo «Deshacer», igual que uno de fábrica
 * ([applyDesign]); su foto se pone después (la tuya de ahora se guarda aparte antes). Desde el hilo principal.
 */
internal fun applyMyDesign(d: Dependencies, context: Context, scope: CoroutineScope, mine: MyDesign) {
    val prefs = d.prefs
    val before = d.dashboard.snapshot() ?: return
    val now = prefs.look
    prefs.put(mine.over(now, ownCar = !prefs.carModel.isNullOrEmpty()))
    d.dashboard.place(mine.page)
    val applied = d.dashboard.snapshot()?.layout ?: return
    val undo = DesignUndo(prefs.design, now, before, applied, pending = prefs.designWallpaper)
    prefs.designUndo = undo.encode()
    prefs.design = mine.id
    DesignNotice.showOwn(mine.name)
    val turn = ++designTurn
    // El fondo de un Diseño de fábrica que esperaba internet ya no: manda este.
    prefs.designWallpaper = null
    scope.launch {
        if (!keepOwnWallpaper(context) || turn != designTurn) return@launch
        prefs.designUndo = undo.copy(wallpaper = true).encode()
        putDesignImage(context, mine.id.takeIf { mine.image }) { turn == designTurn }
    }
}

/** Le cambia el nombre a tu Diseño [id]. */
internal fun renameMyDesign(prefs: Preferences, id: String, name: String) {
    prefs.myDesigns = MyDesign.encode(prefs.mine.map { if (it.id == id) it.copy(name = name) else it })
}

/** Elimina tu Diseño [id] y su foto (tu inicio de ahora no cambia). Desde el hilo principal. */
internal fun deleteMyDesign(prefs: Preferences, context: Context, scope: CoroutineScope, id: String) {
    prefs.myDesigns = MyDesign.encode(prefs.mine.filterNot { it.id == id })
    if (prefs.design == id) prefs.design = null
    scope.launch { dropDesignImage(context, id) }
}
