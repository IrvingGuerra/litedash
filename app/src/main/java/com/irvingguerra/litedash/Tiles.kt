package com.irvingguerra.litedash

import com.irvingguerra.litedash.core.ui.Radius
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.feature.apps.App
import com.irvingguerra.litedash.feature.apps.AppIcon
import com.irvingguerra.litedash.feature.apps.AppRepository
import com.irvingguerra.litedash.feature.dashboard.DashboardState
import com.irvingguerra.litedash.feature.dashboard.SizeLimits
import com.irvingguerra.litedash.feature.dashboard.TileTypes
import com.irvingguerra.litedash.feature.music.NotificationService
import com.irvingguerra.litedash.feature.navigation.NavigationRepository
import com.irvingguerra.litedash.feature.navigation.TurnInstruction
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.delay

/** Lo que la app sabe de cada tipo de pieza: nombre, tamaños y con qué tamaño se agrega. */
data class TileSpec(val type: String, val name: Int, val limits: SizeLimits, val initialSize: Pair<Int, Int>)

val TileSpecs = listOf(
    TileSpec(TileTypes.CAR, R.string.tile_car, SizeLimits(3, 2), 5 to 3),
    TileSpec(TileTypes.MUSIC, R.string.tile_music, SizeLimits(3, 1, maxHeight = 3), 4 to 1),
    TileSpec(TileTypes.NAVIGATE, R.string.tile_navigate, SizeLimits(2, 1), 3 to 1),
    TileSpec(TileTypes.CLOCK, R.string.tile_clock, SizeLimits(2, 1), 3 to 1),
    TileSpec(TileTypes.PHONE, R.string.tile_phone, SizeLimits(1, 1), 2 to 1),
    TileSpec(TileTypes.WEATHER, R.string.tile_weather, SizeLimits(2, 1), 3 to 1),
    TileSpec(TileTypes.TRIP, R.string.tile_trip, SizeLimits(2, 1), 3 to 1),
    TileSpec(TileTypes.SPEEDOMETER, R.string.tile_speedometer, SizeLimits(2, 2), 3 to 2),
    TileSpec(TileTypes.WORKSHOP, R.string.workshop, SizeLimits(1, 1), 2 to 1),
    TileSpec(TileTypes.PROJECTION, R.string.tile_projection, SizeLimits(2, 1), 3 to 1),
    TileSpec(TileTypes.APP, R.string.tile_app, SizeLimits(1, 1, 3, 3), 1 to 1),
)

fun limitsOf(type: String) = TileSpecs.firstOrNull { it.type == type }?.limits ?: SizeLimits(1, 1)

// Apps de navegación en orden de preferencia (Waze, Google Maps, Maps.me, OsmAnd, HERE).
val NAVIGATION_PACKAGES = arrayOf(
    "com.waze", "com.google.android.apps.maps", "com.mapswithme.maps.pro", "net.osmand", "com.here.app.maps",
)

// Apps de música que se sugieren en el dock si no eligió una (Spotify primero).
val MUSIC_PACKAGES = arrayOf(
    "com.spotify.music", "com.google.android.apps.youtube.music", "deezer.android.app",
    "com.apple.android.music", "com.amazon.mp3",
)

/** Pantalla chica (7" o menos de alto útil): ahí los íconos crecen para atinarles manejando. */
@Composable
fun isSmallScreen(): Boolean = LocalConfiguration.current.screenHeightDp < 640

/**
 * Muy poca altura (un teléfono acostado): barras y dock compactos y sin aviso,
 * para que al tablero le quede espacio (si no, las piezas salen vacías).
 */
@Composable
fun isShortScreen(): Boolean = LocalConfiguration.current.screenHeightDp < 480

/** Hora y fecha que respetan el formato del usuario (12/24 h); un repintado por minuto. */
@Composable
fun rememberClock(): Pair<String, String> {
    val language = LocalConfiguration.current.locales[0]
    val context = LocalContext.current
    val time = remember(language) { DateFormat.getTimeFormat(context) }
    val date = remember(language) {
        SimpleDateFormat(DateFormat.getBestDateTimePattern(language, "EEEEdMMMM"), language)
    }
    val now by produceState(Date()) {
        while (true) {
            delay(60_000 - System.currentTimeMillis() % 60_000)
            value = Date()
        }
    }
    return time.format(now) to date.format(now).replaceFirstChar { it.uppercase() }
}

