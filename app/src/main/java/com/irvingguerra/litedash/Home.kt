package com.irvingguerra.litedash

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.CardLook
import com.irvingguerra.litedash.core.ui.CardStyle
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LiteDashTheme
import com.irvingguerra.litedash.core.ui.LocalCardLook
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Radius
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.core.ui.blockTouches
import com.irvingguerra.litedash.core.vehicle.Signal
import com.irvingguerra.litedash.core.vehicle.VehicleBus
import com.irvingguerra.litedash.feature.apps.App
import com.irvingguerra.litedash.feature.apps.AppRepository
import com.irvingguerra.litedash.feature.apps.rememberApps
import com.irvingguerra.litedash.feature.car.CarPackage
import com.irvingguerra.litedash.feature.car.CarRepository
import com.irvingguerra.litedash.feature.dashboard.Dashboard
import com.irvingguerra.litedash.feature.dashboard.DashboardState
import com.irvingguerra.litedash.feature.dashboard.Grid
import com.irvingguerra.litedash.feature.dashboard.PageBackground
import com.irvingguerra.litedash.feature.dashboard.Tile
import com.irvingguerra.litedash.feature.dashboard.TileTypes
import com.irvingguerra.litedash.feature.link.PhoneLink
import com.irvingguerra.litedash.feature.music.MusicMode
import com.irvingguerra.litedash.feature.music.MusicRepository
import com.irvingguerra.litedash.feature.music.MusicTile
import com.irvingguerra.litedash.feature.music.OnFilesEmpty
import com.irvingguerra.litedash.feature.music.ParkedSong
import com.irvingguerra.litedash.feature.music.RadioTile
import com.irvingguerra.litedash.feature.music.SoundPiece
import com.irvingguerra.litedash.feature.navigation.NavigationRepository
import com.irvingguerra.litedash.feature.obd.ObdRepository
import com.irvingguerra.litedash.feature.tpms.TpmsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * Lo que se ve en grande sobre el tablero, a pantalla completa (la barra de apps se queda): crece desde su widget
 * (o desde el centro, si se abrió con la barra o el volante).
 */
enum class Expanded { MEDIA, MAP }

/** Qué panel está abierto encima del inicio (uno a la vez). */
sealed interface Panel {
    data object Drawer : Panel
    data object Settings : Panel
    /** Ajustes abiertos en Acerca de y ayuda → Acerca de (desde el aviso de versión nueva). */
    data object Updates : Panel
    /** Ajustes abiertos en Acerca de y ayuda → Reportar un problema, en "Enviar un reporte" (desde el aviso de la calibración del sonido). */
    data object Report : Panel
    /** Ajustes abiertos en Inicio y apariencia (en su pestaña Idioma y unidades), al volver de cambiar el idioma. */
    data object Language : Panel
    /** Ajustes abiertos en Inicio y apariencia → Interfaz («Personalizar» en Diseños). */
    data object Appearance : Panel
    data object AddTile : Panel
    data object Designs : Panel
    /** El fondo de pantalla del inicio (botón "Fondo" al acomodar). */
    data object Wallpaper : Panel
    /** Lo de la barra de apps (botón "Barra" al acomodarla): agregar botones y apps, posición, tamaño, estilo. */
    data object BarSettings : Panel
    /** Elegir app para una pieza existente ([tileId]) o para una nueva (null); al elegir, regresa a [back]. */
    data class PickApp(val tileId: String?, val back: Panel? = null) : Panel
    /** Ajustes abiertos en el Taller (pieza de acceso directo). */
    data object Workshop : Panel
    /** Ajustes abiertos en Apps (para enlazar la radio desde la radio en grande, o bajar tu estado desde la ruta). */
    data object AppsSettings : Panel
    /** Elegir qué dato muestra un medidor existente ([tileId]) o uno nuevo (null). */
    data class PickGauge(val tileId: String?, val back: Panel? = null) : Panel
    /** Estilo, color y demás de un velocímetro o medidor ([tileId]). */
    data class CustomizeGauge(val tileId: String) : Panel
    /** La foto de una pieza de imagen ([tileId]). */
    data class CustomizeImage(val tileId: String) : Panel
    /** Orientación, vista, zoom y colores del widget Mapa ([tileId]). */
    data class CustomizeMap(val tileId: String) : Panel
    /** La presión de las llantas sobre el auto 3D ([tileId]). */
    data class CustomizeCar(val tileId: String) : Panel
    /** Las opciones de un widget sin panel propio ([WIDGET_OPTIONS]): reloj, clima, música… */
    data class CustomizeWidget(val tileId: String) : Panel
    /** Ajustes abiertos en Llantas (desde la vista de llantas o la pieza sin configurar). */
    data object TiresSettings : Panel
    /** Ajustes abiertos en Celular, donde se vincula LiteDash Connect (desde «Controla la música desde tu celular»). */
    data object PhoneSettings : Panel
}

/**
 * El panel de opciones de un widget ([type], [id]) al tocar su engrane; null = no tiene opciones propias y no lleva
 * engrane (Android Auto / CarPlay se elige en Ajustes → Apps). Solo los medidores van al panel del medidor.
 */
internal fun optionsPanelOf(type: String, id: String): Panel? = when (type) {
    TileTypes.IMAGE -> Panel.CustomizeImage(id)
    TileTypes.MAP -> Panel.CustomizeMap(id)
    TileTypes.CAR -> Panel.CustomizeCar(id)
    in WIDGET_OPTIONS -> Panel.CustomizeWidget(id)
    TileTypes.SPEEDOMETER, TileTypes.GAUGE, TileTypes.ROAD -> Panel.CustomizeGauge(id)
    else -> null
}

class Dependencies(
    val apps: AppRepository,
    val music: MusicRepository,
    val navigation: NavigationRepository,
    val prefs: Preferences,
    val dashboard: DashboardState,
    val cars: CarRepository,
    /** El actualizador: solo en la versión de GitHub (null en la de Play). */
    val updates: AppUpdates?,
    val weather: WeatherRepository,
    val trip: TripRepository,
    val reminders: ReminderRepository,
    val wheel: WheelKeys,
    val stats: Stats,
    /** LiteDash Pro (en prueba): [ProAccess.isPro] lo leen las funciones Pro. */
    val pro: ProAccess,
    /** Señales del auto (OBD, GPS…): el tablero solo lee de aquí. */
    val bus: VehicleBus,
    private val obdLazy: Lazy<ObdRepository>,
    private val obdSaved: () -> Boolean,
    private val tpmsLazy: Lazy<TpmsRepository>,
    private val tpmsSaved: () -> Boolean,
    private val phoneLazy: Lazy<PhoneLink>,
) {
    /** Estado de Compose: las piezas que preguntaron antes de que existiera se vuelven a dibujar. */
    private val obdCreated = mutableStateOf(false)

    /** El escáner OBD: se crea la primera vez que algo lo pide (el Taller o una pieza). */
    val obd: ObdRepository
        get() = obdLazy.value.also { if (!obdCreated.value) obdCreated.value = true }

    /** Ya se usó un adaptador OBD alguna vez (sin crear el escáner para saberlo). */
    val hasObdAdapter get() = obdSaved()

    /**
     * El módulo OBD prendido (de fábrica, si ya hay adaptador). Apagado nada se conecta solo;
     * apagarlo desconecta.
     */
    var obdModule: Boolean
        get() = prefs.obdModule ?: hasObdAdapter
        set(on) {
            prefs.obdModule = on
            if (!on) obdIfCreated()?.disconnect()
        }

    /** Hay adaptador y el módulo está prendido: las piezas pueden conectarse solas. */
    val obdAuto get() = hasObdAdapter && obdModule

    /** El escáner si ya existe, sin crearlo (una pieza solo quiere saber si está conectado). */
    fun obdIfCreated(): ObdRepository? = if (obdCreated.value) obdLazy.value else null

    /**
     * El enlace con LiteDash Connect (Ajustes → Celular). Se arma al volver a LiteDash solo si hay un celular
     * vinculado, o al abrir Ajustes → Celular (el QR); sin vínculo no escucha la red ni busca nada.
     */
    val phone: PhoneLink get() = phoneLazy.value

    /** El enlace si ya existe, sin crearlo (para el reporte). */
    fun phoneIfCreated(): PhoneLink? = if (phoneLazy.isInitialized()) phoneLazy.value else null

    private val tpmsCreated = mutableStateOf(false)

    init {
        // Las piezas de un módulo apagado no se ven ni apartan su lugar (ni las ponen las plantillas).
        dashboard.hidden = { type -> moduleOff(this, type) != null }
        dashboard.limits = ::limitsOf
    }

    /** Las llantas: se crean la primera vez que algo las pide (sus Ajustes o una pieza con fuente elegida). */
    val tpms: TpmsRepository
        get() = tpmsLazy.value.also { if (!tpmsCreated.value) tpmsCreated.value = true }

    /** Las llantas si ya existen, sin crearlas (el estado para el celular solo lee lo que ya corre). */
    fun tpmsIfCreated(): TpmsRepository? = if (tpmsCreated.value) tpmsLazy.value else null

    /**
     * Hay una fuente de llantas elegida. Sin crear el repositorio para saberlo; ya creado, se
     * lee su estado (al elegir o quitar la fuente, el inicio se entera solo).
     */
    val hasTpmsSource: Boolean
        get() = if (tpmsCreated.value) tpmsLazy.value.source != null else tpmsSaved()
}

