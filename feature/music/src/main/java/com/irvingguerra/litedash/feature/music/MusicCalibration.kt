package com.irvingguerra.litedash.feature.music

import android.media.session.PlaybackState
import android.os.SystemClock

/** Un toque al aviso «Controla la música desde tu celular» cuenta para la calibración que empieza en este rato. */
private const val ASKED_MS = 2_000L
/** Pospuesta con el aviso «Controla la música desde tu celular» a la vista: cada cuánto se vuelve a pedir y cuántas veces. */
private const val POSTPONED_RETRY_MS = 20_000L
private const val POSTPONED_RETRIES = 9

internal fun MusicRepository.showCheck(piece: SoundPiece, check: SoundCheck?) {
    if (check != null || soundCheck?.piece == piece) soundCheck = check
}

/** La música Bluetooth empezó a sonar (un play de LiteDash, el celular por Connect o AVRCP): su receta y la revisión. */
internal fun MusicRepository.calibrateBluetooth() {
    if (!bluetoothCalibrates) return
    // De quién es esta calibración: la que ya va conserva lo suyo (sus pasos llegan después del toque).
    if (!btCalibration.pending) calibrationAsked = SystemClock.elapsedRealtime() - askedAt < ASKED_MS
    // El celular ya suena por el Bluetooth de Android y Android tiene el audio: no hay nada que calibrar
    // (reporte E3F104: "plain no suena" con la sesión de com.android.bluetooth sonando).
    if (avrcpController?.playbackState?.state == PlaybackState.STATE_PLAYING && musicActive()) return trace("calibración bt: ya suena por Android")
    val available = headUnit.bluetoothRecipes.filter { btRecipes[it]?.available() == true }
    if (btCalibration.play(available, catalog?.bluetooth)) return
    // Nada funcionó antes: la receta de la marca de todos modos (lo que se hacía antes de calibrar);
    // sin receta de marca, nada que probar.
    if (btStore.saved?.id == Recipes.FAILED && !btCalibration.pending) {
        headUnit.bluetoothRecipes.firstOrNull { it in Recipes.BRAND && btRecipes[it]?.available() == true }?.let(::useBluetooth)
    }
}

/**
 * La pieza empieza o deja de decir «Controla la música desde tu celular» ([MusicRepository.phoneOnly]): la app de
 * Bluetooth de marca no tiene por dónde recibir play y no hay Connect (TopWay `com.tw.bt`, reporte E48170). Sin ▶,
 * nada más pediría la entrada de audio al MCU: al entrar se aplica la receta UNA vez (no en cada repintado; otra vez
 * solo al salir y volver a entrar, al sonar la radio ([pause] de la radio) o si la persona toca el aviso
 * ([askBluetoothSound])). Si la radio de la pantalla suena, no se le quita la salida.
 */
internal fun MusicRepository.phoneOnlyShown(on: Boolean) {
    mainHandler.removeCallbacks(phoneOnlyRetry)
    if (!on) {
        phoneOnlyAsked = false
        return
    }
    if (phoneOnlyAsked) return
    phoneOnlyAsked = true
    phoneOnlyRetries = 0
    if (radio.song?.playing == true || factoryFor(radio)?.on == true) return trace("bluetooth sin canal: la radio suena, no se pide la entrada")
    trace("bluetooth sin canal: se pide la entrada de audio")
    calibrateBluetooth()
    if (btCalibration.postponed) mainHandler.postDelayed(phoneOnlyRetry, POSTPONED_RETRY_MS)
}

/**
 * La receta quedó pospuesta (el auto se movía o no había dato de velocidad) y el aviso sigue a la vista: nadie más la
 * pediría (no hay ▶). Se vuelve a pedir cada [POSTPONED_RETRY_MS], unas cuantas veces: al detenerse el auto, pasa.
 */
