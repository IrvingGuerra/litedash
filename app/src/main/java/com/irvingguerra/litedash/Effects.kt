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
 * adaptador guardado y el módulo prendido se conecta solo (también si se prende después);
 * devuelve el escáner si ya existe. Sin [wanted] o con el módulo apagado devuelve null y no
 * crea ni conecta nada.
 */
@Composable
fun rememberObd(d: Dependencies, wanted: Boolean = true): ObdRepository? {
    val auto = wanted && d.obdAuto
    LaunchedEffect(auto) { if (auto) d.obd.connect() }
    return if (wanted && d.obdModule) d.obdIfCreated() else null
}

/**
 * El permiso de ubicación precisa del GPS: si ya se tiene y la función que lo pide
 * (abre el diálogo de Android; al responder se actualiza el primero). Con [revision] (cambia
 * al volver a la app) también se nota el que se dio o quitó en Ajustes de Android.
 */
@Composable
fun rememberGpsPermission(d: Dependencies, revision: Int = 0): Pair<Boolean, () -> Unit> {
    var granted by remember(revision) { mutableStateOf(d.trip.hasPermission()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val ask = remember(launcher) { { launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION) } }
    return granted to ask
}