@Composable
fun ClockTile(driverName: String? = null, weather: WeatherRepository? = null) {
    val c = LocalColors.current
    val (time, date) = rememberClock()
    val hello = greeting(driverName)
    BoxWithConstraints(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.CenterStart) {
        // El reloj crece con la pieza: en 1 fila es discreto, en 2 o más es protagonista.
        val size = (maxHeight.value * 0.5f).coerceIn(28f, 96f)
        // Con la barra oculta (hay reloj), el clima va aquí junto a la hora.
        if (weather != null) Box(Modifier.align(Alignment.TopEnd)) { WeatherChip(weather) }
        Column {
            Label(time, Typography.clock.copy(fontSize = size.sp), maxLines = 1)
            Label(if (hello != null) "$hello · $date" else date, Typography.body, c.textMuted, maxLines = 1)
        }
    }
}

/** Tarjeta de acceso grande (Navegar, Teléfono): ícono en círculo, título y detalle. */
@Composable
fun ShortcutTile(icon: ImageVector, title: String, detail: String?, onClick: () -> Unit) {
    val c = LocalColors.current
    Card(Modifier.fillMaxSize(), onClick = onClick) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(16.dp)) {
            val large = maxHeight > 120.dp
            val small = isSmallScreen()
            val width = maxWidth
            val content = @Composable {
                Box(Modifier.size(if (large) 64.dp else if (small) 52.dp else 44.dp).clip(CircleShape).background(c.surfaceHigh), contentAlignment = Alignment.Center) {
                    Icon(icon, c.accent, if (large) 30.dp else if (small) 26.dp else 22.dp)
                }
            }
            if (large) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                    content()
                    Column {
                        Label(title, Typography.title, maxLines = 1)
                        if (detail != null) Label(detail, Typography.small, c.textMuted, maxLines = 1)
                    }
                }
            } else {
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    content()
                    if (width > 120.dp) {
                        Column(Modifier.padding(start = 14.dp)) {
                            Label(title, Typography.subtitle, maxLines = 1)
                            if (detail != null) Label(detail, Typography.small, c.textMuted, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/** Una app como pieza: ícono grande y nombre. Vacía, invita a elegir cuál. */
@Composable
fun AppTile(repo: AppRepository, app: App?, onClick: () -> Unit) {
    val c = LocalColors.current
    Card(Modifier.fillMaxSize(), onClick = onClick) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.Center) {
            val side = (minOf(maxWidth, maxHeight) * 0.55f).coerceAtMost(96.dp)
            val height = maxHeight
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (app == null) {
                    Icon(Icons.plus, c.textMuted, side * 0.6f)
                    Spacer(Modifier.height(6.dp))
                    Label(stringResource(R.string.pick_app), Typography.small, c.textMuted, maxLines = 1)
                } else {
                    AppIcon(repo, app, side)
                    if (height > 90.dp) {
                        Spacer(Modifier.height(8.dp))
                        Label(app.name, Typography.small.copy(fontWeight = FontWeight.Medium), maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * Navegar. Sin ruta: acceso a la app de mapas. Navegando: la siguiente vuelta
 * que dice el aviso de Waze o Maps (flecha, distancia y calle), sin abrir el mapa.
 */
@Composable
fun NavigationTile(
    repo: NavigationRepository,
    app: App?,
    home: String? = null,
    work: String? = null,
    onPlace: (String) -> Unit = {},
    onClick: () -> Unit,
) {
    DisposableEffect(repo) {
        repo.use { NotificationService.active() }
        onDispose { repo.release() }
    }
    val instruction = repo.instruction
    if (instruction == null && (home != null || work != null)) return PlacesTile(app, home, work, onPlace, onClick)
    if (instruction == null) return ShortcutTile(Icons.navigate, stringResource(R.string.tile_navigate), app?.name, onClick)
    Card(Modifier.fillMaxSize(), onClick = onClick) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(14.dp), contentAlignment = Alignment.CenterStart) {
            Turn(instruction, large = maxHeight > 120.dp)
        }
    }
}

/** Navegar con los atajos a Casa y Trabajo: un toque y el mapa ya va para allá. */
@Composable
private fun PlacesTile(app: App?, home: String?, work: String?, onPlace: (String) -> Unit, onClick: () -> Unit) {
    val c = LocalColors.current
    Card(Modifier.fillMaxSize(), onClick = onClick) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(14.dp), contentAlignment = Alignment.CenterStart) {
            // Abajo si la pieza es alta; si no, a la derecha (en angosto sin el nombre de la app).
            val stacked = maxHeight > 150.dp
            val narrow = maxWidth < 360.dp
            val places = @Composable {
                if (home != null) Button(stringResource(R.string.place_home), { onPlace(home) })
                if (work != null) Button(stringResource(R.string.place_work), { onPlace(work) })
            }
            val title = @Composable {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(c.surfaceHigh), contentAlignment = Alignment.Center) {
                        Icon(Icons.navigate, c.accent, 22.dp)
                    }
                    // En angosto basta el ícono: los botones dicen a dónde.
                    if (stacked || !narrow) Column(Modifier.padding(start = 12.dp)) {
                        Label(stringResource(R.string.tile_navigate), Typography.subtitle, maxLines = 1)
                        if (app != null) Label(app.name, Typography.small, c.textMuted, maxLines = 1)
                    }
                }
            }
            if (stacked) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    title()
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { places() }
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { title() }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { places() }
                }
            }
        }
    }
}

