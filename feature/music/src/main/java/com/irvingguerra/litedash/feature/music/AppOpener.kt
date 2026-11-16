package com.irvingguerra.litedash.feature.music

import android.content.Intent

/** Cómo quedó una app al abrirla. */
internal enum class Opened { NONE, BESIDE, OVER }

// —— Abrir apps y regresar ——

/**
 * La única forma de abrir una app, encima o a un lado: si es la del Bluetooth, su pantalla de música ([launchIntent]).
 * Es la del Bluetooth si es la fuente Bluetooth, una de marca conocida o una del sistema que lo parece por su paquete
 * (las de la pantalla que aún no se conocen; una app del usuario con "bt" en el paquete no cuenta).
 */
internal fun MusicRepository.open(pkg: String): Opened {
    val bluetooth = sourceOf(pkg) == SourceKind.BLUETOOTH || pkg in HeadUnit.bluetoothApps || looksBluetooth(pkg) && isSystemApp(context, pkg)
    val intent = launchIntentFor(pkg, bluetooth)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return Opened.NONE
    if (opener?.invoke(context, intent, pkg) == true) return Opened.BESIDE
    return if (runCatching { context.startActivity(intent) }.onFailure {
            trace("no se abrió $pkg (${it.javaClass.simpleName})")
            // La app cambió (se actualizó): la próxima vez se pregunta de nuevo cómo abrirla.
            launches.remove("$pkg|$bluetooth")
        }.isSuccess) Opened.OVER else Opened.NONE
}

/**
 * Abre [pkg] encima de LiteDash y, si quedó encima, regresa a los [afterMs] y hace [then]. Un solo regreso pendiente:
 * el nuevo reemplaza al anterior, y se cancela si LiteDash vuelve al frente por su cuenta ([cancelReturn]). True = quedó
 * encima (a un lado, en pantalla dividida, no se regresa).
 */
internal fun MusicRepository.openOver(pkg: String, afterMs: Long, then: () -> Unit): Boolean {
    val over = open(pkg) == Opened.OVER
    mainHandler.removeCallbacks(pendingReturn)
    if (over) {
        pendingReturn = Runnable {
            trace("regreso a LiteDash: audio=${yes(musicActive())}")
            returnHome?.invoke()
            then()
        }
        mainHandler.postDelayed(pendingReturn, afterMs)
    }
    return over
}

internal fun MusicRepository.launchIntentFor(pkg: String, bluetooth: Boolean): Intent? {
    val key = "$pkg|$bluetooth"
    // "Sin pantalla que abrir" no se guarda: la app puede instalarse después.
    val cached = launches[key] ?: launchIntent(context, pkg, bluetooth)?.also { launches[key] = it }
    // Copia: quien la usa le agrega banderas.
    return cached?.let(::Intent)
}
