// Los Diseños: un toque pone acomodo, fondo, colores, mapa, velocímetro y color del auto. Solo mueven perillas que ya
// existen; después todo se cambia como siempre (son un punto de partida, no un candado).
package com.irvingguerra.litedash

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.irvingguerra.litedash.core.ui.CardStyle
import com.irvingguerra.litedash.core.ui.MIN_OPACITY
import com.irvingguerra.litedash.feature.dashboard.PageBackground
import com.irvingguerra.litedash.feature.dashboard.PageSnapshot
import com.irvingguerra.litedash.feature.dashboard.Template
import com.irvingguerra.litedash.feature.map.MapLook
import com.irvingguerra.litedash.feature.map.MapPalettes
import com.irvingguerra.litedash.feature.map.MapStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject

/**
 * Un Diseño: lo que pone de un toque. [template] es su acomodo (con el velocímetro de su estilo y, en Ruta, el mapa de
 * fondo); [wallpaper] el fondo de litedash.dev (null = no se toca); lo demás, lo de toda la app. [carColor] solo se pone
 * si no se eligió un modelo del catálogo (ese es tu auto de verdad).
 */
@Immutable
class Design(
    /** Se guarda: va en inglés y no cambia. */
    val id: String,
    @StringRes val name: Int,
    @StringRes val tagline: Int,
    val template: Template,
    val theme: ThemeMode,
    val wallpaper: String?,
    val carColor: Color,
    val cardStyle: CardStyle = CardStyle.GLASS,
    val cardOpacity: Float = glass(0f),
    val cardColor: Color? = null,
    val accent: Color? = null,
    val mapPalette: MapPalettes = MapPalettes.FACTORY,
    /** El color (RGB) del mapa con [MapPalettes.CUSTOM]. */
    val mapColor: Int? = null,
    val mapStyle: MapStyle = MapStyle.THEME,
    val barStyle: BarStyle = BarStyle.FLOATING,
)

/** La opacidad del vidrio con el control «Cuánto se ven» en [x] (0 = puro cristal, 1 = lisa). */
private fun glass(x: Float) = MIN_OPACITY + (1f - MIN_OPACITY) * x

/** Los Diseños en el orden de su galería; el primero es el de fábrica. Todos gratis. */
val DESIGNS = listOf(
    Design(
        "graphite", R.string.design_graphite, R.string.design_graphite_tagline, Template.DESIGN_GRAPHITE, ThemeMode.DARK,
        wallpaper = "grafito-cinta", carColor = Color(0xFFC9CED4),
    ),
    Design(
        "cloud", R.string.design_cloud, R.string.design_cloud_tagline, Template.DESIGN_CLOUD, ThemeMode.LIGHT,
        wallpaper = "nube-alba", carColor = Color(0xFFF4F6F8), cardOpacity = glass(0.2f), accent = Color(0xFFFF9F0A),
    ),
    Design(
        "track", R.string.design_track, R.string.design_track_tagline, Template.DESIGN_TRACK, ThemeMode.DARK,
        wallpaper = "pista-estela", carColor = Color(0xFFA3141D), cardOpacity = glass(0.5f), accent = Color(0xFFFF453A),
        // El mapa grafito de su galería («Como mi interfaz» sin color de tarjetas tomaría el rojo de la interfaz).
        mapPalette = MapPalettes.CUSTOM, mapColor = 0x1A1D22,
    ),
    Design(
        "route", R.string.design_route, R.string.design_route_tagline, Template.DESIGN_ROUTE, ThemeMode.SUN,
        wallpaper = null, carColor = Color(0xFF2457A6), accent = Color(0xFF4CC9FF),
    ),
    Design(
        "column", R.string.design_column, R.string.design_column_tagline, Template.DESIGN_COLUMN, ThemeMode.DARK,
        wallpaper = "columna-acero", carColor = Color(0xFF7A8590), cardStyle = CardStyle.SOLID, barStyle = BarStyle.FULL,
    ),
    Design(
        "neon", R.string.design_neon, R.string.design_neon_tagline, Template.DESIGN_NEON, ThemeMode.DARK,
        wallpaper = "neon-orbes", carColor = Color(0xFF1D2026), cardColor = Color(0xFF7A3FE0), accent = Color(0xFFFF4F9A),
        mapPalette = MapPalettes.INTERFACE,
    ),
    Design(
        "sand", R.string.design_sand, R.string.design_sand_tagline, Template.DESIGN_SAND, ThemeMode.LIGHT,
        wallpaper = "arena-dunas", carColor = Color(0xFFC2A27C), cardOpacity = glass(0.5f), cardColor = Color(0xFFB89B6A),
        accent = Color(0xFFFF7A1A), mapPalette = MapPalettes.INTERFACE,
    ),
    Design(
        "forest", R.string.design_forest, R.string.design_forest_tagline, Template.DESIGN_FOREST, ThemeMode.DARK,
        wallpaper = "bosque-luz", carColor = Color(0xFF2F5D4A), cardOpacity = glass(0.25f), cardColor = Color(0xFF1E8E4E),
        accent = Color(0xFFE8C15A), mapPalette = MapPalettes.INTERFACE,
    ),
)

