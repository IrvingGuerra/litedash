package com.irvingguerra.litedash

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LiteDashTheme
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Radius
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.core.vehicle.VehicleBus
import com.irvingguerra.litedash.feature.apps.App
import com.irvingguerra.litedash.feature.apps.AppIcon
import com.irvingguerra.litedash.feature.apps.AppRepository
import com.irvingguerra.litedash.feature.apps.rememberApps
import com.irvingguerra.litedash.feature.car.CarPackage
import com.irvingguerra.litedash.feature.car.CarRepository
import com.irvingguerra.litedash.feature.dashboard.Dashboard
import com.irvingguerra.litedash.feature.dashboard.DashboardState
import com.irvingguerra.litedash.feature.dashboard.Grid
import com.irvingguerra.litedash.feature.dashboard.Tile
import com.irvingguerra.litedash.feature.dashboard.TileTypes
import com.irvingguerra.litedash.feature.dashboard.packed
import com.irvingguerra.litedash.feature.music.MusicExpanded
import com.irvingguerra.litedash.feature.music.MusicRepository
import com.irvingguerra.litedash.feature.music.MusicTile
import com.irvingguerra.litedash.feature.navigation.NavigationRepository
import com.irvingguerra.litedash.feature.obd.ObdRepository
import com.irvingguerra.litedash.feature.updater.Updater
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Qué panel está abierto encima del inicio (uno a la vez). */
sealed interface Panel {
    data object Drawer : Panel
    data object Settings : Panel
    /** Ajustes abiertos en "Acerca de" (desde el aviso de versión nueva). */
    data object About : Panel
    /** Ajustes abiertos en "Tu auto" (botón del auto en el dock). */
    data object CarSettings : Panel
    data object AddTile : Panel
    data object Templates : Panel
    /** Elegir app para una pieza existente ([tileId]) o para una nueva (null). */
    data class PickApp(val tileId: String?) : Panel
    /** Ajustes abiertos en el Taller (pieza de acceso directo). */
    data object Workshop : Panel
    /** Elegir qué dato muestra un medidor existente ([tileId]) o uno nuevo (null). */
    data class PickGauge(val tileId: String?) : Panel
    /** Estilo, color y demás de un velocímetro o medidor ([tileId]). */
    data class CustomizeGauge(val tileId: String) : Panel
}

class Dependencies(
    val apps: AppRepository,
    val music: MusicRepository,
    val navigation: NavigationRepository,
    val prefs: Preferences,
    val dashboard: DashboardState,
    val cars: CarRepository,
    val updater: Updater,
    val weather: WeatherRepository,
    val trip: TripRepository,
    val reminders: ReminderRepository,
    val wheel: WheelKeys,
    val stats: Stats,
    /** Señales del auto (OBD, GPS…): el tablero solo lee de aquí. */
    val bus: VehicleBus,
    private val obdLazy: Lazy<ObdRepository>,
    private val obdSaved: () -> Boolean,
) {
    /** Estado de Compose: las piezas que preguntaron antes de que existiera se vuelven a dibujar. */
    private val obdCreated = mutableStateOf(false)

    /** El escáner OBD: se crea la primera vez que algo lo pide (el Taller o una pieza). */
    val obd: ObdRepository
        get() = obdLazy.value.also { if (!obdCreated.value) obdCreated.value = true }

    /** Ya se usó un adaptador OBD alguna vez (sin crear el escáner para saberlo). */
    val hasObdAdapter get() = obdSaved()

    /** El escáner si ya existe, sin crearlo (una pieza solo quiere saber si está conectado). */
    fun obdIfCreated(): ObdRepository? = if (obdCreated.value) obdLazy.value else null
}

/**
 * Qué hace cada pieza al tocarla (abrir un panel, el mapa, la música o el auto en
 * grande). Se crea una vez: sus funciones leen el estado del inicio al llamarse.
 */
class TileActions(
    val onPanel: (Panel) -> Unit,
    val onNavigate: (App?) -> Unit,
    val onMusic: () -> Unit,
    val onCarOnly: () -> Unit,
    /** Dónde está la pieza del auto en la pantalla: de ahí crece al verlo solo. */
    val onCarBounds: (Rect) -> Unit,
    /** Se lee al dibujar (no recompone el tablero en cada cuadro de la animación). */
    val carHidden: () -> Boolean,
)

