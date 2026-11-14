package com.irvingguerra.litedash.core.vehicle

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * La red local (el celular con LiteDash Connect, un OBD por Wi‑Fi). Desde Android 17, a las apps con targetSdk 37
 * Android se la bloquea hasta que el usuario da este permiso (grupo "Dispositivos cercanos"); antes no existe y no se pide.
 * Nunca se pide solo: un intento sin permiso hace que Android abra su selector y, al volver la app al frente, se repetiría.
 */
const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

/** Desde esta versión de Android la red local pide permiso. */
private const val LOCAL_NETWORK_SDK = 37

/** Se puede usar la red local ahora. */
fun localNetworkAllowed(context: Context): Boolean =
    Build.VERSION.SDK_INT < LOCAL_NETWORK_SDK || context.checkSelfPermission(LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED
