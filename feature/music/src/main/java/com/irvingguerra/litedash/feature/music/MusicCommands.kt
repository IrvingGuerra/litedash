package com.irvingguerra.litedash.feature.music

import android.content.ComponentName
import android.content.Intent
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.SystemClock
import android.view.KeyEvent
import com.irvingguerra.litedash.feature.music.MusicRepository.Lens

/**
 * Lo que tarda una app en contestar a un play: que suene la que se despertó (si no, se abre la de la pantalla), que suene
 * la de música (si no, se abre y se regresa) y que su servicio de música conecte.
 */
private const val APP_ANSWER_MS = 4_000L

/**
 * Lo que se espera, tras el play por su servicio, a que la app que conectó suene; si no, la tecla a su receptor
 * ([serviceBackup]). Antes de [APP_ANSWER_MS], cuando se abriría encima.
 */
private const val SERVICE_ANSWER_MS = 1_200L

/** Las acciones de play/pausa que una sesión debe anunciar para mandarle la orden por sus controles. */
private const val PLAY_ACTIONS = PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE

/** Una orden de la música y su tecla multimedia (y lo que se le manda al celular). */
internal enum class Command(val key: Int, val phone: String, val label: String) {
    PLAY(KeyEvent.KEYCODE_MEDIA_PLAY, "play", "play"),
    PAUSE(KeyEvent.KEYCODE_MEDIA_PAUSE, "pause", "pausa"),
    NEXT(KeyEvent.KEYCODE_MEDIA_NEXT, "next", "siguiente"),
    PREVIOUS(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "prev", "anterior"),
}

/** "Sin receptor" en la caché de receptores (el mapa concurrente no guarda nulos). */
private val NO_RECEIVER = ComponentName("", "")

/** La última app que sonó, solo con el método App (con Archivos o Bluetooth no se despierta otra). */
internal fun MusicRepository.lastApp(): String? = if (mode == MusicMode.APP) store.getString(KEY_LAST_APP, null) else null

/** La app a la que va una orden de [lens] que ya muestra algo (o una tecla cuando no hay sesión que la reciba). */
internal fun MusicRepository.target(lens: Lens): String? =
    // Lo que se supo sin sesión (aviso o supuesto) manda: la sesión elegida puede ser otra app vacía.
    lens.song?.takeIf { it.blind }?.pkg?.ifEmpty { null } ?: lens.controller?.packageName ?: lens.song?.pkg?.ifEmpty { null }
        ?: if (lens === radio) radioApp else modeApp ?: lastApp()

// —— Las órdenes: una función por intención ——

/**
 * Play en [lens], venga de donde venga (widget, volante, la música en grande, la radio). Si se ve algo, a esa app;
 * si no, a la del método elegido, la que se ve en su sesión o la última que sonó (la radio: la escogida). El camino lo
 * decide [playPlan]. Lo que sigue al play también se hace solo aquí: callar la radio si se sabe que suena (antes del
 * play), suponer que la app de fábrica obedeció, el plazo del despertar, vigilar que la app de música suene y la
 * receta del Bluetooth. [onIdle] = la radio sin nada con qué sonar (la pieza abre sus ajustes o la lista).
 */
