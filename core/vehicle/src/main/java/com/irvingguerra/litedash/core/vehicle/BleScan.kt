package com.irvingguerra.litedash.core.vehicle

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build

// Buscar por Bluetooth LE (OBD, llantas y el celular con Connect): un solo criterio para todos.

/** El permiso que se pide para buscar: "dispositivos cercanos" desde Android 12; antes, la ubicación. */
fun bleScanPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION

/** Ya se puede buscar (hasta Android 9 también basta la ubicación aproximada). */
fun canScanBle(context: Context): Boolean {
    fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    return granted(bleScanPermission()) ||
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && granted(Manifest.permission.ACCESS_COARSE_LOCATION)
}

/** Hasta Android 11, con la ubicación del sistema apagada la búsqueda corre pero no ve nada. */
fun bleLocationOff(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !locationOn(context)

/** Ubicación del sistema encendida; si no se puede saber, se toma como encendida (no se bloquea a ciegas). */
private fun locationOn(context: Context): Boolean {
    val m = context.getSystemService(LocationManager::class.java) ?: return true
    return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) m.isLocationEnabled
        else m.isProviderEnabled(LocationManager.GPS_PROVIDER) || m.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }.getOrDefault(true)
}