/**
 * Qué hace cada pieza al tocarla (abrir un panel, el mapa o el auto en grande). Se crea
 * una vez: sus funciones leen el estado del inicio al llamarse.
 */
class TileActions(
    val onPanel: (Panel) -> Unit,
    val onNavigate: (App?) -> Unit,
    /** Ver en grande el auto, la carretera o la imagen de esta pieza. */
    val onCarOnly: (Tile) -> Unit,
    /** Dónde está cada pieza que crece (por id): de ahí crece al verla sola. */
    val onCarBounds: (String, Rect) -> Unit,
    /** Si la pieza (por id) se esconde porque se ve en grande; se lee al dibujar (no recompone el tablero en cada cuadro de la animación). */
    val carHidden: (String) -> Boolean,
    /** Las llantas a pantalla completa. */
    val onTires: () -> Unit,
    /** Acomodando el inicio (las pastillas de llantas se esconden: estorban a los botones). */
    val editing: () -> Boolean,
    /** Abre la pantalla Música en esa pestaña (play sin nada que sonar: ahí está la biblioteca). */
    val onMedia: (MediaTab) -> Unit,
    /** Un toque al widget Música, Radio o Mapa: en grande, creciendo desde él (la radio, en su pestaña). */
    val onExpand: (Expanded, Tile) -> Unit,
)

/** Cada tipo de pieza dibujado según su tipo. */
@Composable
fun TileContent(d: Dependencies, p: Tile, apps: List<App>, car: CarPackage?, revision: Int, actions: TileActions) {
    val context = LocalContext.current
    when (p.type) {
        TileTypes.CAR, TileTypes.ROAD, TileTypes.IMAGE -> {
            val hero = Modifier
                .fillMaxSize()
                .onGloballyPositioned { actions.onCarBounds(p.id, it.boundsInRoot()) }
                // Mientras crece o se ve sola, la de la pieza se esconde (si no, se ve doble).
                .graphicsLayer { alpha = if (actions.carHidden(p.id)) 0f else 1f }
            val content = @Composable { onTires: (() -> Unit)? ->
                HeroTile(
                    d,
                    p,
                    car,
                    // Una imagen sin foto abre su panel para elegirla; lo demás crece.
                    onClick = { if (p.type == TileTypes.IMAGE && ImageData.name(p.data).isEmpty()) actions.onPanel(Panel.CustomizeImage(p.id)) else actions.onCarOnly(p) },
                    modifier = Modifier.fillMaxSize(),
                    onTires = onTires,
                )
            }
            // Las llantas junto a cada rueda en la carretera y en el auto 3D (si ya se sabe dónde pisan
            // y así se eligió); si no, en las esquinas. En una pieza chica las cuatro pastillas
            // taparían todo: ahí solo la pieza Llantas o el aviso. Sin llantas no hace falta medir la pieza.
            // Cada carretera y auto dice si las lleva (su ajuste); si no lo tiene, lo de Ajustes → Llantas.
            if (d.hasTpmsSource && showsTires(d, p)) BoxWithConstraints(hero) {
                val wheels = d.prefs.tiresOnCar != TiresOnCar.CORNERS
                // Dónde pisa cada rueda: un archivo chico aparte del paquete, se pide la primera vez.
                if (p.type == TileTypes.CAR && wheels) LaunchedEffect(car) { d.cars.fetchWheels(car) }
                val pills = maxWidth >= PILLS_MIN_WIDTH && maxHeight >= PILLS_MIN_HEIGHT && !actions.editing()
                val onWheels = p.type == TileTypes.ROAD || p.type == TileTypes.CAR && wheels && car?.wheels != null
                if (onWheels) content(actions.onTires.takeIf { pills }) else {
                    content(null)
                    if (pills) TirePills(d, actions.onTires)
                }
            } else Box(hero) { content(null) }
        }
        // Aunque sean grandes, sin biblioteca ni estaciones: eso vive en la pantalla Música (un toque la abre).
        TileTypes.MUSIC -> MusicTile(
            d.music, revision, Modifier.expandable(p, actions), artwork = p.flag(WidgetFlags.ART), skip = p.flag(WidgetFlags.SKIP),
            onOpen = remember(p.id) { { actions.onExpand(Expanded.MEDIA, p) } },
        )
        TileTypes.RADIO -> RadioTile(
            d.music, revision,
            // Play sin estación: la radio por internet elige en la pantalla Música; sin radio enlazada, los ajustes.
            onIdle = {
                if (d.music.radioInternet) actions.onMedia(MediaTab.RADIO)
                else {
                    openRadioSettings()
                    actions.onPanel(Panel.AppsSettings)
                }
            },
            modifier = Modifier.expandable(p, actions),
            artwork = p.flag(WidgetFlags.ART), skip = p.flag(WidgetFlags.SKIP),
            onOpen = remember(p.id) { { actions.onExpand(Expanded.MEDIA, p) } },
        )
        TileTypes.CLOCK -> ClockTile(d.prefs.driverName.takeIf { p.flag(WidgetFlags.GREETING) }, clockWeather(d, p.data), date = p.flag(WidgetFlags.DATE))
        TileTypes.WEATHER -> WeatherTile(d.weather, days = p.flag(WidgetFlags.DAYS), range = p.flag(WidgetFlags.RANGE))
        TileTypes.TRIP -> TripTile(d, time = p.flag(WidgetFlags.TIME), average = p.flag(WidgetFlags.AVERAGE), heading = p.flag(WidgetFlags.HEADING))
        TileTypes.SPEEDOMETER -> SpeedometerTile(d, remember(p.data) { GaugeLook.parse(p.data) })
        TileTypes.GAUGE -> GaugeTile(
            d, signalOf(p.data), remember(p.data) { GaugeLook.parse(p.data) },
            onPick = { actions.onPanel(Panel.PickGauge(p.id)) },
            onWorkshop = { actions.onPanel(Panel.Workshop) },
        )
        TileTypes.WORKSHOP -> HealthTile(d, showCar = p.flag(WidgetFlags.CAR)) { actions.onPanel(Panel.Workshop) }
        // Sin app de Android Auto / CarPlay: sus ajustes, para elegirla.
        TileTypes.PROJECTION -> ProjectionTile(d, apps) {
            openProjectionSettings()
            actions.onPanel(Panel.AppsSettings)
        }
        TileTypes.TIRES -> TiresTile(d, showCar = p.flag(WidgetFlags.CAR), onOpen = actions.onTires, onSettings = { actions.onPanel(Panel.TiresSettings) })
        TileTypes.MAP -> Box(Modifier.expandable(p, actions)) {
            // Con «Navegar con: App de mapas», el toque y Casa/Trabajo abren esa app; si no, todo es el mapa de LiteDash.
            val app = externalMapApp(d, apps)
            MapTile(
                d, p.id, app, editing = actions.editing(),
                onPlace = remember(app) { { if (app != null) navigateTo(context, app, it) else PlaceRequests.fromAddress(it) } },
                onTap = remember(p.id, app) { { if (app != null) actions.onNavigate(app) else actions.onExpand(Expanded.MAP, p) } },
                onSearch = remember(p.id) {
                    {
                        PlaceRequests.focus = true
                        actions.onExpand(Expanded.MAP, p)
                    }
                },
                onGuide = remember(p.id) { { actions.onExpand(Expanded.MAP, p) } },
            )
        }
        TileTypes.PHONE -> {
            // Tu app de llamadas (la del Bluetooth de la pantalla, por ejemplo) o el marcador: la misma de la barra y el volante.
            val app = phoneChoice(d, apps)
            ShortcutTile(
                Icons.phone, stringResource(R.string.tile_phone), app?.name,
                onClick = { openPhone(context, d, apps) },
                tint = LocalColors.current.good,
            )
        }
        TileTypes.APP -> {
            val app = appKeyOf(p.data)?.let { d.apps.find(apps, it) }
            AppTile(d.apps, app, named = p.flag(WidgetFlags.NAME)) {
                if (app != null) d.apps.open(context, app) else actions.onPanel(Panel.PickApp(p.id))
            }
        }
    }
}

/** Llena la pieza y avisa dónde está: de ahí crece al verse en grande ([Expanded]). */
private fun Modifier.expandable(p: Tile, actions: TileActions) =
    fillMaxSize().onGloballyPositioned { actions.onCarBounds(p.id, it.boundsInRoot()) }