/**
 * Los fondos de los Diseños van en pareja, el de noche y el de día (mismas formas): con «Día y noche» la app pone sola
 * el que toca ([ThemedDesignWallpaper]), así un Diseño claro no deslumbra de noche ni uno oscuro se apaga de día.
 */
private val WALLPAPER_PAIRS = listOf(
    "grafito-cinta" to "grafito-plata", "nube-noche" to "nube-alba", "pista-estela" to "pista-dia", "columna-acero" to "columna-dia",
    "neon-orbes" to "neon-dia", "arena-noche" to "arena-dunas", "bosque-luz" to "bosque-dia",
)

/** El fondo que le toca al de un Diseño [id] con el tema oscuro o claro: él mismo o su pareja; null si no es de una pareja. */
internal fun themedWallpaper(id: String, dark: Boolean): String? =
    WALLPAPER_PAIRS.firstOrNull { id == it.first || id == it.second }?.let { if (dark) it.first else it.second }

/** El Diseño con que arrancan las instalaciones nuevas. */
val FACTORY_DESIGN = DESIGNS.first()

fun designOf(id: String?): Design? = DESIGNS.firstOrNull { it.id == id }

/** ¿Es la primera vez que se abre LiteDash ([sawWelcome] y [hasDock] se guardan desde el primer arranque)? */
internal fun isFreshInstall(sawWelcome: Boolean, hasDock: Boolean) = !sawWelcome && !hasDock

/** Lo de toda la app que mueve un Diseño (los colores van en ARGB; null = el de fábrica). */
@Immutable
data class DesignLook(
    val theme: ThemeMode,
    val accent: Int?,
    val cards: Int?,
    val cardStyle: CardStyle,
    val cardOpacity: Float,
    /** El aspecto del mapa ([MapLook.encode]); null = el de fábrica. */
    val mapLook: String?,
    val barStyle: BarStyle,
    val carColor: Int,
)

val Preferences.look
    get() = DesignLook(theme, accentColor?.toArgb(), cardColor?.toArgb(), cardStyle, cardOpacity, mapLook, barStyle, carColor.toArgb())

fun Preferences.put(l: DesignLook) {
    theme = l.theme
    accentColor = l.accent?.let { Color(it) }
    cardColor = l.cards?.let { Color(it) }
    cardStyle = l.cardStyle
    cardOpacity = l.cardOpacity
    mapLook = l.mapLook
    barStyle = l.barStyle
    if (carColor.toArgb() != l.carColor) carColor = Color(l.carColor)
}

/**
 * Lo de toda la app con este Diseño puesto sobre [now]. Del mapa solo cambia el estilo y la paleta (orientación, zoom y
 * la ruta que fluye son de uso, no de estilo); con [ownCar] (un modelo del catálogo) el auto no se toca. Con [keepSun]
 * quien usa «Día y noche» lo conserva (por luces, sensor o sol: un Diseño no debe quitárselo sin que lo note); los colores
 * del Diseño ya salen de claro u oscuro. En la bienvenida va sin [keepSun]: ahí se pone el tema del Diseño tal cual.
 */