internal fun MusicRepository.retryPostponed() {
    if (!phoneOnly || !phoneOnlyAsked || !btCalibration.postponed || !retriesPostponed(speedUnknown(), calibrationAsked)) return
    if (phoneOnlyRetries++ >= POSTPONED_RETRIES) return
    calibrateBluetooth()
    if (btCalibration.postponed) mainHandler.postDelayed(phoneOnlyRetry, POSTPONED_RETRY_MS)
}

/** Tocar el aviso «Controla la música desde tu celular»: vuelve a pedir la entrada de audio aunque ya se hubiera pedido. */
fun MusicRepository.askBluetoothSound() {
    trace("bluetooth sin canal: la persona pide la entrada de audio")
    askedAt = SystemClock.elapsedRealtime()
    phoneOnlyAsked = true
    btApplied?.renew()
    calibrateBluetooth()
}

/** Al dejar el método Bluetooth o irse el celular: se suelta la receta (y lo pendiente se deja). */
internal fun MusicRepository.releaseBluetoothRecipe() {
    mainHandler.removeCallbacks(phoneOnlyRetry)
    btCalibration.abort()
    useBluetooth(null)
    // Lo que se pidió ya no está: al volver al aviso se pide otra vez.
    phoneOnlyAsked = false
}

internal fun MusicRepository.useBluetooth(id: String?): Long? {
    val recipe = id?.let(btRecipes::get)
    if (btApplied !== recipe) btApplied?.release()
    btApplied = recipe
    return recipe?.apply()
}

/**
 * ¿Suena el Bluetooth de verdad? ([bluetoothSounds]). Sin la prueba de que el celular suena, un celular apagado
 * llevaría a guardar [Recipes.FAILED] con un "No" que no era culpa de la pantalla.
 */
internal fun MusicRepository.bluetoothHeard(): Boolean? {
    val song = music.song
    val playing = phoneState.onBluetooth && modeState == MusicMode.BLUETOOTH ||
        avrcpController?.playbackState?.state == PlaybackState.STATE_PLAYING ||
        song?.playing == true && !song.blind
    if (!playing) return null
    return bluetoothAudible()
}

/** Android tiene audio y no es de una app fuera del grupo del Bluetooth ([bluetoothSounds]). */
internal fun MusicRepository.bluetoothAudible(): Boolean =
    bluetoothSounds(true, musicActive(), othersPlaying(bluetoothGroup(avrcpPackage, knownBluetooth, sourceApps[SourceKind.BLUETOOTH]))) == true

/**
 * Hay cómo saber si el celular suena: está con LiteDash Connect, la pantalla trae el Bluetooth de Android (AVRCP) o su
 * app de marca muestra la canción de verdad. Sin nada de eso (TopWay con chip propio, reporte E48170) no se sabe nunca.
 */
internal fun MusicRepository.bluetoothKnowable(): Boolean =
    phoneState.here || avrcpController != null || music.song?.let { !it.blind } == true

/** La radio de la pantalla tomó la salida de audio: la receta del Bluetooth se vuelve a pedir la próxima vez. */
internal fun MusicRepository.bluetoothSourceTaken() = btApplied?.sourceTaken()

internal fun MusicRepository.loadCatalog() {
    if (catalogAsked) return
    fun needs(piece: SoundPiece, users: Int, saved: SavedRecipe?) = users > 0 && saved == null && calibrates(piece)
    if (!needs(SoundPiece.MUSIC, musicUsers, btStore.saved) && !needs(SoundPiece.RADIO, radioUsers, fmStore.saved)) return
    catalogAsked = true
    Thread({
        val found = loadRecipeCatalog(context)
        val retry = found == null && !recipeCatalogCached(context)
        mainHandler.post {
            if (retry) catalogAsked = false
            catalog = found
            trace("catálogo de recetas: ${found?.let { "bt=${it.bluetooth ?: "-"} fm=${it.radio ?: "-"}" } ?: "nada para esta pantalla"}")
        }
    }, "audio-recipes").start()
}