/**
 * El módulo apagado del que depende una pieza (su nombre), o null si se puede ver: el Taller y
 * los medidores son del OBD y la pieza Llantas de Llantas. Esas piezas no se ven ni apartan su
 * lugar ([DashboardState.hidden]) y no se ofrecen para agregar. La pieza Clima no: el clima viene
 * apagado, las plantillas la traen y desde ella se prende.
 */
fun moduleOff(d: Dependencies, type: String): Int? = when (type) {
    TileTypes.WORKSHOP, TileTypes.GAUGE -> R.string.module_obd.takeIf { !d.obdModule }
    TileTypes.TIRES -> R.string.module_tpms.takeIf { !d.hasTpmsSource }
    else -> null
}

/**
 * Si la carretera, el auto 3D o la imagen [p] llevan la presión de las llantas: lo de su ajuste ([TileTires]);
 * sin él, el auto y la carretera siguen lo de Ajustes → Llantas y la imagen no las lleva (no tiene ruedas).
 */
fun showsTires(d: Dependencies, p: Tile): Boolean =
    TileTires.parse(p.data) ?: (p.type != TileTypes.IMAGE && d.prefs.tiresOnCar != TiresOnCar.OFF)

/**
 * Lo que se ofrece para agregar: Llantas siempre (agregarla prende su módulo); lo del OBD, con el OBD; el Mapa no en
 * una página con mapa de fondo (se verían dos).
 */
fun offered(d: Dependencies, type: String) = when {
    type == TileTypes.MAP && d.dashboard.background == PageBackground.MAP -> false
    else -> type == TileTypes.TIRES || moduleOff(d, type) == null
}

/**
 * Agrega la pieza Llantas y prende su módulo: vuelve la última fuente; si nunca hubo, [pickSource]
 * (Ajustes → Llantas) y la pieza aparece al elegirla. false = no cupo.
 */
fun Dependencies.addTiresTile(pickSource: () -> Unit): Boolean {
    if (!hasTpmsSource) tpms.lastSource?.let { tpms.source = it } ?: pickSource()
    return dashboard.allTiles.any { it.type == TileTypes.TIRES } || dashboard.addTile(TileTypes.TIRES)
}

/** La de Ajustes; si no, la primera instalada. */
fun mapApp(d: Dependencies, apps: List<App>): App? =
    d.prefs.mapApp?.let { d.apps.find(apps, it) }
        ?: d.apps.firstOf(apps, *NAVIGATION_PACKAGES)

/** Tu app de llamadas (se elige en el widget Teléfono; vale para el widget, la barra y el volante); null = el marcador. */
fun phoneChoice(d: Dependencies, apps: List<App>): App? = d.prefs.phoneApp?.let { d.apps.find(apps, it) }

/** Abre [phoneChoice] o, si no hay (o ya no está instalada), el marcador. */
fun openPhone(context: Context, d: Dependencies, apps: List<App>) {
    val app = phoneChoice(d, apps)
    if (app != null) d.apps.open(context, app) else openDialer(context)
}

/** Tu app de mapas solo si elegiste usarla («Navegar con: App de mapas»); null = todo va por el mapa de LiteDash. */
fun externalMapApp(d: Dependencies, apps: List<App>): App? = if (d.prefs.mapExternal) mapApp(d, apps) else null

