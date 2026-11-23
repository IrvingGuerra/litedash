package com.irvingguerra.litedash.feature.music

import android.app.Notification
import android.content.IntentFilter
import android.media.AudioManager
import android.media.session.MediaController
import androidx.core.content.ContextCompat
import com.irvingguerra.litedash.feature.music.MusicRepository.Lens

internal fun MusicRepository.syncDevices() {
    val want = users > 0 || phoneWatchers > 0
    if (want == devicesOn) return
    devicesOn = want
    if (want) devices.start() else devices.stop()
}

// El celular llegó o se fue: la música Bluetooth que se suponía sonando ya no suena.
internal fun MusicRepository.phoneChanged(name: String?) {
    bluetoothDevice = name
    // Solo Ajustes → Celular escuchando: nada de la música se toca.
    if (users == 0) return
    if (name != null) {
        avrcp?.connect()
        return
    }
    val app = bluetoothApp() ?: return
    for (lens in listOf(music, radio)) if (lens.assumedPkg == app) lens.forget()
    choose(sessions())
}

/** Si la pantalla trae el Bluetooth de Android (AVRCP), se prepara para leer la música del celular. */
internal fun MusicRepository.startAvrcp() {
    // Ya existe (la música se escondió y volvió): stop() solo lo desconectó, se vuelve a conectar.
    avrcp?.let { it.connect(); return }
    val service = bluetoothService ?: return
    avrcp = BluetoothAvrcp(context, service, ::onAvrcpController)
    avrcp?.connect()
}

/**
 * El controlador AVRCP ya (des)conectó. Su sesión no siempre llega por las sesiones activas (sin
 * el permiso de avisos, nunca): cuando cambia de estado y ninguna pieza la tiene, se vuelve a repartir.
 */
internal fun MusicRepository.onAvrcpController(controller: MediaController?) {
    mainHandler.post {
        avrcpController?.unregisterCallback(avrcpWatch)
        avrcpController = controller
        avrcpLast = controller?.playbackState?.state
        controller?.registerCallback(avrcpWatch, mainHandler)
        if (users > 0) choose(sessions())
    }
}

/** Solo si la pieza podría tomarla (Bluetooth, o App sin app elegida) y aún no la tiene: cada vuelta es IPC. */
internal fun MusicRepository.avrcpRechoose() {
    if (users == 0 || !(mode == MusicMode.BLUETOOTH || (mode == MusicMode.APP && preferred == null))) return
    val token = avrcpController?.sessionToken
    if (listOf(music, radio).none { it.controller?.sessionToken == token }) choose(sessions())
}

/** ¿El aviso [meta] es del método de música escogido? Bluetooth: los del celular; App: la elegida o cualquiera. */
internal fun MusicRepository.musicHears(meta: BroadcastMeta): Boolean = when (mode) {
    MusicMode.FILES -> false
    MusicMode.BLUETOOTH -> meta.bluetooth || meta.pkg == bluetoothApp()
    MusicMode.APP -> modeApp == null || meta.pkg == modeApp
}

/** La primera pieza (de música o de radio) entró: lo que las dos usan (sesiones, avisos, AVRCP, aparatos). */
internal fun MusicRepository.start() {
    syncDevices()
    // La primera vez el servicio AVRCP se busca en otro hilo y se conecta al encontrarlo ([MusicRepository.use]).
    if (avrcp != null) startAvrcp()
    // Son avisos de otras apps: el receptor debe aceptar avisos de fuera. Cualquier app podría mandar
    // un título falso; solo se muestra (sin sesión de por medio) y la sesión real siempre manda.
    val filter = IntentFilter().apply { BroadcastMeta.ACTIONS.forEach(::addAction) }
    ContextCompat.registerReceiver(context, broadcasts, filter, ContextCompat.RECEIVER_EXPORTED)
    NotificationService.media = notifications
    loadSourceNotes()
    listen()
    // Sin permiso de avisos, igual se ven la sesión propia (Archivos) y la del Bluetooth de Android;
    // lo demás, lo último avisado por broadcast.
    if (!listening) choose(sessions())
}

