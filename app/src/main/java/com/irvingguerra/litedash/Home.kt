package com.irvingguerra.litedash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.feature.updater.Updater
import com.irvingguerra.litedash.feature.apps.App
import com.irvingguerra.litedash.feature.apps.AppIcon
import com.irvingguerra.litedash.feature.apps.AppRepository
import com.irvingguerra.litedash.feature.apps.rememberApps
import com.irvingguerra.litedash.feature.car.CarImage
import com.irvingguerra.litedash.feature.car.CarPackage
import com.irvingguerra.litedash.feature.car.CarRepository
import com.irvingguerra.litedash.feature.car.CarTile
import com.irvingguerra.litedash.feature.car.CarViews
import com.irvingguerra.litedash.feature.music.MusicExpanded
import com.irvingguerra.litedash.feature.music.MusicTile
import com.irvingguerra.litedash.feature.navigation.NavigationRepository
import com.irvingguerra.litedash.feature.dashboard.Tile
import com.irvingguerra.litedash.feature.music.MusicRepository
import com.irvingguerra.litedash.feature.dashboard.Grid
import com.irvingguerra.litedash.feature.dashboard.DashboardState
import com.irvingguerra.litedash.feature.dashboard.Dashboard
import com.irvingguerra.litedash.feature.dashboard.TileTypes

/** Qué panel está abierto encima del inicio (uno a la vez). */
sealed interface Panel {
    data object Drawer : Panel
    data object Settings : Panel
    data object Car : Panel
    data object AddTile : Panel
    data object Templates : Panel
    /** Elegir app para una pieza existente ([tileId]) o para una nueva (null). */
    data class PickApp(val tileId: String?) : Panel
}

class Dependencies(
    val apps: AppRepository,
    val music: MusicRepository,
    val navigation: NavigationRepository,
    val prefs: Preferences,
    val dashboard: DashboardState,
    val cars: CarRepository,
    val updater: Updater,
)

/** Qué hace cada pieza al tocarla (abrir un panel, el mapa o la música en grande). */
class TileActions(
    val onPanel: (Panel) -> Unit,
    val onNavigate: (App?) -> Unit,
    val onMusic: () -> Unit,
)