/** Inicio: la barra de arriba (hora y avisos), el tablero personalizable y la barra de apps. */
@Composable
fun Home(d: Dependencies, revision: Int, homePressed: Int) {
    val c = LocalColors.current
    val context = LocalContext.current
    val apps = rememberApps(d.apps).orEmpty()
    val currentApps by rememberUpdatedState(apps)
    // Voz: el asistente que tenga la pantalla (volante y barra); null si no hay ninguno (sin botón).
    val voice = rememberVoiceIntent()
    val onVoice: (() -> Unit)? = remember(voice) {
        voice?.let { intent -> { runCatching { context.startActivity(intent) }; Unit } }
    }
    val car = d.cars.pkg(d.prefs.carModel)
    // Recién cambiado el idioma, de vuelta en Ajustes, donde se eligió.
    // En la bienvenida (se cambió el idioma desde ahí) no se abre Ajustes debajo.
    var panel by remember { mutableStateOf<Panel?>(Panel.Language.takeIf { MainActivity.takeReopenLanguage() && d.prefs.welcomeSeen }) }
    var editing by remember { mutableStateOf(false) }
    // LiteDash puede estar abierto dos veces (como inicio y como app) y el estado es uno solo por proceso. Una sola
    // regla: solo la ventana al frente decide la cuadrícula (la de atrás no se vuelve a medir y deshacía lo que se
    // movía) y atiende lo que llega de fuera (volante, aviso de la calibración, play sin nada con Archivos).
    var inFront by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        inFront = true
        onPauseOrDispose { inFront = false }
    }
    // Lo que se ve en grande ([Expanded]: la pantalla Música o el mapa) o null = el tablero normal. [expanded] es a
    // dónde va y [expandedShown] lo que se dibuja (sigue mientras se encoge al cerrar); [expandedFrom], el widget
    // del que crece (null = desde el centro).
    var expanded by remember { mutableStateOf<Expanded?>(null) }
    var expandedShown by remember { mutableStateOf<Expanded?>(null) }
    var expandedFrom by remember { mutableStateOf<Tile?>(null) }
    val expandProgress = remember { Animatable(0f) }
    LaunchedEffect(expanded) {
        val target = expanded
        if (target != null) {
            // De la música al mapa (o al revés) ya en grande: se cambia sin volver a crecer.
            if (expandedShown != null && expandedShown != target && expandProgress.value > 0.99f) {
                expandedShown = target
                return@LaunchedEffect
            }
            expandedShown = target
            expandProgress.animateTo(1f, tween(EXPAND_MS, easing = FastOutSlowInEasing))
        } else if (expandedShown != null) {
            expandProgress.animateTo(0f, tween(SHRINK_MS, easing = FastOutSlowInEasing))
            expandedShown = null
        }
    }
    val musicBig = expanded == Expanded.MEDIA
    // Ya cubre todo el tablero: el tablero no se dibuja y lo que gasta en los widgets (GPS, mapa) descansa.
    val expandedCovers by remember { derivedStateOf { expandedShown != null && expandProgress.value >= 1f } }
    var mediaTab by remember { mutableStateOf(MediaTab.MUSIC) }
    var mediaBrowse by remember { mutableStateOf(false) }
    // La pieza que se ve en grande (auto, carretera o imagen) y dónde está cada una.
    var bigTile by remember { mutableStateOf<Tile?>(null) }
    val tileBounds = remember { mutableStateMapOf<String, Rect>() }
    // Solo el auto a pantalla completa (un toque al auto entra y otro sale): crece cuando la imagen grande ya está
    // pintada (en su pieza se ve igual que la chica); si tarda, crece de todos modos.
    val carLayer = rememberOverlay(CAR_WAIT_MS, CAR_GROW_MS, CAR_SHRINK_MS)
    // Las llantas a pantalla completa: aparecen cuando el auto de arriba ya está pintado (la primera vez tarda en leerse).
    val tiresLayer = rememberOverlay(TIRES_WAIT_MS, TIRES_FADE_MS, TIRES_FADE_MS)
    // Lo que esté encima del tablero se quita para ver [what] en grande, creciendo desde el widget [from] (si ya
    // se está encogiendo otra cosa, sigue el origen de esa: no salta a medio camino).
    fun expand(what: Expanded, from: Tile?) {
        panel = null
        tiresLayer.open = false
        carLayer.open = false
        editing = false
        if (expandedShown == null) expandedFrom = from
        expanded = what
    }
    // La pantalla Música en [tab] (null = la de lo que suena); [browse] la abre en la biblioteca y [from] es el
    // widget que se tocó.
    fun openMedia(tab: MediaTab?, browse: Boolean = false, from: Tile? = null) {
        mediaTab = tab ?: if (d.music.radio.song?.playing == true) MediaTab.RADIO else MediaTab.MUSIC
        mediaBrowse = browse
        expand(Expanded.MEDIA, from)
    }
    // Dónde se ve la guía en la página de ahora: su widget Mapa, su mapa de fondo (con hueco entre los widgets) o, sin
    // ellos, el mapa en grande.
    fun guideHomeNow() = pageGuideHome(d.dashboard.background, d.dashboard.shown, d.dashboard.grid)
    // A la guía («Ir», la pastilla de arriba, «Navegar» de la barra y del volante), con una sola regla ([guidePage]): el
    // mapa que navega en esta página, si no la primera página con un mapa y, sin ninguna, el mapa en grande.
    fun showGuide() {
        panel = null
        tiresLayer.open = false
        carLayer.open = false
        editing = false
        val grid = d.dashboard.grid
        val page = guidePage(d.dashboard.page, guideHomeNow()) {
            d.dashboard.pageWhere { background, tiles -> pageGuideHome(background, tiles, grid) != GuideHome.EXPANDED }
        }
        if (page == null) expand(Expanded.MAP, null)
        else {
            expanded = null
            d.dashboard.goTo(page)
        }
    }
    // Los accesos directos al mapa (barra, volante): con la guía, a ella; si no, el mapa de LiteDash en grande o, con
    // «Navegar con: App de mapas» (y una app de mapas), esa app.
    fun openMapShortcut() {
        val app = externalMapApp(d, currentApps)
        when {
            Driving.session != null -> showGuide()
            app != null -> openNavigation(context, d.apps, app)
            else -> expand(Expanded.MAP, null)
        }
    }
    // Android Auto / CarPlay (barra, volante): la app de [projectionChoice] o, si no hay, sus ajustes para elegirla.
    fun openProjection() {
        val app = projectionChoice(d, currentApps)
        if (app != null) d.apps.open(context, app)
        else {
            openProjectionSettings()
            panel = Panel.AppsSettings
        }
    }
    // Con Archivos, play sin nada que sonar (volante, barra de Android, widget) abre la pantalla Música en la
    // biblioteca; mientras está abierta, la atiende ella.
    // Un lugar de otra app (geo:) o la lupa sin mapa abierto: el mapa en grande lo atiende (con la guía, en su buscador).
    LaunchedEffect(PlaceRequests.pending) {
        if (PlaceRequests.pending && expanded != Expanded.MAP) expand(Expanded.MAP, null)
    }
    val currentOpenMedia by rememberUpdatedState(::openMedia)
    OnFilesEmpty(d.music, inFront && d.music.mode == MusicMode.FILES, remember { { currentOpenMedia(MediaTab.MUSIC, true, null) } })
    // El aviso de la calibración ("No encontramos cómo hacerlo sonar aquí") lleva a "Enviar un reporte".
    DisposableEffect(d.music, inFront) {
        if (!inFront) return@DisposableEffect onDispose {}
        val mine: (SoundPiece) -> Unit = { openSoundReport(it); panel = Panel.Report }
        d.music.onSoundReport = mine
        // El Bluetooth de marca sin por dónde darle play ofrece LiteDash Connect: a Ajustes → Celular.
        val link = { panel = Panel.PhoneSettings }
        d.music.onPhoneLink = link
        onDispose {
            if (d.music.onSoundReport === mine) d.music.onSoundReport = null
            if (d.music.onPhoneLink === link) d.music.onPhoneLink = null
        }
    }
    // Un Diseño recién aplicado (también desde «Diseños» al acomodar): se sale de acomodar, así al cerrar el panel sale
    // su aviso con «Deshacer» (acomodando no hay avisos).
    LaunchedEffect(DesignNotice.shown) { if (DesignNotice.design != null) editing = false }
    // A dónde lleva «atrás» (y cerrar) desde [p]: elegir la app o el dato, a quien lo pidió; lo demás, al inicio.
    fun backOf(p: Panel?): Panel? = when (p) {
        is Panel.PickApp -> p.back
        is Panel.PickGauge -> p.back
        else -> null
    }

    fun backToDashboard() {
        panel = null
        tiresLayer.open = false
        carLayer.open = false
        expanded = null
        editing = false
    }

    // Cada toque y cada botón del volante reinician la cuenta del modo estacionado. Se
    // leen dentro del efecto (no al armar la pantalla): tocar no vuelve a armar el inicio.
    var touched by remember { mutableIntStateOf(0) }
    var parked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        snapshotFlow { listOf(touched, d.wheel.presses, panel, editing, expanded, carLayer.open, tiresLayer.open, d.prefs.parkedMode, Driving.session) }
            .collectLatest {
                parked = false
                // Con la guía no: manejar minutos sin tocar la pantalla es lo normal.
                if (!d.prefs.parkedMode || panel != null || editing || expanded != null || carLayer.open || tiresLayer.open || Driving.session != null) return@collectLatest
                delay(PARK_AFTER_MS)
                // Manejando no: espera a que se detenga (velocidad del OBD o del GPS, la que haya).
                snapshotFlow { (d.bus.value(Signal.SPEED) ?: 0f) > MOVING_KMH }.first { !it }
                parked = true
            }
    }
    // Algo opaco tapa todo el inicio (estacionado, que puede durar horas, o el auto o las llantas ya en grande): lo
    // de abajo sigue compuesto y medido (al destaparse se ve al instante y lo que crece sabe de dónde), pero no se
    // dibuja y lo que gasta descansa ([LocalHomeCovered]).
    val homeCovered by remember { derivedStateOf { parked || carLayer.covers || tiresLayer.covers } }
    // Con la guía y sin ningún mapa que la enseñe a la vista (otra página, la música en grande o un panel): la pastilla
    // de arriba con la próxima vuelta, que lleva a ella.
    val session = Driving.session
    val guideSeen = panel == null && !homeCovered && !carLayer.open && !tiresLayer.open &&
        (expandedShown == Expanded.MAP || expandedShown == null && guideHomeNow() != GuideHome.EXPANDED)
    val guidePill: (@Composable () -> Unit)? = if (session != null && !guideSeen) {
        { GuidePill(d, session, ::showGuide) }
    } else null
    // Lo que sigue aunque se abra Ajustes o se acomode el inicio: el registro del viaje y
    // los avisos del motor y de las llantas (si no, abrir Ajustes cortaría el viaje y "Entendido" se olvidaría).
    val engineAlert = remember { EngineAlertState() }
    val tiresAlert = remember { TiresAlertState() }
    if (d.prefs.welcomeSeen) TripRecorderEffect(d)

    BackHandler(enabled = (panel != null || editing || expanded != null || carLayer.open || tiresLayer.open) && d.prefs.welcomeSeen) {
        when {
            panel != null -> panel = backOf(panel)
            tiresLayer.open -> tiresLayer.open = false
            carLayer.open -> carLayer.open = false
            expanded != null -> expanded = null
            else -> editing = false
        }
    }
    // Botón de inicio estando ya aquí: de vuelta al tablero tal como lo acomodaste.
    // Solo los toques a Inicio de después: al abrirse (p. ej. tras cambiar el idioma) no cierra lo que se abrió solo.
    val homeAtStart = remember { homePressed }
    LaunchedEffect(homePressed) { if (homePressed != homeAtStart && d.prefs.welcomeSeen) backToDashboard() }
    // Clima al volver al inicio y cada media hora con el inicio a la vista (si está activado); nunca en segundo plano.
    WeatherRefresh(d.weather, revision, d.trip)
    // Al volver al inicio: versión nueva de la app (a lo más cada 12 h), cuántas pantallas usan LiteDash (a lo más
    // una vez al día) y Pro (revisa el código a lo más cada 12 h; sin código guardado, nada de red). Cada uno se limita solo.
    LaunchedEffect(revision) {
        launch { d.updates?.checkQuietly() }
        launch { d.stats.pingDaily() }
        launch { d.pro.refresh() }
    }
    // Modelo 3D nuevo del auto (a lo más cada hora).
    LaunchedEffect(d.prefs.carModel) { d.cars.checkQuietly(d.prefs.carModel) }

    val actions = remember {
        TileActions(
            onPanel = { panel = it },
            onNavigate = { app -> openNavigation(context, d.apps, app) },
            onCarOnly = {
                // Con Llantas activo, tocar el auto 3D abre las llantas; si no, lo agranda.
                if (it.type == TileTypes.CAR && d.hasTpmsSource && d.prefs.carTap == CarTap.TIRES) tiresLayer.open = true
                else {
                    bigTile = it
                    carLayer.open = true
                }
            },
            onCarBounds = { id, rect -> if (tileBounds[id] != rect) tileBounds[id] = rect },
            carHidden = { id -> carLayer.shown && bigTile?.id == id && carLayer.progress.value > 0f },
            onTires = { tiresLayer.open = true },
            editing = { editing },
            onMedia = { openMedia(it) },
            onExpand = { what, tile ->
                // En la vista previa de las opciones del widget no: ahí se está personalizando.
                if (panel == null && !editing) {
                    if (what == Expanded.MEDIA) openMedia(if (tile.type == TileTypes.RADIO) MediaTab.RADIO else MediaTab.MUSIC, from = tile)
                    else expand(Expanded.MAP, tile)
                }
            },
        )
    }
    // Se crea una vez (como [actions]): sus funciones leen el estado al llamarse y la barra no se vuelve a armar
    // cada vez que cambia algo del inicio.
    val barActions = remember {
        BarActions(
            onHome = { backToDashboard() },
            onApps = { panel = Panel.Drawer },
            onMusic = { if (expanded == Expanded.MEDIA) expanded = null else openMedia(null) },
            onNavigate = { if (expanded == Expanded.MAP) expanded = null else openMapShortcut() },
            // Chip propio del estéreo: su app de Bluetooth (la misma que usa la música). Con el Bluetooth de
            // Android, donde se empareja el celular: sus ajustes de Bluetooth (o, si la pantalla no los trae,
            // Ajustes → Apps → Música). Android Auto / CarPlay tiene su propio botón.
            onBluetooth = {
                if (!openStereoBluetooth(d, context)) {
                    openMusicSettings()
                    panel = Panel.AppsSettings
                }
            },
            onProjection = { openProjection() },
            onPhone = { openPhone(context, d, currentApps) },
            onVoice = onVoice,
            onSettings = { panel = Panel.Settings },
            onBarSettings = { panel = Panel.BarSettings },
            onEdit = { expanded = null; editing = true },
        )
    }

    // Mandos al volante: las acciones que abren algo (mapa, música, voz, inicio) se hacen aquí, una sola vez cada
    // una y solo en la ventana al frente (la de atrás no se ve: ahí no pasaría nada).
    LaunchedEffect(inFront) {
        if (!inFront) return@LaunchedEffect
        snapshotFlow { d.wheel.request }.filterNotNull().collect { action ->
            d.wheel.consume()
            when (action) {
                WheelAction.NAVIGATE -> openMapShortcut()
                WheelAction.PROJECTION -> openProjection()
                WheelAction.PHONE -> openPhone(context, d, currentApps)
                WheelAction.MUSIC -> openMedia(null)
                WheelAction.VOICE -> onVoice?.invoke()
                WheelAction.HOME -> backToDashboard()
                else -> Unit
            }
        }
    }

    // Con un auto que trae piso, el fondo baja un poco de tono hacia abajo: es el
    // piso donde se para el auto (como en Tesla), y su sombra y reflejo caen ahí.
    val hasFloor = car?.hasFloor == true && d.dashboard.shown.any { it.type == TileTypes.CAR }
    // Las fotos de piezas de imagen que se quitaron (o cambiaron) salen de memoria, y también dónde estaba cada widget quitado.
    LaunchedEffect(d.dashboard.tiles) {
        // Las de todas las páginas: cambiar de página no las suelta (al volver no parpadean).
        releaseTileImages(d.dashboard.allTiles)
        val ids = d.dashboard.tiles.mapTo(HashSet()) { it.id }
        tileBounds.keys.retainAll(ids)
    }
    // Velocímetros guardados hasta la 1.4.61 (su máximo en la unidad que se veía): a km/h. Basta al cargar el
    // acomodo de una cuadrícula (los que se agregan o cambian después ya vienen en km/h).
    // Quitar cuando < 5 % de las pantallas esté antes de la 1.4.140 según docs/usuarios.py.
    LaunchedEffect(d.dashboard.grid) {
        val miles = d.prefs.units.miles
        d.dashboard.changeData(
            d.dashboard.tiles.filter { it.type == TileTypes.SPEEDOMETER }
                .mapNotNull { t -> migrateSpeedMax(t.data, miles)?.let { t.id to it } }
                .toMap(),
        )
    }
    val backdrop = if (hasFloor) {
        Brush.verticalGradient(0.3f to c.background, 1f to lerp(c.background, Color.Black, if (c.dark) 0.35f else 0.08f))
    } else SolidColor(c.background)
    // Para el mapa de fondo: lo que mide la ventana y dónde está el tablero en ella (tú vas en su hueco libre).
    var rootSize by remember { mutableStateOf(Size.Zero) }
    var board by remember { mutableStateOf<Rect?>(null) }
    // Lo que tapa la llegada de la guía abajo del hueco del mapa de fondo (px): tu flecha o tu auto van arriba de ella.
    var guideCardsH by remember { mutableIntStateOf(0) }
    // Deslizar de página: cuánto se movió el tablero (px).
    val pageShift = remember { Animatable(0f) }
    // Un deslizamiento cortado (al entrar a acomodar, o al quitar o agregar páginas) no deja el tablero corrido.
    LaunchedEffect(editing, d.dashboard.pageCount) { if (pageShift.value != 0f) pageShift.animateTo(0f) }
    // Lo que mide una celda del inicio (la última vez que se dibujó): la vista previa de las opciones de un
    // widget lo muestra a su tamaño real.
    var cell by remember { mutableStateOf(DpSize(48.dp, 48.dp)) }
    Box(
        Modifier
            .fillMaxSize()
            .background(backdrop)
            .onSizeChanged { rootSize = it.toSize() }
            // Cualquier toque reinicia la cuenta del modo estacionado (sin quitarle el toque a nadie).
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    touched++
                }
            },
    ) {
        // Los paneles tapan todo el inicio: mientras hay uno abierto no se compone ni se pinta nada debajo (el
        // auto del tablero no se tiñe dos veces al cambiar el color).
        // En el inicio el vidrio lleva tu fondo desenfocado detrás de cada tarjeta y de la barra.
        val glass = d.prefs.cardStyle == CardStyle.GLASS
        // Como cristal sobre tu fondo o el resplandor del tema; sobre el mapa de fondo (se mueve), con su relleno.
        val calm = d.dashboard.background == PageBackground.WALLPAPER
        val blurred = rememberWallpaperBackdrop(glass && calm) { d.prefs.wallpaperDim }
        val homeLook = remember(d.prefs.cardStyle, d.prefs.cardOpacity, blurred, calm) {
            CardLook(d.prefs.cardStyle, d.prefs.cardOpacity, blurred, calm)
        }
        // El fondo de un Diseño que espera internet: se baja cuando haya red (también con Ajustes o un panel abierto).
        PendingDesignWallpaper(d.prefs)
        ThemedDesignWallpaper()
        if (panel == null) CompositionLocalProvider(LocalHomeCovered provides homeCovered, LocalCardLook provides homeLook) {
            Box(Modifier.fillMaxSize().hiddenWhen { homeCovered }) {
                // Detrás de todo, el fondo de esta página: tu imagen (Inicio y apariencia → Inicio → Fondo de pantalla) o
                // el mapa vivo, contigo en el hueco que dejan los widgets.
                when (d.dashboard.background) {
                    PageBackground.WALLPAPER -> HomeWallpaper(d.prefs)
                    // Con algo en grande encima (la música o el mapa) se queda quieto: no se ve.
                    PageBackground.MAP -> MapBackdrop(
                        d, remember(d.dashboard.shown, d.dashboard.grid, board, rootSize) { mapFocus(d.dashboard, board, rootSize) },
                        covered = expandedCovers, guide = guideHomeNow() == GuideHome.BACKDROP, cardsH = guideCardsH,
                    )
                }
                // Sin apartarse del teclado: lo tapa (como pide adjustNothing) y quien escribe acomoda lo suyo arriba de él
                // (el buscador del mapa). Si se apartara, en un celular acostado todo el inicio quedaba en una franja.
                BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.exclude(WindowInsets.ime))) {
                    // Pantalla acostada: barra lateral (como DuduAuto) y el tablero con toda la altura. Parada: la
                    // barra abajo.
                    val rail = when (d.prefs.barPosition) {
                        BarPosition.SIDE, BarPosition.RIGHT -> true
                        BarPosition.BOTTOM -> false
                        BarPosition.AUTO -> maxWidth > maxHeight && !isShortScreen()
                    }
                    val favorites = rememberFavorites(d, apps)
                    val railRight = rail && d.prefs.barPosition == BarPosition.RIGHT
                    // Oculta (Inicio y apariencia → Barra) se ve al acomodar (ahí está Ajustes) y, encima del tablero,
                    // unos segundos tras tocar un hueco del inicio: cada toque en ella o en un hueco reinicia la cuenta.
                    val barShown = editing || !d.prefs.barHidden
                    var barPeek by remember { mutableIntStateOf(0) }
                    var peeking by remember { mutableStateOf(false) }
                    LaunchedEffect(barPeek) {
                        if (barPeek == 0) return@LaunchedEffect
                        peeking = true
                        delay(BAR_PEEK_MS)
                        peeking = false
                    }
                    // El desvanecido se lee al dibujar; al componer solo importa si se ve (cambia dos veces, no en cada cuadro).
                    val peekAlpha = animateFloatAsState(if (peeking && !barShown) 1f else 0f, tween(BAR_FADE_MS), label = "barPeek")
                    val peekVisible by remember { derivedStateOf { peekAlpha.value > 0f } }
                    // Lo que deja libre al ocultarse: el tablero lo gana, pero la cuadrícula no lo cuenta.
                    val freed = if (barShown) 0.dp else barThickness(d.prefs, rail)
                    val bar = @Composable { vertical: Boolean ->
                        AppBar(d.apps, d.prefs, favorites, vertical, editing, musicBig, barActions, mapOpen = expanded == Expanded.MAP, guiding = session != null)
                    }
                    // Oculta: un toque en cualquier lugar libre (la orilla, la barra de arriba o un hueco del tablero) la
                    // asoma. Va debajo de todo: lo que tiene su propio toque (widgets, puntitos, avisos) lo conserva.
                    if (!barShown) Spacer(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { barPeek++ } })
                    Row(Modifier.fillMaxSize()) {
                        if (barShown && rail && !railRight) bar(true)
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            // Si ya se ve un reloj en el tablero, la barra de arriba no repite la hora.
                            val showTime = d.dashboard.shown.none { it.type == TileTypes.CLOCK }
                            val barHidden = !editing && !showTime
                            TopBar(
                                editing = editing,
                                showTime = showTime,
                                driverName = d.prefs.driverName,
                                weather = d.weather,
                                onAdd = { panel = Panel.AddTile },
                                onDesigns = { panel = Panel.Designs },
                                onWallpaper = { panel = Panel.Wallpaper },
                                onFinish = { editing = false },
                                pages = d.dashboard,
                                guide = guidePill,
                            )
                            // Sin barra abajo (va al costado o está oculta) el tablero lleva su propio aire inferior
                            // (en pantallas sin barra de gestos las piezas quedaban pegadas al borde).
                            val barBelow = barShown && !rail
                            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = if (barBelow) 0.dp else 10.dp)) {
                                // La cuadrícula se calcula siempre como si la barra estuviera: si dependiera
                                // de ella, un reloj en el acomodo la oculta, la cuadrícula crece, se carga
                                // otro acomodo sin reloj, vuelve la barra... y el inicio parpadea sin parar.
                                // El aire inferior tampoco cuenta, ni el lugar que deja la barra oculta: la cuadrícula
                                // (y el acomodo guardado) debe ser la misma con la barra en cualquier lado o sin ella.
                                // Con la barra de Android oculta hay más alto, pero la cuadrícula tampoco cambia.
                                val statusHidden = statusBarHiddenHeight()
                                val gridHeight = (if (barHidden) maxHeight - (topBarHeight() - TOP_BAR_HIDDEN) else maxHeight) + (if (barBelow) 0.dp else 10.dp) - statusHidden - (if (rail) 0.dp else freed)
                                val gridWidth = maxWidth - (if (rail) freed else 0.dp)
                                val grid = remember(gridWidth, gridHeight) { Grid.forSize(gridWidth.value, gridHeight.value) }
                                // Carga el acomodo de esta cuadrícula antes de dibujar (solo hace algo si cambió).
                                if (inFront || d.dashboard.grid == null) d.dashboard.use(grid)
                                val cellNow = DpSize(maxWidth / grid.columns, maxHeight / grid.rows)
                                SideEffect { cell = cellNow }
                                // Con algo en grande encima (la música o el mapa) el tablero tampoco se dibuja.
                                CompositionLocalProvider(LocalHomeCovered provides (homeCovered || expandedCovers)) {
                                    Dashboard(
                                        grid = grid,
                                        tiles = d.dashboard.shown,
                                        editing = editing,
                                        limits = ::limitsOf,
                                        onChange = d.dashboard::changeShown,
                                        onEdit = { editing = true },
                                        modifier = Modifier
                                            .hiddenWhen { expandedCovers }
                                            .onGloballyPositioned { board = it.boundsInRoot() }
                                            .graphicsLayer { translationX = pageShift.value },
                                        swipe = rememberPageSwipe(d.dashboard, pageShift, board?.width ?: 0f),
                                        onSettings = { optionsPanelOf(it.type, it.id)?.let { p -> panel = p } },
                                        // Los que tienen opciones; el auto 3D, solo con Llantas prendido (las de las llantas).
                                        hasSettings = { optionsPanelOf(it.type, it.id) != null && (it.type != TileTypes.CAR || d.hasTpmsSource) },
                                        onTapEmpty = { if (!barShown) barPeek++ },
                                    ) { p -> TileContent(d, p, apps, car, revision, actions) }
                                }
                                // La guía del mapa de fondo va encima del tablero (debajo no recibía toques: «Terminar» no
                                // servía), colocada en el hueco que dejan los widgets y deslizándose con la página.
                                val hole = if (session != null && !editing && guideHomeNow() == GuideHome.BACKDROP) mapFocus(d.dashboard, board, rootSize) else null
                                val boardNow = board
                                if (session != null && hole != null && boardNow != null) {
                                    val density = LocalDensity.current
                                    Box(
                                        Modifier
                                            .offset { IntOffset((hole.left * rootSize.width - boardNow.left).roundToInt(), (hole.top * rootSize.height - boardNow.top).roundToInt()) }
                                            .size(with(density) { (hole.width * rootSize.width).toDp() }, with(density) { (hole.height * rootSize.height).toDp() })
                                            .graphicsLayer { translationX = pageShift.value }
                                            .hiddenWhen { expandedCovers },
                                    ) { GuideCards(d, session, Modifier.fillMaxSize()) { guideCardsH = it } }
                                }
                                // Con varias páginas: los puntitos abajo; al acomodar, la barra de páginas.
                                if (d.dashboard.pageCount > 1 && !editing && !expandedCovers) {
                                    // En el margen de abajo (sin barra abajo hay 10 dp libres); con la barra abajo, pegados a ella.
                                    PageDots(d.dashboard, Modifier.align(Alignment.BottomCenter).offset(y = if (barBelow) 2.dp else 9.dp))
                                }
                                // La pantalla Música o el mapa en grande: llenan el tablero (la barra se queda para cambiar entre ellos).
                                val shownBig = expandedShown
                                if (shownBig != null) ExpandedLayer(expandedFrom?.let { tileBounds[it.id] }, { expandProgress.value }) {
                                    val close = { expanded = null }
                                    when (shownBig) {
                                        Expanded.MEDIA -> MediaScreen(
                                            d, mediaTab, onTab = { mediaTab = it; mediaBrowse = false }, close = close,
                                            onRadioSettings = { openRadioSettings(); panel = Panel.AppsSettings },
                                            modifier = Modifier.padding(6.dp),
                                            browse = mediaBrowse,
                                            // Sin la barra de arriba, la pastilla de la guía va en su encabezado (flotando taparía sus botones).
                                            guide = guidePill.takeIf { !showTime },
                                        )
                                        Expanded.MAP -> {
                                            val app = externalMapApp(d, apps)
                                            // Con la cámara del widget Mapa que se tocó (o del primero que haya). Si se llegó desde la
                                            // música en grande, [expandedFrom] es el de la música: no sirve aquí.
                                            val from = expandedFrom?.takeIf { it.type == TileTypes.MAP }
                                                ?: d.dashboard.shown.firstOrNull { it.type == TileTypes.MAP }
                                            MapExpanded(
                                                d, from?.id, app, onPlace = { if (app != null) navigateTo(context, app, it) else PlaceRequests.fromAddress(it) }, onNavigate = { actions.onNavigate(app) },
                                                onOffline = { openOfflineMaps(); panel = Panel.AppsSettings },
                                                // «Ir»: la guía va a donde la llevan la pastilla y «Navegar» (si es aquí, este se queda).
                                                onGo = { showGuide() },
                                                close = close, modifier = Modifier.padding(6.dp),
                                            )
                                        }
                                    }
                                }
                                // Los avisos flotan encima del tablero, arriba al centro: no lo mueven ni lo achican
                                // (aparecen y se van; la cuadrícula tampoco los cuenta). Con la música o el mapa en
                                // grande solo quedan los del auto (los demás taparían su buscador o sus botones).
                                if (d.prefs.welcomeSeen && !editing) {
                                    Column(Modifier.align(Alignment.TopCenter).widthIn(max = PROMPTS_MAX_WIDTH).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                        // Sin la barra de arriba (la hora va en un widget), la pastilla de la guía flota aquí
                                        // (con la música en grande va en su encabezado).
                                        if (!showTime && expandedShown == null) guidePill?.invoke()
                                        val calm = !expandedCovers
                                        if (calm && !d.prefs.dontAskHome && !isShortScreen()) HomePrompt(d.prefs, revision)
                                        EngineAlertBanner(d, engineAlert)
                                        TiresAlertBanner(d, actions.onTires, tiresAlert)
                                        if (calm && !isShortScreen()) ReminderBanner(d.reminders)
                                        if (calm) d.updates?.Banner(onOpen = { openAbout(); panel = Panel.Updates })
                                        val newModel = d.cars.outdated?.takeIf { it.id == car?.id }
                                        if (calm && car != null && newModel != null) CarUpdateNotice(d.cars, car, newModel)
                                        if (calm) DesignAppliedNotice(d)
                                    }
                                }
                            }
                            if (barBelow) bar(false)
                        }
                        if (barShown && railRight) bar(true)
                    }
                    // La barra oculta asomándose: encima del tablero en su lugar de siempre, sin reacomodar nada. Al
                    // costado lleva el fondo del inicio (ahí no tiene tarjeta propia).
                    if (peekVisible) {
                        val place = when {
                            !rail -> Modifier.align(Alignment.BottomCenter)
                            railRight -> Modifier.align(Alignment.CenterEnd).fillMaxHeight().background(backdrop)
                            else -> Modifier.align(Alignment.CenterStart).fillMaxHeight().background(backdrop)
                        }
                        Box(
                            place
                                .graphicsLayer { alpha = peekAlpha.value }
                                .pointerInput(Unit) {
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                        barPeek++
                                    }
                                },
                        ) { bar(rail) }
                    }
                }
            }
        }

        panel?.let { p ->
            // En los paneles, la pastilla de la guía va en su encabezado.
            CompositionLocalProvider(LocalGuidePill provides guidePill) { FitShort {
                when (p) {
                    Panel.Drawer -> DrawerPanel(d, apps, close = { panel = null })
                    Panel.Settings, Panel.Updates, Panel.Report, Panel.Language, Panel.Appearance, Panel.Workshop, Panel.AppsSettings, Panel.TiresSettings, Panel.PhoneSettings -> SettingsPanel(
                        d,
                        revision = revision,
                        close = { panel = null },
                        // Acomodar cierra lo que esté en grande (si no, el tablero ni se ve).
                        onEdit = { panel = null; expanded = null; editing = true },
                        onDesigns = { panel = Panel.Designs },
                        onAddTile = { panel = Panel.AddTile },
                        start = when (p) {
                            Panel.Updates, Panel.Report -> SettingsSection.SYSTEM
                            Panel.Workshop -> SettingsSection.WORKSHOP
                            Panel.AppsSettings -> SettingsSection.APPS
                            Panel.TiresSettings -> SettingsSection.TIRES
                            Panel.PhoneSettings -> SettingsSection.PHONE
                            Panel.Language, Panel.Appearance -> SettingsSection.APPEARANCE
                            else -> SettingsSection.CAR
                        },
                        onTires = { panel = null; tiresLayer.open = true },
                    )
                    Panel.Designs -> DesignsPanel(d, close = { panel = null }, onCustomize = {
                        openInterfaceSettings()
                        panel = Panel.Appearance
                    })
                    Panel.BarSettings -> BarSettingsPanel(d, apps, close = { panel = null })
                    Panel.Wallpaper -> PanelScaffold(
                        if (d.dashboard.pageCount > 1) stringResource(R.string.page_background_title, d.dashboard.page + 1) else stringResource(R.string.wallpaper_title),
                        close = { panel = null },
                    ) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            PageBackgroundSettings(d)
                        }
                    }
                    Panel.AddTile -> AddTilePanel(
                        d,
                        close = { panel = null },
                        onPickApp = { panel = Panel.PickApp(null) },
                        onPickGauge = { panel = Panel.PickGauge(null) },
                        onTiresSettings = { panel = Panel.TiresSettings },
                    )
                    is Panel.PickApp -> PickAppPanel(d, apps, p.tileId, close = { panel = backOf(p) })
                    is Panel.PickGauge -> PickGaugePanel(d, p.tileId, close = { panel = backOf(p) })
                    // Las vistas previas llevan la piel de la página ([homeLook]): sobre el mapa de fondo no es la del
                    // lienzo del panel, y el widget se veía distinto ahí que al volver al inicio (#32).
                    is Panel.CustomizeGauge -> CustomizeGaugePanel(d, p.tileId, homeLook, close = { panel = null }, onPickSignal = { panel = Panel.PickGauge(p.tileId) })
                    is Panel.CustomizeImage -> CustomizeImagePanel(d, p.tileId, close = { panel = null })
                    is Panel.CustomizeMap -> CustomizeMapPanel(
                        d, p.tileId, close = { panel = null },
                        openLook = { openMapLook(); panel = Panel.AppsSettings }, openOffline = { openOfflineMaps(); panel = Panel.AppsSettings },
                    )
                    is Panel.CustomizeCar -> CustomizeCarPanel(d, p.tileId, close = { panel = null })
                    is Panel.CustomizeWidget -> CustomizeWidgetPanel(
                        d, p.tileId, apps, cell,
                        close = { panel = null },
                        onPickApp = { panel = Panel.PickApp(p.tileId, back = p) },
                        preview = { t -> CompositionLocalProvider(LocalCardLook provides homeLook) { TileContent(d, t, apps, car, revision, actions) } },
                    )
                }
            } }
        }

        // La guía trabaja aquí, una vez, aunque ningún mapa la enseñe.
        session?.let { key(it) { GuideEngine(d, it) } }

        val big = bigTile
        if (carLayer.shown && panel == null && big != null) {
            CarOnly(
                d, car, big, tileBounds[big.id] ?: Rect.Zero, { carLayer.progress.value }, backdrop,
                onTap = { carLayer.open = false }, onReady = { carLayer.ready = true },
            )
            // Con la guía, su pastilla arriba (como en la música en grande); el resto de la pantalla sigue cerrando el auto.
            if (guidePill != null) Box(Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 12.dp, start = 16.dp, end = 16.dp)) { guidePill() }
        }

        if (tiresLayer.shown && panel == null) FitShort {
            // En su encabezado, la pastilla de la guía.
            CompositionLocalProvider(LocalGuidePill provides guidePill) {
                TiresFull(
                    d, { tiresLayer.progress.value },
                    onClose = { tiresLayer.open = false },
                    onSettings = { tiresLayer.open = false; panel = Panel.TiresSettings },
                    onReady = { tiresLayer.ready = true },
                )
            }
        }

        if (parked) FitShort { Parked(d, onWake = { parked = false; touched++ }) }

        // Animación de entrada con tu imagen, una vez por cada vez que se abre LiteDash (no al cambiar de idioma).
        var intro by rememberSaveable { mutableStateOf(!MainActivity.takeRestarted() && d.prefs.logoIntro && d.prefs.welcomeSeen) }
        if (intro) LogoIntro { intro = false }

        if (!d.prefs.welcomeSeen) {
            Welcome(d, revision, onFinish = { catalog ->
                d.prefs.welcomeSeen = true
                // «Ver el catálogo»: al terminar, Ajustes → Tu auto → Modelos.
                if (catalog) {
                    openCarModels()
                    panel = Panel.Settings
                }
            })
        }
    }
}