internal fun MusicRepository.play(lens: Lens, onIdle: () -> Unit = {}) {
    val radioLens = lens === radio
    val s = lens.song
    val pkg = if (s != null) target(lens) else if (radioLens) radioApp else modeApp ?: lens.controller?.packageName ?: lastApp()
    val source = pkg != null && !isOwn(pkg) && sourceOf(pkg) != null
    // Lo supuesto de una app de música normal (un aviso o la última orden) no dice si está despierta: se trata como si no
    // se viera y se despierta por un solo canal (si no, la tecla a ciegas y suponer que suena: "doble play").
    val shown = s != null && !(s.blind && !source && !s.phone)
    val session = !radioLens && !shown && pkg != null && !source && !isOwn(pkg) &&
        (sessionOf(pkg, lens)?.playbackState?.actions ?: 0L) and PLAY_ACTIONS != 0L
    val silent = !radioLens && source && silentFor(pkg, lens)
    val plan = playPlan(
        Target(
            radio = radioLens,
            phone = s?.phone == true,
            own = pkg != null && isOwn(pkg),
            app = pkg != null,
            shown = shown,
            obeys = shown && lens.obeys(PLAY_ACTIONS),
            sessionShown = shown && lens.controller != null,
            profile = factoryFor(lens) != null,
            source = source,
            session = session,
            silent = silent,
        ),
    )
    if (silent) {
        // Nada por dónde darle play: la música se pone desde el celular. Solo que la pantalla deje pasar su sonido.
        trace("play $pkg: sin receptor, sesión ni servicio (se controla desde el celular)")
        return calibrateBluetooth()
    }
    if (plan.route.isEmpty()) return onIdle()
    if (plan.route.first() == Channel.OWN) {
        // Al tocar, el reproductor propio avisa que empieza ([ownMusicStarted]): ahí se calla la radio.
        val kind = if (radioLens) SourceKind.RADIO else SourceKind.USB
        ownPlay?.invoke(kind) { ok ->
            if (!ok) if (radioLens) onIdle() else onFilesEmpty?.invoke()
            if (!radioLens && users > 0) choose(sessions())
        }
        return
    }
    if (!radioLens) quietRadio(pkg)
    if (plan.wake && pkg != null) lens.starting = appLabel(pkg)
    val via = deliver(pkg, lens, Command.PLAY, plan.route)
    // El celular confirma con su aviso; si no hubo a quién, nada más que hacer.
    if (via == null) lens.starting = null
    if (via == null || via == Channel.PHONE || pkg == null) return
    // Su servicio además del receptor o la sesión (ver [Plan.serviceToo]); si fue lo único, ya se llamó.
    val hasService = when (via) {
        Channel.SERVICE -> true
        Channel.RECEIVER, Channel.SESSION -> plan.serviceToo && connectAndPlay(pkg, fallback = false)
        else -> false
    }
    if (!isOwn(pkg) && assumesPlay(plan, via, source, hasService)) lens.assume(pkg, true)
    if (radioLens) {
        // LiteDash prendió la radio: una de las señales para callarla después.
        radioClaimedAt = SystemClock.elapsedRealtime()
    } else {
        if (via == Channel.SESSION || plan.wake) watchPlay(pkg)
        if (playsBluetooth(sourceOf(pkg), standIn = mode == MusicMode.BLUETOOTH && pkg in BluetoothAvrcp.ANDROID_PACKAGES)) calibrateBluetooth()
    }
    // La radio por su perfil o abriéndola tiene su propia confirmación.
    if (plan.wake && via != Channel.PROFILE && via != Channel.OPEN) armWake(lens, pkg, opensAfterWake(plan, via, sourceOf(pkg)))
}

/** Pausa en [lens] ([pausePlan]). Sin a quién, nada (nunca despierta otra cosa). */
internal fun MusicRepository.pause(lens: Lens) {
    val radioLens = lens === radio
    val s = lens.song
    if (radioLens) {
        // Pausar la radio a media calibración: lo pendiente se deja (no es que no suene).
        fmCalibration.abort()
        mainHandler.removeCallbacks(profileAnswer)
        radioClaimedAt = null
    }
    val pkg = target(lens)
    val plan = pausePlan(
        Target(
            radio = radioLens,
            phone = s?.phone == true,
            own = pkg != null && isOwn(pkg),
            app = pkg != null,
            shown = s != null,
            obeys = lens.obeys(PLAY_ACTIONS),
            profile = factoryFor(lens) != null,
            silent = silentFor(pkg, lens),
        ),
    )
    val via = deliver(pkg, lens, Command.PAUSE, plan.route)
    if (pkg != null && !isOwn(pkg) && (via == Channel.RECEIVER || via == Channel.SESSION_KEY || via == Channel.SYSTEM)) lens.assume(pkg, false)
    // La radio le había quitado la salida al Bluetooth de la pantalla: con el método Bluetooth, se le regresa.
    if (radioLens && modeState == MusicMode.BLUETOOTH && btApplied?.stale == true) calibrateBluetooth()
}

/** Siguiente o anterior en [lens] ([skipPlan]). */
internal fun MusicRepository.skip(lens: Lens, forward: Boolean) {
    val action = if (forward) PlaybackState.ACTION_SKIP_TO_NEXT else PlaybackState.ACTION_SKIP_TO_PREVIOUS
    val pkg = target(lens)
    val plan = skipPlan(
        Target(
            radio = lens === radio,
            phone = lens.song?.phone == true,
            own = pkg != null && isOwn(pkg),
            app = pkg != null,
            shown = lens.song != null,
            obeys = lens.obeys(action),
            profile = factoryFor(lens) != null,
            silent = silentFor(pkg, lens),
        ),
    )
    deliver(pkg, lens, if (forward) Command.NEXT else Command.PREVIOUS, plan.route)
}