fun Design.over(now: DesignLook, ownCar: Boolean, keepSun: Boolean = true) = now.copy(
    theme = if (keepSun && now.theme == ThemeMode.SUN) ThemeMode.SUN else theme,
    accent = accent?.toArgb(),
    cards = cardColor?.toArgb(),
    cardStyle = cardStyle,
    cardOpacity = cardOpacity,
    mapLook = MapLook.parse(now.mapLook.orEmpty()).let { it.copy(style = mapStyle, palette = mapPalette, custom = mapColor ?: it.custom) }.encode().ifEmpty { null },
    barStyle = barStyle,
    carColor = if (ownCar) now.carColor else carColor.toArgb(),
)

/** ¿Sigue todo como lo puso el Diseño? (si no, en la galería sale «con tus cambios»). */
fun Design.matches(now: DesignLook, ownCar: Boolean) = over(now, ownCar) == now

/**
 * Lo de antes del último Diseño, para «Deshacer» (un nivel): el Diseño de antes ([design]), lo de toda la app ([look]),
 * la página como estaba ([page]) y cómo la dejó el Diseño ([applied], para saber si alguien la movió después), si el
 * Diseño cambió el fondo ([wallpaper]: al deshacer regresa tu foto, guardada aparte en [OwnWallpaper]) y el fondo que
 * esperaba internet ([pending]).
 */
internal data class DesignUndo(
    val design: String?,
    val look: DesignLook,
    val page: PageSnapshot,
    val applied: String,
    val wallpaper: Boolean = false,
    val pending: String? = null,
) {
    fun encode(): String = JSONObject().apply {
        putOpt("design", design)
        put("theme", look.theme.stored)
        putOpt("accent", look.accent)
        putOpt("cards", look.cards)
        put("cardStyle", look.cardStyle.stored)
        put("cardOpacity", look.cardOpacity.toDouble())
        putOpt("mapLook", look.mapLook)
        put("barStyle", look.barStyle.stored)
        put("carColor", look.carColor)
        put("page", page.page)
        put("grid", page.grid.key)
        put("layout", page.layout)
        put("background", page.background.stored)
        putOpt("template", page.template?.name)
        put("applied", applied)
        put("wallpaper", wallpaper)
        putOpt("pending", pending)
    }.toString()

    companion object {
        /** Lo guardado por [encode]; null si no hay o está dañado. */
        fun decode(text: String?): DesignUndo? {
            if (text.isNullOrEmpty()) return null
            return try {
                val o = JSONObject(text)
                val look = DesignLook(
                    theme = ThemeMode.fromStored(o.getString("theme")) ?: return null,
                    accent = o.optIntOrNull("accent"),
                    cards = o.optIntOrNull("cards"),
                    cardStyle = CardStyle.fromStored(o.getString("cardStyle")),
                    cardOpacity = o.getDouble("cardOpacity").toFloat(),
                    mapLook = o.optStringOrNull("mapLook"),
                    barStyle = BarStyle.fromStored(o.getString("barStyle")),
                    carColor = o.getInt("carColor"),
                )
                val page = PageSnapshot(
                    page = o.getInt("page"),
                    grid = PageSnapshot.grid(o.getString("grid")) ?: return null,
                    layout = o.getString("layout"),
                    background = PageBackground.fromStored(o.getString("background")),
                    template = o.optStringOrNull("template")?.let { n -> Template.entries.firstOrNull { it.name == n } },
                )
                DesignUndo(o.optStringOrNull("design"), look, page, o.getString("applied"), o.optBoolean("wallpaper"), o.optStringOrNull("pending"))
            } catch (e: JSONException) {
                null
            }
        }

        private fun JSONObject.optIntOrNull(key: String) = if (has(key) && !isNull(key)) getInt(key) else null
        private fun JSONObject.optStringOrNull(key: String) = if (has(key) && !isNull(key)) getString(key) else null
    }
}

/** El aviso «Diseño aplicado · Deshacer» del inicio: qué Diseño y cuántas veces se ha pedido (cada una dura 10 s). */
object DesignNotice {
    var design by mutableStateOf<Design?>(null)
    var shown by mutableIntStateOf(0)
        private set

    fun show(d: Design?) {
        design = d
        shown++
    }
}

/**
 * Cada aplicar o deshacer cuenta: lo que un Diseño sigue haciendo en segundo plano (copiar y bajar su fondo) se detiene
 * si después se aplicó otro o se deshizo.
 */
private var designTurn = 0