/** Flecha ámbar, distancia y calle de la siguiente vuelta. */
@Composable
private fun Turn(instruction: TurnInstruction, large: Boolean) {
    val c = LocalColors.current
    val side = if (large) 72.dp else 48.dp
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(side).clip(RoundedCornerShape(Radius.card)).background(c.accent), contentAlignment = Alignment.Center) {
            val arrow = instruction.arrow
            if (arrow != null) {
                Image(arrow.asImageBitmap(), null, Modifier.size(side * 0.7f), colorFilter = ColorFilter.tint(c.background))
            } else {
                Icon(Icons.navigate, c.background, side * 0.5f)
            }
        }
        Column(Modifier.padding(start = 14.dp)) {
            if (instruction.title.isNotBlank()) Label(instruction.title, if (large) Typography.title.copy(fontSize = 28.sp) else Typography.title, maxLines = 1)
            if (instruction.text.isNotBlank()) Label(instruction.text, Typography.body, maxLines = if (large) 2 else 1)
            if (large && instruction.detail.isNotBlank()) Label(instruction.detail, Typography.small, c.textMuted, maxLines = 1)
        }
    }
}

fun openDialer(context: Context) {
    val intent = Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/** Navegación directa a [address]: Waze o Maps si son la app elegida; si no, la que atienda "geo:". */
fun navigateTo(context: Context, app: App?, address: String) {
    val q = Uri.encode(address)
    val intent = when (app?.pkg) {
        "com.waze" -> Intent(Intent.ACTION_VIEW, "https://waze.com/ul?q=$q&navigate=yes".toUri()).setPackage("com.waze")
        "com.google.android.apps.maps" -> Intent(Intent.ACTION_VIEW, "google.navigation:q=$q".toUri()).setPackage(app.pkg)
        else -> Intent(Intent.ACTION_VIEW, "geo:0,0?q=$q".toUri()).apply { app?.let { setPackage(it.pkg) } }
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        // La app elegida no entendió la dirección: que la abra cualquiera que sepa de mapas.
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, "geo:0,0?q=$q".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/** Abre la navegación: la app elegida, o la que haya instalada, o lo que atienda "geo:". */
fun openNavigation(context: Context, repo: AppRepository, app: App?) {
    if (app != null) return repo.open(context, app)
    val intent = Intent(Intent.ACTION_VIEW, "geo:0,0?q=".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/** Agrega una pieza de [type] con su tamaño inicial en el primer hueco; false si ya no cabe. */
fun DashboardState.addTile(type: String, data: String = ""): Boolean {
    val spec = TileSpecs.first { it.type == type }
    return add(spec.type, spec.initialSize, spec.limits, data)
}