/** Cada tipo de pieza dibujado según su tipo. */
@Composable
fun TileContent(d: Dependencies, p: Tile, apps: List<App>, revision: Int, actions: TileActions) {
    val context = LocalContext.current
    when (p.type) {
        TileTypes.CAR -> {
            val color = d.prefs.carColor
            val pkg = remember(d.prefs.carModel, d.cars.changes) { d.cars.pkg(d.prefs.carModel) }
            CarTile(
                name = d.prefs.carName ?: pkg?.name ?: stringResource(R.string.my_car),
                color = color,
                onClick = { actions.onPanel(Panel.Car) },
                drawing = pkg?.let { carPkg -> { m -> CarImage(d.cars, carPkg, dashboardView(carPkg), color, m) } },
                hasFloor = pkg?.let { dashboardView(it) in it.floor } == true,
            )
        }
        TileTypes.MUSIC -> MusicTile(d.music, revision, onExpand = actions.onMusic)
        TileTypes.CLOCK -> ClockTile()
        TileTypes.NAVIGATE -> {
            val app = mapApp(d, apps, p.data)
            NavigationTile(d.navigation, app) { actions.onNavigate(app) }
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

/** La vista del auto en el tablero: la de escena (con piso) si el paquete la trae. */
fun dashboardView(p: CarPackage) = if (CarViews.SCENE in p.views) CarViews.SCENE else CarViews.THREE_QUARTER

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
    var panel by remember { mutableStateOf<Panel?>(null) }
    var editing by remember { mutableStateOf(false) }
    var musicExpanded by remember { mutableStateOf(false) }

    BackHandler(enabled = (panel != null || editing || musicExpanded) && d.prefs.welcomeSeen) {
        when {
            panel != null -> panel = null
            musicExpanded -> musicExpanded = false
            else -> editing = false
        }
    }
    // Botón de inicio estando ya aquí: de vuelta al tablero tal como lo acomodaste.
    LaunchedEffect(homePressed) {
        if (d.prefs.welcomeSeen) {
            panel = null
            musicExpanded = false
            editing = false
        }
    }

    val actions = TileActions(
        onPanel = { panel = it },
        onNavigate = { app -> openNavigation(context, d.apps, app) },
        onMusic = { musicExpanded = true },
    )

    // Con un auto que trae piso, el fondo baja un poco de tono hacia abajo: es el
    // piso donde se para el auto (como en Tesla), y su sombra y reflejo caen ahí.
    val hasFloor = d.dashboard.tiles.any { it.type == TileTypes.CAR } &&
        remember(d.prefs.carModel, d.cars.changes) { d.cars.pkg(d.prefs.carModel) }?.let { dashboardView(it) in it.floor } == true
    val backdrop = if (hasFloor) {
        Modifier.background(Brush.verticalGradient(0.3f to c.background, 1f to lerp(c.background, Color.Black, if (c.dark) 0.35f else 0.08f)))
    } else Modifier.background(c.background)
    Box(Modifier.fillMaxSize().then(backdrop)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            TopBar(
                editing = editing,
                // Si ya hay un reloj en el tablero, la barra no repite la hora.
                showTime = d.dashboard.tiles.none { it.type == TileTypes.CLOCK },
                onAdd = { panel = Panel.AddTile },
                onTemplates = { panel = Panel.Templates },
                onFinish = { editing = false },
            )
            if (d.prefs.welcomeSeen && !d.prefs.dontAskHome && !editing && !isShortScreen()) HomePrompt(d.prefs, revision)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp)) {
                val grid = remember(maxWidth, maxHeight) { Grid.forSize(maxWidth.value, maxHeight.value) }
                remember(grid) { d.dashboard.use(grid) }
                if (musicExpanded) {
                    MusicExpanded(d.music, close = { musicExpanded = false }, Modifier.padding(6.dp))
                } else {
                    Dashboard(
                        grid = grid,
                        tiles = d.dashboard.tiles,
                        editing = editing,
                        limits = ::limitsOf,
                        onChange = d.dashboard::change,
                        onEdit = { editing = true },
                    ) { p -> TileContent(d, p, apps, revision, actions) }
                }
            }
            Dock(
                repo = d.apps,
                favorites = dockFavorites(d, apps),
                onCar = { panel = Panel.Car },
                onApps = { panel = Panel.Drawer },
                onSettings = { panel = Panel.Settings },
            )
        }

        when (val p = panel) {
            null -> Unit
            Panel.Drawer -> DrawerPanel(d, apps, close = { panel = null })
            Panel.Settings -> SettingsPanel(d, close = { panel = null }, onEdit = { panel = null; editing = true }, onTemplates = { panel = Panel.Templates })
            Panel.Car -> CarPanel(d.prefs, d.cars, close = { panel = null })
            Panel.Templates -> TemplatesPanel(d.dashboard, close = { panel = null })
            Panel.AddTile -> AddTilePanel(d.dashboard, close = { panel = null }, onPickApp = { panel = Panel.PickApp(null) })
            is Panel.PickApp -> PickAppPanel(d, apps, p.tileId, close = { panel = null })
        }

        if (!d.prefs.welcomeSeen) {
            Welcome(d.prefs, d.dashboard, revision, onFinish = { d.prefs.welcomeSeen = true })
        }
    }
}

