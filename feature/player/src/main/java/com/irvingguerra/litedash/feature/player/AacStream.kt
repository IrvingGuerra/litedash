package com.irvingguerra.litedash.feature.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import com.irvingguerra.litedash.core.ui.Https
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

/** Frecuencias de muestreo del índice de 4 bits de la cabecera ADTS. */
private val ADTS_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350, 0, 0, 0)

/** Un cuadro AAC-LC comprimido nunca pasa de esto (13 bits en la cabecera). */
private const val MAX_FRAME = 8192

/** Cuánto esperar un dato del decodificador antes de volver a intentar (microsegundos). */
private const val CODEC_WAIT_US = 10_000L

/**
 * Radio AAC en vivo (ADTS: el formato de casi todas las emisoras de México y de
 * StreamTheWorld). `MediaPlayer` no arranca con estas transmisiones (quiere recorrer el
 * archivo entero para prepararse y una transmisión no se acaba), así que aquí se hace a
 * mano, con lo que ya trae Android: se leen los cuadros ADTS del flujo HTTP, los decodifica
 * `MediaCodec` y el PCM va a un `AudioTrack`. Soporta AAC-LC y HE-AAC (AAC+): el
 * decodificador avisa cuando cambia el formato de salida (SBR duplica la frecuencia) y la
 * pista de audio se rearma. Todo corre en su propio hilo; [Listener] recibe los avisos en
 * ese hilo (quien lo use los pasa al principal).
 */
class AacStream(private val context: Context, private val url: String, private val listener: Listener) {
    interface Listener {
        /** Ya suena (el primer PCM salió). */
        fun onStarted()
        /** El flujo no es AAC ADTS: que lo intente [android.media.MediaPlayer]. */
        fun onNotAac()
        fun onError(message: String)
    }

    @Volatile private var closed = false
    @Volatile private var volume = 1f
    private var thread: Thread? = null

    fun start() {
        thread = Thread({ run() }, "aac-stream").also { it.start() }
    }

    fun stop() {
        closed = true
        // Callado al momento: el hilo puede tardar en salir (lectura de red o búfer de audio lleno).
        volume = 0f
        runCatching { track?.setVolume(0f) }
        thread?.interrupt()
    }

    fun setVolume(v: Float) {
        volume = v
        track?.setVolume(v)
    }

    @Volatile private var track: AudioTrack? = null

    private fun run() {
        var codec: MediaCodec? = null
        try {
            val connection = Https.open(context, url).apply {
                connectTimeout = 8_000
                readTimeout = 12_000
                setRequestProperty("Icy-MetaData", "0")
                setRequestProperty("User-Agent", "LiteDash")
            }
            if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode}")
            val type = connection.contentType.orEmpty().lowercase()
            if (type.isNotEmpty() && !type.contains("aac") && !type.contains("mp4") && !type.contains("octet")) {
                connection.disconnect()
                listener.onNotAac()
                return
            }
            connection.inputStream.use { input -> decode(input) { codec = it } }
        } catch (x: InterruptedException) {
            // stop(): nada que avisar.
        } catch (x: Exception) {
            if (!closed) listener.onError(x.message ?: x.javaClass.simpleName)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            track?.let { runCatching { it.stop() }; it.release() }
            track = null
        }
    }

    private fun decode(input: InputStream, created: (MediaCodec) -> Unit) {
        val reader = AdtsReader(input)
        val first = reader.next() ?: throw IOException("Sin audio en la transmisión")
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, first.sampleRate, first.channels).apply {
            // AudioSpecificConfig de 2 bytes: tipo de objeto (5 bits), frecuencia (4), canales (4).
            val config = (first.objectType shl 11) or (first.rateIndex shl 7) or (first.channels shl 3)
            setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf((config shr 8).toByte(), config.toByte())))
            setInteger(MediaFormat.KEY_IS_ADTS, 0)
        }
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        created(codec)
        codec.configure(format, null, null, 0)
        codec.start()
        val info = MediaCodec.BufferInfo()
        var pending: AdtsFrame? = first
        var started = false
        while (!closed) {
            // Entrada: un cuadro por búfer libre.
            if (pending == null) pending = reader.next() ?: throw IOException("Se cortó la transmisión")
            val inIndex = codec.dequeueInputBuffer(CODEC_WAIT_US)
            if (inIndex >= 0) {
                val buffer = codec.getInputBuffer(inIndex) ?: continue
                buffer.clear()
                buffer.put(pending.data, 0, pending.size)
                codec.queueInputBuffer(inIndex, 0, pending.size, 0, 0)
                pending = null
            }
            // Salida: PCM a la pista.
            when (val outIndex = codec.dequeueOutputBuffer(info, CODEC_WAIT_US)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> rebuildTrack(codec.outputFormat)
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                else -> if (outIndex >= 0) {
                    val out = codec.getOutputBuffer(outIndex)
                    if (out != null && info.size > 0) {
                        val t = track ?: rebuildTrack(codec.outputFormat)
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        t.write(out, info.size, AudioTrack.WRITE_BLOCKING)
                        if (!started) {
                            started = true
                            listener.onStarted()
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                }
            }
        }
    }

    /** Pista de audio para el formato que entrega el decodificador (cambia con HE-AAC). */
    private fun rebuildTrack(format: MediaFormat): AudioTrack {
        track?.let { runCatching { it.stop() }; it.release() }
        val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val mask = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
            AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
            // Medio segundo de margen: la red llega a saltos.
            maxOf(min * 2, rate * channels * 2 / 2),
            AudioTrack.MODE_STREAM,
            android.media.AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        t.setVolume(volume)
        t.play()
        track = t
        return t
    }
}