/** Cada tipo de pieza dibujado según su tipo. */
@Composable
fun TileContent(d: Dependencies, p: Tile, apps: List<App>, car: CarPackage?, revision: Int, actions: TileActions) {
    val context = LocalContext.current
    when (p.type) {
        TileTypes.CAR -> HomeCar(
            d,
            car,
            onClick = actions.onCarOnly,
            modifier = Modifier
                .onGloballyPositioned { actions.onCarBounds(it.boundsInRoot()) }
                // Mientras crece o se ve solo, el de la pieza se esconde (si no, se ve doble).
                .graphicsLayer { alpha = if (actions.carHidden()) 0f else 1f },
        )
        TileTypes.MUSIC -> MusicTile(d.music, revision, onExpand = actions.onMusic)
        TileTypes.CLOCK -> ClockTile(d.prefs.driverName, d.weather)
        TileTypes.WEATHER -> WeatherTile(d.weather)
        TileTypes.TRIP -> TripTile(d.trip, d.prefs.language)
        TileTypes.SPEEDOMETER -> SpeedometerTile(d, d.prefs.language, GaugeLook.parse(p.data))
        TileTypes.GAUGE -> GaugeTile(
            d, signalOf(p.data), d.prefs.language, look = GaugeLook.parse(p.data),
            onPick = { actions.onPanel(Panel.PickGauge(p.id)) },
            onWorkshop = { actions.onPanel(Panel.Workshop) },
        )
        TileTypes.WORKSHOP -> WorkshopTile(d) { actions.onPanel(Panel.Workshop) }
        TileTypes.PROJECTION -> ProjectionTile(d, apps) { actions.onPanel(Panel.Drawer) }
        TileTypes.NAVIGATE -> {
            val app = mapApp(d, apps, p.data)
            NavigationTile(
                d.navigation,
                app,
                home = d.prefs.homeAddress,
                work = d.prefs.workAddress,
                onPlace = { navigateTo(context, app, it) },
            ) { actions.onNavigate(app) }
        }
        TileTypes.PHONE -> ShortcutTile(Icons.phone, stringResource(R.string.tile_phone), null) {
            openDialer(context)
        }
        TileTypes.APP -> {
            val app = d.apps.find(apps, p.data)
            AppTile(d.apps, app) {
                if (app != null) d.apps.open(context, app) else actions.onPanel(Panel.PickApp(p.id))
            }
        }
    }
}

/** La de la pieza, si eligió una; si no, la de Ajustes; si no, la primera instalada. */
fun mapApp(d: Dependencies, apps: List<App>, data: String = ""): App? =
    d.apps.find(apps, data)
        ?: d.prefs.mapApp?.let { d.apps.find(apps, it) }
        ?: d.apps.firstOf(apps, *NAVIGATION_PACKAGES)