/**
 * Una capa a pantalla completa que aparece y se va animada (el auto o las llantas en grande): [open] es a dónde va,
 * [shown] si sigue en pantalla (también mientras se va), [ready] lo marca la capa cuando ya está pintada y
 * [progress] va de 0 a 1 (se lee al dibujar).
 */
@Stable
private class Overlay {
    var open by mutableStateOf(false)
    var shown by mutableStateOf(false)
    var ready by mutableStateOf(false)
    val progress = Animatable(0f)

    /** Ya completa: tapa todo el inicio. */
    val covers get() = shown && progress.value >= 1f
}

/** Una [Overlay]: al abrirse espera hasta [waitMs] a estar pintada, aparece en [inMs] y se va en [outMs]. */
@Composable
private fun rememberOverlay(waitMs: Long, inMs: Int, outMs: Int): Overlay {
    val layer = remember { Overlay() }
    LaunchedEffect(layer.open) {
        if (layer.open) {
            layer.shown = true
            withTimeoutOrNull(waitMs) { snapshotFlow { layer.ready }.first { it } }
            layer.progress.animateTo(1f, tween(inMs, easing = FastOutSlowInEasing))
        } else if (layer.shown) {
            layer.progress.animateTo(0f, tween(outMs, easing = FastOutSlowInEasing))
            layer.shown = false
            layer.ready = false
        }
    }
    return layer
}