/**
 * Aplica [design] a la página que se ve (no toca las demás) y a lo de toda la app, de una vez, y deja listo «Deshacer».
 * Su fondo se baja después: tu foto se guarda aparte antes (una sola vez: el fondo de otro Diseño no la reemplaza); sin
 * internet todo lo demás ya quedó y el fondo es el resplandor del tema hasta que haya red ([prefs.designWallpaper], se
 * baja una sola vez). Con [keepSun], «Día y noche» se queda ([Design.over]) y el fondo es el de la pareja que toca ahora
 * ([dark] = la app va en oscuro). Desde el hilo principal.
 */
internal fun applyDesign(d: Dependencies, context: Context, scope: CoroutineScope, design: Design, dark: Boolean, keepSun: Boolean = true) {
    val prefs = d.prefs
    val before = d.dashboard.snapshot() ?: return
    val now = prefs.look
    prefs.put(design.over(now, ownCar = !prefs.carModel.isNullOrEmpty(), keepSun))
    d.dashboard.template(design.template)
    val applied = d.dashboard.snapshot()?.layout ?: return
    val undo = DesignUndo(prefs.design, now, before, applied, pending = prefs.designWallpaper)
    prefs.designUndo = undo.encode()
    prefs.design = design.id
    DesignNotice.show(design)
    val turn = ++designTurn
    // El fondo de otro Diseño que esperaba internet ya no: manda este, traiga fondo o no.
    prefs.designWallpaper = null
    val own = design.wallpaper ?: return
    // Con «Día y noche» puesto el tema no cambia: va la pareja del tema de ahora; si no, el fondo del Diseño tal cual.
    val id = if (keepSun && now.theme == ThemeMode.SUN) themedWallpaper(own, dark) ?: own else own
    scope.launch {
        if (!keepOwnWallpaper(context) || turn != designTurn) return@launch
        prefs.designUndo = undo.copy(wallpaper = true).encode()
        val got = putLiteDashWallpaper(context, id, byDesign = true) { turn == designTurn }
        // Su pareja se guarda de una vez: al anochecer (o amanecer) puede no haber red.
        if (got == WallpaperFetch.DONE) themedWallpaper(id, dark = themedWallpaper(id, true) == id)?.let { keepWallpaper(context, it) }
        if (got != WallpaperFetch.OFFLINE || turn != designTurn) return@launch
        // Sin internet: sin imagen (el resplandor del tema) hasta que haya red.
        clearWallpaper(context)
        prefs.designWallpaper = id
    }
}

/** Lo guardado para «Deshacer», leído una vez por texto (se pregunta en cada dibujo del aviso y del panel). */
private var undoRead: Pair<String, DesignUndo?>? = null

private fun undoOf(text: String?): DesignUndo? {
    if (text.isNullOrEmpty()) return null
    undoRead?.let { (from, undo) -> if (from == text) return undo }
    return DesignUndo.decode(text).also { undoRead = text to it }
}

/**
 * Lo de «Deshacer», si aún se puede: hay algo guardado y nadie ha movido a mano la página donde se aplicó (cambiar
 * una opción de un widget no cuenta: [DashboardState.unchanged] solo mira dónde está cada uno).
 */
internal fun designUndo(d: Dependencies): DesignUndo? =
    undoOf(d.prefs.designUndo)?.takeIf { d.dashboard.unchanged(it.page.page, it.page.grid, it.applied) }

/**
 * Regresa todo a como estaba antes del último Diseño (lo de la app y la página) y, si el Diseño cambió el fondo, tu foto
 * (salvo que hayas elegido otro fondo después: ese se queda). Desde el hilo principal.
 */
internal fun undoDesign(d: Dependencies, context: Context, scope: CoroutineScope) {
    val undo = designUndo(d) ?: return
    designTurn++
    d.prefs.put(undo.look)
    d.dashboard.restore(undo.page)
    d.prefs.design = undo.design
    // Con tu foto de vuelta, el fondo que otro Diseño esperaba ya no se pone encima.
    d.prefs.designWallpaper = if (undo.wallpaper) null else undo.pending
    d.prefs.designUndo = null
    DesignNotice.show(null)
    if (undo.wallpaper) scope.launch { restoreOwnWallpaper(context) }
}