/** Un cuadro AAC ya sin cabecera, con lo que dice la cabecera. */
private class AdtsFrame(val data: ByteArray, val size: Int, val objectType: Int, val rateIndex: Int, val sampleRate: Int, val channels: Int)

/**
 * Lee cuadros ADTS de un flujo: busca la palabra de sincronía (12 bits a 1), lee la
 * cabecera de 7 bytes (9 con CRC) y el cuadro que sigue. Un cuadro mal formado se salta.
 */
private class AdtsReader(private val input: InputStream) {
    private val header = ByteArray(9)
    private val data = ByteArray(MAX_FRAME)

    fun next(): AdtsFrame? {
        while (true) {
            if (!sync()) return null
            if (!fill(header, 2, 5)) return null
            val protectionAbsent = header[1].toInt() and 1
            val objectType = ((header[2].toInt() shr 6) and 3) + 1
            val rateIndex = (header[2].toInt() shr 2) and 0xF
            val channels = ((header[2].toInt() and 1) shl 2) or ((header[3].toInt() shr 6) and 3)
            val frameLength = ((header[3].toInt() and 3) shl 11) or ((header[4].toInt() and 0xFF) shl 3) or ((header[5].toInt() and 0xFF) shr 5)
            val headerSize = if (protectionAbsent == 1) 7 else 9
            val size = frameLength - headerSize
            val sampleRate = ADTS_RATES[rateIndex]
            if (size <= 0 || size > MAX_FRAME || sampleRate == 0 || channels == 0) continue
            if (headerSize == 9 && !fill(header, 7, 2)) return null
            if (!fill(data, 0, size)) return null
            return AdtsFrame(data, size, objectType, rateIndex, sampleRate, channels)
        }
    }

    /** Avanza hasta 0xFFF (deja los dos primeros bytes de la cabecera en [header]). */
    private fun sync(): Boolean {
        var a = input.read()
        while (a >= 0) {
            if (a == 0xFF) {
                val b = input.read()
                if (b < 0) return false
                if (b and 0xF6 == 0xF0) {
                    header[0] = a.toByte()
                    header[1] = b.toByte()
                    return true
                }
                a = b
                continue
            }
            a = input.read()
        }
        return false
    }

    private fun fill(buffer: ByteArray, offset: Int, length: Int): Boolean {
        var done = 0
        while (done < length) {
            val n = input.read(buffer, offset + done, length - done)
            if (n < 0) return false
            done += n
        }
        return true
    }
}
