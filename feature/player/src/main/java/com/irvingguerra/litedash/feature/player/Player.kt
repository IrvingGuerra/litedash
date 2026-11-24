package com.irvingguerra.litedash.feature.player

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.irvingguerra.litedash.feature.music.Drives
import com.irvingguerra.litedash.feature.music.OwnShuffle
import java.util.concurrent.Executors

/** Una canción de la memoria USB o una estación de radio: lo que el reproductor sabe tocar. */
sealed interface Playable {
    val title: String
    val subtitle: String
    val uri: Uri
    /** Clave estable para recordar "lo último que sonó". */
    val key: String
}

/** De dónde viene lo que suena: la memoria USB o la radio por internet. */
enum class PlayerSource { USB, RADIO }

/** Lo que el reproductor está haciendo, como estado de Compose (lo lee la biblioteca). */
data class PlayerState(
    val source: PlayerSource?,
    val queue: List<Playable>,
    val index: Int,
    val playing: Boolean,
    /** Conectando o cargando (la radio tarda unos segundos en arrancar). */
    val buffering: Boolean,
    val error: String?,
) {
    val current: Playable? get() = queue.getOrNull(index)

    companion object {
        val IDLE = PlayerState(null, emptyList(), -1, playing = false, buffering = false, error = null)
    }
}

/** Lado máximo de la carátula que se manda a la sesión (la pieza la muestra chica). */
private const val ART_SIDE = 512

/** Si en este tiempo no empieza a sonar, se da por perdido (la radio puede no contestar). */
private const val LOAD_TIMEOUT_MS = 25_000L

/** La radio AAC que ya sonaba y se cortó (túnel, cambio de antena): se reintenta estas veces antes de mostrar el error. */
private const val RADIO_RETRIES = 2
private const val RADIO_RETRY_MS = 2_000L

/** El volumen mientras otra app habla (la navegación) con el foco "bajar volumen". */
private const val DUCK_VOLUME = 0.2f

/**
 * El reproductor propio de LiteDash: toca las canciones de la memoria USB y las estaciones
 * de radio por internet con [MediaPlayer], publica una sesión de medios (así la pieza Música
 * y la vista en grande lo ven como a cualquier app, y el volante lo controla) y pide el foco
 * de audio como manda Android. Mientras suena, [PlaybackService] lo mantiene en primer plano
 * con su aviso. Uno solo por proceso; no hace nada hasta que algo pide [play].
 * Guarda el contexto de la aplicación (vive lo mismo que el proceso): no es una fuga.
 */
@SuppressLint("StaticFieldLeak")
object Player {
    var state by mutableStateOf(PlayerState.IDLE)
        private set

    /** Sube con cada [play] o [stop]: quien prepara algo en segundo plano sabe si ya pidieron otra cosa. */
    var requests = 0
        private set

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    // Abrir el audio fuera del hilo principal, de uno en uno.
    private val io = Executors.newSingleThreadExecutor { Thread(it, "player-io") }
    // Las etiquetas y la carátula en su propio hilo: el logo de una estación (red, segundos) no retrasa el audio de la siguiente.
    private val art = Executors.newSingleThreadExecutor { Thread(it, "player-art") }
    private var player: MediaPlayer? = null
    /** Radio AAC en vivo (lo que MediaPlayer no sabe arrancar); null si suena por [player]. */
    private var aac: AacStream? = null
    private var session: MediaSession? = null
    private var focus: AudioFocusRequest? = null
    private var ducked = false
    /** Se pausó por una llamada o un aviso: al terminar, sigue solo. */
    private var resumeAfterFocus = false
    /** Qué versión de la cola va cargándose (una carga vieja no debe pisar a la nueva). */
    @Volatile private var load = 0
    private var usbWatcher: BroadcastReceiver? = null
    /** Canciones sin carátula incrustada: no se vuelve a abrir el archivo cada vez que suenan. */
    private val noArt = HashSet<String>()

    /** Llamar una vez (lo hace el app al arrancar): guarda el contexto y conecta el botón de aleatorio. */
    fun init(context: Context) {
        app = context.applicationContext
        OwnShuffle.attach(PlayerStore(app).shuffle, ::shuffle)
    }

    /** La cola en su orden (la de la carpeta o la lista) cuando la que suena va revuelta; null = no va revuelta. */
    private var ordered: List<Playable>? = null

