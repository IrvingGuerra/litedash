package com.irvingguerra.litedash.feature.music

import android.os.SystemClock
import android.view.KeyEvent
import com.irvingguerra.litedash.feature.music.MusicRepository.Lens
import com.irvingguerra.litedash.feature.music.MusicRepository.FmPlay

/** Tras abrir la radio de fábrica, cuándo se revisa si Android tiene audio (el regreso es a los [FACTORY_RETURN_MS]). */
private const val RADIO_VERIFY_MS = 2 * FACTORY_RETURN_MS
/** Lo que tarda el sintonizador de fábrica en contestar (la estación o que ya tiene el audio). */
internal const val FACTORY_ANSWER_MS = 2_000L

/**
 * Cambió con qué suena la pieza Radio (se enlazó o quitó la FM, o se pasó entre la FM y la de internet): lo que se supo
 * de la anterior (su estación, el play que se le mandó o se le confirmaba) ya no vale, y su perfil solo se abre si la FM
 * es la radio escogida y hay una pieza de sonido a la vista.
 */
internal fun MusicRepository.radioChanged() {
    mainHandler.removeCallbacks(profileAnswer)
    fmCalibration.abort()
    radioClaimedAt = null
    radio.clear()
    if (users > 0) startFactory()
}

/**
 * La primera pieza de sonido entró (Música o Radio): el perfil de la radio de fábrica y, la primera vez, adivinar su app.
 * También con solo la pieza Música: sin el perfil no se sabe si la FM suena, y entonces ni el volante la pausa ni un
 * play de música la calla (issue #17).
 */
internal fun MusicRepository.startRadio() {
    startFactory()
    if (!store.getBoolean(KEY_SOURCES_GUESSED, false)) {
        store.edit().putBoolean(KEY_SOURCES_GUESSED, true).apply()
        Thread(::guessSources, "music-sources").start()
    }
}

/** La última pieza de sonido salió: su perfil se cierra (se queda, con la última estación, para el reporte). */
internal fun MusicRepository.stopRadio() {
    factoryRun++
    factory?.close()
}

/** Se arma en otro hilo (reflexión, el driver y el disco) y se abre en el principal. Solo si la FM es la radio escogida. */
internal fun MusicRepository.startFactory() {
    factory?.close()
    factory = null
    val run = ++factoryRun
    val pkg = radioApp?.takeUnless(::isOwn) ?: return
    factoryThread.execute {
        if (run != factoryRun) return@execute
        val p = profileFor(pkg) ?: return@execute
        mainHandler.post { if (run == factoryRun && p.open()) factory = p else p.close() }
    }
}

/** El perfil de la marca de [pkg], armado pero sin abrir; null si ninguna marca la reconoce. En el hilo de los perfiles. */
internal fun MusicRepository.profileFor(pkg: String): FactoryRadio? {
    val kind = radioKind(pkg) ?: return null
    return kind.create(context, store, { radio.heard(it.copy(pkg = pkg)) }, { e -> mainHandler.post { trace(e) } })
}

/** La marca de la FM [pkg]; las que solo se usan sin receptor ni servicio lo revisan aquí (sin caché: puede ir en otro hilo). */
internal fun MusicRepository.radioKind(pkg: String): FactoryRadioKind? {
    val silent by lazy { isSilent(pkg) }
    return HeadUnit.factoryRadios.firstOrNull { it.matches(pkg) && (!it.needsSilent || silent) }
}

/** Sin receptor del botón de play ni servicio de música: no hay cómo hablarle (sin caché: puede ir en otro hilo). */
internal fun MusicRepository.isSilent(pkg: String) = mediaButtonReceivers(pkg).isEmpty() && browserServices(context, pkg).isEmpty()

/** La radio de la pieza se controla por el perfil de su marca. */
internal fun MusicRepository.factoryFor(lens: Lens): FactoryRadio? = factory?.takeIf { lens === radio && it.open }

// —— Callar la radio de fábrica (issue #17): solo si se SABE que suena ——

/**
 * La música de [target] va a sonar: la FM de fábrica se calla (su audio va por fuera de Android y tapaba la música,
 * issue #17), pero SOLO si se sabe que suena ([radioSounds]: su perfil dice que sí, su sesión o su aviso dicen que
 * suena, o LiteDash la prendió y nadie la pausó). Sin saberlo no se manda nada: en radios que toman cualquier tecla
 * como alternar, una pausa a ciegas la prendería. Por dónde, [quietBy]; nunca al sistema ni despertando una app.
 * Lo llaman los tres momentos en que la música empieza: [play], el reproductor propio ([ownMusicStarted]) y el celular
 * ([phoneMusicStarted]); también al pasar la radio a la de internet.
 */