/**
 * No dibuja lo de adentro mientras [hidden] (algo opaco lo tapa por completo). Sigue compuesto y medido: al destaparse
 * se ve en ese mismo cuadro y lo que crece desde un widget sabe de dónde. Se lee al dibujar: no rearma nada.
 */
private fun Modifier.hiddenWhen(hidden: () -> Boolean) = drawWithContent { if (!hidden()) drawContent() }

/**
 * Estacionado: reloj grande, saludo, clima y lo que suena (o tu imagen, si la elegiste) sobre fondo oscuro.
 * Sale tras [PARK_AFTER_MS] sin tocar el inicio (y sin ir en movimiento); un toque lo quita.
 */
@Composable
private fun Parked(d: Dependencies, onWake: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onWake),
        contentAlignment = Alignment.Center,
    ) {
        // Sin imagen guardada, el reloj.
        if (d.prefs.parkedView == ParkedView.IMAGE && parkedImageShown()) return@Box
        val clock = rememberClock()
        val hello = greeting(d.prefs.driverName)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Siempre en colores oscuros: de noche no deslumbra.
            LiteDashTheme(dark = true, scale = 1f, accent = d.prefs.accentColor) {
                BigTime(clock, Typography.clock.copy(fontSize = 120.sp))
                Label(hello?.let { stringResource(R.string.two_parts, it, clock.date) } ?: clock.date, Typography.subtitle, LocalColors.current.textMuted, maxLines = 1)
                Box(Modifier.padding(top = 12.dp)) { WeatherChip(d.weather) }
                ParkedSong(d.music, Modifier.padding(top = 20.dp))
            }
        }
    }
}