    /** Suena [queue] desde [index]; si ya es lo mismo, solo sigue. Con aleatorio (USB), lo que sigue va revuelto. */
    fun play(source: PlayerSource, queue: List<Playable>, index: Int) {
        val item = queue.getOrNull(index) ?: return
        requests++
        if (state.current?.key == item.key && (state.queue == queue || ordered == queue)) return resume()
        onPlay?.invoke(source)
        val shuffled = source == PlayerSource.USB && OwnShuffle.on
        ordered = queue.takeIf { shuffled }
        state = if (shuffled) PlayerState(source, shuffledFrom(queue, index), 0, playing = false, buffering = true, error = null)
        else PlayerState(source, queue, index, playing = false, buffering = true, error = null)
        PlayerStore(app).rememberLast(source, item.key)
        ensureSession()
        start(item)
    }

    fun resume() {
        val item = state.current ?: return
        // Ya suena o va cargando (arrancará solo al estar listo): otro play no la corta ni la reinicia.
        if (state.playing || state.buffering) return
        state.source?.let { onPlay?.invoke(it) }
        // La radio en pausa se desconecta: play la vuelve a abrir (igual si se canceló la carga o falló).
        val p = player ?: run {
            update(playing = false, buffering = true)
            return start(item)
        }
        if (!requestFocus()) return noFocus()
        p.start()
        update(playing = true)
    }

    /**
     * Android no dio el foco de audio: sin él se quedaba mudo sin decir nada (reporte DA2107). Se avisa en la pieza
     * (la carga queda lista: play lo vuelve a pedir) y en el registro.
     */
    private fun noFocus() {
        Log.w("LiteDash", "Player: sin foco de audio")
        state = state.copy(playing = false, buffering = false, error = app.getString(R.string.player_no_focus))
        publishState()
        PlaybackService.update(app)
    }

    fun pause() {
        resumeAfterFocus = false
        if (state.buffering || state.current is Station) {
            // Pausar mientras carga (o una radio en vivo) = cortar; play la vuelve a abrir.
            load++
            releaseAudio()
            update(playing = false, buffering = false)
            return
        }
        player?.takeIf { it.isPlaying }?.pause()
        update(playing = false)
    }

    private fun releaseAudio() {
        player?.release()
        player = null
        aac?.stop()
        aac = null
    }

    fun toggle() = if (state.playing) pause() else resume()

    /** La de [index] primero y las demás revueltas. */
    private fun shuffledFrom(queue: List<Playable>, index: Int): List<Playable> =
        listOf(queue[index]) + queue.filterIndexed { i, _ -> i != index }.shuffled()

    /**
     * Prende o apaga el aleatorio sin cortar lo que suena: prendido, lo que sigue se revuelve; apagado, regresa al orden de
     * la carpeta desde la canción de ahora. Se guarda.
     */
    private fun shuffle(on: Boolean) {
        PlayerStore(app).shuffle = on
        val s = state
        val current = s.current ?: return
        if (s.source != PlayerSource.USB) return
        if (on && ordered == null) {
            ordered = s.queue
            state = s.copy(queue = shuffledFrom(s.queue, s.index), index = 0)
        } else if (!on) {
            val back = ordered ?: return
            ordered = null
            state = s.copy(queue = back, index = back.indexOfFirst { it.key == current.key }.coerceAtLeast(0))
        }
    }

    fun next() = skip(1)
    fun previous() = skip(-1)

    private fun skip(by: Int) {
        val s = state
        if (s.queue.isEmpty()) return
        val i = (s.index + by).mod(s.queue.size)
        // Pasar de canción en pausa también la pone a sonar: como play, calla la radio de fábrica.
        if (!s.playing) s.source?.let { onPlay?.invoke(it) }
        state = s.copy(index = i, playing = false, buffering = true, error = null)
        PlayerStore(app).rememberLast(s.source!!, s.queue[i].key)
        start(s.queue[i])
    }

    fun seekTo(ms: Long) {
        val p = player ?: return
        // Mientras carga, MediaPlayer aún no deja adelantar.
        if (state.current !is Track || state.buffering) return
        p.seekTo(ms.toInt())
        // La sesión se entera ya (la barra se dibuja con la posición y la hora que publica).
        publishState()
    }