/** Inicio: barra de estado, tablero personalizable y dock. */
@Composable
fun Home(d: Dependencies, revision: Int, homePressed: Int) {
    val c = LocalColors.current
    val context = LocalContext.current
    val apps = rememberApps(d.apps).orEmpty()
    val currentApps by rememberUpdatedState(apps)
    val car = d.cars.pkg(d.prefs.carModel)
    var panel by remember { mutableStateOf<Panel?>(null) }
    var editing by remember { mutableStateOf(false) }
    var musicExpanded by remember { mutableStateOf(false) }
    // Solo el auto a pantalla completa (un toque al auto entra y otro sale), con animación:
    // [carOnly] es a dónde va y [carShown] si la capa sigue en pantalla (también al regresar).
    var carOnly by remember { mutableStateOf(false) }
    fun backToDashboard() {
        panel = null
        carOnly = false
        musicExpanded = false
        editing = false
    }

    var carShown by remember { mutableStateOf(false) }
    var carReady by remember { mutableStateOf(false) }
    var carBounds by remember { mutableStateOf(Rect.Zero) }
    val carProgress = remember { Animatable(0f) }
    LaunchedEffect(carOnly) {
        if (carOnly) {
            carShown = true
            // Crece cuando la imagen grande ya está pintada (en su pieza se ve igual que la
            // chica); si tarda, crece de todos modos.
            withTimeoutOrNull(600) { snapshotFlow { carReady }.first { it } }
            carProgress.animateTo(1f, tween(480, easing = FastOutSlowInEasing))
        } else if (carShown) {
            carProgress.animateTo(0f, tween(400, easing = FastOutSlowInEasing))
            carShown = false
            carReady = false
        }
    }

    // Cada toque y cada botón del volante reinician la cuenta del modo estacionado. Se
    // leen dentro del efecto (no al armar la pantalla): tocar no vuelve a armar el inicio.
    var touched by remember { mutableIntStateOf(0) }
    var parked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        snapshotFlow { listOf(touched, d.wheel.presses, panel, editing, musicExpanded, carOnly, d.prefs.parkedMode) }
            .collectLatest {
                parked = false
                if (!d.prefs.parkedMode || panel != null || editing || musicExpanded || carOnly) return@collectLatest
                delay(PARK_AFTER_MS)
                // Manejando no: espera a que se detenga (solo se sabe con la pieza Viaje).
                snapshotFlow { d.trip.moving }.first { !it }
                parked = true
            }
    }

    BackHandler(enabled = (panel != null || editing || musicExpanded || carOnly) && d.prefs.welcomeSeen) {
        when {
            panel != null -> panel = null
            carOnly -> carOnly = false
            musicExpanded -> musicExpanded = false
            else -> editing = false
        }
    }
    // Botón de inicio estando ya aquí: de vuelta al tablero tal como lo acomodaste.
    LaunchedEffect(homePressed) { if (d.prefs.welcomeSeen) backToDashboard() }
    // Clima al volver al inicio (si pasó media hora y está activado); nunca en segundo plano.
    LaunchedEffect(revision) { d.weather.refreshIfStale() }
    // Versión nueva de la app (a lo más cada 12 h) y modelo 3D nuevo del auto (cada hora).
    LaunchedEffect(Unit) { d.updater.checkQuietly() }
    LaunchedEffect(d.prefs.carModel) { d.cars.checkQuietly(d.prefs.carModel) }
    // Cuántas pantallas usan LiteDash: a lo más una vez al día.
    LaunchedEffect(Unit) { d.stats.pingDaily() }

    val actions = remember {
        TileActions(
            onPanel = { panel = it },
            onNavigate = { app -> openNavigation(context, d.apps, app) },
            onMusic = { musicExpanded = true },
            onCarOnly = { carOnly = true },
            onCarBounds = { carBounds = it },
            carHidden = { carShown && carProgress.value > 0f },
        )
    }

    // Mandos al volante: las acciones que abren algo (mapa, música, voz, inicio) se hacen
    // aquí, una sola vez cada una.
    LaunchedEffect(Unit) {
        snapshotFlow { d.wheel.request }.filterNotNull().collect { action ->
            d.wheel.consume()
            when (action) {
                WheelAction.NAVIGATE -> actions.onNavigate(mapApp(d, currentApps))
                WheelAction.MUSIC -> { backToDashboard(); musicExpanded = true }
                WheelAction.VOICE -> voiceIntent(context)?.let { runCatching { context.startActivity(it) } }
                WheelAction.HOME -> backToDashboard()
                else -> Unit
            }
        }
    }

    // Con un auto que trae piso, el fondo baja un poco de tono hacia abajo: es el
    // piso donde se para el auto (como en Tesla), y su sombra y reflejo caen ahí.
    val hasFloor = car?.hasFloor == true && d.prefs.carView == CarView.CAR &&
        d.dashboard.tiles.any { it.type == TileTypes.CAR }
    val backdrop = if (hasFloor) {
        Brush.verticalGradient(0.3f to c.background, 1f to lerp(c.background, Color.Black, if (c.dark) 0.35f else 0.08f))
    } else SolidColor(c.background)
    var promptsHeight by remember { mutableIntStateOf(0) }
    Box(
        Modifier
            .fillMaxSize()
            .background(backdrop)
            // Cualquier toque reinicia la cuenta del modo estacionado (sin quitarle el toque a nadie).
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    touched++
                }
            },
    ) {
        // Tu imagen de fondo (Apariencia → Fondo del inicio), detrás de todo.
        if (panel == null) HomeWallpaper(d.prefs)
        // Los paneles tapan todo el inicio: mientras hay uno abierto no se dibuja ni se
        // pinta nada debajo (el auto del tablero no se tiñe dos veces al cambiar el color).
        if (panel == null) BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
          // Pantalla acostada: barra lateral a la izquierda (como DuduAuto), con la hora y tus
          // apps; el tablero se queda con toda la altura. Parada: el dock abajo, como siempre.
          val rail = when (d.prefs.barPosition) {
              BarPosition.SIDE -> true
              BarPosition.BOTTOM -> false
              BarPosition.AUTO -> maxWidth > maxHeight && !isShortScreen()
          }
          val favorites = remember(apps, d.prefs.dock, d.prefs.musicApp) { dockFavorites(d, apps) }
          Row(Modifier.fillMaxSize()) {
            if (rail) Rail(
                repo = d.apps,
                favorites = favorites,
                onCar = { panel = Panel.CarSettings },
                onApps = { panel = Panel.Drawer },
                onSettings = { panel = Panel.Settings },
            )
            Column(Modifier.weight(1f).fillMaxHeight()) {
                // Si ya hay un reloj en el tablero (o en la barra lateral), la barra no repite la hora.
                val showTime = !rail && d.dashboard.tiles.none { it.type == TileTypes.CLOCK }
                val barHidden = !editing && !showTime
                TopBar(
                    editing = editing,
                    showTime = showTime,
                    driverName = d.prefs.driverName,
                    weather = d.weather,
                    onAdd = { panel = Panel.AddTile },
                    onTemplates = { panel = Panel.Templates },
                    onFinish = { editing = false },
                )
                // Los avisos tampoco cuentan para la cuadrícula: aparecen y se van, y no deben
                // hacer que se cargue otro acomodo.
                Column(Modifier.onSizeChanged { promptsHeight = it.height }) {
                    if (d.prefs.welcomeSeen && !editing) {
                        if (!d.prefs.dontAskHome && !isShortScreen()) HomePrompt(d.prefs, revision)
                        EngineAlertBanner(d)
                        TripRecorderEffect(d)
                        if (!isShortScreen()) ReminderBanner(d.reminders)
                        UpdateBanner(d.updater, onOpen = { panel = Panel.About })
                        val newModel = d.cars.outdated?.takeIf { it.id == car?.id }
                        if (car != null && newModel != null) CarUpdateNotice(d.cars, car, newModel)
                    }
                }
                // Con la barra al costado no hay dock abajo: el tablero lleva su propio aire inferior
                // (en pantallas sin barra de gestos las piezas quedaban pegadas al borde).
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = if (rail) 10.dp else 0.dp)) {
                    // La cuadrícula se calcula siempre como si la barra estuviera: si dependiera
                    // de ella, un reloj en el acomodo la oculta, la cuadrícula crece, se carga
                    // otro acomodo sin reloj, vuelve la barra... y el inicio parpadea sin parar.
                    val prompts = with(LocalDensity.current) { promptsHeight.toDp() }
                    // El aire inferior de la barra lateral tampoco cuenta: la cuadrícula (y el acomodo
                    // guardado) debe ser la misma con o sin él.
                    // Con la barra de Android oculta hay más alto, pero la cuadrícula tampoco cambia.
                    val statusHidden = statusBarHiddenHeight()
                    val gridHeight = (if (barHidden) maxHeight - (topBarHeight() - TOP_BAR_HIDDEN) else maxHeight) + prompts + (if (rail) 10.dp else 0.dp) - statusHidden
                    val grid = remember(maxWidth, gridHeight) { Grid.forSize(maxWidth.value, gridHeight.value) }
                    // Carga el acomodo de esta cuadrícula antes de dibujar (solo hace algo si cambió).
                    d.dashboard.use(grid)
                    if (musicExpanded) {
                        // La música a la derecha y tus piezas acomodadas en la mitad izquierda (sin
                        // tocar el acomodo guardado): no tapa todo y es claro cómo volver.
                        val half = remember(grid) { Grid((grid.columns / 2).coerceAtLeast(2), grid.rows) }
                        val tiles = d.dashboard.tiles
                        val packed = remember(tiles, half) { tiles.filter { it.type != TileTypes.MUSIC }.packed(half, ::limitsOf) }
                        Row(Modifier.fillMaxSize()) {
                            Dashboard(
                                grid = half,
                                tiles = packed,
                                editing = false,
                                limits = ::limitsOf,
                                onChange = {},
                                onEdit = { musicExpanded = false; editing = true },
                                modifier = Modifier.weight(1f),
                            ) { p -> TileContent(d, p, apps, car, revision, actions) }
                            MusicExpanded(d.music, close = { musicExpanded = false }, Modifier.weight(1f).padding(6.dp))
                        }
                    } else {
                        Dashboard(
                            grid = grid,
                            tiles = d.dashboard.tiles,
                            editing = editing,
                            limits = ::limitsOf,
                            onChange = d.dashboard::change,
                            onEdit = { editing = true },
                            onSettings = { panel = Panel.CustomizeGauge(it.id) },
                            hasSettings = { it.type == TileTypes.SPEEDOMETER || it.type == TileTypes.GAUGE },
                        ) { p -> TileContent(d, p, apps, car, revision, actions) }
                    }
                }
                if (!rail) Dock(
                    repo = d.apps,
                    favorites = favorites,
                    onCar = { panel = Panel.CarSettings },
                    onApps = { panel = Panel.Drawer },
                    onSettings = { panel = Panel.Settings },
                )
            }
          }
        }

        when (val p = panel) {
            null -> Unit
            Panel.Drawer -> DrawerPanel(d, apps, close = { panel = null })
            Panel.Settings, Panel.About, Panel.CarSettings, Panel.Workshop -> SettingsPanel(
                d,
                revision = revision,
                close = { panel = null },
                onEdit = { panel = null; editing = true },
                onTemplates = { panel = Panel.Templates },
                start = when (p) {
                    Panel.About -> SettingsSection.ABOUT
                    Panel.CarSettings -> SettingsSection.CAR
                    Panel.Workshop -> SettingsSection.WORKSHOP
                    else -> SettingsSection.CAR
                },
            )
            Panel.Templates -> TemplatesPanel(d.dashboard, d.prefs, close = { panel = null })
            Panel.AddTile -> AddTilePanel(
                d.dashboard,
                close = { panel = null },
                onPickApp = { panel = Panel.PickApp(null) },
                onPickGauge = { panel = Panel.PickGauge(null) },
            )
            is Panel.PickApp -> PickAppPanel(d, apps, p.tileId, close = { panel = null })
            is Panel.PickGauge -> PickGaugePanel(d, p.tileId, close = { panel = null })
            is Panel.CustomizeGauge -> CustomizeGaugePanel(d, p.tileId, close = { panel = null }, onPickSignal = { panel = Panel.PickGauge(p.tileId) })
        }

        if (carShown && panel == null) {
            CarOnly(d, car, carBounds, { carProgress.value }, backdrop, onTap = { carOnly = false }, onReady = { carReady = true })
        }

        if (parked) Parked(d, onWake = { parked = false; touched++ })

        // Animación de entrada con tu imagen, una vez por cada vez que se abre LiteDash.
        var intro by rememberSaveable { mutableStateOf(d.prefs.logoIntro && d.prefs.welcomeSeen) }
        if (intro) LogoIntro(d.prefs) { intro = false }

        if (!d.prefs.welcomeSeen) {
            Welcome(d.prefs, d.dashboard, revision, onFinish = { d.prefs.welcomeSeen = true })
        }
    }
}