internal const val PARK_AFTER_MS = 5 * 60_000L

/** Lo que se asoma la barra oculta tras un toque en un hueco del inicio, y lo que tarda en aparecer o irse. */
private const val BAR_PEEK_MS = 5_000L
private const val BAR_FADE_MS = 180

/** Lo más ancho de los avisos que flotan encima del tablero. */
private val PROMPTS_MAX_WIDTH = 720.dp

/** Más rápido que esto, va manejando (el modo estacionado espera). */
private const val MOVING_KMH = 8f

/** Tus apps favoritas de la barra ([dockFavorites]), al día con lo que cambie. */
@Composable
fun rememberFavorites(d: Dependencies, apps: List<App>): List<App> =
    remember(apps, d.prefs.dock, d.music.preferred, d.prefs.projectionApp, BarItem.PROJECTION in d.prefs.barItems) { dockFavorites(d, apps) }

/** Las del usuario, o unas sugeridas con lo que tenga instalado (navegación, música, mensajes). */
fun dockFavorites(d: Dependencies, apps: List<App>): List<App> {
    d.prefs.dock?.let { keys -> return keys.mapNotNull { d.apps.find(apps, it) } }
    return listOfNotNull(
        // Android Auto / CarPlay primero (mucha gente usa la pantalla con su celular), salvo que ya tenga su botón.
        projectionChoice(d, apps).takeIf { BarItem.PROJECTION !in d.prefs.barItems },
        d.apps.firstOf(apps, *NAVIGATION_PACKAGES),
        // La app de música elegida en Ajustes, o la primera conocida que haya.
        d.music.preferred?.let { d.apps.firstOf(apps, it) } ?: d.apps.firstOf(apps, *MUSIC_PACKAGES),
        d.apps.firstOf(apps, "com.google.android.youtube"),
        d.apps.firstOf(apps, "com.whatsapp", "org.telegram.messenger"),
        d.apps.firstOf(apps, "com.android.chrome"),
    )
}

