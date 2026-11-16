package com.irvingguerra.litedash.feature.music

import android.media.session.PlaybackState
import com.irvingguerra.litedash.feature.music.MusicRepository.Lens

/**
 * Cambia el método. Con [silence] (lo de siempre) solo suena el nuevo: los demás se callan y, al dejar
 * Archivos, el reproductor propio se detiene (en pausa su sesión seguiría recibiendo el play del sistema y
 * sonaría la USB). Sin [silence] solo cambia qué muestra la pieza: es para devolver el método prestado.
 */
internal fun MusicRepository.switchMode(v: MusicMode, silence: Boolean = true) {
    if (v == modeState) return
    if (silence) {
        stopOthers(pkgFor(v))
        if (modeState == MusicMode.FILES && ownSourceNow?.invoke() == SourceKind.USB) ownStop?.invoke()
    }
    modeState = v
    store.edit().putString(KEY_MODE, v.name).apply()
    // Al pasar a Bluetooth con el celular sonando, su música empieza aquí.
    if (v == MusicMode.BLUETOOTH) { if (phoneState.onBluetooth) phoneMusicStarted() } else releaseBluetoothRecipe()
    // Lo que se supo del método anterior (avisos, supuestos) ya no vale.
    music.clear()
    if (users > 0) choose(sessions())
}

/**
 * El método guardado. Si LiteDash se cerró con el celular conectado, lo guardado es el Bluetooth
 * prestado: se regresa el de antes (al reconectarse el celular, se vuelve a prestar).
 */
internal fun MusicRepository.savedMode(): MusicMode {
    fun read(key: String) = store.getString(key, null)?.let { m -> MusicMode.entries.firstOrNull { it.name == m } }
    val held = read(KEY_MODE_HELD)
    if (held != null) store.edit().putString(KEY_MODE, held.name).remove(KEY_MODE_HELD).apply()
    return held ?: read(KEY_MODE) ?: MusicMode.APP
}

internal fun MusicRepository.releaseHold() {
    mainHandler.removeCallbacks(phoneLeft)
    if (heldMode == null) return
    heldMode = null
    store.edit().remove(KEY_MODE_HELD).apply()
}

/** La música del celular empezó a sonar por Bluetooth sin un play de LiteDash: la radio se calla si suena, y su receta. */
internal fun MusicRepository.phoneMusicStarted() {
    quietRadio(bluetoothApp())
    calibrateBluetooth()
}

/** La app que toca [m]: LiteDash, la del Bluetooth o tu app de música (null = la que suene). */
internal fun MusicRepository.pkgFor(m: MusicMode): String? = when (m) {
    MusicMode.FILES -> ownPackage
    MusicMode.BLUETOOTH -> bluetoothApp()
    MusicMode.APP -> preferred
}

/**
 * Deja una sola fuente sonando: calla la USB propia (salvo [keep]) y toda sesión de la pieza
 * Música que esté sonando (salvo [keep] y la radio, que vive en su propia pieza). La música
 * "a ciegas" (Bluetooth/FM sin sesión que LiteDash da por sonando) se pausa como el botón ([pause]).
 */
internal fun MusicRepository.stopOthers(keep: String?) {
    if (keep != ownPackage) ownPause?.invoke()
    for (c in sessions()) {
        val p = c.packageName
        // Android Auto / CarPlay de la pantalla (ZLink…) llevan su propio audio: nunca se les pausa.
        if (p == keep || isRadio(p) || isProjection?.invoke(p) == true) continue
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) {
            runCatching { c.transportControls.pause() }.onFailure { trace("pausa a $p falló (${it.javaClass.simpleName})") }
        }
    }
    val blind = music.song?.takeIf { it.blind && it.playing && it.pkg.isNotBlank() }
    if (blind != null && blind.pkg != keep) pause(music)
}

/** La canción del celular, si toca mostrarla en [lens]. */
internal fun MusicRepository.phoneFor(lens: Lens): Song? {
    val s = phoneSong?.takeIf { lens === music } ?: return null
    return when (mode) {
        MusicMode.FILES -> null
        // El celular en pausa no le gana a otra música por Bluetooth que sí suena (otro celular).
        MusicMode.BLUETOOTH -> s.takeIf { it.playing || lens.controller?.playbackState?.state != PlaybackState.STATE_PLAYING }
        MusicMode.APP -> {
            val app = modeApp
            if (app != null && app != bluetoothApp()) return null
            // Algo de la pantalla suena (que no sea el Bluetooth): eso es lo que se escucha.
            val local = lens.controller?.takeIf { it.packageName != avrcpPackage && it.packageName != bluetoothApp() }
            s.takeIf { local?.playbackState?.state != PlaybackState.STATE_PLAYING && (s.playing || local == null) }
        }
    }
}