    /** Para del todo: suelta el audio, la sesión y el aviso. */
    fun stop() {
        requests++
        load++
        releaseAudio()
        abandonFocus()
        session?.isActive = false
        session?.release()
        session = null
        shown = null
        ordered = null
        unwatchUsb()
        state = PlayerState.IDLE
        PlaybackService.stop(app)
    }

    private fun start(item: Playable) {
        val myLoad = ++load
        preparedDuration = -1L
        releaseAudio()
        if (item is Track) watchUsb() else unwatchUsb()
        // Lo mismo otra vez (play tras pausar la radio, que la corta): su carátula y etiquetas ya se tienen; el logo de la
        // estación no se vuelve a bajar.
        val known = shown?.takeIf { it.first.key == item.key && (it.second != null || it.third != null) }
        publishMetadata(item, known?.second, known?.third)
        PlaybackService.update(app)
        // Si en un rato no suena, se avisa (la radio puede no contestar nunca).
        main.postDelayed({ if (myLoad == load && state.buffering) fail(app.getString(R.string.player_timeout)) }, LOAD_TIMEOUT_MS)
        if (item is Station && item.isAac) startAac(item, myLoad) else startMediaPlayer(item, myLoad)
        // La carátula aparte (ver [art]): la carga del audio no espera a un logo lento.
        if (known != null) return
        art.execute {
            if (myLoad != load) return@execute
            // Las canciones de una carpeta o USB solo traen el nombre del archivo: título, artista y álbum de sus etiquetas.
            val tags = (item as? Track)?.let(::tagsOf)
            val art = artworkOf(item)
            main.post {
                if (myLoad != load) return@post
                publishMetadata(item, art, tags)
                // El aviso también con las etiquetas y la carátula (en Android 6–10 pinta su propio título).
                PlaybackService.update(app)
            }
        }
    }

    /**
     * Radio AAC en vivo con el decodificador propio; si el flujo no es AAC, MediaPlayer. Si ya sonó y se corta (la red del
     * auto va y viene), se reintenta [RADIO_RETRIES] veces antes de mostrar el error: manejando, eso era tocar la pantalla.
     */
    private fun startAac(item: Station, myLoad: Int, retry: Int = 0) {
        if (!requestFocus()) return noFocus()
        var sounded = false
        aac = AacStream(app, item.url, object : AacStream.Listener {
            override fun onStarted() {
                sounded = true
                main.post { if (myLoad == load) update(playing = true, buffering = false) }
            }
            override fun onNotAac() { main.post { if (myLoad == load) { aac = null; startMediaPlayer(item, myLoad) } } }
            override fun onError(message: String) {
                val again = (sounded || retry > 0) && retry < RADIO_RETRIES
                main.post {
                    if (myLoad != load) return@post
                    Log.w("LiteDash", "Radio AAC: $message${if (again) " (otra vez)" else ""}")
                    if (!again) return@post fail(app.getString(R.string.player_cannot_play))
                    aac = null
                    update(playing = false, buffering = true)
                    main.postDelayed({ if (myLoad == load) startAac(item, myLoad, retry + 1) }, RADIO_RETRY_MS)
                }
            }
        }).also {
            if (ducked) it.setVolume(DUCK_VOLUME)
            it.start()
        }
    }

