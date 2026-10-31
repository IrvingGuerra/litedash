package com.irvingguerra.litedash.feature.mapa

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.irvingguerra.litedash.core.ui.Boton
import com.irvingguerra.litedash.core.ui.Etiqueta
import com.irvingguerra.litedash.core.ui.Icono
import com.irvingguerra.litedash.core.ui.Iconos
import com.irvingguerra.litedash.core.ui.LocalColores
import com.irvingguerra.litedash.core.ui.Tarjeta
import com.irvingguerra.litedash.core.ui.Texto
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

// Mapas vectoriales gratis y sin llave (datos de OpenStreetMap).
private const val ESTILO_OSCURO = "https://tiles.openfreemap.org/styles/dark"
private const val ESTILO_CLARO = "https://tiles.openfreemap.org/styles/positron"
private const val ZOOM = 15.5

private val PERMISOS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

private fun tienePermiso(c: Context) = PERMISOS.any { ContextCompat.checkSelfPermission(c, it) == PackageManager.PERMISSION_GRANTED }

/**
 * Pieza de mapa: un mapa propio, centrado en tu auto, que se dibuja dentro de
 * LiteDash. Por ahora es un indicador: tocarlo ([alTocar]) abre la app de
 * navegación. [encima] pinta sobre el mapa (la etiqueta de la app o la
 * siguiente vuelta). Solo existe mientras la pieza está en pantalla: sin ella,
 * ni el mapa ni el GPS se cargan.
 */
@Composable
fun PiezaMapa(alTocar: () -> Unit, modifier: Modifier = Modifier, encima: @Composable BoxScope.() -> Unit = {}) {
    val contexto = LocalContext.current
    var permiso by remember { mutableStateOf(tienePermiso(contexto)) }
    val pedir = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permiso = tienePermiso(contexto)
    }
    Tarjeta(modifier.fillMaxSize()) {
        if (!permiso) {
            SinUbicacion { pedir.launch(PERMISOS) }
        } else {
            val ubicacion = rememberUbicacion()
            if (ubicacion != null) MapaVivo(ubicacion) else Buscando()
            Bordes()
            if (ubicacion != null) Auto(Modifier.align(Alignment.Center))
            // Capa que recibe el toque: el mapa en sí no se mueve con el dedo (es un indicador).
            Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null, onClick = alTocar))
            encima()
            Etiqueta(
                "© OpenStreetMap",
                Texto.chico.copy(fontSize = 9.sp),
                LocalColores.current.textoSuave.copy(alpha = 0.7f),
                maxLineas = 1,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 6.dp),
            )
        }
    }
}

@Composable
private fun SinUbicacion(alActivar: () -> Unit) {
    val c = LocalColores.current
    // Toda la tarjeta activa; el texto y el botón aparecen si caben.
    BoxWithConstraints(Modifier.fillMaxSize().clickable(onClick = alActivar).padding(18.dp), contentAlignment = Alignment.CenterStart) {
        val alta = maxHeight > 200.dp
        Column {
            Icono(Iconos.mapa, c.acento, 30.dp)
            Etiqueta(stringResource(R.string.mapa_activar), Texto.subtitulo, maxLineas = 2, modifier = Modifier.padding(top = 10.dp))
            if (alta) Etiqueta(stringResource(R.string.mapa_activar_detalle), Texto.chico, c.textoSuave, maxLineas = 3, modifier = Modifier.padding(top = 4.dp))
            Boton(stringResource(R.string.activar_ubicacion), alActivar, Modifier.padding(top = 12.dp), principal = true)
        }
    }
}

@Composable
private fun BoxScope.Buscando() {
    val c = LocalColores.current
    Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
        Icono(Iconos.ubicacion, c.textoSuave, 22.dp)
        Etiqueta(stringResource(R.string.buscando_ubicacion), Texto.chico, c.textoSuave, maxLineas = 1, modifier = Modifier.padding(start = 8.dp))
    }
}