/** Las del usuario, o unas sugeridas con lo que tenga instalado (navegación, música, mensajes). */
private fun dockFavorites(d: Dependencies, apps: List<App>): List<App> {
    d.prefs.dock?.let { keys -> return keys.mapNotNull { d.apps.find(apps, it) } }
    return listOfNotNull(
        d.apps.firstOf(apps, *NAVIGATION_PACKAGES),
        // La app de música elegida en Ajustes, o la primera conocida que haya.
        d.prefs.musicApp?.let { d.apps.firstOf(apps, it) } ?: d.apps.firstOf(apps, *MUSIC_PACKAGES),
        d.apps.firstOf(apps, "com.google.android.youtube"),
        d.apps.firstOf(apps, "com.whatsapp", "org.telegram.messenger"),
        d.apps.firstOf(apps, "com.android.chrome"),
    )
}

@Composable
private fun TopBar(editing: Boolean, showTime: Boolean, onAdd: () -> Unit, onTemplates: () -> Unit, onFinish: () -> Unit) {
    val c = LocalColors.current
    // Sin nada que mostrar (la hora ya está en una pieza), la barra no aparta espacio: el tablero sube.
    if (!editing && !showTime) return Spacer(Modifier.height(12.dp))
    Row(
        Modifier.fillMaxWidth().height(if (isShortScreen()) 44.dp else 64.dp).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (editing) {
            Label(stringResource(R.string.arrange_your_home), Typography.subtitle, maxLines = 1, modifier = Modifier.weight(1f))
            Button(stringResource(R.string.templates), onTemplates)
            Button(stringResource(R.string.add), onAdd)
            Button(stringResource(R.string.done), onFinish, primary = true)
        } else if (showTime) {
            val (time, date) = rememberClock()
            Label(date, Typography.body, c.textMuted, maxLines = 1, modifier = Modifier.weight(1f))
            Label(time, Typography.subtitle, maxLines = 1)
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
            .clip(RoundedCornerShape(24.dp))
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
                for (app in favorites.take(fitCount)) {
                    Box(Modifier.size(box).clip(RoundedCornerShape(16.dp)).clickable { repo.open(context, app) }, contentAlignment = Alignment.Center) {
                        AppIcon(repo, app, side)
                    }
                }
            }
        }
        Volume(buttonSize)
        IconButton(Icons.settings, onSettings, size = buttonSize)
    }
}

/** Volumen de la música: botones − / + y el nivel; escucha los cambios solo mientras se ve. */
@Composable
private fun Volume(buttonSize: androidx.compose.ui.unit.Dp) {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    var level by remember { mutableIntStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    DisposableEffect(audio) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                level = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            }
        }
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        onDispose { context.unregisterReceiver(receiver) }
    }
    fun adjust(direction: Int) {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        level = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(Icons.minus, { adjust(AudioManager.ADJUST_LOWER) }, size = buttonSize)
        Row(Modifier.widthIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Label(level.toString(), Typography.subtitle, maxLines = 1)
        }
        IconButton(Icons.plus, { adjust(AudioManager.ADJUST_RAISE) }, size = buttonSize)
        Spacer(Modifier.width(4.dp))
    }
}


/** Aviso cuando LiteDash no es la pantalla de inicio: un toque para arreglarlo, o no volver a preguntar. */
@Composable
private fun HomePrompt(prefs: Preferences, revision: Int) {
    val c = LocalColors.current
    val context = LocalContext.current
    val (request, returns) = rememberRequestHome()
    val isHome = remember(revision, returns) { isHomeApp(context) }
    if (isHome) return
    val box = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 4.dp)
        .clip(RoundedCornerShape(18.dp))
        .background(c.surface)
        .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
    val buttons = @Composable {
        Button(stringResource(R.string.make_home), request, primary = true)
        Button(stringResource(R.string.dont_ask), { prefs.dontAskHome = true })
    }
    // En angosto (teléfono parado) el texto no cabe junto a los botones: va arriba.
    if (LocalConfiguration.current.screenWidthDp < 560) {
        Column(box, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Label(stringResource(R.string.not_home_notice), Typography.body, maxLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { buttons() }
        }
    } else {
        Row(box, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Label(stringResource(R.string.not_home_notice), Typography.body, maxLines = 2, modifier = Modifier.weight(1f))
            buttons()
        }
    }
}
