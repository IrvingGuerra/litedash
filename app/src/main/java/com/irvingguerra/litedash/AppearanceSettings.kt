// Ajustes → Inicio y apariencia: inicio, idioma y unidades, barra, interfaz y tema y brillo.
package com.irvingguerra.litedash

import androidx.compose.runtime.saveable.rememberSaveable
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.irvingguerra.litedash.core.ui.Brand
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.CardLook
import com.irvingguerra.litedash.core.ui.CardStyle
import com.irvingguerra.litedash.core.ui.DarkColors
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LightColors
import com.irvingguerra.litedash.core.ui.LocalCardLook
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.MIN_OPACITY
import com.irvingguerra.litedash.core.ui.Radius
import com.irvingguerra.litedash.core.ui.Segmented
import com.irvingguerra.litedash.core.ui.SwitchRow
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.core.ui.cardTone
import kotlin.math.round
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Los temas a elegir, en Inicio y apariencia y en la bienvenida: oscuro, claro, día y noche y el del sistema. */
internal val ThemeOptions = listOf(
    ThemeMode.DARK to R.string.theme_dark,
    ThemeMode.LIGHT to R.string.theme_light,
    ThemeMode.SUN to R.string.theme_sun,
    ThemeMode.SYSTEM to R.string.theme_system,
)

/**
 * Pestañas de Inicio y apariencia, en el orden en que se busca: el inicio (acomodo, fondo, entrada, tu nombre, modo
 * estacionado y hacerlo tu inicio), idioma y unidades, las barras, la interfaz (tarjetas y tamaño) y tema y brillo.
 */
private enum class AppearanceTab(val title: Int) {
    HOME(R.string.tab_home), LANGUAGE_UNITS(R.string.tab_language_units), BAR(R.string.bar_edit), INTERFACE(R.string.tab_interface),
    THEME_BRIGHTNESS(R.string.tab_theme_brightness),
}

/** Las pestañas de Inicio y apariencia ([AppearanceTab]). */
private const val APPEARANCE_TABS = "appearance"

/** Ajustes → Inicio y apariencia abre en Idioma y unidades (al volver tras cambiar de idioma). */
internal fun openLanguageSettings() = selectTab(APPEARANCE_TABS, AppearanceTab.LANGUAGE_UNITS.ordinal)

/** Ajustes → Inicio y apariencia abre en Interfaz («Personalizar» en Diseños). */
internal fun openInterfaceSettings() = selectTab(APPEARANCE_TABS, AppearanceTab.INTERFACE.ordinal)

@Composable
internal fun Appearance(d: Dependencies, revision: Int, onEdit: () -> Unit, onDesigns: () -> Unit, onAddTile: () -> Unit) {
    when (AppearanceTab.entries[tabs(AppearanceTab.entries.map { it.title }, APPEARANCE_TABS).coerceAtMost(AppearanceTab.entries.lastIndex)]) {
        AppearanceTab.HOME -> HomeSettings(d, revision, onEdit, onDesigns, onAddTile)
        AppearanceTab.LANGUAGE_UNITS -> {
            // Unidades arriba: miden siempre lo mismo y así no quedan hasta abajo de la lista de idiomas, que crece.
            SettingsCard(stringResource(R.string.units_title)) { UnitsSettings(d.prefs) }
            SettingsCard(stringResource(R.string.language)) { LanguageSettings(d.prefs) }
        }
        AppearanceTab.BAR -> BarSettingsTab(d)
        AppearanceTab.INTERFACE -> InterfaceSettings(d.prefs)
        AppearanceTab.THEME_BRIGHTNESS -> ThemeBrightnessSettings(d.prefs, revision)
    }
}

/** Interfaz: sus tarjetas y colores y su tamaño. */
@Composable
private fun InterfaceSettings(prefs: Preferences) {
    val c = LocalColors.current
    SettingsCard(stringResource(R.string.cards_title), stringResource(R.string.cards_help)) { CardSettings(prefs) }
    SettingsCard(stringResource(R.string.scale), stringResource(R.string.scale_help)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconButton(Icons.minus, { prefs.scale = (prefs.scale - 0.1f).coerceAtLeast(0.8f).roundToTenth() }, background = c.surfaceHigh)
            Label(stringResource(R.string.scale_percent, (prefs.scale * 100).roundToInt()), Typography.title, modifier = Modifier.widthIn(min = 90.dp))
            IconButton(Icons.plus, { prefs.scale = (prefs.scale + 0.1f).coerceAtMost(1.5f).roundToTenth() }, background = c.surfaceHigh)
        }
    }
}