    private fun startMediaPlayer(item: Playable, myLoad: Int) {
        val p = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            setOnPreparedListener {
                if (myLoad != load) return@setOnPreparedListener
                // Las canciones de una carpeta o USB no traen duración: la da el reproductor (para la barra de avance).
                if (item is Track && item.durationMs <= 0) {
                    preparedDuration = it.duration.toLong()
                    shown?.let { (i, art, tags) -> publishMetadata(i, art, tags) }
                }
                if (requestFocus()) {
                    it.start()
                    update(playing = true, buffering = false)
                } else {
                    noFocus()
                }
            }
            setOnCompletionListener { if (myLoad == load && state.current is Track) next() }
            setOnSeekCompleteListener { if (myLoad == load) publishState() }
            setOnErrorListener { _, what, extra ->
                if (myLoad == load) { Log.w("LiteDash", "MediaPlayer: $what/$extra ${item.uri}"); fail(cannotPlay(item)) }
                true
            }
            setOnInfoListener { _, what, _ ->
                if (myLoad == load) when (what) {
                    MediaPlayer.MEDIA_INFO_BUFFERING_START -> update(buffering = true)
                    MediaPlayer.MEDIA_INFO_BUFFERING_END -> update(buffering = false)
                }
                false
            }
        }
        // La navegación sigue hablando: la canción que empieza (la siguiente) también va baja.
        if (ducked) p.setVolume(DUCK_VOLUME, DUCK_VOLUME)
        player = p
        io.execute {
            try {
                p.setDataSource(app, item.uri)
                p.prepareAsync()
            } catch (x: Exception) {
                main.post { if (myLoad == load) { Log.w("LiteDash", "MediaPlayer: ${x.message}"); fail(cannotPlay(item)) } }
            }
        }
    }

    /** No se pudo: se suelta el audio (play lo vuelve a intentar) y se avisa. */
    /** Lo que se dice cuando no suena: una canción de la memoria no se abrió; una estación, quizá la red. */
    private fun cannotPlay(item: Playable) =
        app.getString(if (item is Track) R.string.player_cannot_open_track else R.string.player_cannot_play)

    private fun fail(message: String) {
        load++
        releaseAudio()
        state = state.copy(playing = false, buffering = false, error = message)
        publishState()
        PlaybackService.update(app)
    }

    private fun update(playing: Boolean = state.playing, buffering: Boolean = state.buffering) {
        state = state.copy(playing = playing, buffering = buffering, error = null)
        publishState()
        PlaybackService.update(app)
    }

    // --- Sesión de medios: lo que ven la pieza, el volante y el aviso. ---

    private fun ensureSession() {
        if (session != null) return
        session = MediaSession(app, "LiteDash").apply {
            // Android 6–7.1 solo manda las teclas de medios (volante, botones) a las sesiones que lo piden; desde la 8
            // todas las reciben y la bandera ya no existe.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            }
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = resume()
                override fun onPause() = pause()
                override fun onSkipToNext() = next()
                override fun onSkipToPrevious() = previous()
                override fun onSeekTo(pos: Long) = seekTo(pos)
                override fun onStop() = stop()
            })
            openLiteDash(app)?.let(::setSessionActivity)
            isActive = true
        }
        onSession?.invoke()
    }

    /** La sesión del reproductor (la pieza Música la lee directo: no necesita el permiso de avisos). */
    val sessionToken get() = session?.sessionToken

    /** Se creó la sesión (empezó a sonar algo): la pieza Música la toma. */
    var onSession: (() -> Unit)? = null

    /** Se pidió que suene (play o reanudar) [PlayerSource]: con la USB, la música calla la radio de fábrica. */
    var onPlay: ((PlayerSource) -> Unit)? = null

    /** La duración que dio el reproductor al preparar (−1 = aún no o no se sabe). */
    private var preparedDuration = -1L

    /** Lo último publicado, para volverlo a publicar con la duración. */
    private var shown: Triple<Playable, Bitmap?, Tags?>? = null

    /** Título, artista y carátula de lo que suena como los ve la sesión (con sus etiquetas), para el aviso; null = nada. */
    internal fun nowShown(): Triple<String, String, Bitmap?>? {
        val (item, art, tags) = shown?.takeIf { it.first.key == state.current?.key } ?: return null
        return Triple(tags?.title ?: item.title, tags?.artist ?: item.subtitle, art)
    }

    private fun publishMetadata(item: Playable, art: Bitmap?, tags: Tags? = null) {
        shown = Triple(item, art, tags)
        val duration = (item as? Track)?.durationMs?.takeIf { it > 0 } ?: preparedDuration.takeIf { item is Track && it > 0 } ?: -1L
        session?.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, tags?.title ?: item.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, tags?.artist ?: item.subtitle)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, tags?.album ?: (item as? Track)?.album ?: "")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
                .apply { if (art != null) putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art) }
                .build(),
        )
        publishState()
    }

    private fun publishState() {
        val s = state
        val p = player
        val track = s.current is Track
        val status = when {
            s.error != null -> PlaybackState.STATE_ERROR
            s.buffering -> PlaybackState.STATE_BUFFERING
            s.playing -> PlaybackState.STATE_PLAYING
            s.current != null -> PlaybackState.STATE_PAUSED
            else -> PlaybackState.STATE_NONE
        }
        val position = if (track && p != null && !s.buffering) runCatching { p.currentPosition.toLong() }.getOrDefault(0L) else 0L
        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if (track) actions = actions or PlaybackState.ACTION_SEEK_TO
        session?.setPlaybackState(
            PlaybackState.Builder().setActions(actions).setState(status, position, if (s.playing) 1f else 0f)
                .apply { if (s.error != null) setErrorMessage(s.error) }.build(),
        )
    }

    /** Lo que dicen las etiquetas de la canción (null = nada o vacío: se queda lo que ya se sabía). */
    private class Tags(val title: String?, val artist: String?, val album: String?)

    private fun tagsOf(track: Track): Tags? = runCatching {
        // Ya vienen de MediaStore (con artista): no hace falta leer el archivo otra vez.
        if (track.artist.isNotBlank()) return null
        MediaMetadataRetriever().let { r ->
            try {
                r.setDataSource(app, track.uri)
                fun tag(key: Int) = r.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }
                Tags(tag(MediaMetadataRetriever.METADATA_KEY_TITLE), tag(MediaMetadataRetriever.METADATA_KEY_ARTIST), tag(MediaMetadataRetriever.METADATA_KEY_ALBUM))
            } finally {
                r.release()
            }
        }
    }.getOrNull()?.takeIf { it.title != null || it.artist != null }

    /** Carátula del archivo (etiqueta incrustada) o el logo de la estación, reducida a [ART_SIDE]. Solo en [art]. */
    private fun artworkOf(item: Playable): Bitmap? = runCatching {
        if (item.key in noArt) return null
        val bytes = when (item) {
            is Track -> MediaMetadataRetriever().let { r -> try { r.setDataSource(app, item.uri); r.embeddedPicture } finally { r.release() } }
            is Station -> item.logoUrl?.let { Radio.fetchBytes(app, it, maxBytes = 512 * 1024) }
        // Solo las canciones: el logo de una estación pudo no llegar por la red y se vuelve a pedir.
        } ?: return null.also { if (item is Track) noArt += item.key }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > ART_SIDE * 2 || bounds.outHeight / sample > ART_SIDE * 2) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    // --- Foco de audio: pausar con llamadas o avisos y bajar el volumen con la navegación. ---

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> { pause(); abandonFocus() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                val wasPlaying = state.playing
                pause()
                resumeAfterFocus = wasPlaying
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> { player?.setVolume(DUCK_VOLUME, DUCK_VOLUME); aac?.setVolume(DUCK_VOLUME); ducked = true }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (ducked) { player?.setVolume(1f, 1f); aac?.setVolume(1f); ducked = false }
                if (resumeAfterFocus) { resumeAfterFocus = false; resume() }
            }
        }
    }

    private fun requestFocus(): Boolean {
        val audio = app.getSystemService(AudioManager::class.java) ?: return true
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setOnAudioFocusChangeListener(focusListener, main)
                .build().also { focus = it }
            audio.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        return (granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED).also { focusGranted = it }
    }

    /** Lo que contestó Android la última vez que se pidió el foco; null = aún no se pide. */
    @Volatile private var focusGranted: Boolean? = null

    /** Para los reportes: qué hace el reproductor, su error y si tiene el foco. */
    fun describe(): String {
        val s = state
        val doing = when {
            s.buffering -> "carga"
            s.playing -> "suena"
            s.current != null -> "en pausa"
            else -> "nada"
        }
        val focus = focusGranted?.let { if (it) "sí" else "no" } ?: "-"
        return "reproductor=$doing${s.source?.let { " (${it.name.lowercase()})" }.orEmpty()} error=${s.error ?: "-"} foco=$focus"
    }

    private fun abandonFocus() {
        val audio = app.getSystemService(AudioManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) focus?.let { audio.abandonAudioFocusRequest(it) }
        else @Suppress("DEPRECATION") audio.abandonAudioFocus(focusListener)
    }

    // --- Si sacan la memoria de la canción mientras suena, se para (sin ella no hay archivo). ---

    private fun watchUsb() {
        if (usbWatcher != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                Drives.changed()
                if (i.action == Intent.ACTION_MEDIA_MOUNTED) return
                // Otra memoria o la SD que no es la de la canción: sigue sonando.
                val track = state.current as? Track ?: return
                if (track.onVolume(i.data?.path)) stop()
            }
        }
        ContextCompat.registerReceiver(app, receiver, Drives.mediaFilter(), ContextCompat.RECEIVER_EXPORTED)
        usbWatcher = receiver
    }

    private fun unwatchUsb() {
        usbWatcher?.let { runCatching { app.unregisterReceiver(it) } }
        usbWatcher = null
    }
}