internal fun MusicRepository.quietRadio(target: String?) {
    val fm = sourceApps[SourceKind.RADIO]
    if (fm == null || !quietsFor(fm, radioInternet, target)) return
    val now = SystemClock.elapsedRealtime()
    val f = factoryFor(radio)
    val session = sessions().firstOrNull { it.packageName == fm }
    val signals = RadioSignals(
        profileOn = f?.on,
        profileOpen = f != null,
        // Una sesión que no dice cuándo cambió cuenta como de ahora (su estado es lo que hay).
        sessionAt = session?.takeIf { it.busy() }?.let { it.playbackState?.lastPositionUpdateTime?.takeIf { t -> t > 0 } ?: now },
        heardAt = radio.heardPlayingAt(fm),
        claimedAt = radioClaimedAt?.takeIf { now - it < ASSUME_MAX_MS },
    )
    if (!radioSounds(signals, radioQuietAt)) return trace("radio sin señal de que suena: no se toca $fm")
    val receiver = if (f == null) keyReceiver(fm) else null
    val by = quietBy(f != null, receiver != null, session != null, f == null && receiver == null && session == null && radioKind(fm) != null)
    when (by) {
        Quiet.PROFILE -> f?.releaseAudio()
        Quiet.RECEIVER -> receiver?.let { sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PAUSE, it) }
        Quiet.SESSION -> runCatching { session?.transportControls?.pause() }.onFailure { trace("pausa a $fm falló (${it.javaClass.simpleName})") }
        Quiet.PROFILE_LATER -> releaseWithProfile(fm)
        null -> Unit
    }
    radioQuietAt = now
    radioClaimedAt = null
    mainHandler.removeCallbacks(profileAnswer)
    fmCalibration.abort()
    radio.forget()
    trace("radio callada: $fm por ${by?.label ?: "nada (sin a quién)"}")
}

/** La radio que LiteDash prendió se calla con su perfil aunque la pieza Radio no esté a la vista: se abre solo para eso. */
internal fun MusicRepository.releaseWithProfile(pkg: String) {
    factoryThread.execute {
        val p = profileFor(pkg) ?: return@execute
        mainHandler.post {
            if (p.open()) p.releaseAudio()
            p.close()
        }
    }
}

// —— Radio de fábrica: su play, su confirmación y su calibración ——

/**
 * Play de la radio de fábrica: con perfil de su marca ([f]) o sin control (solo suena al abrirla). La
 * calibración dice cómo ([fmCalibration]: el perfil sin abrir nada o abrir su app); si ya se está calibrando o
 * nada funcionó antes, lo de siempre: el perfil y, si no confirma, abrir la app.
 */
internal fun MusicRepository.playRadio(pkg: String, f: FactoryRadio?) {
    fmPlay = FmPlay(pkg)
    bluetoothSourceTaken()
    val available = listOfNotNull(
        Recipes.PROFILE.takeIf { f != null },
        Recipes.OPEN_FACTORY.takeIf { launchIntentFor(pkg, bluetooth = false) != null },
    )
    if (fmCalibration.play(available, catalog?.radio)) return
    if (f != null) playFactory(f, verify = false) else openAndReturn(verify = false)
}

/** Aplica la receta de la radio; la revisión llega sola (la confirmación del perfil o tras regresar de la app). */
internal fun MusicRepository.useRadio(id: String?): Long? {
    if (fmPlay == null) return null
    when (id) {
        Recipes.PROFILE -> factoryFor(radio)?.let { playFactory(it, verify = true) } ?: fmCalibration.abort()
        Recipes.OPEN_FACTORY -> openAndReturn(verify = true)
    }
    return null
}

/**
 * La radio quedó así tras revisar o tras un "Sí". Sonando, se da por hecho. Sin sonar con la receta guardada:
 * si la eligió la persona (Android no ve su audio), el perfil se da por sonando y ya no se abre la app; si la
 * guardó la señal, esta vez se abre la app como antes (tres veces seguidas y se calibra otra vez).
 */
internal fun MusicRepository.radioSettled(heard: Boolean) {
    val f = factoryFor(radio)
    val profile = fmCalibration.current == Recipes.PROFILE
    if (heard || fmCalibration.store.saved?.by == SavedBy.USER) {
        if (profile) f?.started()
        return
    }
    if (profile && fmPlay != null) openAndReturn(verify = false)
}

/**
 * Radios de fábrica que no dejan nada por fuera: solo suenan al abrirlas. Se abre la radio y, si quedó encima de
 * LiteDash, se regresa al inicio en [FACTORY_RETURN_MS] (Android deja a la app volver al frente unos segundos después
 * de abrir otra). Después se anota si hay audio ([radioCheck]) y, con [verify], se lo dice a la calibración.
 */
internal fun MusicRepository.openAndReturn(verify: Boolean) {
    val play = fmPlay ?: return
    radio.starting = null
    val over = openOver(play.pkg, FACTORY_RETURN_MS) { factoryFor(radio)?.started() }
    trace("radio sin control: se abre ${play.pkg}${if (over) " y se regresa" else ""}")
    play.verifyOpen = verify
    mainHandler.removeCallbacks(radioCheck)
    mainHandler.postDelayed(radioCheck, RADIO_VERIFY_MS)
}

/**
 * FM de fábrica: play sin abrir su app (se le pide el audio a la radio). Si en [FACTORY_ANSWER_MS] la radio no dice que
 * tiene el audio ([profileAnswer]): calibrando ([verify]), se le dice a la calibración; si no, se abre como antes.
 */
internal fun MusicRepository.playFactory(f: FactoryRadio, verify: Boolean) {
    val play = fmPlay ?: return
    play.profile = f
    play.askedAt = SystemClock.elapsedRealtime()
    play.run = factoryRun
    play.verifyProfile = verify
    trace("${f.tag} play sin abrir")
    f.takeAudio()
    mainHandler.removeCallbacks(profileAnswer)
    mainHandler.postDelayed(profileAnswer, FACTORY_ANSWER_MS)
}