/** Los colores rápidos de la interfaz (vivos, para verse sobre oscuro); el claro los oscurece un poco. */
private val ACCENTS = listOf(
    Brand.Amber, Color(0xFFFF453A), Color(0xFFFF7A1A), Color(0xFF30D158),
    Color(0xFF2BD4E8), Color(0xFF0A84FF), Color(0xFFBF5AF2), Color(0xFFFF4F9A),
)

/** El color de la interfaz: el de fábrica, los rápidos o uno libre. */
@Composable
private fun AccentSettings(prefs: Preferences) {
    val c = LocalColors.current
    val factory = stringResource(R.string.factory_look)
    // De fábrica: mitad blanco (o negro) y mitad ámbar.
    val split = if (c.dark) DarkColors else LightColors
    val leading = @Composable {
        Swatch(
            prefs.accentColor == null,
            halves(split.primary, split.accent),
            Modifier.semantics { contentDescription = factory },
        ) { prefs.accentColor = null }
    }
    ColorPicker(prefs.accentColor ?: Color.Unspecified, prefs::previewAccent, ACCENTS, leading, startClosed = true) { prefs.accentColor = it }
}

/** Lo que dice la tarjeta de muestra (una hora: se lee igual en cualquier idioma). */
private const val SAMPLE_TIME = "12:45"

/**
 * Colores rápidos de las tarjetas (y del mapa con «Color propio»): tonos vivos; cada tema los lleva a su claridad
 * (profundos en oscuro, pastel en claro).
 */
internal val CARD_COLORS = listOf(
    Color(0xFF2B2F36), Color(0xFF1F4FD8), Color(0xFF0E8F9A), Color(0xFF1E8E4E),
    Color(0xFFB89B6A), Color(0xFFD9601A), Color(0xFFB3243C), Color(0xFF7A3FE0),
)

/** Combinaciones listas de un toque: el color de las tarjetas y el de la interfaz que le va. */
private val CARD_THEMES = listOf(
    Color(0xFF1F4FD8) to Color(0xFF2BD4E8),
    Color(0xFF1E8E4E) to Color(0xFF9BE15D),
    Color(0xFFB3243C) to Color(0xFFFF7A8A),
    Color(0xFFD9601A) to Color(0xFFFFC857),
    Color(0xFF7A3FE0) to Color(0xFFFF4F9A),
    Color(0xFFB89B6A) to Color(0xFFFF7A1A),
)

/**
 * Las tarjetas de toda la app: una muestra sobre tu fondo, lisas o de vidrio, cuánto se ven, combinaciones listas y
 * su color. Lo que se elige se ve al momento también aquí: Ajustes lleva la misma piel.
 */
@Composable
private fun CardSettings(prefs: Preferences) {
    val c = LocalColors.current
    // La muestra es tu inicio de verdad: el pedazo de tu fondo que cae ahí (o el del tema) y una tarjeta con la misma
    // piel, que de vidrio lleva tu fondo desenfocado detrás, igual que los widgets.
    val backdrop = rememberWallpaperBackdrop(prefs.cardStyle == CardStyle.GLASS) { prefs.wallpaperDim }
    val look = remember(prefs.cardStyle, prefs.cardOpacity, backdrop) { CardLook(prefs.cardStyle, prefs.cardOpacity, backdrop) }
    Box(Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(Radius.card)).background(c.background)) {
        HomeWallpaper(prefs, asInWindow = true)
        CompositionLocalProvider(LocalCardLook provides look) {
            Card(Modifier.align(Alignment.Center).fillMaxWidth(0.6f).height(84.dp)) {
                Label(SAMPLE_TIME, Typography.title, LocalColors.current.text, modifier = Modifier.align(Alignment.Center))
            }
        }
    }
    Segmented(
        listOf(CardStyle.GLASS to stringResource(R.string.cards_glass), CardStyle.SOLID to stringResource(R.string.cards_solid)),
        prefs.cardStyle,
        { prefs.cardStyle = it },
    )
    if (prefs.cardStyle == CardStyle.GLASS) {
        Label(stringResource(R.string.cards_opacity), Typography.label, c.textMuted)
        // El deslizador va de lo más transparente que se permite a sólida.
        val toSlider = { o: Float -> ((o - MIN_OPACITY) / (1f - MIN_OPACITY)).coerceIn(0f, 1f) }
        val fromSlider = { v: Float -> MIN_OPACITY + v * (1f - MIN_OPACITY) }
        LevelSlider(toSlider(prefs.cardOpacity), { prefs.previewCardOpacity(fromSlider(it)) }) { prefs.cardOpacity = fromSlider(it) }
        Label(stringResource(R.string.cards_glass_help), Typography.small, c.textMuted)
    }
    Label(stringResource(R.string.cards_themes), Typography.label, c.textMuted)
    val themeLabel = stringResource(R.string.factory_look)
    val otherLabel = stringResource(R.string.other_color)
    val matched = prefs.cardColor == null && prefs.accentColor == null || CARD_THEMES.any { (card, accent) -> prefs.cardColor == card && prefs.accentColor == accent }
    // Los colores sueltos van abajo y cerrados: se abren al tocar la paleta (el último círculo).
    var custom by rememberSaveable { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // El de fábrica: grafito con el ámbar.
        val natural = if (c.dark) DarkColors else LightColors
        Swatch(prefs.cardColor == null && prefs.accentColor == null, halves(natural.surface, natural.accent), Modifier.semantics { contentDescription = themeLabel }) {
            prefs.cardColor = null
            prefs.accentColor = null
        }
        for ((card, accent) in CARD_THEMES) {
            Swatch(prefs.cardColor == card && prefs.accentColor == accent, halves(cardTone(card, c.dark), accent)) {
                prefs.cardColor = card
                prefs.accentColor = accent
            }
        }
        Swatch(custom || !matched, Brush.sweepGradient(RAINBOW), Modifier.semantics { contentDescription = otherLabel }) { custom = !custom }
    }
    if (custom) {
        Label(stringResource(R.string.cards_title), Typography.label, c.textMuted)
        val leading = @Composable {
            Swatch(prefs.cardColor == null, SolidColor(if (c.dark) DarkColors.surface else LightColors.surface), Modifier.semantics { contentDescription = themeLabel }) { prefs.cardColor = null }
        }
        ColorPicker(prefs.cardColor ?: Color.Unspecified, prefs::previewCardColor, CARD_COLORS, leading, startClosed = true) { prefs.cardColor = it }
        Label(stringResource(R.string.accent_title), Typography.label, c.textMuted)
        AccentSettings(prefs)
        Label(stringResource(R.string.accent_help), Typography.small, c.textMuted)
    }
}