/**
 * Estacionado: reloj grande, saludo y clima sobre fondo oscuro. Sale tras
 * [PARK_AFTER_MS] sin tocar el inicio (y sin ir en movimiento); un toque lo quita.
 */
@Composable
private fun Parked(d: Dependencies, onWake: () -> Unit) {
    val clock = rememberClock()
    val hello = greeting(d.prefs.driverName)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onWake),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Siempre en colores oscuros: de noche no deslumbra.
            LiteDashTheme(dark = true, scale = 1f) {
                BigTime(clock, Typography.clock.copy(fontSize = 120.sp))
                Label(hello?.let { "$it · ${clock.date}" } ?: clock.date, Typography.subtitle, LocalColors.current.textMuted, maxLines = 1)
                Box(Modifier.padding(top = 12.dp)) { WeatherChip(d.weather) }
            }
        }
    }
}

private const val PARK_AFTER_MS = 5 * 60_000L

/** Las del usuario, o unas sugeridas con lo que tenga instalado (navegación, música, mensajes). */
fun dockFavorites(d: Dependencies, apps: List<App>): List<App> {
    d.prefs.dock?.let { keys -> return keys.mapNotNull { d.apps.find(apps, it) } }
    return listOfNotNull(
        // Android Auto / CarPlay primero: mucha gente usa la pantalla con su celular.
        projectionApp(apps),
        d.apps.firstOf(apps, *NAVIGATION_PACKAGES),
        // La app de música elegida en Ajustes, o la primera conocida que haya.
        d.prefs.musicApp?.let { d.apps.firstOf(apps, it) } ?: d.apps.firstOf(apps, *MUSIC_PACKAGES),
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
private fun TopBar(editing: Boolean, showTime: Boolean, driverName: String?, weather: WeatherRepository, onAdd: () -> Unit, onTemplates: () -> Unit, onFinish: () -> Unit) {
    val c = LocalColors.current
    // Sin nada que mostrar (la hora ya está en una pieza), la barra no aparta espacio: el tablero sube.
    if (!editing && !showTime) return Spacer(Modifier.height(TOP_BAR_HIDDEN))
    Row(
        Modifier.fillMaxWidth().height(topBarHeight()).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (editing) {
            Label(stringResource(R.string.arrange_your_home), Typography.subtitle, maxLines = 1, modifier = Modifier.weight(1f))
            Button(stringResource(R.string.templates), onTemplates)
            Button(stringResource(R.string.add), onAdd)
            Button(stringResource(R.string.done), onFinish, primary = true)
        } else {
            val clock = rememberClock()
            // Con nombre: el saludo primero y la fecha después, más tenue.
            val hello = greeting(driverName)
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (hello != null) Label(hello, Typography.subtitle, maxLines = 1)
                Label(clock.date, Typography.body, c.textMuted, maxLines = 1)
            }
            WeatherChip(weather)
            StatusIcons()
            Label(clock.full, Typography.subtitle, maxLines = 1)
        }
    }
}

@Composable
private fun Dock(
    repo: AppRepository,
    favorites: List<App>,
    onCar: () -> Unit,
    onApps: () -> Unit,
    onSettings: () -> Unit,
) {
    val c = LocalColors.current
    val context = LocalContext.current
    // En pantallas chicas (7") todo se ve lejos: íconos más grandes, más fáciles de atinar manejando.
    // Pero si casi no hay altura (teléfono acostado), compacto: el tablero va primero.
    val short = isShortScreen()
    val small = isSmallScreen() && !short
    val side = if (small) 56.dp else if (short) 36.dp else 44.dp
    val box = if (small) 68.dp else if (short) 46.dp else 56.dp
    Row(
        Modifier
            .fillMaxWidth()
            .height(if (small) 96.dp else if (short) 64.dp else 84.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(Radius.card))
            .background(c.surface)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val buttonSize = if (small) 64.dp else if (short) 46.dp else 56.dp
        IconButton(Icons.car, onCar, size = buttonSize)
        IconButton(Icons.apps, onApps, size = buttonSize)
        Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(32.dp).background(c.line))
        BoxWithConstraints(Modifier.weight(1f)) {
            // Caben las que quepan: en pantallas chicas el dock no se encima.
            val fitCount = (maxWidth / (box + 8.dp)).toInt()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                for (app in favorites.take(fitCount)) AppShortcut(repo, app, box, side)
            }
        }
        Volume(buttonSize)
        // Voz: abre el asistente que tenga la pantalla; si no hay ninguno, no aparece.
        val voice = remember { voiceIntent(context) }
        if (voice != null) IconButton(Icons.mic, { runCatching { context.startActivity(voice) } }, size = buttonSize)
        IconButton(Icons.settings, onSettings, size = buttonSize)
    }
}