/**
 * La única cadena para mandar una orden: prueba cada canal de [route] en orden y se queda en el primero que tiene a
 * quién; lo anota en el registro. Devuelve por dónde se mandó (null = por ninguno).
 */
internal fun MusicRepository.deliver(pkg: String?, lens: Lens, command: Command, route: List<Channel>): Channel? {
    val via = route.firstOrNull { through(it, pkg, lens, command, fallbackToSystem = Channel.SYSTEM in route) }
    trace("${command.label} ${pkg ?: "-"}${if (lens === radio) " (radio)" else ""}: ${via?.label ?: "sin a quién"}")
    return via
}

/** Manda [command] por [channel]; false = por ahí no hay a quién (se prueba el siguiente). */
internal fun MusicRepository.through(channel: Channel, pkg: String?, lens: Lens, command: Command, fallbackToSystem: Boolean): Boolean = when (channel) {
    Channel.PHONE -> (phoneControl?.invoke(command.phone, 0) == true).also { sent ->
        if (sent && (command == Command.PLAY || command == Command.PAUSE)) lens.phoneToggled(command == Command.PLAY)
    }
    // Lo atiende [play] antes de llegar aquí.
    Channel.OWN -> false
    Channel.PROFILE -> factoryFor(lens)?.let { f ->
        when (command) {
            Command.PLAY -> playRadio(pkg ?: return false, f)
            Command.PAUSE -> f.releaseAudio()
            Command.NEXT -> f.next()
            Command.PREVIOUS -> f.previous()
        }
        true
    } ?: false
    Channel.SESSION -> pkg?.let { sessionOf(it, lens) }?.let { c ->
        runCatching {
            val t = c.transportControls
            when (command) {
                Command.PLAY -> t.play()
                Command.PAUSE -> t.pause()
                Command.NEXT -> t.skipToNext()
                Command.PREVIOUS -> t.skipToPrevious()
            }
        }.onFailure { trace("sesión de $pkg: ${command.label} falló (${it.javaClass.simpleName})") }.isSuccess
    } ?: false
    Channel.RECEIVER -> keyReceiver(pkg)?.let { sendMediaKey(context, command.key, it); true } ?: false
    Channel.SESSION_KEY -> pkg?.takeUnless(::isOwn)?.let { sessionOf(it, lens) }?.let { c ->
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) runCatching { c.dispatchMediaButtonEvent(KeyEvent(action, command.key)) }
        true
    } ?: false
    Channel.SERVICE -> command == Command.PLAY && pkg != null && connectAndPlay(pkg, fallback = fallbackToSystem)
    // La radio: solo la pausa y solo si nada más suena (si no, el sistema se la daría a esa otra app).
    Channel.SYSTEM -> if (lens === radio && othersPlaying(listOfNotNull(pkg))) false else { sendMediaKey(context, command.key); true }
    Channel.OPEN -> command == Command.PLAY && pkg != null && run { playRadio(pkg, null); true }
}

/** El plazo del despertar de [lens]: se quita el "Abriendo…" y, con [opens], se abre [pkg] si sigue sin nada. */
internal fun MusicRepository.armWake(lens: Lens, pkg: String, opens: Boolean) {
    // Uno solo por pieza: el de un despertar anterior no debe quitar el "Abriendo…" de este.
    mainHandler.removeCallbacks(lens.wakeTimeout)
    lens.wakeTimeout = Runnable {
        if (lens.song == null && lens.starting != null && opens) open(pkg)
        lens.starting = null
    }
    mainHandler.postDelayed(lens.wakeTimeout, APP_ANSWER_MS)
}

/**
 * Apps como Spotify, dormidas en segundo plano, a veces dicen que suenan y no suenan hasta abrirlas. Tras un play a una
 * app de música normal (no Bluetooth ni FM: esas suenan por fuera de Android), si en [APP_ANSWER_MS] Android no tiene
 * audio y la pieza no está en pausa, se abre la app, se regresa al inicio y se le vuelve a dar play ([playCheck]).
 */