/** Alto de la barra de arriba, y el espacio que deja cuando no muestra nada. */
@Composable
private fun topBarHeight() = if (isShortScreen()) 44.dp else 64.dp
private val TOP_BAR_HIDDEN = 12.dp

@Composable
private fun TopBar(
    editing: Boolean,
    showTime: Boolean,
    driverName: String?,
    weather: WeatherRepository,
    onAdd: () -> Unit,
    onDesigns: () -> Unit,
    onWallpaper: () -> Unit,
    onFinish: () -> Unit,
    pages: DashboardState,
    /** La pastilla de la guía, en lugar de la fecha (null = la fecha). */
    guide: (@Composable () -> Unit)?,
) {
    val c = LocalColors.current
    // Sin nada que mostrar (la hora ya está en una pieza), la barra no aparta espacio: el tablero sube.
    if (!editing && !showTime) return Spacer(Modifier.height(TOP_BAR_HIDDEN))
    Row(
        Modifier.fillMaxWidth().height(topBarHeight()).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (editing) {
            val title = stringResource(R.string.arrange_your_home)
            val hint = stringResource(R.string.arrange_hint)
            val measurer = rememberTextMeasurer()
            val short = isShortScreen()
            // La ayuda va junto al título si cabe; si no (pantallas de pie), debajo de él, en la misma barra: su alto no
            // cambia (de él sale la cuadrícula del inicio). En pantallas bajas no hay lugar debajo: solo el título.
            BoxWithConstraints(Modifier.weight(1f)) {
                // Las páginas van junto al título (y la ayuda, si aún cabe).
                val bar = pageBarWidth(pages) + 14.dp
                val needed = with(LocalDensity.current) {
                    remember(title, hint, this) {
                        measurer.measure(title, Typography.subtitle).size.width + measurer.measure(hint, Typography.small).size.width
                    }.toDp() + 14.dp
                }
                val fits = needed + bar <= maxWidth
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (fits) {
                        Label(title, Typography.subtitle, maxLines = 1)
                        Label(hint, Typography.small, c.textMuted, maxLines = 1)
                    } else {
                        Column(Modifier.weight(1f, fill = false)) {
                            Label(title, Typography.subtitle, maxLines = 1)
                            if (!short) Label(hint, Typography.small, c.textMuted, maxLines = 2)
                        }
                    }
                    PageBar(pages)
                }
            }
            Button(stringResource(R.string.wallpaper_button), onWallpaper)
            Button(stringResource(R.string.designs), onDesigns)
            Button(stringResource(R.string.add), onAdd)
            Button(stringResource(R.string.done), onFinish, primary = true)
        } else {
            val clock = rememberClock()
            // Con nombre: el saludo primero y la fecha después, más tenue.
            val hello = greeting(driverName)
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (hello != null) Label(hello, Typography.subtitle, maxLines = 1)
                if (guide != null) Box(Modifier.weight(1f, fill = false)) { guide() }
                else Label(clock.date, Typography.body, c.textMuted, maxLines = 1)
            }
            WeatherChip(weather)
            StatusIcons()
            Label(clock.full, Typography.subtitle, maxLines = 1)
        }
    }
}

/** Aviso cuando LiteDash no es la pantalla de inicio: un toque para arreglarlo, o no volver a preguntar. */
@Composable
private fun HomePrompt(prefs: Preferences, revision: Int) {
    val (request, returns) = rememberRequestHome()
    // Fuera del hilo principal (pregunta al sistema); mientras llega, sin aviso.
    if (rememberIsHome(revision, returns) != false) return
    Notice(stringResource(R.string.not_home_notice)) {
        Button(stringResource(R.string.make_home), request, primary = true)
        Button(stringResource(R.string.dont_ask), { prefs.dontAskHome = true })
    }
}

/** El asistente de voz del sistema (manos libres primero); null si la pantalla no trae ninguno. */
private fun voiceIntent(context: Context): Intent? =
    listOf(Intent.ACTION_VOICE_COMMAND, Intent.ACTION_ASSIST)
        .map { Intent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        .firstOrNull { it.resolveActivity(context.packageManager) != null }

/** [voiceIntent] preguntado fuera del hilo principal (es una consulta al PackageManager); null mientras llega. */
@Composable
internal fun rememberVoiceIntent(): Intent? {
    val context = LocalContext.current
    return produceState<Intent?>(null) { value = withContext(Dispatchers.IO) { voiceIntent(context) } }.value
}

/** Lo que mide la barra de Android de arriba mientras está oculta (0 si se ve). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun statusBarHiddenHeight(): Dp {
    val density = LocalDensity.current
    val always = WindowInsets.statusBarsIgnoringVisibility.getTop(density)
    val now = WindowInsets.statusBars.getTop(density)
    return with(density) { (always - now).coerceAtLeast(0).toDp() }
}

/** Tamaño mínimo de la pieza del auto o imagen para ponerle las pastillas de llantas en sus esquinas. */
private val PILLS_MIN_WIDTH = 220.dp
private val PILLS_MIN_HEIGHT = 160.dp

/** Lo más que el auto en grande espera a estar pintado antes de crecer, lo que tarda en crecer y en regresar. */
private const val CAR_WAIT_MS = 600L
private const val CAR_GROW_MS = 480
private const val CAR_SHRINK_MS = 520

/** Lo más que Llantas espera a que el auto de arriba esté pintado antes de aparecer. */
private const val TIRES_WAIT_MS = 300L

/** Lo que tardan las llantas a pantalla completa en aparecer (y en irse). */
private const val TIRES_FADE_MS = 260

/** Una opción del widget (de fábrica prendida, [WidgetFlags]). */
private fun Tile.flag(key: String) = WidgetFlags.on(data, key)

/** Cuánto tarda en crecer lo que se ve en grande ([Expanded]) y en regresar a su widget. */
private const val EXPAND_MS = 320
private const val SHRINK_MS = 260

/**
 * La capa de lo que se ve en grande: llena el tablero y crece desde [from] (el widget que se tocó, en la raíz)
 * o, sin él, desde el centro un poco más chica; aparece desvaneciéndose. [content] se acomoda una sola vez a su
 * tamaño final y la animación solo lo escala (en las pantallas lentas no se reacomoda en cada cuadro). Tapa los
 * toques: nada de lo de abajo se toca por accidente.
 */
@Composable
private fun ExpandedLayer(from: Rect?, progress: () -> Float, content: @Composable () -> Unit) {
    var area by remember { mutableStateOf(Rect.Zero) }
    val shape = RoundedCornerShape(Radius.card)
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { area = it.boundsInRoot() }
            .graphicsLayer {
                val t = progress()
                val start = from?.takeIf { !it.isEmpty && !area.isEmpty }
                if (start != null) {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = start.width / area.width + (1f - start.width / area.width) * t
                    scaleY = start.height / area.height + (1f - start.height / area.height) * t
                    translationX = (start.left - area.left) * (1f - t)
                    translationY = (start.top - area.top) * (1f - t)
                } else {
                    val scale = 0.92f + 0.08f * t
                    scaleX = scale
                    scaleY = scale
                }
                alpha = (t * 2.5f).coerceAtMost(1f)
                this.shape = shape
                clip = true
            }
            .canvas()
            .blockTouches(),
    ) { CompositionLocalProvider(LocalCardLook provides globalLook(LocalCardLook.current)) { FitShort(SHORT_EXPANDED_HEIGHT, content) } }
}