/** Una app favorita del dock o de la barra lateral: su ícono, un toque la abre. */
@Composable
private fun AppShortcut(repo: AppRepository, app: App, box: Dp, icon: Dp) {
    val context = LocalContext.current
    Box(Modifier.size(box).clip(RoundedCornerShape(Radius.inner)).clickable { repo.open(context, app) }, contentAlignment = Alignment.Center) {
        AppIcon(repo, app, icon)
    }
}

/**
 * Barra lateral (pantallas acostadas): hora, clima y estado arriba; el auto, todas las
 * apps y tus favoritas (las que quepan); abajo volumen, voz y Ajustes.
 */
@Composable
private fun Rail(
    repo: AppRepository,
    favorites: List<App>,
    onCar: () -> Unit,
    onApps: () -> Unit,
    onSettings: () -> Unit,
) {
    val c = LocalColors.current
    val context = LocalContext.current
    val small = isSmallScreen()
    val button = if (small) 52.dp else 56.dp
    val icon = if (small) 40.dp else 44.dp
    val clock = rememberClock()
    Column(
        Modifier.fillMaxHeight().width(if (small) 76.dp else 88.dp).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Angosta: a.m./p.m. en chico junto a la hora (en otra línea le quitaría lugar a las apps).
        Row(verticalAlignment = Alignment.Bottom) {
            Label(clock.time, Typography.subtitle, maxLines = 1)
            if (clock.marker != null) Label(clock.marker, Typography.small.copy(fontSize = 11.sp), c.textMuted, maxLines = 1, modifier = Modifier.padding(start = 2.dp, bottom = 2.dp))
        }
        Box(Modifier.padding(bottom = 6.dp)) { StatusIcons() }
        IconButton(Icons.car, onCar, size = button)
        IconButton(Icons.apps, onApps, size = button)
        Box(Modifier.padding(vertical = 4.dp).width(28.dp).height(1.dp).background(c.line))
        val voice = remember { voiceIntent(context) }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            // Las favoritas que quepan entre los accesos de arriba y los controles de abajo.
            val fit = (maxHeight / (button + 4.dp)).toInt()
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (app in favorites.take(fit)) AppShortcut(repo, app, button, icon)
            }
        }
        Volume(if (small) 40.dp else 44.dp, vertical = true)
        if (voice != null) IconButton(Icons.mic, { runCatching { context.startActivity(voice) } }, size = button)
        IconButton(Icons.settings, onSettings, size = button)
    }
}

/** Versión nueva encontrada al abrir LiteDash: verla en Ajustes o dejarla para después. */
@Composable
private fun UpdateBanner(updater: Updater, onOpen: () -> Unit) {
    val v = updater.notice ?: return
    Notice(stringResource(R.string.new_version, v.name), Icons.info) {
        Button(stringResource(R.string.update_see), onOpen, primary = true)
        Button(stringResource(R.string.update_later), updater::dismiss)
    }
}

/** Aviso cuando LiteDash no es la pantalla de inicio: un toque para arreglarlo, o no volver a preguntar. */
@Composable
private fun HomePrompt(prefs: Preferences, revision: Int) {
    val context = LocalContext.current
    val (request, returns) = rememberRequestHome()
    val isHome = remember(revision, returns) { isHomeApp(context) }
    if (isHome) return
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

/** Lo que mide la barra de Android de arriba mientras está oculta (0 si se ve). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun statusBarHiddenHeight(): Dp {
    val density = LocalDensity.current
    val always = WindowInsets.statusBarsIgnoringVisibility.getTop(density)
    val now = WindowInsets.statusBars.getTop(density)
    return with(density) { (always - now).coerceAtLeast(0).toDp() }
}
