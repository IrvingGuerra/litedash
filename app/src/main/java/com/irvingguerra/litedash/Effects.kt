package com.irvingguerra.litedash

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue

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
 * Con un adaptador OBD ya guardado, se reconecta solo al entrar la pieza (sin abrir el
 * Taller). Sin adaptador no hace nada: el escáner ni se crea.
 */
@Composable
fun AutoConnectObd(d: Dependencies) {
    LaunchedEffect(Unit) { if (d.hasObdAdapter) d.obd.connect() }
}

/**
 * El permiso de ubicación precisa del GPS: si ya se tiene y la función que lo pide
 * (abre el diálogo de Android; al responder se actualiza el primero).
 */
@Composable
fun rememberGpsPermission(d: Dependencies): Pair<Boolean, () -> Unit> {
    var granted by remember { mutableStateOf(d.trip.hasPermission()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val ask = remember(launcher) { { launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION) } }
    return granted to ask
}