/** Tu auto: punto ámbar con halo, como el acento de la marca. */
@Composable
private fun Auto(modifier: Modifier) {
    val c = LocalColores.current
    Box(modifier.size(44.dp).clip(CircleShape).background(c.acento.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
        Box(Modifier.size(20.dp).shadow(4.dp, CircleShape).clip(CircleShape).background(c.acento).border(3.dp, Color.White, CircleShape))
    }
}

/** Orillas que se funden con la tarjeta: el mapa se ve integrado, no pegado. */
@Composable
private fun BoxScope.Bordes() {
    val fondo = LocalColores.current.superficie
    Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to fondo.copy(alpha = 0.55f), 0.22f to Color.Transparent, 0.78f to Color.Transparent, 1f to fondo.copy(alpha = 0.6f))))
}

@Composable
private fun MapaVivo(ubicacion: Location) {
    val contexto = LocalContext.current
    val oscuro = LocalColores.current.oscuro
    val ciclo = LocalLifecycleOwner.current.lifecycle
    var mapa by remember { mutableStateOf<MapLibreMap?>(null) }
    val vista = remember {
        MapLibre.getInstance(contexto.applicationContext)
        // TextureView: así el mapa respeta las esquinas redondas de la tarjeta.
        val opciones = MapLibreMapOptions.createFromAttributes(contexto)
            .textureMode(true)
            .attributionEnabled(false)
            .logoEnabled(false)
            .compassEnabled(false)
        MapView(contexto, opciones).apply {
            onCreate(null)
            getMapAsync { m ->
                m.uiSettings.setAllGesturesEnabled(false)
                m.cameraPosition = CameraPosition.Builder().target(LatLng(ubicacion.latitude, ubicacion.longitude)).zoom(ZOOM).build()
                mapa = m
            }
        }
    }
    DisposableEffect(ciclo, vista) {
        val observador = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> vista.onStart()
                Lifecycle.Event.ON_RESUME -> vista.onResume()
                Lifecycle.Event.ON_PAUSE -> vista.onPause()
                Lifecycle.Event.ON_STOP -> vista.onStop()
                else -> Unit
            }
        }
        ciclo.addObserver(observador)
        onDispose {
            ciclo.removeObserver(observador)
            vista.onStop()
            vista.onDestroy()
        }
    }
    LaunchedEffect(mapa, oscuro) {
        mapa?.setStyle(Style.Builder().fromUri(if (oscuro) ESTILO_OSCURO else ESTILO_CLARO))
    }
    LaunchedEffect(mapa, ubicacion) {
        val m = mapa ?: return@LaunchedEffect
        // En movimiento, el mapa gira hacia donde vas; parado, el norte arriba.
        val rumbo = if (ubicacion.hasBearing() && ubicacion.speed > 2f) ubicacion.bearing.toDouble() else m.cameraPosition.bearing
        val camara = CameraPosition.Builder().target(LatLng(ubicacion.latitude, ubicacion.longitude)).zoom(ZOOM).bearing(rumbo).build()
        m.easeCamera(CameraUpdateFactory.newCameraPosition(camara), 900)
    }
    AndroidView({ vista }, Modifier.fillMaxSize())
}

/**
 * La ubicación mientras la pieza está en pantalla: la última conocida al empezar
 * y luego cada 3 s o 10 m (lo que el sistema avise; nada de preguntar en bucle).
 */
@SuppressLint("MissingPermission")
@Composable
private fun rememberUbicacion(): Location? {
    val contexto = LocalContext.current
    var ubicacion by remember { mutableStateOf<Location?>(null) }
    DisposableEffect(Unit) {
        val gestor = contexto.getSystemService(LocationManager::class.java)
        val proveedores = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { gestor.isProviderEnabled(it) }.getOrDefault(false) }
        ubicacion = proveedores.mapNotNull { runCatching { gestor.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        val oyente = LocationListener { l ->
            // El GPS manda sobre la red cuando ya hay señal.
            val actual = ubicacion
            if (actual == null || l.provider == LocationManager.GPS_PROVIDER || actual.provider != LocationManager.GPS_PROVIDER) {
                ubicacion = l
            }
        }
        for (p in proveedores) runCatching { gestor.requestLocationUpdates(p, 3000L, 10f, oyente, Looper.getMainLooper()) }
        onDispose { gestor.removeUpdates(oyente) }
    }
    return ubicacion
}