internal fun MusicRepository.listen() {
    if (!hasPermission()) return
    NotificationService.rebindIfNeeded(context)
    runCatching {
        sessionManager.addOnActiveSessionsChangedListener(onSessionsChanged, component, mainHandler)
        listening = true
        choose(sessions())
    }.onFailure { trace("no se pudo escuchar las sesiones (${it.javaClass.simpleName})") }
}

/**
 * Ya no hay piezas a la vista. Regla de los plazos: los que esperan que algo suene para hacer algo en pantalla (el
 * "Abriendo…" de un despertar, abrir la app que no suena) se cancelan; el regreso tras abrir una app encima y su
 * revisión no (abrirla es justo lo que esconde la música; se cancelan si LiteDash vuelve al frente: [cancelReturn]).
 */
internal fun MusicRepository.stop() {
    syncDevices()
    avrcp?.disconnect()
    avrcpController?.unregisterCallback(avrcpWatch)
    avrcpController = null
    runCatching { context.unregisterReceiver(broadcasts) }
    if (NotificationService.media === notifications) NotificationService.media = null
    if (listening) runCatching { sessionManager.removeOnActiveSessionsChangedListener(onSessionsChanged) }
    listening = false
    stopRadio()
    mainHandler.removeCallbacks(playCheck)
    for (lens in listOf(music, radio)) {
        mainHandler.removeCallbacks(lens.wakeTimeout)
        lens.starting = null
        lens.detach()
    }
    icons.clear()
    // El aviso de que no se encontró cómo es del momento; una pregunta pendiente se quita sola sin respuesta.
    dismissSoundCheck()
}

/**
 * Las sesiones activas y, de las apps enlazadas (Bluetooth, FM), la de su aviso de música si
 * Android no la cuenta como activa (algunas pantallas solo la dejan ver por ahí).
 */
internal fun MusicRepository.sessions(): List<MediaController> {
    val active = runCatching { sessionManager.getActiveSessions(component) }.getOrDefault(emptyList())
    val extra = sourceNotes.values.filter { n -> active.none { it.packageName == n.packageName } }.mapNotNull { n ->
        n.sessionToken()?.let { runCatching { MediaController(context, it) }.getOrNull() }
    }
    val own = ownSession?.invoke()?.takeIf { t -> (active + extra).none { it.sessionToken == t } }
        ?.let { runCatching { MediaController(context, it) }.getOrNull() }
    val all = active + extra + listOfNotNull(own)
    // El Bluetooth de Android (AVRCP) no sale entre las sesiones activas: se agrega.
    val av = avrcpController ?: return all
    return if (all.any { it.packageName == av.packageName }) all else all + av
}

/** La sesión de [pkg]: la que ve [lens] si es suya, si no la primera de las activas. */
internal fun MusicRepository.sessionOf(pkg: String, lens: Lens): MediaController? =
    lens.controller?.takeIf { it.packageName == pkg } ?: sessions().firstOrNull { it.packageName == pkg }

/** Vuelve a leer los avisos de las apps enlazadas (al empezar, al conectarse el servicio o al enlazar otra). */
internal fun MusicRepository.loadSourceNotes() {
    sourceNotes.clear()
    val apps = sourceApps.values.toSet()
    if (apps.isEmpty()) return
    NotificationService.active().filter { it.packageName in apps && it.isMedia() }.forEach { sourceNotes[it.packageName] = it }
}

/** Título y texto del aviso de música de [pkg]; null si no tiene. */
internal fun MusicRepository.notified(pkg: String): Pair<String, String>? {
    val extras = sourceNotes[pkg]?.notification?.extras ?: return null
    val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
    return if (title.isEmpty()) null else title to extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
}

internal fun MusicRepository.musicActive() = context.getSystemService(AudioManager::class.java).isMusicActive

/**
 * ¿Suena una app que no está en [pkgs]? (una sesión ocupada o el reproductor propio). isMusicActive es de todo
 * Android: con esto se sabe si el audio puede ser de la radio (o del Bluetooth) o es de otra app.
 */
internal fun MusicRepository.othersPlaying(pkgs: Collection<String>): Boolean = sessions().any { it.packageName !in pkgs && it.busy() }