internal fun MusicRepository.watchPlay(pkg: String) {
    if (isOwn(pkg) || sourceOf(pkg) != null) return
    playPkg = pkg
    mainHandler.removeCallbacks(playCheck)
    mainHandler.postDelayed(playCheck, APP_ANSWER_MS)
}

// —— Cómo se le habla a una app ——

/**
 * El receptor del botón de play de [pkg] (nunca el de LiteDash: su reproductor tiene sesión). Se pregunta una vez por
 * app (es una llamada al sistema y la pieza lo consulta al pintarse), fuera de cualquier candado: el reporte lo lee en
 * otro hilo.
 */
internal fun MusicRepository.keyReceiver(pkg: String?): ComponentName? {
    val p = pkg?.takeUnless(::isOwn) ?: return null
    val found = receivers[p] ?: (mediaButtonReceivers(p).firstOrNull() ?: NO_RECEIVER).also { receivers.putIfAbsent(p, it) }
    return found.takeUnless { it == NO_RECEIVER }
}

/**
 * Se conecta al servicio de música de [pkg] y le pide play; si no conecta y [fallback], la tecla a su receptor o, sin
 * él, al sistema. False = no tiene servicio.
 */
internal fun MusicRepository.connectAndPlay(pkg: String, fallback: Boolean): Boolean {
    val service = browserServices(context, pkg).firstOrNull() ?: return false
    lateinit var browser: MediaBrowser
    browser = MediaBrowser(context, ComponentName(service.packageName, service.name), object : MediaBrowser.ConnectionCallback() {
        override fun onConnected() {
            val controller = runCatching { MediaController(context, browser.sessionToken).also { it.transportControls.play() } }
                .onFailure { trace("servicio de $pkg: play falló (${it.javaClass.simpleName})") }.getOrNull()
            browser.disconnect()
            // Conectó pero hay apps que ignoran el play en frío: con [fallback], la tecla a su receptor si sigue callada.
            if (fallback && controller != null) mainHandler.postDelayed({
                val receiver = keyReceiver(pkg)
                if (serviceBackup(runCatching { controller.busy() }.getOrDefault(false), musicActive(), receiver != null)) {
                    trace("servicio de $pkg: conectó pero no suena, play a su receptor")
                    receiver?.let { sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY, it) }
                }
            }, SERVICE_ANSWER_MS)
        }

        override fun onConnectionFailed() {
            if (!fallback) return trace("servicio de $pkg: no conectó")
            // Respaldo: la tecla a su receptor (si tiene) y si no, al sistema; nunca las dos.
            val receiver = keyReceiver(pkg)
            trace("servicio de $pkg: no conectó, play ${if (receiver != null) "a su receptor" else "al sistema"}")
            if (receiver != null) sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY, receiver) else sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY)
        }
    }, null)
    // Si el servicio nunca contesta, la conexión no se queda colgada para siempre.
    mainHandler.postDelayed({ runCatching { browser.disconnect() } }, APP_ANSWER_MS)
    return runCatching { browser.connect() }.onFailure { trace("servicio de $pkg: no se pudo conectar") }.isSuccess
}

/**
 * La app de Bluetooth de marca [pkg] sin receptor del botón de play, sesión ni servicio de música, con el celular sin
 * LiteDash Connect (reporte E48170, TopWay `com.tw.bt`): no hay cómo darle play desde la pantalla. El Bluetooth de
 * Android (AVRCP) nunca cuenta: su sesión llega con el celular.
 */
internal fun MusicRepository.silentBluetooth(pkg: String, lens: Lens): Boolean =
    sourceOf(pkg) == SourceKind.BLUETOOTH && pkg != avrcpPackage && !phoneState.here &&
        keyReceiver(pkg) == null && sessionOf(pkg, lens) == null && browserServices(context, pkg).isEmpty()

/** [silentBluetooth] para una orden de la pieza Música ([lens]); la radio nunca. */
private fun MusicRepository.silentFor(pkg: String?, lens: Lens): Boolean =
    lens !== radio && pkg != null && !isOwn(pkg) && silentBluetooth(pkg, lens)

/** Receptores del botón de play de [pkg] (o de todas las apps con null). */
internal fun MusicRepository.mediaButtonReceivers(pkg: String? = null): List<ComponentName> = runCatching {
    val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply { if (pkg != null) setPackage(pkg) }
    context.packageManager.queryBroadcastReceivers(intent, 0)
        .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
}.getOrDefault(emptyList())
