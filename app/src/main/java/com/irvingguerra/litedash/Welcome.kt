package com.irvingguerra.litedash

import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.focus.focusTarget
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Radius
import com.irvingguerra.litedash.core.ui.TextInput
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.core.ui.blockTouches
import com.irvingguerra.litedash.feature.car.CarColors
import com.irvingguerra.litedash.feature.car.CarDrawing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** ¿LiteDash ya es la pantalla de inicio? Consulta al sistema: fuera del hilo principal ([rememberIsHome]). */
fun isHomeApp(context: Context): Boolean {
    // Android 10+: el rol del sistema. Sin esto, desde Android 11 las otras apps de
    // inicio no se ven y LiteDash creería que es la única.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // Capas de Android recortadas no traen el rol (ahí se elige el inicio en sus ajustes, como pide
        // [requestHomeRole]) o truenan al preguntar: entonces se decide como antes de Android 10.
        try {
            context.getSystemService(RoleManager::class.java)
                ?.takeIf { it.isRoleAvailable(RoleManager.ROLE_HOME) }
                ?.let { return it.isRoleHeld(RoleManager.ROLE_HOME) }
        } catch (e: RuntimeException) {
            Log.w(LOG_TAG, "Rol de inicio ilegible", e)
        }
    }
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    val resolved = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
    return resolved?.activityInfo?.packageName == context.packageName
}

/**
 * [isHomeApp] preguntado en un hilo aparte (el sistema puede tardar en pantallas lentas) cada vez que cambian [keys]
 * (al volver a LiteDash o del diálogo); null mientras llega la primera respuesta.
 */
@Composable
internal fun rememberIsHome(vararg keys: Any): Boolean? {
    val context = LocalContext.current
    val isHome by produceState<Boolean?>(null, *keys) { value = withContext(Dispatchers.IO) { isHomeApp(context) } }
    return isHome
}

/** El último de los pasos de la bienvenida (Diseño, nombre y auto). */
private const val LAST_STEP = 2

/**
 * Primer arranque (solo instalaciones nuevas): 1) tu Diseño, 2) tu nombre, 3) tu auto (y hacer LiteDash tu pantalla de
 * inicio). Nada es obligatorio: «Ahora no» deja lo de fábrica. [onFinish] con true abre el catálogo de autos.
 */