/** Un círculo partido en dos colores (una combinación: tarjetas e interfaz). */
private fun halves(a: Color, b: Color) = Brush.linearGradient(0f to a, 0.5f to a, 0.5f to b, 1f to b)

/**
 * Tema y brillo: el tema, el brillo y, solo si alguno de los dos va por día y noche, cuándo es de noche (si no, no
 * decide nada y solo se dice para qué sirve).
 */
@Composable
private fun ThemeBrightnessSettings(prefs: Preferences, revision: Int) {
    val c = LocalColors.current
    val context = LocalContext.current
    SettingsCard(stringResource(R.string.theme)) {
        Segmented(
            ThemeOptions.map { (mode, text) -> mode to stringResource(text) },
            prefs.theme,
            { prefs.theme = it },
        )
    }
    SettingsCard(stringResource(R.string.brightness), stringResource(R.string.brightness_help)) {
        BrightnessSettings(prefs, revision)
    }
    if (prefs.theme != ThemeMode.SUN && !prefs.brightnessBySun) {
        return SettingsCard(stringResource(R.string.night_source)) {
            // Con las luces aprendidas (o elegidas) que se note que siguen ahí y cómo volver a usarlas.
            if (prefs.lightsKey != null || prefs.nightSource == NightSource.LIGHTS) {
                Label(stringResource(R.string.night_source_lights_unused), Typography.small, c.textMuted)
                Button(stringResource(R.string.night_use_day_night), { prefs.theme = ThemeMode.SUN })
            } else {
                Label(stringResource(R.string.night_source_unused), Typography.small, c.textMuted)
            }
        }
    }
    SettingsCard(stringResource(R.string.night_source), stringResource(R.string.night_source_help)) {
        val hasSensor = remember { context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_LIGHT) != null }
        val options = listOfNotNull(
            NightSource.SUN to R.string.night_sun,
            (NightSource.SENSOR to R.string.night_sensor).takeIf { hasSensor },
            NightSource.LIGHTS to R.string.night_lights,
        )
        // Sin sensor, lo guardado como Sensor se comporta como Hora del sol (lo de otra pantalla o un respaldo).
        val shown = if (!hasSensor && prefs.nightSource == NightSource.SENSOR) NightSource.SUN else prefs.nightSource
        Segmented(options.map { (s, text) -> s to stringResource(text) }, shown, { prefs.nightSource = it })
        if (!hasSensor) Label(stringResource(R.string.night_no_sensor), Typography.small, c.textMuted)
        if (prefs.nightSource == NightSource.LIGHTS) {
            Label(stringResource(R.string.lights_help), Typography.small, c.textMuted)
            LightsSettings(prefs)
        }
    }
}

