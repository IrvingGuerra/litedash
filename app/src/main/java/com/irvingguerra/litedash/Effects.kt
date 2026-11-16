package com.irvingguerra.litedash

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleStartEffect
import com.irvingguerra.litedash.feature.obd.ObdRepository

/**
 * Para las piezas que no tienen nada que cargar: avisa [onReady] una sola vez, en
 * cuanto entran a la pantalla (null = nadie espera). Si [onReady] cambia antes de
 * correr, se llama el más nuevo.
 */
@Composable
fun ReadyNow(onReady: (() -> Unit)?) {
    val ready by rememberUpdatedState(onReady)
    LaunchedEffect(Unit) { ready?.invoke() }
}

/**
 * El escáner OBD para una pieza o un aviso que lo quiere ([wanted]: su ajuste prendido). Con
 * adaptador guardado y el módulo prendido dice "lo quiero" mientras está en pantalla (el escáner
 * se conecta solo y, cuando ya nadie lo quiere, se desconecta al rato); devuelve el escáner si ya existe.
 * Sin [wanted] o con el módulo apagado devuelve null y no crea ni conecta nada.
 */
@Composable
fun rememberObd(d: Dependencies, wanted: Boolean = true): ObdRepository? {
    val auto = wanted && d.obdAuto
    DisposableEffect(auto) {
        val owner = Any()
        if (auto) d.obd.want(owner)
        onDispose { if (auto) d.obdIfCreated()?.release(owner) }
    }
    // Al volver a LiteDash (no al aparecer la pieza: el primer inicio no cuenta), si esperaba un reintento, intenta ya.
    val started = remember { booleanArrayOf(false) }
    LifecycleStartEffect(auto) {
        if (auto && started[0]) d.obdIfCreated()?.wake()
        started[0] = true
        onStopOrDispose { }
    }
    return if (wanted && d.obdModule) d.obdIfCreated() else null
}

/**
 * El permiso de ubicación del GPS: si ya se tiene y la función que lo pide (abre el diálogo de
 * Android). Se piden la precisa y la aproximada juntas: en Android 12+ el usuario puede elegir
 * "Aproximada" y también vale. Es uno solo para todos ([TripRepository.permitted]): al darlo desde
 * un widget, los demás se enteran. Con [revision] (cambia al volver a la app) también se nota el
 * que se dio o quitó en Ajustes de Android.
 */
@Composable
fun rememberGpsPermission(d: Dependencies, revision: Int = 0): Pair<Boolean, () -> Unit> {
    LaunchedEffect(revision) { d.trip.refreshPermission() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { d.trip.refreshPermission() }
    val ask = remember(launcher) { { launcher.launch(LOCATION_PERMISSIONS) } }
    return d.trip.permitted to ask
}

private val LOCATION_PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

/**
 * Cuántas veces ha vuelto la app al frente (sube en cada ON_START): como llave, recalcula lo
 * que se quedó viejo mientras la pantalla dormía (los `delay` no corren con el equipo dormido).
 */
@Composable
fun rememberStarts(): Int {
    var starts by remember { mutableIntStateOf(0) }
    LifecycleStartEffect(Unit) {
        starts++
        onStopOrDispose { }
    }
    return starts
}