@Composable
fun Welcome(d: Dependencies, revision: Int, onFinish: (catalog: Boolean) -> Unit) {
    val c = LocalColors.current
    val prefs = d.prefs
    var step by remember { mutableIntStateOf(prefs.welcomeStep.coerceIn(0, LAST_STEP)) }
    var language by remember { mutableStateOf(false) }
    // Los campos sueltan el foco aquí y no con clearFocus(): en Android ≤ 8 el foco regresaba al campo con todo y teclado.
    val rest = remember { FocusRequester() }
    val release: () -> Unit = { rest.requestFocus() }
    fun goTo(n: Int) {
        release()
        language = false
        step = n
        prefs.welcomeStep = n
    }
    // La única animación: el paso nuevo aparece en un instante.
    val shown = remember { Animatable(1f) }
    LaunchedEffect(step) {
        shown.snapTo(0f)
        shown.animateTo(1f, tween(STEP_FADE_MS))
    }
    BackHandler(enabled = step > 0) { goTo(step - 1) }
    FitShort { BoxWithConstraints {
        // Pantallas bajas (1280×480): títulos chicos y sin frase, para que quepa lo que se elige.
        val short = maxHeight < SHORT_WELCOME
        Column(
            Modifier
                .fillMaxSize()
                .canvas()
                .blockTouches()
                .focusRequester(rest)
                .focusTarget()
                .safeDrawingPadding()
                .padding(horizontal = 32.dp, vertical = if (short) 8.dp else 16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // Tres puntos: dónde vas.
                for (i in 0..LAST_STEP) {
                    Box(
                        Modifier
                            .padding(end = 8.dp)
                            .size(width = if (i == step) 28.dp else 10.dp, height = 10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(if (i <= step) c.accent else c.line),
                    )
                }
                Spacer(Modifier.weight(1f))
                // Si el sistema va en otro idioma que la app (aún sin su paquete), se elige aquí sin buscarlo en Ajustes.
                val system = remember { systemLanguages().firstOrNull() }
                if (step == 0 && (language || system != currentLocale().language)) {
                    TextLink(stringResource(if (language) R.string.welcome_design_title else R.string.language)) { language = !language }
                }
                TextLink(stringResource(R.string.not_now)) { release(); onFinish(false) }
            }
            Column(Modifier.weight(1f).fillMaxWidth().graphicsLayer { alpha = shown.value }.padding(top = if (short) 4.dp else 12.dp)) {
                when {
                    step == 0 && language -> Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Titles(stringResource(R.string.language), null, short)
                        LanguageSettings(prefs)
                    }
                    step == 0 -> {
                        Titles(stringResource(R.string.welcome_design_title), stringResource(R.string.welcome_design), short)
                        DesignStep(d, Modifier.weight(1f))
                    }
                    else -> Column(Modifier.verticalScroll(rememberScrollState())) {
                        if (step == 1) {
                            Titles(stringResource(R.string.your_name_hint), stringResource(R.string.your_name_help), short)
                            NameStep(prefs, release)
                        } else {
                            Titles(stringResource(R.string.your_car), stringResource(R.string.welcome_car), short)
                            CarStep(prefs, revision, short, release, onCatalog = { release(); onFinish(true) })
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = if (short) 6.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                if (step > 0) Button(stringResource(R.string.back), { goTo(step - 1) })
                if (step < LAST_STEP) Button(stringResource(R.string.next), { goTo(step + 1) }, primary = true)
                else Button(stringResource(R.string.done), { release(); onFinish(false) }, primary = true)
            }
        }
    } }
}

/** Por debajo de esta altura la bienvenida va compacta. */
private val SHORT_WELCOME = 440.dp

/** Lo que tarda el paso nuevo en aparecer. */
private const val STEP_FADE_MS = 160

/** Un enlace de texto discreto (arriba a la derecha). */
@Composable
private fun TextLink(text: String, onClick: () -> Unit) {
    Label(
        text, Typography.body, LocalColors.current.textMuted, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(Radius.inner)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun Titles(title: String, text: String?, short: Boolean) {
    Label(title, if (short) Typography.title else Typography.clock.copy(fontSize = Typography.title.fontSize * 1.6f), maxLines = 2)
    if (text != null && !short) {
        Spacer(Modifier.height(6.dp))
        Label(text, Typography.body, LocalColors.current.textMuted, modifier = Modifier.widthIn(max = 640.dp))
    }
    Spacer(Modifier.height(if (short) 10.dp else 20.dp))
}

/** El hueco entre las tarjetas de los Diseños, lo que mide su nombre (con su espacio arriba) y lo más chica que se deja una. */
private val DESIGN_GAP = 12.dp
private val DESIGN_NAME = 30.dp
private val DESIGN_MIN = 120.dp

/**
 * Paso 1: los 8 Diseños, el puesto marcado (Grafito de fábrica). Tocar uno lo aplica a la página como en el panel
 * Diseños, sin su aviso de «Deshacer» (aquí basta con tocar otro). Se elige cuántos van por fila (2, 4 u 8) para que
 * quepan todos sin desplazar y lo más grandes posible; en pantallas muy bajas, una fila que se desliza.
 */
@Composable
private fun DesignStep(d: Dependencies, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val grid = d.dashboard.grid ?: return
    val current = d.prefs.design
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val (columns, width) = designFit(maxWidth, maxHeight, grid.columns.toFloat() / grid.rows)
        FlowRow(
            if (columns == DESIGNS.size) Modifier.horizontalScroll(rememberScrollState()) else Modifier.verticalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(DESIGN_GAP),
            verticalArrangement = Arrangement.spacedBy(DESIGN_GAP),
            maxItemsInEachRow = columns,
        ) {
            for (design in DESIGNS) {
                DesignCard(design, grid, d.dashboard, design.id == current, changed = false, Modifier.width(width), brief = true) {
                    if (d.prefs.design != design.id) {
                        // Instalación nueva: el tema del Diseño tal cual (aquí se van probando varios).
                        applyDesign(d, context, scope, design, dark = design.theme == ThemeMode.DARK, keepSun = false)
                        DesignNotice.design = null
                    }
                }
            }
        }
    }
}

/** Cuántos Diseños por fila y de qué ancho para que quepan los 8 en [w]×[h] lo más grandes posible ([ratio] = ancho/alto). */
private fun designFit(w: Dp, h: Dp, ratio: Float): Pair<Int, Dp> {
    val pad = BRIEF_PADDING * 2
    val (columns, width) = listOf(2, 4, 8).map { columns ->
        val rows = (DESIGNS.size + columns - 1) / columns
        val byWidth = (w - DESIGN_GAP * (columns - 1)) / columns
        val byHeight = ((h - DESIGN_GAP * (rows - 1)) / rows - pad - DESIGN_NAME) * ratio + pad
        columns to minOf(byWidth, byHeight)
    }.maxBy { it.second.value }
    // Si ni así salen de buen tamaño (pantalla muy baja), todos en una fila del alto que hay, que se desliza.
    return if (width >= DESIGN_MIN) columns to width
    else DESIGNS.size to ((h - pad - DESIGN_NAME) * ratio + pad).coerceAtLeast(DESIGN_MIN)
}

/** Desde este ancho los pasos 2 y 3 van en dos columnas. */
private val WIDE_STEP = 640.dp

/** Paso 2: tu nombre (opcional) y cómo te va a saludar el inicio. */
@Composable
private fun NameStep(prefs: Preferences, release: () -> Unit) {
    val c = LocalColors.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val field = @Composable { m: Modifier -> DriverNameField(prefs, m, stringResource(R.string.your_name), onDone = release) }
        val preview = @Composable { m: Modifier ->
            Card(m) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val name = prefs.driverName
                    Label(greeting(name ?: "…").orEmpty(), Typography.title, if (name == null) c.textMuted else c.text, maxLines = 1)
                    CarDrawing(prefs.carColor, Modifier.align(Alignment.CenterHorizontally).widthIn(max = 300.dp).fillMaxWidth())
                }
            }
        }
        if (maxWidth > WIDE_STEP) {
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                field(Modifier.weight(1f))
                preview(Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                field(Modifier.fillMaxWidth())
                preview(Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * Paso 3: tu auto. De fábrica hoy solo está el sedán: tres colores que van con tu Diseño, su nombre (opcional), «Ver el
 * catálogo» (tu modelo exacto, al terminar) y hacer LiteDash tu pantalla de inicio.
 */
@Composable
private fun CarStep(prefs: Preferences, revision: Int, short: Boolean, release: () -> Unit, onCatalog: () -> Unit) {
    val c = LocalColors.current
    val design = designOf(prefs.design) ?: FACTORY_DESIGN
    val shape = RoundedCornerShape(Radius.card)
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        for (color in remember(design) { design.carColors() }) {
            val chosen = prefs.carColor == color
            Card(
                Modifier.weight(1f, fill = false).widthIn(max = if (short) 200.dp else 340.dp).border(if (chosen) 3.dp else 0.dp, if (chosen) c.accent else Color.Transparent, shape),
                onClick = { prefs.carColor = color },
            ) {
                CarDrawing(color, Modifier.fillMaxWidth().padding(16.dp))
            }
        }
    }
    Spacer(Modifier.height(20.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        CarNameField(prefs, stringResource(R.string.car_name), Modifier.widthIn(min = 220.dp, max = 420.dp), onDone = release)
        Button(stringResource(R.string.see_catalog), onCatalog)
    }
    Spacer(Modifier.height(20.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Label(stringResource(R.string.welcome_home_title), Typography.body, c.textMuted)
        MakeHome(revision, primary = false)
    }
}

/** Tres colores para tu auto que van con el Diseño: el suyo, su realce (si tiene) y uno neutro. */
internal fun Design.carColors(): List<Color> =
    (listOfNotNull(carColor, accent, if (theme == ThemeMode.LIGHT) CarColors[1] else CarColors[3]) + CarColors).distinct().take(3)

/**
 * Pide ser la pantalla de inicio: el diálogo del sistema (Android 10+) y, si la
 * pantalla no lo tiene, sus ajustes de inicio o de apps predeterminadas.
 * Devuelve la acción y un contador que sube al volver del diálogo.
 */
@Composable
fun rememberRequestHome(): Pair<() -> Unit, Int> {
    val context = LocalContext.current
    var returns by remember { mutableIntStateOf(0) }
    val requestRole = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { returns++ }
    val request = {
        if (!requestHomeRole(context) { requestRole.launch(it) }) {
            // "Apps predeterminadas" existe desde Android 7.
            val defaults = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS else null
            listOfNotNull(Settings.ACTION_HOME_SETTINGS, defaults, Settings.ACTION_SETTINGS)
                .firstOrNull { action -> openSystemScreen(context, Intent(action)) }
        }
        Unit
    }
    return request to returns
}

/**
 * El diálogo del sistema para ser la pantalla de inicio (Android 10+), lanzado con [launch]. false si esta pantalla
 * no lo tiene (Android 9 o menos, o una capa de la marca sin ese rol): entonces se abren sus ajustes.
 */
private fun requestHomeRole(context: Context, launch: (Intent) -> Unit): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
    val roles = context.getSystemService(RoleManager::class.java) ?: return false
    return try {
        if (!roles.isRoleAvailable(RoleManager.ROLE_HOME)) return false
        launch(roles.createRequestRoleIntent(RoleManager.ROLE_HOME))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}

/**
 * "Hazlo tu pantalla de inicio": si ya lo es, lo dice; si no, el botón para pedirlo ([beforeRequest] corre antes).
 * Se vuelve a revisar al regresar de Ajustes ([revision] cambia en onResume) o del diálogo. [primary]: sin otro botón
 * principal al lado (en la bienvenida, «Listo» lo es).
 */
@Composable
internal fun MakeHome(revision: Int, primary: Boolean = true, beforeRequest: () -> Unit = {}) {
    val (request, returns) = rememberRequestHome()
    // Mientras se pregunta al sistema, nada (sin asomar el botón un instante).
    val isHome = rememberIsHome(revision, returns) ?: return
    if (isHome) Label(stringResource(R.string.welcome_already_home), Typography.body, LocalColors.current.accent)
    else Button(stringResource(R.string.make_home), { beforeRequest(); request() }, primary = primary)
}

/** Tu nombre (para el saludo del inicio): hasta 24 letras; vacío = sin nombre. */
@Composable
internal fun DriverNameField(
    prefs: Preferences,
    modifier: Modifier = Modifier,
    hint: String = stringResource(R.string.your_name_hint),
    onDone: (() -> Unit)? = null,
) {
    TextInput(prefs.driverName ?: "", { prefs.driverName = nameOrNull(it) }, hint, modifier.fillMaxWidth(), onDone = onDone)
}

/** El nombre de tu auto: hasta 24 letras; vacío = el del modelo ([hint]). */
@Composable
internal fun CarNameField(prefs: Preferences, hint: String, modifier: Modifier = Modifier, onDone: (() -> Unit)? = null) {
    TextInput(prefs.carName ?: "", { prefs.carName = nameOrNull(it) }, hint, modifier.fillMaxWidth(), onDone = onDone)
}

/** Un nombre escrito, recortado a 24 letras; null si quedó en blanco. */
private fun nameOrNull(text: String) = text.take(24).ifBlank { null }

/** El color de tu auto, con vista previa en vivo mientras lo eliges. */
@Composable
internal fun CarColorPicker(prefs: Preferences) {
    ColorPicker(prefs.carColor, prefs::previewCarColor) { prefs.carColor = it }
}