/** Unidades: velocidad y distancia y temperatura (la presión va en Llantas). Automático = según idioma y región. */
@Composable
private fun UnitsSettings(prefs: Preferences) {
    val c = LocalColors.current
    val auto = imperialUnits(prefs.language)
    Label(stringResource(R.string.units_speed), Typography.label, c.textMuted)
    Segmented(
        listOf(
            SpeedUnits.AUTO to stringResource(R.string.units_auto),
            SpeedUnits.METRIC to stringResource(R.string.units_metric),
            SpeedUnits.IMPERIAL to stringResource(R.string.units_imperial),
        ),
        prefs.speedUnits,
        { prefs.speedUnits = it },
    )
    Label(stringResource(R.string.units_temp), Typography.label, c.textMuted)
    Segmented(
        listOf(
            TempUnits.AUTO to stringResource(R.string.units_auto),
            TempUnits.CELSIUS to stringResource(R.string.unit_c),
            TempUnits.FAHRENHEIT to stringResource(R.string.unit_f),
        ),
        prefs.tempUnits,
        { prefs.tempUnits = it },
    )
    if (prefs.speedUnits == SpeedUnits.AUTO || prefs.tempUnits == TempUnits.AUTO) {
        Label(
            stringResource(
                R.string.units_auto_now,
                stringResource(if (auto) R.string.unit_mph else R.string.unit_kmh),
                stringResource(if (auto) R.string.unit_f else R.string.unit_c),
            ),
            Typography.small, c.textMuted,
        )
    }
}

/**
 * Inicio: el Diseño, acomodo (acomodar, acomodos y agregar widgets), el fondo de la primera página, la animación de
 * entrada, tu nombre, el modo estacionado y hacerlo tu pantalla de inicio.
 */
@Composable
private fun HomeSettings(d: Dependencies, revision: Int, onEdit: () -> Unit, onDesigns: () -> Unit, onAddTile: () -> Unit) {
    val prefs = d.prefs
    DesignSettingsCard(d, onDesigns)
    SettingsCard(stringResource(R.string.layout), stringResource(R.string.arrange_help)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(stringResource(R.string.arrange), onEdit, primary = true)
            Button(stringResource(R.string.add_widget), onAddTile)
        }
    }
    // El mismo panel Fondo que al acomodar, de la primera página (la de siempre); las demás se eligen al acomodar.
    val pages = d.dashboard
    SettingsCard(if (pages.pageCount > 1) stringResource(R.string.page_background_title, 1) else stringResource(R.string.wallpaper_title)) {
        PageBackgroundSettings(d, page = 0)
    }
    // Sin título: el interruptor ya dice qué es.
    SettingsCard { IntroSettings(prefs) }
    SettingsCard(stringResource(R.string.your_name), stringResource(R.string.your_name_help)) {
        DriverNameField(prefs)
    }
    SettingsCard { ParkedSettings(prefs) }
    SettingsCard(stringResource(R.string.welcome_home_title)) {
        // Al pedirlo a mano se vuelve a preguntar al abrir si no queda como inicio.
        MakeHome(revision) { prefs.dontAskHome = false }
        OpenOnBootSettings(prefs)
    }
}

/**
 * "Abrir LiteDash al encender": para pantallas que regresan su launcher de fábrica al encender. Desde
 * Android 10 necesita "Mostrar sobre otras apps"; si falta, se pide al prender y se avisa.
 */
@Composable
private fun OpenOnBootSettings(prefs: Preferences) {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(BootLaunch.allowed(context)) }
    // Al volver de Ajustes de Android (donde se da el permiso) se revisa otra vez.
    LifecycleResumeEffect(Unit) {
        allowed = BootLaunch.allowed(context)
        onPauseOrDispose {}
    }
    val scope = rememberCoroutineScope()
    SwitchRow(stringResource(R.string.open_on_boot), prefs.openOnBoot, { on ->
        prefs.openOnBoot = on
        // Cambia un componente en el PackageManager: fuera del hilo principal, uno a la vez y con lo último que se eligió
        // (dos toques seguidos no quedan al revés).
        scope.launch(Dispatchers.IO) { bootSync.withLock { BootLaunch.sync(context, prefs.openOnBoot) } }
        if (on && !BootLaunch.allowed(context)) openSystemScreen(context, BootLaunch.permissionIntent(context))
    }, stringResource(R.string.open_on_boot_help))
    if (prefs.openOnBoot && !allowed) {
        Label(stringResource(R.string.open_on_boot_permission), Typography.small, LocalColors.current.danger)
        Button(stringResource(R.string.open_on_boot_grant), { openSystemScreen(context, BootLaunch.permissionIntent(context)) })
    }
}

/** Uno a la vez al prender o apagar el receptor de encendido ([OpenOnBootSettings]). */
private val bootSync = Mutex()

private fun Float.roundToTenth() = round(this * 10) / 10f
